package android.net.ethernet;

public class EthernetNative {
	public native static String getInterfaceName(int i);
	public native static int getInterfaceCnt();
	public native static int initEthernetNative();
	public native static String waitForEvent();
}
