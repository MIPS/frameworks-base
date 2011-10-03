/*
 * Copyright (C) 2010 The Android-X86 Open Source Project
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

package android.net.ethernet;

import java.net.InetAddress;
import java.net.UnknownHostException;

import android.R;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothHeadset;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.NetworkStateTracker;
import android.net.NetworkUtils;
import android.net.NetworkInfo.DetailedState;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.SystemProperties;
import android.util.*;

/**
 * Track the state of Ethernet connectivity. All event handling is done here,
 * and all changes in connectivity state are initiated here.
 *
 * @hide
 */

public class EthernetStateTracker extends NetworkStateTracker {
    private static final String TAG                                 = "EthernetStateTracker";
    public static final int EVENT_DHCP_START                        = 0;
    public static final int EVENT_INTERFACE_CONFIGURATION_SUCCEEDED = 1;
    public static final int EVENT_INTERFACE_CONFIGURATION_FAILED    = 2;
    public static final int EVENT_HW_CONNECTED                      = 3;
    public static final int EVENT_HW_DISCONNECTED                   = 4;
    public static final int EVENT_HW_PHYCONNECTED                   = 5;
    private static final int NOTIFY_ID                              = 6;
    private static final boolean localLOGV = true;

    private EthernetManager mEM;
    private boolean mServiceStarted;

    private boolean mStackConnected;
    private boolean mHWConnected;
    private boolean mBootTime;
    private boolean mInterfaceStopped;
    private DhcpHandler mDhcpTarget;
    private String mInterfaceName ;
    private DhcpInfo mDhcpInfo;
    private EthernetMonitor mMonitor;
    private String[] sDnsPropNames;
    private boolean mStartingDhcp;
    private NotificationManager mNotificationManager;
    private Notification mNotification;
    private Handler mTrackerTarget;

    public EthernetStateTracker(Context context, Handler target) {
        super(context, target, ConnectivityManager.TYPE_ETHERNET, 0, "ETH", "");
        if (localLOGV) Slog.v(TAG, "Starts...");

        if (EthernetNative.initEthernetNative() != 0) {
            Slog.e(TAG,"Can not init ethernet device layers");
            return;
        }

        if (localLOGV) Slog.v(TAG,"Successed");
        mServiceStarted = true;
        mBootTime = true;
        HandlerThread dhcpThread = new HandlerThread("DHCP Handler Thread");
        dhcpThread.start();
        mDhcpTarget = new DhcpHandler(dhcpThread.getLooper(), this);
        mMonitor = new EthernetMonitor(this);
        mDhcpInfo = new DhcpInfo();
    }

    /**
     * Stop etherent interface
     * @param suspend {@code false} disable the interface {@code true} only reset the connection without disable the interface
     * @return true
     */
    public boolean stopInterface(boolean suspend) {
        if (mEM != null) {
            EthernetDevInfo info = mEM.getSavedConfig();
            if (info != null && mEM.isConfigured()) {
                synchronized (mDhcpTarget) {
                    mInterfaceStopped = true;
                    if (localLOGV) Slog.i(TAG, "stop dhcp and interface");
                    mDhcpTarget.removeMessages(EVENT_DHCP_START);
                    String ifname = info.getIfName();

                    if (!NetworkUtils.stopDhcp(ifname)) {
                        if (localLOGV) Slog.w(TAG, "Could not stop DHCP");
                    }
                    NetworkUtils.resetConnections(ifname);
                    if (!suspend)
                        NetworkUtils.disableInterface(ifname);
                }
            }
        }

        return true;
    }

    private boolean configureInterface(EthernetDevInfo info) throws UnknownHostException {

        mStackConnected = false;
        mHWConnected = false;
        mInterfaceStopped = false;
        mStartingDhcp = true;
        sDnsPropNames = new String[] {
            "dhcp." + mInterfaceName + ".dns1",
            "dhcp." + mInterfaceName + ".dns2"
        };
        if (info.getConnectMode().equals(EthernetDevInfo.ETHERNET_CONN_MODE_DHCP)) {
            if (localLOGV) Slog.i(TAG, "trigger dhcp for device " + info.getIfName());
            mDhcpTarget.sendEmptyMessage(EVENT_DHCP_START);
        } else {
            int event;

            mDhcpInfo.ipAddress = NetworkUtils.lookupHost(info.getIpAddress());
            mDhcpInfo.gateway = NetworkUtils.lookupHost(info.getRouteAddr());
            mDhcpInfo.netmask = NetworkUtils.lookupHost(info.getNetMask());
            mDhcpInfo.dns1 = NetworkUtils.lookupHost(info.getDnsAddr());
            mDhcpInfo.dns2 = 0;

            if (localLOGV) Slog.i(TAG, "set ip manually " + mDhcpInfo.toString());
            NetworkUtils.removeDefaultRoute(info.getIfName());
            if (NetworkUtils.configureInterface(info.getIfName(), mDhcpInfo)) {
                event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                if (localLOGV) Slog.v(TAG, "Static IP configuration succeeded");
            } else {
                event = EVENT_INTERFACE_CONFIGURATION_FAILED;
                if (localLOGV) Slog.w(TAG, "Static IP configuration failed");
            }
            this.sendEmptyMessage(event);
        }
        return true;
    }

    /**
     * reset ethernet interface
     * @return true
     * @throws UnknownHostException
     */
    public boolean resetInterface()  throws UnknownHostException{
        /*
         * This will guide us to enabled the enabled device
         */
        if (mEM != null) {
            EthernetDevInfo info = mEM.getSavedConfig();
            if (info != null && mEM.isConfigured()) {
                synchronized (this) {
                    mInterfaceName = info.getIfName();
                    if (localLOGV) Slog.i(TAG, "reset device " + mInterfaceName);
                    NetworkUtils.resetConnections(mInterfaceName);
                     // Stop DHCP
                    if (mDhcpTarget != null) {
                        mDhcpTarget.removeMessages(EVENT_DHCP_START);
                    }
                    if (!NetworkUtils.stopDhcp(mInterfaceName)) {
                        if (localLOGV) Slog.w(TAG, "Could not stop DHCP");
                    }
                    configureInterface(info);
                }
            }
        }
        return true;
    }

    @Override
    public String[] getNameServers() {
        return getNameServerList(sDnsPropNames);
    }

    @Override
    public String getTcpBufferSizesPropName() {
        return "net.tcp.buffersize.default";
    }

    public void StartPolling() {
        mMonitor.startMonitoring();
    }
    @Override
    public boolean isAvailable() {
        // Only say available if we have interfaces and user did not disable us.
        return ((mEM.getTotalInterface() != 0) && (mEM.getState() != EthernetManager.ETHERNET_STATE_DISABLED));
    }

    @Override
    public boolean reconnect() {
        try {
            synchronized (this) {
                if (mHWConnected && mStackConnected)
                    return true;
            }
            if (mEM.getState() != EthernetManager.ETHERNET_STATE_DISABLED) {
                // maybe this is the first time we run, so set it to enabled
                mEM.setEnabled(true);
                if (!mEM.isConfigured()) {
                    mEM.setDefaultConf();
                }
                return resetInterface();
            }
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
        return false;

    }

    @Override
    public boolean setRadio(boolean turnOn) {
        return false;
    }

    @Override
    public void startMonitoring() {
        if (localLOGV) Slog.v(TAG,"start to monitor the ethernet devices");
        if (mServiceStarted) {
            mEM = (EthernetManager)mContext.getSystemService(Context.ETHERNET_SERVICE);
            int state = mEM.getState();
            if (state != mEM.ETHERNET_STATE_DISABLED) {
                if (state == mEM.ETHERNET_STATE_UNKNOWN) {
                    // maybe this is the first time we run, so set it to enabled
                    mEM.setEnabled(true);
                } else {
                    try {
                        resetInterface();
                    } catch (UnknownHostException e) {
                        Slog.e(TAG, "Wrong ethernet configuration");
                    }
                }
            }
        }
    }

    @Override
    public int startUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return 0;
    }

    @Override
    public int stopUsingNetworkFeature(String feature, int callingPid, int callingUid) {
        return 0;
    }

    @Override
    public boolean teardown() {
        return (mEM != null) ? stopInterface(false) : false;
    }

    private void postNotification(int event) {
        String ns = Context.NOTIFICATION_SERVICE;
        mNotificationManager = (NotificationManager)mContext.getSystemService(ns);
        final Intent intent = new Intent(EthernetManager.ETHERNET_STATE_CHANGED_ACTION);
        intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT);
        intent.putExtra(EthernetManager.EXTRA_ETHERNET_STATE, event);
        mContext.sendStickyBroadcast(intent);
    }

    private void setState(boolean state, int event) {
        if (mNetworkInfo.isConnected() != state) {
            if (state) {
                setDetailedState(DetailedState.CONNECTED);
                Message msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED, mNetworkInfo);
                msg.sendToTarget();
            } else {
                setDetailedState(DetailedState.DISCONNECTED);
                stopInterface(true);
            }
            mNetworkInfo.setIsAvailable(state);
            postNotification(event);
        }
    }

    public void handleMessage(Message msg) {

        synchronized (this) {
            switch (msg.what) {
            case EVENT_INTERFACE_CONFIGURATION_SUCCEEDED:
                if (localLOGV) Slog.i(TAG, "received configured succeeded, stack=" + mStackConnected + " HW=" + mHWConnected);
                mStackConnected = true;
                if (mHWConnected || mBootTime)
                {
                	mBootTime = false;
                    setState(true, msg.what);
                }
                break;
            case EVENT_INTERFACE_CONFIGURATION_FAILED:
                mStackConnected = false;
                //start to retry ?
                break;
            case EVENT_HW_CONNECTED:
                if (localLOGV) Slog.i(TAG, "received HW connected, stack=" + mStackConnected + " HW=" + mHWConnected);
                mHWConnected = true;
                if (mStackConnected)
                    setState(true, msg.what);
                break;
            case EVENT_HW_DISCONNECTED:
                if (localLOGV) Slog.i(TAG, "received disconnected events, stack=" + mStackConnected + " HW=" + mHWConnected);
                setState(mHWConnected = false, msg.what);
                break;
            case EVENT_HW_PHYCONNECTED:
                if (localLOGV) Slog.i(TAG, "interface up event, kick off connection request");
                if (!mStartingDhcp) {
                    int state = mEM.getState();
                    if (state != mEM.ETHERNET_STATE_DISABLED) {
                        EthernetDevInfo info = mEM.getSavedConfig();
                        if (info != null && mEM.isConfigured()) {
                            try {
                                configureInterface(info);
                            } catch (UnknownHostException e) {
                                 // TODO Auto-generated catch block
                                 e.printStackTrace();
                            }
                        }
                    }
                }
                break;
            }
        }
    }

    private class DhcpHandler extends Handler {
         public DhcpHandler(Looper looper, Handler target) {
             super(looper);
             mTrackerTarget = target;
         }

         public void handleMessage(Message msg) {
             int event;

             switch (msg.what) {
                 case EVENT_DHCP_START:
                     synchronized (mDhcpTarget) {
                         if (!mInterfaceStopped) {
                             if (localLOGV) Slog.d(TAG, "DhcpHandler: DHCP request started");
                             if (NetworkUtils.runDhcp(mInterfaceName, mDhcpInfo)) {
                                 event = EVENT_INTERFACE_CONFIGURATION_SUCCEEDED;
                                 if (localLOGV) Slog.d(TAG, "DhcpHandler: DHCP request succeeded: " + mDhcpInfo.toString());
                             } else {
                                 event = EVENT_INTERFACE_CONFIGURATION_FAILED;
                                 Slog.e(TAG, "DhcpHandler: DHCP request failed: " + NetworkUtils.getDhcpError());
                             }
                             mTrackerTarget.sendEmptyMessage(event);
                         } else {
                             mInterfaceStopped = false;
                         }
                         mStartingDhcp = false;
                     }
                     break;
             }

         }
    }

    public void notifyPhyConnected(String ifname) {
        if (localLOGV) Slog.v(TAG, "report interface is up for " + ifname);
        synchronized(this) {
            this.sendEmptyMessage(EVENT_HW_PHYCONNECTED);
        }

    }

    public void notifyStateChange(String ifname,DetailedState state) {
        if (localLOGV) Slog.i(TAG, "report new state " + state.toString() + " on dev " + ifname);
        if (ifname.equals(mInterfaceName)) {
            if (localLOGV) Slog.v(TAG, "update network state tracker");
            synchronized(this) {
                this.sendEmptyMessage(state.equals(DetailedState.CONNECTED)
                    ? EVENT_HW_CONNECTED : EVENT_HW_DISCONNECTED);
            }
        }
    }
}
