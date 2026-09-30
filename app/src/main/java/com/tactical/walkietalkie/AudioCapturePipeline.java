package com.tactical.walkietalkie;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.NoiseSuppressor;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * AudioCapturePipeline: Ultra-reliable PCM audio capture engine.
 * Specifically hardened against MediaTek / Tecno HiOS Audio HAL locking bugs:
 * - Multi-source hardware fallback (VOICE_COMMUNICATION -> MIC -> DEFAULT)
 * - Safe NoiseSuppressor lifecycle management to prevent MediaTek DSP orphaned sessions
 * - Complete teardown and release on every PTT release to guarantee 100% microphone availability
 * - Real-time tanh soft gain compression and VAD analysis
 */
public class AudioCapturePipeline {
    private static final String TAG = "AudioCapturePipeline";

    public interface AudioPipelineCallback {
        void onAudioChunkCaptured(short[] pcmChunk, float[] floatBlock, float rmsDb);
        void onVadSilenceDetected(float[] completeUtterance);
        void onMaxDurationExceeded(float[] completeUtterance);
        void onCaptureError(String error);
    }

    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final float NOISE_FLOOR_RMS = 0.015f;
    private static final int SILENCE_THRESHOLD_MS = 1800;
    private static final int HARD_TIMEOUT_LIMIT_MS = 45000;

    private final AudioPipelineCallback callback;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private AudioRecord audioRecord;
    private NoiseSuppressor noiseSuppressor;
    private ExecutorService captureExecutor;
    private final AtomicBoolean isCapturing = new AtomicBoolean(false);

    private final ByteArrayOutputStream utteranceAccumulator = new ByteArrayOutputStream();
    private long captureStartTimeMs = 0;
    private long silenceStartTimestampMs = 0;
    private boolean voiceActivityDetected = false;

    public AudioCapturePipeline(AudioPipelineCallback callback) {
        this.callback = callback;
    }

    public boolean isCapturing() {
        return isCapturing.get();
    }

    @SuppressLint("MissingPermission")
    public synchronized void startCapture() {
        if (isCapturing.get()) {
            return;
        }

        // Clean up any residual handles before starting fresh
        releaseHardwareHandles();

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        int bufferSize = Math.max(minBufferSize * 2, 4096);

        // Sources to try in priority order: VOICE_COMMUNICATION is best for Walkie-Talkie/VoIP, MIC is universal
        int[] audioSources = {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.DEFAULT
        };

        int selectedSource = -1;
        for (int source : audioSources) {
            try {
                AudioRecord candidate = new AudioRecord(source, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                if (candidate.getState() == AudioRecord.STATE_INITIALIZED) {
                    audioRecord = candidate;
                    selectedSource = source;
                    break;
                } else {
                    candidate.release();
                }
            } catch (Exception ex) {
                Log.w(TAG, "Audio source " + source + " failed initialization: " + ex.getMessage());
            }
        }

        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            // Short 50ms pause and retry with default MIC if MediaTek HAL was still releasing previous session
            try {
                Thread.sleep(50);
                audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize);
                if (audioRecord.getState() == AudioRecord.STATE_INITIALIZED) {
                    selectedSource = MediaRecorder.AudioSource.MIC;
                }
            } catch (Exception ignored) {}
        }

        if (audioRecord == null || audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post(() -> callback.onCaptureError("Mic hardware busy. Please release PTT and try again."));
            releaseHardwareHandles();
            return;
        }

        // Safely try attaching hardware NoiseSuppressor without crashing if MediaTek HAL fails
        try {
            int audioSessionId = audioRecord.getAudioSessionId();
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }
        } catch (Exception nsEx) {
            Log.w(TAG, "NoiseSuppressor non-fatal notice: " + nsEx.getMessage());
            noiseSuppressor = null;
        }

        synchronized (utteranceAccumulator) {
            utteranceAccumulator.reset();
        }
        captureStartTimeMs = System.currentTimeMillis();
        silenceStartTimestampMs = 0;
        voiceActivityDetected = false;

        try {
            audioRecord.startRecording();
            isCapturing.set(true);
            captureExecutor = Executors.newSingleThreadExecutor();
            captureExecutor.execute(this::processingLoop);
            Log.i(TAG, "Audio capture started successfully (Source: " + selectedSource + ")");
        } catch (Exception startEx) {
            isCapturing.set(false);
            mainHandler.post(() -> callback.onCaptureError("AudioRecord startRecording failed: " + startEx.getMessage()));
            releaseHardwareHandles();
        }
    }

    private void processingLoop() {
        int readChunkSize = 640; // 40ms frame @ 16kHz
        short[] shortBuffer = new short[readChunkSize];
        byte[] byteBuffer = new byte[readChunkSize * 2];
        float[] floatBlock = new float[readChunkSize];

        while (isCapturing.get() && audioRecord != null) {
            int shortsRead = 0;
            try {
                shortsRead = audioRecord.read(shortBuffer, 0, readChunkSize);
            } catch (Exception readEx) {
                Log.e(TAG, "AudioRecord read exception: " + readEx.getMessage());
                break;
            }

            if (shortsRead <= 0) {
                if (shortsRead == AudioRecord.ERROR_INVALID_OPERATION || shortsRead == AudioRecord.ERROR_BAD_VALUE) {
                    Log.w(TAG, "AudioRecord read error code: " + shortsRead);
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException ignored) {}
                }
                continue;
            }

            double sumSquares = 0.0;
            for (int i = 0; i < shortsRead; i++) {
                // Studio-grade soft tanh gain boost (2.2x gain without clipping distortion)
                float sampleNorm = shortBuffer[i] / 32768.0f;
                float amplified = (float) Math.tanh(sampleNorm * 2.2f);
                short s = (short) Math.max(-32768, Math.min(32767, Math.round(amplified * 32767.0f)));
                shortBuffer[i] = s;

                floatBlock[i] = amplified;
                sumSquares += (amplified * amplified);

                byteBuffer[i * 2] = (byte) (s & 0xFF);
                byteBuffer[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
            }

            synchronized (utteranceAccumulator) {
                utteranceAccumulator.write(byteBuffer, 0, shortsRead * 2);
            }

            float rms = (float) Math.sqrt(sumSquares / shortsRead);
            float rmsDb = (rms > 0.0001f) ? (float) (20.0 * Math.log10(rms)) : -80.0f;

            float[] dispatchedBlock = new float[shortsRead];
            System.arraycopy(floatBlock, 0, dispatchedBlock, 0, shortsRead);
            short[] dispatchedShorts = new short[shortsRead];
            System.arraycopy(shortBuffer, 0, dispatchedShorts, 0, shortsRead);

            // Stream chunk for live waveform rendering and network pipes
            mainHandler.post(() -> callback.onAudioChunkCaptured(dispatchedShorts, dispatchedBlock, rmsDb));

            long now = System.currentTimeMillis();

            // Energy-Based VAD tracking for live level metering
            if (rms >= NOISE_FLOOR_RMS) {
                voiceActivityDetected = true;
                silenceStartTimestampMs = 0;
            } else {
                if (voiceActivityDetected && silenceStartTimestampMs == 0) {
                    silenceStartTimestampMs = now;
                }
            }

            // Hard safety timeout limit protection (45 seconds max per PTT burst)
            if ((now - captureStartTimeMs) >= HARD_TIMEOUT_LIMIT_MS) {
                Log.w(TAG, "Max audio duration exceeded: " + HARD_TIMEOUT_LIMIT_MS + "ms");
                final float[] completeAudio = extractAccumulatedFloats();
                releaseHardwareHandles();
                mainHandler.post(() -> callback.onMaxDurationExceeded(completeAudio));
                break;
            }
        }
    }

    public synchronized float[] stopCapture() {
        float[] fullAudio = extractAccumulatedFloats();
        releaseHardwareHandles();
        return fullAudio;
    }

    public byte[] extractAccumulatedBytes() {
        synchronized (utteranceAccumulator) {
            return utteranceAccumulator.toByteArray();
        }
    }

    public float[] extractAccumulatedFloats() {
        byte[] bytes;
        synchronized (utteranceAccumulator) {
            bytes = utteranceAccumulator.toByteArray();
        }
        int sampleCount = bytes.length / 2;
        float[] floats = new float[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            short sample = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
            floats[i] = sample / 32768.0f;
        }
        return floats;
    }

    /**
     * Completely and safely tears down hardware audio handles,
     * releasing the MediaTek HAL PCM device so it is immediately ready for next PTT.
     */
    private synchronized void releaseHardwareHandles() {
        isCapturing.set(false);

        if (captureExecutor != null && !captureExecutor.isShutdown()) {
            captureExecutor.shutdownNow();
            try {
                captureExecutor.awaitTermination(150, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {}
            captureExecutor = null;
        }

        if (noiseSuppressor != null) {
            try {
                noiseSuppressor.setEnabled(false);
                noiseSuppressor.release();
            } catch (Exception ignored) {}
            noiseSuppressor = null;
        }

        if (audioRecord != null) {
            try {
                if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    audioRecord.stop();
                }
            } catch (Exception ignored) {}
            try {
                audioRecord.release();
            } catch (Exception ignored) {}
            audioRecord = null;
        }
    }

    public synchronized void releaseResources() {
        releaseHardwareHandles();
        synchronized (utteranceAccumulator) {
            utteranceAccumulator.reset();
        }
        Log.i(TAG, "Audio capture pipeline resources fully released.");
    }
}
