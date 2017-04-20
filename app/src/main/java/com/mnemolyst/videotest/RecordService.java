package com.mnemolyst.videotest;

import android.Manifest;
import android.app.IntentService;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteStatement;
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
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Binder;
import android.os.Environment;
import android.os.IBinder;
import android.provider.BaseColumns;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
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

    private static final String ACTION_RECORD = "com.mnemolyst.videotest.action.record";

    // TODO: Rename parameters
    private static final String EXTRA_PARAM1 = "com.mnemolyst.videotest.extra.PARAM1";
    private static final String EXTRA_PARAM2 = "com.mnemolyst.videotest.extra.PARAM2";

    private int startId;

    private enum VideoRecordState {
        STOPPING, STOPPED, STARTING, STARTED
    }
    private VideoRecordState videoRecordState;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession captureSession;
    private MediaCodec mediaCodec;
    private MediaFormat mediaFormat;
    private int bufferLimit = 30*30;
    private ArrayList<BufferDataInfoPair> bufferList = new ArrayList<>();

    private SQLiteOpenHelper sqLiteOpenHelper = null;
    private SQLiteDatabase db = null;
    private int sqlEncodedFramesId = 1;

    private RecordServiceBinder binder = new RecordServiceBinder();

    private class RecordServiceBinder extends Binder {

        public RecordService getService() {

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
    /**
     * Starts this service to perform action Foo with the given parameters. If
     * the service is already performing a task this action will be queued.
     *
     * @see IntentService
     */
    // TODO: Customize helper method
    public static void startRecording(Context context, String param1, String param2) {
        Intent intent = new Intent(context, RecordService.class);
        intent.setAction(ACTION_RECORD);
        intent.putExtra(EXTRA_PARAM1, param1);
        intent.putExtra(EXTRA_PARAM2, param2);
        context.startService(intent);
    }

    @Override
    public void onCreate() {

        sqLiteOpenHelper = new EncodedFramesHelper(this);
        db = sqLiteOpenHelper.getWritableDatabase();
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {

        this.startId = startId;

        new Thread(new Runnable() {

            public void run() {

                doStartRecord();
            }
        }).run();

        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        db.close();
        Log.d(TAG, "Destroyed");
    }

    private void doStartRecord() {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {

                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    onCameraOpened();
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

    private void onCameraOpened() {

        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null, return");
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
        try {
            mediaCodec = MediaCodec.createByCodecName(codecName);
        } catch (IOException e) {
            e.printStackTrace();
        }

        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);

        db.execSQL("DELETE FROM " + EncodedFramesContract.EncodedFrameEntry.TABLE_NAME);
        sqlEncodedFramesId = 1;

        mediaCodec.setCallback(mediaCodecCallback);

        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface codecInputSurface = mediaCodec.createInputSurface();
        mediaCodec.start();

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.addTarget(codecInputSurface);
            cameraDevice.createCaptureSession(Arrays.asList(codecInputSurface), captureSessionCallback, null);

            videoRecordState = VideoRecordState.STARTING;

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        List<Sensor> sensorList = sensorManager.getSensorList(Sensor.TYPE_GRAVITY);
        if (sensorList.isEmpty()) {
            sensorList = sensorManager.getSensorList(Sensor.TYPE_ACCELEROMETER);
        }
        sensorManager.registerListener(sensorEventListener, sensorList.get(0), 1_000_000);
    }

    private MediaCodec.Callback mediaCodecCallback = new MediaCodec.Callback() {

        private String insertSql = "INSERT INTO " + EncodedFramesContract.EncodedFrameEntry.TABLE_NAME + "(" +
                EncodedFramesContract.EncodedFrameEntry._ID + "," +
                EncodedFramesContract.EncodedFrameEntry.FRAME_DATA_COLUMN_NAME + ") VALUES (?,?)";

        @Override
        public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { }

        @Override
        public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

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

//            Log.d(TAG, "Frames: " + String.valueOf(bufferList.size()));

            if (bufferList.size() > 30*10) {
                stopRecording();
            }

            codec.releaseOutputBuffer(index, false);

            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {

                try {
                    captureSession.abortCaptures();
                    captureSession.close();
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

            if (mediaFormat == null) {
                Log.d(TAG, "Format changed");
                mediaFormat = format;
            } else {
                Log.e(TAG, "Format already changed");
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
            if (Environment.MEDIA_MOUNTED.equals(state)) {

                File savedFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "test.mp4");
                String filePath = savedFile.getAbsolutePath();

                String[] tableRows = {
                        EncodedFramesContract.EncodedFrameEntry.FRAME_DATA_COLUMN_NAME,
                };


                MediaMuxer mediaMuxer = null;
                try {
                    mediaMuxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                int trackIndex = mediaMuxer.addTrack(mediaFormat);

                mediaMuxer.start();

                for (BufferDataInfoPair dataInfoPair : bufferList) {
//                while (cursor.moveToNext()) {

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

                    /*Log.d(TAG, "offset:" + String.valueOf(info.offset) +
                            " size:" + String.valueOf(info.size) +
                            " pt:" + String.valueOf(info.presentationTimeUs) +
                            " codec config:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) +
                            " keyframe:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) +
                            " EOS:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM));*/

                    mediaMuxer.writeSampleData(trackIndex, buffer, info);
                }
                mediaMuxer.stop();
                mediaMuxer.release();

            } else {
                Log.e(TAG, state);
            }

            bufferList = new ArrayList<>();
            mediaFormat = null;

            mediaCodec.stop();
            mediaCodec.release();

            videoRecordState = VideoRecordState.STOPPED;

            SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
            sensorManager.unregisterListener(sensorEventListener);

            stopSelf(startId);
        }
    };

    private SensorEventListener sensorEventListener = new SensorEventListener() {

        private final double G_THRESHOLD = SensorManager.GRAVITY_EARTH * sqrt(2) / 2;
        private final int X_AXIS = 0;
        private final int Y_AXIS = 1;
        private int downAxis = X_AXIS;
        private long timeAxisDown = 0;
        private boolean orientationLocked = false;

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

    void stopRecording() {

        if (videoRecordState.equals(VideoRecordState.STARTED)) {

            mediaCodec.signalEndOfInputStream();

            videoRecordState = VideoRecordState.STOPPING;
        }
    }
}
