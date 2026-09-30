package com.tactical.walkietalkie;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WifiP2pEngine: Manages Wi-Fi Direct peer-to-peer mesh connectivity.
 * Full-duplex persistent ServerSocket and Socket communication pipeline.
 * Binary packet protocol:
 * [PacketType: 1B][LangCode: 2B][PayloadLength: 4B][PayloadBytes: NB]
 */
public class WifiP2pEngine {
    private static final String TAG = "WifiP2pEngine";

    // Protocol Packet Types
    public static final byte PACKET_TYPE_AUDIO_PCM = 0x01;
    public static final byte PACKET_TYPE_TEXT_STT  = 0x02;
    public static final byte PACKET_TYPE_SOS       = 0x03;
    public static final byte PACKET_TYPE_IMAGE     = 0x04;
    public static final byte PACKET_TYPE_PING      = 0x05;

    public static final int P2P_PORT = 8888;
    public static final int SOCKET_TIMEOUT_MS = 3000;

    public interface WifiP2pEventListener {
        void onPeersListChanged(List<WifiP2pDevice> devices);
        void onConnectionEstablished(String peerAddress, boolean isGroupOwner);
        void onConnectionLost();
        void onPacketReceived(byte packetType, String langCode, byte[] payload);
        void onSocketLatencyUpdated(long latencyMs);
        void onWifiSocketTimeout(byte packetType, String langCode, byte[] payload);
        void onEngineLog(String message);
        void onEngineError(String error);
    }

    public static class PeerConnection {
        public final Socket socket;
        public final DataOutputStream dos;

        public PeerConnection(Socket socket) throws IOException {
            this.socket = socket;
            this.dos = new DataOutputStream(socket.getOutputStream());
        }

        public boolean isValid() {
            return socket != null && socket.isConnected() && !socket.isClosed();
        }

        public void close() {
            try {
                dos.close();
            } catch (Exception ignored) {}
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    private final Context context;
    private final WifiP2pEventListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();

    private WifiP2pManager wifiP2pManager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver p2pReceiver;
    private IntentFilter intentFilter;

    private ServerSocket serverSocket;
    private final List<PeerConnection> activeConnections = new CopyOnWriteArrayList<>();
    private final AtomicBoolean isServerRunning = new AtomicBoolean(false);
    private String targetPeerIp = null;
    private boolean isGroupOwner = false;
    private boolean isGroupFormed = false;

    public WifiP2pEngine(Context context, WifiP2pEventListener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        initWifiP2p();
    }

    @SuppressLint("MissingPermission")
    private void initWifiP2p() {
        wifiP2pManager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        if (wifiP2pManager != null) {
            channel = wifiP2pManager.initialize(context, Looper.getMainLooper(), () -> {
                postLog("Wi-Fi P2P Channel disconnected. Re-initializing...");
            });
        }

        intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        p2pReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                    int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                    boolean isEnabled = state == WifiP2pManager.WIFI_P2P_STATE_ENABLED;
                    postLog("Wi-Fi Direct hardware state: " + (isEnabled ? "ENABLED" : "DISABLED"));
                } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                    requestPeers();
                } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                    handleConnectionChanged(intent);
                }
            }
        };

        try {
            context.registerReceiver(p2pReceiver, intentFilter);
        } catch (Exception e) {
            postError("Receiver registration failed: " + e.getMessage());
        }

        // Always start persistent background ServerSocket listener
        startServerSocket();

        // Immediate query for existing active group connection
        checkExistingConnection();
    }

    @SuppressLint("MissingPermission")
    public void checkExistingConnection() {
        if (wifiP2pManager == null || channel == null) return;
        wifiP2pManager.requestConnectionInfo(channel, (WifiP2pInfo info) -> {
            if (info != null && info.groupFormed) {
                isGroupFormed = true;
                isGroupOwner = info.isGroupOwner;
                requestGroupMembers();
                if (isGroupOwner) {
                    postLog("Active group detected: Node is GROUP OWNER.");
                    mainHandler.post(() -> {
                        if (listener != null) listener.onConnectionEstablished("HOST (Group Owner)", true);
                    });
                } else if (info.groupOwnerAddress != null) {
                    targetPeerIp = info.groupOwnerAddress.getHostAddress();
                    postLog("Active group detected: Client linked to GO: " + targetPeerIp);
                    mainHandler.post(() -> {
                        if (listener != null) listener.onConnectionEstablished(targetPeerIp, false);
                    });
                    connectClientSocketAsync(targetPeerIp);
                }
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void discoverPeers() {
        if (wifiP2pManager == null || channel == null) {
            postError("Wi-Fi P2P Manager not initialized.");
            return;
        }

        wifiP2pManager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                postLog("Wi-Fi P2P Peer discovery initiated.");
            }

            @Override
            public void onFailure(int reasonCode) {
                postLog("Wi-Fi P2P Peer discovery initiation failed. Reason: " + reasonCode);
            }
        });
    }

    @SuppressLint("MissingPermission")
    private void requestPeers() {
        if (wifiP2pManager == null || channel == null) return;

        wifiP2pManager.requestPeers(channel, (WifiP2pDeviceList peerList) -> {
            List<WifiP2pDevice> devices = new ArrayList<>(peerList.getDeviceList());
            if (devices.isEmpty()) {
                requestGroupMembers();
            } else {
                postLog("Discovered " + devices.size() + " nearby P2P node(s).");
                mainHandler.post(() -> {
                    if (listener != null) listener.onPeersListChanged(devices);
                });
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void connectToPeer(WifiP2pDevice device) {
        if (wifiP2pManager == null || channel == null || device == null) return;

        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        postLog("Initiating P2P connection to: " + device.deviceName + " [" + device.deviceAddress + "]");

        wifiP2pManager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                postLog("P2P connection handshake started.");
            }

            @Override
            public void onFailure(int reason) {
                postError("P2P connection failed. Reason code: " + reason);
            }
        });
    }

    @SuppressLint("MissingPermission")
    public void requestGroupMembers() {
        if (wifiP2pManager == null || channel == null) return;
        wifiP2pManager.requestGroupInfo(channel, (android.net.wifi.p2p.WifiP2pGroup group) -> {
            if (group != null) {
                List<WifiP2pDevice> list = new ArrayList<>();
                if (group.getClientList() != null && !group.getClientList().isEmpty()) {
                    list.addAll(group.getClientList());
                }
                if (group.getOwner() != null && !isGroupOwner) {
                    list.add(group.getOwner());
                }
                postLog("Group info updated. Active mesh members: " + list.size());
                mainHandler.post(() -> {
                    if (listener != null && !list.isEmpty()) listener.onPeersListChanged(list);
                });
            }
        });
    }

    private void handleConnectionChanged(Intent intent) {
        NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
        if (networkInfo != null && networkInfo.isConnected()) {
            wifiP2pManager.requestConnectionInfo(channel, (WifiP2pInfo info) -> {
                InetAddress groupOwnerAddr = info.groupOwnerAddress;
                isGroupOwner = info.isGroupOwner;

                if (info.groupFormed) {
                    isGroupFormed = true;
                    requestGroupMembers();
                    if (isGroupOwner) {
                        postLog("This node is GROUP OWNER. Listening on port " + P2P_PORT);
                        mainHandler.post(() -> {
                            if (listener != null) listener.onConnectionEstablished("HOST (Group Owner)", true);
                        });
                    } else if (groupOwnerAddr != null) {
                        targetPeerIp = groupOwnerAddr.getHostAddress();
                        postLog("Connected as Client to Group Owner IP: " + targetPeerIp);
                        mainHandler.post(() -> {
                            if (listener != null) listener.onConnectionEstablished(targetPeerIp, false);
                        });
                        connectClientSocketAsync(targetPeerIp);
                    }
                }
            });
        } else {
            isGroupFormed = false;
            postLog("P2P Network connection terminated or lost.");
            mainHandler.post(() -> {
                if (listener != null) listener.onConnectionLost();
            });
        }
    }

    /**
     * Spawns a persistent background worker with a ServerSocket
     */
    private void startServerSocket() {
        if (isServerRunning.get()) return;

        networkExecutor.execute(() -> {
            try {
                serverSocket = new ServerSocket(P2P_PORT);
                isServerRunning.set(true);
                postLog("Persistent P2P ServerSocket active on port " + P2P_PORT);

                // Start background auto-reconnect heartbeat
                mainHandler.post(this::scheduleKeepAlive);

                while (isServerRunning.get()) {
                    Socket incomingSocket = serverSocket.accept();
                    String remoteIp = incomingSocket.getInetAddress().getHostAddress();
                    postLog("Accepted P2P stream connection from: " + remoteIp);
                    targetPeerIp = remoteIp;

                    try {
                        PeerConnection conn = new PeerConnection(incomingSocket);
                        activeConnections.add(conn);
                        networkExecutor.execute(() -> handleIncomingStream(conn));
                    } catch (Exception e) {
                        postError("Error creating peer connection for accepted socket: " + e.getMessage());
                    }
                }
            } catch (IOException ex) {
                if (isServerRunning.get()) {
                    postError("ServerSocket exception: " + ex.getMessage());
                }
            } finally {
                isServerRunning.set(false);
            }
        });
    }

    private void scheduleKeepAlive() {
        mainHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isServerRunning.get()) {
                    if (!isGroupOwner && activeConnections.isEmpty()) {
                        String fallback = (targetPeerIp != null) ? targetPeerIp : "192.168.49.1";
                        connectClientSocketAsync(fallback);
                    }
                    mainHandler.postDelayed(this, 2500);
                }
            }
        }, 2500);
    }

    public boolean isConnected() {
        return isGroupFormed || isGroupOwner || !activeConnections.isEmpty() || targetPeerIp != null;
    }

    private void handleIncomingStream(PeerConnection conn) {
        try (DataInputStream dis = new DataInputStream(conn.socket.getInputStream())) {
            byte[] langBuf = new byte[2];

            while (isServerRunning.get() && conn.isValid()) {
                // Packet Structure: [PacketType: 1B][LangCode: 2B][PayloadLength: 4B][PayloadBytes: NB]
                byte packetType = dis.readByte();
                dis.readFully(langBuf);
                String langCode = new String(langBuf, StandardCharsets.US_ASCII);

                int payloadLength = dis.readInt();
                if (payloadLength < 0 || payloadLength > 10 * 1024 * 1024) {
                    throw new IOException("Illegal packet payload size: " + payloadLength);
                }

                byte[] payload = new byte[payloadLength];
                dis.readFully(payload);

                if (packetType == PACKET_TYPE_PING) {
                    continue; // Keep-alive ping acknowledged
                }

                mainHandler.post(() -> {
                    if (listener != null) listener.onPacketReceived(packetType, langCode, payload);
                });
            }
        } catch (IOException e) {
            postLog("P2P Peer session closed: " + e.getMessage());
        } finally {
            conn.close();
            activeConnections.remove(conn);
            if (!isGroupOwner && isServerRunning.get()) {
                String target = (targetPeerIp != null) ? targetPeerIp : "192.168.49.1";
                mainHandler.postDelayed(() -> connectClientSocketAsync(target), 1000);
            }
        }
    }

    private synchronized void connectClientSocketAsync(String hostIp) {
        if (hostIp == null) return;
        networkExecutor.execute(() -> {
            try {
                for (PeerConnection conn : activeConnections) {
                    if (conn.isValid()) {
                        return; // Socket pipe is already healthy
                    }
                }
                postLog("Opening outgoing socket pipe to: " + hostIp + ":" + P2P_PORT);
                Socket socket = new Socket();
                socket.connect(new InetSocketAddress(hostIp, P2P_PORT), SOCKET_TIMEOUT_MS);
                
                PeerConnection conn = new PeerConnection(socket);
                activeConnections.add(conn);
                postLog("Outgoing socket pipe connected successfully.");

                // Start reader thread for bidirectional traffic!
                networkExecutor.execute(() -> handleIncomingStream(conn));
            } catch (IOException ex) {
                postLog("Client socket connection pending to " + hostIp + ": " + ex.getMessage());
            }
        });
    }

    /**
     * Writes framed packet directly to all active output socket pipes.
     */
    public synchronized void sendPacket(final byte packetType, final String langCode, final byte[] payload) {
        if (payload == null) return;

        networkExecutor.execute(() -> {
            long sendStart = System.currentTimeMillis();
            boolean transmissionSuccess = false;

            byte[] rawLang = (langCode != null && langCode.length() >= 2 ? langCode.substring(0, 2) : "en").getBytes(StandardCharsets.US_ASCII);
            byte[] langBytes = new byte[2];
            langBytes[0] = (rawLang.length > 0) ? rawLang[0] : (byte)'e';
            langBytes[1] = (rawLang.length > 1) ? rawLang[1] : (byte)'n';

            // Clean invalid connections
            for (PeerConnection conn : activeConnections) {
                if (!conn.isValid()) {
                    conn.close();
                    activeConnections.remove(conn);
                }
            }

            if (activeConnections.isEmpty()) {
                String fallbackIp = (targetPeerIp != null) ? targetPeerIp : (!isGroupOwner ? "192.168.49.1" : null);
                if (fallbackIp != null) {
                    try {
                        Socket newSocket = new Socket();
                        newSocket.connect(new InetSocketAddress(fallbackIp, P2P_PORT), SOCKET_TIMEOUT_MS);
                        PeerConnection newConn = new PeerConnection(newSocket);
                        activeConnections.add(newConn);
                        networkExecutor.execute(() -> handleIncomingStream(newConn));
                    } catch (Exception ignored) {}
                }
            }

            for (PeerConnection conn : activeConnections) {
                try {
                    synchronized (conn.dos) {
                        conn.dos.writeByte(packetType);
                        conn.dos.write(langBytes);
                        conn.dos.writeInt(payload.length);
                        conn.dos.write(payload);
                        conn.dos.flush();
                    }
                    transmissionSuccess = true;
                } catch (Exception ex) {
                    Log.w(TAG, "Failed sending to peer connection, removing: " + ex.getMessage());
                    conn.close();
                    activeConnections.remove(conn);
                }
            }

            if (transmissionSuccess) {
                long latencyMs = System.currentTimeMillis() - sendStart;
                mainHandler.post(() -> {
                    if (listener != null) listener.onSocketLatencyUpdated(latencyMs);
                });
            } else {
                if (packetType == PACKET_TYPE_SOS) {
                    mainHandler.post(() -> {
                        if (listener != null) listener.onWifiSocketTimeout(packetType, langCode, payload);
                    });
                }
            }
        });
    }

    private void postLog(String msg) {
        Log.d(TAG, msg);
        mainHandler.post(() -> {
            if (listener != null) listener.onEngineLog(msg);
        });
    }

    private void postError(String err) {
        Log.e(TAG, err);
        mainHandler.post(() -> {
            if (listener != null) listener.onEngineError(err);
        });
    }

    public synchronized void shutdown() {
        isServerRunning.set(false);
        try {
            if (p2pReceiver != null) context.unregisterReceiver(p2pReceiver);
        } catch (Exception ignored) {}

        for (PeerConnection conn : activeConnections) {
            conn.close();
        }
        activeConnections.clear();

        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }

        networkExecutor.shutdownNow();
        postLog("Wi-Fi P2P engine terminated cleanly.");
    }
}
