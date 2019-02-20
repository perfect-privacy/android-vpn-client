package org.perfectprivacy.android.ui;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import org.perfectprivacy.android.R;

public class SettingsWrapperFragment extends Fragment {

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

        return view;
    }

}
