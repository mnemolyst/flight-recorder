package com.mnemolyst.flightRecorder;

import android.Manifest;
import android.app.IntentService;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

import static java.lang.Math.PI;
import static java.lang.Math.abs;
import static java.lang.Math.max;
import static java.lang.Math.sin;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 */
public class RecordService extends Service {

    private final static String TAG = "RecordService";
    private final static int ONGOING_NOTIFICATION_ID = 1;

    private final Object lockObj = new Object();

    enum RecordState {
        STOPPING, STOPPED, STARTING, STARTED
    }
    enum DownAxis {
        NONE, X_POS, X_NEG, Y_POS, Y_NEG
    }
    enum VideoQuality {
        HIGH_1080P, MED_720P
    }
    enum TipoverThreshold {
        NORMAL, HIGH
    }
    private RecordState recordState;
    private DownAxis downAxis = DownAxis.NONE;
    private static VideoQuality videoQuality;
    private static TipoverThreshold tipoverThreshold;
    private String cameraId = "0";
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession captureSession;
    private boolean videoSize1080pAvailable = false;
    private boolean videoSize720pAvailable = false;
    private MediaCodec videoCodec;
    private Surface videoInputSurface;
    private MediaCodec audioCodec;
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private ArrayList<BufferDataInfoPair> videoBufferList = new ArrayList<>();
    private ArrayList<BufferDataInfoPair> audioBufferList = new ArrayList<>();

    private AudioRecord audioRecord;
    private int audioSampleRate = 48000;
    private int audioChunkBytes = audioSampleRate * 2 / 24;
    private long calPtDiff = -1;

    private static boolean saveOnTipover = true;
    private static boolean recordAudio = true;
    private static boolean saveLocation = false;

    private SQLiteOpenHelper videoSqlHelper;
    private SQLiteDatabase videoDb = null;
    private SQLiteOpenHelper audioSqlHelper;
    private SQLiteDatabase audioDb = null;
    private static int recordDuration = 15_000_000;
    private Integer sensorOrientation = 0;

    // Gravity sensor event listener variables
    private final double G_THRESHOLD_NORMAL = SensorManager.GRAVITY_EARTH * sin(PI / 4);
    private final double G_THRESHOLD_HIGH = SensorManager.GRAVITY_EARTH * sin(PI / 6);
    private final long TIME_THRESHOLD = (long) 2e9; // 2 seconds
    private long timeAxisDown = 0;
    private long timeTipped = 0;
    private boolean orientationLocked = false;

    private RecordServiceBinder binder = new RecordServiceBinder();

    private OnStopRecordCallback onStopRecordCallback = null;
    private OnOrientationLockedCallback onOrientationLockedCallback = null;

    static abstract class OnStopRecordCallback {
        abstract void onStopRecord();
    }

    static abstract class OnOrientationLockedCallback {
        abstract void onOrientationLocked();
    }

    class RecordServiceBinder extends Binder {

        RecordService getService() {

            return RecordService.this;
        }
    }

    private class EncodedVideoContract {

        class Schema implements BaseColumns {
            static final String TABLE_NAME = "encoded_video";
            static final String VIDEO_DATA_COLUMN_NAME = "video_data";
        }
    }

    private class EncodedAudioContract {

        class Schema implements BaseColumns {
            static final String TABLE_NAME = "encoded_audio";
            static final String AUDIO_DATA_COLUMN_NAME = "audio_data";
        }
    }

    private class EncodedVideoHelper extends SQLiteOpenHelper {

        static final int DATABASE_VERSION = 1;
        static final String DATABASE_NAME = "EncodedVideo.db";

        private static final String SQL_CREATE_TABLES =
                "CREATE TABLE " + EncodedVideoContract.Schema.TABLE_NAME + " (" +
                        EncodedVideoContract.Schema._ID + " INTEGER PRIMARY KEY, " +
                        EncodedVideoContract.Schema.VIDEO_DATA_COLUMN_NAME + " BLOB)";

        EncodedVideoHelper(Context context) {

            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {

            db.execSQL(SQL_CREATE_TABLES);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        }
    }

    private class EncodedAudioHelper extends SQLiteOpenHelper {

        static final int DATABASE_VERSION = 1;
        static final String DATABASE_NAME = "EncodedAudio.db";

        private static final String SQL_CREATE_TABLES =
                "CREATE TABLE " + EncodedAudioContract.Schema.TABLE_NAME + " (" +
                        EncodedAudioContract.Schema._ID + " INTEGER PRIMARY KEY, " +
                        EncodedAudioContract.Schema.AUDIO_DATA_COLUMN_NAME + " BLOB)";

        EncodedAudioHelper(Context context) {

            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {

            db.execSQL(SQL_CREATE_TABLES);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {

        }
    }

    void startRecording() {

        new Thread(new Runnable() {

            public void run() {

                openCamera();
            }
        }).run();
    }

    @Override
    public IBinder onBind(Intent intent) {

        recordState = RecordState.STOPPED;

        MainActivity.googleApiClient.connect();

        videoSqlHelper = new EncodedVideoHelper(this);
        videoDb = videoSqlHelper.getWritableDatabase();

        audioSqlHelper = new EncodedAudioHelper(this);
        audioDb = audioSqlHelper.getWritableDatabase();

        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {

        Log.d(TAG, "onUnbind");
        stopRecording();
        MainActivity.googleApiClient.disconnect();

        videoDb.close();
        audioDb.close();

        return false;
    }

    public void registerOnStopRecordCallback(OnStopRecordCallback callback) {

        onStopRecordCallback = callback;
    }

    public void registerOnOrientationLockedCallback(OnOrientationLockedCallback callback) {

        onOrientationLockedCallback = callback;
    }

    private void openCamera() {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {

                @Override
                public void onOpened(@NonNull CameraDevice camera) {

                    cameraDevice = camera;
                    prepareForRecording();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {

                    Log.d(TAG, "cameraStateCallback.onDisconnected");
                    stopRecording();
                }

                @Override
                public void onError(@NonNull CameraDevice camera, int error) {
                    Log.e(TAG, "cameraStateCallback.onError");
                }

            }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void prepareForRecording() {

        videoFormat = null;
        audioFormat = null;

        videoBufferList = new ArrayList<>();
        audioBufferList = new ArrayList<>();

        videoDb.delete(EncodedVideoContract.Schema.TABLE_NAME, null, null);
        audioDb.delete(EncodedAudioContract.Schema.TABLE_NAME, null, null);

        try {
            prepareVideoCodec();
            prepareAudioCodec();
        } catch (IOException e) {
            e.printStackTrace();
        }
        startGravitySensor();
        notifyForeground();
    }

    private void prepareVideoCodec() throws IOException {

        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null");
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics characteristics = null;
        try {
            characteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] codecOutputSizes = map.getOutputSizes(MediaCodec.class);
        for (Size s : codecOutputSizes) {
            Log.d(TAG, String.valueOf(s.getWidth()) + ", " + String.valueOf(s.getHeight()));
        }
        sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
//        Log.d(TAG, String.valueOf(sensorOrientation));

        MediaFormat format = null;
        if (videoQuality == VideoQuality.HIGH_1080P) {
            format = MediaFormat.createVideoFormat("video/avc", 1920, 1080);
        } else if (videoQuality == VideoQuality.MED_720P) {
            format = MediaFormat.createVideoFormat("video/avc", 1280, 720);
        }

        if (format == null) {
            Log.e(TAG, "No suitable video resolution found.");
            return;
        }

        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 5000000);
        format.setString(MediaFormat.KEY_FRAME_RATE, null);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 0);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findEncoderForFormat(format);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);

        videoCodec = MediaCodec.createByCodecName(codecName);
        videoCodec.setCallback(videoCodecCallback);
        videoCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        videoInputSurface = videoCodec.createInputSurface();
        videoCodec.start();

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.addTarget(videoInputSurface);
            cameraDevice.createCaptureSession(Arrays.asList(videoInputSurface), captureSessionCallback, null);

            recordState = RecordState.STARTING;

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void prepareAudioCodec() throws IOException {

        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", audioSampleRate, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findEncoderForFormat(format);

        audioCodec = MediaCodec.createByCodecName(codecName);
        audioCodec.setCallback(audioCodecCallback);
        audioCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
//        audioCodec.start();

        int minAudioBufferSize = AudioRecord.getMinBufferSize(
                audioSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        int audioBufferSize = max(minAudioBufferSize, audioChunkBytes * 2);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                audioSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                audioBufferSize);

        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.startRecording();
        } else {
            Log.e(TAG, "audioRecord unitialized");
        }
    }

    private MediaCodec.Callback videoCodecCallback = new MediaCodec.Callback() {

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                return;
            }

            if (recordState == RecordState.STARTING) {

                recordState = RecordState.STARTED;
                calPtDiff = info.presentationTimeUs - Calendar.getInstance().getTimeInMillis() * 1000;
                audioCodec.start();
            }

            // Once video capture begins, start audio capture.

            ByteBuffer outputBuffer = codec.getOutputBuffer(index);

            if (outputBuffer == null) {
                return;
            }

            byte[] bufferBytes = new byte[outputBuffer.remaining()];
            outputBuffer.get(bufferBytes);

            ContentValues contentValues = new ContentValues();
            contentValues.put(EncodedVideoContract.Schema.VIDEO_DATA_COLUMN_NAME, bufferBytes);
            long insertedId = videoDb.insert(EncodedVideoContract.Schema.TABLE_NAME, null, contentValues);

            BufferDataInfoPair dataInfoPair = new BufferDataInfoPair(insertedId, info);
            videoBufferList.add(dataInfoPair);

            codec.releaseOutputBuffer(index, false);

//            Log.d(TAG, "Frames: " + String.valueOf(videoBufferList.size()));

            /*if (audioBufferList.size() > 0) {
                Log.d(TAG, "min video pt: " + String.valueOf(videoBufferList.get(0).getBufferInfo().presentationTimeUs));
                Log.d(TAG, "max video pt: " + String.valueOf(videoBufferList.get(videoBufferList.size() - 1).getBufferInfo().presentationTimeUs));
                Log.d(TAG, "video del: " + String.valueOf(videoBufferList.get(videoBufferList.size() - 1).getBufferInfo().presentationTimeUs - videoBufferList.get(0).getBufferInfo().presentationTimeUs));
                Log.d(TAG, "min audio pt: " + String.valueOf(audioBufferList.get(0).getBufferInfo().presentationTimeUs));
                Log.d(TAG, "max audio pt: " + String.valueOf(audioBufferList.get(audioBufferList.size() - 1).getBufferInfo().presentationTimeUs));
                Log.d(TAG, "audio del: " + String.valueOf(audioBufferList.get(audioBufferList.size() - 1).getBufferInfo().presentationTimeUs - audioBufferList.get(0).getBufferInfo().presentationTimeUs));
            }*/

            // Discard old buffers
            long duration = 0;
            if (videoBufferList.size() >= 2) {
                duration = videoBufferList.get(videoBufferList.size() - 1).getBufferInfo().presentationTimeUs
                        - videoBufferList.get(0).getBufferInfo().presentationTimeUs;
            }

            if (duration > recordDuration) {

                long minVideoTs = videoBufferList.get(videoBufferList.size() - 1).getBufferInfo().presentationTimeUs - recordDuration;
                int minVideoIdx = 0;
                for (int i = 0; i < videoBufferList.size(); i++) {

                    BufferDataInfoPair pair = videoBufferList.get(i);

                    if ((pair.getBufferInfo().presentationTimeUs > minVideoTs)
                            && ((pair.getBufferInfo().flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) == MediaCodec.BUFFER_FLAG_KEY_FRAME)) {
                        minVideoIdx = i;
                        break;
                    }
                }

                long minVideoId = videoBufferList.get(minVideoIdx).getDataId();
                long minPresentationTime = videoBufferList.get(minVideoIdx).getBufferInfo().presentationTimeUs;
                videoDb.delete(
                        EncodedVideoContract.Schema.TABLE_NAME,
                        EncodedVideoContract.Schema._ID + " < ?",
                        new String[] { String.valueOf(minVideoId) }
                );

                int minAudioIdx = 0;
                for (int i = 0; i < audioBufferList.size(); i++) {
                    if (audioBufferList.get(i).getBufferInfo().presentationTimeUs >= minPresentationTime) {
                        minAudioIdx = i;
                        break;
                    }
                }

                long minAudioId = audioBufferList.get(minAudioIdx).getDataId();
                audioDb.delete(
                        EncodedAudioContract.Schema.TABLE_NAME,
                        EncodedAudioContract.Schema._ID + " < ?",
                        new String[] { String.valueOf(minAudioId) }
                );

                videoBufferList.subList(0, minVideoIdx).clear();
                audioBufferList.subList(0, minAudioIdx).clear();
            }


            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                Log.d(TAG, "videoCodec EOS");
            }
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
            Log.e(TAG, "MediaCodec.Callback.onError", e);
        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

            if (videoFormat == null) {
                Log.d(TAG, "Video format changed");
                videoFormat = format;
            } else {
                Log.e(TAG, "Video format already changed");
            }
        }
    };

    private MediaCodec.Callback audioCodecCallback = new MediaCodec.Callback() {

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {

            switch (recordState) {
                case STARTING:
                    break;
                case STARTED:
                    ByteBuffer inputBuffer = codec.getInputBuffer(index);

                    if (inputBuffer == null) {
                        return;
                    }
                    int size = audioRecord.read(inputBuffer, audioChunkBytes);
                    long presentationTime = Calendar.getInstance().getTimeInMillis() * 1000 + calPtDiff;
                    codec.queueInputBuffer(index, 0, size, presentationTime, 0);
                    break;
                case STOPPING:
                case STOPPED:
                    codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    break;
            }
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                return;
            }

            ByteBuffer outputBuffer = codec.getOutputBuffer(index);

            if (outputBuffer == null) {
                return;
            }

            byte[] bufferBytes = new byte[outputBuffer.remaining()];
            outputBuffer.get(bufferBytes);

            ContentValues contentValues = new ContentValues();
            contentValues.put(EncodedAudioContract.Schema.AUDIO_DATA_COLUMN_NAME, bufferBytes);
            long insertedId = audioDb.insert(EncodedAudioContract.Schema.TABLE_NAME, null, contentValues);

            BufferDataInfoPair dataInfoPair = new BufferDataInfoPair(insertedId, info);
            audioBufferList.add(dataInfoPair);

            codec.releaseOutputBuffer(index, false);
        }

        @Override
        public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {

        }

        @Override
        public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

            if (audioFormat == null) {
                Log.d(TAG, "Audio format changed");
                audioFormat = format;
            } else {
                Log.e(TAG, "Audio format already changed");
            }
        }
    };

    private CameraCaptureSession.StateCallback captureSessionCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            captureSession = session;

            /*HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            Handler backgroundHandler = new Handler(thread.getLooper());*/

            try {
                session.setRepeatingRequest(captureRequestBuilder.build(), null, null);
//                recordState = RecordState.STARTED;
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "captureSessionCallback.onConfigureFailed");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {

            Log.d(TAG, "session.onClosed");

            recordingStopped();
        }
    };

    private void startGravitySensor() {

        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_GRAVITY);
        if (sensorList.isEmpty()) {
            sensorList = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        }
        sensorManager.registerListener(sensorEventListener, sensorList.get(0), 1_000_000);
    }

    private SensorEventListener sensorEventListener = new SensorEventListener() {

        @Override
        public void onSensorChanged(SensorEvent event) {

            /*for (float i : event.values) {
                Log.d(TAG, String.valueOf(i));
            }
            Log.d(TAG, "ol: " + String.valueOf(orientationLocked));
            Log.d(TAG, "da: " + String.valueOf(downAxis));
            Log.d(TAG, "tad: " + String.valueOf(timeAxisDown));
            Log.d(TAG, "diff: " + String.valueOf(event.timestamp - timeAxisDown));*/

            double gThreshold;
            if (tipoverThreshold.equals(TipoverThreshold.NORMAL)) {
                gThreshold = G_THRESHOLD_NORMAL;
            } else {
                gThreshold = G_THRESHOLD_HIGH;
            }

            if (orientationLocked) {

                if ((downAxis == DownAxis.X_POS && event.values[0] < gThreshold)
                        || (downAxis == DownAxis.X_NEG && event.values[0] > -gThreshold)
                        || (downAxis == DownAxis.Y_POS && event.values[1] < gThreshold)
                        || (downAxis == DownAxis.Y_NEG && event.values[1] > -gThreshold)) {

                    if (event.timestamp - timeTipped >= TIME_THRESHOLD) {

                        if (saveOnTipover) {
                            stopRecording();
                        }
                        orientationLocked = false;
                        downAxis = DownAxis.NONE;
                    }
                } else {
                    timeTipped = event.timestamp;
                }
            } else {

                boolean checkForLock = false;

                if (event.values[0] > gThreshold) {
                    if (downAxis == DownAxis.X_POS) {
                        checkForLock = true;
                    } else {
                        downAxis = DownAxis.X_POS;
                        timeAxisDown = event.timestamp;
                    }
                } else if (event.values[0] < -gThreshold) {
                    if (downAxis == DownAxis.X_NEG) {
                        checkForLock = true;
                    } else {
                        downAxis = DownAxis.X_NEG;
                        timeAxisDown = event.timestamp;
                    }
                } else if (event.values[1] > gThreshold) {
                    if (downAxis == DownAxis.Y_POS) {
                        checkForLock = true;
                    } else {
                        downAxis = DownAxis.Y_POS;
                        timeAxisDown = event.timestamp;
                    }
                } else if (event.values[1] < -gThreshold) {
                    if (downAxis == DownAxis.Y_NEG) {
                        checkForLock = true;
                    } else {
                        downAxis = DownAxis.Y_NEG;
                        timeAxisDown = event.timestamp;
                    }
                }

                if (checkForLock && event.timestamp - timeAxisDown >= TIME_THRESHOLD) {
                    orientationLocked = true;
                    onOrientationLockedCallback.onOrientationLocked();
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    };

    private void notifyForeground() {

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);

        Notification notification = new Notification.Builder(this)
                .setContentTitle(getText(R.string.notification_title))
                .setContentText(getText(R.string.notification_message))
                .setSmallIcon(R.drawable.recording_notification_icon)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(ONGOING_NOTIFICATION_ID, notification);
    }

    private void resetOrientation() {
        downAxis = DownAxis.NONE;
        timeAxisDown = 0;
        timeTipped = 0;
        orientationLocked = false;
    }

    @Override
    public void onDestroy() {

    }

    RecordState getRecordState() {
        return recordState;
    }

    void stopRecording() {

        if (recordState.equals(RecordState.STARTED)) {

            recordState = RecordState.STOPPING;

            videoCodec.signalEndOfInputStream();

            try {
                captureSession.abortCaptures();
                captureSession.close();
            } catch (CameraAccessException | IllegalStateException e) {
                e.printStackTrace();
                recordingStopped();
            }

            audioRecord.stop();
            audioRecord.release();
        }
    }

    void recordingStopped() {


        boolean saved = dumpBuffersToFile();

        cleanUp();

        recordState = RecordState.STOPPED;

        stopForeground(true);

        if (onStopRecordCallback != null) {
            onStopRecordCallback.onStopRecord();
        }
    }

    boolean dumpBuffersToFile() {

        String state = Environment.getExternalStorageState();

        if (Environment.MEDIA_MOUNTED.equals(state)
                && ActivityCompat.checkSelfPermission(RecordService.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {

            File savedFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "test.mp4");
            String filePath = savedFile.getAbsolutePath();

            MediaMuxer mediaMuxer = null;
            try {
                mediaMuxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            } catch (IOException e) {
                e.printStackTrace();
            }

            if (mediaMuxer == null) {
                return false;
            }

            int videoTrackIdx = mediaMuxer.addTrack(videoFormat);

            int audioTrackIdx = -1;
            if (recordAudio) {
                audioTrackIdx = mediaMuxer.addTrack(audioFormat);
            }

            mediaMuxer.setOrientationHint(sensorOrientation);
            mediaMuxer.start();

            long latestPt = 0;
            for (BufferDataInfoPair dataInfoPair : videoBufferList) {

                long bufferId = dataInfoPair.getDataId();
                MediaCodec.BufferInfo info = dataInfoPair.getBufferInfo();
                if (info.presentationTimeUs > latestPt) {
                    latestPt = info.presentationTimeUs;
                } else {
                    continue;
                }

                Cursor cursor = videoDb.query(EncodedVideoContract.Schema.TABLE_NAME,
                        null,
                        EncodedVideoContract.Schema._ID + " = " + String.valueOf(bufferId),
                        null, null, null,
                        EncodedVideoContract.Schema._ID + " ASC");

                if (! cursor.moveToFirst()) {
                    continue;
                }

                ByteBuffer buffer = ByteBuffer.wrap(cursor.getBlob(
                        cursor.getColumnIndex(EncodedVideoContract.Schema.VIDEO_DATA_COLUMN_NAME)
                ));

                cursor.close();

                mediaMuxer.writeSampleData(videoTrackIdx, buffer, info);

                /*Log.d(TAG, "offset:" + String.valueOf(info.offset) +
                        " size:" + String.valueOf(info.size) +
                        " pt:" + String.valueOf(info.presentationTimeUs) +
                        " codec config:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) +
                        " keyframe:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) +
                        " EOS:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM));*/
            }

            if (recordAudio) {

                latestPt = 0;
                for (BufferDataInfoPair dataInfoPair : audioBufferList) {

                    long bufferId = dataInfoPair.getDataId();
                    MediaCodec.BufferInfo info = dataInfoPair.getBufferInfo();
                    if (info.presentationTimeUs > latestPt) {
                        latestPt = info.presentationTimeUs;
                    } else {
                        continue;
                    }

                    Cursor cursor = audioDb.query(EncodedAudioContract.Schema.TABLE_NAME,
                            null,
                            EncodedAudioContract.Schema._ID + " = " + String.valueOf(bufferId),
                            null, null, null,
                            EncodedAudioContract.Schema._ID + " ASC");

                    if (!cursor.moveToFirst()) {
                        continue;
                    }

                    ByteBuffer buffer = ByteBuffer.wrap(cursor.getBlob(
                            cursor.getColumnIndex(EncodedAudioContract.Schema.AUDIO_DATA_COLUMN_NAME)
                    ));

                    cursor.close();

                    mediaMuxer.writeSampleData(audioTrackIdx, buffer, info);
                }
            }

            mediaMuxer.stop();
            mediaMuxer.release();

            return true;
        } else {
            Log.e(TAG, "External media unavailable: " + state);

            return false;
        }
    }

    private void cleanUp() {

        videoCodec.stop();
        videoCodec.release();
        videoInputSurface.release();

        audioCodec.stop();
        audioCodec.release();

        cameraDevice.close();

        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        sensorManager.unregisterListener(sensorEventListener);
        resetOrientation();
    }

    public static void setRecordDuration(int recordDuration) {
        Log.d(TAG, "setRecordDuration: " + String.valueOf(recordDuration));
        RecordService.recordDuration = recordDuration * 1_000_000;
    }

    public static void setVideoQuality(String pref, String q1080p, String q720p) {
        Log.d(TAG, "setVideoQuality: " + pref);
        if (pref.equals(q1080p)) {
            RecordService.videoQuality = VideoQuality.HIGH_1080P;
        } else if (pref.equals(q720p)) {
            RecordService.videoQuality = VideoQuality.MED_720P;
        }
    }

    public static void setSaveOnTipover(boolean saveOnTipover) {
        Log.d(TAG, "setSaveOnTipover: " + String.valueOf(saveOnTipover));
        RecordService.saveOnTipover = saveOnTipover;
    }

    public static void setTipoverThreshold(String pref, String normal, String high) {
        Log.d(TAG, "setTipoverThreshold: " + pref);
        if (pref.equals(normal)) {
            RecordService.tipoverThreshold = TipoverThreshold.NORMAL;
        } else if (pref.equals(high)) {
            RecordService.tipoverThreshold = TipoverThreshold.HIGH;
        }
    }

    public static void setRecordAudio(boolean recordAudio) {
        Log.d(TAG, "setRecordAudio: " + String.valueOf(recordAudio));
        RecordService.recordAudio = recordAudio;
    }

    public static void setSaveLocation(boolean saveLocation) {
        Log.d(TAG, "setSaveLocation: " + String.valueOf(saveLocation));
        RecordService.saveLocation = saveLocation;
    }

    public void setCameraId(String cameraId) {
        this.cameraId = cameraId;
    }
}
