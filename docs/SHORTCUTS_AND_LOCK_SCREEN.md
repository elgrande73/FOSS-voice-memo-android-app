# Recording controls: dashboard button, shortcuts and lock screen

Each control starts a memo, or stops the one being recorded. All controls end up in `AudioMonitoringService`:
- **Start/stop request** (`ACTION_START_MANUAL_RECORDING` / `ACTION_STOP_MANUAL_RECORDING`): a repeated start never discards the memo in progress.
- **Toggle request** (`ACTION_TOGGLE_MANUAL_RECORDING`): at most one toggle per 800 ms, even if a key, the tile and the notification are used at once.
- **Finishing the memo:** a stop goes through the existing path. It uses the `USER` stop reason, keeps every captured frame, and finalizes exactly once.

## 1. What Android allows (investigated before implementing)

| Mechanism | Works while… | Limits |
|---|---|---|
| Key events in the activity (`dispatchKeyEvent`) | Voice Memo is open and focused | Nothing in the background |
| Accessibility service with `flagRequestFilterKeyEvents` | Screen **on**: any app, including the lock screen | The user must enable it in Android's accessibility settings. It gets no key events while the screen is off. |
| Volume keys while the screen is off | — | Android's input policy handles them itself (volume, media) and does not pass them to apps or accessibility services. The only route is a `MediaSession` with remote volume handling that reports media *playback*. **Rejected:** it would claim to play media, take over the volume buttons system-wide while monitoring, and its behaviour differs between manufacturers. |
| Both volume buttons held | App or accessibility service (screen on) | Android 8+ uses *hold both volume buttons for 3 s* as the accessibility shortcut. Android 12+ may intercept the combination before apps see it when that shortcut is configured. Voice Memo fires after 0.6 s; *Hold Volume Down* or *Hold Volume Up* avoids the conflict. |
| Power-button combinations | — | Reserved by the system (screenshot, assistant, emergency, power menu). Not available to apps. |
| Notification action | Notification visible, including the lock screen | Shown on the lock screen only if the user's lock-screen notification settings show it. Silent notifications are hidden on many lock screens, so the monitoring notification uses a *default-importance* channel with no sound or vibration. With "hide sensitive content", only the public version is shown. |
| Quick Settings tile (`TileService`) | Quick Settings, including the lock screen | Some manufacturers require unlocking for all tiles. |
| Starting the microphone from the background | — | Android 11+ grants microphone access to a foreground service only if it was started while the app was visible (Android 14 refuses to start it otherwise). So background controls (accessibility service, tile, notification) record **only while monitoring is running**: its foreground service already holds the microphone. Otherwise they do nothing; the accessibility service shows a short message, and the tile is shown as unavailable ("Monitoring off"). |
| External keyboards (Bluetooth/USB) | Activity, or accessibility service (screen on) | Some system shortcuts (for example Meta+…, Alt+Tab) are taken by Android before apps see them. |

No root, Shizuku or proprietary service is used. No permission was added: accessibility and tile services are protected by `BIND_*` permissions that only the system holds.

## 2. Volume-button shortcut (Settings → Shortcuts & Lock Screen; off by default)

- **Hold both volume buttons** (default gesture):
  - Both keys must go down within 200 ms.
  - The memo starts after both have been held for 600 ms.
  - The volume does not change.
  - If a single key is not joined by the other within 200 ms, it is replayed as a normal volume step, then once per key repeat. Holding to change the volume keeps working, with up to 200 ms delay.
  - A short press is replayed on release.
- **Hold Volume Down / Hold Volume Up:**
  - The chosen key held for 1 s toggles recording.
  - A shorter press is replayed as one volume step on release.
  - The other key is not touched.
- **Accidental triggers and repeats:**
  - A gesture fires once per press. Nothing fires again until all involved keys are released.
  - Key repeats never re-fire, and toggles are at least 800 ms apart.
  - Releasing a chord early cancels it without changing the volume.
- **Volume replay** uses `AudioManager.adjustSuggestedStreamVolume` with the system volume panel. Android picks the stream (call, media, ring), as for a normal press.

## 3. Keyboard shortcut (off by default)

- **Capture:** *Set Shortcut* waits for a key combination; Back or Esc cancels. Modifier keys alone are ignored while the user builds the combination.
- **Rejected combinations:**
  - typing keys without Ctrl, Alt or Meta (F1–F12 and the Record media key are allowed alone)
  - navigation, volume and power keys
  - Ctrl+A/C/V/X/Y/Z (also with Shift)
  - Alt/Meta+Tab
- **Triggering:**
  - Fires on the key-down of the main key, with exactly the saved modifiers. Repeats and the key-up are consumed, so holding the key does not toggle again.
  - Events from on-screen keyboards (virtual devices) never trigger.
  - While a text field of Voice Memo has focus, only shortcuts with Ctrl, Alt or Meta fire.
  - While Settings waits for a new shortcut, no shortcut fires.
- **Stored keys:** `keyboardShortcutEnabled`, `keyboardShortcutKeyCode`, `keyboardShortcutModifiers` (bit mask Ctrl=1, Alt=2, Shift=4, Meta=8). Corrupt values fall back to "not set".

## 4. Outside the app: optional accessibility service

"Voice Memo recording shortcuts" (`RecordingShortcutAccessibilityService`):
- **Enabling it:** the user turns it on in Android's accessibility settings; Settings has a button that opens them.
- **What it can see:** it requests no accessibility events and cannot retrieve window content (`canRetrieveWindowContent=false`). It sees only key events, and consumes only the keys of a configured shortcut.
- **When it works:** in other apps and on the lock screen while the screen is on.
- **When Voice Memo is open:** it calls the app's own toggle, which can also start monitoring, so the behaviour is the same as inside the app.

## 5. Lock-screen controls (on by default; Settings → Recording controls on the lock screen)

**While monitoring runs:**
- **Notification:**
  - It shows **Start memo** or **Stop memo** and has `VISIBILITY_PUBLIC`.
  - Its content is only the app name, the status and the button. Memo titles and transcripts are never on the lock screen.
  - The action runs without unlocking. Memos cannot be played or opened without unlocking.
- **Quick Settings tile "Voice memo":** shows *Recording*, *Tap to record* or *Monitoring off* (unavailable).

**With the setting off:**
- **Notification:** the action is marked `setAuthenticationRequired(true)`, so Android 12+ asks for the device to be unlocked first. The notification is `VISIBILITY_PRIVATE`, with a public version that has no buttons.
- **Tile:** it uses `unlockAndRun`.

**Screen off:** recording continues safely, because the microphone foreground service and its partial wake lock keep running. Manufacturer battery optimisation can still stop background services; see the in-app Guide.

**Security:** device security is never bypassed. No activity is shown over the lock screen, and nothing reads or plays memos while the device is locked.

## 6. Dashboard button

- **Look:** a 96 dp round button at the bottom centre of the dashboard, on an opaque bar above the navigation bar. The Scaffold insets handle gesture and 3-button navigation.
- **States:** start is primary colour with a dot and "Quick Memo"; stop is error colour with a square and "Stop Memo". TalkBack reads "Start quick memo" or "Stop memo and save", plus the state ("Recording" / "Not recording").
- **Layout:**
  - The scrolling content reserves the bar's height, so nothing is hidden behind it.
  - The Start/Stop Monitoring button stays at the top, far from the record button.

## 7. Verification

**Unit and Robolectric tests:**
- `RecordingShortcutHandlerTest`: gesture timing, volume replay, repeats, cancel, debounce, keyboard rules, validation.
- `ShortcutActivityTest`: key events dispatched to the real activity, with the hold timer on the main looper.
- `ShortcutSettingsTest`: persistence, corrupt values, capture, dashboard button geometry and state, repeated start.
- `LockScreenControlsTest`: notification channel, visibility, actions and unlock requirement.

**Not verifiable without hardware, so test on devices:**
1. *Hold both volume buttons* with Voice Memo open, with another app open (accessibility service on), and on the lock screen with the screen on. Repeat with Android's accessibility shortcut configured.
2. Short and long volume presses still change the volume, with the volume panel shown.
3. A Bluetooth and a USB keyboard: the shortcut in the app, while typing in Search, and in another app.
4. With the screen locked:
   - The notification button and the tile start and stop a memo; with the setting off, Android asks to unlock.
   - Check with the lock-screen setting "show sensitive content" both on and off.
5. Record a memo of a few minutes with the screen off, then stop it from the lock screen.
6. Manufacturer variants (Samsung, Xiaomi, OnePlus, Pixel): notification on the lock screen and tile behaviour while locked.
