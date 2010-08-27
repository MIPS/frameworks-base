package android.net.ethernet;

import java.util.regex.Matcher;

import android.net.NetworkInfo;
import android.util.Config;
import android.util.Slog;
import java.util.StringTokenizer;

public class EthernetMonitor {
	private static final String TAG = "EthernetMonitor";
	private static final int CONNECTED	= 1;
	private static final int DISCONNECTED = 2;
	private static final int PHYUP = 3;
	private static final String connectedEvent =	"CONNECTED";
	private static final String disconnectedEvent = "DISCONNECTED";
	private static final int ADD_ADDR = 20;
	private static final int RM_ADDR = 21;
	private static final int NEW_LINK = 16;
	private static final int DEL_LINK = 17;

	private EthernetStateTracker mTracker;

	public EthernetMonitor(EthernetStateTracker tracker) {
		mTracker = tracker;
	}

	public void startMonitoring() {
		new MonitorThread().start();
	}

	class MonitorThread extends Thread {

		public MonitorThread() {
			super("EthMonitor");
		}

		public void run() {
			int index;
			int i;

			//noinspection InfiniteLoopStatement
			for (;;) {
				Slog.i(TAG, "go poll events");
				String eventName = EthernetNative.waitForEvent();


				 if (eventName == null) {
					continue;
				}
					Slog.i(TAG, "get event " + eventName);
				/*
				 * Map event name into event enum
				 */
				String [] events = eventName.split(":");
				index = events.length;
				if (index < 2)
					continue;
				i = 0;
				while (index != 0 && i < index-1) {

					int event = 0;
					Slog.i(TAG,"dev: " + events[i] + " ev " + events[i+1]);
					int cmd =Integer.parseInt(events[i+1]);
					if ( cmd == DEL_LINK) {
						event = DISCONNECTED;
						handleEvent(events[i],event);
					}
					else if (cmd == ADD_ADDR ) {
						event = CONNECTED;
						handleEvent(events[i],event);
					} else if (cmd == NEW_LINK) {
						event = PHYUP;
						handleEvent(events[i],event);
					}
					i = i + 2;
				}
			}
		}
		/**
		 * Handle all supplicant events except STATE-CHANGE
		 * @param event the event type
		 * @param remainder the rest of the string following the
		 * event name and &quot;&#8195;&#8212;&#8195;&quot;
		 */
		void handleEvent(String ifname,int event) {
			switch (event) {
				case DISCONNECTED:
					mTracker.notifyStateChange(ifname,NetworkInfo.DetailedState.DISCONNECTED);
					break;
				case CONNECTED:
					mTracker.notifyStateChange(ifname,NetworkInfo.DetailedState.CONNECTED);
					break;
				case PHYUP:
					mTracker.notifyPhyConnected(ifname);
					break;
				default:
					mTracker.notifyStateChange(ifname,NetworkInfo.DetailedState.FAILED);
			}
		}

	}
}
