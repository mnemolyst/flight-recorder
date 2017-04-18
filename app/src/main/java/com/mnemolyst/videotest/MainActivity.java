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
import android.media.MediaMuxer;
import android.os.Bundle;
import android.os.Environment;
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
    private enum VideoRecordState {
        STOPPING, STOPPED, STARTING, STARTED
    }
    private Button btnRecord;

    private TextureView textureView;
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder captureRequestBuilder;
    private CameraCaptureSession captureSession;
    private int bufferLimit = 120;
    private ArrayList<BufferDataInfoPair> bufferList = new ArrayList<>(bufferLimit);
    private MediaFormat mediaFormat;
    private MediaCodec mediaCodec;
    private VideoRecordState videoRecordState;


    private CameraCaptureSession.StateCallback previewSessionCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            captureSession = session;

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
            Log.e(TAG, "previewSessionCallback.onConfigureFailed");
        }
    };

    private CameraCaptureSession.StateCallback recordSessionCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(@NonNull CameraCaptureSession session) {
            captureSession = session;

            HandlerThread thread = new HandlerThread("CameraPreview");
            thread.start();
            Handler backgroundHandler = new Handler(thread.getLooper());

            try {
                session.setRepeatingRequest(captureRequestBuilder.build(), null, backgroundHandler);
                videoRecordState = VideoRecordState.STARTED;
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
            Log.e(TAG, "recordSessionCallback.onConfigureFailed");
        }

        @Override
        public void onClosed(@NonNull CameraCaptureSession session) {
            Log.d(TAG, "Capture session closed");

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
                    BufferDataInfoPair dataInfoPair = bufferList.get(i);
                    ByteBuffer buffer = dataInfoPair.getData();
                    MediaCodec.BufferInfo info = dataInfoPair.getInfo();

//                    buffer.position(info.offset);
//                    buffer.limit(info.offset + info.size);

                    Log.d(TAG, "i:" + String.valueOf(i) +
                            " offset:" + String.valueOf(info.offset) +
                            " size:" + String.valueOf(info.size) +
                            " pt:" + String.valueOf(info.presentationTimeUs) +
                            " codec config:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) +
                            " keyframe:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) +
                            " EOS:" + String.valueOf(info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM));

                    mediaMuxer.writeSampleData(trackIndex, buffer, info);
                }
                mediaMuxer.stop();
                mediaMuxer.release();

            } else {
                Log.e(TAG, state);
            }

            bufferList = new ArrayList<>(bufferLimit);
            mediaFormat = null;

            mediaCodec.stop();
            mediaCodec.release();

            videoRecordState = VideoRecordState.STOPPED;

            btnRecord.setText(R.string.start_button);

            startPreview();
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
            Size[] previewOutputSizes = map.getOutputSizes(SurfaceTexture.class);
            Size previewSize = previewOutputSizes[0];

            SurfaceTexture texture = textureView.getSurfaceTexture();
//            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface surface = new Surface(texture);

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.addTarget(surface);

            cameraDevice.createCaptureSession(Arrays.asList(surface), previewSessionCallback, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void toggleVideoRecord() {
        if (videoRecordState.equals(VideoRecordState.STOPPED)) {
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
        Size[] previewOutputSizes = map.getOutputSizes(SurfaceTexture.class);
        Size[] codecOutputSizes = map.getOutputSizes(MediaCodec.class);
        Size previewSize = previewOutputSizes[0];
        for (Size s : codecOutputSizes) {
            Log.d(TAG, String.valueOf(s.getWidth()) + ", " + String.valueOf(s.getHeight()));
        }

        SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
//        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

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

        mediaCodec.setCallback(new MediaCodec.Callback() {

            @Override
            public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) { }

            @Override
            public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {

                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    return;
                }

                ByteBuffer outputBuffer = codec.getOutputBuffer(index);
                ByteBuffer cloneBuffer = ByteBuffer.allocate(outputBuffer.capacity());
                cloneBuffer.put(outputBuffer);

                if (bufferList.size() >= bufferLimit) {
                    bufferList.subList(0, bufferList.size() - bufferLimit).clear();
                }

                BufferDataInfoPair dataInfoPair = new BufferDataInfoPair(cloneBuffer, info);
                bufferList.add(dataInfoPair);

                codec.releaseOutputBuffer(index, false);


                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {

                    try {
                        captureSession.abortCaptures();
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                    captureSession.close();
                }
            }

            @Override
            public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                Log.e(TAG, "MediaCodec.Callback.onError", e);
            }

            @Override
            public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
//                Log.d(TAG, "FR CHANGED: " + format.getInteger(MediaFormat.KEY_FRAME_RATE) + " " + format.getInteger(MediaFormat.KEY_BIT_RATE));
                if (mediaFormat == null) {
                    Log.d(TAG, "Format changed");
                    mediaFormat = format;
                } else {
                    Log.e(TAG, "Format already changed");
                }
            }
        });


        mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        Surface codecInputSurface = mediaCodec.createInputSurface();
        mediaCodec.start();

        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            captureRequestBuilder.addTarget(previewSurface);
            captureRequestBuilder.addTarget(codecInputSurface);
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, codecInputSurface), recordSessionCallback, null);

            videoRecordState = VideoRecordState.STARTING;

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        btnRecord.setText(R.string.stop_button);
    }

    private void stopRecording() {

        if (videoRecordState.equals(VideoRecordState.STARTED)) {

            mediaCodec.signalEndOfInputStream();

            videoRecordState = VideoRecordState.STOPPING;
        }
    }

    private void initTextureView() {

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {

            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "surfaceTextureListener.onSurfaceTextureAvailable, width="+width+", height="+height);

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
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_main);

        textureView = (TextureView) findViewById(R.id.texture);
        initTextureView();

        btnRecord = (Button) findViewById(R.id.btnRecord);
        btnRecord.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                toggleVideoRecord();
            }
        });

        videoRecordState = VideoRecordState.STOPPED;
    }

    @Override
    public void onRestart() {

        super.onRestart();

        if (! textureView.isAvailable()) {
            SurfaceTexture texture = new SurfaceTexture(0);
            textureView.setSurfaceTexture(texture);
        }

        initTextureView();

        openCamera();
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
