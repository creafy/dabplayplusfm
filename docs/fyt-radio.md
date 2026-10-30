# FYT head units: the built-in FM tuner (DABplay+ FM)

FYT head units (UIS7862 and others; here the EKIY T900, platform 6315, Android 10) have an FM tuner behind the MCU
(TDA7708). Their MainServer `com.syu.ms` (runs as `android.uid.system`) drives it; the stock radio app `com.syu.radio`
is only a UI. The tuner's audio goes from the tuner to the amplifier directly: an app picks the radio as the audio
source and controls the tuner, no audio passes through Android.

Described from the stock APKs (MainServer 25.0714, radio app of 2025-07-16; jadx). No FYT code is used: `:fyt-radio`
writes the Binder calls out by hand. **[hypothesis]** until the FM test (Settings → FM test) has run on the head unit:
that the MainServer accepts calls from another app (its service has no permission and no caller check in its code).

## Connection
- Explicit bind: package `com.syu.ms`, class `app.ToolkitService` (the stock app does exactly this). Android 11+ needs
  `<queries><package android:name="com.syu.ms"/></queries>`.
- The binder is `com.syu.ipc.IRemoteToolkit`: transaction 1 `getRemoteModule(int code)` → a strong binder
  (`com.syu.ipc.IRemoteModule`). Module codes: 0 main, 1 radio (2 BT, 4 sound, 7 canbus, 10 steering wheel, ...).
- `IRemoteModule`, all with the interface token first:
  - 1 `cmd(int code, int[] ints, float[] flts, String[] strs)`, **one-way**
  - 2 `get(int code, int[] ints, float[] flts, String[] strs)` → `writeNoException`, then int 1 + int[] + float[] +
    String[] (or int 0 for none)
  - 3 `register(IModuleCallback cb, int updateCode, int flag)`, one-way; the stock app sends flag 1
  - 4 `unregister(IModuleCallback cb, int updateCode)`, one-way
- `com.syu.ipc.IModuleCallback`: transaction 1 `update(int code, int[] ints, float[] flts, String[] strs)`, one-way.

## Audio source (main module)
- `cmd(0, [1])` asks for the radio (APP_ID_RADIO 1); main update 0 / `get(0)` is the current source. The stock app asks
  again after 300 ms and then every second until update 0 says 1, and also takes Android audio focus (music, gain).
- Leaving: `cmd(0, [0])`, only while the source is still 1.
- Other ids: 0 none, 2 BT phone, 3 BT audio, 8 audio player, 9 video player, 10 third-party player, 11 car radio.

## Radio module: commands
| Code | Meaning | ints |
|---|---|---|
| 3 / 4 | frequency one step up / down | – |
| 5 / 6 | seek up / down | – |
| 7 / 8 | select preset / store on preset | FM: 65536 + index |
| 11 | band | -3 FM, -2 AM, -1 next |
| 12 | region | 0 USA, 1 Latin America, 2 Europe, 3 OIRT, 4 Japan |
| 13 | set frequency | `[3, freq]` |
| 15 / 16 | auto sensitivity / RDS | 0/1 |
| 17 / 18 | stereo / local | 2 = toggle |
| 19 / 20 / 21 | TA / AF / PTY | 2 = toggle |
| 22 | auto store | 2 |
| 25 | tuner power | – |

Frequencies: FM in 10 kHz (8750 = 87.50 MHz), AM in kHz.

## Radio module: updates
0 band, 1 frequency, 2 region, 3 preset, 4 preset frequency `[ch, freq]`, 5 PTY, 6 AF, 7 TA active, 8 TP, 9 TA,
13 radio text (`strs[0]`), 14 preset name, 15 RDS, 16 range `[min, max, step, steps]`, 20 scanning, 21 stereo,
22 seek state (0 none, 1 auto, 2 up, 3 down), 23 local, 26 PS name (`strs[0]`), 27 tuner on, 29 AM supported.
No RDS PI and no signal level in this set.

## Tuner type
`getprop sys.fyt.radio_type` (default 1): 1 = MCU tuner run by the MainServer (this interface). 3 / 4 = a tuner chip
driven by the radio app itself (MTK FM, "Sofia"); then this interface alone doesn't play FM.
