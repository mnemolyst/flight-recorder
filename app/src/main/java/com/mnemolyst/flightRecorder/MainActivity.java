package com.mnemolyst.flightRecorder;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.Snackbar;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.location.LocationServices;

import java.io.File;
import java.io.FilenameFilter;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Created by joshua on 4/10/17.
 */

/*
    TODO
    video stabilization
    "about" screen legal info (Drive)
 */

public class MainActivity extends AppCompatActivity
        implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    final static String TAG = "MainActivity";
    private final static int PERM_REQUEST_INITIAL = 1;

    static GoogleApiClient googleApiClient;

    private RecordService recordService = null;
    private ArrayList<String> availableVideoQualities = new ArrayList<>();

    private File[] fileList;
    private ArrayList<String> filenameList = new ArrayList<>();
    private ArrayAdapter<String> arrayAdapter;
    private SavedVideoListAdapter savedVideoListAdapter;

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
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {

            Log.d(TAG, "onServiceDisconnected");

            recordService = null;
        }
    };

    /*
     *  Callbacks for Google Android API
     */
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

        Log.e(TAG, "Google connect failed");

        if (connectionResult.hasResolution()) {
            try {
                connectionResult.startResolutionForResult(this, ConnectionResult.RESOLUTION_REQUIRED);
            } catch (IntentSender.SendIntentException e) {
                // Unable to resolve, message user appropriately
            }
        } else {
            GoogleApiAvailability.getInstance().getErrorDialog(this, connectionResult.getErrorCode(), 0).show();
        }
    }
    /*
     *  End of Google Android API callbacks
     */

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {

        switch (requestCode) {
            case ConnectionResult.RESOLUTION_REQUIRED:

                if (resultCode == RESULT_OK) {

                    Log.d(TAG, "Google connecting again");
                    googleApiClient.connect();
                } else {

                    SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
                    SharedPreferences.Editor editor = sharedPreferences.edit();
                    editor.putBoolean(PreferenceActivity.KEY_PREF_BACKUP, false);
                    editor.apply();
                }
                break;
        }
    }

    private RecordService.OnStartRecordCallback onStartRecordCallback = new RecordService.OnStartRecordCallback() {
        @Override
        void onStartRecord() {

            Log.d(TAG, "onStartRecordCallback");
            Log.d(TAG, recordService.getRecordState().toString());
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

            Log.d(TAG, "onStopRecord");
            populateSavedFileList();
            savedVideoListAdapter.notifyDataSetChanged();
        }
    };

    private AdapterView.OnItemClickListener itemClickListener = new AdapterView.OnItemClickListener() {

        public void onItemClick(AdapterView parent, View v, int position, long id) {

            Log.d(TAG, String.valueOf(id));

            Uri uri = FileProvider.getUriForFile(MainActivity.this, "com.mnemolyst.flightRecorder.fileprovider", fileList[(int)id]);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Log.d(TAG, "onCreate");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar myToolbar = (Toolbar) findViewById(R.id.main_toolbar);
        setSupportActionBar(myToolbar);

        populateSavedFileList();
        savedVideoListAdapter = new SavedVideoListAdapter(filenameList);
        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.filename_list);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(savedVideoListAdapter);

        Intent intent = new Intent(this, RecordService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (googleApiClient == null
                || !(googleApiClient.isConnected() || googleApiClient.isConnecting())) {

            MainActivity.restartGoogleApiClient(this);
        }
    }

    @Override
    public void onDestroy() {

        Log.d(TAG, "onDestroy");
        unbindService(serviceConnection);
        super.onDestroy();
    }

    private void toggleRecording() {

        if (recordService != null && recordService.getRecordState().equals(RecordService.RecordState.STOPPED)) {

            if (getPermissions()) {
                startRecording();
            }
        } else if (recordService != null) {

            recordService.stopRecording();
        }
    }

    private boolean getPermissions() {

        ArrayList<String> neededPermissions = new ArrayList<>();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.CAMERA);
        }

        /*if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }*/

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
            neededPermissions.add(Manifest.permission.RECORD_AUDIO);
        }

        if (neededPermissions.isEmpty()) {
            return true;
        } else {
            ActivityCompat.requestPermissions(this, neededPermissions.toArray(new String[0]), PERM_REQUEST_INITIAL);
            return false;
        }
    }

    private void startRecording() {

        Intent intent = new Intent(this, RecordService.class);
        startService(intent);
    }

    private FilenameFilter mp4Filter = new FilenameFilter() {
        @Override
        public boolean accept(File dir, String name) {
            return name.substring(name.length() - 4).equals(".mp4");
        }
    };

    private void populateSavedFileList() {

        DateFormat dateFormat = java.text.SimpleDateFormat.getDateTimeInstance();

        fileList = this.getFilesDir().listFiles(mp4Filter);

        filenameList.clear();
        for (File f : fileList) {
            Date lastModified = new Date(f.lastModified());
            filenameList.add(dateFormat.format(lastModified));
        }
    }

    static boolean hasLocationApi() {
        return googleApiClient != null && googleApiClient.hasConnectedApi(LocationServices.API);
    }

    static boolean hasDriveApi() {
        return googleApiClient != null && googleApiClient.hasConnectedApi(Drive.API);
    }

    static void restartGoogleApiClient(Activity activity) {

        Log.d(TAG, "restartGoogleApiClient");

        if (googleApiClient != null && googleApiClient.isConnected()) {
            googleApiClient.disconnect();
        }

        GoogleApiClient.Builder builder = new GoogleApiClient.Builder(activity)
                .addConnectionCallbacks((GoogleApiClient.ConnectionCallbacks) activity)
                .addOnConnectionFailedListener((GoogleApiClient.OnConnectionFailedListener) activity);

        boolean worthIt = false;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(activity);

        if (sharedPreferences.getBoolean(PreferenceActivity.KEY_PREF_LOCATION, false)) {
            Log.d(TAG, "Adding Location API");
            builder.addApi(LocationServices.API);
            worthIt = true;
        }
        if (sharedPreferences.getBoolean(PreferenceActivity.KEY_PREF_BACKUP, false)) {
            Log.d(TAG, "Adding Drive API");
            builder.addApi(Drive.API).addScope(Drive.SCOPE_FILE);
            worthIt = true;
        }
        if (worthIt) {
            googleApiClient = builder.build();
            googleApiClient.connect();
        }
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
            case R.id.clearVideos:
                for (File file : this.getFilesDir().listFiles(mp4Filter)) {
                    Log.d(TAG, file.getName());
                    Log.d(TAG, String.valueOf(file.delete()));
                }
                populateSavedFileList();
                savedVideoListAdapter.notifyDataSetChanged();
            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {

        switch (requestCode) {

            case PERM_REQUEST_INITIAL: {
                boolean haveCamera = true;
//                boolean haveStorage = true;

                for (int i = 0; i < permissions.length; i++) {
                    switch (permissions[i]) {
                        case Manifest.permission.CAMERA:
                            haveCamera = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                            break;
                        /*case Manifest.permission.WRITE_EXTERNAL_STORAGE:
                            haveStorage = grantResults[i] == PackageManager.PERMISSION_GRANTED;
                            break;*/
                    }
                }

                if (haveCamera) { // && haveStorage) {

                    startRecording();
                } else {

                    if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.CAMERA)) {
                        Snackbar snackbar = Snackbar.make(findViewById(R.id.coordinator_layout), R.string.camera_permission_note, Snackbar.LENGTH_LONG);
                        snackbar.show();
                    }
                    /*if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        Snackbar snackbar = Snackbar.make(findViewById(R.id.coordinator_layout), R.string.storage_permission_note, Snackbar.LENGTH_LONG);
                        snackbar.show();
                    }*/
                }
            }
        }
    }
}
