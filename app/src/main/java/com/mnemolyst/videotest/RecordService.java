package com.mnemolyst.videotest;

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
import java.util.List;

import static java.lang.Math.abs;
import static java.lang.Math.sqrt;

/**
 * An {@link IntentService} subclass for handling asynchronous task requests in
 * a service on a separate handler thread.
 */
public class RecordService extends Service {

    private final static String TAG = "RecordService";
    private final static int ONGOING_NOTIFICATION_ID = 1;

    private final Object lockObj = new Object();

    enum VideoRecordState {
        STOPPING, STOPPED, STARTING, STARTED
    }
    private VideoRecordState videoRecordState;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession captureSession;
    private MediaCodec videoCodec;
    private Surface videoInputSurface;
    private MediaCodec audioCodec;
    private MediaFormat videoFormat;
    private MediaFormat audioFormat;
    private ArrayList<BufferDataInfoPair> bufferList = new ArrayList<>();
    private long lastPresentationTime = 0;

    private AudioRecord audioRecord;
    private int audioSampleRate = 48000;
    private int audioFrameBytes = audioSampleRate * 2 / 30;
    private ByteBuffer audioBufferAcc;

    private SQLiteOpenHelper sqLiteOpenHelper = null;
    private SQLiteDatabase db = null;
    private int sqlEncodedFramesId = 1;
    private int maxFrames = 30*5;
    private Integer sensorOrientation = 0;

    // Gravity sensor event listener variables
    private final double G_THRESHOLD = SensorManager.GRAVITY_EARTH * sqrt(2) / 2;
    private final int X_AXIS = 0;
    private final int Y_AXIS = 1;
    private int downAxis = -1;
    private long timeAxisDown = 0;
    private boolean orientationLocked = false;

    private RecordServiceBinder binder = new RecordServiceBinder();

    private OnStopRecordCallback onStopRecordCallback = null;

    static abstract class OnStopRecordCallback {

        abstract void onStopRecord();
    }

    class RecordServiceBinder extends Binder {

        RecordService getService() {

            return RecordService.this;
        }
    }

    private class EncodedFramesContract {

        private EncodedFramesContract() {};

        class EncodedFrameEntry implements BaseColumns {
            static final String TABLE_NAME = "encoded_frames";
            static final String FRAME_DATA_COLUMN_NAME = "frame_data";
        }
    }

    private class EncodedFramesHelper extends SQLiteOpenHelper {

        static final int DATABASE_VERSION = 1;
        static final String DATABASE_NAME = "EncodedFrames.db";
        private static final String SQL_CREATE_TABLES =
                "CREATE TABLE " + EncodedFramesContract.EncodedFrameEntry.TABLE_NAME + " (" +
                        EncodedFramesContract.EncodedFrameEntry._ID + " INTEGER PRIMARY KEY, " +
                        EncodedFramesContract.EncodedFrameEntry.FRAME_DATA_COLUMN_NAME + " BLOB)";

        EncodedFramesHelper(Context context) {

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

                doStartRecord();
            }
        }).run();
    }

    @Override
    public void onCreate() {

        videoRecordState = VideoRecordState.STOPPED;
        sqLiteOpenHelper = new EncodedFramesHelper(this);
        db = sqLiteOpenHelper.getWritableDatabase();
    }

    @Override
    public IBinder onBind(Intent intent) {

        return binder;
    }

    @Override
    public boolean onUnbind(Intent intent) {

        stopRecording();
        return false;
    }

    public void registerOnStopRecordCallback(OnStopRecordCallback callback) {

        onStopRecordCallback = callback;
    }

    private void doStartRecord() {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {

                @Override
                public void onOpened(@NonNull CameraDevice camera) {

                    cameraDevice = camera;
                    prepareForRecording();
                }

                @Override
                public void onDisconnected(@NonNull CameraDevice camera) {
                    Log.d(TAG, "cameraStateCallback.onDisconnected");
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

        db.delete(EncodedFramesContract.EncodedFrameEntry.TABLE_NAME, null, null);
        sqlEncodedFramesId = 1;

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
        Log.d(TAG, String.valueOf(sensorOrientation));

        MediaFormat format = null;
        for (Size s : codecOutputSizes) {
            if (s.getHeight() == 720) {
                format = MediaFormat.createVideoFormat("video/avc", s.getWidth(), s.getHeight());
                format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                format.setInteger(MediaFormat.KEY_BIT_RATE, 5000000);
                format.setString(MediaFormat.KEY_FRAME_RATE, null);
                format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                break;
            }
        }

        if (format == null) {
            Log.e(TAG, "No suitable MediaFormat resolution found.");
            return;
        }

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

            videoRecordState = VideoRecordState.STARTING;

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
        audioCodec.start();

        int minAudioBufferSize = AudioRecord.getMinBufferSize(
                audioSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        audioRecord = new AudioRecord(
                MediaRecorder.AudioSource.CAMCORDER,
                audioSampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                2 * minAudioBufferSize);

        if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
            audioRecord.startRecording();
        } else {
            Log.e(TAG, "audioRecord unitialized");
        }
    }

    private void startGravitySensor() {

        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_GRAVITY);
        if (sensorList.isEmpty()) {
            sensorList = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        }
        sensorManager.registerListener(sensorEventListener, sensorList.get(0), 1_000_000);
    }

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

    private MediaCodec.Callback videoCodecCallback = new MediaCodec.Callback() {

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

            Log.d(TAG, "video output");

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                return;
            }

            ByteBuffer outputBuffer = codec.getOutputBuffer(index);

            byte[] bufferBytes = new byte[outputBuffer.remaining()];
            outputBuffer.get(bufferBytes);

            ContentValues contentValues = new ContentValues();
            contentValues.put(EncodedFramesContract.EncodedFrameEntry._ID, sqlEncodedFramesId++);
            contentValues.put(EncodedFramesContract.EncodedFrameEntry.FRAME_DATA_COLUMN_NAME, bufferBytes);
            long insertedId = db.insert(EncodedFramesContract.EncodedFrameEntry.TABLE_NAME, null, contentValues);

            BufferDataInfoPair dataInfoPair = new BufferDataInfoPair(insertedId, info);
            bufferList.add(dataInfoPair);

            lastPresentationTime = info.presentationTimeUs;

//            Log.d(TAG, "Frames: " + String.valueOf(bufferList.size()));

            if (bufferList.size() > maxFrames) {

                int minFrameIdx = bufferList.size() - maxFrames;

                long minFrameId = bufferList.get(minFrameIdx).getDataId();
                db.delete(
                        EncodedFramesContract.EncodedFrameEntry.TABLE_NAME,
                        EncodedFramesContract.EncodedFrameEntry._ID + " < ?",
                        new String[] { String.valueOf(minFrameId) }
                );

                bufferList.subList(0, minFrameIdx).clear();
            }

            Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM encoded_frames", null, null);
            cursor.moveToFirst();
            Log.d(TAG, cursor.getString(0) + " rows");
            cursor.close();

            codec.releaseOutputBuffer(index, false);

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {

                try {
                    captureSession.abortCaptures();
                    captureSession.close();

                    audioRecord.stop();
                    audioRecord.release();
                } catch (CameraAccessException e) {
                    e.printStackTrace();
                }
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

            ByteBuffer buffer = codec.getInputBuffer(index);
            int size = audioRecord.read(buffer, audioFrameBytes);
            int flags = 0;
            if (videoRecordState == VideoRecordState.STOPPING || videoRecordState == VideoRecordState.STOPPED) {
                flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            }
            codec.queueInputBuffer(index, 0, size, lastPresentationTime, flags);
        }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

            Log.d(TAG, "audio output");

            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                return;
            }

            ByteBuffer outputBuffer = codec.getOutputBuffer(index);
            ByteBuffer cloneBuffer = ByteBuffer.allocate(outputBuffer.capacity());
            outputBuffer.rewind();
            cloneBuffer.put(outputBuffer);

            byte[] bufferBytes = new byte[outputBuffer.remaining()];
            outputBuffer.get(bufferBytes);

            synchronized (lockObj) {
                audioBufferAcc.put(outputBuffer);
            }

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
                videoRecordState = VideoRecordState.STARTED;
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
            Log.d(TAG, "Capture session closed");

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
                int videoTrackIdx = mediaMuxer.addTrack(videoFormat);
//                int audioTrackIdx = mediaMuxer.addTrack(audioFormat);

                mediaMuxer.setOrientationHint(sensorOrientation);
                mediaMuxer.start();

                for (BufferDataInfoPair dataInfoPair : bufferList) {

                    long bufferId = dataInfoPair.getDataId();
                    MediaCodec.BufferInfo info = dataInfoPair.getInfo();
                    Cursor cursor = db.query(EncodedFramesContract.EncodedFrameEntry.TABLE_NAME,
                            null,
                            EncodedFramesContract.EncodedFrameEntry._ID + " = " + String.valueOf(bufferId),
                            null,
                            null,
                            null,
                            EncodedFramesContract.EncodedFrameEntry._ID + " ASC");

                    if (! cursor.moveToFirst()) {
                        continue;
                    }

                    ByteBuffer buffer = ByteBuffer.wrap(cursor.getBlob(
                            cursor.getColumnIndex(EncodedFramesContract.EncodedFrameEntry.FRAME_DATA_COLUMN_NAME)
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

                mediaMuxer.stop();
                mediaMuxer.release();

                db.delete(EncodedFramesContract.EncodedFrameEntry.TABLE_NAME, null, null);
            } else {
                Log.e(TAG, "External media unavailable: " + state);
            }

            bufferList = new ArrayList<>();
            videoFormat = null;

            videoCodec.stop();
            videoCodec.release();
            videoInputSurface.release();

            audioCodec.stop();
            audioCodec.release();

            videoRecordState = VideoRecordState.STOPPED;

            SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            sensorManager.unregisterListener(sensorEventListener);
            resetOrientation();

            stopForeground(true);

            if (onStopRecordCallback != null) {
                onStopRecordCallback.onStopRecord();
            }
        }
    };

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

            if (orientationLocked) {
                if ((downAxis == X_AXIS && abs(event.values[X_AXIS]) < G_THRESHOLD)
                        || (downAxis == Y_AXIS && abs(event.values[Y_AXIS]) < G_THRESHOLD)) {
                    stopRecording();
                    orientationLocked = false;
                }
            }

            if (abs(event.values[X_AXIS]) > G_THRESHOLD) {
                if (downAxis != X_AXIS) {
                    downAxis = X_AXIS;
                    timeAxisDown = event.timestamp;
                } else if (event.timestamp - timeAxisDown >= (long)1e9) {
                    orientationLocked = true;
                }
            } else if (abs(event.values[Y_AXIS]) > G_THRESHOLD) {
                if (downAxis != Y_AXIS) {
                    downAxis = Y_AXIS;
                    timeAxisDown = event.timestamp;
                } else if (event.timestamp - timeAxisDown >= (long)1e9) {
                    orientationLocked = true;
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    };

    private void resetOrientation() {
        downAxis = -1;
        timeAxisDown = 0;
        orientationLocked = false;
    }

    @Override
    public void onDestroy() {

        db.close();
        Log.d(TAG, "Destroyed");
    }

    VideoRecordState getVideoRecordState() {
        return videoRecordState;
    }

    void stopRecording() {

        if (videoRecordState.equals(VideoRecordState.STARTED)) {

            videoCodec.signalEndOfInputStream();

            videoRecordState = VideoRecordState.STOPPING;
        }
    }
}
