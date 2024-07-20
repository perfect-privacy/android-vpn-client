/*
 * Copyright (C) 2012-2020 Tobias Brunner
 *
 * Copyright (C) secunet Security Networks AG
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

import android.Manifest;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.perfectprivacy.android.data.VpnProfileSource;
import org.perfectprivacy.android.R;
import org.perfectprivacy.android.data.VpnProfile;
import org.perfectprivacy.android.data.VpnProfileDataSource;
import org.perfectprivacy.android.data.VpnType;
import org.perfectprivacy.android.data.VpnType.VpnTypeFeature;
import org.perfectprivacy.android.logic.VpnStateService;
import org.perfectprivacy.android.logic.VpnStateService.State;
import org.perfectprivacy.android.utils.Constants;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Scanner;
import java.util.Set;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.preference.PreferenceManager;

import org.perfectprivacy.android.R;
import org.perfectprivacy.android.data.VpnProfile;
import org.perfectprivacy.android.data.VpnProfileDataSource;
import org.perfectprivacy.android.data.VpnType.VpnTypeFeature;
import org.perfectprivacy.android.logic.VpnStateService;
import org.perfectprivacy.android.logic.VpnStateService.State;
import org.perfectprivacy.android.utils.Constants;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDialogFragment;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.preference.PreferenceManager;
public class VpnProfileControlActivity extends AppCompatActivity
{
	public static final String START_PROFILE = "org.perfectprivacy.android.action.START_PROFILE";
	public static final String DISCONNECT = "org.perfectprivacy.android.action.DISCONNECT";
	public static final String REFRESH_SERVERLIST = "org.perfectprivacy.android.action.REFRESH_SERVERLIST";
	public static final String EXTRA_VPN_PROFILE_UUID = "org.perfectprivacy.android.VPN_PROFILE_UUID";
	public static final String EXTRA_VPN_PROFILE_ID = "org.perfectprivacy.android.VPN_PROFILE_ID";

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

	private final ActivityResultLauncher<Intent> mPrepareVpnService = registerForActivityResult(
		new ActivityResultContracts.StartActivityForResult(),
		result -> {
			mWaitingForResult = false;
			if (result.getResultCode() == RESULT_OK && mProfileInfo != null)
			{
				onVpnServicePrepared();
			}
			else
			{	/* this happens if the always-on VPN feature is activated by a different app or the user declined */
				VpnNotSupportedError.showWithMessage(this, R.string.vpn_not_supported_no_permission);
			}
		}
	);

	private final ActivityResultLauncher<Intent> mAddToPowerWhitelist = registerForActivityResult(
		new ActivityResultContracts.StartActivityForResult(),
		result -> {
			mWaitingForResult = false;
			if (checkNotificationPermission())
			{
				performConnect();
			}
		}
	);

	private final ActivityResultLauncher<String> mRequestPermission = registerForActivityResult(
		new ActivityResultContracts.RequestPermission(),
		result -> {
			mWaitingForResult = false;
			performConnect();
		}
	);

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
				mPrepareVpnService.launch(intent);
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
			onVpnServicePrepared();
		}
	}

	/**
	 * Called to actually perform the connection and terminating the activity.
	 */
	protected void performConnect()
	{
		if (mProfileInfo != null && mService != null)
		{
			mService.connect(mProfileInfo, true);
		}
		finish();
	}

	/**
	 * Called once the VpnService has been prepared and permission has been granted
	 * by the user.
	 */
	protected void onVpnServicePrepared()
	{
		if (checkPowerWhitelist() && checkNotificationPermission())
		{
			performConnect();
		}
	}

	/**
	 * Check if we have permission to display notifications to the user, if necessary,
	 * ask the user to allow this.
	 *
	 * @return true if profile can be initiated immediately
	 */
	private boolean checkNotificationPermission()
	{
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
			ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
		{
			mWaitingForResult = true;
			mRequestPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
			return false;
		}
		return true;
	}

	/**
	 * Check if we are on the system's power whitelist, if necessary, or ask the user
	 * to add us.
	 *
	 * @return true if profile can be initiated immediately
	 */
	private boolean checkPowerWhitelist()
	{
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
		{
			PowerManager pm = (PowerManager)this.getSystemService(Context.POWER_SERVICE);
			SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(this);
			if (!pm.isIgnoringBatteryOptimizations(this.getPackageName()) &&
				!pref.getBoolean(Constants.PREF_IGNORE_POWER_WHITELIST, false))
			{
				if (getSupportFragmentManager().isStateSaved())
				{	/* we might get called via service connection and manual onActivityResult()
					 * call when the activity is not active anymore and fragment transactions
					 * would cause an illegalStateException */
					return false;
				}
				PowerWhitelistRequired whitelist = new PowerWhitelistRequired();
				whitelist.show(getSupportFragmentManager(), DIALOG_TAG);
				return false;
			}
		}
		return true;
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
			MainActivity.LoginDialog login = new MainActivity.LoginDialog();
			profileInfo.putBoolean("finish_on_exit", true);
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

		VpnProfileDataSource dataSource = new VpnProfileSource(this);
		dataSource.open();
		String profileUUID = intent.getStringExtra(EXTRA_VPN_PROFILE_UUID);
		if (profileUUID == null)
		{
			profileUUID = intent.getStringExtra(EXTRA_VPN_PROFILE_ID);
		}
		if (profileUUID != null)
		{
			profile = dataSource.getVpnProfile(profileUUID);
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

		String profileUUID = intent.getStringExtra(EXTRA_VPN_PROFILE_UUID);
		if (profileUUID == null)
		{
			profileUUID = intent.getStringExtra(EXTRA_VPN_PROFILE_ID);
		}
		if (profileUUID != null)
		{
			VpnProfileDataSource dataSource = new VpnProfileSource(this);
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
			finish();
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
			String title = String.format(getString(R.string.connect_profile_question), profileInfo.getString(PROFILE_NAME));
			int message = R.string.replaces_active_connection;
			int button = R.string.connect;

			if (profileInfo.getBoolean(PROFILE_RECONNECT))
			{
				icon = android.R.drawable.ic_dialog_info;
				title = getString(R.string.vpn_connected);
				message = R.string.vpn_profile_connected;
				button = R.string.reconnect;
			}
			else if (profileInfo.getBoolean(PROFILE_DISCONNECT))
			{
				title = getString(R.string.disconnect_question);
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
				.setTitle(title)
				.setMessage(message);


			if (profileInfo.getBoolean(PROFILE_RECONNECT))
			{
				//builder.setNegativeButton(R.string.disconnect, disconnectListener);
				builder.setNeutralButton(android.R.string.ok, cancelListener);
			}
			else
			{
				if (profileInfo.getBoolean(PROFILE_DISCONNECT))
				{
					builder.setPositiveButton(button, disconnectListener);
				}
				else
				{
					builder.setPositiveButton(button, connectListener);
				}
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
			EditText username = view.findViewById(R.id.username);
			username.setText(profileInfo.getString(VpnProfileDataSource.KEY_USERNAME));
			final EditText password = view.findViewById(R.id.password);

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
	 * Class that displays a warning before asking the user to add the app to the
	 * device's power whitelist.
	 */
	public static class PowerWhitelistRequired extends AppCompatDialogFragment
	{
		@Override
		public Dialog onCreateDialog(Bundle savedInstanceState)
		{
			return new AlertDialog.Builder(getActivity())
				.setTitle(R.string.power_whitelist_title)
				.setMessage(R.string.power_whitelist_text)
				.setPositiveButton(android.R.string.ok, (dialog, id) -> {
					VpnProfileControlActivity activity = (VpnProfileControlActivity)getActivity();
					activity.mWaitingForResult = true;
					Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
											   Uri.parse("package:" + activity.getPackageName()));
					activity.mAddToPowerWhitelist.launch(intent);
				}).create();
		}

		@Override
		public void onCancel(@NonNull DialogInterface dialog)
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
			progressDialog.setMessage(getString(R.string.updating_serverlist));
			progressDialog.show();
			progressDialog.setOnDismissListener(dialog -> {
				RefreshServerlistTask.this.cancel(true);
				finish();
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

				VpnProfileDataSource dataSource = new VpnProfileDataSource(VpnProfileControlActivity.this);
				dataSource.open();

				// Try to keep default vpn profile if explicitly set by user
				SharedPreferences pref = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
				String uuid = pref.getString(Constants.PREF_DEFAULT_VPN_PROFILE, null);
				VpnProfile old_def_profil = null;
				boolean def_profile_restore_success = true;  // Indicates whether or not a needed profile restore was successfull
				if (uuid != null && !uuid.equals(Constants.PREF_DEFAULT_VPN_PROFILE_MRU)) {
					old_def_profil = dataSource.getVpnProfile(uuid);
					def_profile_restore_success = false;
				}

				// Parse each server and create profile
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
						profile.setCertificateAlias("user:ppca");
						profile.setIkeProposal("aes256gcm16-prfsha512-curve25519");
						profile.setEspProposal("aes256gcm16-curve25519");

						profile.setUsername(globalUsername);
						profile.setPassword(globalPassword);
						profile.setCountry(serverData.getString("country_short"));

						dataSource.insertProfile(profile);

						// Try to restore default profile
						if (old_def_profil != null) {
							if (old_def_profil.getGateway().equals(profile.getGateway()) &&
								old_def_profil.getName().equals(profile.getName())) {

								pref.edit()
										.putString(Constants.PREF_DEFAULT_VPN_PROFILE, profile.getUUID().toString())
										.apply();
								def_profile_restore_success = true;
							}
						}
					}

				}

				// Check if default VPN profile was restored correctly
				if (!def_profile_restore_success) {
					pref.edit()
							.putString(Constants.PREF_DEFAULT_VPN_PROFILE, Constants.PREF_DEFAULT_VPN_PROFILE_MRU)
							.apply();
				}

				dataSource.close();
				updateProfileList();

			} catch (JSONException e) {
				Toast.makeText(VpnProfileControlActivity.this, getString(R.string.error_refreshing_serverlist), Toast.LENGTH_LONG).show();
				Log.e("JSONException", "Error: " + e.toString());
			} finally {
				this.progressDialog.dismiss();
				finish();
			}
		}
	}

	public void updateProfileList() {
		Intent intent = new Intent(Constants.VPN_PROFILES_CHANGED);
		intent.putExtra(Constants.VPN_PROFILES_ALL, true);
		LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
	}
}
