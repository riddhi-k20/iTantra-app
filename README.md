# 🛰️ iTantra • Tactical Offline Mesh & Disaster Walkie-Talkie

> **Zero-Infrastructure, Zero-Internet, Edge-Native Tactical Voice & Emergency Broadcast Platform**  
> *Developed for Smart India Hackathon (SIH 2026) • Team NOVAX (Team ID: 235)*

---

## 📌 Problem Statement (SIH26173)
During natural disasters (floods, earthquakes, building collapses) or military operations, cellular towers and power grids collapse within minutes. First responders and citizens face extreme communication blackouts and regional language barriers. Traditional VHF/UHF military radios are expensive (₹40,000–₹1,50,000/unit), heavy, and lack real-time translation or visual telemetry.

## 💡 The Solution: iTantra
**iTantra** is a decentralized, 100% offline Android communication system that transforms standard commercial smartphones into military-grade tactical walkie-talkie transceivers without requiring SIM cards, internet, cellular towers, or external servers.

---

## ⚡ Key Technical Features

* 🎙️ **Full-Duplex Wi-Fi Direct Mesh (Port 8888)**:
  * High-fidelity **16000 Hz, 16-bit Mono PCM** streaming with hardware noise suppression, studio $\tanh$ dynamic soft gain, and 10ms anti-pop boundary envelopes.
* 📡 **Automated Dual-Layer Failover**:
  * Persistent Wi-Fi P2P socket pipeline with instant auto-switch to **2.4 GHz BLE Priority Beacons** if RF obstruction occurs.
* 🧠 **On-Device Multilingual Edge AI**:
  * 100% offline Speech-to-Text (STT) and Text-to-Speech (TTS) supporting **11 Indian Regional Languages** (*Hindi, Bengali, Tamil, Telugu, Marathi, Gujarati, Kannada, Malayalam, Punjabi, Odia, English*).
* 🚨 **4-Mode Categorized SOS Grid**:
  * One-touch emergency alert dispatches (**FIRE, FLOOD, MEDIC, COLLAPSE**) with acoustic sirens and beacon radiation.
* 📷 **Tactical Photo Reconnaissance**:
  * Peer-to-peer compressed image capture and real-time visual dialog inspection.
* 🎯 **Situational Awareness HUD**:
  * Real-time 60 FPS audio oscilloscope canvas and 2D polar radar node mapper.
* 🛡️ **OPSEC Emergency Zeroize**:
  * One-touch permanent wipe of bookmarks, contacts, cache, and self-uninstallation routine for emergency security.

---

## 🏗️ Technical Architecture & Stack

```
[ Operator Speaks (PTT) ]
           │
           ▼
[ 16kHz 16-Bit Mono Audio Pipeline + Hardware NoiseSuppressor & VAD ]
           │
           ▼
[ Custom Binary Protocol Framing: Type(1B) | Lang(2B) | Len(4B) | Payload ]
           │
   ┌───────┴────────────────────────┐
   ▼                                ▼
[ Primary: Wi-Fi Direct P2P ]     [ Failover: 2.4 GHz BLE Beacons ]
   │                                │
   └───────┬────────────────────────┘
           ▼
[ Receiving Node: Multi-Track Playback + Edge AI STT/TTS + Radar HUD ]
```

* **Language**: Java 8 / Android SDK (Min SDK 24, Target SDK 34)
* **Audio Engine**: `AudioRecord`, `AudioTrack`, `NoiseSuppressor`
* **Transport**: Full-duplex `ServerSocket` (Port 8888), `WifiP2pManager`, `BluetoothLeAdvertiser`, `BluetoothLeScanner`
* **Edge AI**: Local ONNX Runtime / Sherpa-ONNX INT8 Neural Transceiver

---

## 🚀 Installation & Setup

1. **Clone the Repository**:
   ```bash
   git clone https://github.com/riddhi-k20/iTantra-app.git
   ```
2. **Open in Android Studio**:
   * Open Android Studio -> Open Project -> Select `iTantra`.
   * Sync Gradle dependencies.
3. **Build & Install APK**:
   ```bash
   ./gradlew assembleDebug
   adb install -r -d -g app/build/outputs/apk/debug/app-debug.apk
   ```

---

## 👥 Team: 404 The Optimistics (Team NOVAX)
* **Problem Statement**: iTantra - Indian Multilingual TTS & STT Aided Neural Transceiver Radio Access for low bitrate links
* **Theme**: Disaster Management, Defense & Tactical Emergency Response
* **Event**: Smart India Hackathon (SIH 2026)
