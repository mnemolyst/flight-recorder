package com.mnemolyst.videotest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
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
import android.os.Bundle;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Created by joshua on 4/10/17.
 */

/*
    TODO
    video stabilization
    setOrientationHint
 */

public class MainActivity extends Activity {

    private final static String TAG = "VideoTest";
    private final static int PERM_REQUEST_CAMERA = 0;

    private Button btnRecord;
    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession captureSession;
    private ArrayList<ByteBuffer> bufferList = new ArrayList<>();
    private ArrayList<MediaCodec.BufferInfo> bufferInfoList = new ArrayList<>();
    private MediaFormat mediaFormat;
    private MediaCodec mediaCodec;
    private boolean recordingVideo = false;


    private CameraCaptureSession.StateCallback captureSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            captureSession = session;

            /*HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            Handler backgroundHandler = new Handler(thread.getLooper());*/

            try {
                session.setRepeatingRequest(captureRequestBuilder.build(), null, null);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "captureSessionStateCallback.onConfigureFailed");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "CAMERA CLOSED");

            String state = Environment.getExternalStorageState();
            if (Environment.MEDIA_MOUNTED.equals(state)) {

                File savedFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "test.mp4");
                String filePath = savedFile.getAbsolutePath();
                Log.d(TAG, filePath);
                MediaMuxer mediaMuxer = null;
                try {
                    mediaMuxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                int trackIndex = mediaMuxer.addTrack(mediaFormat);

                mediaMuxer.start();
                for (int i = 0; i < bufferList.size(); i++) {
                    ByteBuffer buffer = bufferList.get(i);
                    MediaCodec.BufferInfo info = bufferInfoList.get(i);

                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);

                    Log.d(TAG, "i:"+String.valueOf(i)+" offset:"+String.valueOf(info.offset)+" size:"+String.valueOf(info.size));

                    mediaMuxer.writeSampleData(trackIndex, buffer, info);
                }
                mediaMuxer.stop();
                mediaMuxer.release();
            }

            mediaCodec.stop();
            mediaCodec.release();
        }
    };

    private void openCamera() {

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.CAMERA }, PERM_REQUEST_CAMERA);
            return;
        }

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            String cameraId = cameraManager.getCameraIdList()[0];
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {

                @Override
                public void onOpened(@NonNull CameraDevice camera) {
                    cameraDevice = camera;
                    startPreview();
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

    private void startPreview() {

        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        try {
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraDevice.getId());
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size previewSize = map.getOutputSizes(SurfaceTexture.class)[0];

            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Arrays.asList(surface), captureSessionStateCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void toggleVideoRecord() {
        if (! recordingVideo) {
            startRecording();
        } else {
            stopRecording();
        }
    }

    private void startRecording() {

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
        Size previewSize = map.getOutputSizes(SurfaceTexture.class)[0];

        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface previewSurface = new Surface(texture);

        final MediaFormat format = MediaFormat.createVideoFormat("video/avc", 640, 480);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 1000000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findEncoderForFormat(format);
        try {
            mediaCodec = MediaCodec.createByCodecName(codecName);
        } catch (IOException e) {
            e.printStackTrace();
        }

        /*MediaCodecInfo mediaCodecInfo = mediaCodec.getCodecInfo();
        String[] types = mediaCodecInfo.getSupportedTypes();
        for (String type : types) {
            Log.d(TAG, type);
        }*/

        mediaCodec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                ByteBuffer cloneBuffer = ByteBuffer.allocate(outputBuffer.capacity());
                cloneBuffer.put(outputBuffer);
                bufferList.add(cloneBuffer);

                bufferInfoList.add(info);
                Log.d(TAG, "Frames: "+String.valueOf(bufferList.size()));

                if (bufferList.size() >= 100 && recordingVideo) {
                    Log.d(TAG, "Over 100");
                    recordingVideo = false;
                    try {
                        captureSession.abortCaptures();
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    captureSession.close();
                    codec.signalEndOfInputStream();
                }
                codec.releaseOutputBuffer(index, false);
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec.Callback.onError", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                mediaFormat = format;
            }
        });

        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface codecInputSurface = mediaCodec.createInputSurface();
        mediaCodec.start();
        recordingVideo = true;

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.addTarget(previewSurface);
            captureRequestBuilder.addTarget(codecInputSurface);
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, codecInputSurface), captureSessionStateCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        btnRecord.setText(R.string.stop_button);

    }

    private void stopRecording() {
        //
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.texture);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "surfaceTextureListener.onSurfaceTextureAvailable, width="+width+", height="+height);
                SensorManager sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                    Matrix transform = new Matrix();
                    transform.setRotate(90, width / 2, height / 2);
                    textureView.setTransform(transform);
                }

                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "surfaceTextureListener.onSurfaceTextureSizeChanged, width="+width+", height="+height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Log.d(TAG, "surfaceTextureListener.onSurfaceTextureDestroyed");
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
//                Log.d(TAG, "surfaceTextureListener.onSurfaceTextureUpdated");
            }

        });

        btnRecord = (Button) findViewById(R.id.btnRecord);
        btnRecord.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                toggleVideoRecord();
            }
        });

    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {

        switch (requestCode) {
            case PERM_REQUEST_CAMERA: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    openCamera();
                } else {
                    Log.d(TAG, "Camera permission denied!");
                }
            }
        }
    }
}
