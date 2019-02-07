/*
 * Copyright (C) 2012-2018 Tobias Brunner
 * Copyright (C) 2012 Giuliano Grassi
 * Copyright (C) 2012 Ralf Sager
 * HSR Hochschule fuer Technik Rapperswil
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation; either version 2 of the License, or (at your
 * option) any later version.  See <http://www.fsf.org/copyleft/gpl.txt>.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * for more details.
 */

package org.perfectprivacy.android.ui;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDialogFragment;
import android.text.format.Formatter;
import android.view.Menu;
import android.view.MenuItem;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.perfectprivacy.android.R;
import org.perfectprivacy.android.data.VpnProfile;
import org.perfectprivacy.android.data.VpnProfileDataSource;
import org.perfectprivacy.android.data.VpnType;
import org.perfectprivacy.android.data.VpnType.VpnTypeFeature;
import org.perfectprivacy.android.logic.CharonVpnService;
import org.perfectprivacy.android.logic.TrustedCertificateManager;
import org.perfectprivacy.android.ui.VpnProfileListFragment.OnVpnProfileSelectedListener;
import org.perfectprivacy.android.ui.adapter.VpnProfileAdapter;
import org.perfectprivacy.android.logic.VpnStateService.State;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity implements OnVpnProfileSelectedListener
{
	public static final String CONTACT_EMAIL = "support@perfect-privacy.com";
	public static final String EXTRA_CRL_LIST = "org.perfectprivacy.android.CRL_LIST";

	/**
	 * Use "bring your own device" (BYOD) features
	 */
	public static final boolean USE_BYOD = true;

	private static final String DIALOG_TAG = "Dialog";

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		setContentView(R.layout.main);

		ActionBar bar = getSupportActionBar();
		bar.setDisplayShowHomeEnabled(true);
		//bar.setTitle("  " + getString(R.string.app_name));
		bar.setTitle("  " + getString(R.string.main_activity_name));
		bar.setDisplayShowTitleEnabled(true);
		bar.setIcon(R.drawable.ic_launcher);

		/* load CA certificates in a background task */
		new LoadCertificatesTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

		//Check if Serverlist-Refresh is neccesarry (0 profiles present)
		updateProfileList();
		VpnProfileDataSource dataSource = new VpnProfileDataSource(this);
		dataSource.open();
		int numVPNProfiles = dataSource.getAllVpnProfiles().size();
		dataSource.close();
		if(numVPNProfiles < 1) {
			refreshServerList();
		}
	}

	public void updateProfileList() {
		VpnProfileDataSource dataSource = new VpnProfileDataSource(this);
		dataSource.open();

		ListView profileList = (ListView)findViewById(R.id.profile_list);
		profileList.setAdapter(new VpnProfileAdapter(getApplicationContext(), R.layout.profile_list_item, dataSource.getAllVpnProfiles()));
		dataSource.close();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu)
	{
		getMenuInflater().inflate(R.menu.main, menu);
		return true;
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu)
	{
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId())
		{
			case R.id.menu_refresh_serverlist:
				refreshServerList();
				return true;
			case R.id.menu_crl_cache:
				clearCRLs();
                return true;
			case R.id.menu_set_login_credentials:
				LoginDialog login = new LoginDialog();
				login.setMainActivity(this);
				login.show(getSupportFragmentManager(), DIALOG_TAG);
				return true;
			case R.id.menu_show_log:
				Intent logIntent = new Intent(this, LogActivity.class);
				startActivity(logIntent);
				return true;
			case R.id.menu_settings:
				Intent settingsIntent = new Intent(this, SettingsActivity.class);
				startActivity(settingsIntent);
				return true;
			case R.id.menu_get_help:
				Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.get_help_url)));
				startActivity(browserIntent);
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}

	public void refreshServerList() {
		Intent intent = new Intent(this, VpnProfileControlActivity.class);
		intent.setAction(VpnProfileControlActivity.REFRESH_SERVERLIST);
		startActivity(intent);
	}

	@Override
	public void onVpnProfileSelected(VpnProfile profile)
	{
		Intent intent = new Intent(this, VpnProfileControlActivity.class);
		intent.setAction(VpnProfileControlActivity.START_PROFILE);
		intent.putExtra(VpnProfileControlActivity.EXTRA_VPN_PROFILE_ID, profile.getUUID().toString());
		startActivity(intent);
	}

	/**
	 * Ask the user whether to clear the CRL cache.
	 */
	private void clearCRLs()
	{
		final String FILE_PREFIX = "crl-";
		ArrayList<String> list = new ArrayList<>();

		for (String file : fileList())
		{
			if (file.startsWith(FILE_PREFIX))
			{
				list.add(file);
			}
		}
		if (list.size() == 0)
		{
			Toast.makeText(this, R.string.clear_crl_cache_msg_none, Toast.LENGTH_SHORT).show();
			return;
		}
		removeFragmentByTag(DIALOG_TAG);

		Bundle args = new Bundle();
		args.putStringArrayList(EXTRA_CRL_LIST, list);

		CRLCacheDialog dialog = new CRLCacheDialog();
		dialog.setArguments(args);
		dialog.show(this.getSupportFragmentManager(), DIALOG_TAG);
	}

	/**
	 * Class that loads the cached CA certificates.
	 */
	private class LoadCertificatesTask extends AsyncTask<Void, Void, TrustedCertificateManager>
	{
		@Override
		protected TrustedCertificateManager doInBackground(Void... params)
		{
			return TrustedCertificateManager.getInstance().load();
		}
	}

	/**
	 * Dismiss dialog if shown
	 */
	public void removeFragmentByTag(String tag)
	{
		FragmentManager fm = getSupportFragmentManager();
		Fragment login = fm.findFragmentByTag(tag);
		if (login != null)
		{
			FragmentTransaction ft = fm.beginTransaction();
			ft.remove(login);
			ft.commit();
		}
	}

	/**
	 * Confirmation dialog to clear CRL cache
	 */
	public static class CRLCacheDialog extends AppCompatDialogFragment
	{
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState)
		{
			final List<String> list = getArguments().getStringArrayList(EXTRA_CRL_LIST);
			String size;
			long s = 0;

			for (String file : list)
			{
				File crl = getActivity().getFileStreamPath(file);
				s += crl.length();
			}
			size = Formatter.formatFileSize(getActivity(), s);

			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
					.setTitle(R.string.clear_crl_cache_title)
					.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener()
					{
						@Override
						public void onClick(DialogInterface dialog, int which)
						{
							dismiss();
						}
					})
					.setPositiveButton(R.string.clear, new DialogInterface.OnClickListener()
					{
						@Override
						public void onClick(DialogInterface dialog, int whichButton)
						{
							for (String file : list)
							{
								getActivity().deleteFile(file);
							}
						}
					});
			builder.setMessage(getActivity().getResources().getQuantityString(R.plurals.clear_crl_cache_msg, list.size(), list.size(), size));
			return builder.create();
		}
	}

	/**
	 * Class that displays a login dialog and initiates the selected VPN
	 * profile if the user confirms the dialog.
	 */
	public static class LoginDialog extends AppCompatDialogFragment {

		private MainActivity mainActivity;

		public void setMainActivity(MainActivity mainActivity) {
			this.mainActivity = mainActivity;
		}

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState) {
			// Get attributes
			final Bundle profileInfo = getArguments();

			// Get current login infos
			VpnProfileDataSource dataSource = new VpnProfileDataSource(getContext());
			dataSource.open();

			LayoutInflater inflater = getActivity().getLayoutInflater();
			View view = inflater.inflate(R.layout.login_dialog, null);
			final EditText username = (EditText) view.findViewById(R.id.username);
			username.setText(dataSource.getSettingUsername());
			final EditText password = (EditText) view.findViewById(R.id.password);
			password.setText(dataSource.getSettingPassword());

			dataSource.close();

			// Make register link clickable
			TextView registerLink = (TextView) view.findViewById(R.id.register_now_link);
			registerLink.setMovementMethod(LinkMovementMethod.getInstance());

			// Build Dialog
			AlertDialog.Builder adb = new AlertDialog.Builder(getActivity());
			adb.setView(view);
			adb.setTitle(getString(R.string.login_title));
			adb.setCancelable(false);
			adb.setPositiveButton(R.string.profile_edit_save, null); // Is set later directly on button

			adb.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					dismiss();
				}
			});

			// Create AlertDialog and attach to button listener to allow custom dismissing of dialog
			final AlertDialog ad = adb.create();
			ad.setOnShowListener(new DialogInterface.OnShowListener() {
				@Override
				public void onShow(DialogInterface dialog) {
					// Get positive button and attach onclick listener
					Button button = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
					button.setOnClickListener(new View.OnClickListener() {

						@Override
						public void onClick(View view) {
							VpnProfileDataSource dataSource = new VpnProfileDataSource(getContext());
							dataSource.open();

							String newpass = password.getText().toString();
							String newuser = username.getText().toString();

							// Check validity of new password and username
							if (!newuser.isEmpty() && (newuser.length() < 1 || newpass.length() < 1)) {
								Toast.makeText(getActivity().getBaseContext(), R.string.invalid_user_or_password, Toast.LENGTH_LONG).show();
								// Dialog stays open
								return;
							} else {
								if (newuser.isEmpty()) { newuser = null; }
								if (newpass.isEmpty()) { newpass = null; }
								dataSource.setSettingUsername(newuser);
								dataSource.setSettingPassword(newpass);
								dataSource.updateAllProfilesUsernamePassword(newuser, newpass);
								mainActivity.updateProfileList();

								// Connect to desired profile if dialog was invoked during connection bootstrapping
								if(profileInfo != null && profileInfo.getLong(VpnProfileDataSource.KEY_ID) != 0L) {
									MainActivity activity = (MainActivity) getActivity();
									activity.onVpnProfileSelected(dataSource.getVpnProfile(profileInfo.getLong(VpnProfileDataSource.KEY_ID)));
								}

								dataSource.close();
								ad.dismiss();

								return;
							}
						}
					});
				}
			});

			return ad;
		}
	}

	/**
	 * Class representing an error message which is displayed if VpnService is
	 * not supported on the current device.
	 */
	public static class VpnNotSupportedError extends AppCompatDialogFragment
	{
		static final String ERROR_MESSAGE_ID = "com.perfectprivacy.android.VpnNotSupportedError.MessageId";

		public static void showWithMessage(AppCompatActivity activity, int messageId)
		{
			Bundle bundle = new Bundle();
			bundle.putInt(ERROR_MESSAGE_ID, messageId);
			VpnNotSupportedError dialog = new VpnNotSupportedError();
			dialog.setArguments(bundle);
			dialog.show(activity.getSupportFragmentManager(), DIALOG_TAG);
		}

		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState)
		{
			final Bundle arguments = getArguments();
			final int messageId = arguments.getInt(ERROR_MESSAGE_ID);
			return new AlertDialog.Builder(getActivity())
					.setTitle(R.string.vpn_not_supported_title)
					.setMessage(messageId)
					.setCancelable(false)
					.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener()
					{
						@Override
						public void onClick(DialogInterface dialog, int id)
						{
							dialog.dismiss();
						}
					}).create();
		}
	}

}
