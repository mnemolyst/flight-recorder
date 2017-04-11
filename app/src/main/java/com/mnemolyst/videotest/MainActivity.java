package com.mnemolyst.videotest;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
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
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
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

import java.io.IOException;
import java.util.Arrays;

/**
 * Created by joshua on 4/10/17.
 */

public class MainActivity extends Activity {

    private final static String TAG = "VideoTest";
    private final static int PERM_REQUEST_CAMERA = 0;

    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;


    private CameraCaptureSession.StateCallback captureSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            Handler backgroundHandler = new Handler(thread.getLooper());

            try {
                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "captureSessionStateCallback.onConfigureFailed");
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
                    Log.e(TAG, "cameraStateCallback.onDisconnected");
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

    private void startVideoRecord() {

        Log.d(TAG, "startVideoRecord");
        if (null == cameraDevice) {
            Log.e(TAG, "cameraDevice is null, return");
            return;
        }

        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics characteristics = null;
        try {
            characteristics = manager.getCameraCharacteristics(cameraDevice.getId());
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size previewSize = map.getOutputSizes(SurfaceTexture.class)[0];

        SurfaceTexture texture = textureView.getSurfaceTexture();
        texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface previewSurface = new Surface(texture);

        MediaFormat mediaFormat = null;
        mediaFormat = MediaFormat.createVideoFormat("video/avc", 640, 480);
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 6000000);
        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);

        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        String codecName = codecList.findEncoderForFormat(mediaFormat);
        MediaCodec mediaCodec = null;
        try {
            mediaCodec = MediaCodec.createByCodecName(codecName);
        } catch (IOException e) {
            e.printStackTrace();
        }

        mediaCodec.setCallback(new MediaCodec.Callback() {
            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                Log.e(TAG, "onInputBufferAvailable");
            }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                Log.e(TAG, "onOutputBufferAvailable");
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "onError");
                e.printStackTrace();
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {

            }
        });

        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface codecInputSurface = mediaCodec.createInputSurface();
        mediaCodec.start();

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.addTarget(previewSurface);
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, codecInputSurface), captureSessionStateCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

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
                Log.e(TAG, "surfaceTextureListener.onSurfaceTextureAvailable, width="+width+", height="+height);
                openCamera();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                Log.e(TAG, "surfaceTextureListener.onSurfaceTextureSizeChanged, width="+width+", height="+height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                Log.e(TAG, "surfaceTextureListener.onSurfaceTextureDestroyed");
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
//            Log.e(TAG, "surfaceTextureListener.onSurfaceTextureUpdated");
            }

        });

        Button btnRecord = (Button) findViewById(R.id.btnRecord);
        btnRecord.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                startVideoRecord();
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
                    Log.e(TAG, "Camera permission denied!");
                }
            }
        }
    }
}
