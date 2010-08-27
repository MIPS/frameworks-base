package android.net.ethernet;

import java.util.List;

import android.annotation.SdkConstant;
import android.annotation.SdkConstant.SdkConstantType;
import android.net.wifi.IWifiManager;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Slog;

public class EthernetManager {
    public static final String TAG = "EthernetManager";
    public static final int ETH_DEVICE_SCAN_RESULT_READY = 0;
    public static final String ETH_STATE_CHANGED_ACTION =
            "android.net.ethernet.ETH_STATE_CHANGED";
    public static final String NETWORK_STATE_CHANGED_ACTION =
            "android.net.ethernet.STATE_CHANGE";

//  public static final String ACTION_ETH_NETWORK = "android.net.ethernet.ETH_NET_CHG";


    public static final String EXTRA_NETWORK_INFO = "networkInfo";
    public static final String EXTRA_ETH_STATE = "eth_state";
    public static final String EXTRA_PREVIOUS_ETH_STATE = "previous_eth_state";

    public static final int ETH_STATE_UNKNOWN = 0;
    public static final int ETH_STATE_DISABLED = 1;
    public static final int ETH_STATE_ENABLED = 2;

    IEthernetManager mService;
    Handler mHandler;

    public EthernetManager(IEthernetManager service, Handler handler) {
        Slog.i(TAG, "Init Ethernet Manager");
        mService = service;
        mHandler = handler;
    }

    public boolean isEthConfigured() {
        try {
            return mService.isEthConfigured();
        } catch (RemoteException e) {
            Slog.i(TAG, "Can not check eth config state");
        }
        return false;
    }

    public EthernetDevInfo getSavedEthConfig() {
        try {
            return mService.getSavedEthConfig();
        } catch (RemoteException e) {
            Slog.i(TAG, "Can not get eth config");
        }
        return null;
    }

    public void UpdateEthDevInfo(EthernetDevInfo info) {
        try {
            mService.UpdateEthDevInfo(info);
        } catch (RemoteException e) {
            Slog.i(TAG, "Can not update ethernet device info");
        }
    }

    public String[] getDeviceNameList() {
        try {
            return mService.getDeviceNameList();
        } catch (RemoteException e) {
            return null;
        }
    }

    public void setEthEnabled(boolean enable) {
        try {
            mService.setEthState(enable ? ETH_STATE_ENABLED:ETH_STATE_DISABLED);
        } catch (RemoteException e) {
            Slog.i(TAG,"Can not set new state");
        }
    }

    public int getEthState( ) {
        try {
            return mService.getEthState();
        } catch (RemoteException e) {
            return 0;
        }
    }

    public boolean ethConfigured() {
        try {
            return mService.isEthConfigured();
        } catch (RemoteException e) {
            return false;
        }
    }

    public int getTotalInterface() {
        try {
            return mService.getTotalInterface();
        } catch (RemoteException e) {
            return 0;
        }
    }

    public void ethSetDefaultConf() {
        try {
            mService.setEthMode(EthernetDevInfo.ETH_CONN_MODE_DHCP);
        } catch (RemoteException e) {
        }
    }
}
