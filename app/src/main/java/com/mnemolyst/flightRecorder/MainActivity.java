package com.mnemolyst.flightRecorder;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaCodec;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.ListPreference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.app.FragmentManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.util.Size;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.location.LocationServices;

import java.util.ArrayList;

/**
 * Created by joshua on 4/10/17.
 */

/*
    TODO
    video stabilization
    setOrientationHint
 */

public class MainActivity extends AppCompatActivity
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    final static String TAG = "FlightRecorder";
    private final static int PERM_REQUEST_CAMERA_STORAGE = 1;

    private Button btnRecord;
    private ImageButton btnSettings;
    private RecordService recordService = null;
    private ArrayList<String> availableVideoQualities = new ArrayList<>();

    static GoogleApiClient googleApiClient;

    private ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {

            RecordService.RecordServiceBinder binder = (RecordService.RecordServiceBinder) service;
            recordService = binder.getService();
            recordService.registerOnStopRecordCallback(onStopRecordCallback);
            recordService.registerOnOrientationLockedCallback(onOrientationLockedCallback);

            // Get available output resolutions
            CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

            try {
                String[] cameraIdList = cameraManager.getCameraIdList();
                for (String id : cameraIdList) {

                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {

                        recordService.setCameraId(id);
                        /*StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                        Size[] outputSizes = map.getOutputSizes(MediaCodec.class);

                        availableVideoQualities = new ArrayList<>();
                        for (Size s : outputSizes) {
                            if (s.getWidth() == 1920 && s.getHeight() == 1080) {
                                availableVideoQualities.add(getResources().getString(R.string.pref_video_quality_1080p));
                            } else if (s.getWidth() == 1280 && s.getHeight() == 720) {
                                availableVideoQualities.add(getResources().getString(R.string.pref_video_quality_720p));
                            }
                        }*/

                        break;
                    }
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            PreferenceActivity.updateServiceFromPrefs(sharedPreferences, getResources());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

            recordService = null;
        }
    };

    private RecordService.OnStopRecordCallback onStopRecordCallback = new RecordService.OnStopRecordCallback() {

        void onStopRecord() {

            btnRecord.setText(R.string.start_button);
        }
    };

    private RecordService.OnOrientationLockedCallback onOrientationLockedCallback = new RecordService.OnOrientationLockedCallback() {

        @Override
        void onOrientationLocked() {
            Toast.makeText(MainActivity.this, "Locked!", Toast.LENGTH_SHORT).show();
        }
    };

    @Override
    public void onConnected(@Nullable Bundle bundle) {

        Log.d(TAG, "Google connected");
    }

    @Override
    public void onConnectionSuspended(int i) {

        Log.d(TAG, "Google disconnect");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

    }

    private void toggleVideoRecord() {

        if (recordService.getRecordState().equals(RecordService.RecordState.STOPPED)) {

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, new String[] {
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                }, PERM_REQUEST_CAMERA_STORAGE);

                return;
            }

            recordService.startRecording();
            btnRecord.setText(R.string.stop_button);
        } else {

            recordService.stopRecording();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        btnRecord = (Button) findViewById(R.id.btnRecord);
        btnRecord.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                toggleVideoRecord();
            }
        });

        btnSettings = (ImageButton) findViewById(R.id.btnPrefs);
        btnSettings.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                Intent intent = new Intent(MainActivity.this, PreferenceActivity.class);
                startActivity(intent);
            }
        });

        Intent intent = new Intent(this, RecordService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        googleApiClient = new GoogleApiClient.Builder(this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(LocationServices.API)
                .build();
    }

    @Override
    public void onStart() {

        Log.d(TAG, "onStart");
        super.onStart();
    }

    @Override
    public void onPause() {

        Log.d(TAG, "onPause");
        super.onPause();
    }

    @Override
    public void onStop() {

        Log.d(TAG, "onStop");
        super.onStop();
    }

    @Override
    public void onDestroy() {

        Log.d(TAG, "onDestroy");
        super.onDestroy();
    }

    @Override
    public void onResume() {

        super.onResume();

        if (recordService != null && recordService.getRecordState().equals(RecordService.RecordState.STARTED)) {

            btnRecord.setText(R.string.stop_button);
        }
    }

    @Override
    public void onRestart() {

        super.onRestart();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {

        switch (requestCode) {

            case PERM_REQUEST_CAMERA_STORAGE: {

                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    recordService.startRecording();
                } else {

                    Log.d(TAG, "Camera permission denied!");
                }
            }
        }
    }
}
