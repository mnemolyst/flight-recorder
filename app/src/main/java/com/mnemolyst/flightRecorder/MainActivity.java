package com.mnemolyst.flightRecorder;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * Created by joshua on 4/10/17.
 */

/*
    TODO
    video stabilization
    "about" screen legal info (Drive)
 */

public class MainActivity extends AppCompatActivity {

    final static String TAG = "MainActivity";
    private final static int PERM_REQUEST_CAMERA_STORAGE = 1;

    private RecordService recordService = null;
    private ArrayList<String> availableVideoQualities = new ArrayList<>();

    private ServiceConnection serviceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {

            Log.d(TAG, "onServiceConnected");

            RecordService.RecordServiceBinder binder = (RecordService.RecordServiceBinder) service;
            recordService = binder.getService();

            recordService.registerOnStartRecordCallback(onStartRecordCallback);
            recordService.registerOnOrientationLockedCallback(onOrientationLockedCallback);
            recordService.registerOnTipoverCallback(onTipoverCallback);
            recordService.registerOnStopRecordCallback(onStopRecordCallback);

            // Get available output resolutions
            /*CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

            try {
                String[] cameraIdList = cameraManager.getCameraIdList();
                for (String id : cameraIdList) {

                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                    if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) {

                        recordService.setCameraId(id);
                        *//*StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                        Size[] outputSizes = map.getOutputSizes(MediaCodec.class);

                        availableVideoQualities = new ArrayList<>();
                        for (Size s : outputSizes) {
                            if (s.getWidth() == 1920 && s.getHeight() == 1080) {
                                availableVideoQualities.add(getResources().getString(R.string.pref_video_quality_1080p));
                            } else if (s.getWidth() == 1280 && s.getHeight() == 720) {
                                availableVideoQualities.add(getResources().getString(R.string.pref_video_quality_720p));
                            }
                        }*//*

                        break;
                    }
                }
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }

            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
            PreferenceActivity.updateServiceFromPrefs(sharedPreferences, getResources());*/
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

            Log.d(TAG, "onServiceDisconnected");

            recordService = null;
        }
    };

    private RecordService.OnStartRecordCallback onStartRecordCallback = new RecordService.OnStartRecordCallback() {
        @Override
        void onStartRecord() {

//            btnRecord.setText(R.string.stop_button);
        }
    };

    private RecordService.OnOrientationLockedCallback onOrientationLockedCallback = new RecordService.OnOrientationLockedCallback() {

        @Override
        void onOrientationLocked() {
            Toast.makeText(MainActivity.this, "Locked!", Toast.LENGTH_SHORT).show();
        }
    };

    private RecordService.OnTipoverCallback onTipoverCallback = new RecordService.OnTipoverCallback() {

        @Override
        void onTipover() {
            Toast.makeText(MainActivity.this, "Tipover!", Toast.LENGTH_SHORT).show();
        }
    };

    private RecordService.OnStopRecordCallback onStopRecordCallback = new RecordService.OnStopRecordCallback() {

        void onStopRecord() {

            unbindService(serviceConnection);
            recordService = null;
//            btnRecord.setText(R.string.start_button);
        }
    };

    private void toggleRecording() {

        if (recordService == null) {

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
                    || ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {

                ActivityCompat.requestPermissions(this, new String[] {
                        Manifest.permission.CAMERA,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                }, PERM_REQUEST_CAMERA_STORAGE);

                return;
            }

            startRecording();
        } else {

            recordService.stopRecording();
        }
    }

    private void startRecording() {

        Intent intent = new Intent(this, RecordService.class);
        startService(intent);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar myToolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(myToolbar);
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

//        unbindService(serviceConnection);
        super.onDestroy();
    }

    @Override
    public void onResume() {

        super.onResume();

        if (recordService != null && recordService.getRecordState().equals(RecordService.RecordState.STARTED)) {

//            btnRecord.setText(R.string.stop_button);
        }
    }

    @Override
    public void onRestart() {

        super.onRestart();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {

        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {

        Intent intent;

        switch (menuItem.getItemId()) {
            case R.id.menuItemRecord:
                toggleRecording();
                return true;
            case R.id.menuItemSettings:
                intent = new Intent(MainActivity.this, PreferenceActivity.class);
                startActivity(intent);
                return true;
            case R.id.menuItemAbout:
                intent = new Intent(MainActivity.this, AboutActivity.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {

        switch (requestCode) {

            case PERM_REQUEST_CAMERA_STORAGE: {

                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    startRecording();
                } else {

                    Log.d(TAG, "Camera permission denied!");
                }
            }
        }
    }
}
