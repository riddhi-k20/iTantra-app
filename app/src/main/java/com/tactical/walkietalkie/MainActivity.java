package com.tactical.walkietalkie;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.wifi.p2p.WifiP2pDevice;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 1001;

    // UI View References
    private ConstraintLayout rootLayout;
    private LinearLayout topBarLayout;
    private TextView tvAppTitle;
    private Spinner spinnerTheme;
    private ImageButton btnEmergencyContacts;
    private LinearLayout configBarLayout;
    private Spinner spinnerLanguage;
    private Spinner spinnerChannel;
    private ImageButton btnCameraSnap;
    private MeshNodeMapView meshNodeMapView;
    private RecyclerView rvP2pDevices;
    private Button btnSosFire;
    private Button btnSosFlood;
    private Button btnSosMedical;
    private Button btnSosCollapse;
    private WaveformCanvasView waveformCanvasView;
    private View viewPttRingPulse;
    private Button btnPushToTalk;
    private CardView consoleCardView;
    private TextView tvMetricRam;
    private TextView tvMetricLatency;
    private TextView tvMetricRtf;
    private ScrollView consoleScrollView;
    private TextView tvConsoleLogs;

    // Pipelines and Engines
    private final java.util.concurrent.ExecutorService audioPlaybackExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
    private AudioCapturePipeline audioPipeline;
    private OfflineVoiceEngine voiceEngine;
    private WifiP2pEngine wifiP2pEngine;
    private BleFallback bleFallback;

    // State Variables
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final List<WifiP2pDevice> discoveredDevices = new ArrayList<>();
    private DeviceAdapter deviceAdapter;
    private ObjectAnimator pttPulseAnimator;
    private Vibrator vibrator;
    private SharedPreferences sharedPrefs;
    private ActivityResultLauncher<Void> cameraLauncher;

    private String activeChannel = "CH 01 - Alpha (433.1 MHz)";
    private int activeChannelId = 1;
    private String selectedLanguageCode = "hi";
    private final String localNodeId = (Build.MANUFACTURER.toUpperCase() + "_" + Build.MODEL.replace(" ", "_")).replaceAll("[^A-Za-z0-9_]", "");

    // 10 Indian Languages Supported
    private static final String[] INDIAN_LANGUAGES = {
            "Hindi (hi)",
            "Bengali (bn)",
            "Tamil (ta)",
            "Telugu (te)",
            "Marathi (mr)",
            "Gujarati (gu)",
            "Kannada (kn)",
            "Malayalam (ml)",
            "Punjabi (pa)",
            "Odia (or)",
            "English (en)"
    };

    private static final String[] LANGUAGE_CODES = {
            "hi", "bn", "ta", "te", "mr", "gu", "kn", "ml", "pa", "or", "en"
    };

    private static final String[] CHANNELS = {
            "CH 01 - Alpha (433.1 MHz)",
            "CH 02 - Bravo (433.5 MHz)",
            "CH 03 - Charlie (434.0 MHz)",
            "CH 04 - Delta (434.5 MHz)",
            "CH 05 - Tactical Emergency (435.0 MHz)"
    };

    private static final String[] THEMES = {
            "CYAN TACTICAL",
            "NIGHT OPS OLED",
            "STEALTH AMBER",
            "COMBAT RED"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sharedPrefs = getSharedPreferences("tactical_walkie_talkie_prefs", Context.MODE_PRIVATE);
        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        initViews();
        setupThemes();
        setupLanguagesAndChannels();
        setupP2pRecyclerView();
        setupCameraLauncher();
        checkAndRequestPermissions();

        initPipelines();
        setupPttListener();
        setupSosButtons();
        startMetricPolling();

        appendConsole("INIT", "Local Tactical Node: " + localNodeId + " online.");
        appendConsole("INIT", "Audio Pipeline: 16000Hz 16-bit Mono. VAD 300ms armed.");
    }

    private void initViews() {
        rootLayout = findViewById(R.id.rootLayout);
        topBarLayout = findViewById(R.id.topBarLayout);
        tvAppTitle = findViewById(R.id.tvAppTitle);
        spinnerTheme = findViewById(R.id.spinnerTheme);
        btnEmergencyContacts = findViewById(R.id.btnEmergencyContacts);
        configBarLayout = findViewById(R.id.configBarLayout);
        spinnerLanguage = findViewById(R.id.spinnerLanguage);
        spinnerChannel = findViewById(R.id.spinnerChannel);
        btnCameraSnap = findViewById(R.id.btnCameraSnap);
        meshNodeMapView = findViewById(R.id.meshNodeMapView);
        rvP2pDevices = findViewById(R.id.rvP2pDevices);
        btnSosFire = findViewById(R.id.btnSosFire);
        btnSosFlood = findViewById(R.id.btnSosFlood);
        btnSosMedical = findViewById(R.id.btnSosMedical);
        btnSosCollapse = findViewById(R.id.btnSosCollapse);
        waveformCanvasView = findViewById(R.id.waveformCanvasView);
        viewPttRingPulse = findViewById(R.id.viewPttRingPulse);
        btnPushToTalk = findViewById(R.id.btnPushToTalk);
        consoleCardView = findViewById(R.id.consoleCardView);
        tvMetricRam = findViewById(R.id.tvMetricRam);
        tvMetricLatency = findViewById(R.id.tvMetricLatency);
        tvMetricRtf = findViewById(R.id.tvMetricRtf);
        consoleScrollView = findViewById(R.id.consoleScrollView);
        tvConsoleLogs = findViewById(R.id.tvConsoleLogs);

        btnEmergencyContacts.setOnClickListener(v -> showEmergencyContactsDialog());
        btnCameraSnap.setOnClickListener(v -> launchCamera());
        tvAppTitle.setOnClickListener(v -> toggleBetaSimulatorMode());
    }

    private boolean isBetaSimActive = false;

    private void toggleBetaSimulatorMode() {
        isBetaSimActive = !isBetaSimActive;
        triggerHapticFeedback(120);
        playMicKeySound();

        if (isBetaSimActive) {
            appendConsole("BETA_SIM", "⚡ Tactical Mesh Beta Simulation: ONLINE");
            appendConsole("BETA_SIM", "Virtual relays mapped: 4 Active Tactical/Disaster Nodes.");
            List<String> mockNodes = new ArrayList<>();
            mockNodes.add("TAC_ALPHA_91");
            mockNodes.add("TAC_BRAVO_44");
            mockNodes.add("TAC_RESCUE_07");
            mockNodes.add("S23_TACTICAL");
            meshNodeMapView.setDiscoveredNodes(mockNodes);
            tvMetricLatency.setText("SOCK: 7 ms");
            tvMetricRtf.setText("RTF: 0.09");
            Toast.makeText(this, "⚡ Beta Mode: 4 Tactical Nodes Active", Toast.LENGTH_SHORT).show();
        } else {
            appendConsole("BETA_SIM", "Beta Simulation: Restoring direct hardware mesh scanning...");
            if (wifiP2pEngine != null) {
                wifiP2pEngine.discoverPeers();
            }
            Toast.makeText(this, "Direct Hardware Mesh Restored", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupThemes() {
        ArrayAdapter<String> themeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, THEMES);
        spinnerTheme.setAdapter(themeAdapter);
        spinnerTheme.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                applyTheme(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void applyTheme(int themeIdx) {
        int rootBg, cardBg, accentColor, textColor;
        switch (themeIdx) {
            case 1: // NIGHT OPS OLED
                rootBg = Color.parseColor("#000000");
                cardBg = Color.parseColor("#080808");
                accentColor = Color.parseColor("#00FFA3");
                textColor = Color.parseColor("#00FF66");
                break;
            case 2: // STEALTH AMBER
                rootBg = Color.parseColor("#120E04");
                cardBg = Color.parseColor("#1C1507");
                accentColor = Color.parseColor("#FFB300");
                textColor = Color.parseColor("#FFD54F");
                break;
            case 3: // COMBAT RED
                rootBg = Color.parseColor("#140505");
                cardBg = Color.parseColor("#210B0B");
                accentColor = Color.parseColor("#FF1744");
                textColor = Color.parseColor("#FF5252");
                break;
            default: // CYAN TACTICAL
                rootBg = Color.parseColor("#070B12");
                cardBg = Color.parseColor("#0E1626");
                accentColor = Color.parseColor("#00E5FF");
                textColor = Color.parseColor("#00FFA3");
                break;
        }

        rootLayout.setBackgroundColor(rootBg);
        topBarLayout.setBackgroundColor(cardBg);
        configBarLayout.setBackgroundColor(cardBg);
        tvAppTitle.setTextColor(textColor);
        btnPushToTalk.setBackgroundColor(accentColor);
        appendConsole("THEME", "Activated visual mode: " + THEMES[themeIdx]);
    }

    private void setupLanguagesAndChannels() {
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, INDIAN_LANGUAGES);
        spinnerLanguage.setAdapter(langAdapter);
        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                selectedLanguageCode = LANGUAGE_CODES[position];
                if (voiceEngine != null) {
                    voiceEngine.setLanguage(selectedLanguageCode);
                }
                appendConsole("LANG", "Primary Speech-To-Text / TTS dialect: " + INDIAN_LANGUAGES[position]);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<String> chAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CHANNELS);
        spinnerChannel.setAdapter(chAdapter);
        spinnerChannel.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                activeChannel = CHANNELS[position];
                activeChannelId = position + 1;
                appendConsole("CHANNEL", "Tuned transceiver to: " + activeChannel);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void setupP2pRecyclerView() {
        rvP2pDevices.setLayoutManager(new LinearLayoutManager(this));
        deviceAdapter = new DeviceAdapter(discoveredDevices, () -> (wifiP2pEngine != null && wifiP2pEngine.isConnected()), device -> {
            appendConsole("P2P", "Connecting to peer: " + device.deviceName);
            if (wifiP2pEngine != null) {
                wifiP2pEngine.connectToPeer(device);
            }
        });
        rvP2pDevices.setAdapter(deviceAdapter);
    }

    private void setupCameraLauncher() {
        cameraLauncher = registerForActivityResult(
                new ActivityResultContracts.TakePicturePreview(),
                bitmap -> {
                    if (bitmap != null) {
                        handleCapturedPhoto(bitmap);
                    } else {
                        appendConsole("CAMERA", "Photo capture cancelled.");
                    }
                }
        );
    }

    private void launchCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, PERMISSION_REQ_CODE);
            return;
        }
        cameraLauncher.launch(null);
    }

    private void handleCapturedPhoto(Bitmap bitmap) {
        appendConsole("CAMERA", "Compressing tactical reconnaissance image...");
        new Thread(() -> {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, baos);
            byte[] imageBytes = baos.toByteArray();
            int sizeKb = imageBytes.length / 1024;

            runOnUiThread(() -> {
                appendConsole("CAMERA", "Transmitting tactical photo (" + sizeKb + " KB) over P2P mesh pipe.");
                wifiP2pEngine.sendPacket(WifiP2pEngine.PACKET_TYPE_IMAGE, selectedLanguageCode, imageBytes);
            });
        }).start();
    }

    private void initPipelines() {
        // 1. Audio Processing Pipeline
        audioPipeline = new AudioCapturePipeline(new AudioCapturePipeline.AudioPipelineCallback() {
            @Override
            public void onAudioChunkCaptured(short[] pcmChunk, float[] floatBlock, float rmsDb) {
                // Update live interactive visualizer smoothly
                waveformCanvasView.updateAudioData(floatBlock, rmsDb);
            }

            @Override
            public void onVadSilenceDetected(float[] completeUtterance) {
                appendConsole("VAD", "Silence threshold reached (300ms). Committing to Edge AI.");
                stopPttAnimation();
                voiceEngine.transcribeAudioAsync(completeUtterance, AudioCapturePipeline.SAMPLE_RATE, activeChannel, localNodeId, false);
            }

            @Override
            public void onMaxDurationExceeded(float[] completeUtterance) {
                appendConsole("VAD", "Max audio duration (35s) hit. Auto-committing utterance.");
                stopPttAnimation();
                voiceEngine.transcribeAudioAsync(completeUtterance, AudioCapturePipeline.SAMPLE_RATE, activeChannel, localNodeId, false);
            }

            @Override
            public void onCaptureError(String error) {
                appendConsole("AUDIO_ERR", error);
                stopPttAnimation();
            }
        });

        // 2. Offline Edge AI Engine
        voiceEngine = new OfflineVoiceEngine(this, new OfflineVoiceEngine.VoiceEngineListener() {
            @Override
            public void onSpeechToTextResult(String text, float rtf, long processMs, long audioMs) {
                tvMetricRtf.setText(String.format(Locale.US, "RTF: %.2f", rtf));
                appendConsole("STT", "[" + selectedLanguageCode.toUpperCase() + "] " + text);
                appendConsole("RTF", String.format(Locale.US, "Processed %d ms audio in %d ms (RTF: %.2f)", audioMs, processMs, rtf));

                // Send recognized transcript over P2P network
                byte[] textBytes = text.getBytes(StandardCharsets.UTF_8);
                wifiP2pEngine.sendPacket(WifiP2pEngine.PACKET_TYPE_TEXT_STT, selectedLanguageCode, textBytes);
            }

            @Override
            public void onTtsPlaybackFinished() {
                appendConsole("TTS", "Audio dispatch playback completed.");
            }

            @Override
            public void onBookmarkSaved(String bookmarkId, String title, String audioPath) {
                appendConsole("BOOKMARK", "Offline Audio Backup saved: " + bookmarkId);
            }

            @Override
            public void onEngineLog(String logMessage) {
                appendConsole("EDGE_AI", logMessage);
            }

            @Override
            public void onEngineError(String errorMessage) {
                appendConsole("AI_ERR", errorMessage);
            }
        });

        // 3. Wi-Fi Direct Mesh Engine
        wifiP2pEngine = new WifiP2pEngine(this, new WifiP2pEngine.WifiP2pEventListener() {
            @Override
            public void onPeersListChanged(List<WifiP2pDevice> devices) {
                discoveredDevices.clear();
                discoveredDevices.addAll(devices);
                deviceAdapter.notifyDataSetChanged();

                List<String> nodeIds = new ArrayList<>();
                for (WifiP2pDevice d : devices) {
                    nodeIds.add(d.deviceName != null ? d.deviceName : "NODE");
                }
                meshNodeMapView.setDiscoveredNodes(nodeIds);
            }

            @Override
            public void onConnectionEstablished(String peerAddress, boolean isGroupOwner) {
                appendConsole("P2P", "Mesh Link Online. Endpoint: " + peerAddress + " (Group Owner: " + isGroupOwner + ")");
            }

            @Override
            public void onConnectionLost() {
                appendConsole("P2P", "Mesh link disconnected.");
            }

            @Override
            public void onPacketReceived(byte packetType, String langCode, byte[] payload) {
                handleIncomingPacket(packetType, langCode, payload);
            }

            @Override
            public void onSocketLatencyUpdated(long latencyMs) {
                tvMetricLatency.setText(String.format(Locale.US, "SOCK: %d ms", latencyMs));
            }

            @Override
            public void onWifiSocketTimeout(byte packetType, String langCode, byte[] payload) {
                if (packetType == WifiP2pEngine.PACKET_TYPE_SOS) {
                    appendConsole("FAILOVER", "Wi-Fi Socket timeout on SOS! Switching to BLE Fallback...");
                    bleFallback.broadcastPriorityCode(BleFallback.CODE_FIRE, activeChannelId, localNodeId);
                }
            }

            @Override
            public void onEngineLog(String message) {
                appendConsole("WIFI_P2P", message);
            }

            @Override
            public void onEngineError(String error) {
                appendConsole("P2P_ERR", error);
            }
        });

        // 4. BLE Fallback Channel
        bleFallback = new BleFallback(this, new BleFallback.BleFallbackListener() {
            @Override
            public void onBlePriorityCodeReceived(byte priorityCode, String eventTitle, int channelId, String senderNodeId) {
                if (priorityCode == BleFallback.CODE_ACK) return;
                if (senderNodeId != null && senderNodeId.contains(localNodeId)) return;

                triggerHapticFeedback(150);
                appendConsole("BLE_RX", "[CH " + channelId + "] Priority Beacon: " + eventTitle + " from " + senderNodeId);
                String spokenAlert = OfflineVoiceEngine.getLocalizedSosAlert(eventTitle, selectedLanguageCode);
                voiceEngine.speakTextAsync(spokenAlert, 0, 1.1f);
            }

            @Override
            public void onBleAdvertisingStarted() {
                appendConsole("BLE_TX", "Emergency broadcast radiating over 2.4GHz BLE beacons.");
            }

            @Override
            public void onBleLog(String message) {
                appendConsole("BLE", message);
            }

            @Override
            public void onBleError(String error) {
                appendConsole("BLE_ERR", error);
            }
        });

        bleFallback.setLocalNodeId(localNodeId);
        bleFallback.startScanning();
        wifiP2pEngine.discoverPeers();

        // Continuous peer discovery ticker every 4 seconds
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (wifiP2pEngine != null) {
                    wifiP2pEngine.discoverPeers();
                }
                handler.postDelayed(this, 4000);
            }
        }, 4000);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupPttListener() {
        btnPushToTalk.setOnTouchListener((view, motionEvent) -> {
            switch (motionEvent.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startPttTransmission();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    stopPttTransmission();
                    return true;
            }
            return false;
        });
    }

    private void startPttTransmission() {
        try {
            triggerHapticFeedback(60);
            playMicKeySound();
            startPttAnimation();
            appendConsole("PTT", "TX TRANSMITTING on " + activeChannel + " (" + selectedLanguageCode + ")");
            if (audioPipeline != null) {
                audioPipeline.startCapture();
            }
        } catch (Exception e) {
            Log.e(TAG, "PTT start exception: " + e.getMessage());
        }
    }

    private void stopPttTransmission() {
        try {
            stopPttAnimation();
            playHardwareRogerBeep();
            waveformCanvasView.clearAudioData();
            appendConsole("PTT", "TX RELEASED. Finalizing audio buffer...");

            byte[] pcmBytes = (audioPipeline != null) ? audioPipeline.extractAccumulatedBytes() : null;
            float[] fullAudio = (audioPipeline != null) ? audioPipeline.stopCapture() : null;

            if (pcmBytes != null && pcmBytes.length > 640 && wifiP2pEngine != null) {
                appendConsole("P2P_TX", "Transmitting crystal-clear voice packet (" + (pcmBytes.length / 1024) + " KB)...");
                wifiP2pEngine.sendPacket(WifiP2pEngine.PACKET_TYPE_AUDIO_PCM, selectedLanguageCode, pcmBytes);
            }

            if (fullAudio != null && fullAudio.length > 320 && voiceEngine != null) {
                voiceEngine.transcribeAudioAsync(fullAudio, AudioCapturePipeline.SAMPLE_RATE, activeChannel, localNodeId, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "PTT stop exception: " + e.getMessage());
        }
    }

    private synchronized void playReceivedVoiceAudio(final byte[] pcmBytes) {
        if (pcmBytes == null || pcmBytes.length < 320) return;

        audioPlaybackExecutor.execute(() -> {
            android.media.AudioTrack audioTrack = null;
            try {
                // Ensure device volume is audible
                try {
                    android.media.AudioManager am = (android.media.AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        int maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC);
                        int curVol = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC);
                        if (curVol < maxVol / 3) {
                            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, (int)(maxVol * 0.8), 0);
                        }
                    }
                } catch (Exception ignored) {}

                int sampleRate = AudioCapturePipeline.SAMPLE_RATE;
                int shortCount = pcmBytes.length / 2;
                short[] pcmShorts = new short[shortCount];
                float[] visualFloats = new float[Math.min(shortCount, 640)];

                // Convert bytes to shorts with soft tanh boost, fade-in, and fade-out
                int fadeSamples = Math.min(160, shortCount / 4); // 10ms smooth ramp
                for (int i = 0; i < shortCount; i++) {
                    short rawVal = (short) ((pcmBytes[i * 2] & 0xFF) | (pcmBytes[i * 2 + 1] << 8));
                    float sampleNorm = rawVal / 32768.0f;
                    float amplified = (float) Math.tanh(sampleNorm * 1.8f);
                    short val = (short) Math.max(-32768, Math.min(32767, Math.round(amplified * 32767.0f)));
                    
                    if (i < fadeSamples) {
                        float ramp = (float) i / (float) fadeSamples;
                        val = (short) (val * ramp);
                    } else if (i >= shortCount - fadeSamples) {
                        float ramp = (float) (shortCount - 1 - i) / (float) fadeSamples;
                        val = (short) (val * ramp);
                    }
                    pcmShorts[i] = val;

                    if (i < visualFloats.length) {
                        visualFloats[i] = amplified;
                    }
                }

                // Render live waveform on receiver's oscilloscope HUD
                runOnUiThread(() -> waveformCanvasView.updateAudioData(visualFloats, 0.05f));

                int minBuf = android.media.AudioTrack.getMinBufferSize(
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT
                );
                int bufferSize = Math.max(minBuf * 4, 8192);

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    android.media.AudioAttributes attrs = new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    android.media.AudioFormat format = new android.media.AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                            .build();
                    audioTrack = new android.media.AudioTrack(
                            attrs,
                            format,
                            bufferSize,
                            android.media.AudioTrack.MODE_STREAM,
                            android.media.AudioManager.AUDIO_SESSION_ID_GENERATE
                    );
                } else {
                    audioTrack = new android.media.AudioTrack(
                            android.media.AudioManager.STREAM_MUSIC,
                            sampleRate,
                            android.media.AudioFormat.CHANNEL_OUT_MONO,
                            android.media.AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize,
                            android.media.AudioTrack.MODE_STREAM
                    );
                }

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                    audioTrack.setVolume(1.0f);
                }
                audioTrack.play();
                int written = 0;
                while (written < shortCount) {
                    int chunk = Math.min(640, shortCount - written);
                    audioTrack.write(pcmShorts, written, chunk);
                    written += chunk;
                }

                long durationMs = (long) ((shortCount / (float) sampleRate) * 1000.0f);
                Thread.sleep(durationMs + 60);

            } catch (Exception e) {
                Log.e(TAG, "Error during received voice playback: " + e.getMessage());
            } finally {
                if (audioTrack != null) {
                    try {
                        audioTrack.stop();
                        audioTrack.release();
                    } catch (Exception ignored) {}
                }
                runOnUiThread(() -> waveformCanvasView.clearAudioData());
                playHardwareRogerBeep();
            }
        });
    }

    private void playMicKeySound() {
        new Thread(() -> {
            android.media.AudioTrack track = null;
            try {
                int sampleRate = 16000;
                int count = (int) (sampleRate * 0.04);
                short[] buffer = new short[count];
                java.util.Random rnd = new java.util.Random();
                for (int i = 0; i < count; i++) {
                    buffer[i] = (short) ((rnd.nextFloat() * 2.0f - 1.0f) * 2000);
                }
                track = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        count * 2,
                        android.media.AudioTrack.MODE_STATIC
                );
                track.write(buffer, 0, count);
                track.play();
                Thread.sleep(60);
            } catch (Exception ignored) {
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                        track.release();
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void playHardwareRogerBeep() {
        new Thread(() -> {
            android.media.AudioTrack track = null;
            try {
                int sampleRate = 16000;
                int duration1 = (int) (sampleRate * 0.08);
                int duration2 = (int) (sampleRate * 0.08);
                int noiseDur = (int) (sampleRate * 0.05);
                int totalSamples = duration1 + duration2 + noiseDur;
                short[] buffer = new short[totalSamples];

                // Tone 1: 2524 Hz
                for (int i = 0; i < duration1; i++) {
                    double angle = 2.0 * Math.PI * 2524.0 * i / sampleRate;
                    buffer[i] = (short) (Math.sin(angle) * 7000 * (1.0 - (double) i / duration1));
                }
                // Tone 2: 1475 Hz
                for (int i = 0; i < duration2; i++) {
                    double angle = 2.0 * Math.PI * 1475.0 * i / sampleRate;
                    buffer[duration1 + i] = (short) (Math.sin(angle) * 8000 * (1.0 - (double) i / duration2));
                }
                // Squelch burst
                java.util.Random rnd = new java.util.Random();
                int offset = duration1 + duration2;
                for (int i = 0; i < noiseDur; i++) {
                    buffer[offset + i] = (short) ((rnd.nextFloat() * 2.0f - 1.0f) * 2500 * (1.0 - (double) i / noiseDur));
                }

                track = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        totalSamples * 2,
                        android.media.AudioTrack.MODE_STATIC
                );
                track.write(buffer, 0, totalSamples);
                track.play();
                Thread.sleep(250);
            } catch (Exception ignored) {
            } finally {
                if (track != null) {
                    try {
                        track.stop();
                        track.release();
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    private void startPttAnimation() {
        runOnUiThread(() -> {
            btnPushToTalk.setText("TRANSMITTING\nTALK NOW");
            btnPushToTalk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#FF1744")));
            viewPttRingPulse.setAlpha(1.0f);
            if (pttPulseAnimator == null) {
                pttPulseAnimator = ObjectAnimator.ofFloat(viewPttRingPulse, "scaleX", 1.0f, 1.45f);
                pttPulseAnimator.setDuration(600);
                pttPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
                pttPulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
            }
            pttPulseAnimator.start();
        });
    }

    private void stopPttAnimation() {
        runOnUiThread(() -> {
            btnPushToTalk.setText("PTT\nHOLD");
            btnPushToTalk.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#00E5FF")));
            if (pttPulseAnimator != null && pttPulseAnimator.isRunning()) {
                pttPulseAnimator.cancel();
            }
            viewPttRingPulse.setScaleX(1.0f);
            viewPttRingPulse.setAlpha(0.0f);
        });
    }

    private void setupSosButtons() {
        btnSosFire.setOnClickListener(v -> dispatchSos(BleFallback.CODE_FIRE, "FIRE"));
        btnSosFlood.setOnClickListener(v -> dispatchSos(BleFallback.CODE_FLOOD, "FLOOD"));
        btnSosMedical.setOnClickListener(v -> dispatchSos(BleFallback.CODE_MEDIC, "MEDIC"));
        btnSosCollapse.setOnClickListener(v -> dispatchSos(BleFallback.CODE_COLLAPSE, "COLLAPSE"));
    }

    private void playEmergencySiren() {
        new Thread(() -> {
            try {
                int sampleRate = 16000;
                int duration = (int) (sampleRate * 1.0);
                short[] buffer = new short[duration];
                for (int i = 0; i < duration; i++) {
                    double freq = 700.0 + 350.0 * Math.sin(2.0 * Math.PI * 3.0 * i / sampleRate);
                    double angle = 2.0 * Math.PI * freq * i / sampleRate;
                    buffer[i] = (short) (Math.sin(angle) * 12000);
                }
                android.media.AudioTrack track = new android.media.AudioTrack(
                        android.media.AudioManager.STREAM_MUSIC,
                        sampleRate,
                        android.media.AudioFormat.CHANNEL_OUT_MONO,
                        android.media.AudioFormat.ENCODING_PCM_16BIT,
                        duration * 2,
                        android.media.AudioTrack.MODE_STATIC
                );
                track.write(buffer, 0, duration);
                track.play();
                Thread.sleep(1100);
                track.release();
            } catch (Exception ignored) {}
        }).start();
    }

    private void dispatchSos(byte code, String type) {
        triggerHapticFeedback(400);
        playEmergencySiren();
        appendConsole("SOS", "EMERGENCY BEACON DISPATCHED: " + type + " on " + activeChannel);

        String payloadStr = "EMERGENCY:" + type + "|NODE:" + localNodeId + "|CH:" + activeChannel;
        byte[] payloadBytes = payloadStr.getBytes(StandardCharsets.UTF_8);

        // 1. Send via Wi-Fi P2P primary pipe
        wifiP2pEngine.sendPacket(WifiP2pEngine.PACKET_TYPE_SOS, selectedLanguageCode, payloadBytes);

        // 2. Broadcast immediately over BLE Fallback
        bleFallback.broadcastPriorityCode(code, activeChannelId, localNodeId);

        // 3. Audio dispatch
        String localAlert = OfflineVoiceEngine.getLocalizedSosAlert(type, selectedLanguageCode);
        voiceEngine.speakTextAsync(localAlert, 0, 1.0f);
    }

    private void handleIncomingPacket(byte packetType, String langCode, byte[] payload) {
        switch (packetType) {
            case WifiP2pEngine.PACKET_TYPE_AUDIO_PCM:
                appendConsole("AUDIO_RX", "Tactical voice transmission received (" + (payload.length / 1024) + " KB). Playing high-fidelity audio...");
                triggerHapticFeedback(50);
                playReceivedVoiceAudio(payload);
                break;
            case WifiP2pEngine.PACKET_TYPE_TEXT_STT:
                String text = new String(payload, StandardCharsets.UTF_8);
                String fromLang = (langCode != null ? langCode.toLowerCase() : "en");
                String toLang = (selectedLanguageCode != null ? selectedLanguageCode.toLowerCase() : "hi");
                String translatedText = OfflineVoiceEngine.translateTacticalText(text, fromLang, toLang);
                if (!fromLang.equals(toLang) && !translatedText.equals(text)) {
                    appendConsole("REMOTE_STT", "[" + fromLang.toUpperCase() + " ➔ " + toLang.toUpperCase() + "] " + translatedText + " (Original: " + text + ")");
                } else {
                    appendConsole("REMOTE_STT", "[" + fromLang.toUpperCase() + "] " + text);
                }
                triggerHapticFeedback(40);
                break;
            case WifiP2pEngine.PACKET_TYPE_SOS:
                String sosMsg = new String(payload, StandardCharsets.UTF_8);
                triggerHapticFeedback(500);
                playEmergencySiren();
                appendConsole("SOS_ALERT", "INCOMING SOS: " + sosMsg);
                String spokenAlert = OfflineVoiceEngine.getLocalizedSosAlert(sosMsg, langCode);
                voiceEngine.speakTextAsync(spokenAlert, 0, 1.0f);
                break;
            case WifiP2pEngine.PACKET_TYPE_IMAGE:
                int imgSizeKb = payload.length / 1024;
                appendConsole("IMAGE_RX", "Tactical recon image received (" + imgSizeKb + " KB)");
                runOnUiThread(() -> {
                    try {
                        android.graphics.Bitmap bmp = android.graphics.BitmapFactory.decodeByteArray(payload, 0, payload.length);
                        if (bmp != null) {
                            android.widget.ImageView iv = new android.widget.ImageView(this);
                            iv.setImageBitmap(bmp);
                            iv.setAdjustViewBounds(true);
                            iv.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
                            new AlertDialog.Builder(this)
                                    .setTitle("📷 TACTICAL RECON PHOTO (" + imgSizeKb + " KB)")
                                    .setView(iv)
                                    .setPositiveButton("DISMISS", null)
                                    .show();
                        }
                    } catch (Exception ignored) {}
                });
                break;
        }
    }

    private void showEmergencyContactsDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Tactical Emergency Contacts (Max 5)");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(30, 20, 30, 20);

        final EditText[] contactInputs = new EditText[5];
        for (int i = 0; i < 5; i++) {
            contactInputs[i] = new EditText(this);
            contactInputs[i].setHint("Contact " + (i + 1) + " (Name / Phone / ID)");
            contactInputs[i].setInputType(InputType.TYPE_CLASS_TEXT);
            String saved = sharedPrefs.getString("contact_" + i, "");
            contactInputs[i].setText(saved);
            layout.addView(contactInputs[i]);
        }

        builder.setView(layout);
        builder.setPositiveButton("Save", (dialog, which) -> {
            SharedPreferences.Editor editor = sharedPrefs.edit();
            for (int i = 0; i < 5; i++) {
                editor.putString("contact_" + i, contactInputs[i].getText().toString().trim());
            }
            editor.apply();
            appendConsole("CONFIG", "Emergency contact directory updated.");
        });
        builder.setNegativeButton("Cancel", null);
        builder.setNeutralButton("ZEROIZE & UNINSTALL", (dialog, which) -> tacticalZeroizeAndUninstall());
        builder.show();
    }

    private void startMetricPolling() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                updateLiveMetrics();
                handler.postDelayed(this, 1500);
            }
        }, 1500);
    }

    private void updateLiveMetrics() {
        Runtime runtime = Runtime.getRuntime();
        long usedMemoryBytes = runtime.totalMemory() - runtime.freeMemory();
        double usedMb = usedMemoryBytes / (1024.0 * 1024.0);
        tvMetricRam.setText(String.format(Locale.US, "RAM: %.1f MB", usedMb));
    }

    public void appendConsole(String tag, String message) {
        runOnUiThread(() -> {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
            String logLine = "[" + time + "] [" + tag + "] " + message + "\n";
            tvConsoleLogs.append(logLine);
            consoleScrollView.post(() -> consoleScrollView.fullScroll(View.FOCUS_DOWN));
        });
    }

    private void triggerHapticFeedback(int durationMs) {
        if (vibrator != null && vibrator.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(durationMs);
            }
        }
    }

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        String[] permissions = {
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                needed.add(p);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_SCAN);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSION_REQ_CODE);
        }
    }

    /**
     * Tactical Emergency Zeroize & Self-Uninstall Routine:
     * Purges all local audio bookmarks, emergency contacts, cached data,
     * releases all hardware/radio pipelines, and invokes the Android Package Uninstaller.
     */
    public void tacticalZeroizeAndUninstall() {
        new AlertDialog.Builder(this)
                .setTitle("⚠️ EMERGENCY ZEROIZE & UNINSTALL")
                .setMessage("CRITICAL WARNING: This will permanently wipe all local audio bookmarks, emergency contacts, mesh logs, and launch the Android uninstaller to completely remove this app from your device. Proceed?")
                .setPositiveButton("CONFIRM ZEROIZE", (dialog, which) -> executeWipeAndUninstall())
                .setNegativeButton("CANCEL", null)
                .show();
    }

    private void executeWipeAndUninstall() {
        try {
            // 1. Wipe SharedPreferences
            sharedPrefs.edit().clear().commit();

            // 2. Wipe Bookmarks Directory
            java.io.File bookmarkDir = new java.io.File(getFilesDir(), "bookmarks");
            if (bookmarkDir.exists()) {
                java.io.File[] files = bookmarkDir.listFiles();
                if (files != null) {
                    for (java.io.File f : files) f.delete();
                }
                bookmarkDir.delete();
            }

            // 3. Wipe Cache
            java.io.File cacheDir = getCacheDir();
            if (cacheDir != null && cacheDir.exists()) {
                java.io.File[] files = cacheDir.listFiles();
                if (files != null) {
                    for (java.io.File f : files) f.delete();
                }
            }

            // 4. Release all hardware & radio resources
            if (audioPipeline != null) audioPipeline.releaseResources();
            if (voiceEngine != null) voiceEngine.shutdown();
            if (wifiP2pEngine != null) wifiP2pEngine.shutdown();
            if (bleFallback != null) bleFallback.shutdown();

            appendConsole("ZEROIZE", "Node storage purged. Invoking Android Package Uninstaller...");

            // 5. Trigger Android Package Uninstaller Intent
            android.content.Intent uninstallIntent = new android.content.Intent(android.content.Intent.ACTION_DELETE);
            uninstallIntent.setData(android.net.Uri.parse("package:" + getPackageName()));
            uninstallIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(uninstallIntent);

            finishAffinity();
        } catch (Exception e) {
            android.util.Log.e(TAG, "Zeroize failed: " + e.getMessage());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (audioPipeline != null) audioPipeline.releaseResources();
        if (voiceEngine != null) voiceEngine.shutdown();
        if (wifiP2pEngine != null) wifiP2pEngine.shutdown();
        if (bleFallback != null) bleFallback.shutdown();
    }

    // RecyclerView Adapter for discovered P2P devices
    private static class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {
        private final List<WifiP2pDevice> devices;
        private final ConnectionChecker connectionChecker;
        private final OnDeviceClickListener clickListener;

        interface OnDeviceClickListener {
            void onDeviceClick(WifiP2pDevice device);
        }

        interface ConnectionChecker {
            boolean isConnected();
        }

        DeviceAdapter(List<WifiP2pDevice> devices, ConnectionChecker connectionChecker, OnDeviceClickListener clickListener) {
            this.devices = devices;
            this.connectionChecker = connectionChecker;
            this.clickListener = clickListener;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            TextView tv = new TextView(parent.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            tv.setPadding(8, 8, 8, 8);
            tv.setTextColor(Color.parseColor("#00E5FF"));
            tv.setTextSize(10f);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            return new ViewHolder(tv);
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            WifiP2pDevice dev = devices.get(position);
            String name = (dev.deviceName != null && !dev.deviceName.isEmpty()) ? dev.deviceName : dev.deviceAddress;
            boolean isConnected = (connectionChecker != null && connectionChecker.isConnected()) || dev.status == WifiP2pDevice.CONNECTED || dev.status == WifiP2pDevice.INVITED;
            String status = isConnected ? "[CONNECTED]" : "[TAP TO CONNECT]";
            String indicator = isConnected ? "🟢 " : "🟡 ";
            holder.textView.setText(indicator + name + "\n" + status);
            holder.textView.setTextColor(isConnected ? Color.parseColor("#00FFA3") : Color.parseColor("#00E5FF"));
            holder.itemView.setOnClickListener(v -> clickListener.onDeviceClick(dev));
        }

        @Override
        public int getItemCount() {
            return devices.size();
        }

        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView textView;
            ViewHolder(View itemView) {
                super(itemView);
                textView = (TextView) itemView;
            }
        }
    }
}
