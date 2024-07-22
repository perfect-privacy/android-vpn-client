/*
 * Copyright (C) 2023 Relution GmbH
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

package org.perfectprivacy.android.data;

import static org.perfectprivacy.android.data.DatabaseHelper.TABLE_SETTINGS;
import static org.perfectprivacy.android.data.DatabaseHelper.TABLE_VPN_PROFILE;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;

import org.perfectprivacy.android.logic.StrongSwanApplication;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class VpnProfileSource implements VpnProfileDataSource
{
	private final ManagedConfigurationService mManagedConfigurationService;
	private final List<VpnProfileDataSource> dataSources = new ArrayList<>();
	private final VpnProfileSqlDataSource vpnProfileSqlDataSource;

	public VpnProfileSource(Context context)
	{
		mManagedConfigurationService = StrongSwanApplication.getInstance().getManagedConfigurationService();

		vpnProfileSqlDataSource = new VpnProfileSqlDataSource();

		dataSources.add(vpnProfileSqlDataSource);
		dataSources.add(new VpnProfileManagedDataSource(context));
	}

	@Override
	public VpnProfileDataSource open() throws SQLException
	{
		for (final VpnProfileDataSource source : dataSources)
		{
			source.open();
		}
		return this;
	}

	@Override
	public void close()
	{
		for (final VpnProfileDataSource source : dataSources)
		{
			source.close();
		}
	}

	@Override
	public VpnProfile insertProfile(VpnProfile profile)
	{
		return vpnProfileSqlDataSource.insertProfile(profile);
	}

	@Override
	public boolean updateVpnProfile(VpnProfile profile)
	{
		return profile.getDataSource().updateVpnProfile(profile);
	}

	@Override
	public boolean deleteVpnProfile(VpnProfile profile)
	{
		return profile.getDataSource().deleteVpnProfile(profile);
	}

	private List<VpnProfileDataSource> getAccessibleSources()
	{
		final ManagedConfiguration managedConfiguration = mManagedConfigurationService.getManagedConfiguration();
		ArrayList<VpnProfileDataSource> sources = new ArrayList<>(dataSources);
		if (!managedConfiguration.isAllowExistingProfiles())
		{
			sources.remove(vpnProfileSqlDataSource);
		}
		return sources;
	}

	@Override
	public VpnProfile getVpnProfile(UUID uuid)
	{
		for (final VpnProfileDataSource source : getAccessibleSources())
		{
			final VpnProfile profile = source.getVpnProfile(uuid);
			if (profile != null)
			{
				return profile;
			}
		}
		return null;
	}

	@Override
	public List<VpnProfile> getAllVpnProfiles()
	{
		final List<VpnProfile> profiles = new ArrayList<>();

		for (final VpnProfileDataSource source : getAccessibleSources())
		{
			profiles.addAll(source.getAllVpnProfiles());
		}

		return profiles;
	}



	public String getSettingUsername() {
		return this.vpnProfileSqlDataSource.getSettingUsername();
	}

	public void setSettingUsername(String new_username) {
		this.vpnProfileSqlDataSource.setSettingUsername(new_username);
	}

	public String getSettingPassword() {
		return this.vpnProfileSqlDataSource.getSettingPassword();
	}

	public void setSettingPassword(String new_password) {
		this.vpnProfileSqlDataSource.setSettingPassword(new_password);
	}

	public void updateAllProfilesUsernamePassword(String new_username, String new_password) {
		this.vpnProfileSqlDataSource.updateAllProfilesUsernamePassword(new_username,new_password);
	}

}
