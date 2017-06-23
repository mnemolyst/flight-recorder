package com.mnemolyst.flightRecorder;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentSender;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.widget.CardView;
import android.transition.Scene;
import android.transition.Transition;
import android.transition.TransitionManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.FileProvider;
import android.support.v4.view.ViewPropertyAnimatorCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.support.v7.widget.Toolbar;
import android.transition.TransitionInflater;
import android.util.Log;
import android.support.v7.view.ActionMode;
import android.util.SparseBooleanArray;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.view.ViewGroup;
import android.view.ViewPropertyAnimator;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.location.LocationServices;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
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
    private final static int PERM_REQUEST_STORAGE = 2;

    private Toolbar toolbar;
    private ActionMode actionMode;
    private TransitionManager transitionManager;
    private Scene sceneNormal;
    private Scene sceneRecording;
    private ViewGroup sceneContainer;
    private FloatingActionButton recordFab;

    static GoogleApiClient googleApiClient;

    private RecordService recordService = null;
    private ArrayList<String> availableVideoQualities = new ArrayList<>();

    private ArrayList<File> fileList = new ArrayList<>();
//    private ArrayList<String> filenameList = new ArrayList<>();
//    private ArrayAdapter<String> arrayAdapter;
    private SavedVideoListAdapter savedVideoListAdapter;

    class SavedVideoListAdapter extends RecyclerView.Adapter<SavedVideoListAdapter.ViewHolder> {

        private ArrayList<File> dataSet;
        private SparseBooleanArray selectedPositions = new SparseBooleanArray();
        private int selectedPos = -1;

        ArrayList<File> getSelectedFiles() {

            ArrayList<File> ret = new ArrayList<>();
            for (int i = 0; i < dataSet.size(); i++) {

                if (selectedPositions.get(i)) {
                    ret.add(dataSet.get(i));
                }
            }
            return ret;
        }

        void removeFile(int idx) {

            try {
                dataSet.remove(idx);
                notifyItemRemoved(idx);
            } catch (IndexOutOfBoundsException e) {
                e.printStackTrace();
            }
        }

        void removeSelectedFiles() {

            for (int i = 0; i < dataSet.size(); i++) {

                if (selectedPositions.get(i)) {
                    dataSet.remove(i);
                    notifyItemRemoved(i);
                }
            }
        }

        public void selectNone() {
            selectedPositions.clear();
            notifyDataSetChanged();
        }

        class ViewHolder extends RecyclerView.ViewHolder implements View.OnClickListener, View.OnLongClickListener {

            View view;
            TextView dateTimeView;
            ImageView thumbnailView;

            ViewHolder(View view) {

                super(view);
                this.view = view;

                view.setOnClickListener(this);
                view.setOnLongClickListener(this);
            }

            @Override
            public void onClick(View view) {

                Log.d(TAG, "onClick");

                int position = getLayoutPosition();

                if (actionMode == null) {

                    Uri uri = FileProvider.getUriForFile(MainActivity.this, "com.mnemolyst.flightRecorder.fileprovider", dataSet.get(position));

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(uri);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (intent.resolveActivity(getPackageManager()) != null) {
                        startActivity(intent);
                    }
                } else {

                    if (selectedPositions.get(position)) {

                        selectedPositions.delete(position);
                        if (selectedPositions.size() == 0) {
                            actionMode.finish();
                        }
                    } else {
                        selectedPositions.put(position, true);
                    }

                    notifyItemChanged(position);
                }
            }

            @Override
            public boolean onLongClick(View view) {

                Log.d(TAG, "onLongClick");
                if (actionMode != null) {
                    return false;
                }

                // Start the CAB using the ActionMode.Callback defined above
//                actionMode = MainActivity.this.startActionMode(actionModeCallback);
                actionMode = startSupportActionMode(actionModeCallback);
//                notifyItemChanged(selectedPos);
                int position = getLayoutPosition();
                selectedPositions.put(position, true);
//                selectedPos = getLayoutPosition();
                notifyItemChanged(position);
                return true;
            }
        }

        // Constructor
        SavedVideoListAdapter(ArrayList<File> dataSet) {
            this.dataSet = dataSet;
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {

            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.saved_video_list_item, parent, false);
//            v.setBottom(20);
            // set the view's size, margins, paddings and layout parameters

            return new SavedVideoListAdapter.ViewHolder(v);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {

            File thisFile = dataSet.get(position);

            DateFormat dateFormat = java.text.SimpleDateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT);
            String lastModified = dateFormat.format(new Date(thisFile.lastModified()));

            TextView textView = (TextView) holder.view.findViewById(R.id.dateTime);
            textView.setText(lastModified);
            textView.setTextColor(getResources().getColorStateList(R.color.saved_video_list_text_color));

            Bitmap thumbnail = ThumbnailUtils.createVideoThumbnail(thisFile.getAbsolutePath(), MediaStore.Images.Thumbnails.MICRO_KIND);
            ImageView imageView = (ImageView) holder.view.findViewById(R.id.videoThumbnail);
            imageView.setImageBitmap(thumbnail);

            holder.itemView.setSelected(selectedPositions.get(position));

            Log.d(TAG, "onBind: " + String.valueOf(holder.itemView.isSelected()));
        }

        @Override
        public int getItemCount() {
            return dataSet.size();
        }
    }

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
            savedVideoListAdapter.notifyItemInserted(savedVideoListAdapter.getItemCount());
        }
    };

    private ActionMode.Callback actionModeCallback = new ActionMode.Callback() {

        // Called when the action mode is created; startActionMode() was called
        @Override
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {

            // Inflate a menu resource providing context menu items
            MenuInflater inflater = mode.getMenuInflater();
            inflater.inflate(R.menu.action_mode_menu, menu);
            return true;
        }

        // Called each time the action mode is shown. Always called after onCreateActionMode, but
        // may be called multiple times if the mode is invalidated.
        @Override
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {

            return false; // Return false if nothing is done
        }

        // Called when the user selects a contextual menu item
        @Override
        public boolean onActionItemClicked(final ActionMode mode, MenuItem item) {

            final ArrayList<File> selectedFiles = savedVideoListAdapter.getSelectedFiles();
            if (selectedFiles.isEmpty()) {
                return false;
            }

            switch (item.getItemId()) {
                case R.id.menuItemDelete:

                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle(R.string.delete_confirm_title)
                            .setMessage(R.string.delete_confirm_text)
                            .setIcon(android.R.drawable.ic_dialog_alert)
                            .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {

                                    for (int i = 0; i < selectedFiles.size(); i++) {

                                        if (selectedFiles.get(i).delete()) {
                                            savedVideoListAdapter.removeFile(i);
                                        } else {
                                            Snackbar.make(findViewById(R.id.coordinator_layout), R.string.error_deleting, Snackbar.LENGTH_LONG).show();
                                        }
                                    }
                                    mode.finish(); // Action picked, so close the CAB
                                }
                            })
                            .setNegativeButton(android.R.string.no, null)
                            .show();
                    break;
                case R.id.menuItemSave:

                    if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {

                        if (isExternalStorageWritable()) {
                            saveFilesToExternalStorage(selectedFiles);
                        } else {
                            Snackbar.make(findViewById(R.id.coordinator_layout), R.string.no_external_storage, Snackbar.LENGTH_LONG).show();
                        }
                        mode.finish();
                    } else {
                        ActivityCompat.requestPermissions(MainActivity.this, new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, PERM_REQUEST_STORAGE);
                    }
                    break;
                default:
                    return false;
            }

            return true;
        }

        // Called when the user exits the action mode
        @Override
        public void onDestroyActionMode(ActionMode actionMode) {

            savedVideoListAdapter.selectNone();
            MainActivity.this.actionMode = null;
        }
    };

    boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    void saveFilesToExternalStorage(ArrayList<File> files) {

        for (File srcFile : files) {

            File dstFile = new File(getApplicationContext().getExternalFilesDir(Environment.DIRECTORY_MOVIES), srcFile.getName());

            try {
                FileChannel inChannel = new FileInputStream(srcFile).getChannel();
                FileChannel outChannel = new FileOutputStream(dstFile).getChannel();

                try {
                    inChannel.transferTo(0, inChannel.size(), outChannel);
                } finally {

                    if (inChannel != null) {
                        inChannel.close();
                    }
                    outChannel.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    class SceneTransitionManager {
        private final TransitionManager transitionManager;
        Scene scene_normal;
        Scene scene_recording;

        public SceneTransitionManager() {
            transitionManager = new TransitionManager();
            FrameLayout container = (FrameLayout) findViewById(R.id.container);
            scene_normal = Scene.getSceneForLayout(container, R.layout.scene_normal, MainActivity.this);
            scene_recording = Scene.getSceneForLayout(container, R.layout.scene_recording, MainActivity.this);
            Transition transition = TransitionInflater.from(MainActivity.this).inflateTransition(R.transition.recording);
            transitionManager.setTransition(scene_normal, scene_recording, transition);
            transitionManager.setTransition(scene_recording, scene_normal, transition);
            transitionManager.transitionTo(scene_normal);
        }

        public void sceneTransition(Scene scene) {
            if (scene == scene_normal) {
                transitionManager.transitionTo(scene_normal);
            } else {
                transitionManager.transitionTo(scene_recording);
            }
        }
    }

    private View.OnClickListener recordFabListener = new View.OnClickListener() {

        @Override
        public void onClick(View view) {

//            Animation animation = AnimationUtils.loadAnimation(MainActivity.this, R.anim.fab_animation);
//            animation.setInterpolator(MainActivity.this, android.R.anim.accelerate_decelerate_interpolator);
//            PathInterpolator pathInterpolator = new PathInterpolator((float) 0.4, (float) 0.0, (float) 1.0, (float) 1.0);
//            animation.setInterpolator(pathInterpolator);
//            recordFab.startAnimation(animation);
//            Path path =
//            ObjectAnimator objectAnimator = ObjectAnimator.ofFloat(recordFab, View.X, View.Y, path);
//            sceneTransitionManager.sceneTransition(sceneTransitionManager.scene_recording);

//            Button recordingCard = (Button) findViewById(R.id.cardButton);
//            recordingCard.setOnClickListener(recordCardListener);

//            toggleRecording();
            // previously invisible view
            /*View recordCard = findViewById(R.id.recordCard);

            int cx = recordCard.getWidth() / 2;
            int cy = recordCard.getHeight() / 2;

            float finalRadius = (float) Math.hypot(cx, cy);

            Animator anim = ViewAnimationUtils.createCircularReveal(recordCard, cx, cy, 0, finalRadius);

            recordCard.setVisibility(View.VISIBLE);
            anim.start();*/
        }
    };

    public void fabClick(View view) {
        Log.d(TAG, "fabClick");
        transitionManager.transitionTo(sceneRecording);
    }

    public void recordCardClick(View view) {
        Log.d(TAG, "recordCardClick");
        transitionManager.transitionTo(sceneNormal);
    }

    public View.OnClickListener recordCardListener = new View.OnClickListener() {

        @Override
        public void onClick(View view) {
            Log.d(TAG, "hi");
//            sceneTransitionManager.sceneTransition(sceneTransitionManager.scene_normal);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        Log.d(TAG, "onCreate");

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        toolbar = (Toolbar) findViewById(R.id.mainToolbar);
        setSupportActionBar(toolbar);

        populateSavedFileList();
        savedVideoListAdapter = new SavedVideoListAdapter(fileList);
        RecyclerView recyclerView = (RecyclerView) findViewById(R.id.savedVideoList);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(savedVideoListAdapter);

        sceneContainer = (ViewGroup) findViewById(R.id.container);
        sceneNormal = Scene.getSceneForLayout(sceneContainer, R.layout.scene_normal, this);
        sceneRecording = Scene.getSceneForLayout(sceneContainer, R.layout.scene_recording, this);
        transitionManager = TransitionInflater.from(this).inflateTransitionManager(R.transition.manager, sceneContainer);
        transitionManager.transitionTo(sceneNormal);

        recordFab = (FloatingActionButton) findViewById(R.id.recordFab);
        recordFab.setClipToOutline(true);
//        recordFab.setOnClickListener(recordFabListener);

        Intent intent = new Intent(this, RecordService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (googleApiClient == null
                || !(googleApiClient.isConnected() || googleApiClient.isConnecting())) {

            MainActivity.restartGoogleApiClient(this);
        }
    }

    /*@Override
    public void onCreateContextMenu(ContextMenu contextMenu, View view, ContextMenu.ContextMenuInfo menuInfo) {
        Log.d(TAG, "context menu");
    }*/

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

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//                && ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
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

        DateFormat dateFormat = java.text.SimpleDateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT);

        File[] files = this.getFilesDir().listFiles(mp4Filter);
        fileList.clear();
        fileList.addAll(Arrays.asList(files));

        /*filenameList.clear();
        for (File f : fileList) {
            Date lastModified = new Date(f.lastModified());
            filenameList.add(dateFormat.format(lastModified));
        }*/
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

            case PERM_REQUEST_INITIAL:
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

                    Snackbar.make(findViewById(R.id.coordinator_layout), R.string.camera_permission_note, Snackbar.LENGTH_LONG).show();
                    /*if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                        Snackbar snackbar = Snackbar.make(findViewById(R.id.coordinator_layout), R.string.storage_permission_note, Snackbar.LENGTH_LONG);
                        snackbar.show();
                    }*/
                }
                break;
            case PERM_REQUEST_STORAGE:
                if (permissions.length == 1
                        && permissions[0].equals(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        && grantResults[0] == PackageManager.PERMISSION_GRANTED) {

                    saveFilesToExternalStorage(savedVideoListAdapter.getSelectedFiles());
                } else {

                    Snackbar.make(findViewById(R.id.coordinator_layout), R.string.storage_permission_note, Snackbar.LENGTH_LONG).show();
                }
                break;
        }
    }
}
