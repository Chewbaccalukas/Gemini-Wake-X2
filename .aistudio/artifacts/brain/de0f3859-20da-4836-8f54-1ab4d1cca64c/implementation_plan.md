# Implementation Plan: Per-Chat Custom Greetings, Voice Sleep Mode & Room/Test Fixes

## 1. Per-Chat Custom Greeting Message
- **Room Entity & Migration (`ChatSession.kt`, `AppDatabase.kt`, `FirebaseSyncRepository.kt`)**:
  - Add `@ColumnInfo(name = "greetingMessage", defaultValue = "") val greetingMessage: String = ""` to `ChatSession`.
  - Increment `AppDatabase` schema version from `15` to `16` and register `MIGRATION_15_16` (`ALTER TABLE chat_sessions ADD COLUMN greetingMessage TEXT NOT NULL DEFAULT ''`).
  - Include `greetingMessage` in `FirebaseSyncRepository` Firestore serialization/deserialization so custom greetings sync across devices.
- **Create & Edit Chat Configuration (`CreateEditChatDialog.kt`)**:
  - Add a **Custom Greeting Message** input field (`Modifier.testTag("chat_greeting_input")`) with example placeholder (`"e.g. Good morning, team! Here's what's on the agenda today."`), allowing users to set, edit, or clear a greeting for any chat session.
- **Pinned Greeting Banner & Optional TTS Readout (`ChatDetailScreen.kt`, `HomeScreen.kt`, `MainViewModel.kt`)**:
  - Render a **Pinned Greeting Banner** (`Modifier.testTag("chat_greeting_banner")`) at the top of `ChatDetailScreen` whenever `chat.greetingMessage.isNotBlank()`, styled with the chat's theme accent color and icon, a one-tap TTS speaker button (`onSpeakText(chat.greetingMessage)`), and a quick-edit affordance.
  - Display a subtle greeting preview badge on `HomeScreen` chat cards when a greeting is configured.
  - When a chat is woken via its wake word (with no immediate follow-up query) and `isAutoSpeakEnabled` is enabled, automatically read the chat's custom greeting aloud via TTS.

## 2. Voice Sleep Command ("Gemini, sleep") & Wake-Up Controls
- **Voice Command Detection (`AudioCommandSanitizer.kt`, `WakeWordManager.kt`, `MainViewModel.kt`)**:
  - Support `"Gemini, sleep"` (and natural variations like `"hey gemini sleep"`, `"go to sleep"`, `"sleep mode"`) to immediately transition the listener into **Sleep Mode**, suppressing all chat wake words and hands-free dictation triggers.
  - Expose `isSleepMode: StateFlow<Boolean>`, `sleepModeReason: StateFlow<String>`, `enterSleepMode(...)`, `exitSleepMode(...)`, and `evaluateVoiceSleepOrWakeCommand(transcript: String)` on `WakeWordManager` and synchronize with `MainViewModel` and `WakeWordVoiceService`.
  - While in Sleep Mode during Hands-Free mode, ignore all chat wake words and only accept `"Gemini, wake up"` (or a UI tap) to exit Sleep Mode and resume active wake word listening.
- **Visual Feedback & Tap-to-Wake UI (`HomeScreen.kt`, `ChatDetailScreen.kt`, `WakeWordRecordingScreen.kt`)**:
  - Display a prominent **Sleep Mode Visual Banner** (`SleepModeVisualBanner`) across `HomeScreen`, `ChatDetailScreen`, and `WakeWordRecordingScreen` showing why sleep mode was entered (e.g., `Voice command: "Gemini, sleep"`), a clear sleeping status indicator, and a one-tap **Wake Up** button (`testTag("wake_up_from_sleep_button")`) alongside a header Sleep/Wake toggle chip.

## 3. Room Persistence, `syncStatus` & Test Fixes
- **Fix `SharedFlow` Test Deadlock (`WakeWordRoomPersistenceTest.kt`)**:
  - Migrate tests to `runTest`, subscribe to `wakeManager.triggerEvents` using `backgroundScope.launch(start = CoroutineStart.UNDISPATCHED)` *before* invoking `wakeManager.onKeywordHit(...)`, and assert on the collected `WakeWordTriggerEvent` emissions (`matchedWord`, `chatId`, `chatTitle`, `initialQuery`).
- **Reactive Room `Flow` Assertions**:
  - Use `wakeRepo.chatWakeWords.first()` and `wakeRepo.allWakeWords.first()` inside `runTest` with `.allowMainThreadQueries()` on the in-memory Room builder.
- **Preserve Cloud `syncStatus` on Updates (`WakeWordDao.kt`, `ChatDao.kt`, `WakeWordRepository.kt`, `WakeWordManager.kt`)**:
  - Ensure all SQL `UPDATE` queries and Kotlin `@Transaction`/repository update paths preserve un-synced statuses (`CASE WHEN syncStatus = 'SYNCED' THEN 'DIRTY' ELSE syncStatus END`).
- **Method Signatures & Warning Clean-up**:
  - Add `createChatAndBindWakeWord(chatTitle: String, phrase: String, sensitivity: Int, ...)` overload on `WakeWordRepository` and `WakeWordManager`.
  - Clean up redundant nullability checks (`sample != null`) and retain `@get:JvmName("allMessagesFlow")` on `MessageRepository.allMessages`.

## 4. Verification
- Run unit/Robolectric tests in `WakeWordRoomPersistenceTest` and `HybridRoomSyncDatabaseTest` (including tests for custom chat greetings and `"Gemini, sleep"` / `"Gemini, wake up"` transitions).
- Run `compile_applet` to confirm zero compilation errors or warnings.
