package com.tactical.walkietalkie;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BleFallback: Failsafe ultra-low-latency BLE Advertising & Scanning channel.
 * Dedicated strictly to high-priority emergency broadcasts (FIRE, FLOOD, MEDIC, COLLAPSE).
 * Includes self-transmission filtering and de-duplication to prevent loop storms.
 */
public class BleFallback {
    private static final String TAG = "BleFallback";
    private static final int MANUFACTURER_ID = 0x5148; // "SIH" Tactical Identifier
    private static final byte MAGIC_BYTE = (byte) 0xFA;

    public static final byte CODE_FIRE     = 0x01;
    public static final byte CODE_FLOOD    = 0x02;
    public static final byte CODE_MEDIC    = 0x03;
    public static final byte CODE_COLLAPSE = 0x04;
    public static final byte CODE_ACK      = 0x05;

    public interface BleFallbackListener {
        void onBlePriorityCodeReceived(byte priorityCode, String eventTitle, int channelId, String senderNodeId);
        void onBleAdvertisingStarted();
        void onBleLog(String message);
        void onBleError(String error);
    }

    private final Context context;
    private final BleFallbackListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Map<String, Long> seenPacketsCache = new ConcurrentHashMap<>();

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothLeAdvertiser leAdvertiser;
    private BluetoothLeScanner leScanner;
    private AdvertiseCallback advertiseCallback;
    private ScanCallback scanCallback;
    private boolean isAdvertising = false;
    private boolean isScanning = false;
    private int localNodeHash = 0;

    public BleFallback(Context context, BleFallbackListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initBluetooth();
    }

    public void setLocalNodeId(String nodeId) {
        if (nodeId != null) {
            this.localNodeHash = nodeId.hashCode();
        }
    }

    private void initBluetooth() {
        try {
            BluetoothManager bm = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
            if (bm != null) {
                bluetoothAdapter = bm.getAdapter();
                if (bluetoothAdapter != null && bluetoothAdapter.isEnabled()) {
                    leAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
                    leScanner = bluetoothAdapter.getBluetoothLeScanner();
                    postLog("BLE Fallback Hardware Initialized.");
                } else {
                    postLog("Bluetooth adapter is disabled or unavailable.");
                }
            }
        } catch (Exception e) {
            postError("Bluetooth init exception: " + e.getMessage());
        }
    }

    /**
     * Broadcasts structural priority emergency packets over raw BLE Advertising frames
     */
    @SuppressLint("MissingPermission")
    public synchronized void broadcastPriorityCode(final byte priorityCode, final int channelId, final String senderId) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            postLog("BLE disabled, skipping broadcast.");
            return;
        }

        if (senderId != null) {
            this.localNodeHash = senderId.hashCode();
        }

        if (leAdvertiser == null) {
            leAdvertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
            if (leAdvertiser == null) {
                postLog("BLE Peripheral Advertising not supported on this chipset.");
                return;
            }
        }

        // Build compact 11-byte tactical packet: [Magic: 1B][Code: 1B][Channel: 1B][NodeHash: 4B][Timestamp: 4B]
        byte[] payload = new byte[11];
        payload[0] = MAGIC_BYTE;
        payload[1] = priorityCode;
        payload[2] = (byte) (channelId & 0xFF);

        int nodeHash = localNodeHash;
        payload[3] = (byte) ((nodeHash >> 24) & 0xFF);
        payload[4] = (byte) ((nodeHash >> 16) & 0xFF);
        payload[5] = (byte) ((nodeHash >> 8) & 0xFF);
        payload[6] = (byte) (nodeHash & 0xFF);

        int timeEpochSec = (int) (System.currentTimeMillis() / 1000L);
        payload[7] = (byte) ((timeEpochSec >> 24) & 0xFF);
        payload[8] = (byte) ((timeEpochSec >> 16) & 0xFF);
        payload[9] = (byte) ((timeEpochSec >> 8) & 0xFF);
        payload[10] = (byte) (timeEpochSec & 0xFF);

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(6000) // 6 seconds emergency burst
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(false)
                .setIncludeTxPowerLevel(false)
                .addManufacturerData(MANUFACTURER_ID, payload)
                .build();

        if (advertiseCallback != null && isAdvertising) {
            try {
                leAdvertiser.stopAdvertising(advertiseCallback);
            } catch (Exception ignored) {}
        }

        advertiseCallback = new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                isAdvertising = true;
                postLog("BLE Emergency beacon active: " + getCodeLabel(priorityCode));
                mainHandler.post(() -> {
                    if (listener != null) listener.onBleAdvertisingStarted();
                });
            }

            @Override
            public void onStartFailure(int errorCode) {
                isAdvertising = false;
                postLog("BLE Advertising completed or stopped (code: " + errorCode + ")");
            }
        };

        try {
            leAdvertiser.startAdvertising(settings, data, advertiseCallback);
        } catch (Exception ex) {
            postLog("BLE broadcast notice: " + ex.getMessage());
        }
    }

    /**
     * Continuously listens for peer structural emergency codes via Low-Latency BLE Scanner
     */
    @SuppressLint("MissingPermission")
    public synchronized void startScanning() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (isScanning) return;

        if (leScanner == null) {
            leScanner = bluetoothAdapter.getBluetoothLeScanner();
            if (leScanner == null) return;
        }

        ScanSettings scanSettings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0)
                .build();

        ScanFilter filter = new ScanFilter.Builder()
                .setManufacturerData(MANUFACTURER_ID, new byte[]{MAGIC_BYTE}, new byte[]{(byte) 0xFF})
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                ScanRecord record = result.getScanRecord();
                if (record == null) return;

                byte[] mData = record.getManufacturerSpecificData(MANUFACTURER_ID);
                if (mData != null && mData.length >= 7 && mData[0] == MAGIC_BYTE) {
                    byte code = mData[1];
                    int ch = mData[2] & 0xFF;
                    int senderHash = ((mData[3] & 0xFF) << 24) | ((mData[4] & 0xFF) << 16) | ((mData[5] & 0xFF) << 8) | (mData[6] & 0xFF);

                    // 1. Filter out self broadcasts
                    if (localNodeHash != 0 && senderHash == localNodeHash) {
                        return;
                    }

                    // 2. De-duplicate: ensure we only trigger once per 6 seconds per sender
                    String dedupeKey = code + "_" + senderHash;
                    long now = System.currentTimeMillis();
                    Long lastSeen = seenPacketsCache.get(dedupeKey);
                    if (lastSeen != null && (now - lastSeen) < 6000) {
                        return; // Ignore repetitive advertising packet
                    }
                    seenPacketsCache.put(dedupeKey, now);

                    String senderTag = "NODE_" + Integer.toHexString(senderHash).toUpperCase();

                    mainHandler.post(() -> {
                        if (listener != null) {
                            listener.onBlePriorityCodeReceived(code, getCodeLabel(code), ch, senderTag);
                        }
                    });
                }
            }

            @Override
            public void onScanFailed(int errorCode) {
                postLog("BLE Scanner status code: " + errorCode);
            }
        };

        try {
            leScanner.startScan(Collections.singletonList(filter), scanSettings, scanCallback);
            isScanning = true;
            postLog("BLE Emergency Scanner armed.");
        } catch (Exception e) {
            postLog("BLE scan standby: " + e.getMessage());
        }
    }

    @SuppressLint("MissingPermission")
    public synchronized void stopScanning() {
        if (isScanning && leScanner != null && scanCallback != null) {
            try {
                leScanner.stopScan(scanCallback);
            } catch (Exception ignored) {}
            isScanning = false;
        }
    }

    public static String getCodeLabel(byte code) {
        switch (code) {
            case CODE_FIRE: return "FIRE";
            case CODE_FLOOD: return "FLOOD";
            case CODE_MEDIC: return "MEDIC";
            case CODE_COLLAPSE: return "COLLAPSE";
            case CODE_ACK: return "ACK";
            default: return "EMERGENCY";
        }
    }

    private void postLog(String msg) {
        Log.i(TAG, msg);
        mainHandler.post(() -> {
            if (listener != null) listener.onBleLog(msg);
        });
    }

    private void postError(String err) {
        Log.w(TAG, err);
        mainHandler.post(() -> {
            if (listener != null) listener.onBleError(err);
        });
    }

    @SuppressLint("MissingPermission")
    public synchronized void shutdown() {
        stopScanning();
        if (isAdvertising && leAdvertiser != null && advertiseCallback != null) {
            try {
                leAdvertiser.stopAdvertising(advertiseCallback);
            } catch (Exception ignored) {}
            isAdvertising = false;
        }
        seenPacketsCache.clear();
        postLog("BLE Fallback terminated cleanly.");
    }
}
