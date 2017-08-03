package com.mnemolyst.flightRecorder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.icu.text.AlphabeticIndex;
import android.os.Bundle;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.SwitchPreference;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.common.GooglePlayServicesUtil;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.drive.Drive;
import com.google.android.gms.location.LocationServices;

import java.util.List;

public class PreferenceActivity extends AppCompatActivity implements GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener, SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "PreferenceActivity";
    static final String KEY_PREF_DURATION = "pref_duration";
    static final String KEY_PREF_VIDEO_QUALITY = "pref_video_quality";
    static final String KEY_PREF_TIPOVER = "pref_tipover";
    static final String KEY_PREF_TIPOVER_THRESHOLD = "pref_tipover_threshold";
    static final String KEY_PREF_TIPOVER_TIMEOUT = "pref_tipover_timeout";
    static final String KEY_PREF_LOCATION = "pref_location";
    static final String KEY_PREF_BACKUP = "pref_backup";

    private static final int PERM_REQUEST_LOCATION = 3;
    private SettingsFragment settingsFragment;

    public static class SettingsFragment extends PreferenceFragment {

        @Override
        public void onCreate(Bundle savedInstanceState) {

            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);
        }
    }

    /*
    *  Callbacks for Google Android API
    */
    @Override
    public void onConnected(@Nullable Bundle bundle) {

       //Log.d(TAG, "Google connected");
    }

    @Override
    public void onConnectionSuspended(int i) {

       //Log.d(TAG, "Google disconnect");
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {

       //Log.e(TAG, "Google connect failed");

        if (connectionResult.hasResolution()) {
            try {
                connectionResult.startResolutionForResult(this, ConnectionResult.RESOLUTION_REQUIRED);
            } catch (IntentSender.SendIntentException e) {
                // Unable to resolve, message user appropriately
            }
        } else {
            GoogleApiAvailability.getInstance().getErrorDialog(this, connectionResult.getErrorCode(), 0);
        }
    }
    /*
     *  Google Android API
     */

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        switch (requestCode) {
            case ConnectionResult.RESOLUTION_REQUIRED:
                if (resultCode == RESULT_OK) {
                   //Log.d(TAG, "Google connecting again");
                    MainActivity.googleApiClient.connect();
                } else {
                    SwitchPreference preference = (SwitchPreference) settingsFragment.findPreference(KEY_PREF_BACKUP);
                    preference.setChecked(false);
                }
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preference);

        Toolbar toolbar = (Toolbar) findViewById(R.id.pref_toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        actionBar.setDisplayHomeAsUpEnabled(true);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        settingsFragment = (SettingsFragment) getFragmentManager().findFragmentById(R.id.settings_fragment);
        initSummary(settingsFragment.getPreferenceScreen());
    }

    @Override
    protected void onResume() {

        super.onResume();
        settingsFragment.getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            SwitchPreference preference = (SwitchPreference) settingsFragment.findPreference(KEY_PREF_LOCATION);
            preference.setChecked(false);
        }
    }

    @Override
    protected void onPause() {

        super.onPause();
        settingsFragment.getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
    }

    private void initSummary(Preference preference) {
        if (preference instanceof PreferenceGroup) {
            PreferenceGroup preferenceGroup = (PreferenceGroup) preference;
            for (int i = 0; i < preferenceGroup.getPreferenceCount(); i++) {
                initSummary(preferenceGroup.getPreference(i));
            }
        } else {
            if (preference instanceof ListPreference) {
                ListPreference listPreference = (ListPreference) preference;
                preference.setSummary(listPreference.getEntry());
            };
        }
    }

    static void updateServiceFromPrefs(SharedPreferences sharedPreferences, Resources resources) {

        String prefOpt1, prefOpt2, prefOpt3, prefDefault;

        RecordService.setRecordDuration(Integer.valueOf(sharedPreferences.getString(KEY_PREF_DURATION, "15")));

        prefOpt1 = resources.getString(R.string.pref_video_quality_1080p);
        prefOpt2 = resources.getString(R.string.pref_video_quality_720p);
        prefDefault = resources.getString(R.string.pref_video_quality_default);
        RecordService.setVideoQuality(sharedPreferences.getString(KEY_PREF_VIDEO_QUALITY, prefDefault), prefOpt1, prefOpt2);

        RecordService.setSaveOnTipover(sharedPreferences.getBoolean(KEY_PREF_TIPOVER, true));

        prefOpt1 = resources.getString(R.string.pref_tipover_threshold_low);
        prefOpt2 = resources.getString(R.string.pref_tipover_threshold_medium);
        prefOpt3 = resources.getString(R.string.pref_tipover_threshold_high);
        prefDefault = resources.getString(R.string.pref_tipover_threshold_default);
        RecordService.setTipoverThreshold(sharedPreferences.getString(KEY_PREF_TIPOVER_THRESHOLD, prefDefault), prefOpt1, prefOpt2, prefOpt3);

        RecordService.setTipoverTimeout(Integer.valueOf(sharedPreferences.getString(KEY_PREF_TIPOVER_TIMEOUT, "0")));

        RecordService.setSaveLocation(sharedPreferences.getBoolean(KEY_PREF_LOCATION, false));
    }

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {

        Preference preference = settingsFragment.findPreference(key);

        if (preference instanceof ListPreference) {
            ListPreference listPreference = (ListPreference) preference;
            listPreference.setSummary(listPreference.getEntry());
        }

        switch (key) {
            case KEY_PREF_LOCATION:
                if (sharedPreferences.getBoolean(key, false)) {
                    if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                        RecordService.setSaveLocation(true);

                        if (! MainActivity.hasLocationApi()) {
                            MainActivity.restartGoogleApiClient(this);
                        }
                    } else {
                        ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.ACCESS_COARSE_LOCATION }, PERM_REQUEST_LOCATION);
                    }
                } else {
                    RecordService.setSaveLocation(false);
                }
                break;
            case KEY_PREF_BACKUP:
                if (sharedPreferences.getBoolean(key, false)) {

                    RecordService.setBackupToDrive(true);

                    if (! MainActivity.hasDriveApi()) {
                        MainActivity.restartGoogleApiClient(this);
                    }
                } else {
                    RecordService.setBackupToDrive(false);
                }
                break;
            default:
                PreferenceActivity.updateServiceFromPrefs(sharedPreferences, getResources());
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {

        switch (requestCode) {
            case PERM_REQUEST_LOCATION:
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    RecordService.setSaveLocation(true);
                } else {
                    SwitchPreference preference = (SwitchPreference) settingsFragment.findPreference(KEY_PREF_LOCATION);
                    preference.setChecked(false);
                }
                break;
            default:
               //Log.d(TAG, "OTHER");
        }
    }
}
