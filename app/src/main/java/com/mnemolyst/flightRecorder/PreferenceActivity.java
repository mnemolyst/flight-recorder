package com.mnemolyst.flightRecorder;

import android.Manifest;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.MultiSelectListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import java.util.List;

public class PreferenceActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "PreferenceActivity";
    static final String KEY_PREF_DURATION = "pref_duration";
    static final String KEY_PREF_VIDEO_QUALITY = "pref_video_quality";
    static final String KEY_PREF_LOCATION = "pref_location";
    private static final int PERM_REQUEST_LOCATION = 2;
    private SettingsFragment settingsFragment;

    public static class SettingsFragment extends PreferenceFragment {

        @Override
        public void onCreate(Bundle savedInstanceState) {

            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_preference);

        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        settingsFragment = (SettingsFragment) getFragmentManager().findFragmentById(R.id.settingsFragment);
        initSummary(settingsFragment.getPreferenceScreen());
    }

    @Override
    protected void onResume() {

        super.onResume();
        settingsFragment.getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
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

    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {

        Preference preference = settingsFragment.findPreference(key);

        if (preference instanceof ListPreference) {
            ListPreference listPreference = (ListPreference) preference;
            listPreference.setSummary(listPreference.getEntry());
        }

        switch (key) {
            case KEY_PREF_DURATION:
                RecordService.setRecordDuration(Integer.valueOf(sharedPreferences.getString(key, "15")));
                break;
            case KEY_PREF_VIDEO_QUALITY:
                String q1080p = getResources().getString(R.string.pref_video_quality_1080p);
                String q720p = getResources().getString(R.string.pref_video_quality_720p);
                String qDefault = getResources().getString(R.string.pref_video_quality_default);
                if (sharedPreferences.getString(key, qDefault).equals(q1080p)) {
                    RecordService.setVideoQuality(RecordService.VideoQuality.HIGH_1080P);
                } else if (sharedPreferences.getString(key, qDefault).equals(q720p)) {
                    RecordService.setVideoQuality(RecordService.VideoQuality.MED_720P);
                }
                break;
            case KEY_PREF_LOCATION:
                if (sharedPreferences.getBoolean(key, true)
                        && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

                    ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, PERM_REQUEST_LOCATION);
                }
                break;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String permissions[], @NonNull int[] grantResults) {

        switch (requestCode) {

            case PERM_REQUEST_LOCATION: {

                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_DENIED) {

                    CheckBoxPreference preference = (CheckBoxPreference) settingsFragment.findPreference(KEY_PREF_LOCATION);
                    preference.setChecked(false);
                }
            }
        }
    }
}
