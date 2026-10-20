# System & Software Architecture Documentation (arc42 Aligned)

**Application:** Ciao Linda (Automatic Voice-Note Assistant for Android — FOSS / F-Droid Distribution)  
**Package:** `com.aistudio.ciaolinda`  
**Target Platform:** Android 7.0 (API Level 24) through Android 15 / 16 (API Level 36)

---

## 1. System Context

The application runs entirely on-device in a sandboxed, zero-telemetry environment. It interfaces directly with Android HAL audio endpoints (Internal Microphones, USB Audio interfaces, Bluetooth SCO/BLE peripherals) to passively monitor acoustic activity, recognize wake phrases ("Ciao Linda"), and record automatic voice notes without cloud dependencies.

### System Context Diagram

```mermaid
graph TD
    User([User / Speaker])
    
    subgraph AndroidDevice ["Android Device Host Environment"]
        App["Ciao Linda Application<br/>(com.aistudio.ciaolinda)"]
        OS["Android OS / Framework APIs<br/>(AudioManager, AudioRecord, Room SQLite)"]
        Mic["Microphone Hardware<br/>(Built-in Mic, USB Audio, BT Headset)"]
        Speaker["Media Playback Engines<br/>(Podcasts, Music Players, Audiobooks)"]
        Storage["Internal App Storage<br/>(Room DB, WAV Voice Memos)"]
    end
    
    F_Droid["F-Droid / FOSS Repository<br/>(Distribution & Package Signing)"]
    
    User -->|Configures & Starts Monitoring| App
    App -->|Requests Audio Stream| OS
    OS -->|Captures PCM 16kHz Audio| Mic
    Speaker -.->|Emits Playback Status| OS
    OS -.->|AudioPlaybackCallback| App
    App -->|Persists Voice Notes & WAVs| Storage
    App -->|Dispatches Note Captured Notifications| OS
    F_Droid -.->|Delivers Standalone APK| User

    classDef boundary fill:#1E293B,stroke:#475569,stroke-width:2px,color:#F8FAFC;
    classDef component fill:#0F172A,stroke:#38BDF8,stroke-width:1.5px,color:#F8FAFC;
    classDef actor fill:#334155,stroke:#94A3B8,stroke-width:1.5px,color:#F8FAFC;
    class AndroidDevice boundary;
    class App,OS,Mic,Speaker,Storage component;
    class User,F_Droid actor;
```

**Context Characteristics:**
- **No Remote Backend / Cloud**: There is **no cloud server, analytics ingestion endpoint, or remote AI API**.
- **No Third-Party Tracker SDKs**: Relies strictly on standard Android Open Source Project (AOSP) APIs and Jetpack libraries.
- **Privacy-First**: Audio recordings are stored in private internal app storage.

---

## 2. Application Building Blocks (Decomposition View)

The application adheres to modern Android Architecture (MVVM + Foreground Service + Unidirectional Data Flow).

```mermaid
graph TB
    subgraph UI_Layer ["Presentation Layer (Jetpack Compose & Material 3)"]
        MainActivity["MainActivity<br/>(Compose Viewport & Navigation)"]
        DashboardTab["DashboardScreen<br/>(Live Meters, Status & Manual Controls)"]
        HistoryTab["HistoryScreen<br/>(Timeline, Waveform Playback, ZIP Export)"]
        SettingsTab["SettingsScreen<br/>(VAD Thresholds, Keyword Training, Hardware Selectors)"]
        GuideTab["GuideTab<br/>(Operational Guide & Best Practices)"]
    end

    subgraph ViewModel_Layer ["State & Orchestration Layer"]
        VoiceMemoViewModel["VoiceMemoViewModel<br/>(StateFlows, SharedPreferences, Export IO, Playback)"]
    end

    subgraph Service_Layer ["Core Service & Audio Capture Engine"]
        Service["AudioMonitoringService<br/>(Foreground Service + WakeLock)"]
        InputMgr["AudioInputManager<br/>(HAL Enumeration & SCO/USB Routing)"]
        MediaDet["MediaPlaybackDetector<br/>(AudioPlaybackCallback Evaluator)"]
    end

    subgraph DSP_Layer ["Digital Signal Processing (DSP) & Detection Engine"]
        VAD["VoiceActivityDetector<br/>(RMS, dB, Spectral Tilt, Dynamic Floor)"]
        KWDetector["TemplateKeywordDetector<br/>(Dynamic Time Warping + Filterbanks)"]
        MemoCoordinator["MemoTriggerDetector<br/>(Rolling Ring Buffer & State Machine)"]
        FFTEngine["FFT<br/>(Radix-2 Cooley-Tukey)"]
        WavOut["WavWriter<br/>(PCM 16-bit RIFF Wave Exporter)"]
    end

    subgraph Data_Layer ["Data Persistence & Storage Layer"]
        Repo["VoiceNoteRepository<br/>(Voice Note CRUD & Transcription Facade)"]
        RoomDB["AppDatabase (Room SQLite v4)"]
        VoiceNoteDao["VoiceNoteDao<br/>(voice_notes CRUD & Search)"]
        ErrorLogDao["ErrorLogDao<br/>(system_error_logs CRUD)"]
        DiskFiles["Local Filesystem Cache<br/>(context.filesDir/voice_notes/*.wav)"]
        ExportMgr["VoiceNoteExportManager<br/>(Storage Access Framework & ZipOutputStream)"]
    end

    MainActivity --> DashboardTab & HistoryTab & SettingsTab & GuideTab
    DashboardTab & HistoryTab & SettingsTab & GuideTab --> VoiceMemoViewModel
    VoiceMemoViewModel --> Repo
    VoiceMemoViewModel -.->|Observes Service State| Service
    Service --> InputMgr
    Service --> MediaDet
    Service --> MemoCoordinator
    MemoCoordinator --> VAD & KWDetector & WavOut
    Repo --> RoomDB
    RoomDB --> VoiceNoteDao & ErrorLogDao
```

---

## 3. Data Model

### Entity: `VoiceNote`
- `id`: Primary key (Int auto-generate)
- `title`: User-editable memo title (String)
- `timestamp`: Epoch millisecond timestamp of recording onset (Long)
- `durationSeconds`: Total duration in seconds (Double)
- `audioFilePath`: Absolute path to 16-bit PCM WAV file (String)
- `triggerType`: `AUTO_SOUND`, `KEYWORD_CIAO_LINDA`, or `MANUAL` (String)
- `transcriptionText`: Optional on-device speech-to-text transcript (String?)
- `transcriptionStatus`: `NONE`, `PENDING`, `COMPLETED`, or `FAILED` (String)
- `peakDb`: Maximum acoustic decibel level during note (Float)
- `avgDb`: Average acoustic decibel level during note (Float)
- `isFavorite`: Bookmark flag (Boolean)
- `fileSizeBytes`: Size on disk in bytes (Long)

---

## 4. Key Architectural Patterns & Safety

1. **Deterministic State Machine**: Continuous transitions between `IDLE`, `MONITORING`, `TRIGGER_DETECTED`, `RECORDING`, and `SAVED`.
2. **Pre-Trigger Buffering**: Rolling circular buffer ensures the start of speech is never truncated when sound exceeds the threshold.
3. **Keyword Spotting**: Local filterbanks + DTW allows fast, zero-cloud phrase detection.
4. **Resilient Audio Routing**: Automatic device failover to phone mic if external headsets disconnect.
5. **Foreground Service Compliance**: Proper `FOREGROUND_SERVICE_MICROPHONE` type and notification channel separation for Android 14+.
