package com.tadasr.IOTWifi;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;

import java.util.List;


class FailureCodes {
    static int SYSTEM_ADDED_CONFIG_EXISTS = 1;
    static int FAILED_TO_CONNECT = 2;
    static int FAILED_TO_ADD_CONFIG = 3;
    static int FAILED_TO_BIND_CONFIG = 4;
}

class IOTWifiCallback {
    private Callback callback;

    public IOTWifiCallback(Callback callback) {
        this.callback = callback;
    }

    public void invoke(Object... args) {
        if (callback == null) return;
        callback.invoke(args);
        callback = null;
    }
}

public class IOTWifiModule extends ReactContextBaseJavaModule {
    private static final String TAG = "IOTWifiModule";
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private ReactApplicationContext context;

    public IOTWifiModule(ReactApplicationContext reactContext) {
        super(reactContext);
        wifiManager = (WifiManager) getReactApplicationContext().getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getReactApplicationContext().getApplicationContext()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        context = getReactApplicationContext();
    }

    private String errorFromCode(int errorCode) {
        return "ErrorCode: " + errorCode;
    }

    @Override
    public String getName() {
        return "IOTWifi";
    }

    @ReactMethod
    public void isApiAvailable(final Callback callback) {
        callback.invoke(true);
    }

    @ReactMethod
    public void connect(String ssid, Boolean bindNetwork, Callback callback) {
        connectSecure(ssid, "", false, bindNetwork, callback);
    }

    @ReactMethod
    public void connectSecure(final String ssid, final String passphrase, final Boolean isWEP,
                              final Boolean bindNetwork, final Callback callback) {
        new Thread(new Runnable() {
            public void run() {
                connectToWifi(ssid, passphrase, isWEP, bindNetwork, callback);
            }
        }).start();
    }

    private void connectToWifi(String ssid, String passphrase, Boolean isWEP, Boolean bindNetwork, final Callback callback) {
        IOTWifiCallback iotWifiCallback = new IOTWifiCallback(callback);
//        Log.d(TAG, "connectToWifi: begin");
        if (!removeSSID(ssid)) {
            iotWifiCallback.invoke(errorFromCode(FailureCodes.SYSTEM_ADDED_CONFIG_EXISTS));
            return;
        }

        ScanResult scanResult = this.find(ssid);
        String capabilities = isWEP ? "WEP" : "WPA";
        if (scanResult != null && !TextUtils.isEmpty(scanResult.capabilities)) {
            capabilities = scanResult.capabilities;
        }
//        Log.d(TAG, "connectToWifi: begin01");

        WifiConfiguration configuration = createWifiConfiguration(ssid, passphrase, capabilities);

        List<WifiConfiguration> mWifiConfigList = wifiManager.getConfiguredNetworks();

        int networkId = -1;

        // Use the existing network config if exists
        for (WifiConfiguration wifiConfig : mWifiConfigList) {
            if (wifiConfig.SSID.equals(configuration.SSID)) {
                configuration = wifiConfig;
                networkId = configuration.networkId;
            }
        }

        // If network not already in configured networks add new network
        if (networkId == -1) {
            networkId = wifiManager.addNetwork(configuration);

        }

        Log.d(TAG, "connectToWifi: begin02");
        if (networkId != -1) {
            Log.d(TAG, "connectToWifi: begin03");
            // Enable it so that android can connect
            wifiManager.disconnect();
            boolean success = wifiManager.enableNetwork(networkId, true);
            if (!success) {
                iotWifiCallback.invoke(errorFromCode(FailureCodes.FAILED_TO_ADD_CONFIG));
                return;
            }
            Log.d(TAG, "connectToWifi: begin04");
            success = wifiManager.reconnect();
            if (!success) {
                iotWifiCallback.invoke(errorFromCode(FailureCodes.FAILED_TO_CONNECT));
                return;
            }
            Log.d(TAG, "connectToWifi: begin05");
            boolean connected = pollForValidSSSID(10, ssid);
            if (!connected) {
                iotWifiCallback.invoke(errorFromCode(FailureCodes.FAILED_TO_CONNECT));
                return;
            }
            Log.d(TAG, "connectToWifi: begin06");
            if (!bindNetwork) {
                iotWifiCallback.invoke();
                return;
            }
            try {
                Log.d(TAG, "connectToWifi: begin07");
                bindToNetwork(ssid, iotWifiCallback);
            } catch (Exception e) {
                Log.d(TAG, "connectToWifi Failed Wifi: " + ssid);
                iotWifiCallback.invoke();
            }
        } else {
            iotWifiCallback.invoke(errorFromCode(FailureCodes.FAILED_TO_ADD_CONFIG));
        }
    }

    private WifiConfiguration createWifiConfiguration(String ssid, String passphrase, String capabilities) {
        WifiConfiguration configuration = new WifiConfiguration();
        configuration.allowedAuthAlgorithms.clear();
        configuration.allowedGroupCiphers.clear();
        configuration.allowedKeyManagement.clear();
        configuration.allowedPairwiseCiphers.clear();
        configuration.allowedProtocols.clear();

        configuration.SSID = String.format("\"%s\"", ssid);
        if (!TextUtils.isEmpty(capabilities)) {
            Boolean isWEP = capabilities.contains("WEP");
            if (passphrase.equals("")) {
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
            } else if (isWEP) {

                configuration.wepKeys[0] = "\"" + passphrase + "\"";
                configuration.wepTxKeyIndex = 0;
                configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
                configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.SHARED);
                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
                configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.WEP40);
            } else if (capabilities.contains("WPA") || capabilities.contains("WPA2") || capabilities.contains("WPA/WPA2 PSK")) { // WPA/WPA2
                Log.d(TAG, "createWifiConfiguration: WPA");
                configuration.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);

                configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
                configuration.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);

                configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);

                configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
                configuration.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);

                configuration.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
                configuration.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
                configuration.status = WifiConfiguration.Status.ENABLED;
                configuration.preSharedKey = String.format("\"%s\"", passphrase);
            }
        } else {
            configuration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);
        }


        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
        return configuration;
    }

    private boolean pollForValidSSSID(int maxSeconds, String expectedSSID) {
        try {
            for (int i = 0; i < maxSeconds; i++) {
                String ssid = this.getWifiSSID();
                if (ssid != null && ssid.equalsIgnoreCase(expectedSSID)) {
                    return true;
                }
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            return false;
        }
        return false;
    }

    private ScanResult find(String ssid) {
        List<ScanResult> results = wifiManager.getScanResults();
        boolean connected = false;
        for (ScanResult result : results) {
            String resultString = "" + result.SSID;
            if (!TextUtils.isEmpty(ssid) && ssid.equals(resultString)) {
                return result;
            }
        }
        return null;
    }

    private void bindToNetwork(final String ssid, final IOTWifiCallback callback) {
        NetworkRequest.Builder builder = new NetworkRequest.Builder();
        builder.addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        connectivityManager.requestNetwork(builder.build(), new ConnectivityManager.NetworkCallback() {

            private boolean bound = false;

            @Override
            public void onAvailable(Network network) {
                String offeredSSID = getWifiSSID();

                if (!bound && offeredSSID.equals(ssid)) {
                    try {
                        bindProcessToNetwork(network);
                        bound = true;
                        callback.invoke();
                        return;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
                callback.invoke(errorFromCode(FailureCodes.FAILED_TO_BIND_CONFIG));
            }

            @Override
            public void onLost(Network network) {
                if (bound) {
                    bindProcessToNetwork(null);
                    connectivityManager.unregisterNetworkCallback(this);
                }
            }
        });
    }

    private void bindProcessToNetwork(final Network network) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            connectivityManager.bindProcessToNetwork(network);
        } else {
            ConnectivityManager.setProcessDefaultNetwork(network);
        }
    }

    @ReactMethod
    public void removeSSID(String ssid, Boolean unbind, Callback callback) {
        if (!removeSSID(ssid)) {
            callback.invoke(errorFromCode(FailureCodes.SYSTEM_ADDED_CONFIG_EXISTS));
            return;
        }
        if (unbind) {
            bindProcessToNetwork(null);
        }

        callback.invoke();
    }


    private boolean removeSSID(String ssid) {
        boolean success = true;
        // Remove the existing configuration for this network
        WifiConfiguration existingNetworkConfigForSSID = getExistingNetworkConfig(ssid);

        Log.d(TAG, "removeSSID: begin");
        //No Config found
        if (existingNetworkConfigForSSID == null) {
            return success;
        }
        int existingNetworkId = existingNetworkConfigForSSID.networkId;
        if (existingNetworkId == -1) {
            return success;
        }
        Log.d(TAG, "removeSSID: begin01");
        boolean isDisableSuccess = wifiManager.disableNetwork(existingNetworkId);
        Log.d(TAG, "removeSSID: begin02 " + isDisableSuccess);
        boolean isRemoveSuccess = wifiManager.removeNetwork(existingNetworkId);
        success = isDisableSuccess || isRemoveSuccess;
        Log.d(TAG, "removeSSID: begin03 " + isRemoveSuccess);
        if (isRemoveSuccess && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            wifiManager.saveConfiguration();
        }
        Log.d(TAG, "removeSSID: end " + success);
        //If not our config then success would be false
        return success;
    }

    @ReactMethod
    public void getSSID(Callback callback) {
        String ssid = this.getWifiSSID();
        callback.invoke(ssid);
    }

    private String getWifiSSID() {
        WifiInfo info = wifiManager.getConnectionInfo();
        String ssid = info.getSSID();

        if (ssid == null || ssid.equalsIgnoreCase("<unknown ssid>")) {
            NetworkInfo nInfo = connectivityManager.getActiveNetworkInfo();
            if (nInfo != null && nInfo.isConnected()) {
                ssid = nInfo.getExtraInfo();
            }
        }

        if (ssid != null && ssid.startsWith("\"") && ssid.endsWith("\"")) {
            ssid = ssid.substring(1, ssid.length() - 1);
        }

        return ssid;
    }

    private WifiConfiguration getExistingNetworkConfig(String ssid) {
        WifiConfiguration existingNetworkConfigForSSID = null;
        List<WifiConfiguration> configList = wifiManager.getConfiguredNetworks();
//        String comparableSSID = ('"' + ssid + '"'); // Add quotes because wifiConfig.SSID has them
        String comparableSSID = "\"" + ssid + "\"";
        if (configList != null) {
            for (WifiConfiguration wifiConfig : configList) {
                String savedSSID = wifiConfig.SSID;
                if (savedSSID == null)
                    continue; // In few cases SSID is found to be null, ignore those configs
                if (savedSSID.equals(comparableSSID)) {
                    Log.d("IoTWifi", "Found Matching Wifi: " + wifiConfig.toString());
                    existingNetworkConfigForSSID = wifiConfig;
                    break;

                }
            }
        }
        return existingNetworkConfigForSSID;
    }
}
