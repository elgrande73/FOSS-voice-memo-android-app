# Microphone selection and routing

Settings → Microphone lets the user pick the phone microphone or a connected wired-headset, USB or Bluetooth headset
microphone. Monitoring (`AudioMonitoringService`) and the Settings phrase recording (`VoiceMemoViewModel.capturePhrase`)
open the microphone the same way, through `AndroidInputRouter`; the decisions are pure functions in `InputRouting`
(unit-tested). The format is unchanged everywhere: 16 kHz, mono, 16-bit PCM, read in 50 ms frames
(`PcmFrameAssembler`) and passed to the detector, the WAV writer and later Whisper.

## Root cause of "Bluetooth headset records silence"

Before 1.0.0, selecting a device only called `AudioRecord.setPreferredDevice()` on an `AudioSource.MIC` recorder. That
is enough for wired and USB microphones, but not for Bluetooth: a headset microphone only carries audio while the
headset's voice link (HFP/SCO, or LE audio) is active, the same link a phone call uses. Android lists the headset
microphone (`TYPE_BLUETOOTH_SCO`) as soon as the headset is connected, so it appeared in Settings, but nothing
activated the link, so the recorder received silence or Android used another microphone. Other defects found on the
way:

- "Phone microphone" set no preferred device, so a plugged-in wired or USB headset silently took over.
- Settings listed every input Android reported, including virtual ones (telephony, remote submix) and, on some
  phones, a headset-jack microphone with nothing plugged in.
- The selection was stored by device id only; Android gives a device a new id when it reconnects.
- The Settings phrase recording ignored the selection (samples could come from a different microphone than
  monitoring).
- Failures were invisible: the dashboard only appended "(Default)".

## How each input is opened

| Selection | Routing | Audio source |
|---|---|---|
| Phone microphone | `setPreferredDevice(built-in mic)`, so headsets and USB microphones do not take over | `MIC` |
| Wired headset | `setPreferredDevice` (offered only while a wired headset is plugged in, judged by its output) | `MIC` |
| USB microphone / interface | `setPreferredDevice`; Android resamples to 16 kHz mono | `MIC` |
| Bluetooth headset (SCO, LE audio headset) | Voice link activated first, then `setPreferredDevice` | `VOICE_RECOGNITION` |

Bluetooth voice link:
- **Android 12+ (API 31+)**: `AudioManager.setCommunicationDevice()` with the headset's entry from
  `getAvailableCommunicationDevices()` (matched by type and address), confirmed by
  `OnCommunicationDeviceChangedListener`; released with `clearCommunicationDevice()`.
- **Android 7–11 (API 24–30)**: `startBluetoothSco()` (only if `isBluetoothScoAvailableOffCall`), confirmed by the
  `ACTION_SCO_AUDIO_STATE_UPDATED` broadcast reaching `SCO_AUDIO_STATE_CONNECTED`, then `setBluetoothScoOn(true)`;
  released with `setBluetoothScoOn(false)` + `stopBluetoothSco()`.
- Both wait for Android's event, with a 4 s timeout; no fixed sleeps. The audio mode is never changed. The router
  releases only a link it activated itself.
- A Bluetooth device that only plays audio (A2DP, BLE speaker) is not a microphone and is not listed.

Side effect users must know (also in Settings): while Voice Memo listens through a Bluetooth headset, the headset is in
call mode: narrower audio, music on it sounds like a call, and its battery drains faster.

## Verification and errors

After `startRecording()` the router compares `AudioRecord.getRoutedDevice()` with the plan
(`InputRouting.routedAsPlanned`); the dashboard shows the microphone Android reports, in red when it is not the
selected one, with the reason:

| Situation | Behaviour |
|---|---|
| Selected device not connected | Phone microphone; "… is not connected; using the phone microphone." Settings shows it as "(not connected)". |
| Bluetooth link not activated (timeout, refused, no call profile) | Phone microphone; message asks to check the headset's call connection. Not retried on every device event; retried when monitoring is started again or the device reconnects. |
| Android routes elsewhere | Message names the microphone actually used. |
| Stream is digital silence for 2 s (`InputSignalCheck`: all samples within ±2) | "No sound from …" message. A real microphone always picks up some noise, so this does not fire in a quiet room; a non-silent stream does not prove the right microphone is used. |
| Device connected/disconnected | `AudioDeviceCallback` re-plans; if the device to use changed, capture restarts on the new route, during a memo only after the memo is saved. |
| Route changes during a stream | `AudioRecord` routing listener updates the dashboard. |
| Read errors / dead stream | Existing behaviour: the memo is saved, capture restarts. |
| Permission missing | Monitoring reports the microphone unavailable with a message. |

A stream restart waits for the previous stream to release its recorder and Bluetooth link. Stopping monitoring
releases both.

## Diagnostics

Debug builds log to the `InputRouter` tag: the listed inputs (name, type, id), the plan, `setPreferredDevice` result,
Bluetooth link events, the preferred and routed device after start. Release builds log only failures (`ErrorLog`,
including "Input delivers digital silence"). No audio or transcript is ever logged.

## Permissions

Unchanged: `RECORD_AUDIO`; `MODIFY_AUDIO_SETTINGS` (audio routing, Bluetooth link); `BLUETOOTH` up to Android 11 and
`BLUETOOTH_CONNECT` from Android 12 (Bluetooth headsets; already requested at start). USB audio needs no permission
through Android's audio framework.

## Tests and what remains to be checked on hardware

Automated (build host): `InputRoutingTest` (device kinds, list filtering, resolution after reconnect, fallback,
switching, routed-device check, silence check) and `AndroidInputRouterTest` (Robolectric audio service: listing,
communication-device activation/refusal/release on Android 12+, SCO activation and release on Android 11, fallback
when the link fails, release on close). These check the calls the app makes, not that real hardware delivers audio.

Not yet verified on devices (no phone or headset was available when this was written). For each, start monitoring,
watch the dashboard microphone line and the live level, record a memo and play it back:
1. Phone microphone, with and without a wired/USB headset plugged in (must stay on the phone).
2. Wired headset with microphone; plug and unplug during monitoring and during a memo.
3. USB microphone or audio interface; connect/disconnect.
4. Bluetooth headset (HFP) on Android 12+ and on Android 7–11: connected before start, connected after start,
   disconnected during a memo, reconnected; a headset without call support; LE audio headset if available.
5. Settings → Record Phrase with each microphone (samples should come from the selected microphone).
