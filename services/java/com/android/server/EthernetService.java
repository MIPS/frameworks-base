/*
 * Copyright (C) 2009 The Android-x86 Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Author: Yi Sun <beyounn@gmail.com>
 */

package com.android.server;

import java.net.UnknownHostException;
import android.net.ethernet.EthernetNative;
import android.net.ethernet.IEthernetManager;
import android.net.ethernet.EthernetManager;
import android.net.ethernet.EthernetStateTracker;
import android.net.ethernet.EthernetDevInfo;
import android.provider.Settings;
import android.util.Slog;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

public class EthernetService<syncronized> extends IEthernetManager.Stub{
	private Context mContext;
	private EthernetStateTracker mTracker;
	private String[] DevName;
	private static final String TAG = "EthernetService";
	private int isEthEnabled ;
	private int mEthState= EthernetManager.ETH_STATE_UNKNOWN;


	public EthernetService(Context context, EthernetStateTracker Tracker){
		mTracker = Tracker;
		mContext = context;

		isEthEnabled = getPersistedState();
		Slog.i(TAG,"Ethernet dev enabled " + isEthEnabled );
		getDeviceNameList();
		setEthState(isEthEnabled);
		Slog.i(TAG, "Trigger the ethernet monitor");
		mTracker.StartPolling();
	}

	public boolean isEthConfigured() {

		final ContentResolver cr = mContext.getContentResolver();
		int x = Settings.Secure.getInt(cr, Settings.Secure.ETH_CONF,0);

		if (x == 1)
			return true;
		return false;
	}

	public synchronized EthernetDevInfo getSavedEthConfig() {

		if (isEthConfigured() ) {
			final ContentResolver cr = mContext.getContentResolver();
			EthernetDevInfo info = new EthernetDevInfo();
			info.setConnectMode(Settings.Secure.getString(cr, Settings.Secure.ETH_MODE));
			info.setIfName(Settings.Secure.getString(cr, Settings.Secure.ETH_IFNAME));
			info.setIpAddress(Settings.Secure.getString(cr, Settings.Secure.ETH_IP));
			info.setDnsAddr(Settings.Secure.getString(cr, Settings.Secure.ETH_DNS));
			info.setNetMask(Settings.Secure.getString(cr, Settings.Secure.ETH_MASK));
			info.setRouteAddr(Settings.Secure.getString(cr, Settings.Secure.ETH_ROUTE));

			return info;
		}
		return null;
	}


	public synchronized void setEthMode(String mode) {
		final ContentResolver cr = mContext.getContentResolver();
		if (DevName != null) {
			Settings.Secure.putString(cr, Settings.Secure.ETH_IFNAME, DevName[0]);
			Settings.Secure.putInt(cr, Settings.Secure.ETH_CONF,1);
			Settings.Secure.putString(cr, Settings.Secure.ETH_MODE, mode);
		}
	}

	public synchronized void UpdateEthDevInfo(EthernetDevInfo info) {
		final ContentResolver cr = mContext.getContentResolver();
	    Settings.Secure.putInt(cr, Settings.Secure.ETH_CONF,1);
	    Settings.Secure.putString(cr, Settings.Secure.ETH_IFNAME, info.getIfName());
	    Settings.Secure.putString(cr, Settings.Secure.ETH_IP, info.getIpAddress());
	    Settings.Secure.putString(cr, Settings.Secure.ETH_MODE, info.getConnectMode());
	    Settings.Secure.putString(cr, Settings.Secure.ETH_DNS, info.getDnsAddr());
	    Settings.Secure.putString(cr, Settings.Secure.ETH_ROUTE, info.getRouteAddr());
	    Settings.Secure.putString(cr, Settings.Secure.ETH_MASK,info.getNetMask());
	    if (mEthState == EthernetManager.ETH_STATE_ENABLED) {
		try {
				mTracker.resetInterface();
			} catch (UnknownHostException e) {
				Slog.e(TAG, "Wrong ethernet configuration");
			}

	    }

	}

	public int getTotalInterface() {
		return EthernetNative.getInterfaceCnt();
	}


	private int scanEthDevice() {
		int i = 0,j;
		if ((i = EthernetNative.getInterfaceCnt()) != 0) {
			Slog.i(TAG, "total found "+i+ " net devices");
			DevName = new String[i];
		}
		else
			return i;

		for (j = 0; j < i; j++) {
			DevName[j] = EthernetNative.getInterfaceName(j);
			if (DevName[j] == null)
				break;
			Slog.i(TAG,"device " + j + " name " + DevName[j]);
		}

		return i;
	}

	public String[] getDeviceNameList() {
		return (scanEthDevice() > 0 ) ? DevName : null;
	}

	private int getPersistedState() {
		final ContentResolver cr = mContext.getContentResolver();
		try {
			return Settings.Secure.getInt(cr, Settings.Secure.ETH_ON);
		} catch (Settings.SettingNotFoundException e) {
			return EthernetManager.ETH_STATE_UNKNOWN;
		}
	}

	private synchronized void persistEthEnabled(boolean enabled) {
	    final ContentResolver cr = mContext.getContentResolver();
	    Settings.Secure.putInt(cr, Settings.Secure.ETH_ON,
		enabled ? EthernetManager.ETH_STATE_ENABLED : EthernetManager.ETH_STATE_DISABLED);
	}

	public synchronized void setEthState(int state) {
		Slog.i(TAG, "setEthState from " + mEthState + " to "+ state);

		if (mEthState != state){
			mEthState = state;
			if (state == EthernetManager.ETH_STATE_DISABLED) {
				persistEthEnabled(false);
				mTracker.stopInterface(false);
			} else {
				persistEthEnabled(true);
				if (!isEthConfigured()) {
					// If user did not configure any interfaces yet, pick the first one
					// and enable it.
					setEthMode(EthernetDevInfo.ETH_CONN_MODE_DHCP);
				}
				try {
					mTracker.resetInterface();
				} catch (UnknownHostException e) {
					Slog.e(TAG, "Wrong ethernet configuration");
				}
			}
		}
	}

	public int getEthState( ) {
		return mEthState;
	}

}
