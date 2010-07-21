/*
 * Copyright (C) 2006-2008 The Android Open Source Project
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

package com.android.server.am;

import com.android.internal.os.BatteryStatsImpl;
import com.android.server.AttributeCache;
import com.android.server.IntentResolver;
import com.android.server.ProcessMap;
import com.android.server.ProcessStats;
import com.android.server.SystemServer;
import com.android.server.Watchdog;
import com.android.server.WindowManagerService;

import dalvik.system.Zygote;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.ApplicationErrorReport;
import android.app.Dialog;
import android.app.IActivityController;
import android.app.IActivityManager;
import android.app.IActivityWatcher;
import android.app.IApplicationThread;
import android.app.IInstrumentationWatcher;
import android.app.IServiceConnection;
import android.app.IThumbnailReceiver;
import android.app.Instrumentation;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.ResultInfo;
import android.app.Service;
import android.app.backup.IBackupManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IIntentReceiver;
import android.content.IIntentSender;
import android.content.IntentSender;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.IPackageDataObserver;
import android.content.pm.IPackageManager;
import android.content.pm.InstrumentationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PathPermission;
import android.content.pm.ProviderInfo;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.FileObserver;
import android.os.FileUtils;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPermissionController;
import android.os.Looper;
import android.os.Message;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.util.Config;
import android.util.EventLog;
import android.util.Slog;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.SparseArray;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManagerPolicy;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.lang.IllegalStateException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class ActivityManagerService extends ActivityManagerNative implements Watchdog.Monitor {
    static final String TAG = "ActivityManager";
    static final boolean DEBUG = false;
    static final boolean localLOGV = DEBUG ? Config.LOGD : Config.LOGV;
    static final boolean DEBUG_SWITCH = localLOGV || false;
    static final boolean DEBUG_TASKS = localLOGV || false;
    static final boolean DEBUG_PAUSE = localLOGV || false;
    static final boolean DEBUG_OOM_ADJ = localLOGV || false;
    static final boolean DEBUG_TRANSITION = localLOGV || false;
    static final boolean DEBUG_BROADCAST = localLOGV || false;
    static final boolean DEBUG_BROADCAST_LIGHT = DEBUG_BROADCAST || false;
    static final boolean DEBUG_SERVICE = localLOGV || false;
    static final boolean DEBUG_VISBILITY = localLOGV || false;
    static final boolean DEBUG_PROCESSES = localLOGV || false;
    static final boolean DEBUG_PROVIDER = localLOGV || false;
    static final boolean DEBUG_URI_PERMISSION = localLOGV || false;
    static final boolean DEBUG_USER_LEAVING = localLOGV || false;
    static final boolean DEBUG_RESULTS = localLOGV || false;
    static final boolean DEBUG_BACKUP = localLOGV || false;
    static final boolean DEBUG_CONFIGURATION = localLOGV || false;
    static final boolean VALIDATE_TOKENS = false;
    static final boolean SHOW_ACTIVITY_START_TIME = true;
    
    // Control over CPU and battery monitoring.
    static final long BATTERY_STATS_TIME = 30*60*1000;      // write battery stats every 30 minutes.
    static final boolean MONITOR_CPU_USAGE = true;
    static final long MONITOR_CPU_MIN_TIME = 5*1000;        // don't sample cpu less than every 5 seconds.
    static final long MONITOR_CPU_MAX_TIME = 0x0fffffff;    // wait possibly forever for next cpu sample.
    static final boolean MONITOR_THREAD_CPU_USAGE = false;

    // The flags that are set for all calls we make to the package manager.
    static final int STOCK_PM_FLAGS = PackageManager.GET_SHARED_LIBRARY_FILES;
    
    private static final String SYSTEM_SECURE = "ro.secure";

    // This is the maximum number of application processes we would like
    // to have running.  Due to the asynchronous nature of things, we can
    // temporarily go beyond this limit.
    static final int MAX_PROCESSES = 2;

    // Set to false to leave processes running indefinitely, relying on
    // the kernel killing them as resources are required.
    static final boolean ENFORCE_PROCESS_LIMIT = false;

    // This is the maximum number of activities that we would like to have
    // running at a given time.
    static final int MAX_ACTIVITIES = 20;

    // Maximum number of recent tasks that we can remember.
    static final int MAX_RECENT_TASKS = 20;

    // Amount of time after a call to stopAppSwitches() during which we will
    // prevent further untrusted switches from happening.
    static final long APP_SWITCH_DELAY_TIME = 5*1000;
    
    // How long until we reset a task when the user returns to it.  Currently
    // 30 minutes.
    static final long ACTIVITY_INACTIVE_RESET_TIME = 1000*60*30;
    
    // Set to true to disable the icon that is shown while a new activity
    // is being started.
    static final boolean SHOW_APP_STARTING_ICON = true;

    // How long we wait until giving up on the last activity to pause.  This
    // is short because it directly impacts the responsiveness of starting the
    // next activity.
    static final int PAUSE_TIMEOUT = 500;

    /**
     * How long we can hold the launch wake lock before giving up.
     */
    static final int LAUNCH_TIMEOUT = 40*1000;

    // How long we wait for a launched process to attach to the activity manager
    // before we decide it's never going to come up for real.
    static final int PROC_START_TIMEOUT = 40*1000;

    // How long we wait until giving up on the last activity telling us it
    // is idle.
    static final int IDLE_TIMEOUT = 40*1000;

    // How long to wait after going idle before forcing apps to GC.
    static final int GC_TIMEOUT = 20*1000;

    // The minimum amount of time between successive GC requests for a process.
    static final int GC_MIN_INTERVAL = 60*1000;

    // How long we wait until giving up on an activity telling us it has
    // finished destroying itself.
    static final int DESTROY_TIMEOUT = 40*1000;
    
    // How long we allow a receiver to run before giving up on it.
    static final int BROADCAST_TIMEOUT = 40*1000;

    // How long we wait for a service to finish executing.
    static final int SERVICE_TIMEOUT = 80*1000;

    // How long a service needs to be running until restarting its process
    // is no longer considered to be a relaunch of the service.
    static final int SERVICE_RESTART_DURATION = 20*1000;

    // How long a service needs to be running until it will start back at
    // SERVICE_RESTART_DURATION after being killed.
    static final int SERVICE_RESET_RUN_DURATION = 240*1000;

    // Multiplying factor to increase restart duration time by, for each time
    // a service is killed before it has run for SERVICE_RESET_RUN_DURATION.
    static final int SERVICE_RESTART_DURATION_FACTOR = 4;
    
    // The minimum amount of time between restarting services that we allow.
    // That is, when multiple services are restarting, we won't allow each
    // to restart less than this amount of time from the last one.
    static final int SERVICE_MIN_RESTART_TIME_BETWEEN = 10*1000;

    // Maximum amount of time for there to be no activity on a service before
    // we consider it non-essential and allow its process to go on the
    // LRU background list.
    static final int MAX_SERVICE_INACTIVITY = 30*60*1000;
    
    // How long we wait until we timeout on key dispatching.
    static final int KEY_DISPATCHING_TIMEOUT = 20*1000;

    // The minimum time we allow between crashes, for us to consider this
    // application to be bad and stop and its services and reject broadcasts.
    static final int MIN_CRASH_INTERVAL = 60*1000;

    // How long we wait until we timeout on key dispatching during instrumentation.
    static final int INSTRUMENTATION_KEY_DISPATCHING_TIMEOUT = 60*1000;

    // OOM adjustments for processes in various states:

    // This is a process without anything currently running in it.  Definitely
    // the first to go! Value set in system/rootdir/init.rc on startup.
    // This value is initalized in the constructor, careful when refering to
    // this static variable externally.
    static final int EMPTY_APP_ADJ;

    // This is a process only hosting activities that are not visible,
    // so it can be killed without any disruption. Value set in
    // system/rootdir/init.rc on startup.
    static final int HIDDEN_APP_MAX_ADJ;
    static int HIDDEN_APP_MIN_ADJ;

    // This is a process holding the home application -- we want to try
    // avoiding killing it, even if it would normally be in the background,
    // because the user interacts with it so much.
    static final int HOME_APP_ADJ;

    // This is a process currently hosting a backup operation.  Killing it
    // is not entirely fatal but is generally a bad idea.
    static final int BACKUP_APP_ADJ;

    // This is a process holding a secondary server -- killing it will not
    // have much of an impact as far as the user is concerned. Value set in
    // system/rootdir/init.rc on startup.
    static final int SECONDARY_SERVER_ADJ;

    // This is a process only hosting activities that are visible to the
    // user, so we'd prefer they don't disappear. Value set in
    // system/rootdir/init.rc on startup.
    static final int VISIBLE_APP_ADJ;

    // This is the process running the current foreground app.  We'd really
    // rather not kill it! Value set in system/rootdir/init.rc on startup.
    static final int FOREGROUND_APP_ADJ;

    // This is a process running a core server, such as telephony.  Definitely
    // don't want to kill it, but doing so is not completely fatal.
    static final int CORE_SERVER_ADJ = -12;

    // The system process runs at the default adjustment.
    static final int SYSTEM_ADJ = -16;

    // Memory pages are 4K.
    static final int PAGE_SIZE = 4*1024;
    
    // Corresponding memory levels for above adjustments.
    static final int EMPTY_APP_MEM;
    static final int HIDDEN_APP_MEM;
    static final int HOME_APP_MEM;
    static final int BACKUP_APP_MEM;
    static final int SECONDARY_SERVER_MEM;
    static final int VISIBLE_APP_MEM;
    static final int FOREGROUND_APP_MEM;

    // The minimum number of hidden apps we want to be able to keep around,
    // without empty apps being able to push them out of memory.
    static final int MIN_HIDDEN_APPS = 2;
    
    // The maximum number of hidden processes we will keep around before
    // killing them; this is just a control to not let us go too crazy with
    // keeping around processes on devices with large amounts of RAM.
    static final int MAX_HIDDEN_APPS = 15;
    
    // We put empty content processes after any hidden processes that have
    // been idle for less than 15 seconds.
    static final long CONTENT_APP_IDLE_OFFSET = 15*1000;
    
    // We put empty content processes after any hidden processes that have
    // been idle for less than 120 seconds.
    static final long EMPTY_APP_IDLE_OFFSET = 120*1000;
    
    static {
        // These values are set in system/rootdir/init.rc on startup.
        FOREGROUND_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.FOREGROUND_APP_ADJ"));
        VISIBLE_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.VISIBLE_APP_ADJ"));
        SECONDARY_SERVER_ADJ =
            Integer.valueOf(SystemProperties.get("ro.SECONDARY_SERVER_ADJ"));
        BACKUP_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.BACKUP_APP_ADJ"));
        HOME_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.HOME_APP_ADJ"));
        HIDDEN_APP_MIN_ADJ =
            Integer.valueOf(SystemProperties.get("ro.HIDDEN_APP_MIN_ADJ"));
        EMPTY_APP_ADJ =
            Integer.valueOf(SystemProperties.get("ro.EMPTY_APP_ADJ"));
        HIDDEN_APP_MAX_ADJ = EMPTY_APP_ADJ-1;
        FOREGROUND_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.FOREGROUND_APP_MEM"))*PAGE_SIZE;
        VISIBLE_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.VISIBLE_APP_MEM"))*PAGE_SIZE;
        SECONDARY_SERVER_MEM =
            Integer.valueOf(SystemProperties.get("ro.SECONDARY_SERVER_MEM"))*PAGE_SIZE;
        BACKUP_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.BACKUP_APP_MEM"))*PAGE_SIZE;
        HOME_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.HOME_APP_MEM"))*PAGE_SIZE;
        HIDDEN_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.HIDDEN_APP_MEM"))*PAGE_SIZE;
        EMPTY_APP_MEM =
            Integer.valueOf(SystemProperties.get("ro.EMPTY_APP_MEM"))*PAGE_SIZE;
    }
    
    static final int MY_PID = Process.myPid();
    
    static final String[] EMPTY_STRING_ARRAY = new String[0];

    enum ActivityState {
        INITIALIZING,
        RESUMED,
        PAUSING,
        PAUSED,
        STOPPING,
        STOPPED,
        FINISHING,
        DESTROYING,
        DESTROYED
    }

    /**
     * The back history of all previous (and possibly still
     * running) activities.  It contains HistoryRecord objects.
     */
    final ArrayList mHistory = new ArrayList();

    /**
     * Description of a request to start a new activity, which has been held
     * due to app switches being disabled.
     */
    class PendingActivityLaunch {
        HistoryRecord r;
        HistoryRecord sourceRecord;
        Uri[] grantedUriPermissions;
        int grantedMode;
        boolean onlyIfNeeded;
    }
    
    final ArrayList<PendingActivityLaunch> mPendingActivityLaunches
            = new ArrayList<PendingActivityLaunch>();
    
    /**
     * List of people waiting to find out about the next launched activity.
     */
    final ArrayList<IActivityManager.WaitResult> mWaitingActivityLaunched
            = new ArrayList<IActivityManager.WaitResult>();
    
    /**
     * List of people waiting to find out about the next visible activity.
     */
    final ArrayList<IActivityManager.WaitResult> mWaitingActivityVisible
            = new ArrayList<IActivityManager.WaitResult>();
    
    /**
     * List of all active broadcasts that are to be executed immediately
     * (without waiting for another broadcast to finish).  Currently this only
     * contains broadcasts to registered receivers, to avoid spinning up
     * a bunch of processes to execute IntentReceiver components.
     */
    final ArrayList<BroadcastRecord> mParallelBroadcasts
            = new ArrayList<BroadcastRecord>();

    /**
     * List of all active broadcasts that are to be executed one at a time.
     * The object at the top of the list is the currently activity broadcasts;
     * those after it are waiting for the top to finish..
     */
    final ArrayList<BroadcastRecord> mOrderedBroadcasts
            = new ArrayList<BroadcastRecord>();

    /**
     * Historical data of past broadcasts, for debugging.
     */
    static final int MAX_BROADCAST_HISTORY = 100;
    final BroadcastRecord[] mBroadcastHistory
            = new BroadcastRecord[MAX_BROADCAST_HISTORY];

    /**
     * Set when we current have a BROADCAST_INTENT_MSG in flight.
     */
    boolean mBroadcastsScheduled = false;

    /**
     * Set to indicate whether to issue an onUserLeaving callback when a
     * newly launched activity is being brought in front of us.
     */
    boolean mUserLeaving = false;

    /**
     * When we are in the process of pausing an activity, before starting the
     * next one, this variable holds the activity that is currently being paused.
     */
    HistoryRecord mPausingActivity = null;

    /**
     * Current activity that is resumed, or null if there is none.
     */
    HistoryRecord mResumedActivity = null;

    /**
     * Activity we have told the window manager to have key focus.
     */
    HistoryRecord mFocusedActivity = null;

    /**
     * This is the last activity that we put into the paused state.  This is
     * used to determine if we need to do an activity transition while sleeping,
     * when we normally hold the top activity paused.
     */
    HistoryRecord mLastPausedActivity = null;

    /**
     * List of activities that are waiting for a new activity
     * to become visible before completing whatever operation they are
     * supposed to do.
     */
    final ArrayList mWaitingVisibleActivities = new ArrayList();

    /**
     * List of activities that are ready to be stopped, but waiting
     * for the next activity to settle down before doing so.  It contains
     * HistoryRecord objects.
     */
    final ArrayList<HistoryRecord> mStoppingActivities
            = new ArrayList<HistoryRecord>();

    /**
     * Animations that for the current transition have requested not to
     * be considered for the transition animation.
     */
    final ArrayList<HistoryRecord> mNoAnimActivities
            = new ArrayList<HistoryRecord>();
    
    /**
     * List of intents that were used to start the most recent tasks.
     */
    final ArrayList<TaskRecord> mRecentTasks
            = new ArrayList<TaskRecord>();

    /**
     * List of activities that are ready to be finished, but waiting
     * for the previous activity to settle down before doing so.  It contains
     * HistoryRecord objects.
     */
    final ArrayList mFinishingActivities = new ArrayList();

    /**
     * All of the applications we currently have running organized by name.
     * The keys are strings of the application package name (as
     * returned by the package manager), and the keys are ApplicationRecord
     * objects.
     */
    final ProcessMap<ProcessRecord> mProcessNames
            = new ProcessMap<ProcessRecord>();

    /**
     * The last time that various processes have crashed.
     */
    final ProcessMap<Long> mProcessCrashTimes = new ProcessMap<Long>();

    /**
     * Set of applications that we consider to be bad, and will reject
     * incoming broadcasts from (which the user has no control over).
     * Processes are added to this set when they have crashed twice within
     * a minimum amount of time; they are removed from it when they are
     * later restarted (hopefully due to some user action).  The value is the
     * time it was added to the list.
     */
    final ProcessMap<Long> mBadProcesses = new ProcessMap<Long>();

    /**
     * All of the processes we currently have running organized by pid.
     * The keys are the pid running the application.
     *
     * <p>NOTE: This object is protected by its own lock, NOT the global
     * activity manager lock!
     */
    final SparseArray<ProcessRecord> mPidsSelfLocked
            = new SparseArray<ProcessRecord>();

    /**
     * All of the processes that have been forced to be foreground.  The key
     * is the pid of the caller who requested it (we hold a death
     * link on it).
     */
    abstract class ForegroundToken implements IBinder.DeathRecipient {
        int pid;
        IBinder token;
    }
    final SparseArray<ForegroundToken> mForegroundProcesses
            = new SparseArray<ForegroundToken>();
    
    /**
     * List of records for processes that someone had tried to start before the
     * system was ready.  We don't start them at that point, but ensure they
     * are started by the time booting is complete.
     */
    final ArrayList<ProcessRecord> mProcessesOnHold
            = new ArrayList<ProcessRecord>();

    /**
     * List of records for processes that we have started and are waiting
     * for them to call back.  This is really only needed when running in
     * single processes mode, in which case we do not have a unique pid for
     * each process.
     */
    final ArrayList<ProcessRecord> mStartingProcesses
            = new ArrayList<ProcessRecord>();

    /**
     * List of persistent applications that are in the process
     * of being started.
     */
    final ArrayList<ProcessRecord> mPersistentStartingProcesses
            = new ArrayList<ProcessRecord>();

    /**
     * Processes that are being forcibly torn down.
     */
    final ArrayList<ProcessRecord> mRemovedProcesses
            = new ArrayList<ProcessRecord>();

    /**
     * List of running applications, sorted by recent usage.
     * The first entry in the list is the least recently used.
     * It contains ApplicationRecord objects.  This list does NOT include
     * any persistent application records (since we never want to exit them).
     */
    final ArrayList<ProcessRecord> mLruProcesses
            = new ArrayList<ProcessRecord>();

    /**
     * List of processes that should gc as soon as things are idle.
     */
    final ArrayList<ProcessRecord> mProcessesToGc
            = new ArrayList<ProcessRecord>();

    /**
     * This is the process holding what we currently consider to be
     * the "home" activity.
     */
    private ProcessRecord mHomeProcess;
    
    /**
     * List of running activities, sorted by recent usage.
     * The first entry in the list is the least recently used.
     * It contains HistoryRecord objects.
     */
    private final ArrayList mLRUActivities = new ArrayList();

    /**
     * Set of PendingResultRecord objects that are currently active.
     */
    final HashSet mPendingResultRecords = new HashSet();

    /**
     * Set of IntentSenderRecord objects that are currently active.
     */
    final HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>> mIntentSenderRecords
            = new HashMap<PendingIntentRecord.Key, WeakReference<PendingIntentRecord>>();

    /**
     * Intent broadcast that we have tried to start, but are
     * waiting for its application's process to be created.  We only
     * need one (instead of a list) because we always process broadcasts
     * one at a time, so no others can be started while waiting for this
     * one.
     */
    BroadcastRecord mPendingBroadcast = null;

    /**
     * Keeps track of all IIntentReceivers that have been registered for
     * broadcasts.  Hash keys are the receiver IBinder, hash value is
     * a ReceiverList.
     */
    final HashMap mRegisteredReceivers = new HashMap();

    /**
     * Resolver for broadcast intents to registered receivers.
     * Holds BroadcastFilter (subclass of IntentFilter).
     */
    final IntentResolver<BroadcastFilter, BroadcastFilter> mReceiverResolver
            = new IntentResolver<BroadcastFilter, BroadcastFilter>() {
        @Override
        protected boolean allowFilterResult(
                BroadcastFilter filter, List<BroadcastFilter> dest) {
            IBinder target = filter.receiverList.receiver.asBinder();
            for (int i=dest.size()-1; i>=0; i--) {
                if (dest.get(i).receiverList.receiver.asBinder() == target) {
                    return false;
                }
            }
            return true;
        }
    };

    /**
     * State of all active sticky broadcasts.  Keys are the action of the
     * sticky Intent, values are an ArrayList of all broadcasted intents with
     * that action (which should usually be one).
     */
    final HashMap<String, ArrayList<Intent>> mStickyBroadcasts =
            new HashMap<String, ArrayList<Intent>>();

    /**
     * All currently running services.
     */
    final HashMap<ComponentName, ServiceRecord> mServices =
        new HashMap<ComponentName, ServiceRecord>();

    /**
     * All currently running services indexed by the Intent used to start them.
     */
    final HashMap<Intent.FilterComparison, ServiceRecord> mServicesByIntent =
        new HashMap<Intent.FilterComparison, ServiceRecord>();

    /**
     * All currently bound service connections.  Keys are the IBinder of
     * the client's IServiceConnection.
     */
    final HashMap<IBinder, ConnectionRecord> mServiceConnections
            = new HashMap<IBinder, ConnectionRecord>();

    /**
     * List of services that we have been asked to start,
     * but haven't yet been able to.  It is used to hold start requests
     * while waiting for their corresponding application thread to get
     * going.
     */
    final ArrayList<ServiceRecord> mPendingServices
            = new ArrayList<ServiceRecord>();

    /**
     * List of services that are scheduled to restart following a crash.
     */
    final ArrayList<ServiceRecord> mRestartingServices
            = new ArrayList<ServiceRecord>();

    /**
     * List of services that are in the process of being stopped.
     */
    final ArrayList<ServiceRecord> mStoppingServices
            = new ArrayList<ServiceRecord>();

    /**
     * Backup/restore process management
     */
    String mBackupAppName = null;
    BackupRecord mBackupTarget = null;

    /**
     * List of PendingThumbnailsRecord objects of clients who are still
     * waiting to receive all of the thumbnails for a task.
     */
    final ArrayList mPendingThumbnails = new ArrayList();

    /**
     * List of HistoryRecord objects that have been finished and must
     * still report back to a pending thumbnail receiver.
     */
    final ArrayList mCancelledThumbnails = new ArrayList();

    /**
     * All of the currently running global content providers.  Keys are a
     * string containing the provider name and values are a
     * ContentProviderRecord object containing the data about it.  Note
     * that a single provider may be published under multiple names, so
     * there may be multiple entries here for a single one in mProvidersByClass.
     */
    final HashMap mProvidersByName = new HashMap();

    /**
     * All of the currently running global content providers.  Keys are a
     * string containing the provider's implementation class and values are a
     * ContentProviderRecord object containing the data about it.
     */
    final HashMap mProvidersByClass = new HashMap();

    /**
     * List of content providers who have clients waiting for them.  The
     * application is currently being launched and the provider will be
     * removed from this list once it is published.
     */
    final ArrayList mLaunchingProviders = new ArrayList();

    /**
     * Global set of specific Uri permissions that have been granted.
     */
    final private SparseArray<HashMap<Uri, UriPermission>> mGrantedUriPermissions
            = new SparseArray<HashMap<Uri, UriPermission>>();

    /**
     * Thread-local storage used to carry caller permissions over through
     * indirect content-provider access.
     * @see #ActivityManagerService.openContentUri()
     */
    private class Identity {
        public int pid;
        public int uid;

        Identity(int _pid, int _uid) {
            pid = _pid;
            uid = _uid;
        }
    }
    private static ThreadLocal<Identity> sCallerIdentity = new ThreadLocal<Identity>();

    /**
     * All information we have collected about the runtime performance of
     * any user id that can impact battery performance.
     */
    final BatteryStatsService mBatteryStatsService;
    
    /**
     * information about component usage
     */
    final UsageStatsService mUsageStatsService;

    /**
     * Current configuration information.  HistoryRecord objects are given
     * a reference to this object to indicate which configuration they are
     * currently running in, so this object must be kept immutable.
     */
    Configuration mConfiguration = new Configuration();

    /**
     * Current sequencing integer of the configuration, for skipping old
     * configurations.
     */
    int mConfigurationSeq = 0;
    
    /**
     * Set when we know we are going to be calling updateConfiguration()
     * soon, so want to skip intermediate config checks.
     */
    boolean mConfigWillChange;
    
    /**
     * Hardware-reported OpenGLES version.
     */
    final int GL_ES_VERSION;

    /**
     * List of initialization arguments to pass to all processes when binding applications to them.
     * For example, references to the commonly used services.
     */
    HashMap<String, IBinder> mAppBindArgs;

    /**
     * Temporary to avoid allocations.  Protected by main lock.
     */
    final StringBuilder mStringBuilder = new StringBuilder(256);
    
    /**
     * Used to control how we initialize the service.
     */
    boolean mStartRunning = false;
    ComponentName mTopComponent;
    String mTopAction;
    String mTopData;
    boolean mSystemReady = false;
    boolean mBooting = false;
    boolean mWaitingUpdate = false;
    boolean mDidUpdate = false;

    Context mContext;

    int mFactoryTest;

    boolean mCheckedForSetup;
    
    /**
     * The time at which we will allow normal application switches again,
     * after a call to {@link #stopAppSwitches()}.
     */
    long mAppSwitchesAllowedTime;

    /**
     * This is set to true after the first switch after mAppSwitchesAllowedTime
     * is set; any switches after that will clear the time.
     */
    boolean mDidAppSwitch;
    
    /**
     * Set while we are wanting to sleep, to prevent any
     * activities from being started/resumed.
     */
    boolean mSleeping = false;

    /**
     * Set if we are shutting down the system, similar to sleeping.
     */
    boolean mShuttingDown = false;
    
    /**
     * Set when the system is going to sleep, until we have
     * successfully paused the current activity and released our wake lock.
     * At that point the system is allowed to actually sleep.
     */
    PowerManager.WakeLock mGoingToSleep;

    /**
     * We don't want to allow the device to go to sleep while in the process
     * of launching an activity.  This is primarily to allow alarm intent
     * receivers to launch an activity and get that to run before the device
     * goes back to sleep.
     */
    PowerManager.WakeLock mLaunchingActivity;

    /**
     * Task identifier that activities are currently being started
     * in.  Incremented each time a new task is created.
     * todo: Replace this with a TokenSpace class that generates non-repeating
     * integers that won't wrap.
     */
    int mCurTask = 1;

    /**
     * Current sequence id for oom_adj computation traversal.
     */
    int mAdjSeq = 0;

    /**
     * Current sequence id for process LRU updating.
     */
    int mLruSeq = 0;
    
    /**
     * Set to true if the ANDROID_SIMPLE_PROCESS_MANAGEMENT envvar
     * is set, indicating the user wants processes started in such a way
     * that they can use ANDROID_PROCESS_WRAPPER and know what will be
     * running in each process (thus no pre-initialized process, etc).
     */
    boolean mSimpleProcessManagement = false;

    /**
     * System monitoring: number of processes that died since the last
     * N procs were started.
     */
    int[] mProcDeaths = new int[20];
    
    /**
     * This is set if we had to do a delayed dexopt of an app before launching
     * it, to increasing the ANR timeouts in that case.
     */
    boolean mDidDexOpt;
    
    String mDebugApp = null;
    boolean mWaitForDebugger = false;
    boolean mDebugTransient = false;
    String mOrigDebugApp = null;
    boolean mOrigWaitForDebugger = false;
    boolean mAlwaysFinishActivities = false;
    IActivityController mController = null;

    final RemoteCallbackList<IActivityWatcher> mWatchers
            = new RemoteCallbackList<IActivityWatcher>();
    
    /**
     * Callback of last caller to {@link #requestPss}.
     */
    Runnable mRequestPssCallback;

    /**
     * Remaining processes for which we are waiting results from the last
     * call to {@link #requestPss}.
     */
    final ArrayList<ProcessRecord> mRequestPssList
            = new ArrayList<ProcessRecord>();
    
    /**
     * Runtime statistics collection thread.  This object's lock is used to
     * protect all related state.
     */
    final Thread mProcessStatsThread;
    
    /**
     * Used to collect process stats when showing not responding dialog.
     * Protected by mProcessStatsThread.
     */
    final ProcessStats mProcessStats = new ProcessStats(
            MONITOR_THREAD_CPU_USAGE);
    final AtomicLong mLastCpuTime = new AtomicLong(0);
    final AtomicBoolean mProcessStatsMutexFree = new AtomicBoolean(true);

    long mLastWriteTime = 0;

    long mInitialStartTime = 0;
    
    /**
     * Set to true after the system has finished booting.
     */
    boolean mBooted = false;

    int mProcessLimit = 0;

    WindowManagerService mWindowManager;

    static ActivityManagerService mSelf;
    static ActivityThread mSystemThread;

    private final class AppDeathRecipient implements IBinder.DeathRecipient {
        final ProcessRecord mApp;
        final int mPid;
        final IApplicationThread mAppThread;

        AppDeathRecipient(ProcessRecord app, int pid,
                IApplicationThread thread) {
            if (localLOGV) Slog.v(
                TAG, "New death recipient " + this
                + " for thread " + thread.asBinder());
            mApp = app;
            mPid = pid;
            mAppThread = thread;
        }

        public void binderDied() {
            if (localLOGV) Slog.v(
                TAG, "Death received in " + this
                + " for thread " + mAppThread.asBinder());
            removeRequestedPss(mApp);
            synchronized(ActivityManagerService.this) {
                appDiedLocked(mApp, mPid, mAppThread);
            }
        }
    }

    static final int SHOW_ERROR_MSG = 1;
    static final int SHOW_NOT_RESPONDING_MSG = 2;
    static final int SHOW_FACTORY_ERROR_MSG = 3;
    static final int UPDATE_CONFIGURATION_MSG = 4;
    static final int GC_BACKGROUND_PROCESSES_MSG = 5;
    static final int WAIT_FOR_DEBUGGER_MSG = 6;
    static final int BROADCAST_INTENT_MSG = 7;
    static final int BROADCAST_TIMEOUT_MSG = 8;
    static final int PAUSE_TIMEOUT_MSG = 9;
    static final int IDLE_TIMEOUT_MSG = 10;
    static final int IDLE_NOW_MSG = 11;
    static final int SERVICE_TIMEOUT_MSG = 12;
    static final int UPDATE_TIME_ZONE = 13;
    static final int SHOW_UID_ERROR_MSG = 14;
    static final int IM_FEELING_LUCKY_MSG = 15;
    static final int LAUNCH_TIMEOUT_MSG = 16;
    static final int DESTROY_TIMEOUT_MSG = 17;
    static final int RESUME_TOP_ACTIVITY_MSG = 19;
    static final int PROC_START_TIMEOUT_MSG = 20;
    static final int DO_PENDING_ACTIVITY_LAUNCHES_MSG = 21;
    static final int KILL_APPLICATION_MSG = 22;
    static final int FINALIZE_PENDING_INTENT_MSG = 23;

    AlertDialog mUidAlert;

    final Handler mHandler = new Handler() {
        //public Handler() {
        //    if (localLOGV) Slog.v(TAG, "Handler started!");
        //}

        public void handleMessage(Message msg) {
            switch (msg.what) {
            case SHOW_ERROR_MSG: {
                HashMap data = (HashMap) msg.obj;
                synchronized (ActivityManagerService.this) {
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    if (proc != null && proc.crashDialog != null) {
                        Slog.e(TAG, "App already has crash dialog: " + proc);
                        return;
                    }
                    AppErrorResult res = (AppErrorResult) data.get("result");
                    if (!mSleeping && !mShuttingDown) {
                        Dialog d = new AppErrorDialog(mContext, res, proc);
                        d.show();
                        proc.crashDialog = d;
                    } else {
                        // The device is asleep, so just pretend that the user
                        // saw a crash dialog and hit "force quit".
                        res.set(0);
                    }
                }
                
                ensureBootCompleted();
            } break;
            case SHOW_NOT_RESPONDING_MSG: {
                synchronized (ActivityManagerService.this) {
                    HashMap data = (HashMap) msg.obj;
                    ProcessRecord proc = (ProcessRecord)data.get("app");
                    if (proc != null && proc.anrDialog != null) {
                        Slog.e(TAG, "App already has anr dialog: " + proc);
                        return;
                    }
                    
                    broadcastIntentLocked(null, null, new Intent("android.intent.action.ANR"),
                            null, null, 0, null, null, null,
                            false, false, MY_PID, Process.SYSTEM_UID);

                    Dialog d = new AppNotRespondingDialog(ActivityManagerService.this,
                            mContext, proc, (HistoryRecord)data.get("activity"));
                    d.show();
                    proc.anrDialog = d;
                }
                
                ensureBootCompleted();
            } break;
            case SHOW_FACTORY_ERROR_MSG: {
                Dialog d = new FactoryErrorDialog(
                    mContext, msg.getData().getCharSequence("msg"));
                d.show();
                ensureBootCompleted();
            } break;
            case UPDATE_CONFIGURATION_MSG: {
                final ContentResolver resolver = mContext.getContentResolver();
                Settings.System.putConfiguration(resolver, (Configuration)msg.obj);
            } break;
            case GC_BACKGROUND_PROCESSES_MSG: {
                synchronized (ActivityManagerService.this) {
                    performAppGcsIfAppropriateLocked();
                }
            } break;
            case WAIT_FOR_DEBUGGER_MSG: {
                synchronized (ActivityManagerService.this) {
                    ProcessRecord app = (ProcessRecord)msg.obj;
                    if (msg.arg1 != 0) {
                        if (!app.waitedForDebugger) {
                            Dialog d = new AppWaitingForDebuggerDialog(
                                    ActivityManagerService.this,
                                    mContext, app);
                            app.waitDialog = d;
                            app.waitedForDebugger = true;
                            d.show();
                        }
                    } else {
                        if (app.waitDialog != null) {
                            app.waitDialog.dismiss();
                            app.waitDialog = null;
                        }
                    }
                }
            } break;
            case BROADCAST_INTENT_MSG: {
                if (DEBUG_BROADCAST) Slog.v(
                        TAG, "Received BROADCAST_INTENT_MSG");
                processNextBroadcast(true);
            } break;
            case BROADCAST_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG);
                    mHandler.sendMessageDelayed(nmsg, BROADCAST_TIMEOUT);
                    return;
                }
                // Only process broadcast timeouts if the system is ready. That way
                // PRE_BOOT_COMPLETED broadcasts can't timeout as they are intended
                // to do heavy lifting for system up
                if (mSystemReady) {
                    broadcastTimeout();
                }
            } break;
            case PAUSE_TIMEOUT_MSG: {
                IBinder token = (IBinder)msg.obj;
                // We don't at this point know if the activity is fullscreen,
                // so we need to be conservative and assume it isn't.
                Slog.w(TAG, "Activity pause timeout for " + token);
                activityPaused(token, null, true);
            } break;
            case IDLE_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG);
                    nmsg.obj = msg.obj;
                    mHandler.sendMessageDelayed(nmsg, IDLE_TIMEOUT);
                    return;
                }
                // We don't at this point know if the activity is fullscreen,
                // so we need to be conservative and assume it isn't.
                IBinder token = (IBinder)msg.obj;
                Slog.w(TAG, "Activity idle timeout for " + token);
                activityIdleInternal(token, true, null);
            } break;
            case DESTROY_TIMEOUT_MSG: {
                IBinder token = (IBinder)msg.obj;
                // We don't at this point know if the activity is fullscreen,
                // so we need to be conservative and assume it isn't.
                Slog.w(TAG, "Activity destroy timeout for " + token);
                activityDestroyed(token);
            } break;
            case IDLE_NOW_MSG: {
                IBinder token = (IBinder)msg.obj;
                activityIdle(token, null);
            } break;
            case SERVICE_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                    nmsg.obj = msg.obj;
                    mHandler.sendMessageDelayed(nmsg, SERVICE_TIMEOUT);
                    return;
                }
                serviceTimeout((ProcessRecord)msg.obj);
            } break;
            case UPDATE_TIME_ZONE: {
                synchronized (ActivityManagerService.this) {
                    for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                        ProcessRecord r = mLruProcesses.get(i);
                        if (r.thread != null) {
                            try {
                                r.thread.updateTimeZone();
                            } catch (RemoteException ex) {
                                Slog.w(TAG, "Failed to update time zone for: " + r.info.processName);
                            }
                        }
                    }
                }
            } break;
            case SHOW_UID_ERROR_MSG: {
                // XXX This is a temporary dialog, no need to localize.
                AlertDialog d = new BaseErrorDialog(mContext);
                d.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
                d.setCancelable(false);
                d.setTitle("System UIDs Inconsistent");
                d.setMessage("UIDs on the system are inconsistent, you need to wipe your data partition or your device will be unstable.");
                d.setButton("I'm Feeling Lucky",
                        mHandler.obtainMessage(IM_FEELING_LUCKY_MSG));
                mUidAlert = d;
                d.show();
            } break;
            case IM_FEELING_LUCKY_MSG: {
                if (mUidAlert != null) {
                    mUidAlert.dismiss();
                    mUidAlert = null;
                }
            } break;
            case LAUNCH_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(LAUNCH_TIMEOUT_MSG);
                    mHandler.sendMessageDelayed(nmsg, LAUNCH_TIMEOUT);
                    return;
                }
                synchronized (ActivityManagerService.this) {
                    if (mLaunchingActivity.isHeld()) {
                        Slog.w(TAG, "Launch timeout has expired, giving up wake lock!");
                        mLaunchingActivity.release();
                    }
                }
            } break;
            case RESUME_TOP_ACTIVITY_MSG: {
                synchronized (ActivityManagerService.this) {
                    resumeTopActivityLocked(null);
                }
            } break;
            case PROC_START_TIMEOUT_MSG: {
                if (mDidDexOpt) {
                    mDidDexOpt = false;
                    Message nmsg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                    nmsg.obj = msg.obj;
                    mHandler.sendMessageDelayed(nmsg, PROC_START_TIMEOUT);
                    return;
                }
                ProcessRecord app = (ProcessRecord)msg.obj;
                synchronized (ActivityManagerService.this) {
                    processStartTimedOutLocked(app);
                }
            } break;
            case DO_PENDING_ACTIVITY_LAUNCHES_MSG: {
                synchronized (ActivityManagerService.this) {
                    doPendingActivityLaunchesLocked(true);
                }
            } break;
            case KILL_APPLICATION_MSG: {
                synchronized (ActivityManagerService.this) {
                    int uid = msg.arg1;
                    boolean restart = (msg.arg2 == 1);
                    String pkg = (String) msg.obj;
                    forceStopPackageLocked(pkg, uid, restart, false, true);
                }
            } break;
            case FINALIZE_PENDING_INTENT_MSG: {
                ((PendingIntentRecord)msg.obj).completeFinalize();
            } break;
            }
        }
    };

    public static void setSystemProcess() {
        try {
            ActivityManagerService m = mSelf;
            
            ServiceManager.addService("activity", m);
            ServiceManager.addService("meminfo", new MemBinder(m));
            if (MONITOR_CPU_USAGE) {
                ServiceManager.addService("cpuinfo", new CpuBinder(m));
            }
            ServiceManager.addService("permission", new PermissionController(m));

            ApplicationInfo info =
                mSelf.mContext.getPackageManager().getApplicationInfo(
                        "android", STOCK_PM_FLAGS);
            mSystemThread.installSystemApplicationInfo(info);
       
            synchronized (mSelf) {
                ProcessRecord app = mSelf.newProcessRecordLocked(
                        mSystemThread.getApplicationThread(), info,
                        info.processName);
                app.persistent = true;
                app.pid = MY_PID;
                app.maxAdj = SYSTEM_ADJ;
                mSelf.mProcessNames.put(app.processName, app.info.uid, app);
                synchronized (mSelf.mPidsSelfLocked) {
                    mSelf.mPidsSelfLocked.put(app.pid, app);
                }
                mSelf.updateLruProcessLocked(app, true, true);
            }
        } catch (PackageManager.NameNotFoundException e) {
            throw new RuntimeException(
                    "Unable to find android system package", e);
        }
    }

    public void setWindowManager(WindowManagerService wm) {
        mWindowManager = wm;
    }

    public static final Context main(int factoryTest) {
        AThread thr = new AThread();
        thr.start();

        synchronized (thr) {
            while (thr.mService == null) {
                try {
                    thr.wait();
                } catch (InterruptedException e) {
                }
            }
        }

        ActivityManagerService m = thr.mService;
        mSelf = m;
        ActivityThread at = ActivityThread.systemMain();
        mSystemThread = at;
        Context context = at.getSystemContext();
        m.mContext = context;
        m.mFactoryTest = factoryTest;
        PowerManager pm =
            (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        m.mGoingToSleep = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Sleep");
        m.mLaunchingActivity = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ActivityManager-Launch");
        m.mLaunchingActivity.setReferenceCounted(false);
        
        m.mBatteryStatsService.publish(context);
        m.mUsageStatsService.publish(context);
        
        synchronized (thr) {
            thr.mReady = true;
            thr.notifyAll();
        }

        m.startRunning(null, null, null, null);
        
        return context;
    }

    public static ActivityManagerService self() {
        return mSelf;
    }
    
    static class AThread extends Thread {
        ActivityManagerService mService;
        boolean mReady = false;

        public AThread() {
            super("ActivityManager");
        }

        public void run() {
            Looper.prepare();

            android.os.Process.setThreadPriority(
                    android.os.Process.THREAD_PRIORITY_FOREGROUND);

            ActivityManagerService m = new ActivityManagerService();

            synchronized (this) {
                mService = m;
                notifyAll();
            }

            synchronized (this) {
                while (!mReady) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                    }
                }
            }

            Looper.loop();
        }
    }

    static class MemBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        MemBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            ActivityManagerService service = mActivityManagerService;
            ArrayList<ProcessRecord> procs;
            synchronized (mActivityManagerService) {
                if (args != null && args.length > 0
                        && args[0].charAt(0) != '-') {
                    procs = new ArrayList<ProcessRecord>();
                    int pid = -1;
                    try {
                        pid = Integer.parseInt(args[0]);
                    } catch (NumberFormatException e) {
                        
                    }
                    for (int i=service.mLruProcesses.size()-1; i>=0; i--) {
                        ProcessRecord proc = service.mLruProcesses.get(i);
                        if (proc.pid == pid) {
                            procs.add(proc);
                        } else if (proc.processName.equals(args[0])) {
                            procs.add(proc);
                        }
                    }
                    if (procs.size() <= 0) {
                        pw.println("No process found for: " + args[0]);
                        return;
                    }
                } else {
                    procs = new ArrayList<ProcessRecord>(service.mLruProcesses);
                }
            }
            dumpApplicationMemoryUsage(fd, pw, procs, "  ", args);
        }
    }

    static class CpuBinder extends Binder {
        ActivityManagerService mActivityManagerService;
        CpuBinder(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        @Override
        protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
            synchronized (mActivityManagerService.mProcessStatsThread) {
                pw.print(mActivityManagerService.mProcessStats.printCurrentState());
            }
        }
    }

    private ActivityManagerService() {
        String v = System.getenv("ANDROID_SIMPLE_PROCESS_MANAGEMENT");
        if (v != null && Integer.getInteger(v) != 0) {
            mSimpleProcessManagement = true;
        }
        v = System.getenv("ANDROID_DEBUG_APP");
        if (v != null) {
            mSimpleProcessManagement = true;
        }

        Slog.i(TAG, "Memory class: " + ActivityManager.staticGetMemoryClass());
        
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        systemDir.mkdirs();
        mBatteryStatsService = new BatteryStatsService(new File(
                systemDir, "batterystats.bin").toString());
        mBatteryStatsService.getActiveStatistics().readLocked();
        mBatteryStatsService.getActiveStatistics().writeLocked();
        
        mUsageStatsService = new UsageStatsService( new File(
                systemDir, "usagestats").toString());

        GL_ES_VERSION = SystemProperties.getInt("ro.opengles.version",
            ConfigurationInfo.GL_ES_VERSION_UNDEFINED);

        mConfiguration.setToDefaults();
        mConfiguration.locale = Locale.getDefault();
        mProcessStats.init();
        
        // Add ourself to the Watchdog monitors.
        Watchdog.getInstance().addMonitor(this);

        mProcessStatsThread = new Thread("ProcessStats") {
            public void run() {
                while (true) {
                    try {
                        try {
                            synchronized(this) {
                                final long now = SystemClock.uptimeMillis();
                                long nextCpuDelay = (mLastCpuTime.get()+MONITOR_CPU_MAX_TIME)-now;
                                long nextWriteDelay = (mLastWriteTime+BATTERY_STATS_TIME)-now;
                                //Slog.i(TAG, "Cpu delay=" + nextCpuDelay
                                //        + ", write delay=" + nextWriteDelay);
                                if (nextWriteDelay < nextCpuDelay) {
                                    nextCpuDelay = nextWriteDelay;
                                }
                                if (nextCpuDelay > 0) {
                                    mProcessStatsMutexFree.set(true);
                                    this.wait(nextCpuDelay);
                                }
                            }
                        } catch (InterruptedException e) {
                        }
                        updateCpuStatsNow();
                    } catch (Exception e) {
                        Slog.e(TAG, "Unexpected exception collecting process stats", e);
                    }
                }
            }
        };
        mProcessStatsThread.start();
    }

    @Override
    public boolean onTransact(int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try {
            return super.onTransact(code, data, reply, flags);
        } catch (RuntimeException e) {
            // The activity manager only throws security exceptions, so let's
            // log all others.
            if (!(e instanceof SecurityException)) {
                Slog.e(TAG, "Activity Manager Crash", e);
            }
            throw e;
        }
    }

    void updateCpuStats() {
        final long now = SystemClock.uptimeMillis();
        if (mLastCpuTime.get() >= now - MONITOR_CPU_MIN_TIME) {
            return;
        }
        if (mProcessStatsMutexFree.compareAndSet(true, false)) {
            synchronized (mProcessStatsThread) {
                mProcessStatsThread.notify();
            }
        }
    }

    void updateCpuStatsNow() {
        synchronized (mProcessStatsThread) {
            mProcessStatsMutexFree.set(false);
            final long now = SystemClock.uptimeMillis();
            boolean haveNewCpuStats = false;

            if (MONITOR_CPU_USAGE &&
                    mLastCpuTime.get() < (now-MONITOR_CPU_MIN_TIME)) {
                mLastCpuTime.set(now);
                haveNewCpuStats = true;
                mProcessStats.update();
                //Slog.i(TAG, mProcessStats.printCurrentState());
                //Slog.i(TAG, "Total CPU usage: "
                //        + mProcessStats.getTotalCpuPercent() + "%");

                // Slog the cpu usage if the property is set.
                if ("true".equals(SystemProperties.get("events.cpu"))) {
                    int user = mProcessStats.getLastUserTime();
                    int system = mProcessStats.getLastSystemTime();
                    int iowait = mProcessStats.getLastIoWaitTime();
                    int irq = mProcessStats.getLastIrqTime();
                    int softIrq = mProcessStats.getLastSoftIrqTime();
                    int idle = mProcessStats.getLastIdleTime();

                    int total = user + system + iowait + irq + softIrq + idle;
                    if (total == 0) total = 1;

                    EventLog.writeEvent(EventLogTags.CPU,
                            ((user+system+iowait+irq+softIrq) * 100) / total,
                            (user * 100) / total,
                            (system * 100) / total,
                            (iowait * 100) / total,
                            (irq * 100) / total,
                            (softIrq * 100) / total);
                }
            }
            
            long[] cpuSpeedTimes = mProcessStats.getLastCpuSpeedTimes();
            final BatteryStatsImpl bstats = mBatteryStatsService.getActiveStatistics();
            synchronized(bstats) {
                synchronized(mPidsSelfLocked) {
                    if (haveNewCpuStats) {
                        if (mBatteryStatsService.isOnBattery()) {
                            final int N = mProcessStats.countWorkingStats();
                            for (int i=0; i<N; i++) {
                                ProcessStats.Stats st
                                        = mProcessStats.getWorkingStats(i);
                                ProcessRecord pr = mPidsSelfLocked.get(st.pid);
                                if (pr != null) {
                                    BatteryStatsImpl.Uid.Proc ps = pr.batteryStats;
                                    ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                    ps.addSpeedStepTimes(cpuSpeedTimes);
                                } else {
                                    BatteryStatsImpl.Uid.Proc ps =
                                            bstats.getProcessStatsLocked(st.name, st.pid);
                                    if (ps != null) {
                                        ps.addCpuTimeLocked(st.rel_utime, st.rel_stime);
                                        ps.addSpeedStepTimes(cpuSpeedTimes);
                                    }
                                }
                            }
                        }
                    }
                }

                if (mLastWriteTime < (now-BATTERY_STATS_TIME)) {
                    mLastWriteTime = now;
                    mBatteryStatsService.getActiveStatistics().writeLocked();
                }
            }
        }
    }
    
    /**
     * Initialize the application bind args. These are passed to each
     * process when the bindApplication() IPC is sent to the process. They're
     * lazily setup to make sure the services are running when they're asked for.
     */
    private HashMap<String, IBinder> getCommonServicesLocked() {
        if (mAppBindArgs == null) {
            mAppBindArgs = new HashMap<String, IBinder>();

            // Setup the application init args
            mAppBindArgs.put("package", ServiceManager.getService("package"));
            mAppBindArgs.put("window", ServiceManager.getService("window"));
            mAppBindArgs.put(Context.ALARM_SERVICE,
                    ServiceManager.getService(Context.ALARM_SERVICE));
        }
        return mAppBindArgs;
    }

    private final void setFocusedActivityLocked(HistoryRecord r) {
        if (mFocusedActivity != r) {
            mFocusedActivity = r;
            mWindowManager.setFocusedApp(r, true);
        }
    }

    private final void updateLruProcessInternalLocked(ProcessRecord app,
            boolean oomAdj, boolean updateActivityTime, int bestPos) {
        // put it on the LRU to keep track of when it should be exited.
        int lrui = mLruProcesses.indexOf(app);
        if (lrui >= 0) mLruProcesses.remove(lrui);
        
        int i = mLruProcesses.size()-1;
        int skipTop = 0;
        
        app.lruSeq = mLruSeq;
        
        // compute the new weight for this process.
        if (updateActivityTime) {
            app.lastActivityTime = SystemClock.uptimeMillis();
        }
        if (app.activities.size() > 0) {
            // If this process has activities, we more strongly want to keep
            // it around.
            app.lruWeight = app.lastActivityTime;
        } else if (app.pubProviders.size() > 0) {
            // If this process contains content providers, we want to keep
            // it a little more strongly.
            app.lruWeight = app.lastActivityTime - CONTENT_APP_IDLE_OFFSET;
            // Also don't let it kick out the first few "real" hidden processes.
            skipTop = MIN_HIDDEN_APPS;
        } else {
            // If this process doesn't have activities, we less strongly
            // want to keep it around, and generally want to avoid getting
            // in front of any very recently used activities.
            app.lruWeight = app.lastActivityTime - EMPTY_APP_IDLE_OFFSET;
            // Also don't let it kick out the first few "real" hidden processes.
            skipTop = MIN_HIDDEN_APPS;
        }
        
        while (i >= 0) {
            ProcessRecord p = mLruProcesses.get(i);
            // If this app shouldn't be in front of the first N background
            // apps, then skip over that many that are currently hidden.
            if (skipTop > 0 && p.setAdj >= HIDDEN_APP_MIN_ADJ) {
                skipTop--;
            }
            if (p.lruWeight <= app.lruWeight || i < bestPos) {
                mLruProcesses.add(i+1, app);
                break;
            }
            i--;
        }
        if (i < 0) {
            mLruProcesses.add(0, app);
        }
        
        // If the app is currently using a content provider or service,
        // bump those processes as well.
        if (app.connections.size() > 0) {
            for (ConnectionRecord cr : app.connections) {
                if (cr.binding != null && cr.binding.service != null
                        && cr.binding.service.app != null
                        && cr.binding.service.app.lruSeq != mLruSeq) {
                    updateLruProcessInternalLocked(cr.binding.service.app, oomAdj,
                            updateActivityTime, i+1);
                }
            }
        }
        if (app.conProviders.size() > 0) {
            for (ContentProviderRecord cpr : app.conProviders.keySet()) {
                if (cpr.app != null && cpr.app.lruSeq != mLruSeq) {
                    updateLruProcessInternalLocked(cpr.app, oomAdj,
                            updateActivityTime, i+1);
                }
            }
        }
        
        //Slog.i(TAG, "Putting proc to front: " + app.processName);
        if (oomAdj) {
            updateOomAdjLocked();
        }
    }

    private final void updateLruProcessLocked(ProcessRecord app,
            boolean oomAdj, boolean updateActivityTime) {
        mLruSeq++;
        updateLruProcessInternalLocked(app, oomAdj, updateActivityTime, 0);
    }
    
    private final boolean updateLRUListLocked(HistoryRecord r) {
        final boolean hadit = mLRUActivities.remove(r);
        mLRUActivities.add(r);
        return hadit;
    }

    private final HistoryRecord topRunningActivityLocked(HistoryRecord notTop) {
        int i = mHistory.size()-1;
        while (i >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (!r.finishing && r != notTop) {
                return r;
            }
            i--;
        }
        return null;
    }

    private final HistoryRecord topRunningNonDelayedActivityLocked(HistoryRecord notTop) {
        int i = mHistory.size()-1;
        while (i >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (!r.finishing && !r.delayedResume && r != notTop) {
                return r;
            }
            i--;
        }
        return null;
    }

    /**
     * This is a simplified version of topRunningActivityLocked that provides a number of
     * optional skip-over modes.  It is intended for use with the ActivityController hook only.
     * 
     * @param token If non-null, any history records matching this token will be skipped.
     * @param taskId If non-zero, we'll attempt to skip over records with the same task ID.
     * 
     * @return Returns the HistoryRecord of the next activity on the stack.
     */
    private final HistoryRecord topRunningActivityLocked(IBinder token, int taskId) {
        int i = mHistory.size()-1;
        while (i >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            // Note: the taskId check depends on real taskId fields being non-zero
            if (!r.finishing && (token != r) && (taskId != r.task.taskId)) {
                return r;
            }
            i--;
        }
        return null;
    }

    private final ProcessRecord getProcessRecordLocked(
            String processName, int uid) {
        if (uid == Process.SYSTEM_UID) {
            // The system gets to run in any process.  If there are multiple
            // processes with the same uid, just pick the first (this
            // should never happen).
            SparseArray<ProcessRecord> procs = mProcessNames.getMap().get(
                    processName);
            return procs != null ? procs.valueAt(0) : null;
        }
        ProcessRecord proc = mProcessNames.get(processName, uid);
        return proc;
    }

    private void ensurePackageDexOpt(String packageName) {
        IPackageManager pm = ActivityThread.getPackageManager();
        try {
            if (pm.performDexOpt(packageName)) {
                mDidDexOpt = true;
            }
        } catch (RemoteException e) {
        }
    }
    
    private boolean isNextTransitionForward() {
        int transit = mWindowManager.getPendingAppTransition();
        return transit == WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN
                || transit == WindowManagerPolicy.TRANSIT_TASK_OPEN
                || transit == WindowManagerPolicy.TRANSIT_TASK_TO_FRONT;
    }
    
    private final boolean realStartActivityLocked(HistoryRecord r,
            ProcessRecord app, boolean andResume, boolean checkConfig)
            throws RemoteException {

        r.startFreezingScreenLocked(app, 0);
        mWindowManager.setAppVisibility(r, true);

        // Have the window manager re-evaluate the orientation of
        // the screen based on the new activity order.  Note that
        // as a result of this, it can call back into the activity
        // manager with a new orientation.  We don't care about that,
        // because the activity is not currently running so we are
        // just restarting it anyway.
        if (checkConfig) {
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mConfiguration,
                    r.mayFreezeScreenLocked(app) ? r : null);
            updateConfigurationLocked(config, r);
        }

        r.app = app;

        if (localLOGV) Slog.v(TAG, "Launching: " + r);

        int idx = app.activities.indexOf(r);
        if (idx < 0) {
            app.activities.add(r);
        }
        updateLruProcessLocked(app, true, true);

        try {
            if (app.thread == null) {
                throw new RemoteException();
            }
            List<ResultInfo> results = null;
            List<Intent> newIntents = null;
            if (andResume) {
                results = r.results;
                newIntents = r.newIntents;
            }
            if (DEBUG_SWITCH) Slog.v(TAG, "Launching: " + r
                    + " icicle=" + r.icicle
                    + " with results=" + results + " newIntents=" + newIntents
                    + " andResume=" + andResume);
            if (andResume) {
                EventLog.writeEvent(EventLogTags.AM_RESTART_ACTIVITY,
                        System.identityHashCode(r),
                        r.task.taskId, r.shortComponentName);
            }
            if (r.isHomeActivity) {
                mHomeProcess = app;
            }
            ensurePackageDexOpt(r.intent.getComponent().getPackageName());
            app.thread.scheduleLaunchActivity(new Intent(r.intent), r,
                    System.identityHashCode(r),
                    r.info, r.icicle, results, newIntents, !andResume,
                    isNextTransitionForward());
        } catch (RemoteException e) {
            if (r.launchFailed) {
                // This is the second time we failed -- finish activity
                // and give up.
                Slog.e(TAG, "Second failure launching "
                      + r.intent.getComponent().flattenToShortString()
                      + ", giving up", e);
                appDiedLocked(app, app.pid, app.thread);
                requestFinishActivityLocked(r, Activity.RESULT_CANCELED, null,
                        "2nd-crash");
                return false;
            }

            // This is the first time we failed -- restart process and
            // retry.
            app.activities.remove(r);
            throw e;
        }

        r.launchFailed = false;
        if (updateLRUListLocked(r)) {
            Slog.w(TAG, "Activity " + r
                  + " being launched, but already in LRU list");
        }

        if (andResume) {
            // As part of the process of launching, ActivityThread also performs
            // a resume.
            r.state = ActivityState.RESUMED;
            r.icicle = null;
            r.haveState = false;
            r.stopped = false;
            mResumedActivity = r;
            r.task.touchActiveTime();
            completeResumeLocked(r);
            pauseIfSleepingLocked();                
        } else {
            // This activity is not starting in the resumed state... which
            // should look like we asked it to pause+stop (but remain visible),
            // and it has done so and reported back the current icicle and
            // other state.
            r.state = ActivityState.STOPPED;
            r.stopped = true;
        }

        // Launch the new version setup screen if needed.  We do this -after-
        // launching the initial activity (that is, home), so that it can have
        // a chance to initialize itself while in the background, making the
        // switch back to it faster and look better.
        startSetupActivityLocked();
        
        return true;
    }

    private final void startSpecificActivityLocked(HistoryRecord r,
            boolean andResume, boolean checkConfig) {
        // Is this activity's application already running?
        ProcessRecord app = getProcessRecordLocked(r.processName,
                r.info.applicationInfo.uid);
        
        if (r.startTime == 0) {
            r.startTime = SystemClock.uptimeMillis();
            if (mInitialStartTime == 0) {
                mInitialStartTime = r.startTime;
            }
        } else if (mInitialStartTime == 0) {
            mInitialStartTime = SystemClock.uptimeMillis();
        }
        
        if (app != null && app.thread != null) {
            try {
                realStartActivityLocked(r, app, andResume, checkConfig);
                return;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting activity "
                        + r.intent.getComponent().flattenToShortString(), e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        startProcessLocked(r.processName, r.info.applicationInfo, true, 0,
                "activity", r.intent.getComponent(), false);
    }

    private final ProcessRecord startProcessLocked(String processName,
            ApplicationInfo info, boolean knownToBeDead, int intentFlags,
            String hostingType, ComponentName hostingName, boolean allowWhileBooting) {
        ProcessRecord app = getProcessRecordLocked(processName, info.uid);
        // We don't have to do anything more if:
        // (1) There is an existing application record; and
        // (2) The caller doesn't think it is dead, OR there is no thread
        //     object attached to it so we know it couldn't have crashed; and
        // (3) There is a pid assigned to it, so it is either starting or
        //     already running.
        if (DEBUG_PROCESSES) Slog.v(TAG, "startProcess: name=" + processName
                + " app=" + app + " knownToBeDead=" + knownToBeDead
                + " thread=" + (app != null ? app.thread : null)
                + " pid=" + (app != null ? app.pid : -1));
        if (app != null && app.pid > 0) {
            if (!knownToBeDead || app.thread == null) {
                // We already have the app running, or are waiting for it to
                // come up (we have a pid but not yet its thread), so keep it.
                return app;
            } else {
                // An application record is attached to a previous process,
                // clean it up now.
                handleAppDiedLocked(app, true);
            }
        }

        String hostingNameStr = hostingName != null
                ? hostingName.flattenToShortString() : null;
        
        if ((intentFlags&Intent.FLAG_FROM_BACKGROUND) != 0) {
            // If we are in the background, then check to see if this process
            // is bad.  If so, we will just silently fail.
            if (mBadProcesses.get(info.processName, info.uid) != null) {
                return null;
            }
        } else {
            // When the user is explicitly starting a process, then clear its
            // crash count so that we won't make it bad until they see at
            // least one crash dialog again, and make the process good again
            // if it had been bad.
            mProcessCrashTimes.remove(info.processName, info.uid);
            if (mBadProcesses.get(info.processName, info.uid) != null) {
                EventLog.writeEvent(EventLogTags.AM_PROC_GOOD, info.uid,
                        info.processName);
                mBadProcesses.remove(info.processName, info.uid);
                if (app != null) {
                    app.bad = false;
                }
            }
        }
        
        if (app == null) {
            app = newProcessRecordLocked(null, info, processName);
            mProcessNames.put(processName, info.uid, app);
        } else {
            // If this is a new package in the process, add the package to the list
            app.addPackage(info.packageName);
        }

        // If the system is not ready yet, then hold off on starting this
        // process until it is.
        if (!mSystemReady
                && !isAllowedWhileBooting(info)
                && !allowWhileBooting) {
            if (!mProcessesOnHold.contains(app)) {
                mProcessesOnHold.add(app);
            }
            return app;
        }

        startProcessLocked(app, hostingType, hostingNameStr);
        return (app.pid != 0) ? app : null;
    }

    boolean isAllowedWhileBooting(ApplicationInfo ai) {
        return (ai.flags&ApplicationInfo.FLAG_PERSISTENT) != 0;
    }
    
    private final void startProcessLocked(ProcessRecord app,
            String hostingType, String hostingNameStr) {
        if (app.pid > 0 && app.pid != MY_PID) {
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(app.pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            app.pid = 0;
        }

        mProcessesOnHold.remove(app);

        updateCpuStats();
        
        System.arraycopy(mProcDeaths, 0, mProcDeaths, 1, mProcDeaths.length-1);
        mProcDeaths[0] = 0;
        
        try {
            int uid = app.info.uid;
            int[] gids = null;
            try {
                gids = mContext.getPackageManager().getPackageGids(
                        app.info.packageName);
            } catch (PackageManager.NameNotFoundException e) {
                Slog.w(TAG, "Unable to retrieve gids", e);
            }
            if (mFactoryTest != SystemServer.FACTORY_TEST_OFF) {
                if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL
                        && mTopComponent != null
                        && app.processName.equals(mTopComponent.getPackageName())) {
                    uid = 0;
                }
                if (mFactoryTest == SystemServer.FACTORY_TEST_HIGH_LEVEL
                        && (app.info.flags&ApplicationInfo.FLAG_FACTORY_TEST) != 0) {
                    uid = 0;
                }
            }
            int debugFlags = 0;
            if ((app.info.flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                debugFlags |= Zygote.DEBUG_ENABLE_DEBUGGER;
            }
            // Run the app in safe mode if its manifest requests so or the
            // system is booted in safe mode.
            if ((app.info.flags & ApplicationInfo.FLAG_VM_SAFE_MODE) != 0 ||
                Zygote.systemInSafeMode == true) {
                debugFlags |= Zygote.DEBUG_ENABLE_SAFEMODE;
            }
            if ("1".equals(SystemProperties.get("debug.checkjni"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_CHECKJNI;
            }
            if ("1".equals(SystemProperties.get("debug.assert"))) {
                debugFlags |= Zygote.DEBUG_ENABLE_ASSERT;
            }
            int pid = Process.start("android.app.ActivityThread",
                    mSimpleProcessManagement ? app.processName : null, uid, uid,
                    gids, debugFlags, null);
            BatteryStatsImpl bs = app.batteryStats.getBatteryStats();
            synchronized (bs) {
                if (bs.isOnBattery()) {
                    app.batteryStats.incStartsLocked();
                }
            }
            
            EventLog.writeEvent(EventLogTags.AM_PROC_START, pid, uid,
                    app.processName, hostingType,
                    hostingNameStr != null ? hostingNameStr : "");
            
            if (app.persistent) {
                Watchdog.getInstance().processStarted(app, app.processName, pid);
            }
            
            StringBuilder buf = mStringBuilder;
            buf.setLength(0);
            buf.append("Start proc ");
            buf.append(app.processName);
            buf.append(" for ");
            buf.append(hostingType);
            if (hostingNameStr != null) {
                buf.append(" ");
                buf.append(hostingNameStr);
            }
            buf.append(": pid=");
            buf.append(pid);
            buf.append(" uid=");
            buf.append(uid);
            buf.append(" gids={");
            if (gids != null) {
                for (int gi=0; gi<gids.length; gi++) {
                    if (gi != 0) buf.append(", ");
                    buf.append(gids[gi]);

                }
            }
            buf.append("}");
            Slog.i(TAG, buf.toString());
            if (pid == 0 || pid == MY_PID) {
                // Processes are being emulated with threads.
                app.pid = MY_PID;
                app.removed = false;
                mStartingProcesses.add(app);
            } else if (pid > 0) {
                app.pid = pid;
                app.removed = false;
                synchronized (mPidsSelfLocked) {
                    this.mPidsSelfLocked.put(pid, app);
                    Message msg = mHandler.obtainMessage(PROC_START_TIMEOUT_MSG);
                    msg.obj = app;
                    mHandler.sendMessageDelayed(msg, PROC_START_TIMEOUT);
                }
            } else {
                app.pid = 0;
                RuntimeException e = new RuntimeException(
                        "Failure starting process " + app.processName
                        + ": returned pid=" + pid);
                Slog.e(TAG, e.getMessage(), e);
            }
        } catch (RuntimeException e) {
            // XXX do better error recovery.
            app.pid = 0;
            Slog.e(TAG, "Failure starting process " + app.processName, e);
        }
    }

    private final void startPausingLocked(boolean userLeaving, boolean uiSleeping) {
        if (mPausingActivity != null) {
            RuntimeException e = new RuntimeException();
            Slog.e(TAG, "Trying to pause when pause is already pending for "
                  + mPausingActivity, e);
        }
        HistoryRecord prev = mResumedActivity;
        if (prev == null) {
            RuntimeException e = new RuntimeException();
            Slog.e(TAG, "Trying to pause when nothing is resumed", e);
            resumeTopActivityLocked(null);
            return;
        }
        if (DEBUG_PAUSE) Slog.v(TAG, "Start pausing: " + prev);
        mResumedActivity = null;
        mPausingActivity = prev;
        mLastPausedActivity = prev;
        prev.state = ActivityState.PAUSING;
        prev.task.touchActiveTime();

        updateCpuStats();
        
        if (prev.app != null && prev.app.thread != null) {
            if (DEBUG_PAUSE) Slog.v(TAG, "Enqueueing pending pause: " + prev);
            try {
                EventLog.writeEvent(EventLogTags.AM_PAUSE_ACTIVITY,
                        System.identityHashCode(prev),
                        prev.shortComponentName);
                prev.app.thread.schedulePauseActivity(prev, prev.finishing, userLeaving,
                        prev.configChangeFlags);
                updateUsageStats(prev, false);
            } catch (Exception e) {
                // Ignore exception, if process died other code will cleanup.
                Slog.w(TAG, "Exception thrown during pause", e);
                mPausingActivity = null;
                mLastPausedActivity = null;
            }
        } else {
            mPausingActivity = null;
            mLastPausedActivity = null;
        }

        // If we are not going to sleep, we want to ensure the device is
        // awake until the next activity is started.
        if (!mSleeping && !mShuttingDown) {
            mLaunchingActivity.acquire();
            if (!mHandler.hasMessages(LAUNCH_TIMEOUT_MSG)) {
                // To be safe, don't allow the wake lock to be held for too long.
                Message msg = mHandler.obtainMessage(LAUNCH_TIMEOUT_MSG);
                mHandler.sendMessageDelayed(msg, LAUNCH_TIMEOUT);
            }
        }


        if (mPausingActivity != null) {
            // Have the window manager pause its key dispatching until the new
            // activity has started.  If we're pausing the activity just because
            // the screen is being turned off and the UI is sleeping, don't interrupt
            // key dispatch; the same activity will pick it up again on wakeup.
            if (!uiSleeping) {
                prev.pauseKeyDispatchingLocked();
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG, "Key dispatch not paused for screen off");
            }

            // Schedule a pause timeout in case the app doesn't respond.
            // We don't give it much time because this directly impacts the
            // responsiveness seen by the user.
            Message msg = mHandler.obtainMessage(PAUSE_TIMEOUT_MSG);
            msg.obj = prev;
            mHandler.sendMessageDelayed(msg, PAUSE_TIMEOUT);
            if (DEBUG_PAUSE) Slog.v(TAG, "Waiting for pause to complete...");
        } else {
            // This activity failed to schedule the
            // pause, so just treat it as being paused now.
            if (DEBUG_PAUSE) Slog.v(TAG, "Activity not running, resuming next.");
            resumeTopActivityLocked(null);
        }
    }

    private final void completePauseLocked() {
        HistoryRecord prev = mPausingActivity;
        if (DEBUG_PAUSE) Slog.v(TAG, "Complete pause: " + prev);
        
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_PAUSE) Slog.v(TAG, "Executing finish of activity: " + prev);
                prev = finishCurrentActivityLocked(prev, FINISH_AFTER_VISIBLE);
            } else if (prev.app != null) {
                if (DEBUG_PAUSE) Slog.v(TAG, "Enqueueing pending stop: " + prev);
                if (prev.waitingVisible) {
                    prev.waitingVisible = false;
                    mWaitingVisibleActivities.remove(prev);
                    if (DEBUG_SWITCH || DEBUG_PAUSE) Slog.v(
                            TAG, "Complete pause, no longer waiting: " + prev);
                }
                if (prev.configDestroy) {
                    // The previous is being paused because the configuration
                    // is changing, which means it is actually stopping...
                    // To juggle the fact that we are also starting a new
                    // instance right now, we need to first completely stop
                    // the current instance before starting the new one.
                    if (DEBUG_PAUSE) Slog.v(TAG, "Destroying after pause: " + prev);
                    destroyActivityLocked(prev, true);
                } else {
                    mStoppingActivities.add(prev);
                    if (mStoppingActivities.size() > 3) {
                        // If we already have a few activities waiting to stop,
                        // then give up on things going idle and start clearing
                        // them out.
                        if (DEBUG_PAUSE) Slog.v(TAG, "To many pending stops, forcing idle");
                        Message msg = Message.obtain();
                        msg.what = ActivityManagerService.IDLE_NOW_MSG;
                        mHandler.sendMessage(msg);
                    }
                }
            } else {
                if (DEBUG_PAUSE) Slog.v(TAG, "App died during pause, not stopping: " + prev);
                prev = null;
            }
            mPausingActivity = null;
        }

        if (!mSleeping && !mShuttingDown) {
            resumeTopActivityLocked(prev);
        } else {
            if (mGoingToSleep.isHeld()) {
                mGoingToSleep.release();
            }
            if (mShuttingDown) {
                notifyAll();
            }
        }
        
        if (prev != null) {
            prev.resumeKeyDispatchingLocked();
        }

        if (prev.app != null && prev.cpuTimeAtResume > 0 && mBatteryStatsService.isOnBattery()) {
            long diff = 0;
            synchronized (mProcessStatsThread) {
                diff = mProcessStats.getCpuTimeForPid(prev.app.pid) - prev.cpuTimeAtResume;
            }
            if (diff > 0) {
                BatteryStatsImpl bsi = mBatteryStatsService.getActiveStatistics();
                synchronized (bsi) {
                    BatteryStatsImpl.Uid.Proc ps =
                            bsi.getProcessStatsLocked(prev.info.applicationInfo.uid,
                            prev.info.packageName);
                    if (ps != null) {
                        ps.addForegroundTimeLocked(diff);
                    }
                }
            }
        }
        prev.cpuTimeAtResume = 0; // reset it
    }

    /**
     * Once we know that we have asked an application to put an activity in
     * the resumed state (either by launching it or explicitly telling it),
     * this function updates the rest of our state to match that fact.
     */
    private final void completeResumeLocked(HistoryRecord next) {
        next.idle = false;
        next.results = null;
        next.newIntents = null;

        // schedule an idle timeout in case the app doesn't do it for us.
        Message msg = mHandler.obtainMessage(IDLE_TIMEOUT_MSG);
        msg.obj = next;
        mHandler.sendMessageDelayed(msg, IDLE_TIMEOUT);

        if (false) {
            // The activity was never told to pause, so just keep
            // things going as-is.  To maintain our own state,
            // we need to emulate it coming back and saying it is
            // idle.
            msg = mHandler.obtainMessage(IDLE_NOW_MSG);
            msg.obj = next;
            mHandler.sendMessage(msg);
        }

        reportResumedActivityLocked(next);
        
        next.thumbnail = null;
        setFocusedActivityLocked(next);
        next.resumeKeyDispatchingLocked();
        ensureActivitiesVisibleLocked(null, 0);
        mWindowManager.executeAppTransition();
        mNoAnimActivities.clear();

        // Mark the point when the activity is resuming
        // TODO: To be more accurate, the mark should be before the onCreate,
        //       not after the onResume. But for subsequent starts, onResume is fine.
        if (next.app != null) {
            synchronized (mProcessStatsThread) {
                next.cpuTimeAtResume = mProcessStats.getCpuTimeForPid(next.app.pid);
            }
        } else {
            next.cpuTimeAtResume = 0; // Couldn't get the cpu time of process
        }
    }

    /**
     * Make sure that all activities that need to be visible (that is, they
     * currently can be seen by the user) actually are.
     */
    private final void ensureActivitiesVisibleLocked(HistoryRecord top,
            HistoryRecord starting, String onlyThisProcess, int configChanges) {
        if (DEBUG_VISBILITY) Slog.v(
                TAG, "ensureActivitiesVisible behind " + top
                + " configChanges=0x" + Integer.toHexString(configChanges));

        // If the top activity is not fullscreen, then we need to
        // make sure any activities under it are now visible.
        final int count = mHistory.size();
        int i = count-1;
        while (mHistory.get(i) != top) {
            i--;
        }
        HistoryRecord r;
        boolean behindFullscreen = false;
        for (; i>=0; i--) {
            r = (HistoryRecord)mHistory.get(i);
            if (DEBUG_VISBILITY) Slog.v(
                    TAG, "Make visible? " + r + " finishing=" + r.finishing
                    + " state=" + r.state);
            if (r.finishing) {
                continue;
            }
            
            final boolean doThisProcess = onlyThisProcess == null
                    || onlyThisProcess.equals(r.processName);
            
            // First: if this is not the current activity being started, make
            // sure it matches the current configuration.
            if (r != starting && doThisProcess) {
                ensureActivityConfigurationLocked(r, 0);
            }
            
            if (r.app == null || r.app.thread == null) {
                if (onlyThisProcess == null
                        || onlyThisProcess.equals(r.processName)) {
                    // This activity needs to be visible, but isn't even
                    // running...  get it started, but don't resume it
                    // at this point.
                    if (DEBUG_VISBILITY) Slog.v(
                            TAG, "Start and freeze screen for " + r);
                    if (r != starting) {
                        r.startFreezingScreenLocked(r.app, configChanges);
                    }
                    if (!r.visible) {
                        if (DEBUG_VISBILITY) Slog.v(
                                TAG, "Starting and making visible: " + r);
                        mWindowManager.setAppVisibility(r, true);
                    }
                    if (r != starting) {
                        startSpecificActivityLocked(r, false, false);
                    }
                }

            } else if (r.visible) {
                // If this activity is already visible, then there is nothing
                // else to do here.
                if (DEBUG_VISBILITY) Slog.v(
                        TAG, "Skipping: already visible at " + r);
                r.stopFreezingScreenLocked(false);

            } else if (onlyThisProcess == null) {
                // This activity is not currently visible, but is running.
                // Tell it to become visible.
                r.visible = true;
                if (r.state != ActivityState.RESUMED && r != starting) {
                    // If this activity is paused, tell it
                    // to now show its window.
                    if (DEBUG_VISBILITY) Slog.v(
                            TAG, "Making visible and scheduling visibility: " + r);
                    try {
                        mWindowManager.setAppVisibility(r, true);
                        r.app.thread.scheduleWindowVisibility(r, true);
                        r.stopFreezingScreenLocked(false);
                    } catch (Exception e) {
                        // Just skip on any failure; we'll make it
                        // visible when it next restarts.
                        Slog.w(TAG, "Exception thrown making visibile: "
                                + r.intent.getComponent(), e);
                    }
                }
            }

            // Aggregate current change flags.
            configChanges |= r.configChangeFlags;

            if (r.fullscreen) {
                // At this point, nothing else needs to be shown
                if (DEBUG_VISBILITY) Slog.v(
                        TAG, "Stopping: fullscreen at " + r);
                behindFullscreen = true;
                i--;
                break;
            }
        }

        // Now for any activities that aren't visible to the user, make
        // sure they no longer are keeping the screen frozen.
        while (i >= 0) {
            r = (HistoryRecord)mHistory.get(i);
            if (DEBUG_VISBILITY) Slog.v(
                    TAG, "Make invisible? " + r + " finishing=" + r.finishing
                    + " state=" + r.state
                    + " behindFullscreen=" + behindFullscreen);
            if (!r.finishing) {
                if (behindFullscreen) {
                    if (r.visible) {
                        if (DEBUG_VISBILITY) Slog.v(
                                TAG, "Making invisible: " + r);
                        r.visible = false;
                        try {
                            mWindowManager.setAppVisibility(r, false);
                            if ((r.state == ActivityState.STOPPING
                                    || r.state == ActivityState.STOPPED)
                                    && r.app != null && r.app.thread != null) {
                                if (DEBUG_VISBILITY) Slog.v(
                                        TAG, "Scheduling invisibility: " + r);
                                r.app.thread.scheduleWindowVisibility(r, false);
                            }
                        } catch (Exception e) {
                            // Just skip on any failure; we'll make it
                            // visible when it next restarts.
                            Slog.w(TAG, "Exception thrown making hidden: "
                                    + r.intent.getComponent(), e);
                        }
                    } else {
                        if (DEBUG_VISBILITY) Slog.v(
                                TAG, "Already invisible: " + r);
                    }
                } else if (r.fullscreen) {
                    if (DEBUG_VISBILITY) Slog.v(
                            TAG, "Now behindFullscreen: " + r);
                    behindFullscreen = true;
                }
            }
            i--;
        }
    }

    /**
     * Version of ensureActivitiesVisible that can easily be called anywhere.
     */
    private final void ensureActivitiesVisibleLocked(HistoryRecord starting,
            int configChanges) {
        HistoryRecord r = topRunningActivityLocked(null);
        if (r != null) {
            ensureActivitiesVisibleLocked(r, starting, null, configChanges);
        }
    }
    
    private void updateUsageStats(HistoryRecord resumedComponent, boolean resumed) {
        if (resumed) {
            mUsageStatsService.noteResumeComponent(resumedComponent.realActivity);
        } else {
            mUsageStatsService.notePauseComponent(resumedComponent.realActivity);
        }
    }

    private boolean startHomeActivityLocked() {
        if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL
                && mTopAction == null) {
            // We are running in factory test mode, but unable to find
            // the factory test app, so just sit around displaying the
            // error message and don't try to start anything.
            return false;
        }
        Intent intent = new Intent(
            mTopAction,
            mTopData != null ? Uri.parse(mTopData) : null);
        intent.setComponent(mTopComponent);
        if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
            intent.addCategory(Intent.CATEGORY_HOME);
        }
        ActivityInfo aInfo =
            intent.resolveActivityInfo(mContext.getPackageManager(),
                    STOCK_PM_FLAGS);
        if (aInfo != null) {
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
            // Don't do this if the home app is currently being
            // instrumented.
            ProcessRecord app = getProcessRecordLocked(aInfo.processName,
                    aInfo.applicationInfo.uid);
            if (app == null || app.instrumentationClass == null) {
                intent.setFlags(intent.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivityLocked(null, intent, null, null, 0, aInfo,
                        null, null, 0, 0, 0, false, false);
            }
        }
        
        
        return true;
    }
    
    /**
     * Starts the "new version setup screen" if appropriate.
     */
    private void startSetupActivityLocked() {
        // Only do this once per boot.
        if (mCheckedForSetup) {
            return;
        }
        
        // We will show this screen if the current one is a different
        // version than the last one shown, and we are not running in
        // low-level factory test mode.
        final ContentResolver resolver = mContext.getContentResolver();
        if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL &&
                Settings.Secure.getInt(resolver,
                        Settings.Secure.DEVICE_PROVISIONED, 0) != 0) {
            mCheckedForSetup = true;
            
            // See if we should be showing the platform update setup UI.
            Intent intent = new Intent(Intent.ACTION_UPGRADE_SETUP);
            List<ResolveInfo> ris = mSelf.mContext.getPackageManager()
                    .queryIntentActivities(intent, PackageManager.GET_META_DATA);
            
            // We don't allow third party apps to replace this.
            ResolveInfo ri = null;
            for (int i=0; ris != null && i<ris.size(); i++) {
                if ((ris.get(i).activityInfo.applicationInfo.flags
                        & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    ri = ris.get(i);
                    break;
                }
            }
            
            if (ri != null) {
                String vers = ri.activityInfo.metaData != null
                        ? ri.activityInfo.metaData.getString(Intent.METADATA_SETUP_VERSION)
                        : null;
                if (vers == null && ri.activityInfo.applicationInfo.metaData != null) {
                    vers = ri.activityInfo.applicationInfo.metaData.getString(
                            Intent.METADATA_SETUP_VERSION);
                }
                String lastVers = Settings.Secure.getString(
                        resolver, Settings.Secure.LAST_SETUP_SHOWN);
                if (vers != null && !vers.equals(lastVers)) {
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.setComponent(new ComponentName(
                            ri.activityInfo.packageName, ri.activityInfo.name));
                    startActivityLocked(null, intent, null, null, 0, ri.activityInfo,
                            null, null, 0, 0, 0, false, false);
                }
            }
        }
    }
    
    private void reportResumedActivityLocked(HistoryRecord r) {
        //Slog.i(TAG, "**** REPORT RESUME: " + r);
        
        final int identHash = System.identityHashCode(r);
        updateUsageStats(r, true);
        
        int i = mWatchers.beginBroadcast();
        while (i > 0) {
            i--;
            IActivityWatcher w = mWatchers.getBroadcastItem(i);
            if (w != null) {
                try {
                    w.activityResuming(identHash);
                } catch (RemoteException e) {
                }
            }
        }
        mWatchers.finishBroadcast();
    }
    
    /**
     * Ensure that the top activity in the stack is resumed.
     *
     * @param prev The previously resumed activity, for when in the process
     * of pausing; can be null to call from elsewhere.
     *
     * @return Returns true if something is being resumed, or false if
     * nothing happened.
     */
    private final boolean resumeTopActivityLocked(HistoryRecord prev) {
        // Find the first activity that is not finishing.
        HistoryRecord next = topRunningActivityLocked(null);

        // Remember how we'll process this pause/resume situation, and ensure
        // that the state is reset however we wind up proceeding.
        final boolean userLeaving = mUserLeaving;
        mUserLeaving = false;

        if (next == null) {
            // There are no more activities!  Let's just start up the
            // Launcher...
            return startHomeActivityLocked();
        }

        next.delayedResume = false;
        
        // If the top activity is the resumed one, nothing to do.
        if (mResumedActivity == next && next.state == ActivityState.RESUMED) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            mWindowManager.executeAppTransition();
            mNoAnimActivities.clear();
            return false;
        }

        // If we are sleeping, and there is no resumed activity, and the top
        // activity is paused, well that is the state we want.
        if ((mSleeping || mShuttingDown)
                && mLastPausedActivity == next && next.state == ActivityState.PAUSED) {
            // Make sure we have executed any pending transitions, since there
            // should be nothing left to do at this point.
            mWindowManager.executeAppTransition();
            mNoAnimActivities.clear();
            return false;
        }
        
        // The activity may be waiting for stop, but that is no longer
        // appropriate for it.
        mStoppingActivities.remove(next);
        mWaitingVisibleActivities.remove(next);

        if (DEBUG_SWITCH) Slog.v(TAG, "Resuming " + next);

        // If we are currently pausing an activity, then don't do anything
        // until that is done.
        if (mPausingActivity != null) {
            if (DEBUG_SWITCH) Slog.v(TAG, "Skip resume: pausing=" + mPausingActivity);
            return false;
        }

        // We need to start pausing the current activity so the top one
        // can be resumed...
        if (mResumedActivity != null) {
            if (DEBUG_SWITCH) Slog.v(TAG, "Skip resume: need to start pausing");
            startPausingLocked(userLeaving, false);
            return true;
        }

        if (prev != null && prev != next) {
            if (!prev.waitingVisible && next != null && !next.nowVisible) {
                prev.waitingVisible = true;
                mWaitingVisibleActivities.add(prev);
                if (DEBUG_SWITCH) Slog.v(
                        TAG, "Resuming top, waiting visible to hide: " + prev);
            } else {
                // The next activity is already visible, so hide the previous
                // activity's windows right now so we can show the new one ASAP.
                // We only do this if the previous is finishing, which should mean
                // it is on top of the one being resumed so hiding it quickly
                // is good.  Otherwise, we want to do the normal route of allowing
                // the resumed activity to be shown so we can decide if the
                // previous should actually be hidden depending on whether the
                // new one is found to be full-screen or not.
                if (prev.finishing) {
                    mWindowManager.setAppVisibility(prev, false);
                    if (DEBUG_SWITCH) Slog.v(TAG, "Not waiting for visible to hide: "
                            + prev + ", waitingVisible="
                            + (prev != null ? prev.waitingVisible : null)
                            + ", nowVisible=" + next.nowVisible);
                } else {
                    if (DEBUG_SWITCH) Slog.v(TAG, "Previous already visible but still waiting to hide: "
                        + prev + ", waitingVisible="
                        + (prev != null ? prev.waitingVisible : null)
                        + ", nowVisible=" + next.nowVisible);
                }
            }
        }

        // We are starting up the next activity, so tell the window manager
        // that the previous one will be hidden soon.  This way it can know
        // to ignore it when computing the desired screen orientation.
        if (prev != null) {
            if (prev.finishing) {
                if (DEBUG_TRANSITION) Slog.v(TAG,
                        "Prepare close transition: prev=" + prev);
                if (mNoAnimActivities.contains(prev)) {
                    mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_NONE);
                } else {
                    mWindowManager.prepareAppTransition(prev.task == next.task
                            ? WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE
                            : WindowManagerPolicy.TRANSIT_TASK_CLOSE);
                }
                mWindowManager.setAppWillBeHidden(prev);
                mWindowManager.setAppVisibility(prev, false);
            } else {
                if (DEBUG_TRANSITION) Slog.v(TAG,
                        "Prepare open transition: prev=" + prev);
                if (mNoAnimActivities.contains(next)) {
                    mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_NONE);
                } else {
                    mWindowManager.prepareAppTransition(prev.task == next.task
                            ? WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN
                            : WindowManagerPolicy.TRANSIT_TASK_OPEN);
                }
            }
            if (false) {
                mWindowManager.setAppWillBeHidden(prev);
                mWindowManager.setAppVisibility(prev, false);
            }
        } else if (mHistory.size() > 1) {
            if (DEBUG_TRANSITION) Slog.v(TAG,
                    "Prepare open transition: no previous");
            if (mNoAnimActivities.contains(next)) {
                mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_NONE);
            } else {
                mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN);
            }
        }

        if (next.app != null && next.app.thread != null) {
            if (DEBUG_SWITCH) Slog.v(TAG, "Resume running: " + next);

            // This activity is now becoming visible.
            mWindowManager.setAppVisibility(next, true);

            HistoryRecord lastResumedActivity = mResumedActivity;
            ActivityState lastState = next.state;

            updateCpuStats();
            
            next.state = ActivityState.RESUMED;
            mResumedActivity = next;
            next.task.touchActiveTime();
            updateLruProcessLocked(next.app, true, true);
            updateLRUListLocked(next);

            // Have the window manager re-evaluate the orientation of
            // the screen based on the new activity order.
            boolean updated;
            synchronized (this) {
                Configuration config = mWindowManager.updateOrientationFromAppTokens(
                        mConfiguration,
                        next.mayFreezeScreenLocked(next.app) ? next : null);
                if (config != null) {
                    next.frozenBeforeDestroy = true;
                }
                updated = updateConfigurationLocked(config, next);
            }
            if (!updated) {
                // The configuration update wasn't able to keep the existing
                // instance of the activity, and instead started a new one.
                // We should be all done, but let's just make sure our activity
                // is still at the top and schedule another run if something
                // weird happened.
                HistoryRecord nextNext = topRunningActivityLocked(null);
                if (DEBUG_SWITCH) Slog.i(TAG,
                        "Activity config changed during resume: " + next
                        + ", new next: " + nextNext);
                if (nextNext != next) {
                    // Do over!
                    mHandler.sendEmptyMessage(RESUME_TOP_ACTIVITY_MSG);
                }
                setFocusedActivityLocked(next);
                ensureActivitiesVisibleLocked(null, 0);
                mWindowManager.executeAppTransition();
                mNoAnimActivities.clear();
                return true;
            }
            
            try {
                // Deliver all pending results.
                ArrayList a = next.results;
                if (a != null) {
                    final int N = a.size();
                    if (!next.finishing && N > 0) {
                        if (DEBUG_RESULTS) Slog.v(
                                TAG, "Delivering results to " + next
                                + ": " + a);
                        next.app.thread.scheduleSendResult(next, a);
                    }
                }

                if (next.newIntents != null) {
                    next.app.thread.scheduleNewIntent(next.newIntents, next);
                }

                EventLog.writeEvent(EventLogTags.AM_RESUME_ACTIVITY,
                        System.identityHashCode(next),
                        next.task.taskId, next.shortComponentName);
                
                next.app.thread.scheduleResumeActivity(next,
                        isNextTransitionForward());
                
                pauseIfSleepingLocked();

            } catch (Exception e) {
                // Whoops, need to restart this activity!
                next.state = lastState;
                mResumedActivity = lastResumedActivity;
                Slog.i(TAG, "Restarting because process died: " + next);
                if (!next.hasBeenLaunched) {
                    next.hasBeenLaunched = true;
                } else {
                    if (SHOW_APP_STARTING_ICON) {
                        mWindowManager.setAppStartingWindow(
                                next, next.packageName, next.theme,
                                next.nonLocalizedLabel,
                                next.labelRes, next.icon, null, true);
                    }
                }
                startSpecificActivityLocked(next, true, false);
                return true;
            }

            // From this point on, if something goes wrong there is no way
            // to recover the activity.
            try {
                next.visible = true;
                completeResumeLocked(next);
            } catch (Exception e) {
                // If any exception gets thrown, toss away this
                // activity and try the next one.
                Slog.w(TAG, "Exception thrown during resume of " + next, e);
                requestFinishActivityLocked(next, Activity.RESULT_CANCELED, null,
                        "resume-exception");
                return true;
            }

            // Didn't need to use the icicle, and it is now out of date.
            next.icicle = null;
            next.haveState = false;
            next.stopped = false;

        } else {
            // Whoops, need to restart this activity!
            if (!next.hasBeenLaunched) {
                next.hasBeenLaunched = true;
            } else {
                if (SHOW_APP_STARTING_ICON) {
                    mWindowManager.setAppStartingWindow(
                            next, next.packageName, next.theme,
                            next.nonLocalizedLabel,
                            next.labelRes, next.icon, null, true);
                }
                if (DEBUG_SWITCH) Slog.v(TAG, "Restarting: " + next);
            }
            startSpecificActivityLocked(next, true, true);
        }

        return true;
    }

    private final void startActivityLocked(HistoryRecord r, boolean newTask,
            boolean doResume) {
        final int NH = mHistory.size();

        int addPos = -1;
        
        if (!newTask) {
            // If starting in an existing task, find where that is...
            HistoryRecord next = null;
            boolean startIt = true;
            for (int i = NH-1; i >= 0; i--) {
                HistoryRecord p = (HistoryRecord)mHistory.get(i);
                if (p.finishing) {
                    continue;
                }
                if (p.task == r.task) {
                    // Here it is!  Now, if this is not yet visible to the
                    // user, then just add it without starting; it will
                    // get started when the user navigates back to it.
                    addPos = i+1;
                    if (!startIt) {
                        mHistory.add(addPos, r);
                        r.inHistory = true;
                        r.task.numActivities++;
                        mWindowManager.addAppToken(addPos, r, r.task.taskId,
                                r.info.screenOrientation, r.fullscreen);
                        if (VALIDATE_TOKENS) {
                            mWindowManager.validateAppTokens(mHistory);
                        }
                        return;
                    }
                    break;
                }
                if (p.fullscreen) {
                    startIt = false;
                }
                next = p;
            }
        }

        // Place a new activity at top of stack, so it is next to interact
        // with the user.
        if (addPos < 0) {
            addPos = mHistory.size();
        }
        
        // If we are not placing the new activity frontmost, we do not want
        // to deliver the onUserLeaving callback to the actual frontmost
        // activity
        if (addPos < NH) {
            mUserLeaving = false;
            if (DEBUG_USER_LEAVING) Slog.v(TAG, "startActivity() behind front, mUserLeaving=false");
        }
        
        // Slot the activity into the history stack and proceed
        mHistory.add(addPos, r);
        r.inHistory = true;
        r.frontOfTask = newTask;
        r.task.numActivities++;
        if (NH > 0) {
            // We want to show the starting preview window if we are
            // switching to a new task, or the next activity's process is
            // not currently running.
            boolean showStartingIcon = newTask;
            ProcessRecord proc = r.app;
            if (proc == null) {
                proc = mProcessNames.get(r.processName, r.info.applicationInfo.uid);
            }
            if (proc == null || proc.thread == null) {
                showStartingIcon = true;
            }
            if (DEBUG_TRANSITION) Slog.v(TAG,
                    "Prepare open transition: starting " + r);
            if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
                mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_NONE);
                mNoAnimActivities.add(r);
            } else if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_TASK_OPEN);
                mNoAnimActivities.remove(r);
            } else {
                mWindowManager.prepareAppTransition(newTask
                        ? WindowManagerPolicy.TRANSIT_TASK_OPEN
                        : WindowManagerPolicy.TRANSIT_ACTIVITY_OPEN);
                mNoAnimActivities.remove(r);
            }
            mWindowManager.addAppToken(
                    addPos, r, r.task.taskId, r.info.screenOrientation, r.fullscreen);
            boolean doShow = true;
            if (newTask) {
                // Even though this activity is starting fresh, we still need
                // to reset it to make sure we apply affinities to move any
                // existing activities from other tasks in to it.
                // If the caller has requested that the target task be
                // reset, then do so.
                if ((r.intent.getFlags()
                        &Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                    resetTaskIfNeededLocked(r, r);
                    doShow = topRunningNonDelayedActivityLocked(null) == r;
                }
            }
            if (SHOW_APP_STARTING_ICON && doShow) {
                // Figure out if we are transitioning from another activity that is
                // "has the same starting icon" as the next one.  This allows the
                // window manager to keep the previous window it had previously
                // created, if it still had one.
                HistoryRecord prev = mResumedActivity;
                if (prev != null) {
                    // We don't want to reuse the previous starting preview if:
                    // (1) The current activity is in a different task.
                    if (prev.task != r.task) prev = null;
                    // (2) The current activity is already displayed.
                    else if (prev.nowVisible) prev = null;
                }
                mWindowManager.setAppStartingWindow(
                        r, r.packageName, r.theme, r.nonLocalizedLabel,
                        r.labelRes, r.icon, prev, showStartingIcon);
            }
        } else {
            // If this is the first activity, don't do any fancy animations,
            // because there is nothing for it to animate on top of.
            mWindowManager.addAppToken(addPos, r, r.task.taskId,
                    r.info.screenOrientation, r.fullscreen);
        }
        if (VALIDATE_TOKENS) {
            mWindowManager.validateAppTokens(mHistory);
        }

        if (doResume) {
            resumeTopActivityLocked(null);
        }
    }

    /**
     * Perform clear operation as requested by
     * {@link Intent#FLAG_ACTIVITY_CLEAR_TOP}: search from the top of the
     * stack to the given task, then look for
     * an instance of that activity in the stack and, if found, finish all
     * activities on top of it and return the instance.
     *
     * @param newR Description of the new activity being started.
     * @return Returns the old activity that should be continue to be used,
     * or null if none was found.
     */
    private final HistoryRecord performClearTaskLocked(int taskId,
            HistoryRecord newR, int launchFlags, boolean doClear) {
        int i = mHistory.size();
        
        // First find the requested task.
        while (i > 0) {
            i--;
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (r.task.taskId == taskId) {
                i++;
                break;
            }
        }
        
        // Now clear it.
        while (i > 0) {
            i--;
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (r.finishing) {
                continue;
            }
            if (r.task.taskId != taskId) {
                return null;
            }
            if (r.realActivity.equals(newR.realActivity)) {
                // Here it is!  Now finish everything in front...
                HistoryRecord ret = r;
                if (doClear) {
                    while (i < (mHistory.size()-1)) {
                        i++;
                        r = (HistoryRecord)mHistory.get(i);
                        if (r.finishing) {
                            continue;
                        }
                        if (finishActivityLocked(r, i, Activity.RESULT_CANCELED,
                                null, "clear")) {
                            i--;
                        }
                    }
                }
                
                // Finally, if this is a normal launch mode (that is, not
                // expecting onNewIntent()), then we will finish the current
                // instance of the activity so a new fresh one can be started.
                if (ret.launchMode == ActivityInfo.LAUNCH_MULTIPLE
                        && (launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) == 0) {
                    if (!ret.finishing) {
                        int index = indexOfTokenLocked(ret);
                        if (index >= 0) {
                            finishActivityLocked(ret, index, Activity.RESULT_CANCELED,
                                    null, "clear");
                        }
                        return null;
                    }
                }
                
                return ret;
            }
        }

        return null;
    }

    /**
     * Find the activity in the history stack within the given task.  Returns
     * the index within the history at which it's found, or < 0 if not found.
     */
    private final int findActivityInHistoryLocked(HistoryRecord r, int task) {
        int i = mHistory.size();
        while (i > 0) {
            i--;
            HistoryRecord candidate = (HistoryRecord)mHistory.get(i);
            if (candidate.task.taskId != task) {
                break;
            }
            if (candidate.realActivity.equals(r.realActivity)) {
                return i;
            }
        }

        return -1;
    }

    /**
     * Reorder the history stack so that the activity at the given index is
     * brought to the front.
     */
    private final HistoryRecord moveActivityToFrontLocked(int where) {
        HistoryRecord newTop = (HistoryRecord)mHistory.remove(where);
        int top = mHistory.size();
        HistoryRecord oldTop = (HistoryRecord)mHistory.get(top-1);
        mHistory.add(top, newTop);
        oldTop.frontOfTask = false;
        newTop.frontOfTask = true;
        return newTop;
    }

    /**
     * Deliver a new Intent to an existing activity, so that its onNewIntent()
     * method will be called at the proper time.
     */
    private final void deliverNewIntentLocked(HistoryRecord r, Intent intent) {
        boolean sent = false;
        if (r.state == ActivityState.RESUMED
                && r.app != null && r.app.thread != null) {
            try {
                ArrayList<Intent> ar = new ArrayList<Intent>();
                ar.add(new Intent(intent));
                r.app.thread.scheduleNewIntent(ar, r);
                sent = true;
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending new intent to " + r, e);
            }
        }
        if (!sent) {
            r.addNewIntentLocked(new Intent(intent));
        }
    }

    private final void logStartActivity(int tag, HistoryRecord r,
            TaskRecord task) {
        EventLog.writeEvent(tag,
                System.identityHashCode(r), task.taskId,
                r.shortComponentName, r.intent.getAction(),
                r.intent.getType(), r.intent.getDataString(),
                r.intent.getFlags());
    }

    private final int startActivityLocked(IApplicationThread caller,
            Intent intent, String resolvedType,
            Uri[] grantedUriPermissions,
            int grantedMode, ActivityInfo aInfo, IBinder resultTo,
            String resultWho, int requestCode,
            int callingPid, int callingUid, boolean onlyIfNeeded,
            boolean componentSpecified) {
        Slog.i(TAG, "Starting activity: " + intent);

        HistoryRecord sourceRecord = null;
        HistoryRecord resultRecord = null;
        if (resultTo != null) {
            int index = indexOfTokenLocked(resultTo);
            if (DEBUG_RESULTS) Slog.v(
                TAG, "Sending result to " + resultTo + " (index " + index + ")");
            if (index >= 0) {
                sourceRecord = (HistoryRecord)mHistory.get(index);
                if (requestCode >= 0 && !sourceRecord.finishing) {
                    resultRecord = sourceRecord;
                }
            }
        }

        int launchFlags = intent.getFlags();

        if ((launchFlags&Intent.FLAG_ACTIVITY_FORWARD_RESULT) != 0
                && sourceRecord != null) {
            // Transfer the result target from the source activity to the new
            // one being started, including any failures.
            if (requestCode >= 0) {
                return START_FORWARD_AND_REQUEST_CONFLICT;
            }
            resultRecord = sourceRecord.resultTo;
            resultWho = sourceRecord.resultWho;
            requestCode = sourceRecord.requestCode;
            sourceRecord.resultTo = null;
            if (resultRecord != null) {
                resultRecord.removeResultsLocked(
                    sourceRecord, resultWho, requestCode);
            }
        }

        int err = START_SUCCESS;

        if (intent.getComponent() == null) {
            // We couldn't find a class that can handle the given Intent.
            // That's the end of that!
            err = START_INTENT_NOT_RESOLVED;
        }

        if (err == START_SUCCESS && aInfo == null) {
            // We couldn't find the specific class specified in the Intent.
            // Also the end of the line.
            err = START_CLASS_NOT_FOUND;
        }

        ProcessRecord callerApp = null;
        if (err == START_SUCCESS && caller != null) {
            callerApp = getRecordForAppLocked(caller);
            if (callerApp != null) {
                callingPid = callerApp.pid;
                callingUid = callerApp.info.uid;
            } else {
                Slog.w(TAG, "Unable to find app for caller " + caller
                      + " (pid=" + callingPid + ") when starting: "
                      + intent.toString());
                err = START_PERMISSION_DENIED;
            }
        }

        if (err != START_SUCCESS) {
            if (resultRecord != null) {
                sendActivityResultLocked(-1,
                    resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            return err;
        }

        final int perm = checkComponentPermission(aInfo.permission, callingPid,
                callingUid, aInfo.exported ? -1 : aInfo.applicationInfo.uid);
        if (perm != PackageManager.PERMISSION_GRANTED) {
            if (resultRecord != null) {
                sendActivityResultLocked(-1,
                    resultRecord, resultWho, requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            String msg = "Permission Denial: starting " + intent.toString()
                    + " from " + callerApp + " (pid=" + callingPid
                    + ", uid=" + callingUid + ")"
                    + " requires " + aInfo.permission;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        if (mController != null) {
            boolean abort = false;
            try {
                // The Intent we give to the watcher has the extra data
                // stripped off, since it can contain private information.
                Intent watchIntent = intent.cloneFilter();
                abort = !mController.activityStarting(watchIntent,
                        aInfo.applicationInfo.packageName);
            } catch (RemoteException e) {
                mController = null;
            }

            if (abort) {
                if (resultRecord != null) {
                    sendActivityResultLocked(-1,
                        resultRecord, resultWho, requestCode,
                        Activity.RESULT_CANCELED, null);
                }
                // We pretend to the caller that it was really started, but
                // they will just get a cancel result.
                return START_SUCCESS;
            }
        }

        HistoryRecord r = new HistoryRecord(this, callerApp, callingUid,
                intent, resolvedType, aInfo, mConfiguration,
                resultRecord, resultWho, requestCode, componentSpecified);

        if (mResumedActivity == null
                || mResumedActivity.info.applicationInfo.uid != callingUid) {
            if (!checkAppSwitchAllowedLocked(callingPid, callingUid, "Activity start")) {
                PendingActivityLaunch pal = new PendingActivityLaunch();
                pal.r = r;
                pal.sourceRecord = sourceRecord;
                pal.grantedUriPermissions = grantedUriPermissions;
                pal.grantedMode = grantedMode;
                pal.onlyIfNeeded = onlyIfNeeded;
                mPendingActivityLaunches.add(pal);
                return START_SWITCHES_CANCELED;
            }
        }
        
        if (mDidAppSwitch) {
            // This is the second allowed switch since we stopped switches,
            // so now just generally allow switches.  Use case: user presses
            // home (switches disabled, switch to home, mDidAppSwitch now true);
            // user taps a home icon (coming from home so allowed, we hit here
            // and now allow anyone to switch again).
            mAppSwitchesAllowedTime = 0;
        } else {
            mDidAppSwitch = true;
        }
     
        doPendingActivityLaunchesLocked(false);
        
        return startActivityUncheckedLocked(r, sourceRecord,
                grantedUriPermissions, grantedMode, onlyIfNeeded, true);
    }
  
    private final void doPendingActivityLaunchesLocked(boolean doResume) {
        final int N = mPendingActivityLaunches.size();
        if (N <= 0) {
            return;
        }
        for (int i=0; i<N; i++) {
            PendingActivityLaunch pal = mPendingActivityLaunches.get(i);
            startActivityUncheckedLocked(pal.r, pal.sourceRecord,
                    pal.grantedUriPermissions, pal.grantedMode, pal.onlyIfNeeded,
                    doResume && i == (N-1));
        }
        mPendingActivityLaunches.clear();
    }
    
    private final int startActivityUncheckedLocked(HistoryRecord r,
            HistoryRecord sourceRecord, Uri[] grantedUriPermissions,
            int grantedMode, boolean onlyIfNeeded, boolean doResume) {
        final Intent intent = r.intent;
        final int callingUid = r.launchedFromUid;
        
        int launchFlags = intent.getFlags();
        
        // We'll invoke onUserLeaving before onPause only if the launching
        // activity did not explicitly state that this is an automated launch.
        mUserLeaving = (launchFlags&Intent.FLAG_ACTIVITY_NO_USER_ACTION) == 0;
        if (DEBUG_USER_LEAVING) Slog.v(TAG,
                "startActivity() => mUserLeaving=" + mUserLeaving);
        
        // If the caller has asked not to resume at this point, we make note
        // of this in the record so that we can skip it when trying to find
        // the top running activity.
        if (!doResume) {
            r.delayedResume = true;
        }
        
        HistoryRecord notTop = (launchFlags&Intent.FLAG_ACTIVITY_PREVIOUS_IS_TOP)
                != 0 ? r : null;

        // If the onlyIfNeeded flag is set, then we can do this if the activity
        // being launched is the same as the one making the call...  or, as
        // a special case, if we do not know the caller then we count the
        // current top activity as the caller.
        if (onlyIfNeeded) {
            HistoryRecord checkedCaller = sourceRecord;
            if (checkedCaller == null) {
                checkedCaller = topRunningNonDelayedActivityLocked(notTop);
            }
            if (!checkedCaller.realActivity.equals(r.realActivity)) {
                // Caller is not the same as launcher, so always needed.
                onlyIfNeeded = false;
            }
        }

        if (grantedUriPermissions != null && callingUid > 0) {
            for (int i=0; i<grantedUriPermissions.length; i++) {
                grantUriPermissionLocked(callingUid, r.packageName,
                        grantedUriPermissions[i], grantedMode, r);
            }
        }

        grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                intent, r);

        if (sourceRecord == null) {
            // This activity is not being started from another...  in this
            // case we -always- start a new task.
            if ((launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) == 0) {
                Slog.w(TAG, "startActivity called from non-Activity context; forcing Intent.FLAG_ACTIVITY_NEW_TASK for: "
                      + intent);
                launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
            }
        } else if (sourceRecord.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            // The original activity who is starting us is running as a single
            // instance...  this new activity it is starting must go on its
            // own task.
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        } else if (r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK) {
            // The activity being started is a single instance...  it always
            // gets launched into its own task.
            launchFlags |= Intent.FLAG_ACTIVITY_NEW_TASK;
        }

        if (r.resultTo != null && (launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            // For whatever reason this activity is being launched into a new
            // task...  yet the caller has requested a result back.  Well, that
            // is pretty messed up, so instead immediately send back a cancel
            // and let the new task continue launched as normal without a
            // dependency on its originator.
            Slog.w(TAG, "Activity is launching as a new task, so cancelling activity result.");
            sendActivityResultLocked(-1,
                    r.resultTo, r.resultWho, r.requestCode,
                Activity.RESULT_CANCELED, null);
            r.resultTo = null;
        }

        boolean addingToTask = false;
        if (((launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0 &&
                (launchFlags&Intent.FLAG_ACTIVITY_MULTIPLE_TASK) == 0)
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
                || r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
            // If bring to front is requested, and no result is requested, and
            // we can find a task that was started with this same
            // component, then instead of launching bring that one to the front.
            if (r.resultTo == null) {
                // See if there is a task to bring to the front.  If this is
                // a SINGLE_INSTANCE activity, there can be one and only one
                // instance of it in the history, and it is always in its own
                // unique task, so we do a special search.
                HistoryRecord taskTop = r.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE
                        ? findTaskLocked(intent, r.info)
                        : findActivityLocked(intent, r.info);
                if (taskTop != null) {
                    if (taskTop.task.intent == null) {
                        // This task was started because of movement of
                        // the activity based on affinity...  now that we
                        // are actually launching it, we can assign the
                        // base intent.
                        taskTop.task.setIntent(intent, r.info);
                    }
                    // If the target task is not in the front, then we need
                    // to bring it to the front...  except...  well, with
                    // SINGLE_TASK_LAUNCH it's not entirely clear.  We'd like
                    // to have the same behavior as if a new instance was
                    // being started, which means not bringing it to the front
                    // if the caller is not itself in the front.
                    HistoryRecord curTop = topRunningNonDelayedActivityLocked(notTop);
                    if (curTop.task != taskTop.task) {
                        r.intent.addFlags(Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT);
                        boolean callerAtFront = sourceRecord == null
                                || curTop.task == sourceRecord.task;
                        if (callerAtFront) {
                            // We really do want to push this one into the
                            // user's face, right now.
                            moveTaskToFrontLocked(taskTop.task, r);
                        }
                    }
                    // If the caller has requested that the target task be
                    // reset, then do so.
                    if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) != 0) {
                        taskTop = resetTaskIfNeededLocked(taskTop, r);
                    }
                    if (onlyIfNeeded) {
                        // We don't need to start a new activity, and
                        // the client said not to do anything if that
                        // is the case, so this is it!  And for paranoia, make
                        // sure we have correctly resumed the top activity.
                        if (doResume) {
                            resumeTopActivityLocked(null);
                        }
                        return START_RETURN_INTENT_TO_CALLER;
                    }
                    if ((launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                        // In this situation we want to remove all activities
                        // from the task up to the one being started.  In most
                        // cases this means we are resetting the task to its
                        // initial state.
                        HistoryRecord top = performClearTaskLocked(
                                taskTop.task.taskId, r, launchFlags, true);
                        if (top != null) {
                            if (top.frontOfTask) {
                                // Activity aliases may mean we use different
                                // intents for the top activity, so make sure
                                // the task now has the identity of the new
                                // intent.
                                top.task.setIntent(r.intent, r.info);
                            }
                            logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                            deliverNewIntentLocked(top, r.intent);
                        } else {
                            // A special case: we need to
                            // start the activity because it is not currently
                            // running, and the caller has asked to clear the
                            // current task to have this activity at the top.
                            addingToTask = true;
                            // Now pretend like this activity is being started
                            // by the top of its task, so it is put in the
                            // right place.
                            sourceRecord = taskTop;
                        }
                    } else if (r.realActivity.equals(taskTop.task.realActivity)) {
                        // In this case the top activity on the task is the
                        // same as the one being launched, so we take that
                        // as a request to bring the task to the foreground.
                        // If the top activity in the task is the root
                        // activity, deliver this new intent to it if it
                        // desires.
                        if ((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                                && taskTop.realActivity.equals(r.realActivity)) {
                            logStartActivity(EventLogTags.AM_NEW_INTENT, r, taskTop.task);
                            if (taskTop.frontOfTask) {
                                taskTop.task.setIntent(r.intent, r.info);
                            }
                            deliverNewIntentLocked(taskTop, r.intent);
                        } else if (!r.intent.filterEquals(taskTop.task.intent)) {
                            // In this case we are launching the root activity
                            // of the task, but with a different intent.  We
                            // should start a new instance on top.
                            addingToTask = true;
                            sourceRecord = taskTop;
                        }
                    } else if ((launchFlags&Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED) == 0) {
                        // In this case an activity is being launched in to an
                        // existing task, without resetting that task.  This
                        // is typically the situation of launching an activity
                        // from a notification or shortcut.  We want to place
                        // the new activity on top of the current task.
                        addingToTask = true;
                        sourceRecord = taskTop;
                    } else if (!taskTop.task.rootWasReset) {
                        // In this case we are launching in to an existing task
                        // that has not yet been started from its front door.
                        // The current task has been brought to the front.
                        // Ideally, we'd probably like to place this new task
                        // at the bottom of its stack, but that's a little hard
                        // to do with the current organization of the code so
                        // for now we'll just drop it.
                        taskTop.task.setIntent(r.intent, r.info);
                    }
                    if (!addingToTask) {
                        // We didn't do anything...  but it was needed (a.k.a., client
                        // don't use that intent!)  And for paranoia, make
                        // sure we have correctly resumed the top activity.
                        if (doResume) {
                            resumeTopActivityLocked(null);
                        }
                        return START_TASK_TO_FRONT;
                    }
                }
            }
        }

        //String uri = r.intent.toURI();
        //Intent intent2 = new Intent(uri);
        //Slog.i(TAG, "Given intent: " + r.intent);
        //Slog.i(TAG, "URI is: " + uri);
        //Slog.i(TAG, "To intent: " + intent2);

        if (r.packageName != null) {
            // If the activity being launched is the same as the one currently
            // at the top, then we need to check if it should only be launched
            // once.
            HistoryRecord top = topRunningNonDelayedActivityLocked(notTop);
            if (top != null && r.resultTo == null) {
                if (top.realActivity.equals(r.realActivity)) {
                    if (top.app != null && top.app.thread != null) {
                        if ((launchFlags&Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP
                            || r.launchMode == ActivityInfo.LAUNCH_SINGLE_TASK) {
                            logStartActivity(EventLogTags.AM_NEW_INTENT, top, top.task);
                            // For paranoia, make sure we have correctly
                            // resumed the top activity.
                            if (doResume) {
                                resumeTopActivityLocked(null);
                            }
                            if (onlyIfNeeded) {
                                // We don't need to start a new activity, and
                                // the client said not to do anything if that
                                // is the case, so this is it!
                                return START_RETURN_INTENT_TO_CALLER;
                            }
                            deliverNewIntentLocked(top, r.intent);
                            return START_DELIVERED_TO_TOP;
                        }
                    }
                }
            }

        } else {
            if (r.resultTo != null) {
                sendActivityResultLocked(-1,
                        r.resultTo, r.resultWho, r.requestCode,
                    Activity.RESULT_CANCELED, null);
            }
            return START_CLASS_NOT_FOUND;
        }

        boolean newTask = false;

        // Should this be considered a new task?
        if (r.resultTo == null && !addingToTask
                && (launchFlags&Intent.FLAG_ACTIVITY_NEW_TASK) != 0) {
            // todo: should do better management of integers.
            mCurTask++;
            if (mCurTask <= 0) {
                mCurTask = 1;
            }
            r.task = new TaskRecord(mCurTask, r.info, intent,
                    (r.info.flags&ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0);
            if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r
                    + " in new task " + r.task);
            newTask = true;
            addRecentTaskLocked(r.task);
            
        } else if (sourceRecord != null) {
            if (!addingToTask &&
                    (launchFlags&Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0) {
                // In this case, we are adding the activity to an existing
                // task, but the caller has asked to clear that task if the
                // activity is already running.
                HistoryRecord top = performClearTaskLocked(
                        sourceRecord.task.taskId, r, launchFlags, true);
                if (top != null) {
                    logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                    deliverNewIntentLocked(top, r.intent);
                    // For paranoia, make sure we have correctly
                    // resumed the top activity.
                    if (doResume) {
                        resumeTopActivityLocked(null);
                    }
                    return START_DELIVERED_TO_TOP;
                }
            } else if (!addingToTask &&
                    (launchFlags&Intent.FLAG_ACTIVITY_REORDER_TO_FRONT) != 0) {
                // In this case, we are launching an activity in our own task
                // that may already be running somewhere in the history, and
                // we want to shuffle it to the front of the stack if so.
                int where = findActivityInHistoryLocked(r, sourceRecord.task.taskId);
                if (where >= 0) {
                    HistoryRecord top = moveActivityToFrontLocked(where);
                    logStartActivity(EventLogTags.AM_NEW_INTENT, r, top.task);
                    deliverNewIntentLocked(top, r.intent);
                    if (doResume) {
                        resumeTopActivityLocked(null);
                    }
                    return START_DELIVERED_TO_TOP;
                }
            }
            // An existing activity is starting this new activity, so we want
            // to keep the new one in the same task as the one that is starting
            // it.
            r.task = sourceRecord.task;
            if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r
                    + " in existing task " + r.task);

        } else {
            // This not being started from an existing activity, and not part
            // of a new task...  just put it in the top task, though these days
            // this case should never happen.
            final int N = mHistory.size();
            HistoryRecord prev =
                N > 0 ? (HistoryRecord)mHistory.get(N-1) : null;
            r.task = prev != null
                ? prev.task
                : new TaskRecord(mCurTask, r.info, intent,
                        (r.info.flags&ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0);
            if (DEBUG_TASKS) Slog.v(TAG, "Starting new activity " + r
                    + " in new guessed " + r.task);
        }
        if (newTask) {
            EventLog.writeEvent(EventLogTags.AM_CREATE_TASK, r.task.taskId);
        }
        logStartActivity(EventLogTags.AM_CREATE_ACTIVITY, r, r.task);
        startActivityLocked(r, newTask, doResume);
        return START_SUCCESS;
    }

    void reportActivityLaunchedLocked(boolean timeout, HistoryRecord r,
            long thisTime, long totalTime) {
        for (int i=mWaitingActivityLaunched.size()-1; i>=0; i--) {
            WaitResult w = mWaitingActivityLaunched.get(i);
            w.timeout = timeout;
            if (r != null) {
                w.who = new ComponentName(r.info.packageName, r.info.name);
            }
            w.thisTime = thisTime;
            w.totalTime = totalTime;
        }
        notify();
    }
    
    void reportActivityVisibleLocked(HistoryRecord r) {
        for (int i=mWaitingActivityVisible.size()-1; i>=0; i--) {
            WaitResult w = mWaitingActivityVisible.get(i);
            w.timeout = false;
            if (r != null) {
                w.who = new ComponentName(r.info.packageName, r.info.name);
            }
            w.totalTime = SystemClock.uptimeMillis() - w.thisTime;
            w.thisTime = w.totalTime;
        }
        notify();
    }
    
    private final int startActivityMayWait(IApplicationThread caller,
            Intent intent, String resolvedType, Uri[] grantedUriPermissions,
            int grantedMode, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded,
            boolean debug, WaitResult outResult, Configuration config) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        final boolean componentSpecified = intent.getComponent() != null;
        
        // Don't modify the client's object!
        intent = new Intent(intent);

        // Collect information about the target of the Intent.
        ActivityInfo aInfo;
        try {
            ResolveInfo rInfo =
                ActivityThread.getPackageManager().resolveIntent(
                        intent, resolvedType,
                        PackageManager.MATCH_DEFAULT_ONLY
                        | STOCK_PM_FLAGS);
            aInfo = rInfo != null ? rInfo.activityInfo : null;
        } catch (RemoteException e) {
            aInfo = null;
        }

        if (aInfo != null) {
            // Store the found target back into the intent, because now that
            // we have it we never want to do this again.  For example, if the
            // user navigates back to this point in the history, we should
            // always restart the exact same activity.
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));

            // Don't debug things in the system process
            if (debug) {
                if (!aInfo.processName.equals("system")) {
                    setDebugApp(aInfo.processName, true, false);
                }
            }
        }

        synchronized (this) {
            int callingPid;
            int callingUid;
            if (caller == null) {
                callingPid = Binder.getCallingPid();
                callingUid = Binder.getCallingUid();
            } else {
                callingPid = callingUid = -1;
            }
            
            mConfigWillChange = config != null && mConfiguration.diff(config) != 0;
            if (DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Starting activity when config will change = " + mConfigWillChange);
            
            final long origId = Binder.clearCallingIdentity();
            
            int res = startActivityLocked(caller, intent, resolvedType,
                    grantedUriPermissions, grantedMode, aInfo,
                    resultTo, resultWho, requestCode, callingPid, callingUid,
                    onlyIfNeeded, componentSpecified);
            
            if (mConfigWillChange) {
                // If the caller also wants to switch to a new configuration,
                // do so now.  This allows a clean switch, as we are waiting
                // for the current activity to pause (so we will not destroy
                // it), and have not yet started the next activity.
                enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                        "updateConfiguration()");
                mConfigWillChange = false;
                if (DEBUG_CONFIGURATION) Slog.v(TAG,
                        "Updating to new configuration after starting activity.");
                updateConfigurationLocked(config, null);
            }
            
            Binder.restoreCallingIdentity(origId);
            
            if (outResult != null) {
                outResult.result = res;
                if (res == IActivityManager.START_SUCCESS) {
                    mWaitingActivityLaunched.add(outResult);
                    do {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                        }
                    } while (!outResult.timeout && outResult.who == null);
                } else if (res == IActivityManager.START_TASK_TO_FRONT) {
                    HistoryRecord r = this.topRunningActivityLocked(null);
                    if (r.nowVisible) {
                        outResult.timeout = false;
                        outResult.who = new ComponentName(r.info.packageName, r.info.name);
                        outResult.totalTime = 0;
                        outResult.thisTime = 0;
                    } else {
                        outResult.thisTime = SystemClock.uptimeMillis();
                        mWaitingActivityVisible.add(outResult);
                        do {
                            try {
                                wait();
                            } catch (InterruptedException e) {
                            }
                        } while (!outResult.timeout && outResult.who == null);
                    }
                }
            }
            
            return res;
        }
    }

    public final int startActivity(IApplicationThread caller,
            Intent intent, String resolvedType, Uri[] grantedUriPermissions,
            int grantedMode, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded,
            boolean debug) {
        return startActivityMayWait(caller, intent, resolvedType,
                grantedUriPermissions, grantedMode, resultTo, resultWho,
                requestCode, onlyIfNeeded, debug, null, null);
    }

    public final WaitResult startActivityAndWait(IApplicationThread caller,
            Intent intent, String resolvedType, Uri[] grantedUriPermissions,
            int grantedMode, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded,
            boolean debug) {
        WaitResult res = new WaitResult();
        startActivityMayWait(caller, intent, resolvedType,
                grantedUriPermissions, grantedMode, resultTo, resultWho,
                requestCode, onlyIfNeeded, debug, res, null);
        return res;
    }
    
    public final int startActivityWithConfig(IApplicationThread caller,
            Intent intent, String resolvedType, Uri[] grantedUriPermissions,
            int grantedMode, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded,
            boolean debug, Configuration config) {
        return startActivityMayWait(caller, intent, resolvedType,
                grantedUriPermissions, grantedMode, resultTo, resultWho,
                requestCode, onlyIfNeeded, debug, null, config);
    }

     public int startActivityIntentSender(IApplicationThread caller,
            IntentSender intent, Intent fillInIntent, String resolvedType,
            IBinder resultTo, String resultWho, int requestCode,
            int flagsMask, int flagsValues) {
        // Refuse possible leaked file descriptors
        if (fillInIntent != null && fillInIntent.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }
        
        IIntentSender sender = intent.getTarget();
        if (!(sender instanceof PendingIntentRecord)) {
            throw new IllegalArgumentException("Bad PendingIntent object");
        }
        
        PendingIntentRecord pir = (PendingIntentRecord)sender;
        
        synchronized (this) {
            // If this is coming from the currently resumed activity, it is
            // effectively saying that app switches are allowed at this point.
            if (mResumedActivity != null
                    && mResumedActivity.info.applicationInfo.uid ==
                            Binder.getCallingUid()) {
                mAppSwitchesAllowedTime = 0;
            }
        }
        
        return pir.sendInner(0, fillInIntent, resolvedType,
                null, resultTo, resultWho, requestCode, flagsMask, flagsValues);
    }
    
    public boolean startNextMatchingActivity(IBinder callingActivity,
            Intent intent) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized (this) {
            int index = indexOfTokenLocked(callingActivity);
            if (index < 0) {
                return false;
            }
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            if (r.app == null || r.app.thread == null) {
                // The caller is not running...  d'oh!
                return false;
            }
            intent = new Intent(intent);
            // The caller is not allowed to change the data.
            intent.setDataAndType(r.intent.getData(), r.intent.getType());
            // And we are resetting to find the next component...
            intent.setComponent(null);

            ActivityInfo aInfo = null;
            try {
                List<ResolveInfo> resolves =
                    ActivityThread.getPackageManager().queryIntentActivities(
                            intent, r.resolvedType,
                            PackageManager.MATCH_DEFAULT_ONLY | STOCK_PM_FLAGS);

                // Look for the original activity in the list...
                final int N = resolves != null ? resolves.size() : 0;
                for (int i=0; i<N; i++) {
                    ResolveInfo rInfo = resolves.get(i);
                    if (rInfo.activityInfo.packageName.equals(r.packageName)
                            && rInfo.activityInfo.name.equals(r.info.name)) {
                        // We found the current one...  the next matching is
                        // after it.
                        i++;
                        if (i<N) {
                            aInfo = resolves.get(i).activityInfo;
                        }
                        break;
                    }
                }
            } catch (RemoteException e) {
            }

            if (aInfo == null) {
                // Nobody who is next!
                return false;
            }

            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
            intent.setFlags(intent.getFlags()&~(
                    Intent.FLAG_ACTIVITY_FORWARD_RESULT|
                    Intent.FLAG_ACTIVITY_CLEAR_TOP|
                    Intent.FLAG_ACTIVITY_MULTIPLE_TASK|
                    Intent.FLAG_ACTIVITY_NEW_TASK));

            // Okay now we need to start the new activity, replacing the
            // currently running activity.  This is a little tricky because
            // we want to start the new one as if the current one is finished,
            // but not finish the current one first so that there is no flicker.
            // And thus...
            final boolean wasFinishing = r.finishing;
            r.finishing = true;

            // Propagate reply information over to the new activity.
            final HistoryRecord resultTo = r.resultTo;
            final String resultWho = r.resultWho;
            final int requestCode = r.requestCode;
            r.resultTo = null;
            if (resultTo != null) {
                resultTo.removeResultsLocked(r, resultWho, requestCode);
            }

            final long origId = Binder.clearCallingIdentity();
            // XXX we are not dealing with propagating grantedUriPermissions...
            // those are not yet exposed to user code, so there is no need.
            int res = startActivityLocked(r.app.thread, intent,
                    r.resolvedType, null, 0, aInfo, resultTo, resultWho,
                    requestCode, -1, r.launchedFromUid, false, false);
            Binder.restoreCallingIdentity(origId);

            r.finishing = wasFinishing;
            if (res != START_SUCCESS) {
                return false;
            }
            return true;
        }
    }

    public final int startActivityInPackage(int uid,
            Intent intent, String resolvedType, IBinder resultTo,
            String resultWho, int requestCode, boolean onlyIfNeeded) {
        
        // This is so super not safe, that only the system (or okay root)
        // can do it.
        final int callingUid = Binder.getCallingUid();
        if (callingUid != 0 && callingUid != Process.myUid()) {
            throw new SecurityException(
                    "startActivityInPackage only available to the system");
        }
        
        final boolean componentSpecified = intent.getComponent() != null;
        
        // Don't modify the client's object!
        intent = new Intent(intent);

        // Collect information about the target of the Intent.
        ActivityInfo aInfo;
        try {
            ResolveInfo rInfo =
                ActivityThread.getPackageManager().resolveIntent(
                        intent, resolvedType,
                        PackageManager.MATCH_DEFAULT_ONLY | STOCK_PM_FLAGS);
            aInfo = rInfo != null ? rInfo.activityInfo : null;
        } catch (RemoteException e) {
            aInfo = null;
        }

        if (aInfo != null) {
            // Store the found target back into the intent, because now that
            // we have it we never want to do this again.  For example, if the
            // user navigates back to this point in the history, we should
            // always restart the exact same activity.
            intent.setComponent(new ComponentName(
                    aInfo.applicationInfo.packageName, aInfo.name));
        }

        synchronized(this) {
            return startActivityLocked(null, intent, resolvedType,
                    null, 0, aInfo, resultTo, resultWho, requestCode, -1, uid,
                    onlyIfNeeded, componentSpecified);
        }
    }

    private final void addRecentTaskLocked(TaskRecord task) {
        // Remove any existing entries that are the same kind of task.
        int N = mRecentTasks.size();
        for (int i=0; i<N; i++) {
            TaskRecord tr = mRecentTasks.get(i);
            if ((task.affinity != null && task.affinity.equals(tr.affinity))
                    || (task.intent != null && task.intent.filterEquals(tr.intent))) {
                mRecentTasks.remove(i);
                i--;
                N--;
                if (task.intent == null) {
                    // If the new recent task we are adding is not fully
                    // specified, then replace it with the existing recent task.
                    task = tr;
                }
            }
        }
        if (N >= MAX_RECENT_TASKS) {
            mRecentTasks.remove(N-1);
        }
        mRecentTasks.add(0, task);
    }

    public void setRequestedOrientation(IBinder token,
            int requestedOrientation) {
        synchronized (this) {
            int index = indexOfTokenLocked(token);
            if (index < 0) {
                return;
            }
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            final long origId = Binder.clearCallingIdentity();
            mWindowManager.setAppOrientation(r, requestedOrientation);
            Configuration config = mWindowManager.updateOrientationFromAppTokens(
                    mConfiguration,
                    r.mayFreezeScreenLocked(r.app) ? r : null);
            if (config != null) {
                r.frozenBeforeDestroy = true;
                if (!updateConfigurationLocked(config, r)) {
                    resumeTopActivityLocked(null);
                }
            }
            Binder.restoreCallingIdentity(origId);
        }
    }

    public int getRequestedOrientation(IBinder token) {
        synchronized (this) {
            int index = indexOfTokenLocked(token);
            if (index < 0) {
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            return mWindowManager.getAppOrientation(r);
        }
    }

    private final void stopActivityLocked(HistoryRecord r) {
        if (DEBUG_SWITCH) Slog.d(TAG, "Stopping: " + r);
        if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_HISTORY) != 0
                || (r.info.flags&ActivityInfo.FLAG_NO_HISTORY) != 0) {
            if (!r.finishing) {
                requestFinishActivityLocked(r, Activity.RESULT_CANCELED, null,
                        "no-history");
            }
        } else if (r.app != null && r.app.thread != null) {
            if (mFocusedActivity == r) {
                setFocusedActivityLocked(topRunningActivityLocked(null));
            }
            r.resumeKeyDispatchingLocked();
            try {
                r.stopped = false;
                r.state = ActivityState.STOPPING;
                if (DEBUG_VISBILITY) Slog.v(
                        TAG, "Stopping visible=" + r.visible + " for " + r);
                if (!r.visible) {
                    mWindowManager.setAppVisibility(r, false);
                }
                r.app.thread.scheduleStopActivity(r, r.visible, r.configChangeFlags);
            } catch (Exception e) {
                // Maybe just ignore exceptions here...  if the process
                // has crashed, our death notification will clean things
                // up.
                Slog.w(TAG, "Exception thrown during pause", e);
                // Just in case, assume it to be stopped.
                r.stopped = true;
                r.state = ActivityState.STOPPED;
                if (r.configDestroy) {
                    destroyActivityLocked(r, true);
                }
            }
        }
    }

    /**
     * @return Returns true if the activity is being finished, false if for
     * some reason it is being left as-is.
     */
    private final boolean requestFinishActivityLocked(IBinder token, int resultCode,
            Intent resultData, String reason) {
        if (DEBUG_RESULTS) Slog.v(
            TAG, "Finishing activity: token=" + token
            + ", result=" + resultCode + ", data=" + resultData);

        int index = indexOfTokenLocked(token);
        if (index < 0) {
            return false;
        }
        HistoryRecord r = (HistoryRecord)mHistory.get(index);

        // Is this the last activity left?
        boolean lastActivity = true;
        for (int i=mHistory.size()-1; i>=0; i--) {
            HistoryRecord p = (HistoryRecord)mHistory.get(i);
            if (!p.finishing && p != r) {
                lastActivity = false;
                break;
            }
        }
        
        // If this is the last activity, but it is the home activity, then
        // just don't finish it.
        if (lastActivity) {
            if (r.intent.hasCategory(Intent.CATEGORY_HOME)) {
                return false;
            }
        }
        
        finishActivityLocked(r, index, resultCode, resultData, reason);
        return true;
    }

    /**
     * @return Returns true if this activity has been removed from the history
     * list, or false if it is still in the list and will be removed later.
     */
    private final boolean finishActivityLocked(HistoryRecord r, int index,
            int resultCode, Intent resultData, String reason) {
        if (r.finishing) {
            Slog.w(TAG, "Duplicate finish request for " + r);
            return false;
        }

        r.finishing = true;
        EventLog.writeEvent(EventLogTags.AM_FINISH_ACTIVITY,
                System.identityHashCode(r),
                r.task.taskId, r.shortComponentName, reason);
        r.task.numActivities--;
        if (index < (mHistory.size()-1)) {
            HistoryRecord next = (HistoryRecord)mHistory.get(index+1);
            if (next.task == r.task) {
                if (r.frontOfTask) {
                    // The next activity is now the front of the task.
                    next.frontOfTask = true;
                }
                if ((r.intent.getFlags()&Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0) {
                    // If the caller asked that this activity (and all above it)
                    // be cleared when the task is reset, don't lose that information,
                    // but propagate it up to the next activity.
                    next.intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET);
                }
            }
        }

        r.pauseKeyDispatchingLocked();
        if (mFocusedActivity == r) {
            setFocusedActivityLocked(topRunningActivityLocked(null));
        }

        // send the result
        HistoryRecord resultTo = r.resultTo;
        if (resultTo != null) {
            if (DEBUG_RESULTS) Slog.v(TAG, "Adding result to " + resultTo
                    + " who=" + r.resultWho + " req=" + r.requestCode
                    + " res=" + resultCode + " data=" + resultData);
            if (r.info.applicationInfo.uid > 0) {
                grantUriPermissionFromIntentLocked(r.info.applicationInfo.uid,
                        r.packageName, resultData, r);
            }
            resultTo.addResultLocked(r, r.resultWho, r.requestCode, resultCode,
                                     resultData);
            r.resultTo = null;
        }
        else if (DEBUG_RESULTS) Slog.v(TAG, "No result destination from " + r);

        // Make sure this HistoryRecord is not holding on to other resources,
        // because clients have remote IPC references to this object so we
        // can't assume that will go away and want to avoid circular IPC refs.
        r.results = null;
        r.pendingResults = null;
        r.newIntents = null;
        r.icicle = null;
        
        if (mPendingThumbnails.size() > 0) {
            // There are clients waiting to receive thumbnails so, in case
            // this is an activity that someone is waiting for, add it
            // to the pending list so we can correctly update the clients.
            mCancelledThumbnails.add(r);
        }

        if (mResumedActivity == r) {
            boolean endTask = index <= 0
                    || ((HistoryRecord)mHistory.get(index-1)).task != r.task;
            if (DEBUG_TRANSITION) Slog.v(TAG,
                    "Prepare close transition: finishing " + r);
            mWindowManager.prepareAppTransition(endTask
                    ? WindowManagerPolicy.TRANSIT_TASK_CLOSE
                    : WindowManagerPolicy.TRANSIT_ACTIVITY_CLOSE);
    
            // Tell window manager to prepare for this one to be removed.
            mWindowManager.setAppVisibility(r, false);
                
            if (mPausingActivity == null) {
                if (DEBUG_PAUSE) Slog.v(TAG, "Finish needs to pause: " + r);
                if (DEBUG_USER_LEAVING) Slog.v(TAG, "finish() => pause with userLeaving=false");
                startPausingLocked(false, false);
            }

        } else if (r.state != ActivityState.PAUSING) {
            // If the activity is PAUSING, we will complete the finish once
            // it is done pausing; else we can just directly finish it here.
            if (DEBUG_PAUSE) Slog.v(TAG, "Finish not pausing: " + r);
            return finishCurrentActivityLocked(r, index,
                    FINISH_AFTER_PAUSE) == null;
        } else {
            if (DEBUG_PAUSE) Slog.v(TAG, "Finish waiting for pause of: " + r);
        }

        return false;
    }

    private static final int FINISH_IMMEDIATELY = 0;
    private static final int FINISH_AFTER_PAUSE = 1;
    private static final int FINISH_AFTER_VISIBLE = 2;

    private final HistoryRecord finishCurrentActivityLocked(HistoryRecord r,
            int mode) {
        final int index = indexOfTokenLocked(r);
        if (index < 0) {
            return null;
        }

        return finishCurrentActivityLocked(r, index, mode);
    }

    private final HistoryRecord finishCurrentActivityLocked(HistoryRecord r,
            int index, int mode) {
        // First things first: if this activity is currently visible,
        // and the resumed activity is not yet visible, then hold off on
        // finishing until the resumed one becomes visible.
        if (mode == FINISH_AFTER_VISIBLE && r.nowVisible) {
            if (!mStoppingActivities.contains(r)) {
                mStoppingActivities.add(r);
                if (mStoppingActivities.size() > 3) {
                    // If we already have a few activities waiting to stop,
                    // then give up on things going idle and start clearing
                    // them out.
                    Message msg = Message.obtain();
                    msg.what = ActivityManagerService.IDLE_NOW_MSG;
                    mHandler.sendMessage(msg);
                }
            }
            r.state = ActivityState.STOPPING;
            updateOomAdjLocked();
            return r;
        }

        // make sure the record is cleaned out of other places.
        mStoppingActivities.remove(r);
        mWaitingVisibleActivities.remove(r);
        if (mResumedActivity == r) {
            mResumedActivity = null;
        }
        final ActivityState prevState = r.state;
        r.state = ActivityState.FINISHING;

        if (mode == FINISH_IMMEDIATELY
                || prevState == ActivityState.STOPPED
                || prevState == ActivityState.INITIALIZING) {
            // If this activity is already stopped, we can just finish
            // it right now.
            return destroyActivityLocked(r, true) ? null : r;
        } else {
            // Need to go through the full pause cycle to get this
            // activity into the stopped state and then finish it.
            if (localLOGV) Slog.v(TAG, "Enqueueing pending finish: " + r);
            mFinishingActivities.add(r);
            resumeTopActivityLocked(null);
        }
        return r;
    }

    /**
     * This is the internal entry point for handling Activity.finish().
     * 
     * @param token The Binder token referencing the Activity we want to finish.
     * @param resultCode Result code, if any, from this Activity.
     * @param resultData Result data (Intent), if any, from this Activity.
     * 
     * @return Returns true if the activity successfully finished, or false if it is still running.
     */
    public final boolean finishActivity(IBinder token, int resultCode, Intent resultData) {
        // Refuse possible leaked file descriptors
        if (resultData != null && resultData.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (mController != null) {
                // Find the first activity that is not finishing.
                HistoryRecord next = topRunningActivityLocked(token, 0);
                if (next != null) {
                    // ask watcher if this is allowed
                    boolean resumeOK = true;
                    try {
                        resumeOK = mController.activityResuming(next.packageName);
                    } catch (RemoteException e) {
                        mController = null;
                    }
    
                    if (!resumeOK) {
                        return false;
                    }
                }
            }
            final long origId = Binder.clearCallingIdentity();
            boolean res = requestFinishActivityLocked(token, resultCode,
                    resultData, "app-request");
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    void sendActivityResultLocked(int callingUid, HistoryRecord r,
            String resultWho, int requestCode, int resultCode, Intent data) {

        if (callingUid > 0) {
            grantUriPermissionFromIntentLocked(callingUid, r.packageName,
                    data, r);
        }

        if (DEBUG_RESULTS) Slog.v(TAG, "Send activity result to " + r
                + " : who=" + resultWho + " req=" + requestCode
                + " res=" + resultCode + " data=" + data);
        if (mResumedActivity == r && r.app != null && r.app.thread != null) {
            try {
                ArrayList<ResultInfo> list = new ArrayList<ResultInfo>();
                list.add(new ResultInfo(resultWho, requestCode,
                        resultCode, data));
                r.app.thread.scheduleSendResult(r, list);
                return;
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown sending result to " + r, e);
            }
        }

        r.addResultLocked(null, resultWho, requestCode, resultCode, data);
    }

    public final void finishSubActivity(IBinder token, String resultWho,
            int requestCode) {
        synchronized(this) {
            int index = indexOfTokenLocked(token);
            if (index < 0) {
                return;
            }
            HistoryRecord self = (HistoryRecord)mHistory.get(index);

            final long origId = Binder.clearCallingIdentity();

            int i;
            for (i=mHistory.size()-1; i>=0; i--) {
                HistoryRecord r = (HistoryRecord)mHistory.get(i);
                if (r.resultTo == self && r.requestCode == requestCode) {
                    if ((r.resultWho == null && resultWho == null) ||
                        (r.resultWho != null && r.resultWho.equals(resultWho))) {
                        finishActivityLocked(r, i,
                                Activity.RESULT_CANCELED, null, "request-sub");
                    }
                }
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    public boolean willActivityBeVisible(IBinder token) {
        synchronized(this) {
            int i;
            for (i=mHistory.size()-1; i>=0; i--) {
                HistoryRecord r = (HistoryRecord)mHistory.get(i);
                if (r == token) {
                    return true;
                }
                if (r.fullscreen && !r.finishing) {
                    return false;
                }
            }
            return true;
        }
    }
    
    public void overridePendingTransition(IBinder token, String packageName,
            int enterAnim, int exitAnim) {
        synchronized(this) {
            int index = indexOfTokenLocked(token);
            if (index < 0) {
                return;
            }
            HistoryRecord self = (HistoryRecord)mHistory.get(index);

            final long origId = Binder.clearCallingIdentity();
            
            if (self.state == ActivityState.RESUMED
                    || self.state == ActivityState.PAUSING) {
                mWindowManager.overridePendingAppTransition(packageName,
                        enterAnim, exitAnim);
            }
            
            Binder.restoreCallingIdentity(origId);
        }
    }
    
    /**
     * Perform clean-up of service connections in an activity record.
     */
    private final void cleanUpActivityServicesLocked(HistoryRecord r) {
        // Throw away any services that have been bound by this activity.
        if (r.connections != null) {
            Iterator<ConnectionRecord> it = r.connections.iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                removeConnectionLocked(c, null, r);
            }
            r.connections = null;
        }
    }
    
    /**
     * Perform the common clean-up of an activity record.  This is called both
     * as part of destroyActivityLocked() (when destroying the client-side
     * representation) and cleaning things up as a result of its hosting
     * processing going away, in which case there is no remaining client-side
     * state to destroy so only the cleanup here is needed.
     */
    private final void cleanUpActivityLocked(HistoryRecord r, boolean cleanServices) {
        if (mResumedActivity == r) {
            mResumedActivity = null;
        }
        if (mFocusedActivity == r) {
            mFocusedActivity = null;
        }

        r.configDestroy = false;
        r.frozenBeforeDestroy = false;

        // Make sure this record is no longer in the pending finishes list.
        // This could happen, for example, if we are trimming activities
        // down to the max limit while they are still waiting to finish.
        mFinishingActivities.remove(r);
        mWaitingVisibleActivities.remove(r);
        
        // Remove any pending results.
        if (r.finishing && r.pendingResults != null) {
            for (WeakReference<PendingIntentRecord> apr : r.pendingResults) {
                PendingIntentRecord rec = apr.get();
                if (rec != null) {
                    cancelIntentSenderLocked(rec, false);
                }
            }
            r.pendingResults = null;
        }

        if (cleanServices) {
            cleanUpActivityServicesLocked(r);            
        }

        if (mPendingThumbnails.size() > 0) {
            // There are clients waiting to receive thumbnails so, in case
            // this is an activity that someone is waiting for, add it
            // to the pending list so we can correctly update the clients.
            mCancelledThumbnails.add(r);
        }

        // Get rid of any pending idle timeouts.
        mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
        mHandler.removeMessages(IDLE_TIMEOUT_MSG, r);
    }

    private final void removeActivityFromHistoryLocked(HistoryRecord r) {
        if (r.state != ActivityState.DESTROYED) {
            mHistory.remove(r);
            r.inHistory = false;
            r.state = ActivityState.DESTROYED;
            mWindowManager.removeAppToken(r);
            if (VALIDATE_TOKENS) {
                mWindowManager.validateAppTokens(mHistory);
            }
            cleanUpActivityServicesLocked(r);
            removeActivityUriPermissionsLocked(r);
        }
    }
    
    /**
     * Destroy the current CLIENT SIDE instance of an activity.  This may be
     * called both when actually finishing an activity, or when performing
     * a configuration switch where we destroy the current client-side object
     * but then create a new client-side object for this same HistoryRecord.
     */
    private final boolean destroyActivityLocked(HistoryRecord r,
            boolean removeFromApp) {
        if (DEBUG_SWITCH) Slog.v(
            TAG, "Removing activity: token=" + r
              + ", app=" + (r.app != null ? r.app.processName : "(null)"));
        EventLog.writeEvent(EventLogTags.AM_DESTROY_ACTIVITY,
                System.identityHashCode(r),
                r.task.taskId, r.shortComponentName);

        boolean removedFromHistory = false;
        
        cleanUpActivityLocked(r, false);

        final boolean hadApp = r.app != null;
        
        if (hadApp) {
            if (removeFromApp) {
                int idx = r.app.activities.indexOf(r);
                if (idx >= 0) {
                    r.app.activities.remove(idx);
                }
                if (r.persistent) {
                    decPersistentCountLocked(r.app);
                }
                if (r.app.activities.size() == 0) {
                    // No longer have activities, so update location in
                    // LRU list.
                    updateLruProcessLocked(r.app, true, false);
                }
            }

            boolean skipDestroy = false;
            
            try {
                if (DEBUG_SWITCH) Slog.i(TAG, "Destroying: " + r);
                r.app.thread.scheduleDestroyActivity(r, r.finishing,
                        r.configChangeFlags);
            } catch (Exception e) {
                // We can just ignore exceptions here...  if the process
                // has crashed, our death notification will clean things
                // up.
                //Slog.w(TAG, "Exception thrown during finish", e);
                if (r.finishing) {
                    removeActivityFromHistoryLocked(r);
                    removedFromHistory = true;
                    skipDestroy = true;
                }
            }

            r.app = null;
            r.nowVisible = false;
            
            if (r.finishing && !skipDestroy) {
                r.state = ActivityState.DESTROYING;
                Message msg = mHandler.obtainMessage(DESTROY_TIMEOUT_MSG);
                msg.obj = r;
                mHandler.sendMessageDelayed(msg, DESTROY_TIMEOUT);
            } else {
                r.state = ActivityState.DESTROYED;
            }
        } else {
            // remove this record from the history.
            if (r.finishing) {
                removeActivityFromHistoryLocked(r);
                removedFromHistory = true;
            } else {
                r.state = ActivityState.DESTROYED;
            }
        }

        r.configChangeFlags = 0;
        
        if (!mLRUActivities.remove(r) && hadApp) {
            Slog.w(TAG, "Activity " + r + " being finished, but not in LRU list");
        }
        
        return removedFromHistory;
    }

    private static void removeHistoryRecordsForAppLocked(ArrayList list, ProcessRecord app) {
        int i = list.size();
        if (localLOGV) Slog.v(
            TAG, "Removing app " + app + " from list " + list
            + " with " + i + " entries");
        while (i > 0) {
            i--;
            HistoryRecord r = (HistoryRecord)list.get(i);
            if (localLOGV) Slog.v(
                TAG, "Record #" + i + " " + r + ": app=" + r.app);
            if (r.app == app) {
                if (localLOGV) Slog.v(TAG, "Removing this entry!");
                list.remove(i);
            }
        }
    }

    /**
     * Main function for removing an existing process from the activity manager
     * as a result of that process going away.  Clears out all connections
     * to the process.
     */
    private final void handleAppDiedLocked(ProcessRecord app,
            boolean restarting) {
        cleanUpApplicationRecordLocked(app, restarting, -1);
        if (!restarting) {
            mLruProcesses.remove(app);
        }

        // Just in case...
        if (mPausingActivity != null && mPausingActivity.app == app) {
            if (DEBUG_PAUSE) Slog.v(TAG, "App died while pausing: " + mPausingActivity);
            mPausingActivity = null;
        }
        if (mLastPausedActivity != null && mLastPausedActivity.app == app) {
            mLastPausedActivity = null;
        }

        // Remove this application's activities from active lists.
        removeHistoryRecordsForAppLocked(mLRUActivities, app);
        removeHistoryRecordsForAppLocked(mStoppingActivities, app);
        removeHistoryRecordsForAppLocked(mWaitingVisibleActivities, app);
        removeHistoryRecordsForAppLocked(mFinishingActivities, app);

        boolean atTop = true;
        boolean hasVisibleActivities = false;

        // Clean out the history list.
        int i = mHistory.size();
        if (localLOGV) Slog.v(
            TAG, "Removing app " + app + " from history with " + i + " entries");
        while (i > 0) {
            i--;
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (localLOGV) Slog.v(
                TAG, "Record #" + i + " " + r + ": app=" + r.app);
            if (r.app == app) {
                if ((!r.haveState && !r.stateNotNeeded) || r.finishing) {
                    if (localLOGV) Slog.v(
                        TAG, "Removing this entry!  frozen=" + r.haveState
                        + " finishing=" + r.finishing);
                    mHistory.remove(i);

                    r.inHistory = false;
                    mWindowManager.removeAppToken(r);
                    if (VALIDATE_TOKENS) {
                        mWindowManager.validateAppTokens(mHistory);
                    }
                    removeActivityUriPermissionsLocked(r);

                } else {
                    // We have the current state for this activity, so
                    // it can be restarted later when needed.
                    if (localLOGV) Slog.v(
                        TAG, "Keeping entry, setting app to null");
                    if (r.visible) {
                        hasVisibleActivities = true;
                    }
                    r.app = null;
                    r.nowVisible = false;
                    if (!r.haveState) {
                        r.icicle = null;
                    }
                }

                cleanUpActivityLocked(r, true);
                r.state = ActivityState.STOPPED;
            }
            atTop = false;
        }

        app.activities.clear();
        
        if (app.instrumentationClass != null) {
            Slog.w(TAG, "Crash of app " + app.processName
                  + " running instrumentation " + app.instrumentationClass);
            Bundle info = new Bundle();
            info.putString("shortMsg", "Process crashed.");
            finishInstrumentationLocked(app, Activity.RESULT_CANCELED, info);
        }

        if (!restarting) {
            if (!resumeTopActivityLocked(null)) {
                // If there was nothing to resume, and we are not already
                // restarting this process, but there is a visible activity that
                // is hosted by the process...  then make sure all visible
                // activities are running, taking care of restarting this
                // process.
                if (hasVisibleActivities) {
                    ensureActivitiesVisibleLocked(null, 0);
                }
            }
        }
    }

    private final int getLRURecordIndexForAppLocked(IApplicationThread thread) {
        IBinder threadBinder = thread.asBinder();

        // Find the application record.
        for (int i=mLruProcesses.size()-1; i>=0; i--) {
            ProcessRecord rec = mLruProcesses.get(i);
            if (rec.thread != null && rec.thread.asBinder() == threadBinder) {
                return i;
            }
        }
        return -1;
    }

    private final ProcessRecord getRecordForAppLocked(
            IApplicationThread thread) {
        if (thread == null) {
            return null;
        }

        int appIndex = getLRURecordIndexForAppLocked(thread);
        return appIndex >= 0 ? mLruProcesses.get(appIndex) : null;
    }

    private final void appDiedLocked(ProcessRecord app, int pid,
            IApplicationThread thread) {

        mProcDeaths[0]++;
        
        // Clean up already done if the process has been re-started.
        if (app.pid == pid && app.thread != null &&
                app.thread.asBinder() == thread.asBinder()) {
            if (!app.killedBackground) {
                Slog.i(TAG, "Process " + app.processName + " (pid " + pid
                        + ") has died.");
            }
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.pid, app.processName);
            if (localLOGV) Slog.v(
                TAG, "Dying app: " + app + ", pid: " + pid
                + ", thread: " + thread.asBinder());
            boolean doLowMem = app.instrumentationClass == null;
            handleAppDiedLocked(app, false);

            if (doLowMem) {
                // If there are no longer any background processes running,
                // and the app that died was not running instrumentation,
                // then tell everyone we are now low on memory.
                boolean haveBg = false;
                for (int i=mLruProcesses.size()-1; i>=0; i--) {
                    ProcessRecord rec = mLruProcesses.get(i);
                    if (rec.thread != null && rec.setAdj >= HIDDEN_APP_MIN_ADJ) {
                        haveBg = true;
                        break;
                    }
                }
                
                if (!haveBg) {
                    Slog.i(TAG, "Low Memory: No more background processes.");
                    EventLog.writeEvent(EventLogTags.AM_LOW_MEMORY, mLruProcesses.size());
                    long now = SystemClock.uptimeMillis();
                    for (int i=mLruProcesses.size()-1; i>=0; i--) {
                        ProcessRecord rec = mLruProcesses.get(i);
                        if (rec != app && rec.thread != null &&
                                (rec.lastLowMemory+GC_MIN_INTERVAL) <= now) {
                            // The low memory report is overriding any current
                            // state for a GC request.  Make sure to do
                            // visible/foreground processes first.
                            if (rec.setAdj <= VISIBLE_APP_ADJ) {
                                rec.lastRequestedGc = 0;
                            } else {
                                rec.lastRequestedGc = rec.lastLowMemory;
                            }
                            rec.reportLowMemory = true;
                            rec.lastLowMemory = now;
                            mProcessesToGc.remove(rec);
                            addProcessToGcListLocked(rec);
                        }
                    }
                    scheduleAppGcsLocked();
                }
            }
        } else if (app.pid != pid) {
            // A new process has already been started.
            Slog.i(TAG, "Process " + app.processName + " (pid " + pid
                    + ") has died and restarted (pid " + app.pid + ").");
            EventLog.writeEvent(EventLogTags.AM_PROC_DIED, app.pid, app.processName);
        } else if (DEBUG_PROCESSES) {
            Slog.d(TAG, "Received spurious death notification for thread "
                    + thread.asBinder());
        }
    }

    /**
     * If a stack trace dump file is configured, dump process stack traces.
     * @param clearTraces causes the dump file to be erased prior to the new
     *    traces being written, if true; when false, the new traces will be
     *    appended to any existing file content.
     * @param pids of dalvik VM processes to dump stack traces for
     * @return file containing stack traces, or null if no dump file is configured
     */
    public static File dumpStackTraces(boolean clearTraces, ArrayList<Integer> pids) {
        String tracesPath = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if (tracesPath == null || tracesPath.length() == 0) {
            return null;
        }

        File tracesFile = new File(tracesPath);
        try {
            File tracesDir = tracesFile.getParentFile();
            if (!tracesDir.exists()) tracesFile.mkdirs();
            FileUtils.setPermissions(tracesDir.getPath(), 0775, -1, -1);  // drwxrwxr-x

            if (clearTraces && tracesFile.exists()) tracesFile.delete();
            tracesFile.createNewFile();
            FileUtils.setPermissions(tracesFile.getPath(), 0666, -1, -1); // -rw-rw-rw-
        } catch (IOException e) {
            Slog.w(TAG, "Unable to prepare ANR traces file: " + tracesPath, e);
            return null;
        }

        // Use a FileObserver to detect when traces finish writing.
        // The order of traces is considered important to maintain for legibility.
        FileObserver observer = new FileObserver(tracesPath, FileObserver.CLOSE_WRITE) {
            public synchronized void onEvent(int event, String path) { notify(); }
        };

        try {
            observer.startWatching();
            int num = pids.size();
            for (int i = 0; i < num; i++) {
                synchronized (observer) {
                    Process.sendSignal(pids.get(i), Process.SIGNAL_QUIT);
                    observer.wait(200);  // Wait for write-close, give up after 200msec
                }
            }
        } catch (InterruptedException e) {
            Log.wtf(TAG, e);
        } finally {
            observer.stopWatching();
        }

        return tracesFile;
    }

    final void appNotResponding(ProcessRecord app, HistoryRecord activity,
            HistoryRecord parent, final String annotation) {
        ArrayList<Integer> pids = new ArrayList<Integer>(20);
        
        synchronized (this) {
            // PowerManager.reboot() can block for a long time, so ignore ANRs while shutting down.
            if (mShuttingDown) {
                Slog.i(TAG, "During shutdown skipping ANR: " + app + " " + annotation);
                return;
            } else if (app.notResponding) {
                Slog.i(TAG, "Skipping duplicate ANR: " + app + " " + annotation);
                return;
            } else if (app.crashing) {
                Slog.i(TAG, "Crashing app skipping ANR: " + app + " " + annotation);
                return;
            }
            
            // In case we come through here for the same app before completing
            // this one, mark as anring now so we will bail out.
            app.notResponding = true;

            // Log the ANR to the event log.
            EventLog.writeEvent(EventLogTags.AM_ANR, app.pid, app.processName, app.info.flags,
                    annotation);

            // Dump thread traces as quickly as we can, starting with "interesting" processes.
            pids.add(app.pid);
    
            int parentPid = app.pid;
            if (parent != null && parent.app != null && parent.app.pid > 0) parentPid = parent.app.pid;
            if (parentPid != app.pid) pids.add(parentPid);
    
            if (MY_PID != app.pid && MY_PID != parentPid) pids.add(MY_PID);

            for (int i = mLruProcesses.size() - 1; i >= 0; i--) {
                ProcessRecord r = mLruProcesses.get(i);
                if (r != null && r.thread != null) {
                    int pid = r.pid;
                    if (pid > 0 && pid != app.pid && pid != parentPid && pid != MY_PID) pids.add(pid);
                }
            }
        }

        File tracesFile = dumpStackTraces(true, pids);

        // Log the ANR to the main log.
        StringBuilder info = mStringBuilder;
        info.setLength(0);
        info.append("ANR in ").append(app.processName);
        if (activity != null && activity.shortComponentName != null) {
            info.append(" (").append(activity.shortComponentName).append(")");
        }
        info.append("\n");
        if (annotation != null) {
            info.append("Reason: ").append(annotation).append("\n");
        }
        if (parent != null && parent != activity) {
            info.append("Parent: ").append(parent.shortComponentName).append("\n");
        }

        String cpuInfo = null;
        if (MONITOR_CPU_USAGE) {
            updateCpuStatsNow();
            synchronized (mProcessStatsThread) {
                cpuInfo = mProcessStats.printCurrentState();
            }
            info.append(cpuInfo);
        }

        Slog.e(TAG, info.toString());
        if (tracesFile == null) {
            // There is no trace file, so dump (only) the alleged culprit's threads to the log
            Process.sendSignal(app.pid, Process.SIGNAL_QUIT);
        }

        addErrorToDropBox("anr", app, activity, parent, annotation, cpuInfo, tracesFile, null);

        if (mController != null) {
            try {
                // 0 == show dialog, 1 = keep waiting, -1 = kill process immediately
                int res = mController.appNotResponding(app.processName, app.pid, info.toString());
                if (res != 0) {
                    if (res < 0 && app.pid != MY_PID) Process.killProcess(app.pid);
                    return;
                }
            } catch (RemoteException e) {
                mController = null;
            }
        }

        // Unless configured otherwise, swallow ANRs in background processes & kill the process.
        boolean showBackground = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.ANR_SHOW_BACKGROUND, 0) != 0;
        
        synchronized (this) {
            if (!showBackground && !app.isInterestingToUserLocked() && app.pid != MY_PID) {
                Process.killProcess(app.pid);
                return;
            }
    
            // Set the app's notResponding state, and look up the errorReportReceiver
            makeAppNotRespondingLocked(app,
                    activity != null ? activity.shortComponentName : null,
                    annotation != null ? "ANR " + annotation : "ANR",
                    info.toString());
    
            // Bring up the infamous App Not Responding dialog
            Message msg = Message.obtain();
            HashMap map = new HashMap();
            msg.what = SHOW_NOT_RESPONDING_MSG;
            msg.obj = map;
            map.put("app", app);
            if (activity != null) {
                map.put("activity", activity);
            }
    
            mHandler.sendMessage(msg);
        }
    }

    private final void decPersistentCountLocked(ProcessRecord app)
    {
        app.persistentActivities--;
        if (app.persistentActivities > 0) {
            // Still more of 'em...
            return;
        }
        if (app.persistent) {
            // Ah, but the application itself is persistent.  Whatever!
            return;
        }

        // App is no longer persistent...  make sure it and the ones
        // following it in the LRU list have the correc oom_adj.
        updateOomAdjLocked();
    }

    public void setPersistent(IBinder token, boolean isPersistent) {
        if (checkCallingPermission(android.Manifest.permission.PERSISTENT_ACTIVITY)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: setPersistent() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.PERSISTENT_ACTIVITY;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        synchronized(this) {
            int index = indexOfTokenLocked(token);
            if (index < 0) {
                return;
            }
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            ProcessRecord app = r.app;

            if (localLOGV) Slog.v(
                TAG, "Setting persistence " + isPersistent + ": " + r);

            if (isPersistent) {
                if (r.persistent) {
                    // Okay okay, I heard you already!
                    if (localLOGV) Slog.v(TAG, "Already persistent!");
                    return;
                }
                r.persistent = true;
                app.persistentActivities++;
                if (localLOGV) Slog.v(TAG, "Num persistent now: " + app.persistentActivities);
                if (app.persistentActivities > 1) {
                    // We aren't the first...
                    if (localLOGV) Slog.v(TAG, "Not the first!");
                    return;
                }
                if (app.persistent) {
                    // This would be redundant.
                    if (localLOGV) Slog.v(TAG, "App is persistent!");
                    return;
                }

                // App is now persistent...  make sure it and the ones
                // following it now have the correct oom_adj.
                final long origId = Binder.clearCallingIdentity();
                updateOomAdjLocked();
                Binder.restoreCallingIdentity(origId);

            } else {
                if (!r.persistent) {
                    // Okay okay, I heard you already!
                    return;
                }
                r.persistent = false;
                final long origId = Binder.clearCallingIdentity();
                decPersistentCountLocked(app);
                Binder.restoreCallingIdentity(origId);

            }
        }
    }
    
    public boolean clearApplicationUserData(final String packageName,
            final IPackageDataObserver observer) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Slog.w(TAG, "Invalid packageName:" + packageName);
                    return false;
                }
                if (uid == pkgUid || checkComponentPermission(
                        android.Manifest.permission.CLEAR_APP_USER_DATA,
                        pid, uid, -1)
                        == PackageManager.PERMISSION_GRANTED) {
                    forceStopPackageLocked(packageName, pkgUid);
                } else {
                    throw new SecurityException(pid+" does not have permission:"+
                            android.Manifest.permission.CLEAR_APP_USER_DATA+" to clear data" +
                                    "for process:"+packageName);
                }
            }
            
            try {
                //clear application user data
                pm.clearApplicationUserData(packageName, observer);
                Intent intent = new Intent(Intent.ACTION_PACKAGE_DATA_CLEARED,
                        Uri.fromParts("package", packageName, null));
                intent.putExtra(Intent.EXTRA_UID, pkgUid);
                synchronized (this) {
                    broadcastIntentLocked(null, null, intent,
                            null, null, 0, null, null, null,
                            false, false, MY_PID, Process.SYSTEM_UID);
                }
            } catch (RemoteException e) {
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
        return true;
    }

    public void killBackgroundProcesses(final String packageName) {
        if (checkCallingPermission(android.Manifest.permission.KILL_BACKGROUND_PROCESSES)
                != PackageManager.PERMISSION_GRANTED &&
                checkCallingPermission(android.Manifest.permission.RESTART_PACKAGES)
                        != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: killBackgroundProcesses() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.KILL_BACKGROUND_PROCESSES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                killPackageProcessesLocked(packageName, pkgUid,
                        SECONDARY_SERVER_ADJ, false, true);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    public void forceStopPackage(final String packageName) {
        if (checkCallingPermission(android.Manifest.permission.FORCE_STOP_PACKAGES)
                != PackageManager.PERMISSION_GRANTED) {
            String msg = "Permission Denial: forceStopPackage() from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " requires " + android.Manifest.permission.FORCE_STOP_PACKAGES;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }
        
        long callingId = Binder.clearCallingIdentity();
        try {
            IPackageManager pm = ActivityThread.getPackageManager();
            int pkgUid = -1;
            synchronized(this) {
                try {
                    pkgUid = pm.getPackageUid(packageName);
                } catch (RemoteException e) {
                }
                if (pkgUid == -1) {
                    Slog.w(TAG, "Invalid packageName: " + packageName);
                    return;
                }
                forceStopPackageLocked(packageName, pkgUid);
            }
        } finally {
            Binder.restoreCallingIdentity(callingId);
        }
    }

    /*
     * The pkg name and uid have to be specified.
     * @see android.app.IActivityManager#killApplicationWithUid(java.lang.String, int)
     */
    public void killApplicationWithUid(String pkg, int uid) {
        if (pkg == null) {
            return;
        }
        // Make sure the uid is valid.
        if (uid < 0) {
            Slog.w(TAG, "Invalid uid specified for pkg : " + pkg);
            return;
        }
        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (callerUid == Process.SYSTEM_UID) {
            // Post an aysnc message to kill the application
            Message msg = mHandler.obtainMessage(KILL_APPLICATION_MSG);
            msg.arg1 = uid;
            msg.arg2 = 0;
            msg.obj = pkg;
            mHandler.sendMessage(msg);
        } else {
            throw new SecurityException(callerUid + " cannot kill pkg: " +
                    pkg);
        }
    }

    public void closeSystemDialogs(String reason) {
        Intent intent = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (reason != null) {
            intent.putExtra("reason", reason);
        }
        
        final int uid = Binder.getCallingUid();
        final long origId = Binder.clearCallingIdentity();
        synchronized (this) {
            int i = mWatchers.beginBroadcast();
            while (i > 0) {
                i--;
                IActivityWatcher w = mWatchers.getBroadcastItem(i);
                if (w != null) {
                    try {
                        w.closingSystemDialogs(reason);
                    } catch (RemoteException e) {
                    }
                }
            }
            mWatchers.finishBroadcast();
            
            mWindowManager.closeSystemDialogs(reason);
            
            for (i=mHistory.size()-1; i>=0; i--) {
                HistoryRecord r = (HistoryRecord)mHistory.get(i);
                if ((r.info.flags&ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS) != 0) {
                    finishActivityLocked(r, i,
                            Activity.RESULT_CANCELED, null, "close-sys");
                }
            }
            
            broadcastIntentLocked(null, null, intent, null,
                    null, 0, null, null, null, false, false, -1, uid);
        }
        Binder.restoreCallingIdentity(origId);
    }
    
    public Debug.MemoryInfo[] getProcessMemoryInfo(int[] pids)
            throws RemoteException {
        Debug.MemoryInfo[] infos = new Debug.MemoryInfo[pids.length];
        for (int i=pids.length-1; i>=0; i--) {
            infos[i] = new Debug.MemoryInfo();
            Debug.getMemoryInfo(pids[i], infos[i]);
        }
        return infos;
    }

    public void killApplicationProcess(String processName, int uid) {
        if (processName == null) {
            return;
        }

        int callerUid = Binder.getCallingUid();
        // Only the system server can kill an application
        if (callerUid == Process.SYSTEM_UID) {
            synchronized (this) {
                ProcessRecord app = getProcessRecordLocked(processName, uid);
                if (app != null) {
                    try {
                        app.thread.scheduleSuicide();
                    } catch (RemoteException e) {
                        // If the other end already died, then our work here is done.
                    }
                } else {
                    Slog.w(TAG, "Process/uid not found attempting kill of "
                            + processName + " / " + uid);
                }
            }
        } else {
            throw new SecurityException(callerUid + " cannot kill app process: " +
                    processName);
        }
    }

    private void forceStopPackageLocked(final String packageName, int uid) {
        forceStopPackageLocked(packageName, uid, false, false, true);
        Intent intent = new Intent(Intent.ACTION_PACKAGE_RESTARTED,
                Uri.fromParts("package", packageName, null));
        intent.putExtra(Intent.EXTRA_UID, uid);
        broadcastIntentLocked(null, null, intent,
                null, null, 0, null, null, null,
                false, false, MY_PID, Process.SYSTEM_UID);
    }
    
    private final boolean killPackageProcessesLocked(String packageName, int uid,
            int minOomAdj, boolean callerWillRestart, boolean doit) {
        ArrayList<ProcessRecord> procs = new ArrayList<ProcessRecord>();

        // Remove all processes this package may have touched: all with the
        // same UID (except for the system or root user), and all whose name
        // matches the package name.
        final String procNamePrefix = packageName + ":";
        for (SparseArray<ProcessRecord> apps : mProcessNames.getMap().values()) {
            final int NA = apps.size();
            for (int ia=0; ia<NA; ia++) {
                ProcessRecord app = apps.valueAt(ia);
                if (app.removed) {
                    if (doit) {
                        procs.add(app);
                    }
                } else if ((uid > 0 && uid != Process.SYSTEM_UID && app.info.uid == uid)
                        || app.processName.equals(packageName)
                        || app.processName.startsWith(procNamePrefix)) {
                    if (app.setAdj >= minOomAdj) {
                        if (!doit) {
                            return true;
                        }
                        app.removed = true;
                        procs.add(app);
                    }
                }
            }
        }
        
        int N = procs.size();
        for (int i=0; i<N; i++) {
            removeProcessLocked(procs.get(i), callerWillRestart);
        }
        return N > 0;
    }

    private final boolean forceStopPackageLocked(String name, int uid,
            boolean callerWillRestart, boolean purgeCache, boolean doit) {
        int i, N;

        if (uid < 0) {
            try {
                uid = ActivityThread.getPackageManager().getPackageUid(name);
            } catch (RemoteException e) {
            }
        }

        if (doit) {
            Slog.i(TAG, "Force stopping package " + name + " uid=" + uid);

            Iterator<SparseArray<Long>> badApps = mProcessCrashTimes.getMap().values().iterator();
            while (badApps.hasNext()) {
                SparseArray<Long> ba = badApps.next();
                if (ba.get(uid) != null) {
                    badApps.remove();
                }
            }
        }
        
        boolean didSomething = killPackageProcessesLocked(name, uid, -100,
                callerWillRestart, doit);
        
        for (i=mHistory.size()-1; i>=0; i--) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (r.packageName.equals(name)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i(TAG, "  Force finishing activity " + r);
                if (r.app != null) {
                    r.app.removed = true;
                }
                r.app = null;
                finishActivityLocked(r, i, Activity.RESULT_CANCELED, null, "uninstall");
            }
        }

        ArrayList<ServiceRecord> services = new ArrayList<ServiceRecord>();
        for (ServiceRecord service : mServices.values()) {
            if (service.packageName.equals(name)) {
                if (!doit) {
                    return true;
                }
                didSomething = true;
                Slog.i(TAG, "  Force stopping service " + service);
                if (service.app != null) {
                    service.app.removed = true;
                }
                service.app = null;
                services.add(service);
            }
        }

        N = services.size();
        for (i=0; i<N; i++) {
            bringDownServiceLocked(services.get(i), true);
        }
        
        if (doit) {
            if (purgeCache) {
                AttributeCache ac = AttributeCache.instance();
                if (ac != null) {
                    ac.removePackage(name);
                }
            }
            resumeTopActivityLocked(null);
        }
        
        return didSomething;
    }

    private final boolean removeProcessLocked(ProcessRecord app, boolean callerWillRestart) {
        final String name = app.processName;
        final int uid = app.info.uid;
        if (DEBUG_PROCESSES) Slog.d(
            TAG, "Force removing process " + app + " (" + name
            + "/" + uid + ")");

        mProcessNames.remove(name, uid);
        boolean needRestart = false;
        if (app.pid > 0 && app.pid != MY_PID) {
            int pid = app.pid;
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            handleAppDiedLocked(app, true);
            mLruProcesses.remove(app);
            Process.killProcess(pid);
            
            if (app.persistent) {
                if (!callerWillRestart) {
                    addAppLocked(app.info);
                } else {
                    needRestart = true;
                }
            }
        } else {
            mRemovedProcesses.add(app);
        }
        
        return needRestart;
    }

    private final void processStartTimedOutLocked(ProcessRecord app) {
        final int pid = app.pid;
        boolean gone = false;
        synchronized (mPidsSelfLocked) {
            ProcessRecord knownApp = mPidsSelfLocked.get(pid);
            if (knownApp != null && knownApp.thread == null) {
                mPidsSelfLocked.remove(pid);
                gone = true;
            }        
        }
        
        if (gone) {
            Slog.w(TAG, "Process " + app + " failed to attach");
            EventLog.writeEvent(EventLogTags.AM_PROCESS_START_TIMEOUT, pid, app.info.uid,
                    app.processName);
            mProcessNames.remove(app.processName, app.info.uid);
            // Take care of any launching providers waiting for this process.
            checkAppInLaunchingProvidersLocked(app, true);
            // Take care of any services that are waiting for the process.
            for (int i=0; i<mPendingServices.size(); i++) {
                ServiceRecord sr = mPendingServices.get(i);
                if (app.info.uid == sr.appInfo.uid
                        && app.processName.equals(sr.processName)) {
                    Slog.w(TAG, "Forcing bringing down service: " + sr);
                    mPendingServices.remove(i);
                    i--;
                    bringDownServiceLocked(sr, true);
                }
            }
            Process.killProcess(pid);
            if (mBackupTarget != null && mBackupTarget.app.pid == pid) {
                Slog.w(TAG, "Unattached app died before backup, skipping");
                try {
                    IBackupManager bm = IBackupManager.Stub.asInterface(
                            ServiceManager.getService(Context.BACKUP_SERVICE));
                    bm.agentDisconnected(app.info.packageName);
                } catch (RemoteException e) {
                    // Can't happen; the backup manager is local
                }
            }
            if (mPendingBroadcast != null && mPendingBroadcast.curApp.pid == pid) {
                Slog.w(TAG, "Unattached app died before broadcast acknowledged, skipping");
                mPendingBroadcast = null;
                scheduleBroadcastsLocked();
            }
        } else {
            Slog.w(TAG, "Spurious process start timeout - pid not known for " + app);
        }
    }

    private final boolean attachApplicationLocked(IApplicationThread thread,
            int pid) {

        // Find the application record that is being attached...  either via
        // the pid if we are running in multiple processes, or just pull the
        // next app record if we are emulating process with anonymous threads.
        ProcessRecord app;
        if (pid != MY_PID && pid >= 0) {
            synchronized (mPidsSelfLocked) {
                app = mPidsSelfLocked.get(pid);
            }
        } else if (mStartingProcesses.size() > 0) {
            app = mStartingProcesses.remove(0);
            app.setPid(pid);
        } else {
            app = null;
        }

        if (app == null) {
            Slog.w(TAG, "No pending application record for pid " + pid
                    + " (IApplicationThread " + thread + "); dropping process");
            EventLog.writeEvent(EventLogTags.AM_DROP_PROCESS, pid);
            if (pid > 0 && pid != MY_PID) {
                Process.killProcess(pid);
            } else {
                try {
                    thread.scheduleExit();
                } catch (Exception e) {
                    // Ignore exceptions.
                }
            }
            return false;
        }

        // If this application record is still attached to a previous
        // process, clean it up now.
        if (app.thread != null) {
            handleAppDiedLocked(app, true);
        }

        // Tell the process all about itself.

        if (localLOGV) Slog.v(
                TAG, "Binding process pid " + pid + " to record " + app);

        String processName = app.processName;
        try {
            thread.asBinder().linkToDeath(new AppDeathRecipient(
                    app, pid, thread), 0);
        } catch (RemoteException e) {
            app.resetPackageList();
            startProcessLocked(app, "link fail", processName);
            return false;
        }

        EventLog.writeEvent(EventLogTags.AM_PROC_BOUND, app.pid, app.processName);
        
        app.thread = thread;
        app.curAdj = app.setAdj = -100;
        app.curSchedGroup = Process.THREAD_GROUP_DEFAULT;
        app.setSchedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
        app.forcingToForeground = null;
        app.foregroundServices = false;
        app.debugging = false;

        mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);

        boolean normalMode = mSystemReady || isAllowedWhileBooting(app.info);
        List providers = normalMode ? generateApplicationProvidersLocked(app) : null;

        if (!normalMode) {
            Slog.i(TAG, "Launching preboot mode app: " + app);
        }
        
        if (localLOGV) Slog.v(
            TAG, "New app record " + app
            + " thread=" + thread.asBinder() + " pid=" + pid);
        try {
            int testMode = IApplicationThread.DEBUG_OFF;
            if (mDebugApp != null && mDebugApp.equals(processName)) {
                testMode = mWaitForDebugger
                    ? IApplicationThread.DEBUG_WAIT
                    : IApplicationThread.DEBUG_ON;
                app.debugging = true;
                if (mDebugTransient) {
                    mDebugApp = mOrigDebugApp;
                    mWaitForDebugger = mOrigWaitForDebugger;
                }
            }
            
            // If the app is being launched for restore or full backup, set it up specially
            boolean isRestrictedBackupMode = false;
            if (mBackupTarget != null && mBackupAppName.equals(processName)) {
                isRestrictedBackupMode = (mBackupTarget.backupMode == BackupRecord.RESTORE)
                        || (mBackupTarget.backupMode == BackupRecord.BACKUP_FULL);
            }
            
            ensurePackageDexOpt(app.instrumentationInfo != null
                    ? app.instrumentationInfo.packageName
                    : app.info.packageName);
            if (app.instrumentationClass != null) {
                ensurePackageDexOpt(app.instrumentationClass.getPackageName());
            }
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Binding proc "
                    + processName + " with config " + mConfiguration);
            thread.bindApplication(processName, app.instrumentationInfo != null
                    ? app.instrumentationInfo : app.info, providers,
                    app.instrumentationClass, app.instrumentationProfileFile,
                    app.instrumentationArguments, app.instrumentationWatcher, testMode, 
                    isRestrictedBackupMode || !normalMode,
                    mConfiguration, getCommonServicesLocked());
            updateLruProcessLocked(app, false, true);
            app.lastRequestedGc = app.lastLowMemory = SystemClock.uptimeMillis();
        } catch (Exception e) {
            // todo: Yikes!  What should we do?  For now we will try to
            // start another process, but that could easily get us in
            // an infinite loop of restarting processes...
            Slog.w(TAG, "Exception thrown during bind!", e);

            app.resetPackageList();
            startProcessLocked(app, "bind fail", processName);
            return false;
        }

        // Remove this record from the list of starting applications.
        mPersistentStartingProcesses.remove(app);
        mProcessesOnHold.remove(app);

        boolean badApp = false;
        boolean didSomething = false;

        // See if the top visible activity is waiting to run in this process...
        HistoryRecord hr = topRunningActivityLocked(null);
        if (hr != null && normalMode) {
            if (hr.app == null && app.info.uid == hr.info.applicationInfo.uid
                    && processName.equals(hr.processName)) {
                try {
                    if (realStartActivityLocked(hr, app, true, true)) {
                        didSomething = true;
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Exception in new application when starting activity "
                          + hr.intent.getComponent().flattenToShortString(), e);
                    badApp = true;
                }
            } else {
                ensureActivitiesVisibleLocked(hr, null, processName, 0);
            }
        }

        // Find any services that should be running in this process...
        if (!badApp && mPendingServices.size() > 0) {
            ServiceRecord sr = null;
            try {
                for (int i=0; i<mPendingServices.size(); i++) {
                    sr = mPendingServices.get(i);
                    if (app.info.uid != sr.appInfo.uid
                            || !processName.equals(sr.processName)) {
                        continue;
                    }

                    mPendingServices.remove(i);
                    i--;
                    realStartServiceLocked(sr, app);
                    didSomething = true;
                }
            } catch (Exception e) {
                Slog.w(TAG, "Exception in new application when starting service "
                      + sr.shortName, e);
                badApp = true;
            }
        }

        // Check if the next broadcast receiver is in this process...
        BroadcastRecord br = mPendingBroadcast;
        if (!badApp && br != null && br.curApp == app) {
            try {
                mPendingBroadcast = null;
                processCurBroadcastLocked(br, app);
                didSomething = true;
            } catch (Exception e) {
                Slog.w(TAG, "Exception in new application when starting receiver "
                      + br.curComponent.flattenToShortString(), e);
                badApp = true;
                logBroadcastReceiverDiscard(br);
                finishReceiverLocked(br.receiver, br.resultCode, br.resultData,
                        br.resultExtras, br.resultAbort, true);
                scheduleBroadcastsLocked();
                // We need to reset the state if we fails to start the receiver.
                br.state = BroadcastRecord.IDLE;
            }
        }

        // Check whether the next backup agent is in this process...
        if (!badApp && mBackupTarget != null && mBackupTarget.appInfo.uid == app.info.uid) {
            if (DEBUG_BACKUP) Slog.v(TAG, "New app is backup target, launching agent for " + app);
            ensurePackageDexOpt(mBackupTarget.appInfo.packageName);
            try {
                thread.scheduleCreateBackupAgent(mBackupTarget.appInfo, mBackupTarget.backupMode);
            } catch (Exception e) {
                Slog.w(TAG, "Exception scheduling backup agent creation: ");
                e.printStackTrace();
            }
        }

        if (badApp) {
            // todo: Also need to kill application to deal with all
            // kinds of exceptions.
            handleAppDiedLocked(app, false);
            return false;
        }

        if (!didSomething) {
            updateOomAdjLocked();
        }

        return true;
    }

    public final void attachApplication(IApplicationThread thread) {
        synchronized (this) {
            int callingPid = Binder.getCallingPid();
            final long origId = Binder.clearCallingIdentity();
            attachApplicationLocked(thread, callingPid);
            Binder.restoreCallingIdentity(origId);
        }
    }

    public final void activityIdle(IBinder token, Configuration config) {
        final long origId = Binder.clearCallingIdentity();
        activityIdleInternal(token, false, config);
        Binder.restoreCallingIdentity(origId);
    }

    final ArrayList<HistoryRecord> processStoppingActivitiesLocked(
            boolean remove) {
        int N = mStoppingActivities.size();
        if (N <= 0) return null;

        ArrayList<HistoryRecord> stops = null;

        final boolean nowVisible = mResumedActivity != null
                && mResumedActivity.nowVisible
                && !mResumedActivity.waitingVisible;
        for (int i=0; i<N; i++) {
            HistoryRecord s = mStoppingActivities.get(i);
            if (localLOGV) Slog.v(TAG, "Stopping " + s + ": nowVisible="
                    + nowVisible + " waitingVisible=" + s.waitingVisible
                    + " finishing=" + s.finishing);
            if (s.waitingVisible && nowVisible) {
                mWaitingVisibleActivities.remove(s);
                s.waitingVisible = false;
                if (s.finishing) {
                    // If this activity is finishing, it is sitting on top of
                    // everyone else but we now know it is no longer needed...
                    // so get rid of it.  Otherwise, we need to go through the
                    // normal flow and hide it once we determine that it is
                    // hidden by the activities in front of it.
                    if (localLOGV) Slog.v(TAG, "Before stopping, can hide: " + s);
                    mWindowManager.setAppVisibility(s, false);
                }
            }
            if (!s.waitingVisible && remove) {
                if (localLOGV) Slog.v(TAG, "Ready to stop: " + s);
                if (stops == null) {
                    stops = new ArrayList<HistoryRecord>();
                }
                stops.add(s);
                mStoppingActivities.remove(i);
                N--;
                i--;
            }
        }

        return stops;
    }

    void enableScreenAfterBoot() {
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_ENABLE_SCREEN,
                SystemClock.uptimeMillis());
        mWindowManager.enableScreenAfterBoot();
    }

    final void activityIdleInternal(IBinder token, boolean fromTimeout,
            Configuration config) {
        if (localLOGV) Slog.v(TAG, "Activity idle: " + token);

        ArrayList<HistoryRecord> stops = null;
        ArrayList<HistoryRecord> finishes = null;
        ArrayList<HistoryRecord> thumbnails = null;
        int NS = 0;
        int NF = 0;
        int NT = 0;
        IApplicationThread sendThumbnail = null;
        boolean booting = false;
        boolean enableScreen = false;

        synchronized (this) {
            if (token != null) {
                mHandler.removeMessages(IDLE_TIMEOUT_MSG, token);
            }

            // Get the activity record.
            int index = indexOfTokenLocked(token);
            if (index >= 0) {
                HistoryRecord r = (HistoryRecord)mHistory.get(index);

                if (fromTimeout) {
                    reportActivityLaunchedLocked(fromTimeout, r, -1, -1);
                }
                
                // This is a hack to semi-deal with a race condition
                // in the client where it can be constructed with a
                // newer configuration from when we asked it to launch.
                // We'll update with whatever configuration it now says
                // it used to launch.
                if (config != null) {
                    r.configuration = config;
                }
                
                // No longer need to keep the device awake.
                if (mResumedActivity == r && mLaunchingActivity.isHeld()) {
                    mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                    mLaunchingActivity.release();
                }

                // We are now idle.  If someone is waiting for a thumbnail from
                // us, we can now deliver.
                r.idle = true;
                scheduleAppGcsLocked();
                if (r.thumbnailNeeded && r.app != null && r.app.thread != null) {
                    sendThumbnail = r.app.thread;
                    r.thumbnailNeeded = false;
                }

                // If this activity is fullscreen, set up to hide those under it.

                if (DEBUG_VISBILITY) Slog.v(TAG, "Idle activity for " + r);
                ensureActivitiesVisibleLocked(null, 0);

                //Slog.i(TAG, "IDLE: mBooted=" + mBooted + ", fromTimeout=" + fromTimeout);
                if (!mBooted && !fromTimeout) {
                    mBooted = true;
                    enableScreen = true;
                }
                
            } else if (fromTimeout) {
                reportActivityLaunchedLocked(fromTimeout, null, -1, -1);
            }

            // Atomically retrieve all of the other things to do.
            stops = processStoppingActivitiesLocked(true);
            NS = stops != null ? stops.size() : 0;
            if ((NF=mFinishingActivities.size()) > 0) {
                finishes = new ArrayList<HistoryRecord>(mFinishingActivities);
                mFinishingActivities.clear();
            }
            if ((NT=mCancelledThumbnails.size()) > 0) {
                thumbnails = new ArrayList<HistoryRecord>(mCancelledThumbnails);
                mCancelledThumbnails.clear();
            }

            booting = mBooting;
            mBooting = false;
        }

        int i;

        // Send thumbnail if requested.
        if (sendThumbnail != null) {
            try {
                sendThumbnail.requestThumbnail(token);
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown when requesting thumbnail", e);
                sendPendingThumbnail(null, token, null, null, true);
            }
        }

        // Stop any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (i=0; i<NS; i++) {
            HistoryRecord r = (HistoryRecord)stops.get(i);
            synchronized (this) {
                if (r.finishing) {
                    finishCurrentActivityLocked(r, FINISH_IMMEDIATELY);
                } else {
                    stopActivityLocked(r);
                }
            }
        }

        // Finish any activities that are scheduled to do so but have been
        // waiting for the next one to start.
        for (i=0; i<NF; i++) {
            HistoryRecord r = (HistoryRecord)finishes.get(i);
            synchronized (this) {
                destroyActivityLocked(r, true);
            }
        }

        // Report back to any thumbnail receivers.
        for (i=0; i<NT; i++) {
            HistoryRecord r = (HistoryRecord)thumbnails.get(i);
            sendPendingThumbnail(r, null, null, null, true);
        }

        if (booting) {
            finishBooting();
        }

        trimApplications();
        //dump();
        //mWindowManager.dump();

        if (enableScreen) {
            enableScreenAfterBoot();
        }
    }

    final void finishBooting() {
        IntentFilter pkgFilter = new IntentFilter();
        pkgFilter.addAction(Intent.ACTION_QUERY_PACKAGE_RESTART);
        pkgFilter.addDataScheme("package");
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String[] pkgs = intent.getStringArrayExtra(Intent.EXTRA_PACKAGES);
                if (pkgs != null) {
                    for (String pkg : pkgs) {
                        if (forceStopPackageLocked(pkg, -1, false, false, false)) {
                            setResultCode(Activity.RESULT_OK);
                            return;
                        }
                    }
                }
            }
        }, pkgFilter);
        
        synchronized (this) {
            // Ensure that any processes we had put on hold are now started
            // up.
            final int NP = mProcessesOnHold.size();
            if (NP > 0) {
                ArrayList<ProcessRecord> procs =
                    new ArrayList<ProcessRecord>(mProcessesOnHold);
                for (int ip=0; ip<NP; ip++) {
                    this.startProcessLocked(procs.get(ip), "on-hold", null);
                }
            }
            
            if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
                // Tell anyone interested that we are done booting!
                broadcastIntentLocked(null, null,
                        new Intent(Intent.ACTION_BOOT_COMPLETED, null),
                        null, null, 0, null, null,
                        android.Manifest.permission.RECEIVE_BOOT_COMPLETED,
                        false, false, MY_PID, Process.SYSTEM_UID);
            }
        }
    }
    
    final void ensureBootCompleted() {
        boolean booting;
        boolean enableScreen;
        synchronized (this) {
            booting = mBooting;
            mBooting = false;
            enableScreen = !mBooted;
            mBooted = true;
        }
        
        if (booting) {
            finishBooting();
        }

        if (enableScreen) {
            enableScreenAfterBoot();
        }
    }
    
    public final void activityPaused(IBinder token, Bundle icicle) {
        // Refuse possible leaked file descriptors
        if (icicle != null && icicle.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        final long origId = Binder.clearCallingIdentity();
        activityPaused(token, icicle, false);
        Binder.restoreCallingIdentity(origId);
    }

    final void activityPaused(IBinder token, Bundle icicle, boolean timeout) {
        if (DEBUG_PAUSE) Slog.v(
            TAG, "Activity paused: token=" + token + ", icicle=" + icicle
            + ", timeout=" + timeout);

        HistoryRecord r = null;

        synchronized (this) {
            int index = indexOfTokenLocked(token);
            if (index >= 0) {
                r = (HistoryRecord)mHistory.get(index);
                if (!timeout) {
                    r.icicle = icicle;
                    r.haveState = true;
                }
                mHandler.removeMessages(PAUSE_TIMEOUT_MSG, r);
                if (mPausingActivity == r) {
                    r.state = ActivityState.PAUSED;
                    completePauseLocked();
                } else {
                	EventLog.writeEvent(EventLogTags.AM_FAILED_TO_PAUSE,
                	        System.identityHashCode(r), r.shortComponentName, 
                			mPausingActivity != null
                			    ? mPausingActivity.shortComponentName : "(none)");
                }
            }
        }
    }

    public final void activityStopped(IBinder token, Bitmap thumbnail,
            CharSequence description) {
        if (localLOGV) Slog.v(
            TAG, "Activity stopped: token=" + token);

        HistoryRecord r = null;

        final long origId = Binder.clearCallingIdentity();

        synchronized (this) {
            int index = indexOfTokenLocked(token);
            if (index >= 0) {
                r = (HistoryRecord)mHistory.get(index);
                r.thumbnail = thumbnail;
                r.description = description;
                r.stopped = true;
                r.state = ActivityState.STOPPED;
                if (!r.finishing) {
                    if (r.configDestroy) {
                        destroyActivityLocked(r, true);
                        resumeTopActivityLocked(null);
                    }
                }
            }
        }

        if (r != null) {
            sendPendingThumbnail(r, null, null, null, false);
        }

        trimApplications();

        Binder.restoreCallingIdentity(origId);
    }

    public final void activityDestroyed(IBinder token) {
        if (DEBUG_SWITCH) Slog.v(TAG, "ACTIVITY DESTROYED: " + token);
        synchronized (this) {
            mHandler.removeMessages(DESTROY_TIMEOUT_MSG, token);
            
            int index = indexOfTokenLocked(token);
            if (index >= 0) {
                HistoryRecord r = (HistoryRecord)mHistory.get(index);
                if (r.state == ActivityState.DESTROYING) {
                    final long origId = Binder.clearCallingIdentity();
                    removeActivityFromHistoryLocked(r);
                    Binder.restoreCallingIdentity(origId);
                }
            }
        }
    }
    
    public String getCallingPackage(IBinder token) {
        synchronized (this) {
            HistoryRecord r = getCallingRecordLocked(token);
            return r != null && r.app != null ? r.info.packageName : null;
        }
    }

    public ComponentName getCallingActivity(IBinder token) {
        synchronized (this) {
            HistoryRecord r = getCallingRecordLocked(token);
            return r != null ? r.intent.getComponent() : null;
        }
    }

    private HistoryRecord getCallingRecordLocked(IBinder token) {
        int index = indexOfTokenLocked(token);
        if (index >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(index);
            if (r != null) {
                return r.resultTo;
            }
        }
        return null;
    }

    public ComponentName getActivityClassForToken(IBinder token) {
        synchronized(this) {
            int index = indexOfTokenLocked(token);
            if (index >= 0) {
                HistoryRecord r = (HistoryRecord)mHistory.get(index);
                return r.intent.getComponent();
            }
            return null;
        }
    }

    public String getPackageForToken(IBinder token) {
        synchronized(this) {
            int index = indexOfTokenLocked(token);
            if (index >= 0) {
                HistoryRecord r = (HistoryRecord)mHistory.get(index);
                return r.packageName;
            }
            return null;
        }
    }

    public IIntentSender getIntentSender(int type,
            String packageName, IBinder token, String resultWho,
            int requestCode, Intent intent, String resolvedType, int flags) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        if (type == INTENT_SENDER_BROADCAST) {
            if ((intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0) {
                throw new IllegalArgumentException(
                        "Can't use FLAG_RECEIVER_BOOT_UPGRADE here");
            }
        }
        
        synchronized(this) {
            int callingUid = Binder.getCallingUid();
            try {
                if (callingUid != 0 && callingUid != Process.SYSTEM_UID &&
                        Process.supportsProcesses()) {
                    int uid = ActivityThread.getPackageManager()
                            .getPackageUid(packageName);
                    if (uid != Binder.getCallingUid()) {
                        String msg = "Permission Denial: getIntentSender() from pid="
                            + Binder.getCallingPid()
                            + ", uid=" + Binder.getCallingUid()
                            + ", (need uid=" + uid + ")"
                            + " is not allowed to send as package " + packageName;
                        Slog.w(TAG, msg);
                        throw new SecurityException(msg);
                    }
                }
            } catch (RemoteException e) {
                throw new SecurityException(e);
            }
            HistoryRecord activity = null;
            if (type == INTENT_SENDER_ACTIVITY_RESULT) {
                int index = indexOfTokenLocked(token);
                if (index < 0) {
                    return null;
                }
                activity = (HistoryRecord)mHistory.get(index);
                if (activity.finishing) {
                    return null;
                }
            }

            final boolean noCreate = (flags&PendingIntent.FLAG_NO_CREATE) != 0;
            final boolean cancelCurrent = (flags&PendingIntent.FLAG_CANCEL_CURRENT) != 0;
            final boolean updateCurrent = (flags&PendingIntent.FLAG_UPDATE_CURRENT) != 0;
            flags &= ~(PendingIntent.FLAG_NO_CREATE|PendingIntent.FLAG_CANCEL_CURRENT
                    |PendingIntent.FLAG_UPDATE_CURRENT);

            PendingIntentRecord.Key key = new PendingIntentRecord.Key(
                    type, packageName, activity, resultWho,
                    requestCode, intent, resolvedType, flags);
            WeakReference<PendingIntentRecord> ref;
            ref = mIntentSenderRecords.get(key);
            PendingIntentRecord rec = ref != null ? ref.get() : null;
            if (rec != null) {
                if (!cancelCurrent) {
                    if (updateCurrent) {
                        rec.key.requestIntent.replaceExtras(intent);
                    }
                    return rec;
                }
                rec.canceled = true;
                mIntentSenderRecords.remove(key);
            }
            if (noCreate) {
                return rec;
            }
            rec = new PendingIntentRecord(this, key, callingUid);
            mIntentSenderRecords.put(key, rec.ref);
            if (type == INTENT_SENDER_ACTIVITY_RESULT) {
                if (activity.pendingResults == null) {
                    activity.pendingResults
                            = new HashSet<WeakReference<PendingIntentRecord>>();
                }
                activity.pendingResults.add(rec.ref);
            }
            return rec;
        }
    }

    public void cancelIntentSender(IIntentSender sender) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        synchronized(this) {
            PendingIntentRecord rec = (PendingIntentRecord)sender;
            try {
                int uid = ActivityThread.getPackageManager()
                        .getPackageUid(rec.key.packageName);
                if (uid != Binder.getCallingUid()) {
                    String msg = "Permission Denial: cancelIntentSender() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " is not allowed to cancel packges "
                        + rec.key.packageName;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            } catch (RemoteException e) {
                throw new SecurityException(e);
            }
            cancelIntentSenderLocked(rec, true);
        }
    }

    void cancelIntentSenderLocked(PendingIntentRecord rec, boolean cleanActivity) {
        rec.canceled = true;
        mIntentSenderRecords.remove(rec.key);
        if (cleanActivity && rec.key.activity != null) {
            rec.key.activity.pendingResults.remove(rec.ref);
        }
    }

    public String getPackageForIntentSender(IIntentSender pendingResult) {
        if (!(pendingResult instanceof PendingIntentRecord)) {
            return null;
        }
        try {
            PendingIntentRecord res = (PendingIntentRecord)pendingResult;
            return res.key.packageName;
        } catch (ClassCastException e) {
        }
        return null;
    }

    public void setProcessLimit(int max) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessLimit()");
        mProcessLimit = max;
    }

    public int getProcessLimit() {
        return mProcessLimit;
    }

    void foregroundTokenDied(ForegroundToken token) {
        synchronized (ActivityManagerService.this) {
            synchronized (mPidsSelfLocked) {
                ForegroundToken cur
                    = mForegroundProcesses.get(token.pid);
                if (cur != token) {
                    return;
                }
                mForegroundProcesses.remove(token.pid);
                ProcessRecord pr = mPidsSelfLocked.get(token.pid);
                if (pr == null) {
                    return;
                }
                pr.forcingToForeground = null;
                pr.foregroundServices = false;
            }
            updateOomAdjLocked();
        }
    }
    
    public void setProcessForeground(IBinder token, int pid, boolean isForeground) {
        enforceCallingPermission(android.Manifest.permission.SET_PROCESS_LIMIT,
                "setProcessForeground()");
        synchronized(this) {
            boolean changed = false;
            
            synchronized (mPidsSelfLocked) {
                ProcessRecord pr = mPidsSelfLocked.get(pid);
                if (pr == null) {
                    Slog.w(TAG, "setProcessForeground called on unknown pid: " + pid);
                    return;
                }
                ForegroundToken oldToken = mForegroundProcesses.get(pid);
                if (oldToken != null) {
                    oldToken.token.unlinkToDeath(oldToken, 0);
                    mForegroundProcesses.remove(pid);
                    pr.forcingToForeground = null;
                    changed = true;
                }
                if (isForeground && token != null) {
                    ForegroundToken newToken = new ForegroundToken() {
                        public void binderDied() {
                            foregroundTokenDied(this);
                        }
                    };
                    newToken.pid = pid;
                    newToken.token = token;
                    try {
                        token.linkToDeath(newToken, 0);
                        mForegroundProcesses.put(pid, newToken);
                        pr.forcingToForeground = token;
                        changed = true;
                    } catch (RemoteException e) {
                        // If the process died while doing this, we will later
                        // do the cleanup with the process death link.
                    }
                }
            }
            
            if (changed) {
                updateOomAdjLocked();
            }
        }
    }
    
    // =========================================================
    // PERMISSIONS
    // =========================================================

    static class PermissionController extends IPermissionController.Stub {
        ActivityManagerService mActivityManagerService;
        PermissionController(ActivityManagerService activityManagerService) {
            mActivityManagerService = activityManagerService;
        }

        public boolean checkPermission(String permission, int pid, int uid) {
            return mActivityManagerService.checkPermission(permission, pid,
                    uid) == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * This can be called with or without the global lock held.
     */
    int checkComponentPermission(String permission, int pid, int uid,
            int reqUid) {
        // We might be performing an operation on behalf of an indirect binder
        // invocation, e.g. via {@link #openContentUri}.  Check and adjust the
        // client identity accordingly before proceeding.
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null) {
            Slog.d(TAG, "checkComponentPermission() adjusting {pid,uid} to {"
                    + tlsIdentity.pid + "," + tlsIdentity.uid + "}");
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }

        // Root, system server and our own process get to do everything.
        if (uid == 0 || uid == Process.SYSTEM_UID || pid == MY_PID ||
            !Process.supportsProcesses()) {
            return PackageManager.PERMISSION_GRANTED;
        }
        // If the target requires a specific UID, always fail for others.
        if (reqUid >= 0 && uid != reqUid) {
            Slog.w(TAG, "Permission denied: checkComponentPermission() reqUid=" + reqUid);
            return PackageManager.PERMISSION_DENIED;
        }
        if (permission == null) {
            return PackageManager.PERMISSION_GRANTED;
        }
        try {
            return ActivityThread.getPackageManager()
                    .checkUidPermission(permission, uid);
        } catch (RemoteException e) {
            // Should never happen, but if it does... deny!
            Slog.e(TAG, "PackageManager is dead?!?", e);
        }
        return PackageManager.PERMISSION_DENIED;
    }

    /**
     * As the only public entry point for permissions checking, this method
     * can enforce the semantic that requesting a check on a null global
     * permission is automatically denied.  (Internally a null permission
     * string is used when calling {@link #checkComponentPermission} in cases
     * when only uid-based security is needed.)
     * 
     * This can be called with or without the global lock held.
     */
    public int checkPermission(String permission, int pid, int uid) {
        if (permission == null) {
            return PackageManager.PERMISSION_DENIED;
        }
        return checkComponentPermission(permission, pid, uid, -1);
    }

    /**
     * Binder IPC calls go through the public entry point.
     * This can be called with or without the global lock held.
     */
    int checkCallingPermission(String permission) {
        return checkPermission(permission,
                Binder.getCallingPid(),
                Binder.getCallingUid());
    }

    /**
     * This can be called with or without the global lock held.
     */
    void enforceCallingPermission(String permission, String func) {
        if (checkCallingPermission(permission)
                == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        String msg = "Permission Denial: " + func + " from pid="
                + Binder.getCallingPid()
                + ", uid=" + Binder.getCallingUid()
                + " requires " + permission;
        Slog.w(TAG, msg);
        throw new SecurityException(msg);
    }

    private final boolean checkHoldingPermissionsLocked(IPackageManager pm,
            ProviderInfo pi, int uid, int modeFlags) {
        try {
            if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                if ((pi.readPermission != null) &&
                        (pm.checkUidPermission(pi.readPermission, uid)
                                != PackageManager.PERMISSION_GRANTED)) {
                    return false;
                }
            }
            if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
                if ((pi.writePermission != null) &&
                        (pm.checkUidPermission(pi.writePermission, uid)
                                != PackageManager.PERMISSION_GRANTED)) {
                    return false;
                }
            }
            return true;
        } catch (RemoteException e) {
            return false;
        }
    }

    private final boolean checkUriPermissionLocked(Uri uri, int uid,
            int modeFlags) {
        // Root gets to do everything.
        if (uid == 0 || !Process.supportsProcesses()) {
            return true;
        }
        HashMap<Uri, UriPermission> perms = mGrantedUriPermissions.get(uid);
        if (perms == null) return false;
        UriPermission perm = perms.get(uri);
        if (perm == null) return false;
        return (modeFlags&perm.modeFlags) == modeFlags;
    }

    public int checkUriPermission(Uri uri, int pid, int uid, int modeFlags) {
        // Another redirected-binder-call permissions check as in
        // {@link checkComponentPermission}.
        Identity tlsIdentity = sCallerIdentity.get();
        if (tlsIdentity != null) {
            uid = tlsIdentity.uid;
            pid = tlsIdentity.pid;
        }

        // Our own process gets to do everything.
        if (pid == MY_PID) {
            return PackageManager.PERMISSION_GRANTED;
        }
        synchronized(this) {
            return checkUriPermissionLocked(uri, uid, modeFlags)
                    ? PackageManager.PERMISSION_GRANTED
                    : PackageManager.PERMISSION_DENIED;
        }
    }

    private void grantUriPermissionLocked(int callingUid,
            String targetPkg, Uri uri, int modeFlags, HistoryRecord activity) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modeFlags == 0) {
            return;
        }

        if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                "Requested grant " + targetPkg + " permission to " + uri);
        
        final IPackageManager pm = ActivityThread.getPackageManager();

        // If this is not a content: uri, we can't do anything with it.
        if (!ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                    "Can't grant URI permission for non-content URI: " + uri);
            return;
        }

        String name = uri.getAuthority();
        ProviderInfo pi = null;
        ContentProviderRecord cpr
                = (ContentProviderRecord)mProvidersByName.get(name);
        if (cpr != null) {
            pi = cpr.info;
        } else {
            try {
                pi = pm.resolveContentProvider(name,
                        PackageManager.GET_URI_PERMISSION_PATTERNS);
            } catch (RemoteException ex) {
            }
        }
        if (pi == null) {
            Slog.w(TAG, "No content provider found for: " + name);
            return;
        }

        int targetUid;
        try {
            targetUid = pm.getPackageUid(targetPkg);
            if (targetUid < 0) {
                if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                        "Can't grant URI permission no uid for: " + targetPkg);
                return;
            }
        } catch (RemoteException ex) {
            return;
        }

        // First...  does the target actually need this permission?
        if (checkHoldingPermissionsLocked(pm, pi, targetUid, modeFlags)) {
            // No need to grant the target this permission.
            if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                    "Target " + targetPkg + " already has full permission to " + uri);
            return;
        }

        // Second...  is the provider allowing granting of URI permissions?
        if (!pi.grantUriPermissions) {
            throw new SecurityException("Provider " + pi.packageName
                    + "/" + pi.name
                    + " does not allow granting of Uri permissions (uri "
                    + uri + ")");
        }
        if (pi.uriPermissionPatterns != null) {
            final int N = pi.uriPermissionPatterns.length;
            boolean allowed = false;
            for (int i=0; i<N; i++) {
                if (pi.uriPermissionPatterns[i] != null
                        && pi.uriPermissionPatterns[i].match(uri.getPath())) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new SecurityException("Provider " + pi.packageName
                        + "/" + pi.name
                        + " does not allow granting of permission to path of Uri "
                        + uri);
            }
        }

        // Third...  does the caller itself have permission to access
        // this uri?
        if (!checkHoldingPermissionsLocked(pm, pi, callingUid, modeFlags)) {
            if (!checkUriPermissionLocked(uri, callingUid, modeFlags)) {
                throw new SecurityException("Uid " + callingUid
                        + " does not have permission to uri " + uri);
            }
        }

        // Okay!  So here we are: the caller has the assumed permission
        // to the uri, and the target doesn't.  Let's now give this to
        // the target.

        if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                "Granting " + targetPkg + " permission to " + uri);
        
        HashMap<Uri, UriPermission> targetUris
                = mGrantedUriPermissions.get(targetUid);
        if (targetUris == null) {
            targetUris = new HashMap<Uri, UriPermission>();
            mGrantedUriPermissions.put(targetUid, targetUris);
        }

        UriPermission perm = targetUris.get(uri);
        if (perm == null) {
            perm = new UriPermission(targetUid, uri);
            targetUris.put(uri, perm);

        }
        perm.modeFlags |= modeFlags;
        if (activity == null) {
            perm.globalModeFlags |= modeFlags;
        } else if ((modeFlags&Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
            perm.readActivities.add(activity);
            if (activity.readUriPermissions == null) {
                activity.readUriPermissions = new HashSet<UriPermission>();
            }
            activity.readUriPermissions.add(perm);
        } else if ((modeFlags&Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0) {
            perm.writeActivities.add(activity);
            if (activity.writeUriPermissions == null) {
                activity.writeUriPermissions = new HashSet<UriPermission>();
            }
            activity.writeUriPermissions.add(perm);
        }
    }

    private void grantUriPermissionFromIntentLocked(int callingUid,
            String targetPkg, Intent intent, HistoryRecord activity) {
        if (intent == null) {
            return;
        }
        Uri data = intent.getData();
        if (data == null) {
            return;
        }
        grantUriPermissionLocked(callingUid, targetPkg, data,
                intent.getFlags(), activity);
    }

    public void grantUriPermission(IApplicationThread caller, String targetPkg,
            Uri uri, int modeFlags) {
        synchronized(this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException("Unable to find app for caller "
                        + caller
                        + " when granting permission to uri " + uri);
            }
            if (targetPkg == null) {
                Slog.w(TAG, "grantUriPermission: null target");
                return;
            }
            if (uri == null) {
                Slog.w(TAG, "grantUriPermission: null uri");
                return;
            }

            grantUriPermissionLocked(r.info.uid, targetPkg, uri, modeFlags,
                    null);
        }
    }

    private void removeUriPermissionIfNeededLocked(UriPermission perm) {
        if ((perm.modeFlags&(Intent.FLAG_GRANT_READ_URI_PERMISSION
                |Intent.FLAG_GRANT_WRITE_URI_PERMISSION)) == 0) {
            HashMap<Uri, UriPermission> perms
                    = mGrantedUriPermissions.get(perm.uid);
            if (perms != null) {
                if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                        "Removing " + perm.uid + " permission to " + perm.uri);
                perms.remove(perm.uri);
                if (perms.size() == 0) {
                    mGrantedUriPermissions.remove(perm.uid);
                }
            }
        }
    }

    private void removeActivityUriPermissionsLocked(HistoryRecord activity) {
        if (activity.readUriPermissions != null) {
            for (UriPermission perm : activity.readUriPermissions) {
                perm.readActivities.remove(activity);
                if (perm.readActivities.size() == 0 && (perm.globalModeFlags
                        &Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) {
                    perm.modeFlags &= ~Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    removeUriPermissionIfNeededLocked(perm);
                }
            }
        }
        if (activity.writeUriPermissions != null) {
            for (UriPermission perm : activity.writeUriPermissions) {
                perm.writeActivities.remove(activity);
                if (perm.writeActivities.size() == 0 && (perm.globalModeFlags
                        &Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) {
                    perm.modeFlags &= ~Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                    removeUriPermissionIfNeededLocked(perm);
                }
            }
        }
    }

    private void revokeUriPermissionLocked(int callingUid, Uri uri,
            int modeFlags) {
        modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modeFlags == 0) {
            return;
        }

        if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                "Revoking all granted permissions to " + uri);
        
        final IPackageManager pm = ActivityThread.getPackageManager();

        final String authority = uri.getAuthority();
        ProviderInfo pi = null;
        ContentProviderRecord cpr
                = (ContentProviderRecord)mProvidersByName.get(authority);
        if (cpr != null) {
            pi = cpr.info;
        } else {
            try {
                pi = pm.resolveContentProvider(authority,
                        PackageManager.GET_URI_PERMISSION_PATTERNS);
            } catch (RemoteException ex) {
            }
        }
        if (pi == null) {
            Slog.w(TAG, "No content provider found for: " + authority);
            return;
        }

        // Does the caller have this permission on the URI?
        if (!checkHoldingPermissionsLocked(pm, pi, callingUid, modeFlags)) {
            // Right now, if you are not the original owner of the permission,
            // you are not allowed to revoke it.
            //if (!checkUriPermissionLocked(uri, callingUid, modeFlags)) {
                throw new SecurityException("Uid " + callingUid
                        + " does not have permission to uri " + uri);
            //}
        }

        // Go through all of the permissions and remove any that match.
        final List<String> SEGMENTS = uri.getPathSegments();
        if (SEGMENTS != null) {
            final int NS = SEGMENTS.size();
            int N = mGrantedUriPermissions.size();
            for (int i=0; i<N; i++) {
                HashMap<Uri, UriPermission> perms
                        = mGrantedUriPermissions.valueAt(i);
                Iterator<UriPermission> it = perms.values().iterator();
            toploop:
                while (it.hasNext()) {
                    UriPermission perm = it.next();
                    Uri targetUri = perm.uri;
                    if (!authority.equals(targetUri.getAuthority())) {
                        continue;
                    }
                    List<String> targetSegments = targetUri.getPathSegments();
                    if (targetSegments == null) {
                        continue;
                    }
                    if (targetSegments.size() < NS) {
                        continue;
                    }
                    for (int j=0; j<NS; j++) {
                        if (!SEGMENTS.get(j).equals(targetSegments.get(j))) {
                            continue toploop;
                        }
                    }
                    if (DEBUG_URI_PERMISSION) Slog.v(TAG, 
                            "Revoking " + perm.uid + " permission to " + perm.uri);
                    perm.clearModes(modeFlags);
                    if (perm.modeFlags == 0) {
                        it.remove();
                    }
                }
                if (perms.size() == 0) {
                    mGrantedUriPermissions.remove(
                            mGrantedUriPermissions.keyAt(i));
                    N--;
                    i--;
                }
            }
        }
    }

    public void revokeUriPermission(IApplicationThread caller, Uri uri,
            int modeFlags) {
        synchronized(this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException("Unable to find app for caller "
                        + caller
                        + " when revoking permission to uri " + uri);
            }
            if (uri == null) {
                Slog.w(TAG, "revokeUriPermission: null uri");
                return;
            }

            modeFlags &= (Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (modeFlags == 0) {
                return;
            }

            final IPackageManager pm = ActivityThread.getPackageManager();

            final String authority = uri.getAuthority();
            ProviderInfo pi = null;
            ContentProviderRecord cpr
                    = (ContentProviderRecord)mProvidersByName.get(authority);
            if (cpr != null) {
                pi = cpr.info;
            } else {
                try {
                    pi = pm.resolveContentProvider(authority,
                            PackageManager.GET_URI_PERMISSION_PATTERNS);
                } catch (RemoteException ex) {
                }
            }
            if (pi == null) {
                Slog.w(TAG, "No content provider found for: " + authority);
                return;
            }

            revokeUriPermissionLocked(r.info.uid, uri, modeFlags);
        }
    }

    public void showWaitingForDebugger(IApplicationThread who, boolean waiting) {
        synchronized (this) {
            ProcessRecord app =
                who != null ? getRecordForAppLocked(who) : null;
            if (app == null) return;

            Message msg = Message.obtain();
            msg.what = WAIT_FOR_DEBUGGER_MSG;
            msg.obj = app;
            msg.arg1 = waiting ? 1 : 0;
            mHandler.sendMessage(msg);
        }
    }

    public void getMemoryInfo(ActivityManager.MemoryInfo outInfo) {
        outInfo.availMem = Process.getFreeMemory();
        outInfo.threshold = HOME_APP_MEM;
        outInfo.lowMemory = outInfo.availMem <
                (HOME_APP_MEM + ((HIDDEN_APP_MEM-HOME_APP_MEM)/2));
    }
    
    // =========================================================
    // TASK MANAGEMENT
    // =========================================================

    public List getTasks(int maxNum, int flags,
                         IThumbnailReceiver receiver) {
        ArrayList list = new ArrayList();

        PendingThumbnailsRecord pending = null;
        IApplicationThread topThumbnail = null;
        HistoryRecord topRecord = null;

        synchronized(this) {
            if (localLOGV) Slog.v(
                TAG, "getTasks: max=" + maxNum + ", flags=" + flags
                + ", receiver=" + receiver);

            if (checkCallingPermission(android.Manifest.permission.GET_TASKS)
                    != PackageManager.PERMISSION_GRANTED) {
                if (receiver != null) {
                    // If the caller wants to wait for pending thumbnails,
                    // it ain't gonna get them.
                    try {
                        receiver.finished();
                    } catch (RemoteException ex) {
                    }
                }
                String msg = "Permission Denial: getTasks() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + android.Manifest.permission.GET_TASKS;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }

            int pos = mHistory.size()-1;
            HistoryRecord next =
                pos >= 0 ? (HistoryRecord)mHistory.get(pos) : null;
            HistoryRecord top = null;
            CharSequence topDescription = null;
            TaskRecord curTask = null;
            int numActivities = 0;
            int numRunning = 0;
            while (pos >= 0 && maxNum > 0) {
                final HistoryRecord r = next;
                pos--;
                next = pos >= 0 ? (HistoryRecord)mHistory.get(pos) : null;

                // Initialize state for next task if needed.
                if (top == null ||
                        (top.state == ActivityState.INITIALIZING
                            && top.task == r.task)) {
                    top = r;
                    topDescription = r.description;
                    curTask = r.task;
                    numActivities = numRunning = 0;
                }

                // Add 'r' into the current task.
                numActivities++;
                if (r.app != null && r.app.thread != null) {
                    numRunning++;
                }
                if (topDescription == null) {
                    topDescription = r.description;
                }

                if (localLOGV) Slog.v(
                    TAG, r.intent.getComponent().flattenToShortString()
                    + ": task=" + r.task);

                // If the next one is a different task, generate a new
                // TaskInfo entry for what we have.
                if (next == null || next.task != curTask) {
                    ActivityManager.RunningTaskInfo ci
                            = new ActivityManager.RunningTaskInfo();
                    ci.id = curTask.taskId;
                    ci.baseActivity = r.intent.getComponent();
                    ci.topActivity = top.intent.getComponent();
                    ci.thumbnail = top.thumbnail;
                    ci.description = topDescription;
                    ci.numActivities = numActivities;
                    ci.numRunning = numRunning;
                    //System.out.println(
                    //    "#" + maxNum + ": " + " descr=" + ci.description);
                    if (ci.thumbnail == null && receiver != null) {
                        if (localLOGV) Slog.v(
                            TAG, "State=" + top.state + "Idle=" + top.idle
                            + " app=" + top.app
                            + " thr=" + (top.app != null ? top.app.thread : null));
                        if (top.state == ActivityState.RESUMED
                                || top.state == ActivityState.PAUSING) {
                            if (top.idle && top.app != null
                                && top.app.thread != null) {
                                topRecord = top;
                                topThumbnail = top.app.thread;
                            } else {
                                top.thumbnailNeeded = true;
                            }
                        }
                        if (pending == null) {
                            pending = new PendingThumbnailsRecord(receiver);
                        }
                        pending.pendingRecords.add(top);
                    }
                    list.add(ci);
                    maxNum--;
                    top = null;
                }
            }

            if (pending != null) {
                mPendingThumbnails.add(pending);
            }
        }

        if (localLOGV) Slog.v(TAG, "We have pending thumbnails: " + pending);

        if (topThumbnail != null) {
            if (localLOGV) Slog.v(TAG, "Requesting top thumbnail");
            try {
                topThumbnail.requestThumbnail(topRecord);
            } catch (Exception e) {
                Slog.w(TAG, "Exception thrown when requesting thumbnail", e);
                sendPendingThumbnail(null, topRecord, null, null, true);
            }
        }

        if (pending == null && receiver != null) {
            // In this case all thumbnails were available and the client
            // is being asked to be told when the remaining ones come in...
            // which is unusually, since the top-most currently running
            // activity should never have a canned thumbnail!  Oh well.
            try {
                receiver.finished();
            } catch (RemoteException ex) {
            }
        }

        return list;
    }

    public List<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum,
            int flags) {
        synchronized (this) {
            enforceCallingPermission(android.Manifest.permission.GET_TASKS,
                    "getRecentTasks()");

            IPackageManager pm = ActivityThread.getPackageManager();
            
            final int N = mRecentTasks.size();
            ArrayList<ActivityManager.RecentTaskInfo> res
                    = new ArrayList<ActivityManager.RecentTaskInfo>(
                            maxNum < N ? maxNum : N);
            for (int i=0; i<N && maxNum > 0; i++) {
                TaskRecord tr = mRecentTasks.get(i);
                if (((flags&ActivityManager.RECENT_WITH_EXCLUDED) != 0)
                        || (tr.intent == null)
                        || ((tr.intent.getFlags()
                                &Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) == 0)) {
                    ActivityManager.RecentTaskInfo rti
                            = new ActivityManager.RecentTaskInfo();
                    rti.id = tr.numActivities > 0 ? tr.taskId : -1;
                    rti.baseIntent = new Intent(
                            tr.intent != null ? tr.intent : tr.affinityIntent);
                    rti.origActivity = tr.origActivity;
                    
                    if ((flags&ActivityManager.RECENT_IGNORE_UNAVAILABLE) != 0) {
                        // Check whether this activity is currently available.
                        try {
                            if (rti.origActivity != null) {
                                if (pm.getActivityInfo(rti.origActivity, 0) == null) {
                                    continue;
                                }
                            } else if (rti.baseIntent != null) {
                                if (pm.queryIntentActivities(rti.baseIntent,
                                        null, 0) == null) {
                                    continue;
                                }
                            }
                        } catch (RemoteException e) {
                            // Will never happen.
                        }
                    }
                    
                    res.add(rti);
                    maxNum--;
                }
            }
            return res;
        }
    }

    private final int findAffinityTaskTopLocked(int startIndex, String affinity) {
        int j;
        TaskRecord startTask = ((HistoryRecord)mHistory.get(startIndex)).task; 
        TaskRecord jt = startTask;
        
        // First look backwards
        for (j=startIndex-1; j>=0; j--) {
            HistoryRecord r = (HistoryRecord)mHistory.get(j);
            if (r.task != jt) {
                jt = r.task;
                if (affinity.equals(jt.affinity)) {
                    return j;
                }
            }
        }
        
        // Now look forwards
        final int N = mHistory.size();
        jt = startTask;
        for (j=startIndex+1; j<N; j++) {
            HistoryRecord r = (HistoryRecord)mHistory.get(j);
            if (r.task != jt) {
                if (affinity.equals(jt.affinity)) {
                    return j;
                }
                jt = r.task;
            }
        }
        
        // Might it be at the top?
        if (affinity.equals(((HistoryRecord)mHistory.get(N-1)).task.affinity)) {
            return N-1;
        }
        
        return -1;
    }
    
    /**
     * Perform a reset of the given task, if needed as part of launching it.
     * Returns the new HistoryRecord at the top of the task.
     */
    private final HistoryRecord resetTaskIfNeededLocked(HistoryRecord taskTop,
            HistoryRecord newActivity) {
        boolean forceReset = (newActivity.info.flags
                &ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0;
        if (taskTop.task.getInactiveDuration() > ACTIVITY_INACTIVE_RESET_TIME) {
            if ((newActivity.info.flags
                    &ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE) == 0) {
                forceReset = true;
            }
        }
        
        final TaskRecord task = taskTop.task;
        
        // We are going to move through the history list so that we can look
        // at each activity 'target' with 'below' either the interesting
        // activity immediately below it in the stack or null.
        HistoryRecord target = null;
        int targetI = 0;
        int taskTopI = -1;
        int replyChainEnd = -1;
        int lastReparentPos = -1;
        for (int i=mHistory.size()-1; i>=-1; i--) {
            HistoryRecord below = i >= 0 ? (HistoryRecord)mHistory.get(i) : null;
            
            if (below != null && below.finishing) {
                continue;
            }
            if (target == null) {
                target = below;
                targetI = i;
                // If we were in the middle of a reply chain before this
                // task, it doesn't appear like the root of the chain wants
                // anything interesting, so drop it.
                replyChainEnd = -1;
                continue;
            }
        
            final int flags = target.info.flags;
            
            final boolean finishOnTaskLaunch =
                (flags&ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH) != 0;
            final boolean allowTaskReparenting =
                (flags&ActivityInfo.FLAG_ALLOW_TASK_REPARENTING) != 0;
            
            if (target.task == task) {
                // We are inside of the task being reset...  we'll either
                // finish this activity, push it out for another task,
                // or leave it as-is.  We only do this
                // for activities that are not the root of the task (since
                // if we finish the root, we may no longer have the task!).
                if (taskTopI < 0) {
                    taskTopI = targetI;
                }
                if (below != null && below.task == task) {
                    final boolean clearWhenTaskReset =
                            (target.intent.getFlags()
                                    &Intent.FLAG_ACTIVITY_CLEAR_WHEN_TASK_RESET) != 0;
                    if (!finishOnTaskLaunch && !clearWhenTaskReset && target.resultTo != null) {
                        // If this activity is sending a reply to a previous
                        // activity, we can't do anything with it now until
                        // we reach the start of the reply chain.
                        // XXX note that we are assuming the result is always
                        // to the previous activity, which is almost always
                        // the case but we really shouldn't count on.
                        if (replyChainEnd < 0) {
                            replyChainEnd = targetI;
                        }
                    } else if (!finishOnTaskLaunch && !clearWhenTaskReset && allowTaskReparenting
                            && target.taskAffinity != null
                            && !target.taskAffinity.equals(task.affinity)) {
                        // If this activity has an affinity for another
                        // task, then we need to move it out of here.  We will
                        // move it as far out of the way as possible, to the
                        // bottom of the activity stack.  This also keeps it
                        // correctly ordered with any activities we previously
                        // moved.
                        HistoryRecord p = (HistoryRecord)mHistory.get(0);
                        if (target.taskAffinity != null
                                && target.taskAffinity.equals(p.task.affinity)) {
                            // If the activity currently at the bottom has the
                            // same task affinity as the one we are moving,
                            // then merge it into the same task.
                            target.task = p.task;
                            if (DEBUG_TASKS) Slog.v(TAG, "Start pushing activity " + target
                                    + " out to bottom task " + p.task);
                        } else {
                            mCurTask++;
                            if (mCurTask <= 0) {
                                mCurTask = 1;
                            }
                            target.task = new TaskRecord(mCurTask, target.info, null,
                                    (target.info.flags&ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH) != 0);
                            target.task.affinityIntent = target.intent;
                            if (DEBUG_TASKS) Slog.v(TAG, "Start pushing activity " + target
                                    + " out to new task " + target.task);
                        }
                        mWindowManager.setAppGroupId(target, task.taskId);
                        if (replyChainEnd < 0) {
                            replyChainEnd = targetI;
                        }
                        int dstPos = 0;
                        for (int srcPos=targetI; srcPos<=replyChainEnd; srcPos++) {
                            p = (HistoryRecord)mHistory.get(srcPos);
                            if (p.finishing) {
                                continue;
                            }
                            if (DEBUG_TASKS) Slog.v(TAG, "Pushing next activity " + p
                                    + " out to target's task " + target.task);
                            task.numActivities--;
                            p.task = target.task;
                            target.task.numActivities++;
                            mHistory.remove(srcPos);
                            mHistory.add(dstPos, p);
                            mWindowManager.moveAppToken(dstPos, p);
                            mWindowManager.setAppGroupId(p, p.task.taskId);
                            dstPos++;
                            if (VALIDATE_TOKENS) {
                                mWindowManager.validateAppTokens(mHistory);
                            }
                            i++;
                        }
                        if (taskTop == p) {
                            taskTop = below;
                        }
                        if (taskTopI == replyChainEnd) {
                            taskTopI = -1;
                        }
                        replyChainEnd = -1;
                        addRecentTaskLocked(target.task);
                    } else if (forceReset || finishOnTaskLaunch
                            || clearWhenTaskReset) {
                        // If the activity should just be removed -- either
                        // because it asks for it, or the task should be
                        // cleared -- then finish it and anything that is
                        // part of its reply chain.
                        if (clearWhenTaskReset) {
                            // In this case, we want to finish this activity
                            // and everything above it, so be sneaky and pretend
                            // like these are all in the reply chain.
                            replyChainEnd = targetI+1;
                            while (replyChainEnd < mHistory.size() &&
                                    ((HistoryRecord)mHistory.get(
                                                replyChainEnd)).task == task) {
                                replyChainEnd++;
                            }
                            replyChainEnd--;
                        } else if (replyChainEnd < 0) {
                            replyChainEnd = targetI;
                        }
                        HistoryRecord p = null;
                        for (int srcPos=targetI; srcPos<=replyChainEnd; srcPos++) {
                            p = (HistoryRecord)mHistory.get(srcPos);
                            if (p.finishing) {
                                continue;
                            }
                            if (finishActivityLocked(p, srcPos,
                                    Activity.RESULT_CANCELED, null, "reset")) {
                                replyChainEnd--;
                                srcPos--;
                            }
                        }
                        if (taskTop == p) {
                            taskTop = below;
                        }
                        if (taskTopI == replyChainEnd) {
                            taskTopI = -1;
                        }
                        replyChainEnd = -1;
                    } else {
                        // If we were in the middle of a chain, well the
                        // activity that started it all doesn't want anything
                        // special, so leave it all as-is.
                        replyChainEnd = -1;
                    }
                } else {
                    // Reached the bottom of the task -- any reply chain
                    // should be left as-is.
                    replyChainEnd = -1;
                }
                
            } else if (target.resultTo != null) {
                // If this activity is sending a reply to a previous
                // activity, we can't do anything with it now until
                // we reach the start of the reply chain.
                // XXX note that we are assuming the result is always
                // to the previous activity, which is almost always
                // the case but we really shouldn't count on.
                if (replyChainEnd < 0) {
                    replyChainEnd = targetI;
                }

            } else if (taskTopI >= 0 && allowTaskReparenting
                    && task.affinity != null
                    && task.affinity.equals(target.taskAffinity)) {
                // We are inside of another task...  if this activity has
                // an affinity for our task, then either remove it if we are
                // clearing or move it over to our task.  Note that
                // we currently punt on the case where we are resetting a
                // task that is not at the top but who has activities above
                // with an affinity to it...  this is really not a normal
                // case, and we will need to later pull that task to the front
                // and usually at that point we will do the reset and pick
                // up those remaining activities.  (This only happens if
                // someone starts an activity in a new task from an activity
                // in a task that is not currently on top.)
                if (forceReset || finishOnTaskLaunch) {
                    if (replyChainEnd < 0) {
                        replyChainEnd = targetI;
                    }
                    HistoryRecord p = null;
                    for (int srcPos=targetI; srcPos<=replyChainEnd; srcPos++) {
                        p = (HistoryRecord)mHistory.get(srcPos);
                        if (p.finishing) {
                            continue;
                        }
                        if (finishActivityLocked(p, srcPos,
                                Activity.RESULT_CANCELED, null, "reset")) {
                            taskTopI--;
                            lastReparentPos--;
                            replyChainEnd--;
                            srcPos--;
                        }
                    }
                    replyChainEnd = -1;
                } else {
                    if (replyChainEnd < 0) {
                        replyChainEnd = targetI;
                    }
                    for (int srcPos=replyChainEnd; srcPos>=targetI; srcPos--) {
                        HistoryRecord p = (HistoryRecord)mHistory.get(srcPos);
                        if (p.finishing) {
                            continue;
                        }
                        if (lastReparentPos < 0) {
                            lastReparentPos = taskTopI;
                            taskTop = p;
                        } else {
                            lastReparentPos--;
                        }
                        mHistory.remove(srcPos);
                        p.task.numActivities--;
                        p.task = task;
                        mHistory.add(lastReparentPos, p);
                        if (DEBUG_TASKS) Slog.v(TAG, "Pulling activity " + p
                                + " in to resetting task " + task);
                        task.numActivities++;
                        mWindowManager.moveAppToken(lastReparentPos, p);
                        mWindowManager.setAppGroupId(p, p.task.taskId);
                        if (VALIDATE_TOKENS) {
                            mWindowManager.validateAppTokens(mHistory);
                        }
                    }
                    replyChainEnd = -1;
                    
                    // Now we've moved it in to place...  but what if this is
                    // a singleTop activity and we have put it on top of another
                    // instance of the same activity?  Then we drop the instance
                    // below so it remains singleTop.
                    if (target.info.launchMode == ActivityInfo.LAUNCH_SINGLE_TOP) {
                        for (int j=lastReparentPos-1; j>=0; j--) {
                            HistoryRecord p = (HistoryRecord)mHistory.get(j);
                            if (p.finishing) {
                                continue;
                            }
                            if (p.intent.getComponent().equals(target.intent.getComponent())) {
                                if (finishActivityLocked(p, j,
                                        Activity.RESULT_CANCELED, null, "replace")) {
                                    taskTopI--;
                                    lastReparentPos--;
                                }
                            }
                        }
                    }
                }
            }
            
            target = below;
            targetI = i;
        }
        
        return taskTop;
    }
    
    /**
     * TODO: Add mController hook
     */
    public void moveTaskToFront(int task) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToFront()");

        synchronized(this) {
            if (!checkAppSwitchAllowedLocked(Binder.getCallingPid(),
                    Binder.getCallingUid(), "Task to front")) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            try {
                int N = mRecentTasks.size();
                for (int i=0; i<N; i++) {
                    TaskRecord tr = mRecentTasks.get(i);
                    if (tr.taskId == task) {
                        moveTaskToFrontLocked(tr, null);
                        return;
                    }
                }
                for (int i=mHistory.size()-1; i>=0; i--) {
                    HistoryRecord hr = (HistoryRecord)mHistory.get(i);
                    if (hr.task.taskId == task) {
                        moveTaskToFrontLocked(hr.task, null);
                        return;
                    }
                }
            } finally {
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    private final void moveTaskToFrontLocked(TaskRecord tr, HistoryRecord reason) {
        if (DEBUG_SWITCH) Slog.v(TAG, "moveTaskToFront: " + tr);

        final int task = tr.taskId;
        int top = mHistory.size()-1;

        if (top < 0 || ((HistoryRecord)mHistory.get(top)).task.taskId == task) {
            // nothing to do!
            return;
        }

        ArrayList moved = new ArrayList();

        // Applying the affinities may have removed entries from the history,
        // so get the size again.
        top = mHistory.size()-1;
        int pos = top;

        // Shift all activities with this task up to the top
        // of the stack, keeping them in the same internal order.
        while (pos >= 0) {
            HistoryRecord r = (HistoryRecord)mHistory.get(pos);
            if (localLOGV) Slog.v(
                TAG, "At " + pos + " ckp " + r.task + ": " + r);
            boolean first = true;
            if (r.task.taskId == task) {
                if (localLOGV) Slog.v(TAG, "Removing and adding at " + top);
                mHistory.remove(pos);
                mHistory.add(top, r);
                moved.add(0, r);
                top--;
                if (first) {
                    addRecentTaskLocked(r.task);
                    first = false;
                }
            }
            pos--;
        }

        if (DEBUG_TRANSITION) Slog.v(TAG,
                "Prepare to front transition: task=" + tr);
        if (reason != null &&
                (reason.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
            mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_NONE);
            HistoryRecord r = topRunningActivityLocked(null);
            if (r != null) {
                mNoAnimActivities.add(r);
            }
        } else {
            mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_TASK_TO_FRONT);
        }
        
        mWindowManager.moveAppTokensToTop(moved);
        if (VALIDATE_TOKENS) {
            mWindowManager.validateAppTokens(mHistory);
        }

        finishTaskMoveLocked(task);
        EventLog.writeEvent(EventLogTags.AM_TASK_TO_FRONT, task);
    }

    private final void finishTaskMoveLocked(int task) {
        resumeTopActivityLocked(null);
    }

    public void moveTaskToBack(int task) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskToBack()");

        synchronized(this) {
            if (mResumedActivity != null && mResumedActivity.task.taskId == task) {
                if (!checkAppSwitchAllowedLocked(Binder.getCallingPid(),
                        Binder.getCallingUid(), "Task to back")) {
                    return;
                }
            }
            final long origId = Binder.clearCallingIdentity();
            moveTaskToBackLocked(task, null);
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Moves an activity, and all of the other activities within the same task, to the bottom
     * of the history stack.  The activity's order within the task is unchanged.
     * 
     * @param token A reference to the activity we wish to move
     * @param nonRoot If false then this only works if the activity is the root
     *                of a task; if true it will work for any activity in a task.
     * @return Returns true if the move completed, false if not.
     */
    public boolean moveActivityTaskToBack(IBinder token, boolean nonRoot) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            int taskId = getTaskForActivityLocked(token, !nonRoot);
            if (taskId >= 0) {
                return moveTaskToBackLocked(taskId, null);
            }
            Binder.restoreCallingIdentity(origId);
        }
        return false;
    }

    /**
     * Worker method for rearranging history stack.  Implements the function of moving all 
     * activities for a specific task (gathering them if disjoint) into a single group at the 
     * bottom of the stack.
     * 
     * If a watcher is installed, the action is preflighted and the watcher has an opportunity
     * to premeptively cancel the move.
     * 
     * @param task The taskId to collect and move to the bottom.
     * @return Returns true if the move completed, false if not.
     */
    private final boolean moveTaskToBackLocked(int task, HistoryRecord reason) {
        Slog.i(TAG, "moveTaskToBack: " + task);
        
        // If we have a watcher, preflight the move before committing to it.  First check
        // for *other* available tasks, but if none are available, then try again allowing the
        // current task to be selected.
        if (mController != null) {
            HistoryRecord next = topRunningActivityLocked(null, task);
            if (next == null) {
                next = topRunningActivityLocked(null, 0);
            }
            if (next != null) {
                // ask watcher if this is allowed
                boolean moveOK = true;
                try {
                    moveOK = mController.activityResuming(next.packageName);
                } catch (RemoteException e) {
                    mController = null;
                }
                if (!moveOK) {
                    return false;
                }
            }
        }

        ArrayList moved = new ArrayList();

        if (DEBUG_TRANSITION) Slog.v(TAG,
                "Prepare to back transition: task=" + task);
        
        final int N = mHistory.size();
        int bottom = 0;
        int pos = 0;

        // Shift all activities with this task down to the bottom
        // of the stack, keeping them in the same internal order.
        while (pos < N) {
            HistoryRecord r = (HistoryRecord)mHistory.get(pos);
            if (localLOGV) Slog.v(
                TAG, "At " + pos + " ckp " + r.task + ": " + r);
            if (r.task.taskId == task) {
                if (localLOGV) Slog.v(TAG, "Removing and adding at " + (N-1));
                mHistory.remove(pos);
                mHistory.add(bottom, r);
                moved.add(r);
                bottom++;
            }
            pos++;
        }

        if (reason != null &&
                (reason.intent.getFlags()&Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0) {
            mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_NONE);
            HistoryRecord r = topRunningActivityLocked(null);
            if (r != null) {
                mNoAnimActivities.add(r);
            }
        } else {
            mWindowManager.prepareAppTransition(WindowManagerPolicy.TRANSIT_TASK_TO_BACK);
        }
        mWindowManager.moveAppTokensToBottom(moved);
        if (VALIDATE_TOKENS) {
            mWindowManager.validateAppTokens(mHistory);
        }

        finishTaskMoveLocked(task);
        return true;
    }

    public void moveTaskBackwards(int task) {
        enforceCallingPermission(android.Manifest.permission.REORDER_TASKS,
                "moveTaskBackwards()");

        synchronized(this) {
            if (!checkAppSwitchAllowedLocked(Binder.getCallingPid(),
                    Binder.getCallingUid(), "Task backwards")) {
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            moveTaskBackwardsLocked(task);
            Binder.restoreCallingIdentity(origId);
        }
    }

    private final void moveTaskBackwardsLocked(int task) {
        Slog.e(TAG, "moveTaskBackwards not yet implemented!");
    }

    public int getTaskForActivity(IBinder token, boolean onlyRoot) {
        synchronized(this) {
            return getTaskForActivityLocked(token, onlyRoot);
        }
    }

    int getTaskForActivityLocked(IBinder token, boolean onlyRoot) {
        final int N = mHistory.size();
        TaskRecord lastTask = null;
        for (int i=0; i<N; i++) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (r == token) {
                if (!onlyRoot || lastTask != r.task) {
                    return r.task.taskId;
                }
                return -1;
            }
            lastTask = r.task;
        }

        return -1;
    }

    /**
     * Returns the top activity in any existing task matching the given
     * Intent.  Returns null if no such task is found.
     */
    private HistoryRecord findTaskLocked(Intent intent, ActivityInfo info) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }

        TaskRecord cp = null;

        final int N = mHistory.size();
        for (int i=(N-1); i>=0; i--) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (!r.finishing && r.task != cp
                    && r.launchMode != ActivityInfo.LAUNCH_SINGLE_INSTANCE) {
                cp = r.task;
                //Slog.i(TAG, "Comparing existing cls=" + r.task.intent.getComponent().flattenToShortString()
                //        + "/aff=" + r.task.affinity + " to new cls="
                //        + intent.getComponent().flattenToShortString() + "/aff=" + taskAffinity);
                if (r.task.affinity != null) {
                    if (r.task.affinity.equals(info.taskAffinity)) {
                        //Slog.i(TAG, "Found matching affinity!");
                        return r;
                    }
                } else if (r.task.intent != null
                        && r.task.intent.getComponent().equals(cls)) {
                    //Slog.i(TAG, "Found matching class!");
                    //dump();
                    //Slog.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                    return r;
                } else if (r.task.affinityIntent != null
                        && r.task.affinityIntent.getComponent().equals(cls)) {
                    //Slog.i(TAG, "Found matching class!");
                    //dump();
                    //Slog.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                    return r;
                }
            }
        }

        return null;
    }

    /**
     * Returns the first activity (starting from the top of the stack) that
     * is the same as the given activity.  Returns null if no such activity
     * is found.
     */
    private HistoryRecord findActivityLocked(Intent intent, ActivityInfo info) {
        ComponentName cls = intent.getComponent();
        if (info.targetActivity != null) {
            cls = new ComponentName(info.packageName, info.targetActivity);
        }

        final int N = mHistory.size();
        for (int i=(N-1); i>=0; i--) {
            HistoryRecord r = (HistoryRecord)mHistory.get(i);
            if (!r.finishing) {
                if (r.intent.getComponent().equals(cls)) {
                    //Slog.i(TAG, "Found matching class!");
                    //dump();
                    //Slog.i(TAG, "For Intent " + intent + " bringing to top: " + r.intent);
                    return r;
                }
            }
        }

        return null;
    }

    public void finishOtherInstances(IBinder token, ComponentName className) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();

            int N = mHistory.size();
            TaskRecord lastTask = null;
            for (int i=0; i<N; i++) {
                HistoryRecord r = (HistoryRecord)mHistory.get(i);
                if (r.realActivity.equals(className)
                        && r != token && lastTask != r.task) {
                    if (finishActivityLocked(r, i, Activity.RESULT_CANCELED,
                            null, "others")) {
                        i--;
                        N--;
                    }
                }
                lastTask = r.task;
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    // =========================================================
    // THUMBNAILS
    // =========================================================

    public void reportThumbnail(IBinder token,
            Bitmap thumbnail, CharSequence description) {
        //System.out.println("Report thumbnail for " + token + ": " + thumbnail);
        final long origId = Binder.clearCallingIdentity();
        sendPendingThumbnail(null, token, thumbnail, description, true);
        Binder.restoreCallingIdentity(origId);
    }

    final void sendPendingThumbnail(HistoryRecord r, IBinder token,
            Bitmap thumbnail, CharSequence description, boolean always) {
        TaskRecord task = null;
        ArrayList receivers = null;

        //System.out.println("Send pending thumbnail: " + r);

        synchronized(this) {
            if (r == null) {
                int index = indexOfTokenLocked(token);
                if (index < 0) {
                    return;
                }
                r = (HistoryRecord)mHistory.get(index);
            }
            if (thumbnail == null) {
                thumbnail = r.thumbnail;
                description = r.description;
            }
            if (thumbnail == null && !always) {
                // If there is no thumbnail, and this entry is not actually
                // going away, then abort for now and pick up the next
                // thumbnail we get.
                return;
            }
            task = r.task;

            int N = mPendingThumbnails.size();
            int i=0;
            while (i<N) {
                PendingThumbnailsRecord pr =
                    (PendingThumbnailsRecord)mPendingThumbnails.get(i);
                //System.out.println("Looking in " + pr.pendingRecords);
                if (pr.pendingRecords.remove(r)) {
                    if (receivers == null) {
                        receivers = new ArrayList();
                    }
                    receivers.add(pr);
                    if (pr.pendingRecords.size() == 0) {
                        pr.finished = true;
                        mPendingThumbnails.remove(i);
                        N--;
                        continue;
                    }
                }
                i++;
            }
        }

        if (receivers != null) {
            final int N = receivers.size();
            for (int i=0; i<N; i++) {
                try {
                    PendingThumbnailsRecord pr =
                        (PendingThumbnailsRecord)receivers.get(i);
                    pr.receiver.newThumbnail(
                        task != null ? task.taskId : -1, thumbnail, description);
                    if (pr.finished) {
                        pr.receiver.finished();
                    }
                } catch (Exception e) {
                    Slog.w(TAG, "Exception thrown when sending thumbnail", e);
                }
            }
        }
    }

    // =========================================================
    // CONTENT PROVIDERS
    // =========================================================

    private final List generateApplicationProvidersLocked(ProcessRecord app) {
        List providers = null;
        try {
            providers = ActivityThread.getPackageManager().
                queryContentProviders(app.processName, app.info.uid,
                        STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS);
        } catch (RemoteException ex) {
        }
        if (providers != null) {
            final int N = providers.size();
            for (int i=0; i<N; i++) {
                ProviderInfo cpi =
                    (ProviderInfo)providers.get(i);
                ContentProviderRecord cpr =
                    (ContentProviderRecord)mProvidersByClass.get(cpi.name);
                if (cpr == null) {
                    cpr = new ContentProviderRecord(cpi, app.info);
                    mProvidersByClass.put(cpi.name, cpr);
                }
                app.pubProviders.put(cpi.name, cpr);
                app.addPackage(cpi.applicationInfo.packageName);
                ensurePackageDexOpt(cpi.applicationInfo.packageName);
            }
        }
        return providers;
    }

    private final String checkContentProviderPermissionLocked(
            ProviderInfo cpi, ProcessRecord r, int mode) {
        final int callingPid = (r != null) ? r.pid : Binder.getCallingPid();
        final int callingUid = (r != null) ? r.info.uid : Binder.getCallingUid();
        if (checkComponentPermission(cpi.readPermission, callingPid, callingUid,
                cpi.exported ? -1 : cpi.applicationInfo.uid)
                == PackageManager.PERMISSION_GRANTED
                && mode == ParcelFileDescriptor.MODE_READ_ONLY || mode == -1) {
            return null;
        }
        if (checkComponentPermission(cpi.writePermission, callingPid, callingUid,
                cpi.exported ? -1 : cpi.applicationInfo.uid)
                == PackageManager.PERMISSION_GRANTED) {
            return null;
        }
        
        PathPermission[] pps = cpi.pathPermissions;
        if (pps != null) {
            int i = pps.length;
            while (i > 0) {
                i--;
                PathPermission pp = pps[i];
                if (checkComponentPermission(pp.getReadPermission(), callingPid, callingUid,
                        cpi.exported ? -1 : cpi.applicationInfo.uid)
                        == PackageManager.PERMISSION_GRANTED
                        && mode == ParcelFileDescriptor.MODE_READ_ONLY || mode == -1) {
                    return null;
                }
                if (checkComponentPermission(pp.getWritePermission(), callingPid, callingUid,
                        cpi.exported ? -1 : cpi.applicationInfo.uid)
                        == PackageManager.PERMISSION_GRANTED) {
                    return null;
                }
            }
        }
        
        String msg = "Permission Denial: opening provider " + cpi.name
                + " from " + (r != null ? r : "(null)") + " (pid=" + callingPid
                + ", uid=" + callingUid + ") requires "
                + cpi.readPermission + " or " + cpi.writePermission;
        Slog.w(TAG, msg);
        return msg;
    }

    private final ContentProviderHolder getContentProviderImpl(
        IApplicationThread caller, String name) {
        ContentProviderRecord cpr;
        ProviderInfo cpi = null;

        synchronized(this) {
            ProcessRecord r = null;
            if (caller != null) {
                r = getRecordForAppLocked(caller);
                if (r == null) {
                    throw new SecurityException(
                            "Unable to find app for caller " + caller
                          + " (pid=" + Binder.getCallingPid()
                          + ") when getting content provider " + name);
                }
            }

            // First check if this content provider has been published...
            cpr = (ContentProviderRecord)mProvidersByName.get(name);
            if (cpr != null) {
                cpi = cpr.info;
                if (checkContentProviderPermissionLocked(cpi, r, -1) != null) {
                    return new ContentProviderHolder(cpi,
                            cpi.readPermission != null
                                    ? cpi.readPermission : cpi.writePermission);
                }

                if (r != null && cpr.canRunHere(r)) {
                    // This provider has been published or is in the process
                    // of being published...  but it is also allowed to run
                    // in the caller's process, so don't make a connection
                    // and just let the caller instantiate its own instance.
                    if (cpr.provider != null) {
                        // don't give caller the provider object, it needs
                        // to make its own.
                        cpr = new ContentProviderRecord(cpr);
                    }
                    return cpr;
                }

                final long origId = Binder.clearCallingIdentity();

                // In this case the provider instance already exists, so we can
                // return it right away.
                if (r != null) {
                    if (DEBUG_PROVIDER) Slog.v(TAG,
                            "Adding provider requested by "
                            + r.processName + " from process "
                            + cpr.info.processName);
                    Integer cnt = r.conProviders.get(cpr);
                    if (cnt == null) {
                        r.conProviders.put(cpr, new Integer(1));
                    } else {
                        r.conProviders.put(cpr, new Integer(cnt.intValue()+1));
                    }
                    cpr.clients.add(r);
                    if (cpr.app != null && r.setAdj >= VISIBLE_APP_ADJ) {
                        // If this is a visible app accessing the provider,
                        // make sure to count it as being accessed and thus
                        // back up on the LRU list.  This is good because
                        // content providers are often expensive to start.
                        updateLruProcessLocked(cpr.app, false, true);
                    }
                } else {
                    cpr.externals++;
                }

                if (cpr.app != null) {
                    updateOomAdjLocked(cpr.app);
                }

                Binder.restoreCallingIdentity(origId);

            } else {
                try {
                    cpi = ActivityThread.getPackageManager().
                        resolveContentProvider(name,
                                STOCK_PM_FLAGS | PackageManager.GET_URI_PERMISSION_PATTERNS);
                } catch (RemoteException ex) {
                }
                if (cpi == null) {
                    return null;
                }

                if (checkContentProviderPermissionLocked(cpi, r, -1) != null) {
                    return new ContentProviderHolder(cpi,
                            cpi.readPermission != null
                                    ? cpi.readPermission : cpi.writePermission);
                }

                if (!mSystemReady && !mDidUpdate && !mWaitingUpdate
                        && !cpi.processName.equals("system")) {
                    // If this content provider does not run in the system
                    // process, and the system is not yet ready to run other
                    // processes, then fail fast instead of hanging.
                    throw new IllegalArgumentException(
                            "Attempt to launch content provider before system ready");
                }
                
                cpr = (ContentProviderRecord)mProvidersByClass.get(cpi.name);
                final boolean firstClass = cpr == null;
                if (firstClass) {
                    try {
                        ApplicationInfo ai =
                            ActivityThread.getPackageManager().
                                getApplicationInfo(
                                        cpi.applicationInfo.packageName,
                                        STOCK_PM_FLAGS);
                        if (ai == null) {
                            Slog.w(TAG, "No package info for content provider "
                                    + cpi.name);
                            return null;
                        }
                        cpr = new ContentProviderRecord(cpi, ai);
                    } catch (RemoteException ex) {
                        // pm is in same process, this will never happen.
                    }
                }

                if (r != null && cpr.canRunHere(r)) {
                    // If this is a multiprocess provider, then just return its
                    // info and allow the caller to instantiate it.  Only do
                    // this if the provider is the same user as the caller's
                    // process, or can run as root (so can be in any process).
                    return cpr;
                }

                if (DEBUG_PROVIDER) {
                    RuntimeException e = new RuntimeException("here");
                    Slog.w(TAG, "LAUNCHING REMOTE PROVIDER (myuid " + r.info.uid
                          + " pruid " + cpr.appInfo.uid + "): " + cpr.info.name, e);
                }

                // This is single process, and our app is now connecting to it.
                // See if we are already in the process of launching this
                // provider.
                final int N = mLaunchingProviders.size();
                int i;
                for (i=0; i<N; i++) {
                    if (mLaunchingProviders.get(i) == cpr) {
                        break;
                    }
                }

                // If the provider is not already being launched, then get it
                // started.
                if (i >= N) {
                    final long origId = Binder.clearCallingIdentity();
                    ProcessRecord proc = startProcessLocked(cpi.processName,
                            cpr.appInfo, false, 0, "content provider",
                            new ComponentName(cpi.applicationInfo.packageName,
                                    cpi.name), false);
                    if (proc == null) {
                        Slog.w(TAG, "Unable to launch app "
                                + cpi.applicationInfo.packageName + "/"
                                + cpi.applicationInfo.uid + " for provider "
                                + name + ": process is bad");
                        return null;
                    }
                    cpr.launchingApp = proc;
                    mLaunchingProviders.add(cpr);
                    Binder.restoreCallingIdentity(origId);
                }

                // Make sure the provider is published (the same provider class
                // may be published under multiple names).
                if (firstClass) {
                    mProvidersByClass.put(cpi.name, cpr);
                }
                mProvidersByName.put(name, cpr);

                if (r != null) {
                    if (DEBUG_PROVIDER) Slog.v(TAG,
                            "Adding provider requested by "
                            + r.processName + " from process "
                            + cpr.info.processName);
                    Integer cnt = r.conProviders.get(cpr);
                    if (cnt == null) {
                        r.conProviders.put(cpr, new Integer(1));
                    } else {
                        r.conProviders.put(cpr, new Integer(cnt.intValue()+1));
                    }
                    cpr.clients.add(r);
                } else {
                    cpr.externals++;
                }
            }
        }

        // Wait for the provider to be published...
        synchronized (cpr) {
            while (cpr.provider == null) {
                if (cpr.launchingApp == null) {
                    Slog.w(TAG, "Unable to launch app "
                            + cpi.applicationInfo.packageName + "/"
                            + cpi.applicationInfo.uid + " for provider "
                            + name + ": launching app became null");
                    EventLog.writeEvent(EventLogTags.AM_PROVIDER_LOST_PROCESS,
                            cpi.applicationInfo.packageName,
                            cpi.applicationInfo.uid, name);
                    return null;
                }
                try {
                    cpr.wait();
                } catch (InterruptedException ex) {
                }
            }
        }
        return cpr;
    }

    public final ContentProviderHolder getContentProvider(
            IApplicationThread caller, String name) {
        if (caller == null) {
            String msg = "null IApplicationThread when getting content provider "
                    + name;
            Slog.w(TAG, msg);
            throw new SecurityException(msg);
        }

        return getContentProviderImpl(caller, name);
    }

    private ContentProviderHolder getContentProviderExternal(String name) {
        return getContentProviderImpl(null, name);
    }

    /**
     * Drop a content provider from a ProcessRecord's bookkeeping
     * @param cpr
     */
    public void removeContentProvider(IApplicationThread caller, String name) {
        synchronized (this) {
            ContentProviderRecord cpr = (ContentProviderRecord)mProvidersByName.get(name);
            if(cpr == null) {
                // remove from mProvidersByClass
                if (DEBUG_PROVIDER) Slog.v(TAG, name +
                        " provider not found in providers list");
                return;
            }
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller +
                        " when removing content provider " + name);
            }
            //update content provider record entry info
            ContentProviderRecord localCpr = (ContentProviderRecord)
                    mProvidersByClass.get(cpr.info.name);
            if (DEBUG_PROVIDER) Slog.v(TAG, "Removing provider requested by "
                    + r.info.processName + " from process "
                    + localCpr.appInfo.processName);
            if (localCpr.app == r) {
                //should not happen. taken care of as a local provider
                Slog.w(TAG, "removeContentProvider called on local provider: "
                        + cpr.info.name + " in process " + r.processName);
                return;
            } else {
                Integer cnt = r.conProviders.get(localCpr);
                if (cnt == null || cnt.intValue() <= 1) {
                    localCpr.clients.remove(r);
                    r.conProviders.remove(localCpr);
                } else {
                    r.conProviders.put(localCpr, new Integer(cnt.intValue()-1));
                }
            }
            updateOomAdjLocked();
        }
    }

    private void removeContentProviderExternal(String name) {
        synchronized (this) {
            ContentProviderRecord cpr = (ContentProviderRecord)mProvidersByName.get(name);
            if(cpr == null) {
                //remove from mProvidersByClass
                if(localLOGV) Slog.v(TAG, name+" content provider not found in providers list");
                return;
            }

            //update content provider record entry info
            ContentProviderRecord localCpr = (ContentProviderRecord) mProvidersByClass.get(cpr.info.name);
            localCpr.externals--;
            if (localCpr.externals < 0) {
                Slog.e(TAG, "Externals < 0 for content provider " + localCpr);
            }
            updateOomAdjLocked();
        }
    }
    
    public final void publishContentProviders(IApplicationThread caller,
            List<ContentProviderHolder> providers) {
        if (providers == null) {
            return;
        }

        synchronized(this) {
            final ProcessRecord r = getRecordForAppLocked(caller);
            if (r == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                      + " (pid=" + Binder.getCallingPid()
                      + ") when publishing content providers");
            }

            final long origId = Binder.clearCallingIdentity();

            final int N = providers.size();
            for (int i=0; i<N; i++) {
                ContentProviderHolder src = providers.get(i);
                if (src == null || src.info == null || src.provider == null) {
                    continue;
                }
                ContentProviderRecord dst =
                    (ContentProviderRecord)r.pubProviders.get(src.info.name);
                if (dst != null) {
                    mProvidersByClass.put(dst.info.name, dst);
                    String names[] = dst.info.authority.split(";");
                    for (int j = 0; j < names.length; j++) {
                        mProvidersByName.put(names[j], dst);
                    }

                    int NL = mLaunchingProviders.size();
                    int j;
                    for (j=0; j<NL; j++) {
                        if (mLaunchingProviders.get(j) == dst) {
                            mLaunchingProviders.remove(j);
                            j--;
                            NL--;
                        }
                    }
                    synchronized (dst) {
                        dst.provider = src.provider;
                        dst.app = r;
                        dst.notifyAll();
                    }
                    updateOomAdjLocked(r);
                }
            }

            Binder.restoreCallingIdentity(origId);
        }
    }

    public static final void installSystemProviders() {
        List providers;
        synchronized (mSelf) {
            ProcessRecord app = mSelf.mProcessNames.get("system", Process.SYSTEM_UID);
            providers = mSelf.generateApplicationProvidersLocked(app);
            if (providers != null) {
                for (int i=providers.size()-1; i>=0; i--) {
                    ProviderInfo pi = (ProviderInfo)providers.get(i);
                    if ((pi.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) == 0) {
                        Slog.w(TAG, "Not installing system proc provider " + pi.name
                                + ": not system .apk");
                        providers.remove(i);
                    }
                }
            }
        }
        if (providers != null) {
            mSystemThread.installSystemProviders(providers);
        }
    }

    // =========================================================
    // GLOBAL MANAGEMENT
    // =========================================================

    final ProcessRecord newProcessRecordLocked(IApplicationThread thread,
            ApplicationInfo info, String customProcess) {
        String proc = customProcess != null ? customProcess : info.processName;
        BatteryStatsImpl.Uid.Proc ps = null;
        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        synchronized (stats) {
            ps = stats.getProcessStatsLocked(info.uid, proc);
        }
        return new ProcessRecord(ps, thread, info, proc);
    }

    final ProcessRecord addAppLocked(ApplicationInfo info) {
        ProcessRecord app = getProcessRecordLocked(info.processName, info.uid);

        if (app == null) {
            app = newProcessRecordLocked(null, info, null);
            mProcessNames.put(info.processName, info.uid, app);
            updateLruProcessLocked(app, true, true);
        }

        if ((info.flags&(ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT))
                == (ApplicationInfo.FLAG_SYSTEM|ApplicationInfo.FLAG_PERSISTENT)) {
            app.persistent = true;
            app.maxAdj = CORE_SERVER_ADJ;
        }
        if (app.thread == null && mPersistentStartingProcesses.indexOf(app) < 0) {
            mPersistentStartingProcesses.add(app);
            startProcessLocked(app, "added application", app.processName);
        }

        return app;
    }

    public void unhandledBack() {
        enforceCallingPermission(android.Manifest.permission.FORCE_BACK,
                "unhandledBack()");

        synchronized(this) {
            int count = mHistory.size();
            if (DEBUG_SWITCH) Slog.d(
                TAG, "Performing unhandledBack(): stack size = " + count);
            if (count > 1) {
                final long origId = Binder.clearCallingIdentity();
                finishActivityLocked((HistoryRecord)mHistory.get(count-1),
                        count-1, Activity.RESULT_CANCELED, null, "unhandled-back");
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public ParcelFileDescriptor openContentUri(Uri uri) throws RemoteException {
        String name = uri.getAuthority();
        ContentProviderHolder cph = getContentProviderExternal(name);
        ParcelFileDescriptor pfd = null;
        if (cph != null) {
            // We record the binder invoker's uid in thread-local storage before
            // going to the content provider to open the file.  Later, in the code
            // that handles all permissions checks, we look for this uid and use
            // that rather than the Activity Manager's own uid.  The effect is that
            // we do the check against the caller's permissions even though it looks
            // to the content provider like the Activity Manager itself is making
            // the request.
            sCallerIdentity.set(new Identity(
                    Binder.getCallingPid(), Binder.getCallingUid()));
            try {
                pfd = cph.provider.openFile(uri, "r");
            } catch (FileNotFoundException e) {
                // do nothing; pfd will be returned null
            } finally {
                // Ensure that whatever happens, we clean up the identity state
                sCallerIdentity.remove();
            }

            // We've got the fd now, so we're done with the provider.
            removeContentProviderExternal(name);
        } else {
            Slog.d(TAG, "Failed to get provider for authority '" + name + "'");
        }
        return pfd;
    }

    public void goingToSleep() {
        synchronized(this) {
            mSleeping = true;
            mWindowManager.setEventDispatching(false);

            if (mResumedActivity != null) {
                pauseIfSleepingLocked();
            } else {
                Slog.w(TAG, "goingToSleep with no resumed activity!");
            }
        }
    }

    public boolean shutdown(int timeout) {
        if (checkCallingPermission(android.Manifest.permission.SHUTDOWN)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.SHUTDOWN);
        }
        
        boolean timedout = false;
        
        synchronized(this) {
            mShuttingDown = true;
            mWindowManager.setEventDispatching(false);

            if (mResumedActivity != null) {
                pauseIfSleepingLocked();
                final long endTime = System.currentTimeMillis() + timeout;
                while (mResumedActivity != null || mPausingActivity != null) {
                    long delay = endTime - System.currentTimeMillis();
                    if (delay <= 0) {
                        Slog.w(TAG, "Activity manager shutdown timed out");
                        timedout = true;
                        break;
                    }
                    try {
                        this.wait();
                    } catch (InterruptedException e) {
                    }
                }
            }
        }
        
        mUsageStatsService.shutdown();
        mBatteryStatsService.shutdown();
        
        return timedout;
    }
    
    void pauseIfSleepingLocked() {
        if (mSleeping || mShuttingDown) {
            if (!mGoingToSleep.isHeld()) {
                mGoingToSleep.acquire();
                if (mLaunchingActivity.isHeld()) {
                    mLaunchingActivity.release();
                    mHandler.removeMessages(LAUNCH_TIMEOUT_MSG);
                }
            }

            // If we are not currently pausing an activity, get the current
            // one to pause.  If we are pausing one, we will just let that stuff
            // run and release the wake lock when all done.
            if (mPausingActivity == null) {
                if (DEBUG_PAUSE) Slog.v(TAG, "Sleep needs to pause...");
                if (DEBUG_USER_LEAVING) Slog.v(TAG, "Sleep => pause with userLeaving=false");
                startPausingLocked(false, true);
            }
        }
    }
    
    public void wakingUp() {
        synchronized(this) {
            if (mGoingToSleep.isHeld()) {
                mGoingToSleep.release();
            }
            mWindowManager.setEventDispatching(true);
            mSleeping = false;
            resumeTopActivityLocked(null);
        }
    }

    public void stopAppSwitches() {
        if (checkCallingPermission(android.Manifest.permission.STOP_APP_SWITCHES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.STOP_APP_SWITCHES);
        }
        
        synchronized(this) {
            mAppSwitchesAllowedTime = SystemClock.uptimeMillis()
                    + APP_SWITCH_DELAY_TIME;
            mDidAppSwitch = false;
            mHandler.removeMessages(DO_PENDING_ACTIVITY_LAUNCHES_MSG);
            Message msg = mHandler.obtainMessage(DO_PENDING_ACTIVITY_LAUNCHES_MSG);
            mHandler.sendMessageDelayed(msg, APP_SWITCH_DELAY_TIME);
        }
    }
    
    public void resumeAppSwitches() {
        if (checkCallingPermission(android.Manifest.permission.STOP_APP_SWITCHES)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires permission "
                    + android.Manifest.permission.STOP_APP_SWITCHES);
        }
        
        synchronized(this) {
            // Note that we don't execute any pending app switches... we will
            // let those wait until either the timeout, or the next start
            // activity request.
            mAppSwitchesAllowedTime = 0;
        }
    }
    
    boolean checkAppSwitchAllowedLocked(int callingPid, int callingUid,
            String name) {
        if (mAppSwitchesAllowedTime < SystemClock.uptimeMillis()) {
            return true;
        }
            
        final int perm = checkComponentPermission(
                android.Manifest.permission.STOP_APP_SWITCHES, callingPid,
                callingUid, -1);
        if (perm == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        
        Slog.w(TAG, name + " request from " + callingUid + " stopped");
        return false;
    }
    
    public void setDebugApp(String packageName, boolean waitForDebugger,
            boolean persistent) {
        enforceCallingPermission(android.Manifest.permission.SET_DEBUG_APP,
                "setDebugApp()");

        // Note that this is not really thread safe if there are multiple
        // callers into it at the same time, but that's not a situation we
        // care about.
        if (persistent) {
            final ContentResolver resolver = mContext.getContentResolver();
            Settings.System.putString(
                resolver, Settings.System.DEBUG_APP,
                packageName);
            Settings.System.putInt(
                resolver, Settings.System.WAIT_FOR_DEBUGGER,
                waitForDebugger ? 1 : 0);
        }

        synchronized (this) {
            if (!persistent) {
                mOrigDebugApp = mDebugApp;
                mOrigWaitForDebugger = mWaitForDebugger;
            }
            mDebugApp = packageName;
            mWaitForDebugger = waitForDebugger;
            mDebugTransient = !persistent;
            if (packageName != null) {
                final long origId = Binder.clearCallingIdentity();
                forceStopPackageLocked(packageName, -1, false, false, true);
                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void setAlwaysFinish(boolean enabled) {
        enforceCallingPermission(android.Manifest.permission.SET_ALWAYS_FINISH,
                "setAlwaysFinish()");

        Settings.System.putInt(
                mContext.getContentResolver(),
                Settings.System.ALWAYS_FINISH_ACTIVITIES, enabled ? 1 : 0);
        
        synchronized (this) {
            mAlwaysFinishActivities = enabled;
        }
    }

    public void setActivityController(IActivityController controller) {
        enforceCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER,
                "setActivityController()");
        synchronized (this) {
            mController = controller;
        }
    }

    public boolean isUserAMonkey() {
        // For now the fact that there is a controller implies
        // we have a monkey.
        synchronized (this) {
            return mController != null;
        }
    }
    
    public void registerActivityWatcher(IActivityWatcher watcher) {
        synchronized (this) {
            mWatchers.register(watcher);
        }
    }

    public void unregisterActivityWatcher(IActivityWatcher watcher) {
        synchronized (this) {
            mWatchers.unregister(watcher);
        }
    }

    public final void enterSafeMode() {
        synchronized(this) {
            // It only makes sense to do this before the system is ready
            // and started launching other packages.
            if (!mSystemReady) {
                try {
                    ActivityThread.getPackageManager().enterSafeMode();
                } catch (RemoteException e) {
                }

                View v = LayoutInflater.from(mContext).inflate(
                        com.android.internal.R.layout.safe_mode, null);
                WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
                lp.type = WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY;
                lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
                lp.gravity = Gravity.BOTTOM | Gravity.LEFT;
                lp.format = v.getBackground().getOpacity();
                lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                ((WindowManager)mContext.getSystemService(
                        Context.WINDOW_SERVICE)).addView(v, lp);
            }
        }
    }

    public void noteWakeupAlarm(IIntentSender sender) {
        if (!(sender instanceof PendingIntentRecord)) {
            return;
        }
        BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
        synchronized (stats) {
            if (mBatteryStatsService.isOnBattery()) {
                mBatteryStatsService.enforceCallingPermission();
                PendingIntentRecord rec = (PendingIntentRecord)sender;
                int MY_UID = Binder.getCallingUid();
                int uid = rec.uid == MY_UID ? Process.SYSTEM_UID : rec.uid;
                BatteryStatsImpl.Uid.Pkg pkg =
                    stats.getPackageStatsLocked(uid, rec.key.packageName);
                pkg.incWakeupsLocked();
            }
        }
    }

    public boolean killPids(int[] pids, String pReason) {
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("killPids only available to the system");
        }
        String reason = (pReason == null) ? "Unknown" : pReason;
        // XXX Note: don't acquire main activity lock here, because the window
        // manager calls in with its locks held.
        
        boolean killed = false;
        synchronized (mPidsSelfLocked) {
            int[] types = new int[pids.length];
            int worstType = 0;
            for (int i=0; i<pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc != null) {
                    int type = proc.setAdj;
                    types[i] = type;
                    if (type > worstType) {
                        worstType = type;
                    }
                }
            }
            
            // If the worse oom_adj is somewhere in the hidden proc LRU range,
            // then constrain it so we will kill all hidden procs.
            if (worstType < EMPTY_APP_ADJ && worstType > HIDDEN_APP_MIN_ADJ) {
                worstType = HIDDEN_APP_MIN_ADJ;
            }
            Slog.w(TAG, "Killing processes " + reason + " at adjustment " + worstType);
            for (int i=0; i<pids.length; i++) {
                ProcessRecord proc = mPidsSelfLocked.get(pids[i]);
                if (proc == null) {
                    continue;
                }
                int adj = proc.setAdj;
                if (adj >= worstType && !proc.killedBackground) {
                    Slog.w(TAG, "Killing " + proc + " (adj " + adj + "): " + reason);
                    EventLog.writeEvent(EventLogTags.AM_KILL, proc.pid,
                            proc.processName, adj, reason);
                    killed = true;
                    proc.killedBackground = true;
                    Process.killProcessQuiet(pids[i]);
                }
            }
        }
        return killed;
    }
    
    public void reportPss(IApplicationThread caller, int pss) {
        Watchdog.PssRequestor req;
        String name;
        ProcessRecord callerApp;
        synchronized (this) {
            if (caller == null) {
                return;
            }
            callerApp = getRecordForAppLocked(caller);
            if (callerApp == null) {
                return;
            }
            callerApp.lastPss = pss;
            req = callerApp;
            name = callerApp.processName;
        }
        Watchdog.getInstance().reportPss(req, name, pss);
        if (!callerApp.persistent) {
            removeRequestedPss(callerApp);
        }
    }
    
    public void requestPss(Runnable completeCallback) {
        ArrayList<ProcessRecord> procs;
        synchronized (this) {
            mRequestPssCallback = completeCallback;
            mRequestPssList.clear();
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord proc = mLruProcesses.get(i);
                if (!proc.persistent) {
                    mRequestPssList.add(proc);
                }
            }
            procs = new ArrayList<ProcessRecord>(mRequestPssList);
        }
        
        int oldPri = Process.getThreadPriority(Process.myTid()); 
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
        for (int i=procs.size()-1; i>=0; i--) {
            ProcessRecord proc = procs.get(i);
            proc.lastPss = 0;
            proc.requestPss();
        }
        Process.setThreadPriority(oldPri);
    }
    
    void removeRequestedPss(ProcessRecord proc) {
        Runnable callback = null;
        synchronized (this) {
            if (mRequestPssList.remove(proc)) {
                if (mRequestPssList.size() == 0) {
                    callback = mRequestPssCallback;
                    mRequestPssCallback = null;
                }
            }
        }
        
        if (callback != null) {
            callback.run();
        }
    }
    
    public void collectPss(Watchdog.PssStats stats) {
        stats.mEmptyPss = 0;
        stats.mEmptyCount = 0;
        stats.mBackgroundPss = 0;
        stats.mBackgroundCount = 0;
        stats.mServicePss = 0;
        stats.mServiceCount = 0;
        stats.mVisiblePss = 0;
        stats.mVisibleCount = 0;
        stats.mForegroundPss = 0;
        stats.mForegroundCount = 0;
        stats.mNoPssCount = 0;
        synchronized (this) {
            int i;
            int NPD = mProcDeaths.length < stats.mProcDeaths.length
                    ? mProcDeaths.length : stats.mProcDeaths.length;
            int aggr = 0;
            for (i=0; i<NPD; i++) {
                aggr += mProcDeaths[i];
                stats.mProcDeaths[i] = aggr;
            }
            while (i<stats.mProcDeaths.length) {
                stats.mProcDeaths[i] = 0;
                i++;
            }
            
            for (i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord proc = mLruProcesses.get(i);
                if (proc.persistent) {
                    continue;
                }
                //Slog.i(TAG, "Proc " + proc + ": pss=" + proc.lastPss);
                if (proc.lastPss == 0) {
                    stats.mNoPssCount++;
                    continue;
                }
                if (proc.setAdj >= HIDDEN_APP_MIN_ADJ) {
                    if (proc.empty) {
                        stats.mEmptyPss += proc.lastPss;
                        stats.mEmptyCount++;
                    } else {
                        stats.mBackgroundPss += proc.lastPss;
                        stats.mBackgroundCount++;
                    }
                } else if (proc.setAdj >= VISIBLE_APP_ADJ) {
                    stats.mVisiblePss += proc.lastPss;
                    stats.mVisibleCount++;
                } else {
                    stats.mForegroundPss += proc.lastPss;
                    stats.mForegroundCount++;
                }
            }
        }
    }
    
    public final void startRunning(String pkg, String cls, String action,
            String data) {
        synchronized(this) {
            if (mStartRunning) {
                return;
            }
            mStartRunning = true;
            mTopComponent = pkg != null && cls != null
                    ? new ComponentName(pkg, cls) : null;
            mTopAction = action != null ? action : Intent.ACTION_MAIN;
            mTopData = data;
            if (!mSystemReady) {
                return;
            }
        }

        systemReady(null);
    }

    private void retrieveSettings() {
        final ContentResolver resolver = mContext.getContentResolver();
        String debugApp = Settings.System.getString(
            resolver, Settings.System.DEBUG_APP);
        boolean waitForDebugger = Settings.System.getInt(
            resolver, Settings.System.WAIT_FOR_DEBUGGER, 0) != 0;
        boolean alwaysFinishActivities = Settings.System.getInt(
            resolver, Settings.System.ALWAYS_FINISH_ACTIVITIES, 0) != 0;

        Configuration configuration = new Configuration();
        Settings.System.getConfiguration(resolver, configuration);

        synchronized (this) {
            mDebugApp = mOrigDebugApp = debugApp;
            mWaitForDebugger = mOrigWaitForDebugger = waitForDebugger;
            mAlwaysFinishActivities = alwaysFinishActivities;
            // This happens before any activities are started, so we can
            // change mConfiguration in-place.
            mConfiguration.updateFrom(configuration);
            mConfigurationSeq = mConfiguration.seq = 1;
            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Initial config: " + mConfiguration);
        }
    }

    public boolean testIsSystemReady() {
        // no need to synchronize(this) just to read & return the value
        return mSystemReady;
    }
    
    public void systemReady(final Runnable goingCallback) {
        // In the simulator, startRunning will never have been called, which
        // normally sets a few crucial variables. Do it here instead.
        if (!Process.supportsProcesses()) {
            mStartRunning = true;
            mTopAction = Intent.ACTION_MAIN;
        }

        synchronized(this) {
            if (mSystemReady) {
                if (goingCallback != null) goingCallback.run();
                return;
            }
            
            // Check to see if there are any update receivers to run.
            if (!mDidUpdate) {
                if (mWaitingUpdate) {
                    return;
                }
                Intent intent = new Intent(Intent.ACTION_PRE_BOOT_COMPLETED);
                List<ResolveInfo> ris = null;
                try {
                    ris = ActivityThread.getPackageManager().queryIntentReceivers(
                                intent, null, 0);
                } catch (RemoteException e) {
                }
                if (ris != null) {
                    for (int i=ris.size()-1; i>=0; i--) {
                        if ((ris.get(i).activityInfo.applicationInfo.flags
                                &ApplicationInfo.FLAG_SYSTEM) == 0) {
                            ris.remove(i);
                        }
                    }
                    intent.addFlags(Intent.FLAG_RECEIVER_BOOT_UPGRADE);
                    for (int i=0; i<ris.size(); i++) {
                        ActivityInfo ai = ris.get(i).activityInfo;
                        intent.setComponent(new ComponentName(ai.packageName, ai.name));
                        IIntentReceiver finisher = null;
                        if (i == ris.size()-1) {
                            finisher = new IIntentReceiver.Stub() {
                                public void performReceive(Intent intent, int resultCode,
                                        String data, Bundle extras, boolean ordered,
                                        boolean sticky)
                                        throws RemoteException {
                                    synchronized (ActivityManagerService.this) {
                                        mDidUpdate = true;
                                    }
                                    systemReady(goingCallback);
                                }
                            };
                        }
                        Slog.i(TAG, "Sending system update to: " + intent.getComponent());
                        broadcastIntentLocked(null, null, intent, null, finisher,
                                0, null, null, null, true, false, MY_PID, Process.SYSTEM_UID);
                        if (finisher != null) {
                            mWaitingUpdate = true;
                        }
                    }
                }
                if (mWaitingUpdate) {
                    return;
                }
                mDidUpdate = true;
            }
            
            mSystemReady = true;
            if (!mStartRunning) {
                return;
            }
        }

        ArrayList<ProcessRecord> procsToKill = null;
        synchronized(mPidsSelfLocked) {
            for (int i=mPidsSelfLocked.size()-1; i>=0; i--) {
                ProcessRecord proc = mPidsSelfLocked.valueAt(i);
                if (!isAllowedWhileBooting(proc.info)){
                    if (procsToKill == null) {
                        procsToKill = new ArrayList<ProcessRecord>();
                    }
                    procsToKill.add(proc);
                }
            }
        }
        
        if (procsToKill != null) {
            synchronized(this) {
                for (int i=procsToKill.size()-1; i>=0; i--) {
                    ProcessRecord proc = procsToKill.get(i);
                    Slog.i(TAG, "Removing system update proc: " + proc);
                    removeProcessLocked(proc, true);
                }
            }
        }
        
        Slog.i(TAG, "System now ready");
        EventLog.writeEvent(EventLogTags.BOOT_PROGRESS_AMS_READY,
            SystemClock.uptimeMillis());

        synchronized(this) {
            // Make sure we have no pre-ready processes sitting around.
            
            if (mFactoryTest == SystemServer.FACTORY_TEST_LOW_LEVEL) {
                ResolveInfo ri = mContext.getPackageManager()
                        .resolveActivity(new Intent(Intent.ACTION_FACTORY_TEST),
                                STOCK_PM_FLAGS);
                CharSequence errorMsg = null;
                if (ri != null) {
                    ActivityInfo ai = ri.activityInfo;
                    ApplicationInfo app = ai.applicationInfo;
                    if ((app.flags&ApplicationInfo.FLAG_SYSTEM) != 0) {
                        mTopAction = Intent.ACTION_FACTORY_TEST;
                        mTopData = null;
                        mTopComponent = new ComponentName(app.packageName,
                                ai.name);
                    } else {
                        errorMsg = mContext.getResources().getText(
                                com.android.internal.R.string.factorytest_not_system);
                    }
                } else {
                    errorMsg = mContext.getResources().getText(
                            com.android.internal.R.string.factorytest_no_action);
                }
                if (errorMsg != null) {
                    mTopAction = null;
                    mTopData = null;
                    mTopComponent = null;
                    Message msg = Message.obtain();
                    msg.what = SHOW_FACTORY_ERROR_MSG;
                    msg.getData().putCharSequence("msg", errorMsg);
                    mHandler.sendMessage(msg);
                }
            }
        }

        retrieveSettings();

        if (goingCallback != null) goingCallback.run();
        
        synchronized (this) {
            if (mFactoryTest != SystemServer.FACTORY_TEST_LOW_LEVEL) {
                try {
                    List apps = ActivityThread.getPackageManager().
                        getPersistentApplications(STOCK_PM_FLAGS);
                    if (apps != null) {
                        int N = apps.size();
                        int i;
                        for (i=0; i<N; i++) {
                            ApplicationInfo info
                                = (ApplicationInfo)apps.get(i);
                            if (info != null &&
                                    !info.packageName.equals("android")) {
                                addAppLocked(info);
                            }
                        }
                    }
                } catch (RemoteException ex) {
                    // pm is in same process, this will never happen.
                }
            }

            // Start up initial activity.
            mBooting = true;
            
            try {
                if (ActivityThread.getPackageManager().hasSystemUidErrors()) {
                    Message msg = Message.obtain();
                    msg.what = SHOW_UID_ERROR_MSG;
                    mHandler.sendMessage(msg);
                }
            } catch (RemoteException e) {
            }

            resumeTopActivityLocked(null);
        }
    }

    private boolean makeAppCrashingLocked(ProcessRecord app,
            String shortMsg, String longMsg, String stackTrace) {
        app.crashing = true;
        app.crashingReport = generateProcessError(app,
                ActivityManager.ProcessErrorStateInfo.CRASHED, null, shortMsg, longMsg, stackTrace);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
        return handleAppCrashLocked(app);
    }

    private void makeAppNotRespondingLocked(ProcessRecord app,
            String activity, String shortMsg, String longMsg) {
        app.notResponding = true;
        app.notRespondingReport = generateProcessError(app,
                ActivityManager.ProcessErrorStateInfo.NOT_RESPONDING,
                activity, shortMsg, longMsg, null);
        startAppProblemLocked(app);
        app.stopFreezingAllLocked();
    }
    
    /**
     * Generate a process error record, suitable for attachment to a ProcessRecord.
     * 
     * @param app The ProcessRecord in which the error occurred.
     * @param condition Crashing, Application Not Responding, etc.  Values are defined in 
     *                      ActivityManager.AppErrorStateInfo
     * @param activity The activity associated with the crash, if known.
     * @param shortMsg Short message describing the crash.
     * @param longMsg Long message describing the crash.
     * @param stackTrace Full crash stack trace, may be null.
     *
     * @return Returns a fully-formed AppErrorStateInfo record.
     */
    private ActivityManager.ProcessErrorStateInfo generateProcessError(ProcessRecord app, 
            int condition, String activity, String shortMsg, String longMsg, String stackTrace) {
        ActivityManager.ProcessErrorStateInfo report = new ActivityManager.ProcessErrorStateInfo();

        report.condition = condition;
        report.processName = app.processName;
        report.pid = app.pid;
        report.uid = app.info.uid;
        report.tag = activity;
        report.shortMsg = shortMsg;
        report.longMsg = longMsg;
        report.stackTrace = stackTrace;

        return report;
    }

    void killAppAtUsersRequest(ProcessRecord app, Dialog fromDialog) {
        synchronized (this) {
            app.crashing = false;
            app.crashingReport = null;
            app.notResponding = false;
            app.notRespondingReport = null;
            if (app.anrDialog == fromDialog) {
                app.anrDialog = null;
            }
            if (app.waitDialog == fromDialog) {
                app.waitDialog = null;
            }
            if (app.pid > 0 && app.pid != MY_PID) {
                handleAppCrashLocked(app);
                Slog.i(ActivityManagerService.TAG, "Killing "
                        + app.processName + " (pid=" + app.pid + "): user's request");
                EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                        app.processName, app.setAdj, "user's request after error");
                Process.killProcess(app.pid);
            }
        }
    }

    private boolean handleAppCrashLocked(ProcessRecord app) {
        long now = SystemClock.uptimeMillis();

        Long crashTime = mProcessCrashTimes.get(app.info.processName,
                app.info.uid);
        if (crashTime != null && now < crashTime+MIN_CRASH_INTERVAL) {
            // This process loses!
            Slog.w(TAG, "Process " + app.info.processName
                    + " has crashed too many times: killing!");
            EventLog.writeEvent(EventLogTags.AM_PROCESS_CRASHED_TOO_MUCH,
                    app.info.processName, app.info.uid);
            killServicesLocked(app, false);
            for (int i=mHistory.size()-1; i>=0; i--) {
                HistoryRecord r = (HistoryRecord)mHistory.get(i);
                if (r.app == app) {
                    Slog.w(TAG, "  Force finishing activity "
                        + r.intent.getComponent().flattenToShortString());
                    finishActivityLocked(r, i, Activity.RESULT_CANCELED, null, "crashed");
                }
            }
            if (!app.persistent) {
                // We don't want to start this process again until the user
                // explicitly does so...  but for persistent process, we really
                // need to keep it running.  If a persistent process is actually
                // repeatedly crashing, then badness for everyone.
                EventLog.writeEvent(EventLogTags.AM_PROC_BAD, app.info.uid,
                        app.info.processName);
                mBadProcesses.put(app.info.processName, app.info.uid, now);
                app.bad = true;
                mProcessCrashTimes.remove(app.info.processName, app.info.uid);
                app.removed = true;
                removeProcessLocked(app, false);
                return false;
            }
        } else {
            HistoryRecord r = topRunningActivityLocked(null);
            if (r.app == app) {
                // If the top running activity is from this crashing
                // process, then terminate it to avoid getting in a loop.
                Slog.w(TAG, "  Force finishing activity "
                        + r.intent.getComponent().flattenToShortString());
                int index = indexOfTokenLocked(r);
                finishActivityLocked(r, index,
                        Activity.RESULT_CANCELED, null, "crashed");
                // Also terminate an activities below it that aren't yet
                // stopped, to avoid a situation where one will get
                // re-start our crashing activity once it gets resumed again.
                index--;
                if (index >= 0) {
                    r = (HistoryRecord)mHistory.get(index);
                    if (r.state == ActivityState.RESUMED
                            || r.state == ActivityState.PAUSING
                            || r.state == ActivityState.PAUSED) {
                        if (!r.isHomeActivity) {
                            Slog.w(TAG, "  Force finishing activity "
                                    + r.intent.getComponent().flattenToShortString());
                            finishActivityLocked(r, index,
                                    Activity.RESULT_CANCELED, null, "crashed");
                        }
                    }
                }
            }
        }

        // Bump up the crash count of any services currently running in the proc.
        if (app.services.size() != 0) {
            // Any services running in the application need to be placed
            // back in the pending list.
            Iterator it = app.services.iterator();
            while (it.hasNext()) {
                ServiceRecord sr = (ServiceRecord)it.next();
                sr.crashCount++;
            }
        }
        
        mProcessCrashTimes.put(app.info.processName, app.info.uid, now);
        return true;
    }

    void startAppProblemLocked(ProcessRecord app) {
        app.errorReportReceiver = ApplicationErrorReport.getErrorReportReceiver(
                mContext, app.info.packageName, app.info.flags);
        skipCurrentReceiverLocked(app);
    }

    void skipCurrentReceiverLocked(ProcessRecord app) {
        boolean reschedule = false;
        BroadcastRecord r = app.curReceiver;
        if (r != null) {
            // The current broadcast is waiting for this app's receiver
            // to be finished.  Looks like that's not going to happen, so
            // let the broadcast continue.
            logBroadcastReceiverDiscard(r);
            finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, true);
            reschedule = true;
        }
        r = mPendingBroadcast;
        if (r != null && r.curApp == app) {
            if (DEBUG_BROADCAST) Slog.v(TAG,
                    "skip & discard pending app " + r);
            logBroadcastReceiverDiscard(r);
            finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, true);
            reschedule = true;
        }
        if (reschedule) {
            scheduleBroadcastsLocked();
        }
    }

    /**
     * Used by {@link com.android.internal.os.RuntimeInit} to report when an application crashes.
     * The application process will exit immediately after this call returns.
     * @param app object of the crashing app, null for the system server
     * @param crashInfo describing the exception
     */
    public void handleApplicationCrash(IBinder app, ApplicationErrorReport.CrashInfo crashInfo) {
        ProcessRecord r = findAppProcess(app);

        EventLog.writeEvent(EventLogTags.AM_CRASH, Binder.getCallingPid(),
                app == null ? "system" : (r == null ? "unknown" : r.processName),
                r == null ? -1 : r.info.flags,
                crashInfo.exceptionClassName,
                crashInfo.exceptionMessage,
                crashInfo.throwFileName,
                crashInfo.throwLineNumber);

        addErrorToDropBox("crash", r, null, null, null, null, null, crashInfo);

        crashApplication(r, crashInfo);
    }

    /**
     * Used by {@link Log} via {@link com.android.internal.os.RuntimeInit} to report serious errors.
     * @param app object of the crashing app, null for the system server
     * @param tag reported by the caller
     * @param crashInfo describing the context of the error
     * @return true if the process should exit immediately (WTF is fatal)
     */
    public boolean handleApplicationWtf(IBinder app, String tag,
            ApplicationErrorReport.CrashInfo crashInfo) {
        ProcessRecord r = findAppProcess(app);

        EventLog.writeEvent(EventLogTags.AM_WTF, Binder.getCallingPid(),
                app == null ? "system" : (r == null ? "unknown" : r.processName),
                r == null ? -1 : r.info.flags,
                tag, crashInfo.exceptionMessage);

        addErrorToDropBox("wtf", r, null, null, tag, null, null, crashInfo);

        if (Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.WTF_IS_FATAL, 0) != 0) {
            crashApplication(r, crashInfo);
            return true;
        } else {
            return false;
        }
    }

    /**
     * @param app object of some object (as stored in {@link com.android.internal.os.RuntimeInit})
     * @return the corresponding {@link ProcessRecord} object, or null if none could be found
     */
    private ProcessRecord findAppProcess(IBinder app) {
        if (app == null) {
            return null;
        }

        synchronized (this) {
            for (SparseArray<ProcessRecord> apps : mProcessNames.getMap().values()) {
                final int NA = apps.size();
                for (int ia=0; ia<NA; ia++) {
                    ProcessRecord p = apps.valueAt(ia);
                    if (p.thread != null && p.thread.asBinder() == app) {
                        return p;
                    }
                }
            }

            Slog.w(TAG, "Can't find mystery application: " + app);
            return null;
        }
    }

    /**
     * Write a description of an error (crash, WTF, ANR) to the drop box.
     * @param eventType to include in the drop box tag ("crash", "wtf", etc.)
     * @param process which caused the error, null means the system server
     * @param activity which triggered the error, null if unknown
     * @param parent activity related to the error, null if unknown
     * @param subject line related to the error, null if absent
     * @param report in long form describing the error, null if absent
     * @param logFile to include in the report, null if none
     * @param crashInfo giving an application stack trace, null if absent
     */
    public void addErrorToDropBox(String eventType,
            ProcessRecord process, HistoryRecord activity, HistoryRecord parent, String subject,
            final String report, final File logFile,
            final ApplicationErrorReport.CrashInfo crashInfo) {
        // NOTE -- this must never acquire the ActivityManagerService lock,
        // otherwise the watchdog may be prevented from resetting the system.

        String prefix;
        if (process == null || process.pid == MY_PID) {
            prefix = "system_server_";
        } else if ((process.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            prefix = "system_app_";
        } else {
            prefix = "data_app_";
        }

        final String dropboxTag = prefix + eventType;
        final DropBoxManager dbox = (DropBoxManager)
                mContext.getSystemService(Context.DROPBOX_SERVICE);

        // Exit early if the dropbox isn't configured to accept this report type.
        if (dbox == null || !dbox.isTagEnabled(dropboxTag)) return;

        final StringBuilder sb = new StringBuilder(1024);
        if (process == null || process.pid == MY_PID) {
            sb.append("Process: system_server\n");
        } else {
            sb.append("Process: ").append(process.processName).append("\n");
        }
        if (process != null) {
            int flags = process.info.flags;
            IPackageManager pm = ActivityThread.getPackageManager();
            sb.append("Flags: 0x").append(Integer.toString(flags, 16)).append("\n");
            for (String pkg : process.pkgList) {
                sb.append("Package: ").append(pkg);
                try {
                    PackageInfo pi = pm.getPackageInfo(pkg, 0);
                    if (pi != null) {
                        sb.append(" v").append(pi.versionCode);
                        if (pi.versionName != null) {
                            sb.append(" (").append(pi.versionName).append(")");
                        }
                    }
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error getting package info: " + pkg, e);
                }
                sb.append("\n");
            }
        }
        if (activity != null) {
            sb.append("Activity: ").append(activity.shortComponentName).append("\n");
        }
        if (parent != null && parent.app != null && parent.app.pid != process.pid) {
            sb.append("Parent-Process: ").append(parent.app.processName).append("\n");
        }
        if (parent != null && parent != activity) {
            sb.append("Parent-Activity: ").append(parent.shortComponentName).append("\n");
        }
        if (subject != null) {
            sb.append("Subject: ").append(subject).append("\n");
        }
        sb.append("Build: ").append(Build.FINGERPRINT).append("\n");
        sb.append("\n");

        // Do the rest in a worker thread to avoid blocking the caller on I/O
        // (After this point, we shouldn't access AMS internal data structures.)
        Thread worker = new Thread("Error dump: " + dropboxTag) {
            @Override
            public void run() {
                if (report != null) {
                    sb.append(report);
                }
                if (logFile != null) {
                    try {
                        sb.append(FileUtils.readTextFile(logFile, 128 * 1024, "\n\n[[TRUNCATED]]"));
                    } catch (IOException e) {
                        Slog.e(TAG, "Error reading " + logFile, e);
                    }
                }
                if (crashInfo != null && crashInfo.stackTrace != null) {
                    sb.append(crashInfo.stackTrace);
                }

                String setting = Settings.Secure.ERROR_LOGCAT_PREFIX + dropboxTag;
                int lines = Settings.Secure.getInt(mContext.getContentResolver(), setting, 0);
                if (lines > 0) {
                    sb.append("\n");

                    // Merge several logcat streams, and take the last N lines
                    InputStreamReader input = null;
                    try {
                        java.lang.Process logcat = new ProcessBuilder("/system/bin/logcat",
                                "-v", "time", "-b", "events", "-b", "system", "-b", "main",
                                "-t", String.valueOf(lines)).redirectErrorStream(true).start();

                        try { logcat.getOutputStream().close(); } catch (IOException e) {}
                        try { logcat.getErrorStream().close(); } catch (IOException e) {}
                        input = new InputStreamReader(logcat.getInputStream());

                        int num;
                        char[] buf = new char[8192];
                        while ((num = input.read(buf)) > 0) sb.append(buf, 0, num);
                    } catch (IOException e) {
                        Slog.e(TAG, "Error running logcat", e);
                    } finally {
                        if (input != null) try { input.close(); } catch (IOException e) {}
                    }
                }

                dbox.addText(dropboxTag, sb.toString());
            }
        };

        if (process == null || process.pid == MY_PID) {
            worker.run();  // We may be about to die -- need to run this synchronously
        } else {
            worker.start();
        }
    }

    /**
     * Bring up the "unexpected error" dialog box for a crashing app.
     * Deal with edge cases (intercepts from instrumented applications,
     * ActivityController, error intent receivers, that sort of thing).
     * @param r the application crashing
     * @param crashInfo describing the failure
     */
    private void crashApplication(ProcessRecord r, ApplicationErrorReport.CrashInfo crashInfo) {
        long timeMillis = System.currentTimeMillis();
        String shortMsg = crashInfo.exceptionClassName;
        String longMsg = crashInfo.exceptionMessage;
        String stackTrace = crashInfo.stackTrace;
        if (shortMsg != null && longMsg != null) {
            longMsg = shortMsg + ": " + longMsg;
        } else if (shortMsg != null) {
            longMsg = shortMsg;
        }

        AppErrorResult result = new AppErrorResult();
        synchronized (this) {
            if (mController != null) {
                try {
                    String name = r != null ? r.processName : null;
                    int pid = r != null ? r.pid : Binder.getCallingPid();
                    if (!mController.appCrashed(name, pid,
                            shortMsg, longMsg, timeMillis, crashInfo.stackTrace)) {
                        Slog.w(TAG, "Force-killing crashed app " + name
                                + " at watcher's request");
                        Process.killProcess(pid);
                        return;
                    }
                } catch (RemoteException e) {
                    mController = null;
                }
            }

            final long origId = Binder.clearCallingIdentity();

            // If this process is running instrumentation, finish it.
            if (r != null && r.instrumentationClass != null) {
                Slog.w(TAG, "Error in app " + r.processName
                      + " running instrumentation " + r.instrumentationClass + ":");
                if (shortMsg != null) Slog.w(TAG, "  " + shortMsg);
                if (longMsg != null) Slog.w(TAG, "  " + longMsg);
                Bundle info = new Bundle();
                info.putString("shortMsg", shortMsg);
                info.putString("longMsg", longMsg);
                finishInstrumentationLocked(r, Activity.RESULT_CANCELED, info);
                Binder.restoreCallingIdentity(origId);
                return;
            }

            // If we can't identify the process or it's already exceeded its crash quota,
            // quit right away without showing a crash dialog.
            if (r == null || !makeAppCrashingLocked(r, shortMsg, longMsg, stackTrace)) {
                Binder.restoreCallingIdentity(origId);
                return;
            }

            Message msg = Message.obtain();
            msg.what = SHOW_ERROR_MSG;
            HashMap data = new HashMap();
            data.put("result", result);
            data.put("app", r);
            msg.obj = data;
            mHandler.sendMessage(msg);

            Binder.restoreCallingIdentity(origId);
        }

        int res = result.get();

        Intent appErrorIntent = null;
        synchronized (this) {
            if (r != null) {
                mProcessCrashTimes.put(r.info.processName, r.info.uid,
                        SystemClock.uptimeMillis());
            }
            if (res == AppErrorDialog.FORCE_QUIT_AND_REPORT) {
                appErrorIntent = createAppErrorIntentLocked(r, timeMillis, crashInfo);
            }
        }

        if (appErrorIntent != null) {
            try {
                mContext.startActivity(appErrorIntent);
            } catch (ActivityNotFoundException e) {
                Slog.w(TAG, "bug report receiver dissappeared", e);
            }
        }
    }

    Intent createAppErrorIntentLocked(ProcessRecord r,
            long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        ApplicationErrorReport report = createAppErrorReportLocked(r, timeMillis, crashInfo);
        if (report == null) {
            return null;
        }
        Intent result = new Intent(Intent.ACTION_APP_ERROR);
        result.setComponent(r.errorReportReceiver);
        result.putExtra(Intent.EXTRA_BUG_REPORT, report);
        result.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return result;
    }

    private ApplicationErrorReport createAppErrorReportLocked(ProcessRecord r,
            long timeMillis, ApplicationErrorReport.CrashInfo crashInfo) {
        if (r.errorReportReceiver == null) {
            return null;
        }

        if (!r.crashing && !r.notResponding) {
            return null;
        }

        ApplicationErrorReport report = new ApplicationErrorReport();
        report.packageName = r.info.packageName;
        report.installerPackageName = r.errorReportReceiver.getPackageName();
        report.processName = r.processName;
        report.time = timeMillis;
        report.systemApp = (r.info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;

        if (r.crashing) {
            report.type = ApplicationErrorReport.TYPE_CRASH;
            report.crashInfo = crashInfo;
        } else if (r.notResponding) {
            report.type = ApplicationErrorReport.TYPE_ANR;
            report.anrInfo = new ApplicationErrorReport.AnrInfo();

            report.anrInfo.activity = r.notRespondingReport.tag;
            report.anrInfo.cause = r.notRespondingReport.shortMsg;
            report.anrInfo.info = r.notRespondingReport.longMsg;
        }

        return report;
    }

    public List<ActivityManager.ProcessErrorStateInfo> getProcessesInErrorState() {
        // assume our apps are happy - lazy create the list
        List<ActivityManager.ProcessErrorStateInfo> errList = null;

        synchronized (this) {

            // iterate across all processes
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mLruProcesses.get(i);
                if ((app.thread != null) && (app.crashing || app.notResponding)) {
                    // This one's in trouble, so we'll generate a report for it
                    // crashes are higher priority (in case there's a crash *and* an anr)
                    ActivityManager.ProcessErrorStateInfo report = null;
                    if (app.crashing) {
                        report = app.crashingReport;
                    } else if (app.notResponding) {
                        report = app.notRespondingReport;
                    }
                    
                    if (report != null) {
                        if (errList == null) {
                            errList = new ArrayList<ActivityManager.ProcessErrorStateInfo>(1);
                        }
                        errList.add(report);
                    } else {
                        Slog.w(TAG, "Missing app error report, app = " + app.processName + 
                                " crashing = " + app.crashing +
                                " notResponding = " + app.notResponding);
                    }
                }
            }
        }

        return errList;
    }
    
    public List<ActivityManager.RunningAppProcessInfo> getRunningAppProcesses() {
        // Lazy instantiation of list
        List<ActivityManager.RunningAppProcessInfo> runList = null;
        synchronized (this) {
            // Iterate across all processes
            for (int i=mLruProcesses.size()-1; i>=0; i--) {
                ProcessRecord app = mLruProcesses.get(i);
                if ((app.thread != null) && (!app.crashing && !app.notResponding)) {
                    // Generate process state info for running application
                    ActivityManager.RunningAppProcessInfo currApp = 
                        new ActivityManager.RunningAppProcessInfo(app.processName,
                                app.pid, app.getPackageList());
                    currApp.uid = app.info.uid;
                    int adj = app.curAdj;
                    if (adj >= EMPTY_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_EMPTY;
                    } else if (adj >= HIDDEN_APP_MIN_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
                        currApp.lru = adj - HIDDEN_APP_MIN_ADJ + 1;
                    } else if (adj >= HOME_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND;
                        currApp.lru = 0;
                    } else if (adj >= SECONDARY_SERVER_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE;
                    } else if (adj >= VISIBLE_APP_ADJ) {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE;
                    } else {
                        currApp.importance = ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
                    }
                    currApp.importanceReasonCode = app.adjTypeCode;
                    if (app.adjSource instanceof ProcessRecord) {
                        currApp.importanceReasonPid = ((ProcessRecord)app.adjSource).pid;
                    } else if (app.adjSource instanceof HistoryRecord) {
                        HistoryRecord r = (HistoryRecord)app.adjSource;
                        if (r.app != null) currApp.importanceReasonPid = r.app.pid;
                    }
                    if (app.adjTarget instanceof ComponentName) {
                        currApp.importanceReasonComponent = (ComponentName)app.adjTarget;
                    }
                    //Slog.v(TAG, "Proc " + app.processName + ": imp=" + currApp.importance
                    //        + " lru=" + currApp.lru);
                    if (runList == null) {
                        runList = new ArrayList<ActivityManager.RunningAppProcessInfo>();
                    }
                    runList.add(currApp);
                }
            }
        }
        return runList;
    }

    public List<ApplicationInfo> getRunningExternalApplications() {
        List<ActivityManager.RunningAppProcessInfo> runningApps = getRunningAppProcesses();
        List<ApplicationInfo> retList = new ArrayList<ApplicationInfo>();
        if (runningApps != null && runningApps.size() > 0) {
            Set<String> extList = new HashSet<String>();
            for (ActivityManager.RunningAppProcessInfo app : runningApps) {
                if (app.pkgList != null) {
                    for (String pkg : app.pkgList) {
                        extList.add(pkg);
                    }
                }
            }
            IPackageManager pm = ActivityThread.getPackageManager();
            for (String pkg : extList) {
                try {
                    ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
                    if ((info.flags & ApplicationInfo.FLAG_EXTERNAL_STORAGE) != 0) {
                        retList.add(info);
                    }
                } catch (RemoteException e) {
                }
            }
        }
        return retList;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (checkCallingPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump ActivityManager from from pid="
                    + Binder.getCallingPid()
                    + ", uid=" + Binder.getCallingUid()
                    + " without permission "
                    + android.Manifest.permission.DUMP);
            return;
        }
        
        boolean dumpAll = false;
        
        int opti = 0;
        while (opti < args.length) {
            String opt = args[opti];
            if (opt == null || opt.length() <= 0 || opt.charAt(0) != '-') {
                break;
            }
            opti++;
            if ("-a".equals(opt)) {
                dumpAll = true;
            } else if ("-h".equals(opt)) {
                pw.println("Activity manager dump options:");
                pw.println("  [-a] [-h] [cmd] ...");
                pw.println("  cmd may be one of:");
                pw.println("    activities: activity stack state");
                pw.println("    broadcasts: broadcast state");
                pw.println("    intents: pending intent state");
                pw.println("    processes: process state");
                pw.println("    providers: content provider state");
                pw.println("    services: service state");
                pw.println("    service [name]: service client-side state");
                return;
            } else {
                pw.println("Unknown argument: " + opt + "; use -h for help");
            }
        }
        
        // Is the caller requesting to dump a particular piece of data?
        if (opti < args.length) {
            String cmd = args[opti];
            opti++;
            if ("activities".equals(cmd) || "a".equals(cmd)) {
                synchronized (this) {
                    dumpActivitiesLocked(fd, pw, args, opti, true, true);
                }
                return;
            } else if ("broadcasts".equals(cmd) || "b".equals(cmd)) {
                synchronized (this) {
                    dumpBroadcastsLocked(fd, pw, args, opti, true);
                }
                return;
            } else if ("intents".equals(cmd) || "i".equals(cmd)) {
                synchronized (this) {
                    dumpPendingIntentsLocked(fd, pw, args, opti, true);
                }
                return;
            } else if ("processes".equals(cmd) || "p".equals(cmd)) {
                synchronized (this) {
                    dumpProcessesLocked(fd, pw, args, opti, true);
                }
                return;
            } else if ("providers".equals(cmd) || "prov".equals(cmd)) {
                synchronized (this) {
                    dumpProvidersLocked(fd, pw, args, opti, true);
                }
                return;
            } else if ("service".equals(cmd)) {
                dumpService(fd, pw, args, opti, true);
                return;
            } else if ("services".equals(cmd) || "s".equals(cmd)) {
                synchronized (this) {
                    dumpServicesLocked(fd, pw, args, opti, true);
                }
                return;
            }
        }
        
        // No piece of data specified, dump everything.
        synchronized (this) {
            boolean needSep;
            if (dumpAll) {
                pw.println("Providers in Current Activity Manager State:");
            }
            needSep = dumpProvidersLocked(fd, pw, args, opti, dumpAll);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
                pw.println("Broadcasts in Current Activity Manager State:");
            }
            needSep = dumpBroadcastsLocked(fd, pw, args, opti, dumpAll);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
                pw.println("Services in Current Activity Manager State:");
            }
            needSep = dumpServicesLocked(fd, pw, args, opti, dumpAll);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
                pw.println("PendingIntents in Current Activity Manager State:");
            }
            needSep = dumpPendingIntentsLocked(fd, pw, args, opti, dumpAll);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
                pw.println("Activities in Current Activity Manager State:");
            }
            needSep = dumpActivitiesLocked(fd, pw, args, opti, dumpAll, !dumpAll);
            if (needSep) {
                pw.println(" ");
            }
            if (dumpAll) {
                pw.println("-------------------------------------------------------------------------------");
                pw.println("Processes in Current Activity Manager State:");
            }
            dumpProcessesLocked(fd, pw, args, opti, dumpAll);
        }
    }
    
    boolean dumpActivitiesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll, boolean needHeader) {
        if (needHeader) {
            pw.println("  Activity stack:");
        }
        dumpHistoryList(pw, mHistory, "  ", "Hist", true);
        pw.println(" ");
        pw.println("  Running activities (most recent first):");
        dumpHistoryList(pw, mLRUActivities, "  ", "Run", false);
        if (mWaitingVisibleActivities.size() > 0) {
            pw.println(" ");
            pw.println("  Activities waiting for another to become visible:");
            dumpHistoryList(pw, mWaitingVisibleActivities, "  ", "Wait", false);
        }
        if (mStoppingActivities.size() > 0) {
            pw.println(" ");
            pw.println("  Activities waiting to stop:");
            dumpHistoryList(pw, mStoppingActivities, "  ", "Stop", false);
        }
        if (mFinishingActivities.size() > 0) {
            pw.println(" ");
            pw.println("  Activities waiting to finish:");
            dumpHistoryList(pw, mFinishingActivities, "  ", "Fin", false);
        }

        pw.println(" ");
        pw.println("  mPausingActivity: " + mPausingActivity);
        pw.println("  mResumedActivity: " + mResumedActivity);
        pw.println("  mFocusedActivity: " + mFocusedActivity);
        pw.println("  mLastPausedActivity: " + mLastPausedActivity);

        if (dumpAll && mRecentTasks.size() > 0) {
            pw.println(" ");
            pw.println("Recent tasks in Current Activity Manager State:");

            final int N = mRecentTasks.size();
            for (int i=0; i<N; i++) {
                TaskRecord tr = mRecentTasks.get(i);
                pw.print("  * Recent #"); pw.print(i); pw.print(": ");
                        pw.println(tr);
                mRecentTasks.get(i).dump(pw, "    ");
            }
        }
        
        pw.println(" ");
        pw.println("  mCurTask: " + mCurTask);
        
        return true;
    }
        
    boolean dumpProcessesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;
        int numPers = 0;

        if (dumpAll) {
            for (SparseArray<ProcessRecord> procs : mProcessNames.getMap().values()) {
                final int NA = procs.size();
                for (int ia=0; ia<NA; ia++) {
                    if (!needSep) {
                        pw.println("  All known processes:");
                        needSep = true;
                    }
                    ProcessRecord r = procs.valueAt(ia);
                    pw.print(r.persistent ? "  *PERS*" : "  *APP*");
                        pw.print(" UID "); pw.print(procs.keyAt(ia));
                        pw.print(" "); pw.println(r);
                    r.dump(pw, "    ");
                    if (r.persistent) {
                        numPers++;
                    }
                }
            }
        }
        
        if (mLruProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Running processes (most recent first):");
            dumpProcessList(pw, this, mLruProcesses, "    ",
                    "App ", "PERS", true);
            needSep = true;
        }

        synchronized (mPidsSelfLocked) {
            if (mPidsSelfLocked.size() > 0) {
                if (needSep) pw.println(" ");
                needSep = true;
                pw.println("  PID mappings:");
                for (int i=0; i<mPidsSelfLocked.size(); i++) {
                    pw.print("    PID #"); pw.print(mPidsSelfLocked.keyAt(i));
                        pw.print(": "); pw.println(mPidsSelfLocked.valueAt(i));
                }
            }
        }
        
        if (mForegroundProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Foreground Processes:");
            for (int i=0; i<mForegroundProcesses.size(); i++) {
                pw.print("    PID #"); pw.print(mForegroundProcesses.keyAt(i));
                        pw.print(": "); pw.println(mForegroundProcesses.valueAt(i));
            }
        }
        
        if (mPersistentStartingProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Persisent processes that are starting:");
            dumpProcessList(pw, this, mPersistentStartingProcesses, "    ",
                    "Starting Norm", "Restarting PERS", false);
        }

        if (mStartingProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Processes that are starting:");
            dumpProcessList(pw, this, mStartingProcesses, "    ",
                    "Starting Norm", "Starting PERS", false);
        }

        if (mRemovedProcesses.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Processes that are being removed:");
            dumpProcessList(pw, this, mRemovedProcesses, "    ",
                    "Removed Norm", "Removed PERS", false);
        }
        
        if (mProcessesOnHold.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Processes that are on old until the system is ready:");
            dumpProcessList(pw, this, mProcessesOnHold, "    ",
                    "OnHold Norm", "OnHold PERS", false);
        }

        if (mProcessesToGc.size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Processes that are waiting to GC:");
            long now = SystemClock.uptimeMillis();
            for (int i=0; i<mProcessesToGc.size(); i++) {
                ProcessRecord proc = mProcessesToGc.get(i);
                pw.print("    Process "); pw.println(proc);
                pw.print("      lowMem="); pw.print(proc.reportLowMemory);
                        pw.print(", last gced=");
                        pw.print(now-proc.lastRequestedGc);
                        pw.print(" ms ago, last lowMem=");
                        pw.print(now-proc.lastLowMemory);
                        pw.println(" ms ago");
                
            }
        }
        
        if (mProcessCrashTimes.getMap().size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Time since processes crashed:");
            long now = SystemClock.uptimeMillis();
            for (Map.Entry<String, SparseArray<Long>> procs
                    : mProcessCrashTimes.getMap().entrySet()) {
                SparseArray<Long> uids = procs.getValue();
                final int N = uids.size();
                for (int i=0; i<N; i++) {
                    pw.print("    Process "); pw.print(procs.getKey());
                            pw.print(" uid "); pw.print(uids.keyAt(i));
                            pw.print(": last crashed ");
                            pw.print((now-uids.valueAt(i)));
                            pw.println(" ms ago");
                }
            }
        }

        if (mBadProcesses.getMap().size() > 0) {
            if (needSep) pw.println(" ");
            needSep = true;
            pw.println("  Bad processes:");
            for (Map.Entry<String, SparseArray<Long>> procs
                    : mBadProcesses.getMap().entrySet()) {
                SparseArray<Long> uids = procs.getValue();
                final int N = uids.size();
                for (int i=0; i<N; i++) {
                    pw.print("    Bad process "); pw.print(procs.getKey());
                            pw.print(" uid "); pw.print(uids.keyAt(i));
                            pw.print(": crashed at time ");
                            pw.println(uids.valueAt(i));
                }
            }
        }

        pw.println(" ");
        pw.println("  mHomeProcess: " + mHomeProcess);
        pw.println("  mConfiguration: " + mConfiguration);
        pw.println("  mConfigWillChange: " + mConfigWillChange);
        pw.println("  mSleeping=" + mSleeping + " mShuttingDown=" + mShuttingDown);
        if (mDebugApp != null || mOrigDebugApp != null || mDebugTransient
                || mOrigWaitForDebugger) {
            pw.println("  mDebugApp=" + mDebugApp + "/orig=" + mOrigDebugApp
                    + " mDebugTransient=" + mDebugTransient
                    + " mOrigWaitForDebugger=" + mOrigWaitForDebugger);
        }
        if (mAlwaysFinishActivities || mController != null) {
            pw.println("  mAlwaysFinishActivities=" + mAlwaysFinishActivities
                    + " mController=" + mController);
        }
        if (dumpAll) {
            pw.println("  Total persistent processes: " + numPers);
            pw.println("  mStartRunning=" + mStartRunning
                    + " mSystemReady=" + mSystemReady
                    + " mBooting=" + mBooting
                    + " mBooted=" + mBooted
                    + " mFactoryTest=" + mFactoryTest);
            pw.println("  mGoingToSleep=" + mGoingToSleep);
            pw.println("  mLaunchingActivity=" + mLaunchingActivity);
            pw.println("  mAdjSeq=" + mAdjSeq + " mLruSeq=" + mLruSeq);
        }
        
        return true;
    }

    /**
     * There are three ways to call this:
     *  - no service specified: dump all the services
     *  - a flattened component name that matched an existing service was specified as the
     *    first arg: dump that one service
     *  - the first arg isn't the flattened component name of an existing service:
     *    dump all services whose component contains the first arg as a substring
     */
    protected void dumpService(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        String[] newArgs;
        String componentNameString;
        ServiceRecord r;
        if (opti >= args.length) {
            componentNameString = null;
            newArgs = EMPTY_STRING_ARRAY;
            r = null;
        } else {
            componentNameString = args[opti];
            opti++;
            ComponentName componentName = ComponentName.unflattenFromString(componentNameString);
            r = componentName != null ? mServices.get(componentName) : null;
            newArgs = new String[args.length - opti];
            if (args.length > 2) System.arraycopy(args, opti, newArgs, 0, args.length - opti);
        }

        if (r != null) {
            dumpService(fd, pw, r, newArgs);
        } else {
            for (ServiceRecord r1 : mServices.values()) {
                if (componentNameString == null
                        || r1.name.flattenToString().contains(componentNameString)) {
                    dumpService(fd, pw, r1, newArgs);
                }
            }
        }
    }

    /**
     * Invokes IApplicationThread.dumpService() on the thread of the specified service if
     * there is a thread associated with the service.
     */
    private void dumpService(FileDescriptor fd, PrintWriter pw, ServiceRecord r, String[] args) {
        pw.println("  Service " + r.name.flattenToString());
        if (r.app != null && r.app.thread != null) {
            try {
                // flush anything that is already in the PrintWriter since the thread is going
                // to write to the file descriptor directly
                pw.flush();
                r.app.thread.dumpService(fd, r, args);
                pw.print("\n");
            } catch (RemoteException e) {
                pw.println("got a RemoteException while dumping the service");
            }
        }
    }

    boolean dumpBroadcastsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;
        
        if (dumpAll) {
            if (mRegisteredReceivers.size() > 0) {
                pw.println(" ");
                pw.println("  Registered Receivers:");
                Iterator it = mRegisteredReceivers.values().iterator();
                while (it.hasNext()) {
                    ReceiverList r = (ReceiverList)it.next();
                    pw.print("  * "); pw.println(r);
                    r.dump(pw, "    ");
                }
            }
    
            pw.println(" ");
            pw.println("Receiver Resolver Table:");
            mReceiverResolver.dump(pw, null, "  ", null);
            needSep = true;
        }
        
        if (mParallelBroadcasts.size() > 0 || mOrderedBroadcasts.size() > 0
                || mPendingBroadcast != null) {
            if (mParallelBroadcasts.size() > 0) {
                pw.println(" ");
                pw.println("  Active broadcasts:");
            }
            for (int i=mParallelBroadcasts.size()-1; i>=0; i--) {
                pw.println("  Broadcast #" + i + ":");
                mParallelBroadcasts.get(i).dump(pw, "    ");
            }
            if (mOrderedBroadcasts.size() > 0) {
                pw.println(" ");
                pw.println("  Active ordered broadcasts:");
            }
            for (int i=mOrderedBroadcasts.size()-1; i>=0; i--) {
                pw.println("  Serialized Broadcast #" + i + ":");
                mOrderedBroadcasts.get(i).dump(pw, "    ");
            }
            pw.println(" ");
            pw.println("  Pending broadcast:");
            if (mPendingBroadcast != null) {
                mPendingBroadcast.dump(pw, "    ");
            } else {
                pw.println("    (null)");
            }
            needSep = true;
        }

        if (dumpAll) {
            pw.println(" ");
            pw.println("  Historical broadcasts:");
            for (int i=0; i<MAX_BROADCAST_HISTORY; i++) {
                BroadcastRecord r = mBroadcastHistory[i];
                if (r == null) {
                    break;
                }
                pw.println("  Historical Broadcast #" + i + ":");
                r.dump(pw, "    ");
            }
            needSep = true;
        }
        
        if (mStickyBroadcasts != null) {
            pw.println(" ");
            pw.println("  Sticky broadcasts:");
            StringBuilder sb = new StringBuilder(128);
            for (Map.Entry<String, ArrayList<Intent>> ent
                    : mStickyBroadcasts.entrySet()) {
                pw.print("  * Sticky action "); pw.print(ent.getKey());
                        pw.println(":");
                ArrayList<Intent> intents = ent.getValue();
                final int N = intents.size();
                for (int i=0; i<N; i++) {
                    sb.setLength(0);
                    sb.append("    Intent: ");
                    intents.get(i).toShortString(sb, true, false);
                    pw.println(sb.toString());
                    Bundle bundle = intents.get(i).getExtras();
                    if (bundle != null) {
                        pw.print("      ");
                        pw.println(bundle.toString());
                    }
                }
            }
            needSep = true;
        }
        
        if (dumpAll) {
            pw.println(" ");
            pw.println("  mBroadcastsScheduled=" + mBroadcastsScheduled);
            pw.println("  mHandler:");
            mHandler.dump(new PrintWriterPrinter(pw), "    ");
            needSep = true;
        }
        
        return needSep;
    }

    boolean dumpServicesLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;

        if (dumpAll) {
            if (mServices.size() > 0) {
                pw.println("  Active services:");
                Iterator<ServiceRecord> it = mServices.values().iterator();
                while (it.hasNext()) {
                    ServiceRecord r = it.next();
                    pw.print("  * "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }
        }

        if (mPendingServices.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Pending services:");
            for (int i=0; i<mPendingServices.size(); i++) {
                ServiceRecord r = mPendingServices.get(i);
                pw.print("  * Pending "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (mRestartingServices.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Restarting services:");
            for (int i=0; i<mRestartingServices.size(); i++) {
                ServiceRecord r = mRestartingServices.get(i);
                pw.print("  * Restarting "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (mStoppingServices.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Stopping services:");
            for (int i=0; i<mStoppingServices.size(); i++) {
                ServiceRecord r = mStoppingServices.get(i);
                pw.print("  * Stopping "); pw.println(r);
                r.dump(pw, "    ");
            }
            needSep = true;
        }

        if (dumpAll) {
            if (mServiceConnections.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Connection bindings to services:");
                Iterator<ConnectionRecord> it
                        = mServiceConnections.values().iterator();
                while (it.hasNext()) {
                    ConnectionRecord r = it.next();
                    pw.print("  * "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }
        }
        
        return needSep;
    }

    boolean dumpProvidersLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;

        if (dumpAll) {
            if (mProvidersByClass.size() > 0) {
                if (needSep) pw.println(" ");
                pw.println("  Published content providers (by class):");
                Iterator it = mProvidersByClass.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry e = (Map.Entry)it.next();
                    ContentProviderRecord r = (ContentProviderRecord)e.getValue();
                    pw.print("  * "); pw.println(r);
                    r.dump(pw, "    ");
                }
                needSep = true;
            }
    
            if (mProvidersByName.size() > 0) {
                pw.println(" ");
                pw.println("  Authority to provider mappings:");
                Iterator it = mProvidersByName.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry e = (Map.Entry)it.next();
                    ContentProviderRecord r = (ContentProviderRecord)e.getValue();
                    pw.print("  "); pw.print(e.getKey()); pw.print(": ");
                            pw.println(r);
                }
                needSep = true;
            }
        }

        if (mLaunchingProviders.size() > 0) {
            if (needSep) pw.println(" ");
            pw.println("  Launching content providers:");
            for (int i=mLaunchingProviders.size()-1; i>=0; i--) {
                pw.print("  Launching #"); pw.print(i); pw.print(": ");
                        pw.println(mLaunchingProviders.get(i));
            }
            needSep = true;
        }

        if (mGrantedUriPermissions.size() > 0) {
            pw.println();
            pw.println("Granted Uri Permissions:");
            for (int i=0; i<mGrantedUriPermissions.size(); i++) {
                int uid = mGrantedUriPermissions.keyAt(i);
                HashMap<Uri, UriPermission> perms
                        = mGrantedUriPermissions.valueAt(i);
                pw.print("  * UID "); pw.print(uid);
                        pw.println(" holds:");
                for (UriPermission perm : perms.values()) {
                    pw.print("    "); pw.println(perm);
                    perm.dump(pw, "      ");
                }
            }
            needSep = true;
        }
        
        return needSep;
    }

    boolean dumpPendingIntentsLocked(FileDescriptor fd, PrintWriter pw, String[] args,
            int opti, boolean dumpAll) {
        boolean needSep = false;
        
        if (dumpAll) {
            if (this.mIntentSenderRecords.size() > 0) {
                Iterator<WeakReference<PendingIntentRecord>> it
                        = mIntentSenderRecords.values().iterator();
                while (it.hasNext()) {
                    WeakReference<PendingIntentRecord> ref = it.next();
                    PendingIntentRecord rec = ref != null ? ref.get(): null;
                    needSep = true;
                    if (rec != null) {
                        pw.print("  * "); pw.println(rec);
                        rec.dump(pw, "    ");
                    } else {
                        pw.print("  * "); pw.print(ref);
                    }
                }
            }
        }
        
        return needSep;
    }

    private static final void dumpHistoryList(PrintWriter pw, List list,
            String prefix, String label, boolean complete) {
        TaskRecord lastTask = null;
        for (int i=list.size()-1; i>=0; i--) {
            HistoryRecord r = (HistoryRecord)list.get(i);
            final boolean full = complete || !r.inHistory;
            if (lastTask != r.task) {
                lastTask = r.task;
                pw.print(prefix);
                pw.print(full ? "* " : "  ");
                pw.println(lastTask);
                if (full) {
                    lastTask.dump(pw, prefix + "  ");
                }
            }
            pw.print(prefix); pw.print(full ? "  * " : "    "); pw.print(label);
            pw.print(" #"); pw.print(i); pw.print(": ");
            pw.println(r);
            if (full) {
                r.dump(pw, prefix + "      ");
            }
        }
    }

    private static String buildOomTag(String prefix, String space, int val, int base) {
        if (val == base) {
            if (space == null) return prefix;
            return prefix + "  ";
        }
        return prefix + "+" + Integer.toString(val-base);
    }
    
    private static final int dumpProcessList(PrintWriter pw,
            ActivityManagerService service, List list,
            String prefix, String normalLabel, String persistentLabel,
            boolean inclOomAdj) {
        int numPers = 0;
        for (int i=list.size()-1; i>=0; i--) {
            ProcessRecord r = (ProcessRecord)list.get(i);
            if (false) {
                pw.println(prefix + (r.persistent ? persistentLabel : normalLabel)
                      + " #" + i + ":");
                r.dump(pw, prefix + "  ");
            } else if (inclOomAdj) {
                String oomAdj;
                if (r.setAdj >= EMPTY_APP_ADJ) {
                    oomAdj = buildOomTag("empty", null, r.setAdj, EMPTY_APP_ADJ);
                } else if (r.setAdj >= HIDDEN_APP_MIN_ADJ) {
                    oomAdj = buildOomTag("bak", "  ", r.setAdj, HIDDEN_APP_MIN_ADJ);
                } else if (r.setAdj >= HOME_APP_ADJ) {
                    oomAdj = buildOomTag("home ", null, r.setAdj, HOME_APP_ADJ);
                } else if (r.setAdj >= SECONDARY_SERVER_ADJ) {
                    oomAdj = buildOomTag("svc", "  ", r.setAdj, SECONDARY_SERVER_ADJ);
                } else if (r.setAdj >= BACKUP_APP_ADJ) {
                    oomAdj = buildOomTag("bckup", null, r.setAdj, BACKUP_APP_ADJ);
                } else if (r.setAdj >= VISIBLE_APP_ADJ) {
                    oomAdj = buildOomTag("vis  ", null, r.setAdj, VISIBLE_APP_ADJ);
                } else if (r.setAdj >= FOREGROUND_APP_ADJ) {
                    oomAdj = buildOomTag("fore ", null, r.setAdj, FOREGROUND_APP_ADJ);
                } else if (r.setAdj >= CORE_SERVER_ADJ) {
                    oomAdj = buildOomTag("core ", null, r.setAdj, CORE_SERVER_ADJ);
                } else if (r.setAdj >= SYSTEM_ADJ) {
                    oomAdj = buildOomTag("sys  ", null, r.setAdj, SYSTEM_ADJ);
                } else {
                    oomAdj = Integer.toString(r.setAdj);
                }
                String schedGroup;
                switch (r.setSchedGroup) {
                    case Process.THREAD_GROUP_BG_NONINTERACTIVE:
                        schedGroup = "B";
                        break;
                    case Process.THREAD_GROUP_DEFAULT:
                        schedGroup = "F";
                        break;
                    default:
                        schedGroup = Integer.toString(r.setSchedGroup);
                        break;
                }
                pw.println(String.format("%s%s #%2d: adj=%s/%s %s (%s)",
                        prefix, (r.persistent ? persistentLabel : normalLabel),
                        i, oomAdj, schedGroup, r.toShortString(), r.adjType));
                if (r.adjSource != null || r.adjTarget != null) {
                    pw.println(prefix + "          " + r.adjTarget
                            + "<=" + r.adjSource);
                }
            } else {
                pw.println(String.format("%s%s #%2d: %s",
                        prefix, (r.persistent ? persistentLabel : normalLabel),
                        i, r.toString()));
            }
            if (r.persistent) {
                numPers++;
            }
        }
        return numPers;
    }

    static final void dumpApplicationMemoryUsage(FileDescriptor fd,
            PrintWriter pw, List list, String prefix, String[] args) {
        final boolean isCheckinRequest = scanArgs(args, "--checkin");
        long uptime = SystemClock.uptimeMillis();
        long realtime = SystemClock.elapsedRealtime();
        
        if (isCheckinRequest) {
            // short checkin version
            pw.println(uptime + "," + realtime);
            pw.flush();
        } else {
            pw.println("Applications Memory Usage (kB):");
            pw.println("Uptime: " + uptime + " Realtime: " + realtime);
        }
        for (int i = list.size() - 1 ; i >= 0 ; i--) {
            ProcessRecord r = (ProcessRecord)list.get(i);
            if (r.thread != null) {
                if (!isCheckinRequest) {
                    pw.println("\n** MEMINFO in pid " + r.pid + " [" + r.processName + "] **");
                    pw.flush();
                }
                try {
                    r.thread.asBinder().dump(fd, args);
                } catch (RemoteException e) {
                    if (!isCheckinRequest) {
                        pw.println("Got RemoteException!");
                        pw.flush();
                    }
                }
            }
        }
    }

    /**
     * Searches array of arguments for the specified string
     * @param args array of argument strings
     * @param value value to search for
     * @return true if the value is contained in the array
     */
    private static boolean scanArgs(String[] args, String value) {
        if (args != null) {
            for (String arg : args) {
                if (value.equals(arg)) {
                    return true;
                }
            }
        }
        return false;
    }

    private final int indexOfTokenLocked(IBinder token) {
        int count = mHistory.size();

        // convert the token to an entry in the history.
        int index = -1;
        for (int i=count-1; i>=0; i--) {
            Object o = mHistory.get(i);
            if (o == token) {
                index = i;
                break;
            }
        }

        return index;
    }

    private final void killServicesLocked(ProcessRecord app,
            boolean allowRestart) {
        // Report disconnected services.
        if (false) {
            // XXX we are letting the client link to the service for
            // death notifications.
            if (app.services.size() > 0) {
                Iterator it = app.services.iterator();
                while (it.hasNext()) {
                    ServiceRecord r = (ServiceRecord)it.next();
                    if (r.connections.size() > 0) {
                        Iterator<ConnectionRecord> jt
                                = r.connections.values().iterator();
                        while (jt.hasNext()) {
                            ConnectionRecord c = jt.next();
                            if (c.binding.client != app) {
                                try {
                                    //c.conn.connected(r.className, null);
                                } catch (Exception e) {
                                    // todo: this should be asynchronous!
                                    Slog.w(TAG, "Exception thrown disconnected servce "
                                          + r.shortName
                                          + " from app " + app.processName, e);
                                }
                            }
                        }
                    }
                }
            }
        }

        // Clean up any connections this application has to other services.
        if (app.connections.size() > 0) {
            Iterator<ConnectionRecord> it = app.connections.iterator();
            while (it.hasNext()) {
                ConnectionRecord r = it.next();
                removeConnectionLocked(r, app, null);
            }
        }
        app.connections.clear();

        if (app.services.size() != 0) {
            // Any services running in the application need to be placed
            // back in the pending list.
            Iterator it = app.services.iterator();
            while (it.hasNext()) {
                ServiceRecord sr = (ServiceRecord)it.next();
                synchronized (sr.stats.getBatteryStats()) {
                    sr.stats.stopLaunchedLocked();
                }
                sr.app = null;
                sr.executeNesting = 0;
                mStoppingServices.remove(sr);
                
                boolean hasClients = sr.bindings.size() > 0;
                if (hasClients) {
                    Iterator<IntentBindRecord> bindings
                            = sr.bindings.values().iterator();
                    while (bindings.hasNext()) {
                        IntentBindRecord b = bindings.next();
                        if (DEBUG_SERVICE) Slog.v(TAG, "Killing binding " + b
                                + ": shouldUnbind=" + b.hasBound);
                        b.binder = null;
                        b.requested = b.received = b.hasBound = false;
                    }
                }

                if (sr.crashCount >= 2) {
                    Slog.w(TAG, "Service crashed " + sr.crashCount
                            + " times, stopping: " + sr);
                    EventLog.writeEvent(EventLogTags.AM_SERVICE_CRASHED_TOO_MUCH,
                            sr.crashCount, sr.shortName, app.pid);
                    bringDownServiceLocked(sr, true);
                } else if (!allowRestart) {
                    bringDownServiceLocked(sr, true);
                } else {
                    boolean canceled = scheduleServiceRestartLocked(sr, true);
                    
                    // Should the service remain running?  Note that in the
                    // extreme case of so many attempts to deliver a command
                    // that it failed, that we also will stop it here.
                    if (sr.startRequested && (sr.stopIfKilled || canceled)) {
                        if (sr.pendingStarts.size() == 0) {
                            sr.startRequested = false;
                            if (!hasClients) {
                                // Whoops, no reason to restart!
                                bringDownServiceLocked(sr, true);
                            }
                        }
                    }
                }
            }

            if (!allowRestart) {
                app.services.clear();
            }
        }

        // Make sure we have no more records on the stopping list.
        int i = mStoppingServices.size();
        while (i > 0) {
            i--;
            ServiceRecord sr = mStoppingServices.get(i);
            if (sr.app == app) {
                mStoppingServices.remove(i);
            }
        }
        
        app.executingServices.clear();
    }

    private final void removeDyingProviderLocked(ProcessRecord proc,
            ContentProviderRecord cpr) {
        synchronized (cpr) {
            cpr.launchingApp = null;
            cpr.notifyAll();
        }
        
        mProvidersByClass.remove(cpr.info.name);
        String names[] = cpr.info.authority.split(";");
        for (int j = 0; j < names.length; j++) {
            mProvidersByName.remove(names[j]);
        }
        
        Iterator<ProcessRecord> cit = cpr.clients.iterator();
        while (cit.hasNext()) {
            ProcessRecord capp = cit.next();
            if (!capp.persistent && capp.thread != null
                    && capp.pid != 0
                    && capp.pid != MY_PID) {
                Slog.i(TAG, "Kill " + capp.processName
                        + " (pid " + capp.pid + "): provider " + cpr.info.name
                        + " in dying process " + proc.processName);
                EventLog.writeEvent(EventLogTags.AM_KILL, capp.pid,
                        capp.processName, capp.setAdj, "dying provider " + proc.processName);
                Process.killProcess(capp.pid);
            }
        }
        
        mLaunchingProviders.remove(cpr);
    }
    
    /**
     * Main code for cleaning up a process when it has gone away.  This is
     * called both as a result of the process dying, or directly when stopping 
     * a process when running in single process mode.
     */
    private final void cleanUpApplicationRecordLocked(ProcessRecord app,
            boolean restarting, int index) {
        if (index >= 0) {
            mLruProcesses.remove(index);
        }

        mProcessesToGc.remove(app);
        
        // Dismiss any open dialogs.
        if (app.crashDialog != null) {
            app.crashDialog.dismiss();
            app.crashDialog = null;
        }
        if (app.anrDialog != null) {
            app.anrDialog.dismiss();
            app.anrDialog = null;
        }
        if (app.waitDialog != null) {
            app.waitDialog.dismiss();
            app.waitDialog = null;
        }

        app.crashing = false;
        app.notResponding = false;
        
        app.resetPackageList();
        app.thread = null;
        app.forcingToForeground = null;
        app.foregroundServices = false;

        killServicesLocked(app, true);

        boolean restart = false;

        int NL = mLaunchingProviders.size();
        
        // Remove published content providers.
        if (!app.pubProviders.isEmpty()) {
            Iterator it = app.pubProviders.values().iterator();
            while (it.hasNext()) {
                ContentProviderRecord cpr = (ContentProviderRecord)it.next();
                cpr.provider = null;
                cpr.app = null;

                // See if someone is waiting for this provider...  in which
                // case we don't remove it, but just let it restart.
                int i = 0;
                if (!app.bad) {
                    for (; i<NL; i++) {
                        if (mLaunchingProviders.get(i) == cpr) {
                            restart = true;
                            break;
                        }
                    }
                } else {
                    i = NL;
                }

                if (i >= NL) {
                    removeDyingProviderLocked(app, cpr);
                    NL = mLaunchingProviders.size();
                }
            }
            app.pubProviders.clear();
        }
        
        // Take care of any launching providers waiting for this process.
        if (checkAppInLaunchingProvidersLocked(app, false)) {
            restart = true;
        }
        
        // Unregister from connected content providers.
        if (!app.conProviders.isEmpty()) {
            Iterator it = app.conProviders.keySet().iterator();
            while (it.hasNext()) {
                ContentProviderRecord cpr = (ContentProviderRecord)it.next();
                cpr.clients.remove(app);
            }
            app.conProviders.clear();
        }

        // At this point there may be remaining entries in mLaunchingProviders
        // where we were the only one waiting, so they are no longer of use.
        // Look for these and clean up if found.
        // XXX Commented out for now.  Trying to figure out a way to reproduce
        // the actual situation to identify what is actually going on.
        if (false) {
            for (int i=0; i<NL; i++) {
                ContentProviderRecord cpr = (ContentProviderRecord)
                        mLaunchingProviders.get(i);
                if (cpr.clients.size() <= 0 && cpr.externals <= 0) {
                    synchronized (cpr) {
                        cpr.launchingApp = null;
                        cpr.notifyAll();
                    }
                }
            }
        }
        
        skipCurrentReceiverLocked(app);

        // Unregister any receivers.
        if (app.receivers.size() > 0) {
            Iterator<ReceiverList> it = app.receivers.iterator();
            while (it.hasNext()) {
                removeReceiverLocked(it.next());
            }
            app.receivers.clear();
        }
        
        // If the app is undergoing backup, tell the backup manager about it
        if (mBackupTarget != null && app.pid == mBackupTarget.app.pid) {
            if (DEBUG_BACKUP) Slog.d(TAG, "App " + mBackupTarget.appInfo + " died during backup");
            try {
                IBackupManager bm = IBackupManager.Stub.asInterface(
                        ServiceManager.getService(Context.BACKUP_SERVICE));
                bm.agentDisconnected(app.info.packageName);
            } catch (RemoteException e) {
                // can't happen; backup manager is local
            }
        }

        // If the caller is restarting this app, then leave it in its
        // current lists and let the caller take care of it.
        if (restarting) {
            return;
        }

        if (!app.persistent) {
            if (DEBUG_PROCESSES) Slog.v(TAG,
                    "Removing non-persistent process during cleanup: " + app);
            mProcessNames.remove(app.processName, app.info.uid);
        } else if (!app.removed) {
            // This app is persistent, so we need to keep its record around.
            // If it is not already on the pending app list, add it there
            // and start a new process for it.
            app.thread = null;
            app.forcingToForeground = null;
            app.foregroundServices = false;
            if (mPersistentStartingProcesses.indexOf(app) < 0) {
                mPersistentStartingProcesses.add(app);
                restart = true;
            }
        }
        mProcessesOnHold.remove(app);

        if (app == mHomeProcess) {
            mHomeProcess = null;
        }
        
        if (restart) {
            // We have components that still need to be running in the
            // process, so re-launch it.
            mProcessNames.put(app.processName, app.info.uid, app);
            startProcessLocked(app, "restart", app.processName);
        } else if (app.pid > 0 && app.pid != MY_PID) {
            // Goodbye!
            synchronized (mPidsSelfLocked) {
                mPidsSelfLocked.remove(app.pid);
                mHandler.removeMessages(PROC_START_TIMEOUT_MSG, app);
            }
            app.setPid(0);
        }
    }

    boolean checkAppInLaunchingProvidersLocked(ProcessRecord app, boolean alwaysBad) {
        // Look through the content providers we are waiting to have launched,
        // and if any run in this process then either schedule a restart of
        // the process or kill the client waiting for it if this process has
        // gone bad.
        int NL = mLaunchingProviders.size();
        boolean restart = false;
        for (int i=0; i<NL; i++) {
            ContentProviderRecord cpr = (ContentProviderRecord)
                    mLaunchingProviders.get(i);
            if (cpr.launchingApp == app) {
                if (!alwaysBad && !app.bad) {
                    restart = true;
                } else {
                    removeDyingProviderLocked(app, cpr);
                    NL = mLaunchingProviders.size();
                }
            }
        }
        return restart;
    }
    
    // =========================================================
    // SERVICES
    // =========================================================

    ActivityManager.RunningServiceInfo makeRunningServiceInfoLocked(ServiceRecord r) {
        ActivityManager.RunningServiceInfo info =
            new ActivityManager.RunningServiceInfo();
        info.service = r.name;
        if (r.app != null) {
            info.pid = r.app.pid;
        }
        info.uid = r.appInfo.uid;
        info.process = r.processName;
        info.foreground = r.isForeground;
        info.activeSince = r.createTime;
        info.started = r.startRequested;
        info.clientCount = r.connections.size();
        info.crashCount = r.crashCount;
        info.lastActivityTime = r.lastActivity;
        if (r.isForeground) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_FOREGROUND;
        }
        if (r.startRequested) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_STARTED;
        }
        if (r.app != null && r.app.pid == MY_PID) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_SYSTEM_PROCESS;
        }
        if (r.app != null && r.app.persistent) {
            info.flags |= ActivityManager.RunningServiceInfo.FLAG_PERSISTENT_PROCESS;
        }
        for (ConnectionRecord conn : r.connections.values()) {
            if (conn.clientLabel != 0) {
                info.clientPackage = conn.binding.client.info.packageName;
                info.clientLabel = conn.clientLabel;
                break;
            }
        }
        return info;
    }
    
    public List<ActivityManager.RunningServiceInfo> getServices(int maxNum,
            int flags) {
        synchronized (this) {
            ArrayList<ActivityManager.RunningServiceInfo> res
                    = new ArrayList<ActivityManager.RunningServiceInfo>();
            
            if (mServices.size() > 0) {
                Iterator<ServiceRecord> it = mServices.values().iterator();
                while (it.hasNext() && res.size() < maxNum) {
                    res.add(makeRunningServiceInfoLocked(it.next()));
                }
            }

            for (int i=0; i<mRestartingServices.size() && res.size() < maxNum; i++) {
                ServiceRecord r = mRestartingServices.get(i);
                ActivityManager.RunningServiceInfo info =
                        makeRunningServiceInfoLocked(r);
                info.restarting = r.nextRestartTime;
                res.add(info);
            }
            
            return res;
        }
    }

    public PendingIntent getRunningServiceControlPanel(ComponentName name) {
        synchronized (this) {
            ServiceRecord r = mServices.get(name);
            if (r != null) {
                for (ConnectionRecord conn : r.connections.values()) {
                    if (conn.clientIntent != null) {
                        return conn.clientIntent;
                    }
                }
            }
        }
        return null;
    }
    
    private final ServiceRecord findServiceLocked(ComponentName name,
            IBinder token) {
        ServiceRecord r = mServices.get(name);
        return r == token ? r : null;
    }

    private final class ServiceLookupResult {
        final ServiceRecord record;
        final String permission;

        ServiceLookupResult(ServiceRecord _record, String _permission) {
            record = _record;
            permission = _permission;
        }
    };

    private ServiceLookupResult findServiceLocked(Intent service,
            String resolvedType) {
        ServiceRecord r = null;
        if (service.getComponent() != null) {
            r = mServices.get(service.getComponent());
        }
        if (r == null) {
            Intent.FilterComparison filter = new Intent.FilterComparison(service);
            r = mServicesByIntent.get(filter);
        }

        if (r == null) {
            try {
                ResolveInfo rInfo =
                    ActivityThread.getPackageManager().resolveService(
                            service, resolvedType, 0);
                ServiceInfo sInfo =
                    rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    return null;
                }

                ComponentName name = new ComponentName(
                        sInfo.applicationInfo.packageName, sInfo.name);
                r = mServices.get(name);
            } catch (RemoteException ex) {
                // pm is in same process, this will never happen.
            }
        }
        if (r != null) {
            int callingPid = Binder.getCallingPid();
            int callingUid = Binder.getCallingUid();
            if (checkComponentPermission(r.permission,
                    callingPid, callingUid, r.exported ? -1 : r.appInfo.uid)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                        + " from pid=" + callingPid
                        + ", uid=" + callingUid
                        + " requires " + r.permission);
                return new ServiceLookupResult(null, r.permission);
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private class ServiceRestarter implements Runnable {
        private ServiceRecord mService;

        void setService(ServiceRecord service) {
            mService = service;
        }

        public void run() {
            synchronized(ActivityManagerService.this) {
                performServiceRestartLocked(mService);
            }
        }
    }

    private ServiceLookupResult retrieveServiceLocked(Intent service,
            String resolvedType, int callingPid, int callingUid) {
        ServiceRecord r = null;
        if (service.getComponent() != null) {
            r = mServices.get(service.getComponent());
        }
        Intent.FilterComparison filter = new Intent.FilterComparison(service);
        r = mServicesByIntent.get(filter);
        if (r == null) {
            try {
                ResolveInfo rInfo =
                    ActivityThread.getPackageManager().resolveService(
                            service, resolvedType, STOCK_PM_FLAGS);
                ServiceInfo sInfo =
                    rInfo != null ? rInfo.serviceInfo : null;
                if (sInfo == null) {
                    Slog.w(TAG, "Unable to start service " + service +
                          ": not found");
                    return null;
                }

                ComponentName name = new ComponentName(
                        sInfo.applicationInfo.packageName, sInfo.name);
                r = mServices.get(name);
                if (r == null) {
                    filter = new Intent.FilterComparison(service.cloneFilter());
                    ServiceRestarter res = new ServiceRestarter();
                    BatteryStatsImpl.Uid.Pkg.Serv ss = null;
                    BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
                    synchronized (stats) {
                        ss = stats.getServiceStatsLocked(
                                sInfo.applicationInfo.uid, sInfo.packageName,
                                sInfo.name);
                    }
                    r = new ServiceRecord(this, ss, name, filter, sInfo, res);
                    res.setService(r);
                    mServices.put(name, r);
                    mServicesByIntent.put(filter, r);
                    
                    // Make sure this component isn't in the pending list.
                    int N = mPendingServices.size();
                    for (int i=0; i<N; i++) {
                        ServiceRecord pr = mPendingServices.get(i);
                        if (pr.name.equals(name)) {
                            mPendingServices.remove(i);
                            i--;
                            N--;
                        }
                    }
                }
            } catch (RemoteException ex) {
                // pm is in same process, this will never happen.
            }
        }
        if (r != null) {
            if (checkComponentPermission(r.permission,
                    callingPid, callingUid, r.exported ? -1 : r.appInfo.uid)
                    != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: Accessing service " + r.name
                        + " from pid=" + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + r.permission);
                return new ServiceLookupResult(null, r.permission);
            }
            return new ServiceLookupResult(r, null);
        }
        return null;
    }

    private final void bumpServiceExecutingLocked(ServiceRecord r) {
        long now = SystemClock.uptimeMillis();
        if (r.executeNesting == 0 && r.app != null) {
            if (r.app.executingServices.size() == 0) {
                Message msg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                msg.obj = r.app;
                mHandler.sendMessageAtTime(msg, now+SERVICE_TIMEOUT);
            }
            r.app.executingServices.add(r);
        }
        r.executeNesting++;
        r.executingStart = now;
    }

    private final void sendServiceArgsLocked(ServiceRecord r,
            boolean oomAdjusted) {
        final int N = r.pendingStarts.size();
        if (N == 0) {
            return;
        }

        int i = 0;
        while (i < N) {
            try {
                ServiceRecord.StartItem si = r.pendingStarts.get(i);
                if (DEBUG_SERVICE) Slog.v(TAG, "Sending arguments to service: "
                        + r.name + " " + r.intent + " args=" + si.intent);
                if (si.intent == null && N > 1) {
                    // If somehow we got a dummy start at the front, then
                    // just drop it here.
                    i++;
                    continue;
                }
                bumpServiceExecutingLocked(r);
                if (!oomAdjusted) {
                    oomAdjusted = true;
                    updateOomAdjLocked(r.app);
                }
                int flags = 0;
                if (si.deliveryCount > 0) {
                    flags |= Service.START_FLAG_RETRY;
                }
                if (si.doneExecutingCount > 0) {
                    flags |= Service.START_FLAG_REDELIVERY;
                }
                r.app.thread.scheduleServiceArgs(r, si.id, flags, si.intent);
                si.deliveredTime = SystemClock.uptimeMillis();
                r.deliveredStarts.add(si);
                si.deliveryCount++;
                i++;
            } catch (RemoteException e) {
                // Remote process gone...  we'll let the normal cleanup take
                // care of this.
                break;
            } catch (Exception e) {
                Slog.w(TAG, "Unexpected exception", e);
                break;
            }
        }
        if (i == N) {
            r.pendingStarts.clear();
        } else {
            while (i > 0) {
                i--;
                r.pendingStarts.remove(i);
            }
        }
    }

    private final boolean requestServiceBindingLocked(ServiceRecord r,
            IntentBindRecord i, boolean rebind) {
        if (r.app == null || r.app.thread == null) {
            // If service is not currently running, can't yet bind.
            return false;
        }
        if ((!i.requested || rebind) && i.apps.size() > 0) {
            try {
                bumpServiceExecutingLocked(r);
                if (DEBUG_SERVICE) Slog.v(TAG, "Connecting binding " + i
                        + ": shouldUnbind=" + i.hasBound);
                r.app.thread.scheduleBindService(r, i.intent.getIntent(), rebind);
                if (!rebind) {
                    i.requested = true;
                }
                i.hasBound = true;
                i.doRebind = false;
            } catch (RemoteException e) {
                return false;
            }
        }
        return true;
    }

    private final void requestServiceBindingsLocked(ServiceRecord r) {
        Iterator<IntentBindRecord> bindings = r.bindings.values().iterator();
        while (bindings.hasNext()) {
            IntentBindRecord i = bindings.next();
            if (!requestServiceBindingLocked(r, i, false)) {
                break;
            }
        }
    }

    private final void realStartServiceLocked(ServiceRecord r,
            ProcessRecord app) throws RemoteException {
        if (app.thread == null) {
            throw new RemoteException();
        }

        r.app = app;
        r.restartTime = r.lastActivity = SystemClock.uptimeMillis();

        app.services.add(r);
        bumpServiceExecutingLocked(r);
        updateLruProcessLocked(app, true, true);

        boolean created = false;
        try {
            if (DEBUG_SERVICE) Slog.v(TAG, "Scheduling start service: "
                    + r.name + " " + r.intent);
            mStringBuilder.setLength(0);
            r.intent.getIntent().toShortString(mStringBuilder, false, true);
            EventLog.writeEvent(EventLogTags.AM_CREATE_SERVICE,
                    System.identityHashCode(r), r.shortName,
                    mStringBuilder.toString(), r.app.pid);
            synchronized (r.stats.getBatteryStats()) {
                r.stats.startLaunchedLocked();
            }
            ensurePackageDexOpt(r.serviceInfo.packageName);
            app.thread.scheduleCreateService(r, r.serviceInfo);
            r.postNotification();
            created = true;
        } finally {
            if (!created) {
                app.services.remove(r);
                scheduleServiceRestartLocked(r, false);
            }
        }

        requestServiceBindingsLocked(r);
        
        // If the service is in the started state, and there are no
        // pending arguments, then fake up one so its onStartCommand() will
        // be called.
        if (r.startRequested && r.callStart && r.pendingStarts.size() == 0) {
            r.lastStartId++;
            if (r.lastStartId < 1) {
                r.lastStartId = 1;
            }
            r.pendingStarts.add(new ServiceRecord.StartItem(r.lastStartId, null));
        }
        
        sendServiceArgsLocked(r, true);
    }

    private final boolean scheduleServiceRestartLocked(ServiceRecord r,
            boolean allowCancel) {
        boolean canceled = false;
        
        final long now = SystemClock.uptimeMillis();
        long minDuration = SERVICE_RESTART_DURATION;
        long resetTime = SERVICE_RESET_RUN_DURATION;
        
        // Any delivered but not yet finished starts should be put back
        // on the pending list.
        final int N = r.deliveredStarts.size();
        if (N > 0) {
            for (int i=N-1; i>=0; i--) {
                ServiceRecord.StartItem si = r.deliveredStarts.get(i);
                if (si.intent == null) {
                    // We'll generate this again if needed.
                } else if (!allowCancel || (si.deliveryCount < ServiceRecord.MAX_DELIVERY_COUNT
                        && si.doneExecutingCount < ServiceRecord.MAX_DONE_EXECUTING_COUNT)) {
                    r.pendingStarts.add(0, si);
                    long dur = SystemClock.uptimeMillis() - si.deliveredTime;
                    dur *= 2;
                    if (minDuration < dur) minDuration = dur;
                    if (resetTime < dur) resetTime = dur;
                } else {
                    Slog.w(TAG, "Canceling start item " + si.intent + " in service "
                            + r.name);
                    canceled = true;
                }
            }
            r.deliveredStarts.clear();
        }
        
        r.totalRestartCount++;
        if (r.restartDelay == 0) {
            r.restartCount++;
            r.restartDelay = minDuration;
        } else {
            // If it has been a "reasonably long time" since the service
            // was started, then reset our restart duration back to
            // the beginning, so we don't infinitely increase the duration
            // on a service that just occasionally gets killed (which is
            // a normal case, due to process being killed to reclaim memory).
            if (now > (r.restartTime+resetTime)) {
                r.restartCount = 1;
                r.restartDelay = minDuration;
            } else {
                r.restartDelay *= SERVICE_RESTART_DURATION_FACTOR;
                if (r.restartDelay < minDuration) {
                    r.restartDelay = minDuration;
                }
            }
        }
        
        r.nextRestartTime = now + r.restartDelay;
        
        // Make sure that we don't end up restarting a bunch of services
        // all at the same time.
        boolean repeat;
        do {
            repeat = false;
            for (int i=mRestartingServices.size()-1; i>=0; i--) {
                ServiceRecord r2 = mRestartingServices.get(i);
                if (r2 != r && r.nextRestartTime
                        >= (r2.nextRestartTime-SERVICE_MIN_RESTART_TIME_BETWEEN)
                        && r.nextRestartTime
                        < (r2.nextRestartTime+SERVICE_MIN_RESTART_TIME_BETWEEN)) {
                    r.nextRestartTime = r2.nextRestartTime + SERVICE_MIN_RESTART_TIME_BETWEEN;
                    r.restartDelay = r.nextRestartTime - now;
                    repeat = true;
                    break;
                }
            }
        } while (repeat);
        
        if (!mRestartingServices.contains(r)) {
            mRestartingServices.add(r);
        }
        
        r.cancelNotification();
        
        mHandler.removeCallbacks(r.restarter);
        mHandler.postAtTime(r.restarter, r.nextRestartTime);
        r.nextRestartTime = SystemClock.uptimeMillis() + r.restartDelay;
        Slog.w(TAG, "Scheduling restart of crashed service "
                + r.shortName + " in " + r.restartDelay + "ms");
        EventLog.writeEvent(EventLogTags.AM_SCHEDULE_SERVICE_RESTART,
                r.shortName, r.restartDelay);

        return canceled;
    }

    final void performServiceRestartLocked(ServiceRecord r) {
        if (!mRestartingServices.contains(r)) {
            return;
        }
        bringUpServiceLocked(r, r.intent.getIntent().getFlags(), true);
    }

    private final boolean unscheduleServiceRestartLocked(ServiceRecord r) {
        if (r.restartDelay == 0) {
            return false;
        }
        r.resetRestartCounter();
        mRestartingServices.remove(r);
        mHandler.removeCallbacks(r.restarter);
        return true;
    }

    private final boolean bringUpServiceLocked(ServiceRecord r,
            int intentFlags, boolean whileRestarting) {
        //Slog.i(TAG, "Bring up service:");
        //r.dump("  ");

        if (r.app != null && r.app.thread != null) {
            sendServiceArgsLocked(r, false);
            return true;
        }

        if (!whileRestarting && r.restartDelay > 0) {
            // If waiting for a restart, then do nothing.
            return true;
        }

        if (DEBUG_SERVICE) Slog.v(TAG, "Bringing up service " + r.name
                + " " + r.intent);

        // We are now bringing the service up, so no longer in the
        // restarting state.
        mRestartingServices.remove(r);
        
        final String appName = r.processName;
        ProcessRecord app = getProcessRecordLocked(appName, r.appInfo.uid);
        if (app != null && app.thread != null) {
            try {
                realStartServiceLocked(r, app);
                return true;
            } catch (RemoteException e) {
                Slog.w(TAG, "Exception when starting service " + r.shortName, e);
            }

            // If a dead object exception was thrown -- fall through to
            // restart the application.
        }

        // Not running -- get it started, and enqueue this service record
        // to be executed when the app comes up.
        if (startProcessLocked(appName, r.appInfo, true, intentFlags,
                "service", r.name, false) == null) {
            Slog.w(TAG, "Unable to launch app "
                    + r.appInfo.packageName + "/"
                    + r.appInfo.uid + " for service "
                    + r.intent.getIntent() + ": process is bad");
            bringDownServiceLocked(r, true);
            return false;
        }
        
        if (!mPendingServices.contains(r)) {
            mPendingServices.add(r);
        }
        
        return true;
    }

    private final void bringDownServiceLocked(ServiceRecord r, boolean force) {
        //Slog.i(TAG, "Bring down service:");
        //r.dump("  ");

        // Does it still need to run?
        if (!force && r.startRequested) {
            return;
        }
        if (r.connections.size() > 0) {
            if (!force) {
                // XXX should probably keep a count of the number of auto-create
                // connections directly in the service.
                Iterator<ConnectionRecord> it = r.connections.values().iterator();
                while (it.hasNext()) {
                    ConnectionRecord cr = it.next();
                    if ((cr.flags&Context.BIND_AUTO_CREATE) != 0) {
                        return;
                    }
                }
            }

            // Report to all of the connections that the service is no longer
            // available.
            Iterator<ConnectionRecord> it = r.connections.values().iterator();
            while (it.hasNext()) {
                ConnectionRecord c = it.next();
                try {
                    // todo: shouldn't be a synchronous call!
                    c.conn.connected(r.name, null);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure disconnecting service " + r.name +
                          " to connection " + c.conn.asBinder() +
                          " (in " + c.binding.client.processName + ")", e);
                }
            }
        }

        // Tell the service that it has been unbound.
        if (r.bindings.size() > 0 && r.app != null && r.app.thread != null) {
            Iterator<IntentBindRecord> it = r.bindings.values().iterator();
            while (it.hasNext()) {
                IntentBindRecord ibr = it.next();
                if (DEBUG_SERVICE) Slog.v(TAG, "Bringing down binding " + ibr
                        + ": hasBound=" + ibr.hasBound);
                if (r.app != null && r.app.thread != null && ibr.hasBound) {
                    try {
                        bumpServiceExecutingLocked(r);
                        updateOomAdjLocked(r.app);
                        ibr.hasBound = false;
                        r.app.thread.scheduleUnbindService(r,
                                ibr.intent.getIntent());
                    } catch (Exception e) {
                        Slog.w(TAG, "Exception when unbinding service "
                                + r.shortName, e);
                        serviceDoneExecutingLocked(r, true);
                    }
                }
            }
        }

        if (DEBUG_SERVICE) Slog.v(TAG, "Bringing down service " + r.name
                 + " " + r.intent);
        EventLog.writeEvent(EventLogTags.AM_DESTROY_SERVICE,
                System.identityHashCode(r), r.shortName,
                (r.app != null) ? r.app.pid : -1);

        mServices.remove(r.name);
        mServicesByIntent.remove(r.intent);
        if (localLOGV) Slog.v(TAG, "BRING DOWN SERVICE: " + r.shortName);
        r.totalRestartCount = 0;
        unscheduleServiceRestartLocked(r);

        // Also make sure it is not on the pending list.
        int N = mPendingServices.size();
        for (int i=0; i<N; i++) {
            if (mPendingServices.get(i) == r) {
                mPendingServices.remove(i);
                if (DEBUG_SERVICE) Slog.v(
                    TAG, "Removed pending service: " + r.shortName);
                i--;
                N--;
            }
        }

        r.cancelNotification();
        r.isForeground = false;
        r.foregroundId = 0;
        r.foregroundNoti = null;
        
        // Clear start entries.
        r.deliveredStarts.clear();
        r.pendingStarts.clear();
        
        if (r.app != null) {
            synchronized (r.stats.getBatteryStats()) {
                r.stats.stopLaunchedLocked();
            }
            r.app.services.remove(r);
            if (r.app.thread != null) {
                try {
                    if (DEBUG_SERVICE) Slog.v(TAG,
                            "Stopping service: " + r.shortName);
                    bumpServiceExecutingLocked(r);
                    mStoppingServices.add(r);
                    updateOomAdjLocked(r.app);
                    r.app.thread.scheduleStopService(r);
                } catch (Exception e) {
                    Slog.w(TAG, "Exception when stopping service "
                            + r.shortName, e);
                    serviceDoneExecutingLocked(r, true);
                }
                updateServiceForegroundLocked(r.app, false);
            } else {
                if (DEBUG_SERVICE) Slog.v(
                    TAG, "Removed service that has no process: " + r.shortName);
            }
        } else {
            if (DEBUG_SERVICE) Slog.v(
                TAG, "Removed service that is not running: " + r.shortName);
        }
    }

    ComponentName startServiceLocked(IApplicationThread caller,
            Intent service, String resolvedType,
            int callingPid, int callingUid) {
        synchronized(this) {
            if (DEBUG_SERVICE) Slog.v(TAG, "startService: " + service
                    + " type=" + resolvedType + " args=" + service.getExtras());

            if (caller != null) {
                final ProcessRecord callerApp = getRecordForAppLocked(caller);
                if (callerApp == null) {
                    throw new SecurityException(
                            "Unable to find app for caller " + caller
                            + " (pid=" + Binder.getCallingPid()
                            + ") when starting service " + service);
                }
            }

            ServiceLookupResult res =
                retrieveServiceLocked(service, resolvedType,
                        callingPid, callingUid);
            if (res == null) {
                return null;
            }
            if (res.record == null) {
                return new ComponentName("!", res.permission != null
                        ? res.permission : "private to package");
            }
            ServiceRecord r = res.record;
            if (unscheduleServiceRestartLocked(r)) {
                if (DEBUG_SERVICE) Slog.v(TAG, "START SERVICE WHILE RESTART PENDING: "
                        + r.shortName);
            }
            r.startRequested = true;
            r.callStart = false;
            r.lastStartId++;
            if (r.lastStartId < 1) {
                r.lastStartId = 1;
            }
            r.pendingStarts.add(new ServiceRecord.StartItem(r.lastStartId, service));
            r.lastActivity = SystemClock.uptimeMillis();
            synchronized (r.stats.getBatteryStats()) {
                r.stats.startRunningLocked();
            }
            if (!bringUpServiceLocked(r, service.getFlags(), false)) {
                return new ComponentName("!", "Service process is bad");
            }
            return r.name;
        }
    }

    public ComponentName startService(IApplicationThread caller, Intent service,
            String resolvedType) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            ComponentName res = startServiceLocked(caller, service,
                    resolvedType, callingPid, callingUid);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    ComponentName startServiceInPackage(int uid,
            Intent service, String resolvedType) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            ComponentName res = startServiceLocked(null, service,
                    resolvedType, -1, uid);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    public int stopService(IApplicationThread caller, Intent service,
            String resolvedType) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (DEBUG_SERVICE) Slog.v(TAG, "stopService: " + service
                    + " type=" + resolvedType);

            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            if (caller != null && callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when stopping service " + service);
            }

            // If this service is active, make sure it is stopped.
            ServiceLookupResult r = findServiceLocked(service, resolvedType);
            if (r != null) {
                if (r.record != null) {
                    synchronized (r.record.stats.getBatteryStats()) {
                        r.record.stats.stopRunningLocked();
                    }
                    r.record.startRequested = false;
                    r.record.callStart = false;
                    final long origId = Binder.clearCallingIdentity();
                    bringDownServiceLocked(r.record, false);
                    Binder.restoreCallingIdentity(origId);
                    return 1;
                }
                return -1;
            }
        }

        return 0;
    }

    public IBinder peekService(Intent service, String resolvedType) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        IBinder ret = null;

        synchronized(this) {
            ServiceLookupResult r = findServiceLocked(service, resolvedType);
            
            if (r != null) {
                // r.record is null if findServiceLocked() failed the caller permission check
                if (r.record == null) {
                    throw new SecurityException(
                            "Permission Denial: Accessing service " + r.record.name
                            + " from pid=" + Binder.getCallingPid()
                            + ", uid=" + Binder.getCallingUid()
                            + " requires " + r.permission);
                }
                IntentBindRecord ib = r.record.bindings.get(r.record.intent);
                if (ib != null) {
                    ret = ib.binder;
                }
            }
        }

        return ret;
    }
    
    public boolean stopServiceToken(ComponentName className, IBinder token,
            int startId) {
        synchronized(this) {
            if (DEBUG_SERVICE) Slog.v(TAG, "stopServiceToken: " + className
                    + " " + token + " startId=" + startId);
            ServiceRecord r = findServiceLocked(className, token);
            if (r != null) {
                if (startId >= 0) {
                    // Asked to only stop if done with all work.  Note that
                    // to avoid leaks, we will take this as dropping all
                    // start items up to and including this one.
                    ServiceRecord.StartItem si = r.findDeliveredStart(startId, false);
                    if (si != null) {
                        while (r.deliveredStarts.size() > 0) {
                            if (r.deliveredStarts.remove(0) == si) {
                                break;
                            }
                        }
                    }
                    
                    if (r.lastStartId != startId) {
                        return false;
                    }
                    
                    if (r.deliveredStarts.size() > 0) {
                        Slog.w(TAG, "stopServiceToken startId " + startId
                                + " is last, but have " + r.deliveredStarts.size()
                                + " remaining args");
                    }
                }
                
                synchronized (r.stats.getBatteryStats()) {
                    r.stats.stopRunningLocked();
                    r.startRequested = false;
                    r.callStart = false;
                }
                final long origId = Binder.clearCallingIdentity();
                bringDownServiceLocked(r, false);
                Binder.restoreCallingIdentity(origId);
                return true;
            }
        }
        return false;
    }

    public void setServiceForeground(ComponentName className, IBinder token,
            int id, Notification notification, boolean removeNotification) {
        final long origId = Binder.clearCallingIdentity();
        try {
        synchronized(this) {
            ServiceRecord r = findServiceLocked(className, token);
            if (r != null) {
                if (id != 0) {
                    if (notification == null) {
                        throw new IllegalArgumentException("null notification");
                    }
                    if (r.foregroundId != id) {
                        r.cancelNotification();
                        r.foregroundId = id;
                    }
                    notification.flags |= Notification.FLAG_FOREGROUND_SERVICE;
                    r.foregroundNoti = notification;
                    r.isForeground = true;
                    r.postNotification();
                    if (r.app != null) {
                        updateServiceForegroundLocked(r.app, true);
                    }
                } else {
                    if (r.isForeground) {
                        r.isForeground = false;
                        if (r.app != null) {
                            updateLruProcessLocked(r.app, false, true);
                            updateServiceForegroundLocked(r.app, true);
                        }
                    }
                    if (removeNotification) {
                        r.cancelNotification();
                        r.foregroundId = 0;
                        r.foregroundNoti = null;
                    }
                }
            }
        }
        } finally {
            Binder.restoreCallingIdentity(origId);
        }
    }

    public void updateServiceForegroundLocked(ProcessRecord proc, boolean oomAdj) {
        boolean anyForeground = false;
        for (ServiceRecord sr : (HashSet<ServiceRecord>)proc.services) {
            if (sr.isForeground) {
                anyForeground = true;
                break;
            }
        }
        if (anyForeground != proc.foregroundServices) {
            proc.foregroundServices = anyForeground;
            if (oomAdj) {
                updateOomAdjLocked();
            }
        }
    }
    
    public int bindService(IApplicationThread caller, IBinder token,
            Intent service, String resolvedType,
            IServiceConnection connection, int flags) {
        // Refuse possible leaked file descriptors
        if (service != null && service.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (DEBUG_SERVICE) Slog.v(TAG, "bindService: " + service
                    + " type=" + resolvedType + " conn=" + connection.asBinder()
                    + " flags=0x" + Integer.toHexString(flags));
            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            if (callerApp == null) {
                throw new SecurityException(
                        "Unable to find app for caller " + caller
                        + " (pid=" + Binder.getCallingPid()
                        + ") when binding service " + service);
            }

            HistoryRecord activity = null;
            if (token != null) {
                int aindex = indexOfTokenLocked(token);
                if (aindex < 0) {
                    Slog.w(TAG, "Binding with unknown activity: " + token);
                    return 0;
                }
                activity = (HistoryRecord)mHistory.get(aindex);
            }

            int clientLabel = 0;
            PendingIntent clientIntent = null;
            
            if (callerApp.info.uid == Process.SYSTEM_UID) {
                // Hacky kind of thing -- allow system stuff to tell us
                // what they are, so we can report this elsewhere for
                // others to know why certain services are running.
                try {
                    clientIntent = (PendingIntent)service.getParcelableExtra(
                            Intent.EXTRA_CLIENT_INTENT);
                } catch (RuntimeException e) {
                }
                if (clientIntent != null) {
                    clientLabel = service.getIntExtra(Intent.EXTRA_CLIENT_LABEL, 0);
                    if (clientLabel != 0) {
                        // There are no useful extras in the intent, trash them.
                        // System code calling with this stuff just needs to know
                        // this will happen.
                        service = service.cloneFilter();
                    }
                }
            }
            
            ServiceLookupResult res =
                retrieveServiceLocked(service, resolvedType,
                        Binder.getCallingPid(), Binder.getCallingUid());
            if (res == null) {
                return 0;
            }
            if (res.record == null) {
                return -1;
            }
            ServiceRecord s = res.record;

            final long origId = Binder.clearCallingIdentity();

            if (unscheduleServiceRestartLocked(s)) {
                if (DEBUG_SERVICE) Slog.v(TAG, "BIND SERVICE WHILE RESTART PENDING: "
                        + s.shortName);
            }

            AppBindRecord b = s.retrieveAppBindingLocked(service, callerApp);
            ConnectionRecord c = new ConnectionRecord(b, activity,
                    connection, flags, clientLabel, clientIntent);

            IBinder binder = connection.asBinder();
            s.connections.put(binder, c);
            b.connections.add(c);
            if (activity != null) {
                if (activity.connections == null) {
                    activity.connections = new HashSet<ConnectionRecord>();
                }
                activity.connections.add(c);
            }
            b.client.connections.add(c);
            mServiceConnections.put(binder, c);

            if ((flags&Context.BIND_AUTO_CREATE) != 0) {
                s.lastActivity = SystemClock.uptimeMillis();
                if (!bringUpServiceLocked(s, service.getFlags(), false)) {
                    return 0;
                }
            }

            if (s.app != null) {
                // This could have made the service more important.
                updateOomAdjLocked(s.app);
            }

            if (DEBUG_SERVICE) Slog.v(TAG, "Bind " + s + " with " + b
                    + ": received=" + b.intent.received
                    + " apps=" + b.intent.apps.size()
                    + " doRebind=" + b.intent.doRebind);

            if (s.app != null && b.intent.received) {
                // Service is already running, so we can immediately
                // publish the connection.
                try {
                    c.conn.connected(s.name, b.intent.binder);
                } catch (Exception e) {
                    Slog.w(TAG, "Failure sending service " + s.shortName
                            + " to connection " + c.conn.asBinder()
                            + " (in " + c.binding.client.processName + ")", e);
                }

                // If this is the first app connected back to this binding,
                // and the service had previously asked to be told when
                // rebound, then do so.
                if (b.intent.apps.size() == 1 && b.intent.doRebind) {
                    requestServiceBindingLocked(s, b.intent, true);
                }
            } else if (!b.intent.requested) {
                requestServiceBindingLocked(s, b.intent, false);
            }

            Binder.restoreCallingIdentity(origId);
        }

        return 1;
    }

    private void removeConnectionLocked(
        ConnectionRecord c, ProcessRecord skipApp, HistoryRecord skipAct) {
        IBinder binder = c.conn.asBinder();
        AppBindRecord b = c.binding;
        ServiceRecord s = b.service;
        s.connections.remove(binder);
        b.connections.remove(c);
        if (c.activity != null && c.activity != skipAct) {
            if (c.activity.connections != null) {
                c.activity.connections.remove(c);
            }
        }
        if (b.client != skipApp) {
            b.client.connections.remove(c);
        }
        mServiceConnections.remove(binder);

        if (b.connections.size() == 0) {
            b.intent.apps.remove(b.client);
        }

        if (DEBUG_SERVICE) Slog.v(TAG, "Disconnecting binding " + b.intent
                + ": shouldUnbind=" + b.intent.hasBound);
        if (s.app != null && s.app.thread != null && b.intent.apps.size() == 0
                && b.intent.hasBound) {
            try {
                bumpServiceExecutingLocked(s);
                updateOomAdjLocked(s.app);
                b.intent.hasBound = false;
                // Assume the client doesn't want to know about a rebind;
                // we will deal with that later if it asks for one.
                b.intent.doRebind = false;
                s.app.thread.scheduleUnbindService(s, b.intent.intent.getIntent());
            } catch (Exception e) {
                Slog.w(TAG, "Exception when unbinding service " + s.shortName, e);
                serviceDoneExecutingLocked(s, true);
            }
        }

        if ((c.flags&Context.BIND_AUTO_CREATE) != 0) {
            bringDownServiceLocked(s, false);
        }
    }

    public boolean unbindService(IServiceConnection connection) {
        synchronized (this) {
            IBinder binder = connection.asBinder();
            if (DEBUG_SERVICE) Slog.v(TAG, "unbindService: conn=" + binder);
            ConnectionRecord r = mServiceConnections.get(binder);
            if (r == null) {
                Slog.w(TAG, "Unbind failed: could not find connection for "
                      + connection.asBinder());
                return false;
            }

            final long origId = Binder.clearCallingIdentity();

            removeConnectionLocked(r, null, null);

            if (r.binding.service.app != null) {
                // This could have made the service less important.
                updateOomAdjLocked(r.binding.service.app);
            }

            Binder.restoreCallingIdentity(origId);
        }

        return true;
    }

    public void publishService(IBinder token, Intent intent, IBinder service) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                throw new IllegalArgumentException("Invalid service token");
            }
            ServiceRecord r = (ServiceRecord)token;

            final long origId = Binder.clearCallingIdentity();

            if (DEBUG_SERVICE) Slog.v(TAG, "PUBLISHING SERVICE " + r.name
                    + " " + intent + ": " + service);
            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (b != null && !b.received) {
                    b.binder = service;
                    b.requested = true;
                    b.received = true;
                    if (r.connections.size() > 0) {
                        Iterator<ConnectionRecord> it
                                = r.connections.values().iterator();
                        while (it.hasNext()) {
                            ConnectionRecord c = it.next();
                            if (!filter.equals(c.binding.intent.intent)) {
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG, "Not publishing to: " + c);
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG, "Bound intent: " + c.binding.intent.intent);
                                if (DEBUG_SERVICE) Slog.v(
                                        TAG, "Published intent: " + intent);
                                continue;
                            }
                            if (DEBUG_SERVICE) Slog.v(TAG, "Publishing to: " + c);
                            try {
                                c.conn.connected(r.name, service);
                            } catch (Exception e) {
                                Slog.w(TAG, "Failure sending service " + r.name +
                                      " to connection " + c.conn.asBinder() +
                                      " (in " + c.binding.client.processName + ")", e);
                            }
                        }
                    }
                }

                serviceDoneExecutingLocked(r, mStoppingServices.contains(r));

                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void unbindFinished(IBinder token, Intent intent, boolean doRebind) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                throw new IllegalArgumentException("Invalid service token");
            }
            ServiceRecord r = (ServiceRecord)token;

            final long origId = Binder.clearCallingIdentity();

            if (r != null) {
                Intent.FilterComparison filter
                        = new Intent.FilterComparison(intent);
                IntentBindRecord b = r.bindings.get(filter);
                if (DEBUG_SERVICE) Slog.v(TAG, "unbindFinished in " + r
                        + " at " + b + ": apps="
                        + (b != null ? b.apps.size() : 0));
                if (b != null) {
                    if (b.apps.size() > 0) {
                        // Applications have already bound since the last
                        // unbind, so just rebind right here.
                        requestServiceBindingLocked(r, b, true);
                    } else {
                        // Note to tell the service the next time there is
                        // a new client.
                        b.doRebind = true;
                    }
                }

                serviceDoneExecutingLocked(r, mStoppingServices.contains(r));

                Binder.restoreCallingIdentity(origId);
            }
        }
    }

    public void serviceDoneExecuting(IBinder token, int type, int startId, int res) {
        synchronized(this) {
            if (!(token instanceof ServiceRecord)) {
                throw new IllegalArgumentException("Invalid service token");
            }
            ServiceRecord r = (ServiceRecord)token;
            boolean inStopping = mStoppingServices.contains(token);
            if (r != null) {
                if (DEBUG_SERVICE) Slog.v(TAG, "DONE EXECUTING SERVICE " + r.name
                        + ": nesting=" + r.executeNesting
                        + ", inStopping=" + inStopping);
                if (r != token) {
                    Slog.w(TAG, "Done executing service " + r.name
                          + " with incorrect token: given " + token
                          + ", expected " + r);
                    return;
                }

                if (type == 1) {
                    // This is a call from a service start...  take care of
                    // book-keeping.
                    r.callStart = true;
                    switch (res) {
                        case Service.START_STICKY_COMPATIBILITY:
                        case Service.START_STICKY: {
                            // We are done with the associated start arguments.
                            r.findDeliveredStart(startId, true);
                            // Don't stop if killed.
                            r.stopIfKilled = false;
                            break;
                        }
                        case Service.START_NOT_STICKY: {
                            // We are done with the associated start arguments.
                            r.findDeliveredStart(startId, true);
                            if (r.lastStartId == startId) {
                                // There is no more work, and this service
                                // doesn't want to hang around if killed.
                                r.stopIfKilled = true;
                            }
                            break;
                        }
                        case Service.START_REDELIVER_INTENT: {
                            // We'll keep this item until they explicitly
                            // call stop for it, but keep track of the fact
                            // that it was delivered.
                            ServiceRecord.StartItem si = r.findDeliveredStart(startId, false);
                            if (si != null) {
                                si.deliveryCount = 0;
                                si.doneExecutingCount++;
                                // Don't stop if killed.
                                r.stopIfKilled = true;
                            }
                            break;
                        }
                        default:
                            throw new IllegalArgumentException(
                                    "Unknown service start result: " + res);
                    }
                    if (res == Service.START_STICKY_COMPATIBILITY) {
                        r.callStart = false;
                    }
                }
                
                final long origId = Binder.clearCallingIdentity();
                serviceDoneExecutingLocked(r, inStopping);
                Binder.restoreCallingIdentity(origId);
            } else {
                Slog.w(TAG, "Done executing unknown service " + r.name
                        + " with token " + token);
            }
        }
    }

    public void serviceDoneExecutingLocked(ServiceRecord r, boolean inStopping) {
        r.executeNesting--;
        if (r.executeNesting <= 0 && r.app != null) {
            r.app.executingServices.remove(r);
            if (r.app.executingServices.size() == 0) {
                mHandler.removeMessages(SERVICE_TIMEOUT_MSG, r.app);
            }
            if (inStopping) {
                mStoppingServices.remove(r);
            }
            updateOomAdjLocked(r.app);
        }
    }

    void serviceTimeout(ProcessRecord proc) {
        String anrMessage = null;
        
        synchronized(this) {
            if (proc.executingServices.size() == 0 || proc.thread == null) {
                return;
            }
            long maxTime = SystemClock.uptimeMillis() - SERVICE_TIMEOUT;
            Iterator<ServiceRecord> it = proc.executingServices.iterator();
            ServiceRecord timeout = null;
            long nextTime = 0;
            while (it.hasNext()) {
                ServiceRecord sr = it.next();
                if (sr.executingStart < maxTime) {
                    timeout = sr;
                    break;
                }
                if (sr.executingStart > nextTime) {
                    nextTime = sr.executingStart;
                }
            }
            if (timeout != null && mLruProcesses.contains(proc)) {
                Slog.w(TAG, "Timeout executing service: " + timeout);
                anrMessage = "Executing service " + timeout.shortName;
            } else {
                Message msg = mHandler.obtainMessage(SERVICE_TIMEOUT_MSG);
                msg.obj = proc;
                mHandler.sendMessageAtTime(msg, nextTime+SERVICE_TIMEOUT);
            }
        }
        
        if (anrMessage != null) {
            appNotResponding(proc, null, null, anrMessage);
        }
    }
    
    // =========================================================
    // BACKUP AND RESTORE
    // =========================================================
    
    // Cause the target app to be launched if necessary and its backup agent
    // instantiated.  The backup agent will invoke backupAgentCreated() on the
    // activity manager to announce its creation.
    public boolean bindBackupAgent(ApplicationInfo app, int backupMode) {
        if (DEBUG_BACKUP) Slog.v(TAG, "startBackupAgent: app=" + app + " mode=" + backupMode);
        enforceCallingPermission("android.permission.BACKUP", "startBackupAgent");

        synchronized(this) {
            // !!! TODO: currently no check here that we're already bound
            BatteryStatsImpl.Uid.Pkg.Serv ss = null;
            BatteryStatsImpl stats = mBatteryStatsService.getActiveStatistics();
            synchronized (stats) {
                ss = stats.getServiceStatsLocked(app.uid, app.packageName, app.name);
            }

            BackupRecord r = new BackupRecord(ss, app, backupMode);
            ComponentName hostingName = new ComponentName(app.packageName, app.backupAgentName);
            // startProcessLocked() returns existing proc's record if it's already running
            ProcessRecord proc = startProcessLocked(app.processName, app,
                    false, 0, "backup", hostingName, false);
            if (proc == null) {
                Slog.e(TAG, "Unable to start backup agent process " + r);
                return false;
            }

            r.app = proc;
            mBackupTarget = r;
            mBackupAppName = app.packageName;

            // Try not to kill the process during backup
            updateOomAdjLocked(proc);

            // If the process is already attached, schedule the creation of the backup agent now.
            // If it is not yet live, this will be done when it attaches to the framework.
            if (proc.thread != null) {
                if (DEBUG_BACKUP) Slog.v(TAG, "Agent proc already running: " + proc);
                try {
                    proc.thread.scheduleCreateBackupAgent(app, backupMode);
                } catch (RemoteException e) {
                    // Will time out on the backup manager side
                }
            } else {
                if (DEBUG_BACKUP) Slog.v(TAG, "Agent proc not running, waiting for attach");
            }
            // Invariants: at this point, the target app process exists and the application
            // is either already running or in the process of coming up.  mBackupTarget and
            // mBackupAppName describe the app, so that when it binds back to the AM we
            // know that it's scheduled for a backup-agent operation.
        }
        
        return true;
    }

    // A backup agent has just come up                    
    public void backupAgentCreated(String agentPackageName, IBinder agent) {
        if (DEBUG_BACKUP) Slog.v(TAG, "backupAgentCreated: " + agentPackageName
                + " = " + agent);

        synchronized(this) {
            if (!agentPackageName.equals(mBackupAppName)) {
                Slog.e(TAG, "Backup agent created for " + agentPackageName + " but not requested!");
                return;
            }

            long oldIdent = Binder.clearCallingIdentity();
            try {
                IBackupManager bm = IBackupManager.Stub.asInterface(
                        ServiceManager.getService(Context.BACKUP_SERVICE));
                bm.agentConnected(agentPackageName, agent);
            } catch (RemoteException e) {
                // can't happen; the backup manager service is local
            } catch (Exception e) {
                Slog.w(TAG, "Exception trying to deliver BackupAgent binding: ");
                e.printStackTrace();
            } finally {
                Binder.restoreCallingIdentity(oldIdent);
            }
        }
    }

    // done with this agent
    public void unbindBackupAgent(ApplicationInfo appInfo) {
        if (DEBUG_BACKUP) Slog.v(TAG, "unbindBackupAgent: " + appInfo);
        if (appInfo == null) {
            Slog.w(TAG, "unbind backup agent for null app");
            return;
        }

        synchronized(this) {
            if (mBackupAppName == null) {
                Slog.w(TAG, "Unbinding backup agent with no active backup");
                return;
            }

            if (!mBackupAppName.equals(appInfo.packageName)) {
                Slog.e(TAG, "Unbind of " + appInfo + " but is not the current backup target");
                return;
            }

            ProcessRecord proc = mBackupTarget.app;
            mBackupTarget = null;
            mBackupAppName = null;

            // Not backing this app up any more; reset its OOM adjustment
            updateOomAdjLocked(proc);

            // If the app crashed during backup, 'thread' will be null here
            if (proc.thread != null) {
                try {
                    proc.thread.scheduleDestroyBackupAgent(appInfo);
                } catch (Exception e) {
                    Slog.e(TAG, "Exception when unbinding backup agent:");
                    e.printStackTrace();
                }
            }
        }
    }
    // =========================================================
    // BROADCASTS
    // =========================================================

    private final List getStickiesLocked(String action, IntentFilter filter,
            List cur) {
        final ContentResolver resolver = mContext.getContentResolver();
        final ArrayList<Intent> list = mStickyBroadcasts.get(action);
        if (list == null) {
            return cur;
        }
        int N = list.size();
        for (int i=0; i<N; i++) {
            Intent intent = list.get(i);
            if (filter.match(resolver, intent, true, TAG) >= 0) {
                if (cur == null) {
                    cur = new ArrayList<Intent>();
                }
                cur.add(intent);
            }
        }
        return cur;
    }

    private final void scheduleBroadcastsLocked() {
        if (DEBUG_BROADCAST) Slog.v(TAG, "Schedule broadcasts: current="
                + mBroadcastsScheduled);

        if (mBroadcastsScheduled) {
            return;
        }
        mHandler.sendEmptyMessage(BROADCAST_INTENT_MSG);
        mBroadcastsScheduled = true;
    }

    public Intent registerReceiver(IApplicationThread caller,
            IIntentReceiver receiver, IntentFilter filter, String permission) {
        synchronized(this) {
            ProcessRecord callerApp = null;
            if (caller != null) {
                callerApp = getRecordForAppLocked(caller);
                if (callerApp == null) {
                    throw new SecurityException(
                            "Unable to find app for caller " + caller
                            + " (pid=" + Binder.getCallingPid()
                            + ") when registering receiver " + receiver);
                }
            }

            List allSticky = null;

            // Look for any matching sticky broadcasts...
            Iterator actions = filter.actionsIterator();
            if (actions != null) {
                while (actions.hasNext()) {
                    String action = (String)actions.next();
                    allSticky = getStickiesLocked(action, filter, allSticky);
                }
            } else {
                allSticky = getStickiesLocked(null, filter, allSticky);
            }

            // The first sticky in the list is returned directly back to
            // the client.
            Intent sticky = allSticky != null ? (Intent)allSticky.get(0) : null;

            if (DEBUG_BROADCAST) Slog.v(TAG, "Register receiver " + filter
                    + ": " + sticky);

            if (receiver == null) {
                return sticky;
            }

            ReceiverList rl
                = (ReceiverList)mRegisteredReceivers.get(receiver.asBinder());
            if (rl == null) {
                rl = new ReceiverList(this, callerApp,
                        Binder.getCallingPid(),
                        Binder.getCallingUid(), receiver);
                if (rl.app != null) {
                    rl.app.receivers.add(rl);
                } else {
                    try {
                        receiver.asBinder().linkToDeath(rl, 0);
                    } catch (RemoteException e) {
                        return sticky;
                    }
                    rl.linkedToDeath = true;
                }
                mRegisteredReceivers.put(receiver.asBinder(), rl);
            }
            BroadcastFilter bf = new BroadcastFilter(filter, rl, permission);
            rl.add(bf);
            if (!bf.debugCheck()) {
                Slog.w(TAG, "==> For Dynamic broadast");
            }
            mReceiverResolver.addFilter(bf);

            // Enqueue broadcasts for all existing stickies that match
            // this filter.
            if (allSticky != null) {
                ArrayList receivers = new ArrayList();
                receivers.add(bf);

                int N = allSticky.size();
                for (int i=0; i<N; i++) {
                    Intent intent = (Intent)allSticky.get(i);
                    BroadcastRecord r = new BroadcastRecord(intent, null,
                            null, -1, -1, null, receivers, null, 0, null, null,
                            false, true, true);
                    if (mParallelBroadcasts.size() == 0) {
                        scheduleBroadcastsLocked();
                    }
                    mParallelBroadcasts.add(r);
                }
            }

            return sticky;
        }
    }

    public void unregisterReceiver(IIntentReceiver receiver) {
        if (DEBUG_BROADCAST) Slog.v(TAG, "Unregister receiver: " + receiver);

        boolean doNext = false;

        synchronized(this) {
            ReceiverList rl
                = (ReceiverList)mRegisteredReceivers.get(receiver.asBinder());
            if (rl != null) {
                if (rl.curBroadcast != null) {
                    BroadcastRecord r = rl.curBroadcast;
                    doNext = finishReceiverLocked(
                        receiver.asBinder(), r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, true);
                }

                if (rl.app != null) {
                    rl.app.receivers.remove(rl);
                }
                removeReceiverLocked(rl);
                if (rl.linkedToDeath) {
                    rl.linkedToDeath = false;
                    rl.receiver.asBinder().unlinkToDeath(rl, 0);
                }
            }
        }

        if (!doNext) {
            return;
        }
        
        final long origId = Binder.clearCallingIdentity();
        processNextBroadcast(false);
        trimApplications();
        Binder.restoreCallingIdentity(origId);
    }

    void removeReceiverLocked(ReceiverList rl) {
        mRegisteredReceivers.remove(rl.receiver.asBinder());
        int N = rl.size();
        for (int i=0; i<N; i++) {
            mReceiverResolver.removeFilter(rl.get(i));
        }
    }
    
    private final void sendPackageBroadcastLocked(int cmd, String[] packages) {
        for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
            ProcessRecord r = mLruProcesses.get(i);
            if (r.thread != null) {
                try {
                    r.thread.dispatchPackageBroadcast(cmd, packages);
                } catch (RemoteException ex) {
                }
            }
        }
    }
    
    private final int broadcastIntentLocked(ProcessRecord callerApp,
            String callerPackage, Intent intent, String resolvedType,
            IIntentReceiver resultTo, int resultCode, String resultData,
            Bundle map, String requiredPermission,
            boolean ordered, boolean sticky, int callingPid, int callingUid) {
        intent = new Intent(intent);

        if (DEBUG_BROADCAST_LIGHT) Slog.v(
            TAG, (sticky ? "Broadcast sticky: ": "Broadcast: ") + intent
            + " ordered=" + ordered);
        if ((resultTo != null) && !ordered) {
            Slog.w(TAG, "Broadcast " + intent + " not ordered but result callback requested!");
        }
        
        // Handle special intents: if this broadcast is from the package
        // manager about a package being removed, we need to remove all of
        // its activities from the history stack.
        final boolean uidRemoved = intent.ACTION_UID_REMOVED.equals(
                intent.getAction());
        if (intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())
                || intent.ACTION_PACKAGE_CHANGED.equals(intent.getAction())
                || Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(intent.getAction())
                || uidRemoved) {
            if (checkComponentPermission(
                    android.Manifest.permission.BROADCAST_PACKAGE_REMOVED,
                    callingPid, callingUid, -1)
                    == PackageManager.PERMISSION_GRANTED) {
                if (uidRemoved) {
                    final Bundle intentExtras = intent.getExtras();
                    final int uid = intentExtras != null
                            ? intentExtras.getInt(Intent.EXTRA_UID) : -1;
                    if (uid >= 0) {
                        BatteryStatsImpl bs = mBatteryStatsService.getActiveStatistics();
                        synchronized (bs) {
                            bs.removeUidStatsLocked(uid);
                        }
                    }
                } else {
                    // If resources are unvailble just force stop all
                    // those packages and flush the attribute cache as well.
                    if (Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(intent.getAction())) {
                        String list[] = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                        if (list != null && (list.length > 0)) {
                            for (String pkg : list) {
                                forceStopPackageLocked(pkg, -1, false, true, true);
                            }
                            sendPackageBroadcastLocked(
                                    IApplicationThread.EXTERNAL_STORAGE_UNAVAILABLE, list);
                        }
                    } else {
                        Uri data = intent.getData();
                        String ssp;
                        if (data != null && (ssp=data.getSchemeSpecificPart()) != null) {
                            if (!intent.getBooleanExtra(Intent.EXTRA_DONT_KILL_APP, false)) {
                                forceStopPackageLocked(ssp,
                                        intent.getIntExtra(Intent.EXTRA_UID, -1), false, true, true);
                            }
                            if (intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction())) {
                                sendPackageBroadcastLocked(IApplicationThread.PACKAGE_REMOVED,
                                        new String[] {ssp});
                            }
                        }
                    }
                }
            } else {
                String msg = "Permission Denial: " + intent.getAction()
                        + " broadcast from " + callerPackage + " (pid=" + callingPid
                        + ", uid=" + callingUid + ")"
                        + " requires "
                        + android.Manifest.permission.BROADCAST_PACKAGE_REMOVED;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
        }

        /*
         * If this is the time zone changed action, queue up a message that will reset the timezone
         * of all currently running processes. This message will get queued up before the broadcast
         * happens.
         */
        if (intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
            mHandler.sendEmptyMessage(UPDATE_TIME_ZONE);
        }

        /*
         * Prevent non-system code (defined here to be non-persistent
         * processes) from sending protected broadcasts.
         */
        if (callingUid == Process.SYSTEM_UID || callingUid == Process.PHONE_UID
                || callingUid == Process.SHELL_UID || callingUid == 0) {
            // Always okay.
        } else if (callerApp == null || !callerApp.persistent) {
            try {
                if (ActivityThread.getPackageManager().isProtectedBroadcast(
                        intent.getAction())) {
                    String msg = "Permission Denial: not allowed to send broadcast "
                            + intent.getAction() + " from pid="
                            + callingPid + ", uid=" + callingUid;
                    Slog.w(TAG, msg);
                    throw new SecurityException(msg);
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Remote exception", e);
                return BROADCAST_SUCCESS;
            }
        }
        
        // Add to the sticky list if requested.
        if (sticky) {
            if (checkPermission(android.Manifest.permission.BROADCAST_STICKY,
                    callingPid, callingUid)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: broadcastIntent() requesting a sticky broadcast from pid="
                        + callingPid + ", uid=" + callingUid
                        + " requires " + android.Manifest.permission.BROADCAST_STICKY;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
            if (requiredPermission != null) {
                Slog.w(TAG, "Can't broadcast sticky intent " + intent
                        + " and enforce permission " + requiredPermission);
                return BROADCAST_STICKY_CANT_HAVE_PERMISSION;
            }
            if (intent.getComponent() != null) {
                throw new SecurityException(
                        "Sticky broadcasts can't target a specific component");
            }
            ArrayList<Intent> list = mStickyBroadcasts.get(intent.getAction());
            if (list == null) {
                list = new ArrayList<Intent>();
                mStickyBroadcasts.put(intent.getAction(), list);
            }
            int N = list.size();
            int i;
            for (i=0; i<N; i++) {
                if (intent.filterEquals(list.get(i))) {
                    // This sticky already exists, replace it.
                    list.set(i, new Intent(intent));
                    break;
                }
            }
            if (i >= N) {
                list.add(new Intent(intent));
            }
        }

        // Figure out who all will receive this broadcast.
        List receivers = null;
        List<BroadcastFilter> registeredReceivers = null;
        try {
            if (intent.getComponent() != null) {
                // Broadcast is going to one specific receiver class...
                ActivityInfo ai = ActivityThread.getPackageManager().
                    getReceiverInfo(intent.getComponent(), STOCK_PM_FLAGS);
                if (ai != null) {
                    receivers = new ArrayList();
                    ResolveInfo ri = new ResolveInfo();
                    ri.activityInfo = ai;
                    receivers.add(ri);
                }
            } else {
                // Need to resolve the intent to interested receivers...
                if ((intent.getFlags()&Intent.FLAG_RECEIVER_REGISTERED_ONLY)
                         == 0) {
                    receivers =
                        ActivityThread.getPackageManager().queryIntentReceivers(
                                intent, resolvedType, STOCK_PM_FLAGS);
                }
                registeredReceivers = mReceiverResolver.queryIntent(intent, resolvedType, false);
            }
        } catch (RemoteException ex) {
            // pm is in same process, this will never happen.
        }

        final boolean replacePending =
                (intent.getFlags()&Intent.FLAG_RECEIVER_REPLACE_PENDING) != 0;
        
        if (DEBUG_BROADCAST) Slog.v(TAG, "Enqueing broadcast: " + intent.getAction()
                + " replacePending=" + replacePending);
        
        int NR = registeredReceivers != null ? registeredReceivers.size() : 0;
        if (!ordered && NR > 0) {
            // If we are not serializing this broadcast, then send the
            // registered receivers separately so they don't wait for the
            // components to be launched.
            BroadcastRecord r = new BroadcastRecord(intent, callerApp,
                    callerPackage, callingPid, callingUid, requiredPermission,
                    registeredReceivers, resultTo, resultCode, resultData, map,
                    ordered, sticky, false);
            if (DEBUG_BROADCAST) Slog.v(
                    TAG, "Enqueueing parallel broadcast " + r
                    + ": prev had " + mParallelBroadcasts.size());
            boolean replaced = false;
            if (replacePending) {
                for (int i=mParallelBroadcasts.size()-1; i>=0; i--) {
                    if (intent.filterEquals(mParallelBroadcasts.get(i).intent)) {
                        if (DEBUG_BROADCAST) Slog.v(TAG,
                                "***** DROPPING PARALLEL: " + intent);
                        mParallelBroadcasts.set(i, r);
                        replaced = true;
                        break;
                    }
                }
            }
            if (!replaced) {
                mParallelBroadcasts.add(r);
                scheduleBroadcastsLocked();
            }
            registeredReceivers = null;
            NR = 0;
        }

        // Merge into one list.
        int ir = 0;
        if (receivers != null) {
            // A special case for PACKAGE_ADDED: do not allow the package
            // being added to see this broadcast.  This prevents them from
            // using this as a back door to get run as soon as they are
            // installed.  Maybe in the future we want to have a special install
            // broadcast or such for apps, but we'd like to deliberately make
            // this decision.
            String skipPackages[] = null;
            if (intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                    || intent.ACTION_PACKAGE_RESTARTED.equals(intent.getAction())
                    || intent.ACTION_PACKAGE_DATA_CLEARED.equals(intent.getAction())) {
                Uri data = intent.getData();
                if (data != null) {
                    String pkgName = data.getSchemeSpecificPart();
                    if (pkgName != null) {
                        skipPackages = new String[] { pkgName };
                    }
                }
            } else if (intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(intent.getAction())) {
                skipPackages = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
            }
            if (skipPackages != null && (skipPackages.length > 0)) {
                for (String skipPackage : skipPackages) {
                    if (skipPackage != null) {
                        int NT = receivers.size();
                        for (int it=0; it<NT; it++) {
                            ResolveInfo curt = (ResolveInfo)receivers.get(it);
                            if (curt.activityInfo.packageName.equals(skipPackage)) {
                                receivers.remove(it);
                                it--;
                                NT--;
                            }
                        }
                    }
                }
            }

            int NT = receivers != null ? receivers.size() : 0;
            int it = 0;
            ResolveInfo curt = null;
            BroadcastFilter curr = null;
            while (it < NT && ir < NR) {
                if (curt == null) {
                    curt = (ResolveInfo)receivers.get(it);
                }
                if (curr == null) {
                    curr = registeredReceivers.get(ir);
                }
                if (curr.getPriority() >= curt.priority) {
                    // Insert this broadcast record into the final list.
                    receivers.add(it, curr);
                    ir++;
                    curr = null;
                    it++;
                    NT++;
                } else {
                    // Skip to the next ResolveInfo in the final list.
                    it++;
                    curt = null;
                }
            }
        }
        while (ir < NR) {
            if (receivers == null) {
                receivers = new ArrayList();
            }
            receivers.add(registeredReceivers.get(ir));
            ir++;
        }

        if ((receivers != null && receivers.size() > 0)
                || resultTo != null) {
            BroadcastRecord r = new BroadcastRecord(intent, callerApp,
                    callerPackage, callingPid, callingUid, requiredPermission,
                    receivers, resultTo, resultCode, resultData, map, ordered,
                    sticky, false);
            if (DEBUG_BROADCAST) Slog.v(
                    TAG, "Enqueueing ordered broadcast " + r
                    + ": prev had " + mOrderedBroadcasts.size());
            if (DEBUG_BROADCAST) {
                int seq = r.intent.getIntExtra("seq", -1);
                Slog.i(TAG, "Enqueueing broadcast " + r.intent.getAction() + " seq=" + seq);
            }
            boolean replaced = false;
            if (replacePending) {
                for (int i=mOrderedBroadcasts.size()-1; i>=0; i--) {
                    if (intent.filterEquals(mOrderedBroadcasts.get(i).intent)) {
                        if (DEBUG_BROADCAST) Slog.v(TAG,
                                "***** DROPPING ORDERED: " + intent);
                        mOrderedBroadcasts.set(i, r);
                        replaced = true;
                        break;
                    }
                }
            }
            if (!replaced) {
                mOrderedBroadcasts.add(r);
                scheduleBroadcastsLocked();
            }
        }

        return BROADCAST_SUCCESS;
    }

    public final int broadcastIntent(IApplicationThread caller,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle map,
            String requiredPermission, boolean serialized, boolean sticky) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            int flags = intent.getFlags();
            
            if (!mSystemReady) {
                // if the caller really truly claims to know what they're doing, go
                // ahead and allow the broadcast without launching any receivers
                if ((flags&Intent.FLAG_RECEIVER_REGISTERED_ONLY_BEFORE_BOOT) != 0) {
                    intent = new Intent(intent);
                    intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                } else if ((flags&Intent.FLAG_RECEIVER_REGISTERED_ONLY) == 0){
                    Slog.e(TAG, "Attempt to launch receivers of broadcast intent " + intent
                            + " before boot completion");
                    throw new IllegalStateException("Cannot broadcast before boot completed");
                }
            }
            
            if ((flags&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0) {
                throw new IllegalArgumentException(
                        "Can't use FLAG_RECEIVER_BOOT_UPGRADE here");
            }
            
            final ProcessRecord callerApp = getRecordForAppLocked(caller);
            final int callingPid = Binder.getCallingPid();
            final int callingUid = Binder.getCallingUid();
            final long origId = Binder.clearCallingIdentity();
            int res = broadcastIntentLocked(callerApp,
                    callerApp != null ? callerApp.info.packageName : null,
                    intent, resolvedType, resultTo,
                    resultCode, resultData, map, requiredPermission, serialized,
                    sticky, callingPid, callingUid);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    int broadcastIntentInPackage(String packageName, int uid,
            Intent intent, String resolvedType, IIntentReceiver resultTo,
            int resultCode, String resultData, Bundle map,
            String requiredPermission, boolean serialized, boolean sticky) {
        synchronized(this) {
            final long origId = Binder.clearCallingIdentity();
            int res = broadcastIntentLocked(null, packageName, intent, resolvedType,
                    resultTo, resultCode, resultData, map, requiredPermission,
                    serialized, sticky, -1, uid);
            Binder.restoreCallingIdentity(origId);
            return res;
        }
    }

    public final void unbroadcastIntent(IApplicationThread caller,
            Intent intent) {
        // Refuse possible leaked file descriptors
        if (intent != null && intent.hasFileDescriptors() == true) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            if (checkCallingPermission(android.Manifest.permission.BROADCAST_STICKY)
                    != PackageManager.PERMISSION_GRANTED) {
                String msg = "Permission Denial: unbroadcastIntent() from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingUid()
                        + " requires " + android.Manifest.permission.BROADCAST_STICKY;
                Slog.w(TAG, msg);
                throw new SecurityException(msg);
            }
            ArrayList<Intent> list = mStickyBroadcasts.get(intent.getAction());
            if (list != null) {
                int N = list.size();
                int i;
                for (i=0; i<N; i++) {
                    if (intent.filterEquals(list.get(i))) {
                        list.remove(i);
                        break;
                    }
                }
            }
        }
    }

    private final boolean finishReceiverLocked(IBinder receiver, int resultCode,
            String resultData, Bundle resultExtras, boolean resultAbort,
            boolean explicit) {
        if (mOrderedBroadcasts.size() == 0) {
            if (explicit) {
                Slog.w(TAG, "finishReceiver called but no pending broadcasts");
            }
            return false;
        }
        BroadcastRecord r = mOrderedBroadcasts.get(0);
        if (r.receiver == null) {
            if (explicit) {
                Slog.w(TAG, "finishReceiver called but none active");
            }
            return false;
        }
        if (r.receiver != receiver) {
            Slog.w(TAG, "finishReceiver called but active receiver is different");
            return false;
        }
        int state = r.state;
        r.state = r.IDLE;
        if (state == r.IDLE) {
            if (explicit) {
                Slog.w(TAG, "finishReceiver called but state is IDLE");
            }
        }
        r.receiver = null;
        r.intent.setComponent(null);
        if (r.curApp != null) {
            r.curApp.curReceiver = null;
        }
        if (r.curFilter != null) {
            r.curFilter.receiverList.curBroadcast = null;
        }
        r.curFilter = null;
        r.curApp = null;
        r.curComponent = null;
        r.curReceiver = null;
        mPendingBroadcast = null;

        r.resultCode = resultCode;
        r.resultData = resultData;
        r.resultExtras = resultExtras;
        r.resultAbort = resultAbort;

        // We will process the next receiver right now if this is finishing
        // an app receiver (which is always asynchronous) or after we have
        // come back from calling a receiver.
        return state == BroadcastRecord.APP_RECEIVE
                || state == BroadcastRecord.CALL_DONE_RECEIVE;
    }

    public void finishReceiver(IBinder who, int resultCode, String resultData,
            Bundle resultExtras, boolean resultAbort) {
        if (DEBUG_BROADCAST) Slog.v(TAG, "Finish receiver: " + who);

        // Refuse possible leaked file descriptors
        if (resultExtras != null && resultExtras.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        boolean doNext;

        final long origId = Binder.clearCallingIdentity();

        synchronized(this) {
            doNext = finishReceiverLocked(
                who, resultCode, resultData, resultExtras, resultAbort, true);
        }

        if (doNext) {
            processNextBroadcast(false);
        }
        trimApplications();

        Binder.restoreCallingIdentity(origId);
    }

    private final void logBroadcastReceiverDiscard(BroadcastRecord r) {
        if (r.nextReceiver > 0) {
            Object curReceiver = r.receivers.get(r.nextReceiver-1);
            if (curReceiver instanceof BroadcastFilter) {
                BroadcastFilter bf = (BroadcastFilter) curReceiver;
                EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_FILTER,
                        System.identityHashCode(r),
                        r.intent.getAction(),
                        r.nextReceiver - 1,
                        System.identityHashCode(bf));
            } else {
                EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_APP,
                        System.identityHashCode(r),
                        r.intent.getAction(),
                        r.nextReceiver - 1,
                        ((ResolveInfo)curReceiver).toString());
            }
        } else {
            Slog.w(TAG, "Discarding broadcast before first receiver is invoked: "
                    + r);
            EventLog.writeEvent(EventLogTags.AM_BROADCAST_DISCARD_APP,
                    System.identityHashCode(r),
                    r.intent.getAction(),
                    r.nextReceiver,
                    "NONE");
        }
    }

    private final void broadcastTimeout() {
        ProcessRecord app = null;
        String anrMessage = null;

        synchronized (this) {
            if (mOrderedBroadcasts.size() == 0) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            BroadcastRecord r = mOrderedBroadcasts.get(0);
            if ((r.receiverTime+BROADCAST_TIMEOUT) > now) {
                if (DEBUG_BROADCAST) Slog.v(TAG,
                        "Premature timeout @ " + now + ": resetting BROADCAST_TIMEOUT_MSG for "
                        + (r.receiverTime + BROADCAST_TIMEOUT));
                Message msg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG);
                mHandler.sendMessageAtTime(msg, r.receiverTime+BROADCAST_TIMEOUT);
                return;
            }

            Slog.w(TAG, "Timeout of broadcast " + r + " - receiver=" + r.receiver);
            r.receiverTime = now;
            r.anrCount++;

            // Current receiver has passed its expiration date.
            if (r.nextReceiver <= 0) {
                Slog.w(TAG, "Timeout on receiver with nextReceiver <= 0");
                return;
            }

            Object curReceiver = r.receivers.get(r.nextReceiver-1);
            Slog.w(TAG, "Receiver during timeout: " + curReceiver);
            logBroadcastReceiverDiscard(r);
            if (curReceiver instanceof BroadcastFilter) {
                BroadcastFilter bf = (BroadcastFilter)curReceiver;
                if (bf.receiverList.pid != 0
                        && bf.receiverList.pid != MY_PID) {
                    synchronized (this.mPidsSelfLocked) {
                        app = this.mPidsSelfLocked.get(
                                bf.receiverList.pid);
                    }
                }
            } else {
                app = r.curApp;
            }
            
            if (app != null) {
                anrMessage = "Broadcast of " + r.intent.toString();
            }

            if (mPendingBroadcast == r) {
                mPendingBroadcast = null;
            }

            // Move on to the next receiver.
            finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                    r.resultExtras, r.resultAbort, true);
            scheduleBroadcastsLocked();
        }
        
        if (anrMessage != null) {
            appNotResponding(app, null, null, anrMessage);
        }
    }

    private final void processCurBroadcastLocked(BroadcastRecord r,
            ProcessRecord app) throws RemoteException {
        if (app.thread == null) {
            throw new RemoteException();
        }
        r.receiver = app.thread.asBinder();
        r.curApp = app;
        app.curReceiver = r;
        updateLruProcessLocked(app, true, true);

        // Tell the application to launch this receiver.
        r.intent.setComponent(r.curComponent);

        boolean started = false;
        try {
            if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG,
                    "Delivering to component " + r.curComponent
                    + ": " + r);
            ensurePackageDexOpt(r.intent.getComponent().getPackageName());
            app.thread.scheduleReceiver(new Intent(r.intent), r.curReceiver,
                    r.resultCode, r.resultData, r.resultExtras, r.ordered);
            started = true;
        } finally {
            if (!started) {
                r.receiver = null;
                r.curApp = null;
                app.curReceiver = null;
            }
        }

    }

    static void performReceive(ProcessRecord app, IIntentReceiver receiver,
            Intent intent, int resultCode, String data, Bundle extras,
            boolean ordered, boolean sticky) throws RemoteException {
        if (app != null && app.thread != null) {
            // If we have an app thread, do the call through that so it is
            // correctly ordered with other one-way calls.
            app.thread.scheduleRegisteredReceiver(receiver, intent, resultCode,
                    data, extras, ordered, sticky);
        } else {
            receiver.performReceive(intent, resultCode, data, extras, ordered, sticky);
        }
    }
    
    private final void deliverToRegisteredReceiver(BroadcastRecord r,
            BroadcastFilter filter, boolean ordered) {
        boolean skip = false;
        if (filter.requiredPermission != null) {
            int perm = checkComponentPermission(filter.requiredPermission,
                    r.callingPid, r.callingUid, -1);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid="
                        + r.callingPid + ", uid=" + r.callingUid + ")"
                        + " requires " + filter.requiredPermission
                        + " due to registered receiver " + filter);
                skip = true;
            }
        }
        if (r.requiredPermission != null) {
            int perm = checkComponentPermission(r.requiredPermission,
                    filter.receiverList.pid, filter.receiverList.uid, -1);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: receiving "
                        + r.intent.toString()
                        + " to " + filter.receiverList.app
                        + " (pid=" + filter.receiverList.pid
                        + ", uid=" + filter.receiverList.uid + ")"
                        + " requires " + r.requiredPermission
                        + " due to sender " + r.callerPackage
                        + " (uid " + r.callingUid + ")");
                skip = true;
            }
        }

        if (!skip) {
            // If this is not being sent as an ordered broadcast, then we
            // don't want to touch the fields that keep track of the current
            // state of ordered broadcasts.
            if (ordered) {
                r.receiver = filter.receiverList.receiver.asBinder();
                r.curFilter = filter;
                filter.receiverList.curBroadcast = r;
                r.state = BroadcastRecord.CALL_IN_RECEIVE;
                if (filter.receiverList.app != null) {
                    // Bump hosting application to no longer be in background
                    // scheduling class.  Note that we can't do that if there
                    // isn't an app...  but we can only be in that case for
                    // things that directly call the IActivityManager API, which
                    // are already core system stuff so don't matter for this.
                    r.curApp = filter.receiverList.app;
                    filter.receiverList.app.curReceiver = r;
                    updateOomAdjLocked();
                }
            }
            try {
                if (DEBUG_BROADCAST_LIGHT) {
                    int seq = r.intent.getIntExtra("seq", -1);
                    Slog.i(TAG, "Delivering to " + filter
                            + " (seq=" + seq + "): " + r);
                }
                performReceive(filter.receiverList.app, filter.receiverList.receiver,
                    new Intent(r.intent), r.resultCode,
                    r.resultData, r.resultExtras, r.ordered, r.initialSticky);
                if (ordered) {
                    r.state = BroadcastRecord.CALL_DONE_RECEIVE;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Failure sending broadcast " + r.intent, e);
                if (ordered) {
                    r.receiver = null;
                    r.curFilter = null;
                    filter.receiverList.curBroadcast = null;
                    if (filter.receiverList.app != null) {
                        filter.receiverList.app.curReceiver = null;
                    }
                }
            }
        }
    }

    private final void addBroadcastToHistoryLocked(BroadcastRecord r) {
        if (r.callingUid < 0) {
            // This was from a registerReceiver() call; ignore it.
            return;
        }
        System.arraycopy(mBroadcastHistory, 0, mBroadcastHistory, 1,
                MAX_BROADCAST_HISTORY-1);
        r.finishTime = SystemClock.uptimeMillis();
        mBroadcastHistory[0] = r;
    }
    
    private final void processNextBroadcast(boolean fromMsg) {
        synchronized(this) {
            BroadcastRecord r;

            if (DEBUG_BROADCAST) Slog.v(TAG, "processNextBroadcast: "
                    + mParallelBroadcasts.size() + " broadcasts, "
                    + mOrderedBroadcasts.size() + " ordered broadcasts");

            updateCpuStats();
            
            if (fromMsg) {
                mBroadcastsScheduled = false;
            }

            // First, deliver any non-serialized broadcasts right away.
            while (mParallelBroadcasts.size() > 0) {
                r = mParallelBroadcasts.remove(0);
                r.dispatchTime = SystemClock.uptimeMillis();
                final int N = r.receivers.size();
                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG, "Processing parallel broadcast "
                        + r);
                for (int i=0; i<N; i++) {
                    Object target = r.receivers.get(i);
                    if (DEBUG_BROADCAST)  Slog.v(TAG,
                            "Delivering non-ordered to registered "
                            + target + ": " + r);
                    deliverToRegisteredReceiver(r, (BroadcastFilter)target, false);
                }
                addBroadcastToHistoryLocked(r);
                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG, "Done with parallel broadcast "
                        + r);
            }

            // Now take care of the next serialized one...

            // If we are waiting for a process to come up to handle the next
            // broadcast, then do nothing at this point.  Just in case, we
            // check that the process we're waiting for still exists.
            if (mPendingBroadcast != null) {
                if (DEBUG_BROADCAST_LIGHT) {
                    Slog.v(TAG, "processNextBroadcast: waiting for "
                            + mPendingBroadcast.curApp);
                }

                boolean isDead;
                synchronized (mPidsSelfLocked) {
                    isDead = (mPidsSelfLocked.get(mPendingBroadcast.curApp.pid) == null);
                }
                if (!isDead) {
                    // It's still alive, so keep waiting
                    return;
                } else {
                    Slog.w(TAG, "pending app " + mPendingBroadcast.curApp
                            + " died before responding to broadcast");
                    mPendingBroadcast = null;
                }
            }

            boolean looped = false;
            
            do {
                if (mOrderedBroadcasts.size() == 0) {
                    // No more broadcasts pending, so all done!
                    scheduleAppGcsLocked();
                    if (looped) {
                        // If we had finished the last ordered broadcast, then
                        // make sure all processes have correct oom and sched
                        // adjustments.
                        updateOomAdjLocked();
                    }
                    return;
                }
                r = mOrderedBroadcasts.get(0);
                boolean forceReceive = false;

                // Ensure that even if something goes awry with the timeout
                // detection, we catch "hung" broadcasts here, discard them,
                // and continue to make progress.
                //
                // This is only done if the system is ready so that PRE_BOOT_COMPLETED
                // receivers don't get executed with with timeouts. They're intended for
                // one time heavy lifting after system upgrades and can take
                // significant amounts of time.
                int numReceivers = (r.receivers != null) ? r.receivers.size() : 0;
                if (mSystemReady && r.dispatchTime > 0) {
                    long now = SystemClock.uptimeMillis();
                    if ((numReceivers > 0) &&
                            (now > r.dispatchTime + (2*BROADCAST_TIMEOUT*numReceivers))) {
                        Slog.w(TAG, "Hung broadcast discarded after timeout failure:"
                                + " now=" + now
                                + " dispatchTime=" + r.dispatchTime
                                + " startTime=" + r.receiverTime
                                + " intent=" + r.intent
                                + " numReceivers=" + numReceivers
                                + " nextReceiver=" + r.nextReceiver
                                + " state=" + r.state);
                        broadcastTimeout(); // forcibly finish this broadcast
                        forceReceive = true;
                        r.state = BroadcastRecord.IDLE;
                    }
                }

                if (r.state != BroadcastRecord.IDLE) {
                    if (DEBUG_BROADCAST) Slog.d(TAG,
                            "processNextBroadcast() called when not idle (state="
                            + r.state + ")");
                    return;
                }

                if (r.receivers == null || r.nextReceiver >= numReceivers
                        || r.resultAbort || forceReceive) {
                    // No more receivers for this broadcast!  Send the final
                    // result if requested...
                    if (r.resultTo != null) {
                        try {
                            if (DEBUG_BROADCAST) {
                                int seq = r.intent.getIntExtra("seq", -1);
                                Slog.i(TAG, "Finishing broadcast " + r.intent.getAction()
                                        + " seq=" + seq + " app=" + r.callerApp);
                            }
                            performReceive(r.callerApp, r.resultTo,
                                new Intent(r.intent), r.resultCode,
                                r.resultData, r.resultExtras, false, false);
                        } catch (RemoteException e) {
                            Slog.w(TAG, "Failure sending broadcast result of " + r.intent, e);
                        }
                    }
                    
                    if (DEBUG_BROADCAST) Slog.v(TAG, "Cancelling BROADCAST_TIMEOUT_MSG");
                    mHandler.removeMessages(BROADCAST_TIMEOUT_MSG);

                    if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG, "Finished with ordered broadcast "
                            + r);
                    
                    // ... and on to the next...
                    addBroadcastToHistoryLocked(r);
                    mOrderedBroadcasts.remove(0);
                    r = null;
                    looped = true;
                    continue;
                }
            } while (r == null);

            // Get the next receiver...
            int recIdx = r.nextReceiver++;

            // Keep track of when this receiver started, and make sure there
            // is a timeout message pending to kill it if need be.
            r.receiverTime = SystemClock.uptimeMillis();
            if (recIdx == 0) {
                r.dispatchTime = r.receiverTime;

                if (DEBUG_BROADCAST_LIGHT) Slog.v(TAG, "Processing ordered broadcast "
                        + r);
                if (DEBUG_BROADCAST) Slog.v(TAG,
                        "Submitting BROADCAST_TIMEOUT_MSG for "
                        + (r.receiverTime + BROADCAST_TIMEOUT));
                Message msg = mHandler.obtainMessage(BROADCAST_TIMEOUT_MSG);
                mHandler.sendMessageAtTime(msg, r.receiverTime+BROADCAST_TIMEOUT);
            }

            Object nextReceiver = r.receivers.get(recIdx);
            if (nextReceiver instanceof BroadcastFilter) {
                // Simple case: this is a registered receiver who gets
                // a direct call.
                BroadcastFilter filter = (BroadcastFilter)nextReceiver;
                if (DEBUG_BROADCAST)  Slog.v(TAG,
                        "Delivering ordered to registered "
                        + filter + ": " + r);
                deliverToRegisteredReceiver(r, filter, r.ordered);
                if (r.receiver == null || !r.ordered) {
                    // The receiver has already finished, so schedule to
                    // process the next one.
                    if (DEBUG_BROADCAST) Slog.v(TAG, "Quick finishing: ordered="
                            + r.ordered + " receiver=" + r.receiver);
                    r.state = BroadcastRecord.IDLE;
                    scheduleBroadcastsLocked();
                }
                return;
            }

            // Hard case: need to instantiate the receiver, possibly
            // starting its application process to host it.

            ResolveInfo info =
                (ResolveInfo)nextReceiver;

            boolean skip = false;
            int perm = checkComponentPermission(info.activityInfo.permission,
                    r.callingPid, r.callingUid,
                    info.activityInfo.exported
                            ? -1 : info.activityInfo.applicationInfo.uid);
            if (perm != PackageManager.PERMISSION_GRANTED) {
                Slog.w(TAG, "Permission Denial: broadcasting "
                        + r.intent.toString()
                        + " from " + r.callerPackage + " (pid=" + r.callingPid
                        + ", uid=" + r.callingUid + ")"
                        + " requires " + info.activityInfo.permission
                        + " due to receiver " + info.activityInfo.packageName
                        + "/" + info.activityInfo.name);
                skip = true;
            }
            if (r.callingUid != Process.SYSTEM_UID &&
                r.requiredPermission != null) {
                try {
                    perm = ActivityThread.getPackageManager().
                            checkPermission(r.requiredPermission,
                                    info.activityInfo.applicationInfo.packageName);
                } catch (RemoteException e) {
                    perm = PackageManager.PERMISSION_DENIED;
                }
                if (perm != PackageManager.PERMISSION_GRANTED) {
                    Slog.w(TAG, "Permission Denial: receiving "
                            + r.intent + " to "
                            + info.activityInfo.applicationInfo.packageName
                            + " requires " + r.requiredPermission
                            + " due to sender " + r.callerPackage
                            + " (uid " + r.callingUid + ")");
                    skip = true;
                }
            }
            if (r.curApp != null && r.curApp.crashing) {
                // If the target process is crashing, just skip it.
                skip = true;
            }

            if (skip) {
                r.receiver = null;
                r.curFilter = null;
                r.state = BroadcastRecord.IDLE;
                scheduleBroadcastsLocked();
                return;
            }

            r.state = BroadcastRecord.APP_RECEIVE;
            String targetProcess = info.activityInfo.processName;
            r.curComponent = new ComponentName(
                    info.activityInfo.applicationInfo.packageName,
                    info.activityInfo.name);
            r.curReceiver = info.activityInfo;

            // Is this receiver's application already running?
            ProcessRecord app = getProcessRecordLocked(targetProcess,
                    info.activityInfo.applicationInfo.uid);
            if (app != null && app.thread != null) {
                try {
                    processCurBroadcastLocked(r, app);
                    return;
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception when sending broadcast to "
                          + r.curComponent, e);
                }

                // If a dead object exception was thrown -- fall through to
                // restart the application.
            }

            // Not running -- get it started, to be executed when the app comes up.
            if ((r.curApp=startProcessLocked(targetProcess,
                    info.activityInfo.applicationInfo, true,
                    r.intent.getFlags() | Intent.FLAG_FROM_BACKGROUND,
                    "broadcast", r.curComponent,
                    (r.intent.getFlags()&Intent.FLAG_RECEIVER_BOOT_UPGRADE) != 0))
                            == null) {
                // Ah, this recipient is unavailable.  Finish it if necessary,
                // and mark the broadcast record as ready for the next.
                Slog.w(TAG, "Unable to launch app "
                        + info.activityInfo.applicationInfo.packageName + "/"
                        + info.activityInfo.applicationInfo.uid + " for broadcast "
                        + r.intent + ": process is bad");
                logBroadcastReceiverDiscard(r);
                finishReceiverLocked(r.receiver, r.resultCode, r.resultData,
                        r.resultExtras, r.resultAbort, true);
                scheduleBroadcastsLocked();
                r.state = BroadcastRecord.IDLE;
                return;
            }

            mPendingBroadcast = r;
        }
    }

    // =========================================================
    // INSTRUMENTATION
    // =========================================================

    public boolean startInstrumentation(ComponentName className,
            String profileFile, int flags, Bundle arguments,
            IInstrumentationWatcher watcher) {
        // Refuse possible leaked file descriptors
        if (arguments != null && arguments.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Bundle");
        }

        synchronized(this) {
            InstrumentationInfo ii = null;
            ApplicationInfo ai = null;
            try {
                ii = mContext.getPackageManager().getInstrumentationInfo(
                    className, STOCK_PM_FLAGS);
                ai = mContext.getPackageManager().getApplicationInfo(
                    ii.targetPackage, STOCK_PM_FLAGS);
            } catch (PackageManager.NameNotFoundException e) {
            }
            if (ii == null) {
                reportStartInstrumentationFailure(watcher, className,
                        "Unable to find instrumentation info for: " + className);
                return false;
            }
            if (ai == null) {
                reportStartInstrumentationFailure(watcher, className,
                        "Unable to find instrumentation target package: " + ii.targetPackage);
                return false;
            }

            int match = mContext.getPackageManager().checkSignatures(
                    ii.targetPackage, ii.packageName);
            if (match < 0 && match != PackageManager.SIGNATURE_FIRST_NOT_SIGNED) {
                String msg = "Permission Denial: starting instrumentation "
                        + className + " from pid="
                        + Binder.getCallingPid()
                        + ", uid=" + Binder.getCallingPid()
                        + " not allowed because package " + ii.packageName
                        + " does not have a signature matching the target "
                        + ii.targetPackage;
                reportStartInstrumentationFailure(watcher, className, msg);
                throw new SecurityException(msg);
            }

            final long origId = Binder.clearCallingIdentity();
            forceStopPackageLocked(ii.targetPackage, -1, true, false, true);
            ProcessRecord app = addAppLocked(ai);
            app.instrumentationClass = className;
            app.instrumentationInfo = ai;
            app.instrumentationProfileFile = profileFile;
            app.instrumentationArguments = arguments;
            app.instrumentationWatcher = watcher;
            app.instrumentationResultClass = className;
            Binder.restoreCallingIdentity(origId);
        }

        return true;
    }
    
    /**
     * Report errors that occur while attempting to start Instrumentation.  Always writes the 
     * error to the logs, but if somebody is watching, send the report there too.  This enables
     * the "am" command to report errors with more information.
     * 
     * @param watcher The IInstrumentationWatcher.  Null if there isn't one.
     * @param cn The component name of the instrumentation.
     * @param report The error report.
     */
    private void reportStartInstrumentationFailure(IInstrumentationWatcher watcher, 
            ComponentName cn, String report) {
        Slog.w(TAG, report);
        try {
            if (watcher != null) {
                Bundle results = new Bundle();
                results.putString(Instrumentation.REPORT_KEY_IDENTIFIER, "ActivityManagerService");
                results.putString("Error", report);
                watcher.instrumentationStatus(cn, -1, results);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, e);
        }
    }

    void finishInstrumentationLocked(ProcessRecord app, int resultCode, Bundle results) {
        if (app.instrumentationWatcher != null) {
            try {
                // NOTE:  IInstrumentationWatcher *must* be oneway here
                app.instrumentationWatcher.instrumentationFinished(
                    app.instrumentationClass,
                    resultCode,
                    results);
            } catch (RemoteException e) {
            }
        }
        app.instrumentationWatcher = null;
        app.instrumentationClass = null;
        app.instrumentationInfo = null;
        app.instrumentationProfileFile = null;
        app.instrumentationArguments = null;

        forceStopPackageLocked(app.processName, -1, false, false, true);
    }

    public void finishInstrumentation(IApplicationThread target,
            int resultCode, Bundle results) {
        // Refuse possible leaked file descriptors
        if (results != null && results.hasFileDescriptors()) {
            throw new IllegalArgumentException("File descriptors passed in Intent");
        }

        synchronized(this) {
            ProcessRecord app = getRecordForAppLocked(target);
            if (app == null) {
                Slog.w(TAG, "finishInstrumentation: no app for " + target);
                return;
            }
            final long origId = Binder.clearCallingIdentity();
            finishInstrumentationLocked(app, resultCode, results);
            Binder.restoreCallingIdentity(origId);
        }
    }

    // =========================================================
    // CONFIGURATION
    // =========================================================
    
    public ConfigurationInfo getDeviceConfigurationInfo() {
        ConfigurationInfo config = new ConfigurationInfo();
        synchronized (this) {
            config.reqTouchScreen = mConfiguration.touchscreen;
            config.reqKeyboardType = mConfiguration.keyboard;
            config.reqNavigation = mConfiguration.navigation;
            if (mConfiguration.navigation == Configuration.NAVIGATION_DPAD
                    || mConfiguration.navigation == Configuration.NAVIGATION_TRACKBALL) {
                config.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
            }
            if (mConfiguration.keyboard != Configuration.KEYBOARD_UNDEFINED
                    && mConfiguration.keyboard != Configuration.KEYBOARD_NOKEYS) {
                config.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
            }
            config.reqGlEsVersion = GL_ES_VERSION;
        }
        return config;
    }

    public Configuration getConfiguration() {
        Configuration ci;
        synchronized(this) {
            ci = new Configuration(mConfiguration);
        }
        return ci;
    }

    public void updateConfiguration(Configuration values) {
        enforceCallingPermission(android.Manifest.permission.CHANGE_CONFIGURATION,
                "updateConfiguration()");

        synchronized(this) {
            if (values == null && mWindowManager != null) {
                // sentinel: fetch the current configuration from the window manager
                values = mWindowManager.computeNewConfiguration();
            }
            
            final long origId = Binder.clearCallingIdentity();
            updateConfigurationLocked(values, null);
            Binder.restoreCallingIdentity(origId);
        }
    }

    /**
     * Do either or both things: (1) change the current configuration, and (2)
     * make sure the given activity is running with the (now) current
     * configuration.  Returns true if the activity has been left running, or
     * false if <var>starting</var> is being destroyed to match the new
     * configuration.
     */
    public boolean updateConfigurationLocked(Configuration values,
            HistoryRecord starting) {
        int changes = 0;
        
        boolean kept = true;
        
        if (values != null) {
            Configuration newConfig = new Configuration(mConfiguration);
            changes = newConfig.updateFrom(values);
            if (changes != 0) {
                if (DEBUG_SWITCH || DEBUG_CONFIGURATION) {
                    Slog.i(TAG, "Updating configuration to: " + values);
                }
                
                EventLog.writeEvent(EventLogTags.CONFIGURATION_CHANGED, changes);

                if (values.locale != null) {
                    saveLocaleLocked(values.locale, 
                                     !values.locale.equals(mConfiguration.locale),
                                     values.userSetLocale);
                }

                mConfigurationSeq++;
                if (mConfigurationSeq <= 0) {
                    mConfigurationSeq = 1;
                }
                newConfig.seq = mConfigurationSeq;
                mConfiguration = newConfig;
                Slog.i(TAG, "Config changed: " + newConfig);
                
                AttributeCache ac = AttributeCache.instance();
                if (ac != null) {
                    ac.updateConfiguration(mConfiguration);
                }

                if (Settings.System.hasInterestingConfigurationChanges(changes)) {
                    Message msg = mHandler.obtainMessage(UPDATE_CONFIGURATION_MSG);
                    msg.obj = new Configuration(mConfiguration);
                    mHandler.sendMessage(msg);
                }
        
                for (int i=mLruProcesses.size()-1; i>=0; i--) {
                    ProcessRecord app = mLruProcesses.get(i);
                    try {
                        if (app.thread != null) {
                            if (DEBUG_CONFIGURATION) Slog.v(TAG, "Sending to proc "
                                    + app.processName + " new config " + mConfiguration);
                            app.thread.scheduleConfigurationChanged(mConfiguration);
                        }
                    } catch (Exception e) {
                    }
                }
                Intent intent = new Intent(Intent.ACTION_CONFIGURATION_CHANGED);
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY
                        | Intent.FLAG_RECEIVER_REPLACE_PENDING);
                broadcastIntentLocked(null, null, intent, null, null, 0, null, null,
                        null, false, false, MY_PID, Process.SYSTEM_UID);
                if ((changes&ActivityInfo.CONFIG_LOCALE) != 0) {
                    broadcastIntentLocked(null, null,
                            new Intent(Intent.ACTION_LOCALE_CHANGED),
                            null, null, 0, null, null,
                            null, false, false, MY_PID, Process.SYSTEM_UID);
                }
            }
        }
        
        if (changes != 0 && starting == null) {
            // If the configuration changed, and the caller is not already
            // in the process of starting an activity, then find the top
            // activity to check if its configuration needs to change.
            starting = topRunningActivityLocked(null);
        }
        
        if (starting != null) {
            kept = ensureActivityConfigurationLocked(starting, changes);
            if (kept) {
                // If this didn't result in the starting activity being
                // destroyed, then we need to make sure at this point that all
                // other activities are made visible.
                if (DEBUG_SWITCH) Slog.i(TAG, "Config didn't destroy " + starting
                        + ", ensuring others are correct.");
                ensureActivitiesVisibleLocked(starting, changes);
            }
        }
        
        if (values != null && mWindowManager != null) {
            mWindowManager.setNewConfiguration(mConfiguration);
        }
        
        return kept;
    }

    private final boolean relaunchActivityLocked(HistoryRecord r,
            int changes, boolean andResume) {
        List<ResultInfo> results = null;
        List<Intent> newIntents = null;
        if (andResume) {
            results = r.results;
            newIntents = r.newIntents;
        }
        if (DEBUG_SWITCH) Slog.v(TAG, "Relaunching: " + r
                + " with results=" + results + " newIntents=" + newIntents
                + " andResume=" + andResume);
        EventLog.writeEvent(andResume ? EventLogTags.AM_RELAUNCH_RESUME_ACTIVITY
                : EventLogTags.AM_RELAUNCH_ACTIVITY, System.identityHashCode(r),
                r.task.taskId, r.shortComponentName);
        
        r.startFreezingScreenLocked(r.app, 0);
        
        try {
            if (DEBUG_SWITCH) Slog.i(TAG, "Switch is restarting resumed " + r);
            r.app.thread.scheduleRelaunchActivity(r, results, newIntents,
                    changes, !andResume, mConfiguration);
            // Note: don't need to call pauseIfSleepingLocked() here, because
            // the caller will only pass in 'andResume' if this activity is
            // currently resumed, which implies we aren't sleeping.
        } catch (RemoteException e) {
            return false;
        }

        if (andResume) {
            r.results = null;
            r.newIntents = null;
            reportResumedActivityLocked(r);
        }

        return true;
    }

    /**
     * Make sure the given activity matches the current configuration.  Returns
     * false if the activity had to be destroyed.  Returns true if the
     * configuration is the same, or the activity will remain running as-is
     * for whatever reason.  Ensures the HistoryRecord is updated with the
     * correct configuration and all other bookkeeping is handled.
     */
    private final boolean ensureActivityConfigurationLocked(HistoryRecord r,
            int globalChanges) {
        if (mConfigWillChange) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Skipping config check (will change): " + r);
            return true;
        }
        
        if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                "Ensuring correct configuration: " + r);
        
        // Short circuit: if the two configurations are the exact same
        // object (the common case), then there is nothing to do.
        Configuration newConfig = mConfiguration;
        if (r.configuration == newConfig) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Configuration unchanged in " + r);
            return true;
        }
        
        // We don't worry about activities that are finishing.
        if (r.finishing) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Configuration doesn't matter in finishing " + r);
            r.stopFreezingScreenLocked(false);
            return true;
        }
        
        // Okay we now are going to make this activity have the new config.
        // But then we need to figure out how it needs to deal with that.
        Configuration oldConfig = r.configuration;
        r.configuration = newConfig;
        
        // If the activity isn't currently running, just leave the new
        // configuration and it will pick that up next time it starts.
        if (r.app == null || r.app.thread == null) {
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                    "Configuration doesn't matter not running " + r);
            r.stopFreezingScreenLocked(false);
            return true;
        }
        
        // If the activity isn't persistent, there is a chance we will
        // need to restart it.
        if (!r.persistent) {

            // Figure out what has changed between the two configurations.
            int changes = oldConfig.diff(newConfig);
            if (DEBUG_SWITCH || DEBUG_CONFIGURATION) {
                Slog.v(TAG, "Checking to restart " + r.info.name + ": changed=0x"
                        + Integer.toHexString(changes) + ", handles=0x"
                        + Integer.toHexString(r.info.configChanges)
                        + ", newConfig=" + newConfig);
            }
            if ((changes&(~r.info.configChanges)) != 0) {
                // Aha, the activity isn't handling the change, so DIE DIE DIE.
                r.configChangeFlags |= changes;
                r.startFreezingScreenLocked(r.app, globalChanges);
                if (r.app == null || r.app.thread == null) {
                    if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                            "Switch is destroying non-running " + r);
                    destroyActivityLocked(r, true);
                } else if (r.state == ActivityState.PAUSING) {
                    // A little annoying: we are waiting for this activity to
                    // finish pausing.  Let's not do anything now, but just
                    // flag that it needs to be restarted when done pausing.
                    if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                            "Switch is skipping already pausing " + r);
                    r.configDestroy = true;
                    return true;
                } else if (r.state == ActivityState.RESUMED) {
                    // Try to optimize this case: the configuration is changing
                    // and we need to restart the top, resumed activity.
                    // Instead of doing the normal handshaking, just say
                    // "restart!".
                    if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                            "Switch is restarting resumed " + r);
                    relaunchActivityLocked(r, r.configChangeFlags, true);
                    r.configChangeFlags = 0;
                } else {
                    if (DEBUG_SWITCH || DEBUG_CONFIGURATION) Slog.v(TAG,
                            "Switch is restarting non-resumed " + r);
                    relaunchActivityLocked(r, r.configChangeFlags, false);
                    r.configChangeFlags = 0;
                }
                
                // All done...  tell the caller we weren't able to keep this
                // activity around.
                return false;
            }
        }
        
        // Default case: the activity can handle this new configuration, so
        // hand it over.  Note that we don't need to give it the new
        // configuration, since we always send configuration changes to all
        // process when they happen so it can just use whatever configuration
        // it last got.
        if (r.app != null && r.app.thread != null) {
            try {
                if (DEBUG_CONFIGURATION) Slog.v(TAG, "Sending new config to " + r);
                r.app.thread.scheduleActivityConfigurationChanged(r);
            } catch (RemoteException e) {
                // If process died, whatever.
            }
        }
        r.stopFreezingScreenLocked(false);
        
        return true;
    }
    
    /**
     * Save the locale.  You must be inside a synchronized (this) block.
     */
    private void saveLocaleLocked(Locale l, boolean isDiff, boolean isPersist) {
        if(isDiff) {
            SystemProperties.set("user.language", l.getLanguage());
            SystemProperties.set("user.region", l.getCountry());
        } 

        if(isPersist) {
            SystemProperties.set("persist.sys.language", l.getLanguage());
            SystemProperties.set("persist.sys.country", l.getCountry());
            SystemProperties.set("persist.sys.localevar", l.getVariant());
        }
    }

    // =========================================================
    // LIFETIME MANAGEMENT
    // =========================================================

    private final int computeOomAdjLocked(ProcessRecord app, int hiddenAdj,
            ProcessRecord TOP_APP, boolean recursed) {
        if (mAdjSeq == app.adjSeq) {
            // This adjustment has already been computed.  If we are calling
            // from the top, we may have already computed our adjustment with
            // an earlier hidden adjustment that isn't really for us... if
            // so, use the new hidden adjustment.
            if (!recursed && app.hidden) {
                app.curAdj = hiddenAdj;
            }
            return app.curAdj;
        }

        if (app.thread == null) {
            app.adjSeq = mAdjSeq;
            app.curSchedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            return (app.curAdj=EMPTY_APP_ADJ);
        }

        if (app.maxAdj <= FOREGROUND_APP_ADJ) {
            // The max adjustment doesn't allow this app to be anything
            // below foreground, so it is not worth doing work for it.
            app.adjType = "fixed";
            app.adjSeq = mAdjSeq;
            app.curRawAdj = app.maxAdj;
            app.curSchedGroup = Process.THREAD_GROUP_DEFAULT;
            return (app.curAdj=app.maxAdj);
       }
        
        app.adjTypeCode = ActivityManager.RunningAppProcessInfo.REASON_UNKNOWN;
        app.adjSource = null;
        app.adjTarget = null;
        app.empty = false;
        app.hidden = false;

        // Determine the importance of the process, starting with most
        // important to least, and assign an appropriate OOM adjustment.
        int adj;
        int schedGroup;
        int N;
        if (app == TOP_APP) {
            // The last app on the list is the foreground app.
            adj = FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "top-activity";
        } else if (app.instrumentationClass != null) {
            // Don't want to kill running instrumentation.
            adj = FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "instrumentation";
        } else if (app.persistentActivities > 0) {
            // Special persistent activities...  shouldn't be used these days.
            adj = FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "persistent";
        } else if (app.curReceiver != null ||
                (mPendingBroadcast != null && mPendingBroadcast.curApp == app)) {
            // An app that is currently receiving a broadcast also
            // counts as being in the foreground.
            adj = FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "broadcast";
        } else if (app.executingServices.size() > 0) {
            // An app that is currently executing a service callback also
            // counts as being in the foreground.
            adj = FOREGROUND_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "exec-service";
        } else if (app.foregroundServices) {
            // The user is aware of this app, so make it visible.
            adj = VISIBLE_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "foreground-service";
        } else if (app.forcingToForeground != null) {
            // The user is aware of this app, so make it visible.
            adj = VISIBLE_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_DEFAULT;
            app.adjType = "force-foreground";
            app.adjSource = app.forcingToForeground;
        } else if (app == mHomeProcess) {
            // This process is hosting what we currently consider to be the
            // home app, so we don't want to let it go into the background.
            adj = HOME_APP_ADJ;
            schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.adjType = "home";
        } else if ((N=app.activities.size()) != 0) {
            // This app is in the background with paused activities.
            app.hidden = true;
            adj = hiddenAdj;
            schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            app.adjType = "bg-activities";
            N = app.activities.size();
            for (int j=0; j<N; j++) {
                if (((HistoryRecord)app.activities.get(j)).visible) {
                    // This app has a visible activity!
                    app.hidden = false;
                    adj = VISIBLE_APP_ADJ;
                    schedGroup = Process.THREAD_GROUP_DEFAULT;
                    app.adjType = "visible";
                    break;
                }
            }
        } else {
            // A very not-needed process.  If this is lower in the lru list,
            // we will push it in to the empty bucket.
            app.hidden = true;
            app.empty = true;
            schedGroup = Process.THREAD_GROUP_BG_NONINTERACTIVE;
            adj = hiddenAdj;
            app.adjType = "bg-empty";
        }

        //Slog.i(TAG, "OOM " + app + ": initial adj=" + adj);
        
        // By default, we use the computed adjustment.  It may be changed if
        // there are applications dependent on our services or providers, but
        // this gives us a baseline and makes sure we don't get into an
        // infinite recursion.
        app.adjSeq = mAdjSeq;
        app.curRawAdj = adj;

        if (mBackupTarget != null && app == mBackupTarget.app) {
            // If possible we want to avoid killing apps while they're being backed up
            if (adj > BACKUP_APP_ADJ) {
                if (DEBUG_BACKUP) Slog.v(TAG, "oom BACKUP_APP_ADJ for " + app);
                adj = BACKUP_APP_ADJ;
                app.adjType = "backup";
                app.hidden = false;
            }
        }

        if (app.services.size() != 0 && (adj > FOREGROUND_APP_ADJ
                || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE)) {
            final long now = SystemClock.uptimeMillis();
            // This process is more important if the top activity is
            // bound to the service.
            Iterator jt = app.services.iterator();
            while (jt.hasNext() && adj > FOREGROUND_APP_ADJ) {
                ServiceRecord s = (ServiceRecord)jt.next();
                if (s.startRequested) {
                    if (now < (s.lastActivity+MAX_SERVICE_INACTIVITY)) {
                        // This service has seen some activity within
                        // recent memory, so we will keep its process ahead
                        // of the background processes.
                        if (adj > SECONDARY_SERVER_ADJ) {
                            adj = SECONDARY_SERVER_ADJ;
                            app.adjType = "started-services";
                            app.hidden = false;
                        }
                    }
                    // If we have let the service slide into the background
                    // state, still have some text describing what it is doing
                    // even though the service no longer has an impact.
                    if (adj > SECONDARY_SERVER_ADJ) {
                        app.adjType = "started-bg-services";
                    }
                }
                if (s.connections.size() > 0 && (adj > FOREGROUND_APP_ADJ
                        || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE)) {
                    Iterator<ConnectionRecord> kt
                            = s.connections.values().iterator();
                    while (kt.hasNext() && adj > FOREGROUND_APP_ADJ) {
                        // XXX should compute this based on the max of
                        // all connected clients.
                        ConnectionRecord cr = kt.next();
                        if (cr.binding.client == app) {
                            // Binding to ourself is not interesting.
                            continue;
                        }
                        if ((cr.flags&Context.BIND_AUTO_CREATE) != 0) {
                            ProcessRecord client = cr.binding.client;
                            int myHiddenAdj = hiddenAdj;
                            if (myHiddenAdj > client.hiddenAdj) {
                                if (client.hiddenAdj > VISIBLE_APP_ADJ) {
                                    myHiddenAdj = client.hiddenAdj;
                                } else {
                                    myHiddenAdj = VISIBLE_APP_ADJ;
                                }
                            }
                            int clientAdj = computeOomAdjLocked(
                                client, myHiddenAdj, TOP_APP, true);
                            if (adj > clientAdj) {
                                adj = clientAdj > VISIBLE_APP_ADJ
                                        ? clientAdj : VISIBLE_APP_ADJ;
                                if (!client.hidden) {
                                    app.hidden = false;
                                }
                                app.adjType = "service";
                                app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                        .REASON_SERVICE_IN_USE;
                                app.adjSource = cr.binding.client;
                                app.adjTarget = s.serviceInfo.name;
                            }
                            if ((cr.flags&Context.BIND_NOT_FOREGROUND) == 0) {
                                if (client.curSchedGroup == Process.THREAD_GROUP_DEFAULT) {
                                    schedGroup = Process.THREAD_GROUP_DEFAULT;
                                }
                            }
                        }
                        HistoryRecord a = cr.activity;
                        //if (a != null) {
                        //    Slog.i(TAG, "Connection to " + a ": state=" + a.state);
                        //}
                        if (a != null && adj > FOREGROUND_APP_ADJ &&
                                (a.state == ActivityState.RESUMED
                                 || a.state == ActivityState.PAUSING)) {
                            adj = FOREGROUND_APP_ADJ;
                            schedGroup = Process.THREAD_GROUP_DEFAULT;
                            app.hidden = false;
                            app.adjType = "service";
                            app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                    .REASON_SERVICE_IN_USE;
                            app.adjSource = a;
                            app.adjTarget = s.serviceInfo.name;
                        }
                    }
                }
            }
            
            // Finally, f this process has active services running in it, we
            // would like to avoid killing it unless it would prevent the current
            // application from running.  By default we put the process in
            // with the rest of the background processes; as we scan through
            // its services we may bump it up from there.
            if (adj > hiddenAdj) {
                adj = hiddenAdj;
                app.hidden = false;
                app.adjType = "bg-services";
            }
        }

        if (app.pubProviders.size() != 0 && (adj > FOREGROUND_APP_ADJ
                || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE)) {
            Iterator jt = app.pubProviders.values().iterator();
            while (jt.hasNext() && (adj > FOREGROUND_APP_ADJ
                    || schedGroup == Process.THREAD_GROUP_BG_NONINTERACTIVE)) {
                ContentProviderRecord cpr = (ContentProviderRecord)jt.next();
                if (cpr.clients.size() != 0) {
                    Iterator<ProcessRecord> kt = cpr.clients.iterator();
                    while (kt.hasNext() && adj > FOREGROUND_APP_ADJ) {
                        ProcessRecord client = kt.next();
                        if (client == app) {
                            // Being our own client is not interesting.
                            continue;
                        }
                        int myHiddenAdj = hiddenAdj;
                        if (myHiddenAdj > client.hiddenAdj) {
                            if (client.hiddenAdj > FOREGROUND_APP_ADJ) {
                                myHiddenAdj = client.hiddenAdj;
                            } else {
                                myHiddenAdj = FOREGROUND_APP_ADJ;
                            }
                        }
                        int clientAdj = computeOomAdjLocked(
                            client, myHiddenAdj, TOP_APP, true);
                        if (adj > clientAdj) {
                            adj = clientAdj > FOREGROUND_APP_ADJ
                                    ? clientAdj : FOREGROUND_APP_ADJ;
                            if (!client.hidden) {
                                app.hidden = false;
                            }
                            app.adjType = "provider";
                            app.adjTypeCode = ActivityManager.RunningAppProcessInfo
                                    .REASON_PROVIDER_IN_USE;
                            app.adjSource = client;
                            app.adjTarget = cpr.info.name;
                        }
                        if (client.curSchedGroup == Process.THREAD_GROUP_DEFAULT) {
                            schedGroup = Process.THREAD_GROUP_DEFAULT;
                        }
                    }
                }
                // If the provider has external (non-framework) process
                // dependencies, ensure that its adjustment is at least
                // FOREGROUND_APP_ADJ.
                if (cpr.externals != 0) {
                    if (adj > FOREGROUND_APP_ADJ) {
                        adj = FOREGROUND_APP_ADJ;
                        schedGroup = Process.THREAD_GROUP_DEFAULT;
                        app.hidden = false;
                        app.adjType = "provider";
                        app.adjTarget = cpr.info.name;
                    }
                }
            }
        }

        app.curRawAdj = adj;
        
        //Slog.i(TAG, "OOM ADJ " + app + ": pid=" + app.pid +
        //      " adj=" + adj + " curAdj=" + app.curAdj + " maxAdj=" + app.maxAdj);
        if (adj > app.maxAdj) {
            adj = app.maxAdj;
            if (app.maxAdj <= VISIBLE_APP_ADJ) {
                schedGroup = Process.THREAD_GROUP_DEFAULT;
            }
        }

        app.curAdj = adj;
        app.curSchedGroup = schedGroup;
        
        return adj;
    }

    /**
     * Ask a given process to GC right now.
     */
    final void performAppGcLocked(ProcessRecord app) {
        try {
            app.lastRequestedGc = SystemClock.uptimeMillis();
            if (app.thread != null) {
                if (app.reportLowMemory) {
                    app.reportLowMemory = false;
                    app.thread.scheduleLowMemory();
                } else {
                    app.thread.processInBackground();
                }
            }
        } catch (Exception e) {
            // whatever.
        }
    }
    
    /**
     * Returns true if things are idle enough to perform GCs.
     */
    private final boolean canGcNowLocked() {
        return mParallelBroadcasts.size() == 0
                && mOrderedBroadcasts.size() == 0
                && (mSleeping || (mResumedActivity != null &&
                        mResumedActivity.idle));
    }
    
    /**
     * Perform GCs on all processes that are waiting for it, but only
     * if things are idle.
     */
    final void performAppGcsLocked() {
        final int N = mProcessesToGc.size();
        if (N <= 0) {
            return;
        }
        if (canGcNowLocked()) {
            while (mProcessesToGc.size() > 0) {
                ProcessRecord proc = mProcessesToGc.remove(0);
                if (proc.curRawAdj > VISIBLE_APP_ADJ || proc.reportLowMemory) {
                    if ((proc.lastRequestedGc+GC_MIN_INTERVAL)
                            <= SystemClock.uptimeMillis()) {
                        // To avoid spamming the system, we will GC processes one
                        // at a time, waiting a few seconds between each.
                        performAppGcLocked(proc);
                        scheduleAppGcsLocked();
                        return;
                    } else {
                        // It hasn't been long enough since we last GCed this
                        // process...  put it in the list to wait for its time.
                        addProcessToGcListLocked(proc);
                        break;
                    }
                }
            }
            
            scheduleAppGcsLocked();
        }
    }
    
    /**
     * If all looks good, perform GCs on all processes waiting for them.
     */
    final void performAppGcsIfAppropriateLocked() {
        if (canGcNowLocked()) {
            performAppGcsLocked();
            return;
        }
        // Still not idle, wait some more.
        scheduleAppGcsLocked();
    }

    /**
     * Schedule the execution of all pending app GCs.
     */
    final void scheduleAppGcsLocked() {
        mHandler.removeMessages(GC_BACKGROUND_PROCESSES_MSG);
        
        if (mProcessesToGc.size() > 0) {
            // Schedule a GC for the time to the next process.
            ProcessRecord proc = mProcessesToGc.get(0);
            Message msg = mHandler.obtainMessage(GC_BACKGROUND_PROCESSES_MSG);
            
            long when = mProcessesToGc.get(0).lastRequestedGc + GC_MIN_INTERVAL;
            long now = SystemClock.uptimeMillis();
            if (when < (now+GC_TIMEOUT)) {
                when = now + GC_TIMEOUT;
            }
            mHandler.sendMessageAtTime(msg, when);
        }
    }
    
    /**
     * Add a process to the array of processes waiting to be GCed.  Keeps the
     * list in sorted order by the last GC time.  The process can't already be
     * on the list.
     */
    final void addProcessToGcListLocked(ProcessRecord proc) {
        boolean added = false;
        for (int i=mProcessesToGc.size()-1; i>=0; i--) {
            if (mProcessesToGc.get(i).lastRequestedGc <
                    proc.lastRequestedGc) {
                added = true;
                mProcessesToGc.add(i+1, proc);
                break;
            }
        }
        if (!added) {
            mProcessesToGc.add(0, proc);
        }
    }
    
    /**
     * Set up to ask a process to GC itself.  This will either do it
     * immediately, or put it on the list of processes to gc the next
     * time things are idle.
     */
    final void scheduleAppGcLocked(ProcessRecord app) {
        long now = SystemClock.uptimeMillis();
        if ((app.lastRequestedGc+GC_MIN_INTERVAL) > now) {
            return;
        }
        if (!mProcessesToGc.contains(app)) {
            addProcessToGcListLocked(app);
            scheduleAppGcsLocked();
        }
    }

    private final boolean updateOomAdjLocked(
        ProcessRecord app, int hiddenAdj, ProcessRecord TOP_APP) {
        app.hiddenAdj = hiddenAdj;

        if (app.thread == null) {
            return true;
        }

        int adj = computeOomAdjLocked(app, hiddenAdj, TOP_APP, false);

        if ((app.pid != 0 && app.pid != MY_PID) || Process.supportsProcesses()) {
            if (app.curRawAdj != app.setRawAdj) {
                if (app.curRawAdj > FOREGROUND_APP_ADJ
                        && app.setRawAdj <= FOREGROUND_APP_ADJ) {
                    // If this app is transitioning from foreground to
                    // non-foreground, have it do a gc.
                    scheduleAppGcLocked(app);
                } else if (app.curRawAdj >= HIDDEN_APP_MIN_ADJ
                        && app.setRawAdj < HIDDEN_APP_MIN_ADJ) {
                    // Likewise do a gc when an app is moving in to the
                    // background (such as a service stopping).
                    scheduleAppGcLocked(app);
                }
                app.setRawAdj = app.curRawAdj;
            }
            if (adj != app.setAdj) {
                if (Process.setOomAdj(app.pid, adj)) {
                    if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(
                        TAG, "Set app " + app.processName +
                        " oom adj to " + adj);
                    app.setAdj = adj;
                } else {
                    return false;
                }
            }
            if (app.setSchedGroup != app.curSchedGroup) {
                app.setSchedGroup = app.curSchedGroup;
                if (DEBUG_SWITCH || DEBUG_OOM_ADJ) Slog.v(TAG,
                        "Setting process group of " + app.processName
                        + " to " + app.curSchedGroup);
                if (true) {
                    long oldId = Binder.clearCallingIdentity();
                    try {
                        Process.setProcessGroup(app.pid, app.curSchedGroup);
                    } catch (Exception e) {
                        Slog.w(TAG, "Failed setting process group of " + app.pid
                                + " to " + app.curSchedGroup);
                        e.printStackTrace();
                    } finally {
                        Binder.restoreCallingIdentity(oldId);
                    }
                }
                if (false) {
                    if (app.thread != null) {
                        try {
                            app.thread.setSchedulingGroup(app.curSchedGroup);
                        } catch (RemoteException e) {
                        }
                    }
                }
            }
        }

        return true;
    }

    private final HistoryRecord resumedAppLocked() {
        HistoryRecord resumedActivity = mResumedActivity;
        if (resumedActivity == null || resumedActivity.app == null) {
            resumedActivity = mPausingActivity;
            if (resumedActivity == null || resumedActivity.app == null) {
                resumedActivity = topRunningActivityLocked(null);
            }
        }
        return resumedActivity;
    }

    private final boolean updateOomAdjLocked(ProcessRecord app) {
        final HistoryRecord TOP_ACT = resumedAppLocked();
        final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;
        int curAdj = app.curAdj;
        final boolean wasHidden = app.curAdj >= HIDDEN_APP_MIN_ADJ
            && app.curAdj <= HIDDEN_APP_MAX_ADJ;

        mAdjSeq++;

        final boolean res = updateOomAdjLocked(app, app.hiddenAdj, TOP_APP);
        if (res) {
            final boolean nowHidden = app.curAdj >= HIDDEN_APP_MIN_ADJ
                && app.curAdj <= HIDDEN_APP_MAX_ADJ;
            if (nowHidden != wasHidden) {
                // Changed to/from hidden state, so apps after it in the LRU
                // list may also be changed.
                updateOomAdjLocked();
            }
        }
        return res;
    }

    private final boolean updateOomAdjLocked() {
        boolean didOomAdj = true;
        final HistoryRecord TOP_ACT = resumedAppLocked();
        final ProcessRecord TOP_APP = TOP_ACT != null ? TOP_ACT.app : null;

        if (false) {
            RuntimeException e = new RuntimeException();
            e.fillInStackTrace();
            Slog.i(TAG, "updateOomAdj: top=" + TOP_ACT, e);
        }

        mAdjSeq++;

        // Let's determine how many processes we have running vs.
        // how many slots we have for background processes; we may want
        // to put multiple processes in a slot of there are enough of
        // them.
        int numSlots = HIDDEN_APP_MAX_ADJ - HIDDEN_APP_MIN_ADJ + 1;
        int factor = (mLruProcesses.size()-4)/numSlots;
        if (factor < 1) factor = 1;
        int step = 0;
        int numHidden = 0;
        
        // First try updating the OOM adjustment for each of the
        // application processes based on their current state.
        int i = mLruProcesses.size();
        int curHiddenAdj = HIDDEN_APP_MIN_ADJ;
        while (i > 0) {
            i--;
            ProcessRecord app = mLruProcesses.get(i);
            //Slog.i(TAG, "OOM " + app + ": cur hidden=" + curHiddenAdj);
            if (updateOomAdjLocked(app, curHiddenAdj, TOP_APP)) {
                if (curHiddenAdj < EMPTY_APP_ADJ
                    && app.curAdj == curHiddenAdj) {
                    step++;
                    if (step >= factor) {
                        step = 0;
                        curHiddenAdj++;
                    }
                }
                if (app.curAdj >= HIDDEN_APP_MIN_ADJ) {
                    if (!app.killedBackground) {
                        numHidden++;
                        if (numHidden > MAX_HIDDEN_APPS) {
                            Slog.i(TAG, "No longer want " + app.processName
                                    + " (pid " + app.pid + "): hidden #" + numHidden);
                            EventLog.writeEvent(EventLogTags.AM_KILL, app.pid,
                                    app.processName, app.setAdj, "too many background");
                            app.killedBackground = true;
                            Process.killProcessQuiet(app.pid);
                        }
                    }
                }
            } else {
                didOomAdj = false;
            }
        }

        // If we return false, we will fall back on killing processes to
        // have a fixed limit.  Do this if a limit has been requested; else
        // only return false if one of the adjustments failed.
        return ENFORCE_PROCESS_LIMIT || mProcessLimit > 0 ? false : didOomAdj;
    }

    private final void trimApplications() {
        synchronized (this) {
            int i;

            // First remove any unused application processes whose package
            // has been removed.
            for (i=mRemovedProcesses.size()-1; i>=0; i--) {
                final ProcessRecord app = mRemovedProcesses.get(i);
                if (app.activities.size() == 0
                        && app.curReceiver == null && app.services.size() == 0) {
                    Slog.i(
                        TAG, "Exiting empty application process "
                        + app.processName + " ("
                        + (app.thread != null ? app.thread.asBinder() : null)
                        + ")\n");
                    if (app.pid > 0 && app.pid != MY_PID) {
                        Process.killProcess(app.pid);
                    } else {
                        try {
                            app.thread.scheduleExit();
                        } catch (Exception e) {
                            // Ignore exceptions.
                        }
                    }
                    cleanUpApplicationRecordLocked(app, false, -1);
                    mRemovedProcesses.remove(i);
                    
                    if (app.persistent) {
                        if (app.persistent) {
                            addAppLocked(app.info);
                        }
                    }
                }
            }

            // Now try updating the OOM adjustment for each of the
            // application processes based on their current state.
            // If the setOomAdj() API is not supported, then go with our
            // back-up plan...
            if (!updateOomAdjLocked()) {

                // Count how many processes are running services.
                int numServiceProcs = 0;
                for (i=mLruProcesses.size()-1; i>=0; i--) {
                    final ProcessRecord app = mLruProcesses.get(i);

                    if (app.persistent || app.services.size() != 0
                            || app.curReceiver != null
                            || app.persistentActivities > 0) {
                        // Don't count processes holding services against our
                        // maximum process count.
                        if (localLOGV) Slog.v(
                            TAG, "Not trimming app " + app + " with services: "
                            + app.services);
                        numServiceProcs++;
                    }
                }

                int curMaxProcs = mProcessLimit;
                if (curMaxProcs <= 0) curMaxProcs = MAX_PROCESSES;
                if (mAlwaysFinishActivities) {
                    curMaxProcs = 1;
                }
                curMaxProcs += numServiceProcs;

                // Quit as many processes as we can to get down to the desired
                // process count.  First remove any processes that no longer
                // have activites running in them.
                for (   i=0;
                        i<mLruProcesses.size()
                            && mLruProcesses.size() > curMaxProcs;
                        i++) {
                    final ProcessRecord app = mLruProcesses.get(i);
                    // Quit an application only if it is not currently
                    // running any activities.
                    if (!app.persistent && app.activities.size() == 0
                            && app.curReceiver == null && app.services.size() == 0) {
                        Slog.i(
                            TAG, "Exiting empty application process "
                            + app.processName + " ("
                            + (app.thread != null ? app.thread.asBinder() : null)
                            + ")\n");
                        if (app.pid > 0 && app.pid != MY_PID) {
                            Process.killProcess(app.pid);
                        } else {
                            try {
                                app.thread.scheduleExit();
                            } catch (Exception e) {
                                // Ignore exceptions.
                            }
                        }
                        // todo: For now we assume the application is not buggy
                        // or evil, and will quit as a result of our request.
                        // Eventually we need to drive this off of the death
                        // notification, and kill the process if it takes too long.
                        cleanUpApplicationRecordLocked(app, false, i);
                        i--;
                    }
                }

                // If we still have too many processes, now from the least
                // recently used process we start finishing activities.
                if (Config.LOGV) Slog.v(
                    TAG, "*** NOW HAVE " + mLruProcesses.size() +
                    " of " + curMaxProcs + " processes");
                for (   i=0;
                        i<mLruProcesses.size()
                            && mLruProcesses.size() > curMaxProcs;
                        i++) {
                    final ProcessRecord app = mLruProcesses.get(i);
                    // Quit the application only if we have a state saved for
                    // all of its activities.
                    boolean canQuit = !app.persistent && app.curReceiver == null
                        && app.services.size() == 0
                        && app.persistentActivities == 0;
                    int NUMA = app.activities.size();
                    int j;
                    if (Config.LOGV) Slog.v(
                        TAG, "Looking to quit " + app.processName);
                    for (j=0; j<NUMA && canQuit; j++) {
                        HistoryRecord r = (HistoryRecord)app.activities.get(j);
                        if (Config.LOGV) Slog.v(
                            TAG, "  " + r.intent.getComponent().flattenToShortString()
                            + ": frozen=" + r.haveState + ", visible=" + r.visible);
                        canQuit = (r.haveState || !r.stateNotNeeded)
                                && !r.visible && r.stopped;
                    }
                    if (canQuit) {
                        // Finish all of the activities, and then the app itself.
                        for (j=0; j<NUMA; j++) {
                            HistoryRecord r = (HistoryRecord)app.activities.get(j);
                            if (!r.finishing) {
                                destroyActivityLocked(r, false);
                            }
                            r.resultTo = null;
                        }
                        Slog.i(TAG, "Exiting application process "
                              + app.processName + " ("
                              + (app.thread != null ? app.thread.asBinder() : null)
                              + ")\n");
                        if (app.pid > 0 && app.pid != MY_PID) {
                            Process.killProcess(app.pid);
                        } else {
                            try {
                                app.thread.scheduleExit();
                            } catch (Exception e) {
                                // Ignore exceptions.
                            }
                        }
                        // todo: For now we assume the application is not buggy
                        // or evil, and will quit as a result of our request.
                        // Eventually we need to drive this off of the death
                        // notification, and kill the process if it takes too long.
                        cleanUpApplicationRecordLocked(app, false, i);
                        i--;
                        //dump();
                    }
                }

            }

            int curMaxActivities = MAX_ACTIVITIES;
            if (mAlwaysFinishActivities) {
                curMaxActivities = 1;
            }

            // Finally, if there are too many activities now running, try to
            // finish as many as we can to get back down to the limit.
            for (   i=0;
                    i<mLRUActivities.size()
                        && mLRUActivities.size() > curMaxActivities;
                    i++) {
                final HistoryRecord r
                    = (HistoryRecord)mLRUActivities.get(i);

                // We can finish this one if we have its icicle saved and
                // it is not persistent.
                if ((r.haveState || !r.stateNotNeeded) && !r.visible
                        && r.stopped && !r.persistent && !r.finishing) {
                    final int origSize = mLRUActivities.size();
                    destroyActivityLocked(r, true);

                    // This will remove it from the LRU list, so keep
                    // our index at the same value.  Note that this check to
                    // see if the size changes is just paranoia -- if
                    // something unexpected happens, we don't want to end up
                    // in an infinite loop.
                    if (origSize > mLRUActivities.size()) {
                        i--;
                    }
                }
            }
        }
    }

    /** This method sends the specified signal to each of the persistent apps */
    public void signalPersistentProcesses(int sig) throws RemoteException {
        if (sig != Process.SIGNAL_USR1) {
            throw new SecurityException("Only SIGNAL_USR1 is allowed");
        }

        synchronized (this) {
            if (checkCallingPermission(android.Manifest.permission.SIGNAL_PERSISTENT_PROCESSES)
                    != PackageManager.PERMISSION_GRANTED) {
                throw new SecurityException("Requires permission "
                        + android.Manifest.permission.SIGNAL_PERSISTENT_PROCESSES);
            }

            for (int i = mLruProcesses.size() - 1 ; i >= 0 ; i--) {
                ProcessRecord r = mLruProcesses.get(i);
                if (r.thread != null && r.persistent) {
                    Process.sendSignal(r.pid, sig);
                }
            }
        }
    }

    public boolean profileControl(String process, boolean start,
            String path, ParcelFileDescriptor fd) throws RemoteException {

        try {
            synchronized (this) {
                // note: hijacking SET_ACTIVITY_WATCHER, but should be changed to
                // its own permission.
                if (checkCallingPermission(android.Manifest.permission.SET_ACTIVITY_WATCHER)
                        != PackageManager.PERMISSION_GRANTED) {
                    throw new SecurityException("Requires permission "
                            + android.Manifest.permission.SET_ACTIVITY_WATCHER);
                }
                
                if (start && fd == null) {
                    throw new IllegalArgumentException("null fd");
                }
                
                ProcessRecord proc = null;
                try {
                    int pid = Integer.parseInt(process);
                    synchronized (mPidsSelfLocked) {
                        proc = mPidsSelfLocked.get(pid);
                    }
                } catch (NumberFormatException e) {
                }
                
                if (proc == null) {
                    HashMap<String, SparseArray<ProcessRecord>> all
                            = mProcessNames.getMap();
                    SparseArray<ProcessRecord> procs = all.get(process);
                    if (procs != null && procs.size() > 0) {
                        proc = procs.valueAt(0);
                    }
                }
                
                if (proc == null || proc.thread == null) {
                    throw new IllegalArgumentException("Unknown process: " + process);
                }
                
                boolean isSecure = "1".equals(SystemProperties.get(SYSTEM_SECURE, "0"));
                if (isSecure) {
                    if ((proc.info.flags&ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
                        throw new SecurityException("Process not debuggable: " + proc);
                    }
                }
            
                proc.thread.profilerControl(start, path, fd);
                fd = null;
                return true;
            }
        } catch (RemoteException e) {
            throw new IllegalStateException("Process disappeared");
        } finally {
            if (fd != null) {
                try {
                    fd.close();
                } catch (IOException e) {
                }
            }
        }
    }
    
    /** In this method we try to acquire our lock to make sure that we have not deadlocked */
    public void monitor() {
        synchronized (this) { }
    }
}
