# Firebase Performance Monitoring for App Startup & Gemini API Latency

Integrates Firebase Performance Monitoring alongside a unified in-app telemetry tracker to measure cold/warm startup times (`onCreate` to first Compose frame) and end-to-end Gemini API latency (Time to First Token, total streaming duration, audio chunk throughput, and transcription speed), surfacing real-time metrics both to the Firebase Performance backend and inside the in-app Control Sheet.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following design and telemetry choices incorporate your confirmed selections from the clarification step:

- **Confirmed Decision 1 — Gemini API Trace Granularity**: Custom performance traces will capture **Time to First Token (TTFT)**, **total request-to-completion duration**, and **streamed audio/text chunk counts** (plus custom attributes for model name, voice modality, and failover status).
- **Confirmed Decision 2 — App Startup Trace Scope**: In addition to Firebase Performance Monitoring's automatic cold/warm start instrumentation, a custom startup trace will measure the exact window from **Activity `onCreate` entry to the first rendered Jetpack Compose frame**.
- **Confirmed Decision 3 — In-App Telemetry Visibility**: A live **Performance & Latency Telemetry** card will be added inside the **Gemini Control & Settings Sheet** so you can inspect startup timing, latest and rolling average TTFT, total stream duration, and audio chunk counters directly in the app.

---

## 1. Overview & Core Concept

- **What It Does**: Instruments the application's startup lifecycle and Gemini AI communication layer with Firebase Performance Monitoring (`FirebasePerformance`) custom code traces while mirroring those measurements into a reactive in-app telemetry state flow.
- **Target Audience / Persona**: Power users and developers using Gemini Wake for hands-free voice interactions who want immediate visibility into wake-to-first-frame responsiveness and Gemini neural voice/text latency.
- **Key Value**: Eliminates guesswork around whether latency comes from app startup, network/TTFT delay, or multi-chunk audio streaming, while automatically exporting production traces and metrics to the Firebase Console.

---

## 2. User Experience & Visual Design

- **Key User Flows**:
  1. **App Launch & First Frame**: When the app starts, a startup trace begins immediately in `onCreate` and automatically stops once the initial Compose frame is drawn. The measured duration (in milliseconds) is dispatched to Firebase Performance and recorded in the live telemetry store.
  2. **Gemini Chat & Voice Streaming**: When a user sends a text or voice turn, a trace starts for the Gemini request. As the first streamed chunk (text or Orion PCM audio) arrives, Time to First Token (TTFT) is recorded as a custom metric. When the stream finishes, total duration, text chunk count, and audio chunk count are finalized and pushed to both Firebase Performance and the live telemetry state.
  3. **Live Inspection in Control Sheet**: Opening the **Gemini Control & Settings** sheet reveals a dedicated **Performance & Latency Telemetry** section under Session Telemetry, displaying startup latency, latest TTFT, average TTFT, total stream duration, and a button to reset session counters.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: High-contrast technical telemetry panel that matches Gemini Wake's existing Material 3 surface hierarchy and adaptive theme modes (`System`, `Light`, `Dark`).
  - *Color Palette & Mood*: Uses semantic Material 3 tokens (`MaterialTheme.colorScheme.surfaceVariant`, `primary`, `secondary`, and `tertiary`) with status color coding (fast/nominal vs. elevated latency badges) and subtle tonal elevation.
  - *Typography & Hierarchy*: Crisp tabular numeric readouts (`titleMedium` bold values in `ms`) paired with compact uppercase metric labels (`labelSmall` with `0.5.sp` letter spacing).
  - *Component Styling & Layout*: A structured 2x2 metric grid inside a rounded M3 card (`16.dp` corner radius) showing **App Startup (`ms`)**, **Latest TTFT (`ms`)**, **Last Stream Total (`ms`)**, and **Audio / Text Chunks**, accompanied by a trace history summary pill and a clear/reset action with a minimum `48.dp` touch target.
- **Interactive Feedback & Motion**:
  - Smooth animated state updates as new Gemini turns complete, a live "Tracing active request..." status badge while a stream is in flight, and an interactive **Reset Telemetry** button with ripple feedback.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1: Dual Instrumentation (Firebase SDK Traces + In-App Reactive Snapshot)**
  - *Chosen Approach*: Wrap `FirebasePerformance.getInstance().newTrace(...)` inside a dedicated performance tracker manager that simultaneously records Firebase native trace metrics/attributes and updates a `StateFlow` snapshot for the UI and local JVM unit tests.
  - *Why*: Firebase Performance `Trace` objects batch and upload data asynchronously to the cloud and do not expose getters for previously recorded durations in the UI. Maintaining a lightweight reactive snapshot alongside the SDK trace guarantees zero-latency UI observability and deterministic unit testability.
  - *Alternatives Considered*: Relying solely on Firebase Console logs (rejected because the user requested live in-app telemetry in the Control Sheet).
- **Decision 2: Safe Dependency & Fallback Handling**
  - *Chosen Approach*: Validate the Firebase Performance library against Google Play Creator Program rules and guard `FirebasePerformance` calls behind safe initialization checks so offline or unit-test environments never crash if `FirebaseApp` is not active.
  - *Why*: Preserves 100% compatibility with Robolectric unit tests and offline launches while delivering full cloud trace reporting in production and emulator runs.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

- **Architecture & Component Diagram**:
  ```
  ┌──────────────────────────────────────────────────────────────────────┐
  │                        Activity Startup Flow                         │
  │   onCreate() ──► startStartupTrace()                                 │
  │   First Compose Frame (withFrameNanos) ──► stopStartupTrace()        │
  └──────────────────────────────────┬───────────────────────────────────┘
                                     │
                                     ▼
  ┌──────────────────────────────────────────────────────────────────────┐
  │                   Performance Telemetry Manager                      │
  │  • Creates & manages FirebasePerformance Trace instances             │
  │  • Records TTFT (ttft_ms), total_duration_ms, audio_chunks,          │
  │    text_chunks, and model/modality attributes                        │
  │  • Publishes immutable PerformanceTelemetrySnapshot via StateFlow    │
  └───────────────┬──────────────────────────────────────┬───────────────┘
                  │                                      │
                  ▼                                      ▼
  ┌───────────────────────────────┐      ┌───────────────────────────────┐
  │   Firebase Performance SDK    │      │   Gemini Control Sheet UI     │
  │  • Trace("app_cold_startup")  │      │  • Startup Time (ms) badge    │
  │  • Trace("gemini_chat_stream")│      │  • Latest & Avg TTFT (ms)     │
  │  • Trace("gemini_transcribe") │      │  • Total Stream Duration (ms) │
  │  • Cloud Console Reporting    │      │  • Audio Chunk Counter & Reset│
  └───────────────────────────────┘      └───────────────────────────────┘
  ```

- **Data Model & State**:
  - **Performance Telemetry Snapshot**: Holds `startupDurationMs: Long?`, `isStartupTraceCompleted: Boolean`, `isRequestInFlight: Boolean`, `lastTtftMs: Long?`, `averageTtftMs: Long?`, `lastTotalDurationMs: Long?`, `lastAudioChunkCount: Int`, `lastTextChunkCount: Int`, `lastTranscribeDurationMs: Long?`, `totalTrackedRequests: Int`, and `lastModelName: String`.
  - **Firebase Trace Attributes & Metrics**:
    - `app_startup_first_frame`: metric `startup_first_frame_ms`, attribute `launch_mode` (`cold_or_warm`).
    - `gemini_chat_stream`: metrics `ttft_ms`, `total_duration_ms`, `audio_chunks`, `text_chunks`; attributes `model`, `request_audio`, `has_attachment`, `status` (`success` / `fallback_text` / `error`).
    - `gemini_audio_transcribe`: metric `transcribe_duration_ms`, `audio_bytes`; attributes `model`, `mime_type`, `status`.
- **Interactive Component & State Mapping**:
  - **First-Frame Hook**: A `LaunchedEffect(Unit)` using `withFrameNanos` inside the root Compose content completes the startup trace on the first rendered frame and updates `startupDurationMs`.
  - **Streaming Interceptor**: Inside the Gemini streaming flow, starting the flow opens a trace Session; the first emitted audio or text chunk records `ttft_ms`; each emitted chunk increments the respective audio/text counter; flow completion or error stops the trace and updates the reactive telemetry state.
  - **Control Sheet Controls**: The telemetry card displays live metrics, highlights when a Gemini stream is actively being traced, and provides a `Reset Telemetry` button that clears session averages while preserving the initial startup measurement.
