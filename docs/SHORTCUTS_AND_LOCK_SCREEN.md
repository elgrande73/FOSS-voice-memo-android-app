# Recording controls: dashboard, notification, tile, widget, launcher shortcut, keyboard

Every control starts a memo, or stops the one being recorded, and ends in the same `AudioMonitoringService` requests
(there is no second recorder):
- **Start/stop request** (`ACTION_START_MANUAL_RECORDING` / `ACTION_STOP_MANUAL_RECORDING`): a repeated start never
  discards the memo in progress.
- **Toggle request** (`ACTION_TOGGLE_MANUAL_RECORDING`): at most one toggle per 800 ms, even if the keyboard, the tile,
  the widget and the notification are used at once.
- **Finishing the memo:** a stop uses the `USER` stop reason, keeps every captured frame and finalizes exactly once.
- **One source of truth:** the service's state (`isServiceRunning`, `isCurrentlyRecording`, `engineState`). Every change
  calls `RecordingEntryPoints.onStateChanged`, which refreshes the tile and all widgets (no polling).

## 1. What Android allows

| Mechanism | Works while… | Limits |
|---|---|---|
| Starting the microphone | an app screen is visible | Android 11+ grants microphone access to a foreground service only if it was started while the app was visible (Android 14 refuses to start it otherwise). |
| Tile, widget, notification while **monitoring runs** | always (the service already holds the microphone) | They toggle the memo through the running service, without opening the app. |
| Tile, widget, launcher shortcut while **monitoring is off** | — | They open Voice Memo with `ACTION_RECORD_MEMO`; the app starts the memo like Quick Memo and shows the dashboard with its Stop button. This is the only reliable compliant way to start the microphone from outside the app. |
| Quick Settings tile on the lock screen | Quick Settings | Some manufacturers require unlocking for all tiles. Voice Memo never shows app content over the lock screen: opening the app asks to unlock first. |
| Launcher shortcuts | Android 7.1+ (API 25) | Static shortcut: long-press the app icon, or drag "Record memo" to the home screen. Not available on Android 7.0. |
| Keyboard shortcut | Voice Memo is open and focused | Bluetooth/USB keyboards; nothing in the background. |

Removed (did not work reliably, see CHANGELOG): the volume-button shortcut and the accessibility service that extended
shortcuts to other apps. Their settings are dropped by settings migration v5; no accessibility service or permission
remains. No root, Shizuku or proprietary service is used. Tile and widget are protected by `BIND_QUICK_SETTINGS_TILE`
and the system widget host; the static shortcut targets the app's own launcher activity.

## 2. Quick Settings tile "Voice memo" (`RecordingTileService`)

- Add it: pull down Quick Settings, tap the edit (pencil) button and drag the tile in. Adding or refreshing the tile
  never records; only a tap does.
- States: **Recording** (active, label "Stop memo"), **Tap to record** (monitoring runs), **Tap to open and record**
  (monitoring off). The tile is never shown as active unless a memo is really being recorded.
- Tap while monitoring runs: toggles the memo. On the lock screen this works without unlocking only while
  *Recording controls in the notification and on the lock screen* is on; otherwise Android asks to unlock first.
- Tap while monitoring is off: opens the app (after unlocking, on the lock screen), which starts the memo; Android 14+
  uses `startActivityAndCollapse(PendingIntent)`, older versions the Intent variant.

## 3. Home-screen widget (`RecordingWidgetProvider`)

- One large button (at least 48 dp high, TalkBack description "Starts a memo" / "Stops the memo being recorded" /
  "Opens Voice Memo and starts a memo"). Original artwork; no images.
- Monitoring off: **Record memo** opens the app, which starts the memo.
- Monitoring on: **Record memo** starts a memo through the running service; **Stop memo** (red) stops it.
- `updatePeriodMillis` is 0: the widget is redrawn only when the state changes (no battery use while idle). After the
  process was killed, the next system update shows the real state ("Record memo", monitoring off).
- A stale widget toggle while monitoring has stopped records nothing in the background; the widget is redrawn instead.

## 4. Launcher shortcut "Record memo" (`res/xml/shortcuts.xml`)

Opens Voice Memo and starts a memo (a memo already being recorded is kept). Without the microphone permission, Android's
permission dialog is shown first and the memo starts once it is granted; if it is denied, a message explains why
nothing is recorded. The request is handled once: rotating the screen or recreating the activity does not start
another memo.

## 5. Keyboard shortcut (off by default)

- **Capture:** *Set Shortcut* waits for a key combination; Back or Esc cancels. Modifier keys alone are ignored while the
  user builds the combination.
- **Rejected combinations:** typing keys without Ctrl, Alt or Meta (F1–F12 and the Record media key are allowed alone);
  navigation, volume and power keys; Ctrl+A/C/V/X/Y/Z (also with Shift); Alt/Meta+Tab.
- **Triggering:** on the key-down of the main key, with exactly the saved modifiers. Repeats and the key-up are
  consumed, so holding the key does not toggle again. Events from on-screen keyboards never trigger. While a text field
  of Voice Memo has focus, only shortcuts with Ctrl, Alt or Meta fire. While Settings waits for a new shortcut, no
  shortcut fires. Volume keys are never consumed.
- **Stored keys:** `keyboardShortcutEnabled`, `keyboardShortcutKeyCode`, `keyboardShortcutModifiers` (bit mask Ctrl=1,
  Alt=2, Shift=4, Meta=8). Corrupt values fall back to "not set".

## 6. Recording controls in the notification and on the lock screen (on by default)

Setting key `lockScreenControlsEnabled` (unchanged, so saved choices are kept).

- **On:** while monitoring runs, its notification has a **Start memo** / **Stop memo** button with `VISIBILITY_PUBLIC`
  (only the app name, the status and the button; memo titles and transcripts are never on the lock screen). The button
  and the tile work without unlocking.
- **Off:** the notification has **no** Start/Stop button at all, locked or unlocked; it shows the status only, and on
  the lock screen just the app name (`VISIBILITY_PRIVATE` with a public version without buttons). The tile asks to
  unlock first. The dashboard, tile, widget, launcher shortcut, keyboard shortcut and wake phrase keep working; hiding
  the controls never stops monitoring or a memo in progress.
- **Applies at once:** Settings asks the running service to rebuild its notification (`ACTION_REFRESH_NOTIFICATION`);
  every rebuild reads the setting, so it also holds after a restart.

**Root cause of the earlier bug** (toggling had no visible effect, and *Start memo* stayed available once monitoring
started): the notification channel set `lockscreenVisibility = PUBLIC`. A channel-level value overrides the visibility of
every notification in the channel, so the private/public switch never took effect. With the setting off, the only other
change was `setAuthenticationRequired`, which exists only on Android 12+ and only matters while the device is locked;
unlocked, the button was always there. Fix: a new channel without a lock-screen override (the old channel is deleted;
channels cannot be changed after creation), and no button at all when the setting is off.

**Screen off:** recording continues safely, because the microphone foreground service and its partial wake lock keep
running. Manufacturer battery optimisation can still stop background services; see the in-app Guide.

**Process recreation:** if Android kills the process and restarts the monitoring service without a request, the
service stops itself (it may not reclaim the microphone from the background) instead of showing a monitoring
notification while nothing is captured; tile and widget then show monitoring as off.

## 7. Dashboard button

- **Look:** a 96 dp round button at the bottom centre of the dashboard, on an opaque bar above the navigation bar.
- **States:** start is primary colour with a dot and "Quick Memo"; stop is error colour with a square and "Stop Memo".
  TalkBack reads "Start quick memo" or "Stop memo and save", plus the state.

## 8. Verification

**Unit and Robolectric tests (build host):**
- `RecordingShortcutHandlerTest`: keyboard rules, repeats, debounce, volume keys never consumed, validation.
- `ShortcutActivityTest`: key events dispatched to the real activity; volume keys do nothing even with old settings.
- `ShortcutSettingsTest`, `SettingsMigratorTest` (v5 drops the volume-button settings, keeps the keyboard shortcut).
- `LockScreenControlsTest`: channel without override, button present/absent, immediate effect during a memo, restart
  without a request.
- `RecordingEntryPointsTest`: tile and widget states, placing/refreshing never records, stale widget toggle, record
  intent starts once through the service (and not again after recreation), permission prompt, launcher shortcut.

**Not verifiable without a device (no emulator or phone was available):**
1. Tile: add it, tap with monitoring off (app opens and records), on, while recording; on the lock screen with the
   setting on and off.
2. Widget on several launchers: placement, size, state changes from the app, tile and wake phrase.
3. Launcher shortcut on Android 7.1+ launchers; behaviour on Android 7.0 (no shortcuts).
4. Notification with the setting off: no button, locked and unlocked; with "show sensitive content" on and off.
5. Manufacturer variants (Samsung, Xiaomi, OnePlus, Pixel) for the lock screen and Quick Settings.
6. Keyboard shortcut with Bluetooth and USB keyboards.
