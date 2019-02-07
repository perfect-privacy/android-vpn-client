/*
 * Copyright (C) 2012-2018 Tobias Brunner
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
import android.net.VpnService;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentTransaction;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.app.AppCompatDialogFragment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.perfectprivacy.android.R;
import org.perfectprivacy.android.data.VpnProfile;
import org.perfectprivacy.android.data.VpnProfileDataSource;
import org.perfectprivacy.android.data.VpnType;
import org.perfectprivacy.android.data.VpnType.VpnTypeFeature;
import org.perfectprivacy.android.logic.VpnStateService;
import org.perfectprivacy.android.logic.VpnStateService.State;
import org.perfectprivacy.android.ui.adapter.VpnProfileAdapter;
import org.perfectprivacy.android.utils.Constants;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Scanner;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;

import static java.util.stream.Collectors.toList;

public class VpnProfileControlActivity extends AppCompatActivity
{
	public static final String START_PROFILE = "org.perfectprivacy.android.action.START_PROFILE";
	public static final String DISCONNECT = "org.perfectprivacy.android.action.DISCONNECT";
	public static final String REFRESH_SERVERLIST = "org.perfectprivacy.android.action.REFRESH_SERVERLIST";
	public static final String EXTRA_VPN_PROFILE_ID = "org.perfectprivacy.android.VPN_PROFILE_ID";

	private static final int PREPARE_VPN_SERVICE = 0;
	private static final String WAITING_FOR_RESULT = "WAITING_FOR_RESULT";
	private static final String PROFILE_NAME = "PROFILE_NAME";
	private static final String PROFILE_REQUIRES_PASSWORD = "REQUIRES_PASSWORD";
	private static final String PROFILE_RECONNECT = "RECONNECT";
	private static final String PROFILE_DISCONNECT = "DISCONNECT";
	private static final String DIALOG_TAG = "Dialog";

	private Bundle mProfileInfo;
	private boolean mWaitingForResult;
	private VpnStateService mService;
	private final ServiceConnection mServiceConnection = new ServiceConnection()
	{
		@Override
		public void onServiceDisconnected(ComponentName name)
		{
			mService = null;
		}

		@Override
		public void onServiceConnected(ComponentName name, IBinder service)
		{
			mService = ((VpnStateService.LocalBinder)service).getService();
			handleIntent();
		}
	};

	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		if (savedInstanceState != null)
		{
			mWaitingForResult = savedInstanceState.getBoolean(WAITING_FOR_RESULT, false);
		}
		this.bindService(new Intent(this, VpnStateService.class),
						 mServiceConnection, Service.BIND_AUTO_CREATE);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState)
	{
		super.onSaveInstanceState(outState);
		outState.putBoolean(WAITING_FOR_RESULT, mWaitingForResult);
	}

	@Override
	protected void onDestroy()
	{
		super.onDestroy();
		if (mService != null)
		{
			this.unbindService(mServiceConnection);
		}
	}

	/**
	 * Due to launchMode=singleTop this is called if the Activity already exists
	 */
	@Override
	protected void onNewIntent(Intent intent)
	{
		super.onNewIntent(intent);

		/* store this intent in case the service is not yet connected or the activity is restarted */
		setIntent(intent);

		if (mService != null)
		{
			handleIntent();
		}
	}

	/**
	 * Prepare the VpnService. If this succeeds the current VPN profile is
	 * started.
	 *
	 * @param profileInfo a bundle containing the information about the profile to be started
	 */
	protected void prepareVpnService(Bundle profileInfo)
	{
		Intent intent;

		if (mWaitingForResult)
		{
			mProfileInfo = profileInfo;
			return;
		}

		try
		{
			intent = VpnService.prepare(this);
		}
		catch (IllegalStateException ex)
		{
			/* this happens if the always-on VPN feature (Android 4.2+) is activated */
			VpnNotSupportedError.showWithMessage(this, R.string.vpn_not_supported_during_lockdown);
			return;
		}
		catch (NullPointerException ex)
		{
			/* not sure when this happens exactly, but apparently it does */
			VpnNotSupportedError.showWithMessage(this, R.string.vpn_not_supported);
			return;
		}
		/* store profile info until the user grants us permission */
		mProfileInfo = profileInfo;
		if (intent != null)
		{
			try
			{
				mWaitingForResult = true;
				startActivityForResult(intent, PREPARE_VPN_SERVICE);
			}
			catch (ActivityNotFoundException ex)
			{
				/* it seems some devices, even though they come with Android 4,
				 * don't have the VPN components built into the system image.
				 * com.android.vpndialogs/com.android.vpndialogs.ConfirmDialog
				 * will not be found then */
				VpnNotSupportedError.showWithMessage(this, R.string.vpn_not_supported);
				mWaitingForResult = false;
			}
		}
		else
		{	/* user already granted permission to use VpnService */
			onActivityResult(PREPARE_VPN_SERVICE, RESULT_OK, null);
		}
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data)
	{
		switch (requestCode)
		{
			case PREPARE_VPN_SERVICE:
				mWaitingForResult = false;
				if (resultCode == RESULT_OK && mProfileInfo != null)
				{
					if (mService != null)
					{
						mService.connect(mProfileInfo, true);
					}
					finish();
				}
				else
				{	/* this happens if the always-on VPN feature is activated by a different app or the user declined */
					if (getSupportFragmentManager().isStateSaved())
					{	/* onActivityResult() might be called when we aren't active anymore e.g. if the
						 * user pressed the home button, if the activity is started again we land here
						 * before onNewIntent() is called */
						return;
					}
					VpnNotSupportedError.showWithMessage(this, R.string.vpn_not_supported_no_permission);
				}
				break;
			default:
				super.onActivityResult(requestCode, resultCode, data);
		}
	}

	/**
	 * Check if we are currently connected to a VPN connection
	 *
	 * @return true if currently connected
	 */
	private boolean isConnected()
	{
		if (mService == null)
		{
			return false;
		}
		if (mService.getErrorState() != VpnStateService.ErrorState.NO_ERROR)
		{	/* allow reconnecting (even to a different profile) without confirmation if there is an error */
			return false;
		}
		return (mService.getState() == State.CONNECTED || mService.getState() == State.CONNECTING);
	}

	/**
	 * Start the given VPN profile
	 *
	 * @param profile VPN profile
	 */
	public void startVpnProfile(VpnProfile profile)
	{
		Bundle profileInfo = new Bundle();
		profileInfo.putString(VpnProfileDataSource.KEY_UUID, profile.getUUID().toString());
		profileInfo.putString(VpnProfileDataSource.KEY_USERNAME, profile.getUsername());
		profileInfo.putString(VpnProfileDataSource.KEY_PASSWORD, profile.getPassword());
		profileInfo.putBoolean(PROFILE_REQUIRES_PASSWORD, profile.getVpnType().has(VpnTypeFeature.USER_PASS));
		profileInfo.putString(PROFILE_NAME, profile.getName());

		removeFragmentByTag(DIALOG_TAG);

		if (isConnected())
		{
			profileInfo.putBoolean(PROFILE_RECONNECT, mService.getProfile().getUUID().equals(profile.getUUID()));

			ConfirmationDialog dialog = new ConfirmationDialog();
			dialog.setArguments(profileInfo);
			dialog.show(this.getSupportFragmentManager(), DIALOG_TAG);
			return;
		}
		startVpnProfile(profileInfo);
	}

	/**
	 * Start the given VPN profile asking the user for a password if required.
	 *
	 * @param profileInfo data about the profile
	 */
	private void startVpnProfile(Bundle profileInfo)
	{
		if (profileInfo.getBoolean(PROFILE_REQUIRES_PASSWORD) &&
			profileInfo.getString(VpnProfileDataSource.KEY_PASSWORD) == null)
		{
			LoginDialog login = new LoginDialog();
			login.setArguments(profileInfo);
			login.show(getSupportFragmentManager(), DIALOG_TAG);
			return;
		}
		prepareVpnService(profileInfo);
	}

	/**
	 * Start the VPN profile referred to by the given intent. Displays an error
	 * if the profile doesn't exist.
	 *
	 * @param intent Intent that caused us to start this
	 */
	private void startVpnProfile(Intent intent)
	{
		VpnProfile profile = null;

		VpnProfileDataSource dataSource = new VpnProfileDataSource(this);
		dataSource.open();
		String profileUUID = intent.getStringExtra(EXTRA_VPN_PROFILE_ID);
		if (profileUUID != null)
		{
			profile = dataSource.getVpnProfile(profileUUID);
		}
		else
		{
			long profileId = intent.getLongExtra(EXTRA_VPN_PROFILE_ID, 0);
			if (profileId > 0)
			{
				profile = dataSource.getVpnProfile(profileId);
			}
		}
		dataSource.close();

		if (profile != null)
		{
			startVpnProfile(profile);
		}
		else
		{
			Toast.makeText(this, R.string.profile_not_found, Toast.LENGTH_LONG).show();
			finish();
		}
	}

	/**
	 * Disconnect the current connection, if any (silently ignored if there is no connection).
	 *
	 * @param intent Intent that caused us to start this
	 */
	private void disconnect(Intent intent)
	{
		VpnProfile profile = null;

		removeFragmentByTag(DIALOG_TAG);

		String profileUUID = intent.getStringExtra(EXTRA_VPN_PROFILE_ID);
		if (profileUUID != null)
		{
			VpnProfileDataSource dataSource = new VpnProfileDataSource(this);
			dataSource.open();
			profile = dataSource.getVpnProfile(profileUUID);
			dataSource.close();
		}

		if (mService != null)
		{
			if (mService.getState() == State.CONNECTED ||
				mService.getState() == State.CONNECTING)
			{
				if (profile != null && profile.equals(mService.getProfile()))
				{	/* allow explicit termination without confirmation */
					mService.disconnect();
					finish();
					return;
				}
				Bundle args = new Bundle();
				args.putBoolean(PROFILE_DISCONNECT, true);

				ConfirmationDialog dialog = new ConfirmationDialog();
				dialog.setArguments(args);
				dialog.show(this.getSupportFragmentManager(), DIALOG_TAG);
			}
			else
			{
				finish();
			}
		}
	}

	/**
	 * Refreshes the server list of VPN is disconnected.
	 */
	public void refreshServerList() {
		//Check if connection is currently active
		if (!isConnected()) {
			new RefreshServerlistTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "");
		} else {
			//Print error-message to user
			Toast.makeText(this, R.string.disconnect_before_refreshing, Toast.LENGTH_LONG).show();
		}
	}

	/**
	 * Handle the Intent of this Activity depending on its action
	 */
	private void handleIntent()
	{
		Intent intent = getIntent();

		if (START_PROFILE.equals(intent.getAction()))
		{
			startVpnProfile(intent);
		}
		else if (DISCONNECT.equals(intent.getAction()))
		{
			disconnect(intent);
		}
		else if (REFRESH_SERVERLIST.equals(intent.getAction()))
		{
			refreshServerList();
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
	 * Class that displays a confirmation dialog if a VPN profile is already connected
	 * and then initiates the selected VPN profile if the user confirms the dialog.
	 */
	public static class ConfirmationDialog extends AppCompatDialogFragment
	{
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState)
		{
			final Bundle profileInfo = getArguments();
			int icon = android.R.drawable.ic_dialog_alert;
			int title = R.string.connect_profile_question;
			int message = R.string.replaces_active_connection;
			int button = R.string.connect;

			if (profileInfo.getBoolean(PROFILE_RECONNECT))
			{
				icon = android.R.drawable.ic_dialog_info;
				title = R.string.vpn_connected;
				message = R.string.vpn_profile_connected;
				button = R.string.reconnect;
			}
			else if (profileInfo.getBoolean(PROFILE_DISCONNECT))
			{
				title = R.string.disconnect_question;
				message = R.string.disconnect_active_connection;
				button = R.string.disconnect;
			}

			DialogInterface.OnClickListener connectListener = new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(DialogInterface dialog, int which)
				{
					VpnProfileControlActivity activity = (VpnProfileControlActivity)getActivity();
					activity.startVpnProfile(profileInfo);
				}
			};
			DialogInterface.OnClickListener disconnectListener = new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(DialogInterface dialog, int which)
				{
					VpnProfileControlActivity activity = (VpnProfileControlActivity)getActivity();
					if (activity.mService != null)
					{
						activity.mService.disconnect();
					}
					activity.finish();
				}
			};
			DialogInterface.OnClickListener cancelListener = new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(DialogInterface dialog, int which)
				{
					getActivity().finish();
				}
			};

			AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
				.setIcon(icon)
				.setTitle(String.format(getString(title), profileInfo.getString(PROFILE_NAME)))
				.setMessage(message);

			if (profileInfo.getBoolean(PROFILE_DISCONNECT))
			{
				builder.setPositiveButton(button, disconnectListener);
			}
			else
			{
				builder.setPositiveButton(button, connectListener);
			}

			if (profileInfo.getBoolean(PROFILE_RECONNECT))
			{
				builder.setNegativeButton(R.string.disconnect, disconnectListener);
				builder.setNeutralButton(android.R.string.cancel, cancelListener);
			}
			else
			{
				builder.setNegativeButton(android.R.string.cancel, cancelListener);
			}
			return builder.create();
		}

		@Override
		public void onCancel(DialogInterface dialog)
		{
			getActivity().finish();
		}
	}

	/**
	 * Class that displays a login dialog and initiates the selected VPN
	 * profile if the user confirms the dialog.
	 */
	public static class LoginDialog extends AppCompatDialogFragment
	{
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState)
		{
			final Bundle profileInfo = getArguments();
			LayoutInflater inflater = getActivity().getLayoutInflater();
			View view = inflater.inflate(R.layout.login_dialog, null);
			EditText username = (EditText)view.findViewById(R.id.username);
			username.setText(profileInfo.getString(VpnProfileDataSource.KEY_USERNAME));
			final EditText password = (EditText)view.findViewById(R.id.password);

			AlertDialog.Builder adb = new AlertDialog.Builder(getActivity());
			adb.setView(view);
			adb.setTitle(getString(R.string.login_title));
			adb.setPositiveButton(R.string.login_confirm, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(DialogInterface dialog, int whichButton)
				{
					VpnProfileControlActivity activity = (VpnProfileControlActivity)getActivity();
					profileInfo.putString(VpnProfileDataSource.KEY_PASSWORD, password.getText().toString().trim());
					activity.prepareVpnService(profileInfo);
				}
			});
			adb.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(DialogInterface dialog, int which)
				{
					getActivity().finish();
				}
			});
			return adb.create();
		}

		@Override
		public void onCancel(DialogInterface dialog)
		{
			getActivity().finish();
		}
	}

	/**
	 * Class representing an error message which is displayed if VpnService is
	 * not supported on the current device.
	 */
	public static class VpnNotSupportedError extends AppCompatDialogFragment
	{
		static final String ERROR_MESSAGE_ID = "org.perfectprivacy.android.VpnNotSupportedError.MessageId";

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
						getActivity().finish();
					}
				}).create();
		}

		@Override
		public void onCancel(DialogInterface dialog)
		{
			getActivity().finish();
		}
	}

	/**
	 * Class that loads or reloads the cached serverlist.
	 */
	public class RefreshServerlistTask extends AsyncTask<String, String, Void> {

		private ProgressDialog progressDialog = new ProgressDialog(VpnProfileControlActivity.this);

		private String rawJSON = "";

		@Override
		protected void onPreExecute() {
			//setProgressBarIndeterminateVisibility(true);
			progressDialog.setMessage(getString(R.string.updating_serverlist));
			progressDialog.show();
			progressDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
				public void onDismiss(DialogInterface arg0) {
					RefreshServerlistTask.this.cancel(true);
				}
			});
		}

		@Override
		protected Void doInBackground(String... params) {

			rawJSON = "";

			try {
				URL url = new URL("https://www.perfect-privacy.com/api/serverlocations.json");
				HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
				urlConnection.setUseCaches(false);
				urlConnection.setRequestMethod("GET");
				urlConnection.setDoOutput(false);
				urlConnection.setDoInput(true);
				urlConnection.setConnectTimeout(10000);
				urlConnection.setReadTimeout(10000);

				try {
					//Get JSON-Data
					if (urlConnection.getResponseCode() == HttpsURLConnection.HTTP_OK) {
						InputStream is = urlConnection.getInputStream();
						rawJSON = new Scanner(is, "UTF-8").useDelimiter("\\A").next();
						is.close();
					}
					urlConnection.disconnect();
				} catch (SSLHandshakeException e) {
					e.printStackTrace();
				} catch (SocketTimeoutException e) {
					Toast.makeText(VpnProfileControlActivity.this, "Connection timed out!", Toast.LENGTH_LONG).show();
				} catch (Exception e) {
					e.printStackTrace();
				}

			} catch (Exception e) {
				e.printStackTrace();
			}

			return null;
		}

		@Override
		protected void onPostExecute(Void v) {
			// Parse JSON-API data
			try {
				// Create JSON Object of stringified JSON-response
				JSONObject jObject = new JSONObject(rawJSON);
				Iterator<String> keys = jObject.keys();
				Set<String> processedServers = new HashSet<>();

				// Check if servers were found
				if (!jObject.keys().hasNext()) {
					Toast.makeText(VpnProfileControlActivity.this, getString(R.string.error_refreshing_serverlist), Toast.LENGTH_LONG).show();
					Log.i("Serverlist refresh", "No servers found");
					return;
				}

				// Parse each server and create profile
				VpnProfileDataSource dataSource = new VpnProfileDataSource(VpnProfileControlActivity.this);
				dataSource.open();
				dataSource.deleteVpnProfiles();

				String globalUsername = dataSource.getSettingUsername();
				String globalPassword = dataSource.getSettingPassword();

				while (keys.hasNext()) {
					String curKey = keys.next();
					String curServerAddress = curKey.replaceAll("\\d", "");
					//Log.i("Json Key", "Server: " + curServerAddress);

					// Since we are ignoring numbers only process every server once
					if (!processedServers.contains(curServerAddress)) {
						// Create profile for current server and insert into database
						JSONObject serverData = jObject.getJSONObject(curKey);
						processedServers.add(curServerAddress);

						VpnProfile profile = new VpnProfile();
						profile.setName(serverData.getString("city"));
						profile.setGateway(curServerAddress);
						profile.setVpnType(VpnType.IKEV2_EAP);
						profile.setUsername(globalUsername);
						profile.setPassword(globalPassword);
						profile.setCountry(serverData.getString("country_short"));

						dataSource.insertProfile(profile);
					}

				}

				dataSource.close();
				updateProfileList();

			} catch (JSONException e) {
				Toast.makeText(VpnProfileControlActivity.this, getString(R.string.error_refreshing_serverlist), Toast.LENGTH_LONG).show();
				Log.e("JSONException", "Error: " + e.toString());
			} finally {
				this.progressDialog.dismiss();
			}
		}
	}

	public void updateProfileList() {
		Intent intent = new Intent(Constants.VPN_PROFILES_CHANGED);
		intent.putExtra(Constants.VPN_PROFILES_ALL, true);
		LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
	}
}
