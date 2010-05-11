/*
 * Copyright (C) 2008 The Android Open Source Project
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
 */

 
package com.android.internal.app;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.ProgressDialog;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.IBluetooth;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Handler;
import android.os.RemoteException;
import android.os.Power;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.IMountService;

import com.android.internal.telephony.ITelephony;
import android.util.Log;
import android.view.WindowManager;

public final class ShutdownThread extends Thread {
    // constants
    private static final String TAG = "ShutdownThread";
    private static final int MAX_NUM_PHONE_STATE_READS = 16;
    private static final int PHONE_STATE_POLL_SLEEP_MSEC = 500;
    // maximum time we wait for the shutdown broadcast before going on.
    private static final int MAX_BROADCAST_TIME = 10*1000;
    
    // state tracking
    private static Object sIsStartedGuard = new Object();
    private static boolean sIsStarted = false;
    
    // static instance of this thread
    private static final ShutdownThread sInstance = new ShutdownThread();
    
    private final Object mBroadcastDoneSync = new Object();
    private boolean mBroadcastDone;
    private Context mContext;
    private Handler mHandler;
    
    private ShutdownThread() {
    }
 
    /** 
     * Request a clean shutdown, waiting for subsystems to clean up their
     * state etc.  Must be called from a Looper thread in which its UI
     * is shown.
     * 
     * @param context Context used to display the shutdown progress dialog.
     */
    public static void shutdown(final Context context, boolean confirm) {
        // ensure that only one thread is trying to power down.
        // any additional calls are just returned
        synchronized (sIsStartedGuard){
            if (sIsStarted) {
                Log.d(TAG, "Request to shutdown already running, returning.");
                return;
            }
        }

        Log.d(TAG, "Notifying thread to start radio shutdown");

        if (confirm) {
            final AlertDialog dialog = new AlertDialog.Builder(context)
                    .setIcon(android.R.drawable.ic_dialog_alert)
                    .setTitle(com.android.internal.R.string.power_off)
                    .setMessage(com.android.internal.R.string.shutdown_confirm)
                    .setPositiveButton(com.android.internal.R.string.yes, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            beginShutdownSequence(context);
                        }
                    })
                    .setNegativeButton(com.android.internal.R.string.no, null)
                    .create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
            if (!context.getResources().getBoolean(
                    com.android.internal.R.bool.config_sf_slowBlur)) {
                dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            }
            dialog.show();
        } else {
            beginShutdownSequence(context);
        }
    }

    private static void beginShutdownSequence(Context context) {
        synchronized (sIsStartedGuard) {
            sIsStarted = true;
        }

        // throw up an indeterminate system dialog to indicate radio is
        // shutting down.
        ProgressDialog pd = new ProgressDialog(context);
        pd.setTitle(context.getText(com.android.internal.R.string.power_off));
        pd.setMessage(context.getText(com.android.internal.R.string.shutdown_progress));
        pd.setIndeterminate(true);
        pd.setCancelable(false);
        pd.getWindow().setType(WindowManager.LayoutParams.TYPE_KEYGUARD_DIALOG);
        if (!context.getResources().getBoolean(
                com.android.internal.R.bool.config_sf_slowBlur)) {
            pd.getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        }

        pd.show();

        // start the thread that initiates shutdown
        sInstance.mContext = context;
        sInstance.mHandler = new Handler() {
        };
        sInstance.start();
    }

    void broadcastDone() {
        synchronized (mBroadcastDoneSync) {
            mBroadcastDone = true;
            mBroadcastDoneSync.notifyAll();
        }
    }
    
    /**
     * Makes sure we handle the shutdown gracefully.
     * Shuts off power regardless of radio and bluetooth state if the alloted time has passed.
     */
    public void run() {
        boolean bluetoothOff;
        boolean radioOff;

        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                // We don't allow apps to cancel this, so ignore the result.
                broadcastDone();
            }
        };
        
        Log.i(TAG, "Sending shutdown broadcast...");
        
        // First send the high-level shut down broadcast.
        mBroadcastDone = false;
        mContext.sendOrderedBroadcast(new Intent(Intent.ACTION_SHUTDOWN), null,
                br, mHandler, 0, null, null);
        
        final long endTime = System.currentTimeMillis() + MAX_BROADCAST_TIME;
        synchronized (mBroadcastDoneSync) {
            while (!mBroadcastDone) {
                long delay = endTime - System.currentTimeMillis();
                if (delay <= 0) {
                    Log.w(TAG, "Shutdown broadcast timed out");
                    break;
                }
                try {
                    mBroadcastDoneSync.wait(delay);
                } catch (InterruptedException e) {
                }
            }
        }
        
        Log.i(TAG, "Shutting down activity manager...");
        
        final IActivityManager am =
            ActivityManagerNative.asInterface(ServiceManager.checkService("activity"));
        if (am != null) {
            try {
                am.shutdown(MAX_BROADCAST_TIME);
            } catch (RemoteException e) {
            }
        }
        
        final ITelephony phone =
                ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
        final IBluetooth bluetooth =
                IBluetooth.Stub.asInterface(ServiceManager.checkService(
                        BluetoothAdapter.BLUETOOTH_SERVICE));

        final IMountService mount =
                IMountService.Stub.asInterface(
                        ServiceManager.checkService("mount"));
        
        try {
            bluetoothOff = bluetooth == null ||
                           bluetooth.getBluetoothState() == BluetoothAdapter.STATE_OFF;
            if (!bluetoothOff) {
                Log.w(TAG, "Disabling Bluetooth...");
                bluetooth.disable(false);  // disable but don't persist new state
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
            bluetoothOff = true;
        }

        try {
            radioOff = phone == null || !phone.isRadioOn();
            if (!radioOff) {
                Log.w(TAG, "Turning off radio...");
                phone.setRadio(false);
            }
        } catch (RemoteException ex) {
            Log.e(TAG, "RemoteException during radio shutdown", ex);
            radioOff = true;
        }

        Log.i(TAG, "Waiting for Bluetooth and Radio...");
        
        // Wait a max of 32 seconds for clean shutdown
        for (int i = 0; i < MAX_NUM_PHONE_STATE_READS; i++) {
            if (!bluetoothOff) {
                try {
                    bluetoothOff =
                            bluetooth.getBluetoothState() == BluetoothAdapter.STATE_OFF;
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during bluetooth shutdown", ex);
                    bluetoothOff = true;
                }
            }
            if (!radioOff) {
                try {
                    radioOff = !phone.isRadioOn();
                } catch (RemoteException ex) {
                    Log.e(TAG, "RemoteException during radio shutdown", ex);
                    radioOff = true;
                }
            }
            if (radioOff && bluetoothOff) {
                Log.i(TAG, "Radio and Bluetooth shutdown complete.");
                break;
            }
            SystemClock.sleep(PHONE_STATE_POLL_SLEEP_MSEC);
        }

        // Shutdown MountService to ensure media is in a safe state
        try {
            if (mount != null) {
                mount.shutdown();
            } else {
                Log.w(TAG, "MountService unavailable for shutdown");
            }
        } catch (Exception e) {
            Log.e(TAG, "Exception during MountService shutdown", e);
        }

        //shutdown power
        Log.i(TAG, "Performing low-level shutdown...");
        Power.shutdown();
    }
}
