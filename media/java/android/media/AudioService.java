/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.media;

import android.app.ActivityManagerNative;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;

import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.os.Binder;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.provider.Settings.System;
import android.util.Log;
import android.view.VolumePanel;
import android.os.SystemProperties;

import com.android.internal.telephony.ITelephony;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;


/**
 * The implementation of the volume manager service.
 * <p>
 * This implementation focuses on delivering a responsive UI. Most methods are
 * asynchronous to external calls. For example, the task of setting a volume
 * will update our internal state, but in a separate thread will set the system
 * volume and later persist to the database. Similarly, setting the ringer mode
 * will update the state and broadcast a change and in a separate thread later
 * persist the ringer mode.
 *
 * @hide
 */
public class AudioService extends IAudioService.Stub {

    private static final String TAG = "AudioService";

    /** How long to delay before persisting a change in volume/ringer mode. */
    private static final int PERSIST_DELAY = 3000;

    private Context mContext;
    private ContentResolver mContentResolver;

    /** The UI */
    private VolumePanel mVolumePanel;

    // sendMsg() flags
    /** Used when a message should be shared across all stream types. */
    private static final int SHARED_MSG = -1;
    /** If the msg is already queued, replace it with this one. */
    private static final int SENDMSG_REPLACE = 0;
    /** If the msg is already queued, ignore this one and leave the old. */
    private static final int SENDMSG_NOOP = 1;
    /** If the msg is already queued, queue this one and leave the old. */
    private static final int SENDMSG_QUEUE = 2;

    // AudioHandler message.whats
    private static final int MSG_SET_SYSTEM_VOLUME = 0;
    private static final int MSG_PERSIST_VOLUME = 1;
    private static final int MSG_PERSIST_RINGER_MODE = 3;
    private static final int MSG_PERSIST_VIBRATE_SETTING = 4;
    private static final int MSG_MEDIA_SERVER_DIED = 5;
    private static final int MSG_MEDIA_SERVER_STARTED = 6;
    private static final int MSG_PLAY_SOUND_EFFECT = 7;

    /** @see AudioSystemThread */
    private AudioSystemThread mAudioSystemThread;
    /** @see AudioHandler */
    private AudioHandler mAudioHandler;
    /** @see VolumeStreamState */
    private VolumeStreamState[] mStreamStates;
    private SettingsObserver mSettingsObserver;

    private int mMode;
    private Object mSettingsLock = new Object();
    private boolean mMediaServerOk;

    /** cached value of the BT dock address to recognize undocking events */
    private static String sBtDockAddress = "";

    private SoundPool mSoundPool;
    private Object mSoundEffectsLock = new Object();
    private static final int NUM_SOUNDPOOL_CHANNELS = 4;
    private static final int SOUND_EFFECT_VOLUME = 1000;

    /* Sound effect file names  */
    private static final String SOUND_EFFECTS_PATH = "/media/audio/ui/";
    private static final String[] SOUND_EFFECT_FILES = new String[] {
        "Effect_Tick.ogg",
        "KeypressStandard.ogg",
        "KeypressSpacebar.ogg",
        "KeypressDelete.ogg",
        "KeypressReturn.ogg"
    };

    /* Sound effect file name mapping sound effect id (AudioManager.FX_xxx) to
     * file index in SOUND_EFFECT_FILES[] (first column) and indicating if effect
     * uses soundpool (second column) */
    private int[][] SOUND_EFFECT_FILES_MAP = new int[][] {
        {0, -1},  // FX_KEY_CLICK
        {0, -1},  // FX_FOCUS_NAVIGATION_UP
        {0, -1},  // FX_FOCUS_NAVIGATION_DOWN
        {0, -1},  // FX_FOCUS_NAVIGATION_LEFT
        {0, -1},  // FX_FOCUS_NAVIGATION_RIGHT
        {1, -1},  // FX_KEYPRESS_STANDARD
        {2, -1},  // FX_KEYPRESS_SPACEBAR
        {3, -1},  // FX_FOCUS_DELETE
        {4, -1}   // FX_FOCUS_RETURN
    };

   /** @hide Maximum volume index values for audio streams */
    private int[] MAX_STREAM_VOLUME = new int[] {
        5,  // STREAM_VOICE_CALL
        7,  // STREAM_SYSTEM
        7,  // STREAM_RING
        15, // STREAM_MUSIC
        7,  // STREAM_ALARM
        7,  // STREAM_NOTIFICATION
        15, // STREAM_BLUETOOTH_SCO
        7,  // STREAM_SYSTEM_ENFORCED
        15, // STREAM_DTMF
        15  // STREAM_TTS
    };
    /* STREAM_VOLUME_ALIAS[] indicates for each stream if it uses the volume settings
     * of another stream: This avoids multiplying the volume settings for hidden
     * stream types that follow other stream behavior for volume settings
     * NOTE: do not create loops in aliases! */
    private int[] STREAM_VOLUME_ALIAS = new int[] {
        AudioSystem.STREAM_VOICE_CALL,  // STREAM_VOICE_CALL
        AudioSystem.STREAM_SYSTEM,  // STREAM_SYSTEM
        AudioSystem.STREAM_RING,  // STREAM_RING
        AudioSystem.STREAM_MUSIC, // STREAM_MUSIC
        AudioSystem.STREAM_ALARM,  // STREAM_ALARM
        AudioSystem.STREAM_NOTIFICATION,  // STREAM_NOTIFICATION
        AudioSystem.STREAM_VOICE_CALL, // STREAM_BLUETOOTH_SCO
        AudioSystem.STREAM_SYSTEM,  // STREAM_SYSTEM_ENFORCED
        AudioSystem.STREAM_VOICE_CALL, // STREAM_DTMF
        AudioSystem.STREAM_MUSIC  // STREAM_TTS
    };

    private AudioSystem.ErrorCallback mAudioSystemCallback = new AudioSystem.ErrorCallback() {
        public void onError(int error) {
            switch (error) {
            case AudioSystem.AUDIO_STATUS_SERVER_DIED:
                if (mMediaServerOk) {
                    sendMsg(mAudioHandler, MSG_MEDIA_SERVER_DIED, SHARED_MSG, SENDMSG_NOOP, 0, 0,
                            null, 1500);
                    mMediaServerOk = false;
                }
                break;
            case AudioSystem.AUDIO_STATUS_OK:
                if (!mMediaServerOk) {
                    sendMsg(mAudioHandler, MSG_MEDIA_SERVER_STARTED, SHARED_MSG, SENDMSG_NOOP, 0, 0,
                            null, 0);
                    mMediaServerOk = true;
                }
                break;
            default:
                break;
            }
       }
    };

    /**
     * Current ringer mode from one of {@link AudioManager#RINGER_MODE_NORMAL},
     * {@link AudioManager#RINGER_MODE_SILENT}, or
     * {@link AudioManager#RINGER_MODE_VIBRATE}.
     */
    private int mRingerMode;

    /** @see System#MODE_RINGER_STREAMS_AFFECTED */
    private int mRingerModeAffectedStreams;

    /** @see System#MUTE_STREAMS_AFFECTED */
    private int mMuteAffectedStreams;

    /**
     * Has multiple bits per vibrate type to indicate the type's vibrate
     * setting. See {@link #setVibrateSetting(int, int)}.
     * <p>
     * NOTE: This is not the final decision of whether vibrate is on/off for the
     * type since it depends on the ringer mode. See {@link #shouldVibrate(int)}.
     */
    private int mVibrateSetting;

    /** @see System#NOTIFICATIONS_USE_RING_VOLUME */
    private int mNotificationsUseRingVolume;

    // Broadcast receiver for device connections intent broadcasts
    private final BroadcastReceiver mReceiver = new AudioServiceBroadcastReceiver();

    // Devices currently connected
    private HashMap <Integer, String> mConnectedDevices = new HashMap <Integer, String>();

    // Forced device usage for communications
    private int mForcedUseForComm;


    ///////////////////////////////////////////////////////////////////////////
    // Construction
    ///////////////////////////////////////////////////////////////////////////

    /** @hide */
    public AudioService(Context context) {
        mContext = context;
        mContentResolver = context.getContentResolver();

       // Intialized volume
        MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL] = SystemProperties.getInt(
            "ro.config.vc_call_vol_steps",
           MAX_STREAM_VOLUME[AudioSystem.STREAM_VOICE_CALL]);

        mVolumePanel = new VolumePanel(context, this);
        mSettingsObserver = new SettingsObserver();
        mMode = AudioSystem.MODE_NORMAL;
        mForcedUseForComm = AudioSystem.FORCE_NONE;
        createAudioSystemThread();
        readPersistedSettings();
        createStreamStates();
        mMediaServerOk = true;
        AudioSystem.setErrorCallback(mAudioSystemCallback);
        loadSoundEffects();

        // Register for device connection intent broadcasts.
        IntentFilter intentFilter =
                new IntentFilter(Intent.ACTION_HEADSET_PLUG);
        intentFilter.addAction(BluetoothA2dp.ACTION_SINK_STATE_CHANGED);
        intentFilter.addAction(BluetoothHeadset.ACTION_STATE_CHANGED);
        context.registerReceiver(mReceiver, intentFilter);

    }

    private void createAudioSystemThread() {
        mAudioSystemThread = new AudioSystemThread();
        mAudioSystemThread.start();
        waitForAudioHandlerCreation();
    }

    /** Waits for the volume handler to be created by the other thread. */
    private void waitForAudioHandlerCreation() {
        synchronized(this) {
            while (mAudioHandler == null) {
                try {
                    // Wait for mAudioHandler to be set by the other thread
                    wait();
                } catch (InterruptedException e) {
                    Log.e(TAG, "Interrupted while waiting on volume handler.");
                }
            }
        }
    }

    private void createStreamStates() {
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        VolumeStreamState[] streams = mStreamStates = new VolumeStreamState[numStreamTypes];

        for (int i = 0; i < numStreamTypes; i++) {
            streams[i] = new VolumeStreamState(System.VOLUME_SETTINGS[STREAM_VOLUME_ALIAS[i]], i);
        }

        // Correct stream index values for streams with aliases
        for (int i = 0; i < numStreamTypes; i++) {
            if (STREAM_VOLUME_ALIAS[i] != i) {
                int index = rescaleIndex(streams[i].mIndex, STREAM_VOLUME_ALIAS[i], i);
                streams[i].mIndex = streams[i].getValidIndex(index);
                setStreamVolumeIndex(i, index);
                index = rescaleIndex(streams[i].mLastAudibleIndex, STREAM_VOLUME_ALIAS[i], i);
                streams[i].mLastAudibleIndex = streams[i].getValidIndex(index);
            }
        }
    }

    private void readPersistedSettings() {
        final ContentResolver cr = mContentResolver;

        mRingerMode = System.getInt(cr, System.MODE_RINGER, AudioManager.RINGER_MODE_NORMAL);

        mVibrateSetting = System.getInt(cr, System.VIBRATE_ON, 0);

        mRingerModeAffectedStreams = Settings.System.getInt(cr,
                Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                ((1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_NOTIFICATION)|
                 (1 << AudioSystem.STREAM_SYSTEM)|(1 << AudioSystem.STREAM_SYSTEM_ENFORCED)));

        mMuteAffectedStreams = System.getInt(cr,
                System.MUTE_STREAMS_AFFECTED,
                ((1 << AudioSystem.STREAM_MUSIC)|(1 << AudioSystem.STREAM_RING)|(1 << AudioSystem.STREAM_SYSTEM)));

        mNotificationsUseRingVolume = System.getInt(cr,
                Settings.System.NOTIFICATIONS_USE_RING_VOLUME, 1);

        if (mNotificationsUseRingVolume == 1) {
            STREAM_VOLUME_ALIAS[AudioSystem.STREAM_NOTIFICATION] = AudioSystem.STREAM_RING;
        }
        // Each stream will read its own persisted settings

        // Broadcast the sticky intent
        broadcastRingerMode();

        // Broadcast vibrate settings
        broadcastVibrateSetting(AudioManager.VIBRATE_TYPE_RINGER);
        broadcastVibrateSetting(AudioManager.VIBRATE_TYPE_NOTIFICATION);
    }

    private void setStreamVolumeIndex(int stream, int index) {
        AudioSystem.setStreamVolumeIndex(stream, (index + 5)/10);
    }

    private int rescaleIndex(int index, int srcStream, int dstStream) {
        return (index * mStreamStates[dstStream].getMaxIndex() + mStreamStates[srcStream].getMaxIndex() / 2) / mStreamStates[srcStream].getMaxIndex();
    }

    ///////////////////////////////////////////////////////////////////////////
    // IPC methods
    ///////////////////////////////////////////////////////////////////////////

    /** @see AudioManager#adjustVolume(int, int) */
    public void adjustVolume(int direction, int flags) {
        adjustSuggestedStreamVolume(direction, AudioManager.USE_DEFAULT_STREAM_TYPE, flags);
    }

    /** @see AudioManager#adjustVolume(int, int, int) */
    public void adjustSuggestedStreamVolume(int direction, int suggestedStreamType, int flags) {

        int streamType = getActiveStreamType(suggestedStreamType);

        // Don't play sound on other streams
        if (streamType != AudioSystem.STREAM_RING && (flags & AudioManager.FLAG_PLAY_SOUND) != 0) {
            flags &= ~AudioManager.FLAG_PLAY_SOUND;
        }

        adjustStreamVolume(streamType, direction, flags);
    }

    /** @see AudioManager#adjustStreamVolume(int, int, int) */
    public void adjustStreamVolume(int streamType, int direction, int flags) {
        ensureValidDirection(direction);
        ensureValidStreamType(streamType);


        VolumeStreamState streamState = mStreamStates[STREAM_VOLUME_ALIAS[streamType]];
        final int oldIndex = streamState.mIndex;
        boolean adjustVolume = true;

        // If either the client forces allowing ringer modes for this adjustment,
        // or the stream type is one that is affected by ringer modes
        if ((flags & AudioManager.FLAG_ALLOW_RINGER_MODES) != 0
                || streamType == AudioSystem.STREAM_RING) {
            // Check if the ringer mode changes with this volume adjustment. If
            // it does, it will handle adjusting the volume, so we won't below
            adjustVolume = checkForRingerModeChange(oldIndex, direction);
        }

        if (adjustVolume && streamState.adjustIndex(direction)) {
            // Post message to set system volume (it in turn will post a message
            // to persist). Do not change volume if stream is muted.
            if (streamState.muteCount() == 0) {
                sendMsg(mAudioHandler, MSG_SET_SYSTEM_VOLUME, STREAM_VOLUME_ALIAS[streamType], SENDMSG_NOOP, 0, 0,
                        streamState, 0);
            }
        }

        // UI
        mVolumePanel.postVolumeChanged(streamType, flags);
        // Broadcast Intent
        sendVolumeUpdate(streamType);
    }

    /** @see AudioManager#setStreamVolume(int, int, int) */
    public void setStreamVolume(int streamType, int index, int flags) {
        ensureValidStreamType(streamType);
        index = rescaleIndex(index * 10, streamType, STREAM_VOLUME_ALIAS[streamType]);
        setStreamVolumeInt(STREAM_VOLUME_ALIAS[streamType], index, false, true);

        // UI, etc.
        mVolumePanel.postVolumeChanged(streamType, flags);
        // Broadcast Intent
        sendVolumeUpdate(streamType);
    }

    private void sendVolumeUpdate(int streamType) {
        Intent intent = new Intent(AudioManager.VOLUME_CHANGED_ACTION);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_TYPE, streamType);
        intent.putExtra(AudioManager.EXTRA_VOLUME_STREAM_VALUE, getStreamVolume(streamType));

        // Currently, sending the intent only when the stream is BLUETOOTH_SCO
        if (streamType == AudioSystem.STREAM_BLUETOOTH_SCO) {
            mContext.sendBroadcast(intent);
        }
    }

    /**
     * Sets the stream state's index, and posts a message to set system volume.
     * This will not call out to the UI. Assumes a valid stream type.
     *
     * @param streamType Type of the stream
     * @param index Desired volume index of the stream
     * @param force If true, set the volume even if the desired volume is same
     * as the current volume.
     * @param lastAudible If true, stores new index as last audible one
     */
    private void setStreamVolumeInt(int streamType, int index, boolean force, boolean lastAudible) {
        VolumeStreamState streamState = mStreamStates[streamType];
        if (streamState.setIndex(index, lastAudible) || force) {
            // Post message to set system volume (it in turn will post a message
            // to persist). Do not change volume if stream is muted.
            if (streamState.muteCount() == 0) {
                sendMsg(mAudioHandler, MSG_SET_SYSTEM_VOLUME, streamType, SENDMSG_NOOP, 0, 0,
                        streamState, 0);
            }
        }
    }

    /** @see AudioManager#setStreamSolo(int, boolean) */
    public void setStreamSolo(int streamType, boolean state, IBinder cb) {
        for (int stream = 0; stream < mStreamStates.length; stream++) {
            if (!isStreamAffectedByMute(stream) || stream == streamType) continue;
            // Bring back last audible volume
            mStreamStates[stream].mute(cb, state);
         }
    }

    /** @see AudioManager#setStreamMute(int, boolean) */
    public void setStreamMute(int streamType, boolean state, IBinder cb) {
        if (isStreamAffectedByMute(streamType)) {
            mStreamStates[streamType].mute(cb, state);
        }
    }

    /** @see AudioManager#getStreamVolume(int) */
    public int getStreamVolume(int streamType) {
        ensureValidStreamType(streamType);
        return (mStreamStates[streamType].mIndex + 5) / 10;
    }

    /** @see AudioManager#getStreamMaxVolume(int) */
    public int getStreamMaxVolume(int streamType) {
        ensureValidStreamType(streamType);
        return (mStreamStates[streamType].getMaxIndex() + 5) / 10;
    }

    /** @see AudioManager#getRingerMode() */
    public int getRingerMode() {
        return mRingerMode;
    }

    /** @see AudioManager#setRingerMode(int) */
    public void setRingerMode(int ringerMode) {
        synchronized (mSettingsLock) {
            if (ringerMode != mRingerMode) {
                setRingerModeInt(ringerMode, true);
                // Send sticky broadcast
                broadcastRingerMode();
            }
        }
    }

    private void setRingerModeInt(int ringerMode, boolean persist) {
        mRingerMode = ringerMode;

        // Adjust volumes via posting message
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        if (mRingerMode == AudioManager.RINGER_MODE_NORMAL) {
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (!isStreamAffectedByRingerMode(streamType)) continue;
                // Bring back last audible volume
                setStreamVolumeInt(streamType, mStreamStates[streamType].mLastAudibleIndex,
                                   false, false);
            }
        } else {
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (isStreamAffectedByRingerMode(streamType)) {
                    // Either silent or vibrate, either way volume is 0
                    setStreamVolumeInt(streamType, 0, false, false);
                } else {
                    // restore stream volume in the case the stream changed from affected
                    // to non affected by ringer mode. Does not arm to do it for streams that
                    // are not affected as well.
                    setStreamVolumeInt(streamType, mStreamStates[streamType].mLastAudibleIndex,
                            false, false);
                }
            }
        }

        // Post a persist ringer mode msg
        if (persist) {
            sendMsg(mAudioHandler, MSG_PERSIST_RINGER_MODE, SHARED_MSG,
                    SENDMSG_REPLACE, 0, 0, null, PERSIST_DELAY);
        }
    }

    /** @see AudioManager#shouldVibrate(int) */
    public boolean shouldVibrate(int vibrateType) {

        switch (getVibrateSetting(vibrateType)) {

            case AudioManager.VIBRATE_SETTING_ON:
                return mRingerMode != AudioManager.RINGER_MODE_SILENT;

            case AudioManager.VIBRATE_SETTING_ONLY_SILENT:
                return mRingerMode == AudioManager.RINGER_MODE_VIBRATE;

            case AudioManager.VIBRATE_SETTING_OFF:
                // Phone ringer should always vibrate in vibrate mode
                if (vibrateType == AudioManager.VIBRATE_TYPE_RINGER) {
                    return mRingerMode == AudioManager.RINGER_MODE_VIBRATE;
                }

            default:
                return false;
        }
    }

    /** @see AudioManager#getVibrateSetting(int) */
    public int getVibrateSetting(int vibrateType) {
        return (mVibrateSetting >> (vibrateType * 2)) & 3;
    }

    /** @see AudioManager#setVibrateSetting(int, int) */
    public void setVibrateSetting(int vibrateType, int vibrateSetting) {

        mVibrateSetting = getValueForVibrateSetting(mVibrateSetting, vibrateType, vibrateSetting);

        // Broadcast change
        broadcastVibrateSetting(vibrateType);

        // Post message to set ringer mode (it in turn will post a message
        // to persist)
        sendMsg(mAudioHandler, MSG_PERSIST_VIBRATE_SETTING, SHARED_MSG, SENDMSG_NOOP, 0, 0,
                null, 0);
    }

    /**
     * @see #setVibrateSetting(int, int)
     */
    public static int getValueForVibrateSetting(int existingValue, int vibrateType,
            int vibrateSetting) {

        // First clear the existing setting. Each vibrate type has two bits in
        // the value. Note '3' is '11' in binary.
        existingValue &= ~(3 << (vibrateType * 2));

        // Set into the old value
        existingValue |= (vibrateSetting & 3) << (vibrateType * 2);

        return existingValue;
    }

    /** @see AudioManager#setMode(int) */
    public void setMode(int mode) {
        if (!checkAudioSettingsPermission("setMode()")) {
            return;
        }

        if (mode < AudioSystem.MODE_CURRENT || mode > AudioSystem.MODE_IN_CALL) {
            return;
        }

        synchronized (mSettingsLock) {
            if (mode == AudioSystem.MODE_CURRENT) {
                mode = mMode;
            }
            if (mode != mMode) {
                if (AudioSystem.setPhoneState(mode) == AudioSystem.AUDIO_STATUS_OK) {
                    mMode = mode;
                }
            }
            int streamType = getActiveStreamType(AudioManager.USE_DEFAULT_STREAM_TYPE);
            int index = mStreamStates[STREAM_VOLUME_ALIAS[streamType]].mIndex;
            setStreamVolumeInt(STREAM_VOLUME_ALIAS[streamType], index, true, true);
        }
    }

    /** @see AudioManager#getMode() */
    public int getMode() {
        return mMode;
    }

    /** @see AudioManager#playSoundEffect(int) */
    public void playSoundEffect(int effectType) {
        sendMsg(mAudioHandler, MSG_PLAY_SOUND_EFFECT, SHARED_MSG, SENDMSG_NOOP,
                effectType, -1, null, 0);
    }

    /** @see AudioManager#playSoundEffect(int, float) */
    public void playSoundEffectVolume(int effectType, float volume) {
        loadSoundEffects();
        sendMsg(mAudioHandler, MSG_PLAY_SOUND_EFFECT, SHARED_MSG, SENDMSG_NOOP,
                effectType, (int) (volume * 1000), null, 0);
    }

    /**
     * Loads samples into the soundpool.
     * This method must be called at when sound effects are enabled
     */
    public boolean loadSoundEffects() {
        synchronized (mSoundEffectsLock) {
            if (mSoundPool != null) {
                return true;
            }
            mSoundPool = new SoundPool(NUM_SOUNDPOOL_CHANNELS, AudioSystem.STREAM_SYSTEM, 0);
            if (mSoundPool == null) {
                return false;
            }
            /*
             * poolId table: The value -1 in this table indicates that corresponding
             * file (same index in SOUND_EFFECT_FILES[] has not been loaded.
             * Once loaded, the value in poolId is the sample ID and the same
             * sample can be reused for another effect using the same file.
             */
            int[] poolId = new int[SOUND_EFFECT_FILES.length];
            for (int fileIdx = 0; fileIdx < SOUND_EFFECT_FILES.length; fileIdx++) {
                poolId[fileIdx] = -1;
            }
            /*
             * Effects whose value in SOUND_EFFECT_FILES_MAP[effect][1] is -1 must be loaded.
             * If load succeeds, value in SOUND_EFFECT_FILES_MAP[effect][1] is > 0:
             * this indicates we have a valid sample loaded for this effect.
             */
            for (int effect = 0; effect < AudioManager.NUM_SOUND_EFFECTS; effect++) {
                // Do not load sample if this effect uses the MediaPlayer
                if (SOUND_EFFECT_FILES_MAP[effect][1] == 0) {
                    continue;
                }
                if (poolId[SOUND_EFFECT_FILES_MAP[effect][0]] == -1) {
                    String filePath = Environment.getRootDirectory() + SOUND_EFFECTS_PATH + SOUND_EFFECT_FILES[SOUND_EFFECT_FILES_MAP[effect][0]];
                    int sampleId = mSoundPool.load(filePath, 0);
                    SOUND_EFFECT_FILES_MAP[effect][1] = sampleId;
                    poolId[SOUND_EFFECT_FILES_MAP[effect][0]] = sampleId;
                    if (sampleId <= 0) {
                        Log.w(TAG, "Soundpool could not load file: "+filePath);
                    }
                } else {
                    SOUND_EFFECT_FILES_MAP[effect][1] = poolId[SOUND_EFFECT_FILES_MAP[effect][0]];
                }
            }
        }

        return true;
    }

    /**
     *  Unloads samples from the sound pool.
     *  This method can be called to free some memory when
     *  sound effects are disabled.
     */
    public void unloadSoundEffects() {
        synchronized (mSoundEffectsLock) {
            if (mSoundPool == null) {
                return;
            }
            int[] poolId = new int[SOUND_EFFECT_FILES.length];
            for (int fileIdx = 0; fileIdx < SOUND_EFFECT_FILES.length; fileIdx++) {
                poolId[fileIdx] = 0;
            }

            for (int effect = 0; effect < AudioManager.NUM_SOUND_EFFECTS; effect++) {
                if (SOUND_EFFECT_FILES_MAP[effect][1] <= 0) {
                    continue;
                }
                if (poolId[SOUND_EFFECT_FILES_MAP[effect][0]] == 0) {
                    mSoundPool.unload(SOUND_EFFECT_FILES_MAP[effect][1]);
                    SOUND_EFFECT_FILES_MAP[effect][1] = -1;
                    poolId[SOUND_EFFECT_FILES_MAP[effect][0]] = -1;
                }
            }
            mSoundPool = null;
        }
    }

    /** @see AudioManager#reloadAudioSettings() */
    public void reloadAudioSettings() {
        // restore ringer mode, ringer mode affected streams, mute affected streams and vibrate settings
        readPersistedSettings();

        // restore volume settings
        int numStreamTypes = AudioSystem.getNumStreamTypes();
        for (int streamType = 0; streamType < numStreamTypes; streamType++) {
            VolumeStreamState streamState = mStreamStates[streamType];

            String settingName = System.VOLUME_SETTINGS[STREAM_VOLUME_ALIAS[streamType]];
            String lastAudibleSettingName = settingName + System.APPEND_FOR_LAST_AUDIBLE;
            int index = Settings.System.getInt(mContentResolver,
                                           settingName,
                                           AudioManager.DEFAULT_STREAM_VOLUME[streamType]);
            if (STREAM_VOLUME_ALIAS[streamType] != streamType) {
                index = rescaleIndex(index * 10, STREAM_VOLUME_ALIAS[streamType], streamType);
            } else {
                index *= 10;
            }
            streamState.mIndex = streamState.getValidIndex(index);

            index = (index + 5) / 10;
            index = Settings.System.getInt(mContentResolver,
                                            lastAudibleSettingName,
                                            (index > 0) ? index : AudioManager.DEFAULT_STREAM_VOLUME[streamType]);
            if (STREAM_VOLUME_ALIAS[streamType] != streamType) {
                index = rescaleIndex(index * 10, STREAM_VOLUME_ALIAS[streamType], streamType);
            } else {
                index *= 10;
            }
            streamState.mLastAudibleIndex = streamState.getValidIndex(index);

            // unmute stream that whas muted but is not affect by mute anymore
            if (streamState.muteCount() != 0 && !isStreamAffectedByMute(streamType)) {
                int size = streamState.mDeathHandlers.size();
                for (int i = 0; i < size; i++) {
                    streamState.mDeathHandlers.get(i).mMuteCount = 1;
                    streamState.mDeathHandlers.get(i).mute(false);
                }
            }
            // apply stream volume
            if (streamState.muteCount() == 0) {
                setStreamVolumeIndex(streamType, streamState.mIndex);
            }
        }

        // apply new ringer mode
        setRingerModeInt(getRingerMode(), false);
    }

    /** @see AudioManager#setSpeakerphoneOn() */
    public void setSpeakerphoneOn(boolean on){
        if (!checkAudioSettingsPermission("setSpeakerphoneOn()")) {
            return;
        }
        if (on) {
            AudioSystem.setForceUse(AudioSystem.FOR_COMMUNICATION, AudioSystem.FORCE_SPEAKER);
            mForcedUseForComm = AudioSystem.FORCE_SPEAKER;
        } else {
            AudioSystem.setForceUse(AudioSystem.FOR_COMMUNICATION, AudioSystem.FORCE_NONE);
            mForcedUseForComm = AudioSystem.FORCE_NONE;
        }
    }

    /** @see AudioManager#isSpeakerphoneOn() */
    public boolean isSpeakerphoneOn() {
        if (mForcedUseForComm == AudioSystem.FORCE_SPEAKER) {
            return true;
        } else {
            return false;
        }
    }

    /** @see AudioManager#setBluetoothScoOn() */
    public void setBluetoothScoOn(boolean on){
        if (!checkAudioSettingsPermission("setBluetoothScoOn()")) {
            return;
        }
        if (on) {
            AudioSystem.setForceUse(AudioSystem.FOR_COMMUNICATION, AudioSystem.FORCE_BT_SCO);
            AudioSystem.setForceUse(AudioSystem.FOR_RECORD, AudioSystem.FORCE_BT_SCO);
            mForcedUseForComm = AudioSystem.FORCE_BT_SCO;
        } else {
            AudioSystem.setForceUse(AudioSystem.FOR_COMMUNICATION, AudioSystem.FORCE_NONE);
            AudioSystem.setForceUse(AudioSystem.FOR_RECORD, AudioSystem.FORCE_NONE);
            mForcedUseForComm = AudioSystem.FORCE_NONE;
        }
    }

    /** @see AudioManager#isBluetoothScoOn() */
    public boolean isBluetoothScoOn() {
        if (mForcedUseForComm == AudioSystem.FORCE_BT_SCO) {
            return true;
        } else {
            return false;
        }
    }

    ///////////////////////////////////////////////////////////////////////////
    // Internal methods
    ///////////////////////////////////////////////////////////////////////////

    /**
     * Checks if the adjustment should change ringer mode instead of just
     * adjusting volume. If so, this will set the proper ringer mode and volume
     * indices on the stream states.
     */
    private boolean checkForRingerModeChange(int oldIndex, int direction) {
        boolean adjustVolumeIndex = true;
        int newRingerMode = mRingerMode;

        if (mRingerMode == AudioManager.RINGER_MODE_NORMAL && (oldIndex + 5) / 10 == 1
                && direction == AudioManager.ADJUST_LOWER) {
            newRingerMode = AudioManager.RINGER_MODE_VIBRATE;
        } else if (mRingerMode == AudioManager.RINGER_MODE_VIBRATE) {
            if (direction == AudioManager.ADJUST_RAISE) {
                newRingerMode = AudioManager.RINGER_MODE_NORMAL;
            } else if (direction == AudioManager.ADJUST_LOWER) {
                newRingerMode = AudioManager.RINGER_MODE_SILENT;
            }
        } else if (direction == AudioManager.ADJUST_RAISE
                && mRingerMode == AudioManager.RINGER_MODE_SILENT) {
            newRingerMode = AudioManager.RINGER_MODE_VIBRATE;
        }

        if (newRingerMode != mRingerMode) {
            setRingerMode(newRingerMode);

            /*
             * If we are changing ringer modes, do not increment/decrement the
             * volume index. Instead, the handler for the message above will
             * take care of changing the index.
             */
            adjustVolumeIndex = false;
        }

        return adjustVolumeIndex;
    }

    public boolean isStreamAffectedByRingerMode(int streamType) {
        return (mRingerModeAffectedStreams & (1 << streamType)) != 0;
    }

    public boolean isStreamAffectedByMute(int streamType) {
        return (mMuteAffectedStreams & (1 << streamType)) != 0;
    }

    private void ensureValidDirection(int direction) {
        if (direction < AudioManager.ADJUST_LOWER || direction > AudioManager.ADJUST_RAISE) {
            throw new IllegalArgumentException("Bad direction " + direction);
        }
    }

    private void ensureValidStreamType(int streamType) {
        if (streamType < 0 || streamType >= mStreamStates.length) {
            throw new IllegalArgumentException("Bad stream type " + streamType);
        }
    }

    private int getActiveStreamType(int suggestedStreamType) {
        boolean isOffhook = false;
        try {
            ITelephony phone = ITelephony.Stub.asInterface(ServiceManager.checkService("phone"));
            if (phone != null) isOffhook = phone.isOffhook();
        } catch (RemoteException e) {
            Log.w(TAG, "Couldn't connect to phone service", e);
        }

        if (AudioSystem.getForceUse(AudioSystem.FOR_COMMUNICATION) == AudioSystem.FORCE_BT_SCO) {
            // Log.v(TAG, "getActiveStreamType: Forcing STREAM_BLUETOOTH_SCO...");
            return AudioSystem.STREAM_BLUETOOTH_SCO;
        } else if (isOffhook) {
            // Log.v(TAG, "getActiveStreamType: Forcing STREAM_VOICE_CALL...");
            return AudioSystem.STREAM_VOICE_CALL;
        } else if (AudioSystem.isMusicActive()) {
            // Log.v(TAG, "getActiveStreamType: Forcing STREAM_MUSIC...");
            return AudioSystem.STREAM_MUSIC;
        } else if (suggestedStreamType == AudioManager.USE_DEFAULT_STREAM_TYPE) {
            // Log.v(TAG, "getActiveStreamType: Forcing STREAM_RING...");
            return AudioSystem.STREAM_RING;
        } else {
            // Log.v(TAG, "getActiveStreamType: Returning suggested type " + suggestedStreamType);
            return suggestedStreamType;
        }
    }

    private void broadcastRingerMode() {
        // Send sticky broadcast
        if (ActivityManagerNative.isSystemReady()) {
            Intent broadcast = new Intent(AudioManager.RINGER_MODE_CHANGED_ACTION);
            broadcast.putExtra(AudioManager.EXTRA_RINGER_MODE, mRingerMode);
            long origCallerIdentityToken = Binder.clearCallingIdentity();
            mContext.sendStickyBroadcast(broadcast);
            Binder.restoreCallingIdentity(origCallerIdentityToken);
        }
    }

    private void broadcastVibrateSetting(int vibrateType) {
        // Send broadcast
        if (ActivityManagerNative.isSystemReady()) {
            Intent broadcast = new Intent(AudioManager.VIBRATE_SETTING_CHANGED_ACTION);
            broadcast.putExtra(AudioManager.EXTRA_VIBRATE_TYPE, vibrateType);
            broadcast.putExtra(AudioManager.EXTRA_VIBRATE_SETTING, getVibrateSetting(vibrateType));
            mContext.sendBroadcast(broadcast);
        }
    }

    // Message helper methods
    private static int getMsg(int baseMsg, int streamType) {
        return (baseMsg & 0xffff) | streamType << 16;
    }

    private static int getMsgBase(int msg) {
        return msg & 0xffff;
    }

    private static void sendMsg(Handler handler, int baseMsg, int streamType,
            int existingMsgPolicy, int arg1, int arg2, Object obj, int delay) {
        int msg = (streamType == SHARED_MSG) ? baseMsg : getMsg(baseMsg, streamType);

        if (existingMsgPolicy == SENDMSG_REPLACE) {
            handler.removeMessages(msg);
        } else if (existingMsgPolicy == SENDMSG_NOOP && handler.hasMessages(msg)) {
            return;
        }

        handler
                .sendMessageDelayed(handler.obtainMessage(msg, arg1, arg2, obj), delay);
    }

    boolean checkAudioSettingsPermission(String method) {
        if (mContext.checkCallingOrSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS")
                == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        String msg = "Audio Settings Permission Denial: " + method + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid();
        Log.w(TAG, msg);
        return false;
    }


    ///////////////////////////////////////////////////////////////////////////
    // Inner classes
    ///////////////////////////////////////////////////////////////////////////

    public class VolumeStreamState {
        private final int mStreamType;

        private String mVolumeIndexSettingName;
        private String mLastAudibleVolumeIndexSettingName;
        private int mIndexMax;
        private int mIndex;
        private int mLastAudibleIndex;
        private ArrayList<VolumeDeathHandler> mDeathHandlers; //handles mute/solo requests client death

        private VolumeStreamState(String settingName, int streamType) {

            setVolumeIndexSettingName(settingName);

            mStreamType = streamType;

            final ContentResolver cr = mContentResolver;
            mIndexMax = MAX_STREAM_VOLUME[streamType];
            mIndex = Settings.System.getInt(cr,
                                            mVolumeIndexSettingName,
                                            AudioManager.DEFAULT_STREAM_VOLUME[streamType]);
            mLastAudibleIndex = Settings.System.getInt(cr,
                                                       mLastAudibleVolumeIndexSettingName,
                                                       (mIndex > 0) ? mIndex : AudioManager.DEFAULT_STREAM_VOLUME[streamType]);
            AudioSystem.initStreamVolume(streamType, 0, mIndexMax);
            mIndexMax *= 10;
            mIndex = getValidIndex(10 * mIndex);
            mLastAudibleIndex = getValidIndex(10 * mLastAudibleIndex);
            setStreamVolumeIndex(streamType, mIndex);
            mDeathHandlers = new ArrayList<VolumeDeathHandler>();
        }

        public void setVolumeIndexSettingName(String settingName) {
            mVolumeIndexSettingName = settingName;
            mLastAudibleVolumeIndexSettingName = settingName + System.APPEND_FOR_LAST_AUDIBLE;
        }

        public boolean adjustIndex(int deltaIndex) {
            return setIndex(mIndex + deltaIndex * 10, true);
        }

        public boolean setIndex(int index, boolean lastAudible) {
            int oldIndex = mIndex;
            mIndex = getValidIndex(index);

            if (oldIndex != mIndex) {
                if (lastAudible) {
                    mLastAudibleIndex = mIndex;
                }
                // Apply change to all streams using this one as alias
                int numStreamTypes = AudioSystem.getNumStreamTypes();
                for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                    if (streamType != mStreamType && STREAM_VOLUME_ALIAS[streamType] == mStreamType) {
                        mStreamStates[streamType].setIndex(rescaleIndex(mIndex, mStreamType, streamType), lastAudible);
                    }
                }
                return true;
            } else {
                return false;
            }
        }

        public int getMaxIndex() {
            return mIndexMax;
        }

        public void mute(IBinder cb, boolean state) {
            VolumeDeathHandler handler = getDeathHandler(cb, state);
            if (handler == null) {
                Log.e(TAG, "Could not get client death handler for stream: "+mStreamType);
                return;
            }
            handler.mute(state);
        }

        private int getValidIndex(int index) {
            if (index < 0) {
                return 0;
            } else if (index > mIndexMax) {
                return mIndexMax;
            }

            return index;
        }

        private class VolumeDeathHandler implements IBinder.DeathRecipient {
            private IBinder mICallback; // To be notified of client's death
            private int mMuteCount; // Number of active mutes for this client

            VolumeDeathHandler(IBinder cb) {
                mICallback = cb;
            }

            public void mute(boolean state) {
                synchronized(mDeathHandlers) {
                    if (state) {
                        if (mMuteCount == 0) {
                            // Register for client death notification
                            try {
                                mICallback.linkToDeath(this, 0);
                                mDeathHandlers.add(this);
                                // If the stream is not yet muted by any client, set lvel to 0
                                if (muteCount() == 0) {
                                    setIndex(0, false);
                                    sendMsg(mAudioHandler, MSG_SET_SYSTEM_VOLUME, mStreamType, SENDMSG_NOOP, 0, 0,
                                            VolumeStreamState.this, 0);
                                }
                            } catch (RemoteException e) {
                                // Client has died!
                                binderDied();
                                mDeathHandlers.notify();
                                return;
                            }
                        } else {
                            Log.w(TAG, "stream: "+mStreamType+" was already muted by this client");
                        }
                        mMuteCount++;
                    } else {
                        if (mMuteCount == 0) {
                            Log.e(TAG, "unexpected unmute for stream: "+mStreamType);
                        } else {
                            mMuteCount--;
                            if (mMuteCount == 0) {
                                // Unregistr from client death notification
                                mDeathHandlers.remove(this);
                                mICallback.unlinkToDeath(this, 0);
                                if (muteCount() == 0) {
                                    // If the stream is not mut any more, restore it's volume if
                                    // ringer mode allows it
                                    if (!isStreamAffectedByRingerMode(mStreamType) || mRingerMode == AudioManager.RINGER_MODE_NORMAL) {
                                        setIndex(mLastAudibleIndex, false);
                                        sendMsg(mAudioHandler, MSG_SET_SYSTEM_VOLUME, mStreamType, SENDMSG_NOOP, 0, 0,
                                                VolumeStreamState.this, 0);
                                    }
                                }
                            }
                        }
                    }
                    mDeathHandlers.notify();
                }
            }

            public void binderDied() {
                Log.w(TAG, "Volume service client died for stream: "+mStreamType);
                if (mMuteCount != 0) {
                    // Reset all active mute requests from this client.
                    mMuteCount = 1;
                    mute(false);
                }
            }
        }

        private int muteCount() {
            int count = 0;
            int size = mDeathHandlers.size();
            for (int i = 0; i < size; i++) {
                count += mDeathHandlers.get(i).mMuteCount;
            }
            return count;
        }

        private VolumeDeathHandler getDeathHandler(IBinder cb, boolean state) {
            synchronized(mDeathHandlers) {
                VolumeDeathHandler handler;
                int size = mDeathHandlers.size();
                for (int i = 0; i < size; i++) {
                    handler = mDeathHandlers.get(i);
                    if (cb.equals(handler.mICallback)) {
                        return handler;
                    }
                }
                // If this is the first mute request for this client, create a new
                // client death handler. Otherwise, it is an out of sequence unmute request.
                if (state) {
                    handler = new VolumeDeathHandler(cb);
                } else {
                    Log.w(TAG, "stream was not muted by this client");
                    handler = null;
                }
                return handler;
            }
        }
    }

    /** Thread that handles native AudioSystem control. */
    private class AudioSystemThread extends Thread {
        AudioSystemThread() {
            super("AudioService");
        }

        @Override
        public void run() {
            // Set this thread up so the handler will work on it
            Looper.prepare();

            synchronized(AudioService.this) {
                mAudioHandler = new AudioHandler();

                // Notify that the handler has been created
                AudioService.this.notify();
            }

            // Listen for volume change requests that are set by VolumePanel
            Looper.loop();
        }
    }

    /** Handles internal volume messages in separate volume thread. */
    private class AudioHandler extends Handler {

        private void setSystemVolume(VolumeStreamState streamState) {

            // Adjust volume
            setStreamVolumeIndex(streamState.mStreamType, streamState.mIndex);

            // Apply change to all streams using this one as alias
            int numStreamTypes = AudioSystem.getNumStreamTypes();
            for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                if (streamType != streamState.mStreamType &&
                    STREAM_VOLUME_ALIAS[streamType] == streamState.mStreamType) {
                    setStreamVolumeIndex(streamType, mStreamStates[streamType].mIndex);
                }
            }

            // Post a persist volume msg
            sendMsg(mAudioHandler, MSG_PERSIST_VOLUME, streamState.mStreamType,
                    SENDMSG_REPLACE, 0, 0, streamState, PERSIST_DELAY);
        }

        private void persistVolume(VolumeStreamState streamState) {
            System.putInt(mContentResolver, streamState.mVolumeIndexSettingName,
                    (streamState.mIndex + 5)/ 10);
            System.putInt(mContentResolver, streamState.mLastAudibleVolumeIndexSettingName,
                    (streamState.mLastAudibleIndex + 5) / 10);
        }

        private void persistRingerMode() {
            System.putInt(mContentResolver, System.MODE_RINGER, mRingerMode);
        }

        private void persistVibrateSetting() {
            System.putInt(mContentResolver, System.VIBRATE_ON, mVibrateSetting);
        }

        private void playSoundEffect(int effectType, int volume) {
            synchronized (mSoundEffectsLock) {
                if (mSoundPool == null) {
                    return;
                }
                float volFloat;
                // use STREAM_MUSIC volume attenuated by 3 dB if volume is not specified by caller
                if (volume < 0) {
                    // Same linear to log conversion as in native AudioSystem::linearToLog() (AudioSystem.cpp)
                    float dBPerStep = (float)((0.5 * 100) / MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC]);
                    int musicVolIndex = (mStreamStates[AudioSystem.STREAM_MUSIC].mIndex + 5) / 10;
                    float musicVoldB = dBPerStep * (musicVolIndex - MAX_STREAM_VOLUME[AudioSystem.STREAM_MUSIC]);
                    volFloat = (float)Math.pow(10, (musicVoldB - 3)/20);
                } else {
                    volFloat = (float) volume / 1000.0f;
                }

                if (SOUND_EFFECT_FILES_MAP[effectType][1] > 0) {
                    mSoundPool.play(SOUND_EFFECT_FILES_MAP[effectType][1], volFloat, volFloat, 0, 0, 1.0f);
                } else {
                    MediaPlayer mediaPlayer = new MediaPlayer();
                    if (mediaPlayer != null) {
                        try {
                            String filePath = Environment.getRootDirectory() + SOUND_EFFECTS_PATH + SOUND_EFFECT_FILES[SOUND_EFFECT_FILES_MAP[effectType][0]];
                            mediaPlayer.setDataSource(filePath);
                            mediaPlayer.setAudioStreamType(AudioSystem.STREAM_SYSTEM);
                            mediaPlayer.prepare();
                            mediaPlayer.setVolume(volFloat, volFloat);
                            mediaPlayer.setOnCompletionListener(new OnCompletionListener() {
                                public void onCompletion(MediaPlayer mp) {
                                    cleanupPlayer(mp);
                                }
                            });
                            mediaPlayer.setOnErrorListener(new OnErrorListener() {
                                public boolean onError(MediaPlayer mp, int what, int extra) {
                                    cleanupPlayer(mp);
                                    return true;
                                }
                            });
                            mediaPlayer.start();
                        } catch (IOException ex) {
                            Log.w(TAG, "MediaPlayer IOException: "+ex);
                        } catch (IllegalArgumentException ex) {
                            Log.w(TAG, "MediaPlayer IllegalArgumentException: "+ex);
                        } catch (IllegalStateException ex) {
                            Log.w(TAG, "MediaPlayer IllegalStateException: "+ex);
                        }
                    }
                }
            }
        }

        private void cleanupPlayer(MediaPlayer mp) {
            if (mp != null) {
                try {
                    mp.stop();
                    mp.release();
                } catch (IllegalStateException ex) {
                    Log.w(TAG, "MediaPlayer IllegalStateException: "+ex);
                }
            }
        }

        @Override
        public void handleMessage(Message msg) {
            int baseMsgWhat = getMsgBase(msg.what);

            switch (baseMsgWhat) {

                case MSG_SET_SYSTEM_VOLUME:
                    setSystemVolume((VolumeStreamState) msg.obj);
                    break;

                case MSG_PERSIST_VOLUME:
                    persistVolume((VolumeStreamState) msg.obj);
                    break;

                case MSG_PERSIST_RINGER_MODE:
                    persistRingerMode();
                    break;

                case MSG_PERSIST_VIBRATE_SETTING:
                    persistVibrateSetting();
                    break;

                case MSG_MEDIA_SERVER_DIED:
                    // Force creation of new IAudioflinger interface
                    if (!mMediaServerOk) {
                        Log.e(TAG, "Media server died.");
                        AudioSystem.isMusicActive();
                        sendMsg(mAudioHandler, MSG_MEDIA_SERVER_DIED, SHARED_MSG, SENDMSG_NOOP, 0, 0,
                                null, 500);
                    }
                    break;

                case MSG_MEDIA_SERVER_STARTED:
                    Log.e(TAG, "Media server started.");
                    // Restore device connection states
                    Set set = mConnectedDevices.entrySet();
                    Iterator i = set.iterator();
                    while(i.hasNext()){
                        Map.Entry device = (Map.Entry)i.next();
                        AudioSystem.setDeviceConnectionState(((Integer)device.getKey()).intValue(),
                                                             AudioSystem.DEVICE_STATE_AVAILABLE,
                                                             (String)device.getValue());
                    }

                    // Restore call state
                    AudioSystem.setPhoneState(mMode);

                    // Restore forced usage for communcations and record
                    AudioSystem.setForceUse(AudioSystem.FOR_COMMUNICATION, mForcedUseForComm);
                    AudioSystem.setForceUse(AudioSystem.FOR_RECORD, mForcedUseForComm);

                    // Restore stream volumes
                    int numStreamTypes = AudioSystem.getNumStreamTypes();
                    for (int streamType = numStreamTypes - 1; streamType >= 0; streamType--) {
                        int index;
                        VolumeStreamState streamState = mStreamStates[streamType];
                        AudioSystem.initStreamVolume(streamType, 0, (streamState.mIndexMax + 5) / 10);
                        if (streamState.muteCount() == 0) {
                            index = streamState.mIndex;
                        } else {
                            index = 0;
                        }
                        setStreamVolumeIndex(streamType, index);
                    }

                    // Restore ringer mode
                    setRingerModeInt(getRingerMode(), false);
                    break;

                case MSG_PLAY_SOUND_EFFECT:
                    playSoundEffect(msg.arg1, msg.arg2);
                    break;
            }
        }
    }

    private class SettingsObserver extends ContentObserver {

        SettingsObserver() {
            super(new Handler());
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                Settings.System.MODE_RINGER_STREAMS_AFFECTED), false, this);
            mContentResolver.registerContentObserver(Settings.System.getUriFor(
                    Settings.System.NOTIFICATIONS_USE_RING_VOLUME), false, this);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            synchronized (mSettingsLock) {
                int ringerModeAffectedStreams = Settings.System.getInt(mContentResolver,
                        Settings.System.MODE_RINGER_STREAMS_AFFECTED,
                        0);
                if (ringerModeAffectedStreams != mRingerModeAffectedStreams) {
                    /*
                     * Ensure all stream types that should be affected by ringer mode
                     * are in the proper state.
                     */
                    mRingerModeAffectedStreams = ringerModeAffectedStreams;
                    setRingerModeInt(getRingerMode(), false);
                }

                int notificationsUseRingVolume = Settings.System.getInt(mContentResolver,
                        Settings.System.NOTIFICATIONS_USE_RING_VOLUME,
                        1);
                if (notificationsUseRingVolume != mNotificationsUseRingVolume) {
                    mNotificationsUseRingVolume = notificationsUseRingVolume;
                    if (mNotificationsUseRingVolume == 1) {
                        STREAM_VOLUME_ALIAS[AudioSystem.STREAM_NOTIFICATION] = AudioSystem.STREAM_RING;
                        mStreamStates[AudioSystem.STREAM_NOTIFICATION].setVolumeIndexSettingName(
                                System.VOLUME_SETTINGS[AudioSystem.STREAM_RING]);
                    } else {
                        STREAM_VOLUME_ALIAS[AudioSystem.STREAM_NOTIFICATION] = AudioSystem.STREAM_NOTIFICATION;
                        mStreamStates[AudioSystem.STREAM_NOTIFICATION].setVolumeIndexSettingName(
                                System.VOLUME_SETTINGS[AudioSystem.STREAM_NOTIFICATION]);
                        // Persist notification volume volume as it was not persisted while aliased to ring volume
                        //  and persist with no delay as there might be registered observers of the persisted
                        //  notification volume.
                        sendMsg(mAudioHandler, MSG_PERSIST_VOLUME, AudioSystem.STREAM_NOTIFICATION,
                                SENDMSG_REPLACE, 0, 0, mStreamStates[AudioSystem.STREAM_NOTIFICATION], 0);
                    }
                }
            }
        }
    }

    /**
     * Receiver for misc intent broadcasts the Phone app cares about.
     */
    private class AudioServiceBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(BluetoothA2dp.ACTION_SINK_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothA2dp.EXTRA_SINK_STATE,
                                               BluetoothA2dp.STATE_DISCONNECTED);
                BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String address = btDevice.getAddress();
                boolean isConnected = (mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP) &&
                                       ((String)mConnectedDevices.get(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP)).equals(address));

                if (isConnected &&
                    state != BluetoothA2dp.STATE_CONNECTED && state != BluetoothA2dp.STATE_PLAYING) {
                    if (address.equals(sBtDockAddress)) {
                        Log.v(TAG, "Recognized undocking from BT dock");
                        AudioSystem.setForceUse(AudioSystem.FOR_DOCK, AudioSystem.FORCE_NONE);
                    }
                    AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                            AudioSystem.DEVICE_STATE_UNAVAILABLE,
                            address);
                    mConnectedDevices.remove(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP);
                } else if (!isConnected &&
                             (state == BluetoothA2dp.STATE_CONNECTED ||
                              state == BluetoothA2dp.STATE_PLAYING)) {
                    if (btDevice.isBluetoothDock()) {
                        Log.v(TAG, "Recognized connection to BT dock");
                        sBtDockAddress = address;
                        Intent i = context.registerReceiver(null, new IntentFilter(Intent.ACTION_DOCK_EVENT));
                        if (i != null) {
                            int dockState = i.getIntExtra(Intent.EXTRA_DOCK_STATE, Intent.EXTRA_DOCK_STATE_UNDOCKED);
                            int config;
                            switch (dockState) {
                                case Intent.EXTRA_DOCK_STATE_DESK:
                                    config = AudioSystem.FORCE_BT_DESK_DOCK;
                                    break;
                                case Intent.EXTRA_DOCK_STATE_CAR:
                                    config = AudioSystem.FORCE_BT_CAR_DOCK;
                                    break;
                                case Intent.EXTRA_DOCK_STATE_UNDOCKED:
                                default:
                                    config = AudioSystem.FORCE_NONE;
                            }
                            AudioSystem.setForceUse(AudioSystem.FOR_DOCK, config);
                        }
                    }
                    AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP,
                                                         AudioSystem.DEVICE_STATE_AVAILABLE,
                                                         address);
                    // Reset A2DP suspend state each time a new sink is connected
                    AudioSystem.setParameters("A2dpSuspended=false");
                    mConnectedDevices.put( new Integer(AudioSystem.DEVICE_OUT_BLUETOOTH_A2DP),
                            address);
                }
            } else if (action.equals(BluetoothHeadset.ACTION_STATE_CHANGED)) {
                int state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE,
                                               BluetoothHeadset.STATE_ERROR);
                int device = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO;
                BluetoothDevice btDevice = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                String address = null;
                if (btDevice != null) {
                    address = btDevice.getAddress();
                    BluetoothClass btClass = btDevice.getBluetoothClass();
                    if (btClass != null) {
                        switch (btClass.getDeviceClass()) {
                        case BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET:
                        case BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE:
                            device = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_HEADSET;
                            break;
                        case BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO:
                            device = AudioSystem.DEVICE_OUT_BLUETOOTH_SCO_CARKIT;
                            break;
                        }
                    }
                }

                boolean isConnected = (mConnectedDevices.containsKey(device) &&
                                       ((String)mConnectedDevices.get(device)).equals(address));

                if (isConnected && state != BluetoothHeadset.STATE_CONNECTED) {
                    AudioSystem.setDeviceConnectionState(device,
                                                         AudioSystem.DEVICE_STATE_UNAVAILABLE,
                                                         address);
                    mConnectedDevices.remove(device);
                } else if (!isConnected && state == BluetoothHeadset.STATE_CONNECTED) {
                    AudioSystem.setDeviceConnectionState(device,
                                                         AudioSystem.DEVICE_STATE_AVAILABLE,
                                                         address);
                    mConnectedDevices.put(new Integer(device), address);
                }
            } else if (action.equals(Intent.ACTION_HEADSET_PLUG)) {
                int state = intent.getIntExtra("state", 0);
                int microphone = intent.getIntExtra("microphone", 0);

                if (microphone != 0) {
                    boolean isConnected = mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_WIRED_HEADSET);
                    if (state == 0 && isConnected) {
                        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_WIRED_HEADSET,
                                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                                "");
                        mConnectedDevices.remove(AudioSystem.DEVICE_OUT_WIRED_HEADSET);
                    } else if (state == 1 && !isConnected)  {
                        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_WIRED_HEADSET,
                                AudioSystem.DEVICE_STATE_AVAILABLE,
                                "");
                        mConnectedDevices.put( new Integer(AudioSystem.DEVICE_OUT_WIRED_HEADSET), "");
                    }
                } else {
                    boolean isConnected = mConnectedDevices.containsKey(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
                    if (state == 0 && isConnected) {
                        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE,
                                AudioSystem.DEVICE_STATE_UNAVAILABLE,
                                "");
                        mConnectedDevices.remove(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE);
                    } else if (state == 1 && !isConnected)  {
                        AudioSystem.setDeviceConnectionState(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE,
                                AudioSystem.DEVICE_STATE_AVAILABLE,
                                "");
                        mConnectedDevices.put( new Integer(AudioSystem.DEVICE_OUT_WIRED_HEADPHONE), "");
                    }
                }
            }
        }
    }
}
