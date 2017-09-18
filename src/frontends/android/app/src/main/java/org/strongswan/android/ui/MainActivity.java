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

package org.strongswan.android.ui;

import android.app.Dialog;
import android.app.ProgressDialog;
import android.app.Service;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
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
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;
import org.strongswan.android.R;
import org.strongswan.android.data.VpnProfile;
import org.strongswan.android.data.VpnProfileDataSource;
import org.strongswan.android.data.VpnType;
import org.strongswan.android.data.VpnType.VpnTypeFeature;
import org.strongswan.android.logic.CharonVpnService;
import org.strongswan.android.logic.TrustedCertificateManager;
import org.strongswan.android.ui.VpnProfileListFragment.OnVpnProfileSelectedListener;
import org.strongswan.android.ui.adapter.VpnProfileAdapter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

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

		//Check if Serverlist-Refresh is neccesarry
		if(getIntent() != null) {
			Bundle extraIntentInfo = getIntent().getExtras();

			//Check if Activity got Extras
			if(extraIntentInfo == null) { new ProfileLoadTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, ""); }
			else {
				//Check specific activity extras
				if (extraIntentInfo.getInt("recreatedFromRefresh") != 1) {
					new ProfileLoadTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, "");
				} else {
					updateProfileList();
				}
			}
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
		/*if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT)
		{
			menu.removeItem(R.id.menu_import_profile);
		}*/
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		switch (item.getItemId())
		{
			/*case R.id.menu_import_profile:
				Intent intent = new Intent(this, VpnProfileImportActivity.class);
				startActivity(intent);
				return true;*/
			/*case R.id.menu_manage_certs:
				Intent certIntent = new Intent(this, TrustedCertificatesActivity.class);
				startActivity(certIntent);
				return true;*/
			//TODO: Add action listeners for other menu items here
			case R.id.menu_refresh_serverlist:
				Toast.makeText(this, "Not implemented yet :(", Toast.LENGTH_LONG).show();
				return true;
			case R.id.menu_crl_cache:
				clearCRLs();
				return true;
			case R.id.menu_show_log:
				Intent logIntent = new Intent(this, LogActivity.class);
				startActivity(logIntent);
				return true;
			case R.id.menu_settings:
				Intent settingsIntent = new Intent(this, SettingsActivity.class);
				startActivity(settingsIntent);
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
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

			TextView registerLink = (TextView) view.findViewById(R.id.register_now_link);
			registerLink.setMovementMethod(LinkMovementMethod.getInstance());

			AlertDialog.Builder adb = new AlertDialog.Builder(getActivity());
			adb.setView(view);
			adb.setTitle(getString(R.string.login_title));
			adb.setPositiveButton(R.string.login_confirm, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(DialogInterface dialog, int whichButton)
				{
					MainActivity activity = (MainActivity)getActivity();
					profileInfo.putString(VpnProfileDataSource.KEY_PASSWORD, password.getText().toString().trim());
					// FIXME
                    Log.e("LoginDialog", "Not implemented! FIXME");
					//activity.prepareVpnService(profileInfo);
				}
			});
			adb.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener()
			{
				@Override
				public void onClick(DialogInterface dialog, int which)
				{
					dismiss();
				}
			});
			return adb.create();
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
							dialog.dismiss();
						}
					}).create();
		}
	}

	/**
	 * Class that loads or reloads the cached CA certificates.
	 */
	private class ProfileLoadTask extends AsyncTask<String, String, Void> {
		private ProgressDialog progressDialog = new ProgressDialog(MainActivity.this);
		String result = "";

		@Override
		protected void onPreExecute() {
			//setProgressBarIndeterminateVisibility(true);
			progressDialog.setMessage(getString(R.string.updating_serverlist));
			progressDialog.show();
			progressDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
				public void onDismiss(DialogInterface arg0) {
					ProfileLoadTask.this.cancel(true);
				}
			});
		}

		@Override
		protected Void doInBackground(String... params) {

			result = "";
			try {
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.FROYO) {
					System.setProperty("http.keepAlive", "false");
				}

				URL url = new URL("https://www.perfect-privacy.com/api/traffic.json");
				HttpsURLConnection urlConnection = (HttpsURLConnection) url.openConnection();
				urlConnection.setUseCaches(false);
				urlConnection.setRequestMethod("GET");
				urlConnection.setDoOutput(false);
				urlConnection.setDoInput(true);
				urlConnection.setConnectTimeout(10000);
				urlConnection.setReadTimeout(10000);
				try {
					//urlConnection.connect();
					//Get JSON-Data
					if (urlConnection.getResponseCode() == HttpsURLConnection.HTTP_OK) {
						InputStream is = urlConnection.getInputStream();
						BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(is));

						//Converts JSON-Response into usable data
						String line;
						while ((line = bufferedReader.readLine()) != null) {
							result = result + line;
						}

						is.close();
					}
					urlConnection.disconnect();
				} catch (SSLHandshakeException e) {
					if (e != null) {
						e.printStackTrace();
					}
				} catch (SocketTimeoutException e) {
					if (e != null) {
						Toast.makeText(MainActivity.this, "Connection timed out!", Toast.LENGTH_LONG).show();
					}
				} catch (Exception e) {
					if (e != null) {
						e.printStackTrace();
					}
				}

			} catch (MalformedURLException e) {
				// TODO Auto-generated catch block
				if (e != null) {
					e.printStackTrace();
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				if (e != null) {
					e.printStackTrace();
				}
			} catch (Exception e) {
				if (e != null) {
					e.printStackTrace();
				}
			}

			return null;

		}

		@Override
		protected void onPostExecute(Void v) {
			//parse JSON data
			try {
				JSONObject jObject = new JSONObject(result);
				Iterator<?> keys = jObject.keys();
				Map<String, Integer> serverBwMaxMap = new HashMap<>();
				Map<String, Integer> serverBwInMap = new HashMap<>();
				while (keys.hasNext()) {
					String key = (String) keys.next();
					Log.i("Json Key", "Server: " + key);

					if (jObject.get(key) instanceof JSONObject) {
						//Generate nice looking profile names
						String server = key;
						server = server.replaceAll("\\d+", "");
						Integer bw_in = Integer.parseInt(((JSONObject) jObject.get(key)).get("bandwidth_in").toString());
						Integer bw_out = Integer.parseInt(((JSONObject) jObject.get(key)).get("bandwidth_out").toString());
						Integer bw_max = Integer.parseInt(((JSONObject) jObject.get(key)).get("bandwidth_max").toString());
						Integer val = serverBwMaxMap.get(server);
						if (val == null) {
							serverBwMaxMap.put(server, bw_max);
						} else {
							serverBwMaxMap.put(server, bw_max + val);
						}
						Integer val1 = serverBwInMap.get(server);
						if (val1 == null) {
							serverBwInMap.put(server, bw_in);
						} else {
							serverBwInMap.put(server, bw_in + val1);
						}

					}

				}
				if (serverBwMaxMap.entrySet().size() > 0) {
					VpnProfileDataSource dataSource = new VpnProfileDataSource(MainActivity.this);

					dataSource.open();
					dataSource.deleteVpnProfiles();

					String new_username = dataSource.getSettingUsername();
					String new_password = dataSource.getSettingPassword();

					for (Map.Entry<String, Integer> entry : serverBwMaxMap.entrySet()) {
						Log.i("Json Key", entry.getKey() + " - " + serverBwInMap.get(entry.getKey()) + "/" + entry.getValue());
						VpnProfile profile = new VpnProfile();
						String[] serverNameSplit = entry.getKey().split("\\.");
						String serverName = serverNameSplit[0];
						serverName = serverName.substring(0, 1).toUpperCase() + serverName.substring(1);
						//double serverLoad = Math.round(((double) serverBwInMap.get(entry.getKey()) / (double) entry.getValue())*100);
						//profile.setName(serverName + " - Load: " + (int) serverLoad +"%");
						profile.setName(serverName);
						profile.setGateway(entry.getKey());
						profile.setVpnType(VpnType.IKEV2_EAP);

						//Set account-data from settings
						profile.setUsername(new_username);
						profile.setPassword(new_password);

						dataSource.insertProfile(profile);

					}
				} else {
					Toast.makeText(MainActivity.this, getString(R.string.error_refreshing_serverlist), Toast.LENGTH_LONG).show();
				}

			} catch (JSONException e) {
				Toast.makeText(MainActivity.this, getString(R.string.error_refreshing_serverlist), Toast.LENGTH_LONG).show();
				Log.e("JSONException", "Error: " + e.toString());
			}

			this.progressDialog.dismiss();
			updateProfileList();
		}

	}
}
