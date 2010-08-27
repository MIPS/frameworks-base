package android.net.ethernet;
import android.net.ethernet.EthernetDevInfo;

interface IEthernetManager
{
	String[] getDeviceNameList();
	void setEthState(int state);
	int getEthState( );
	void UpdateEthDevInfo(in EthernetDevInfo info);
	boolean isEthConfigured();
	EthernetDevInfo getSavedEthConfig();
	int getTotalInterface();
	void setEthMode(String mode);
}
