package org.perfectprivacy.android.ui;

import static android.app.Activity.RESULT_OK;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import org.perfectprivacy.android.R;
import org.perfectprivacy.android.data.VpnProfile;
import org.perfectprivacy.android.data.VpnProfileDataSource;
import org.perfectprivacy.android.utils.Constants;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.SortedSet;
import java.util.TreeSet;

public class SettingsWrapperFragment extends Fragment {
	private VpnProfile.SelectedAppsHandling mSelectedAppsHandling = VpnProfile.SelectedAppsHandling.SELECTED_APPS_DISABLE;
	private SortedSet<String> mSelectedApps = new TreeSet<>();
	private RelativeLayout mSelectApps;
	private Spinner mSelectSelectedAppsHandling;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.settings_wrapper, container, false);

        // Set current app version
        try {
            PackageInfo pInfo = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);

            TextView appVersionName = view.findViewById(R.id.app_version_name);
            appVersionName.setText(String.format("%s: %s", getString(R.string.app_version), pInfo.versionName));
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        // Enable Always-On VPN setup button if current android version supports this feature
        Button setupAlwayOnVPN = view.findViewById(R.id.setup_alway_on_vpn);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            setupAlwayOnVPN.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_VPN_SETTINGS)));
        } else {
            setupAlwayOnVPN.setEnabled(false);

            ((TextView) view.findViewById(R.id.alway_on_vpn_hint)).setText(
                getString(R.string.always_on_vpn_not_supported)
            );
        }

		SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getContext());

		mSelectSelectedAppsHandling = view.findViewById(R.id.apps_handling);
		mSelectSelectedAppsHandling.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
		{
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
			{
				mSelectedAppsHandling = VpnProfile.SelectedAppsHandling.values()[position];
				final SharedPreferences.Editor editor = pref.edit();
				editor.putInt(Constants.PREF_SELECTED_APPS_HANDLING, mSelectedAppsHandling.getValue());
				editor.apply();
				updateAppsSelector();
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent)
			{	/* should not happen */
				mSelectedAppsHandling = VpnProfile.SelectedAppsHandling.SELECTED_APPS_DISABLE;
				final SharedPreferences.Editor editor = pref.edit();
				editor.putInt(Constants.PREF_SELECTED_APPS_HANDLING, mSelectedAppsHandling.getValue());
				editor.apply();
				updateAppsSelector();
			}
		});

		mSelectApps = view.findViewById(R.id.select_applications);
		mSelectApps.setOnClickListener(new View.OnClickListener()
		{
			@Override
			public void onClick(View v)
			{
				Intent intent = new Intent(getContext(), SelectedApplicationsActivity.class);
				intent.putExtra(VpnProfileDataSource.KEY_SELECTED_APPS_LIST, new ArrayList<>(mSelectedApps));
				intent.putExtra(VpnProfileDataSource.KEY_READ_ONLY, false);
				mSelectApplications.launch(intent);
			}
		});

		int v = pref.getInt(Constants.PREF_SELECTED_APPS_HANDLING, 0);
		if(v==VpnProfile.SelectedAppsHandling.SELECTED_APPS_DISABLE.ordinal()){
			mSelectedAppsHandling = VpnProfile.SelectedAppsHandling.SELECTED_APPS_DISABLE;
		}
		else if(v==VpnProfile.SelectedAppsHandling.SELECTED_APPS_EXCLUDE.ordinal()){
			mSelectedAppsHandling = VpnProfile.SelectedAppsHandling.SELECTED_APPS_EXCLUDE;
		}
		else if(v==VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY.ordinal()){
			mSelectedAppsHandling = VpnProfile.SelectedAppsHandling.SELECTED_APPS_ONLY;
		}
		mSelectSelectedAppsHandling.setSelection(mSelectedAppsHandling.ordinal());

		TreeSet<String> set = new TreeSet<>();
		if (!TextUtils.isEmpty(pref.getString(Constants.PREF_SELECTED_APPS,"")))
		{
			set.addAll(Arrays.asList(pref.getString(Constants.PREF_SELECTED_APPS,"").split("\\s+")));
		}
		if (savedInstanceState != null)
		{
			ArrayList<String> selectedApps = savedInstanceState.getStringArrayList(VpnProfileDataSource.KEY_SELECTED_APPS_LIST);
			mSelectedApps = new TreeSet<>(selectedApps);
		}else{
			mSelectedApps = set;
		}
		updateAppsSelector();
        return view;
    }

	private final ActivityResultLauncher<Intent> mSelectApplications = registerForActivityResult(
		new ActivityResultContracts.StartActivityForResult(),
		result -> {
			if (result.getResultCode() == RESULT_OK)
			{
				ArrayList<String> selection = result.getData().getStringArrayListExtra(VpnProfileDataSource.KEY_SELECTED_APPS_LIST);
				mSelectedApps = new TreeSet<>(selection);
				SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getContext());
				final SharedPreferences.Editor editor = pref.edit();
				editor.putString(Constants.PREF_SELECTED_APPS, mSelectedApps.size() > 0 ? TextUtils.join(" ", mSelectedApps) : "");
				editor.apply();
				updateAppsSelector();
			}
		}
	);

	/**
	 * Update the application selection UI
	 */
	private void updateAppsSelector()
	{
		if (mSelectedAppsHandling == VpnProfile.SelectedAppsHandling.SELECTED_APPS_DISABLE)
		{
			mSelectApps.setEnabled(false);
			mSelectApps.setVisibility(View.GONE);
		}
		else
		{
			mSelectApps.setEnabled(true);
			mSelectApps.setVisibility(View.VISIBLE);

			((TextView)mSelectApps.findViewById(android.R.id.text1)).setText(R.string.profile_select_apps);
			String selected;
			switch (mSelectedApps.size())
			{
				case 0:
					selected = getString(R.string.profile_select_no_apps);
					break;
				case 1:
					selected = getString(R.string.profile_select_one_app);
					break;
				default:
					selected = getString(R.string.profile_select_x_apps, mSelectedApps.size());
					break;
			}
			((TextView)mSelectApps.findViewById(android.R.id.text2)).setText(selected);
		}
	}

}
