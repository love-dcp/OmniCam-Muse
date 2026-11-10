# X8U professional-video ISZ test

This note covers the 2026-11-06 PKJ110 candidate. Professional-video ISZ is validated with
stabilization off for 1080p30 and 4K30. It remains opt-in because stabilization-on and 60fps
are not part of the validated first-release matrix.

## Candidate

| file | size | MD5 |
| --- | ---: | --- |
| `module/payload_pkj110/lib64/libextensionlayer.so` | 1723576 | `6c3d0eb13f2e2dd0efa6e608b5d70b43` |
| `module/payload_pkj110/etc/camera/CameraHWConfiguration.config` | 274231 | `9aa9cf7c444e8ed418d2fe41561688ee` |

The library requires `m_modeDescriptor == 30` and a requested zoom no greater than `3.0`.
The configuration adds only the main-camera `0x830B` 2X-3X row. It intentionally has no
ultra-tele `0x830B` row, so 6X remains on the stock telephoto path. It also sets
`isDisable4K30VideoEIS = FALSE`; the previous candidate omitted this key and failed to activate
ISZ in the 4K30 professional-video session.

## Host commands

The helper uses the PKJ110 ADB binary and serial by default:

```powershell
pwsh -File tools\x8u_isz_lab.ps1 -Action Status
pwsh -File tools\x8u_isz_lab.ps1 -Action Apply
```

After `Apply`, open the camera once. The helper has already stopped the old camera and
provider so the next session loads the candidate.

While the isolated candidate is active, do not toggle “专业视频 ISZ” in the OmniCam
settings app. That control calls the installed module's `apply-now.sh` and may replace
the temporary candidate with older payload bytes. Use the lab script for this run.

Capture each screen while it is still on the requested zoom:

```powershell
pwsh -File tools\x8u_isz_lab.ps1 -Action Capture -Name pro_eis_off_2x
pwsh -File tools\x8u_isz_lab.ps1 -Action Capture -Name pro_eis_off_3x
pwsh -File tools\x8u_isz_lab.ps1 -Action Capture -Name pro_eis_off_6x
```

Captures go to `output/isz_test/` and contain the screenshot, UI hierarchy, and a cumulative
logcat snapshot with the relevant ISZ lines printed to the terminal.

Always restore the stock files after the run:

```powershell
pwsh -File tools\x8u_isz_lab.ps1 -Action Restore
pwsh -File tools\x8u_isz_lab.ps1 -Action Status
```

`Restore` requires these stock hashes and no remaining bind:

```text
6af236b1dbfae3629c86e8f956d33f77  /odm/lib64/libextensionlayer.so
470820672de033b9d8f8a93acb5aadc1  /odm/etc/camera/CameraHWConfiguration.config
```

## Device matrix

Do not start another camera run while the phone is low on power. Resume only after the battery
has recovered and the phone is confirmed to be charging, and keep the camera flash disabled for
the remaining tests.

Use professional video, 1080p30. Start with stabilization off because EIS can still pull the
professional-video FOV back to the full-frame path.

| mode | stabilization | zooms | observed 2026-11-06 | result |
| --- | --- | --- | --- | --- |
| professional video 1080p30 | off | 2X, 3X, 6X | ISZ, ISZ, telephoto | pass |
| professional video 1080p30 | on | 2X, 3X, 6X | not stable, not stable, telephoto | inconclusive |
| ordinary video 1080p30 | ordinary | 2X, 3X, 6X | no ISZ, no ISZ, telephoto | pass |
| ordinary video 1080p30 | super stabilization | 2X, 3X, 6X | no ISZ, no ISZ, telephoto | pass |
| professional video 4K30 | off | 2X, 3X, 6X | ISZ, ISZ, telephoto | pass |
| professional video 4K30 | on | 2X, 3X, 6X | not part of this run | pending |
| ordinary video 4K30 | ordinary | 2X, 3X, 6X | no ISZ, no ISZ, telephoto | pass |
| professional video 4K30 HDR | off | 2X, 3X | ISZ, ISZ | pass |

For professional video at 2X/3X, the decision log should contain all of:

```text
m_modeDescriptor:30
isTriggerIzoom 1
SensorMode:13
```

At 6X, `isTriggerIzoom` must be `0`; there must be no ISZ `SensorMode:9/23` and no
`RecoveryErr=1047`. Ordinary 4K30 reported `m_modeDescriptor:6` on this build and must not
enter ISZ. Older super-stabilization logs report descriptor `4`, so isolation is defined by the
native descriptor `30` gate rather than one ordinary-video descriptor value.

Also move directly from 2X or 3X to 6X and wait at least 30 seconds. The previous build could
leave ISZ active during that transition and trigger MCTFE `1047`.

The 1080p30 stabilization-off run produced the expected decision evidence. The 1080p30
stabilization-on run did not trigger consistently, likely because EIS changes the active
full-frame/FOV path; it still needs a clean repeat before being treated as a pass or failure.

Professional video 4K30 stabilization-off now reports the professional-video session and ISZ
decision:

```text
recorder profile 3840x2160@30
m_modeDescriptor:30
isVideoIzoomRange:1
isTriggerIzoom 1
bEnableInSensorZoomMode: 1
SensorMode: 13
Crop: {l 2048 t 1792 w 4096 h 2560}
```

Moving from 3X to 6X left ISZ and returned to the stock telephoto path. A 30-second observation
contained no MCTFE `RecoveryErr=1047`.

Ordinary 4K30 isolation was checked at 2X, 2X-to-3X, and 3X-to-6X. Every decision reported
`m_modeDescriptor:6`, `isVideoIzoomRange:0`, and `isTriggerIzoom 0`; the transition logs contained
no `SensorMode:13`, no `RecoveryErr`, and no `ErrorCode:1047`. The 6X state was observed for 10
seconds and continued to output `3840x2160`.

The candidate does not NOP the stock 60fps rejection, so 60fps is not part of this first matrix.
Professional-video stabilization-on also remains pending; do not describe it as validated.

## 4K30 SDR/HDR descriptor comparison

The standard 4K30 SDR path does not enter the candidate: while recording, the UI switches from
1X to 2X and 3X, but the native decision consistently reports descriptor `2`,
`isVideoIzoomRange:0`, and `isTriggerIzoom 0`. This is a descriptor mismatch, not a zoom-action
failure.

The same recording-time action was repeated with the standard professional-video `4K·30·HDR`
profile selected. HDR reaches the candidate gate and activates ISZ:

```text
m_modeDescriptor:30
isVideoIzoomRange:1
isTriggerIzoom 1
SensorMode: 13
Crop: {l 2048 t 1792 w 4096 h 2560}
HLG dataspace=0x12060000
```

The captured MP4 is a 3840x2160 HEVC Main 10 stream with `yuv420p10le`, BT.2020 primaries,
ARIB STD-B67/HLG transfer, and a 30fps container rate. All 489 video frames decode. The HDR logs
contain no `RecoveryErr` or `ErrorCode:1047`. Therefore the HDR format itself is supported by
the current candidate; standard SDR 4K30 is the unsupported descriptor-2 case.

## Evidence

| case | screenshot | log |
| --- | --- | --- |
| professional 4K30 2X | `output/isz_test/4k30_2x_gate5s.png` | `output/isz_test/4k30_2x_gate5s.log` |
| professional 4K30 3X | `output/isz_test/4k30_3x_gate4s.png` | `output/isz_test/4k30_3x_gate4s.log` |
| professional 4K30 3X-to-6X, 30s | `output/isz_test/4k30_6x_from3x_30s.png` | `output/isz_test/4k30_6x_from3x_30s.log` |
| ordinary 4K30 2X | `output/isz_test/ordinary_4k30_2x_isolated_gate.png` | `output/isz_test/ordinary_4k30_2x_isolated_gate.log` |
| ordinary 4K30 2X-to-3X | `output/isz_test/ordinary_4k30_2x_to_3x_gate.png` | `output/isz_test/ordinary_4k30_2x_to_3x_gate.log` |
| ordinary 4K30 3X-to-6X, 10s | `output/isz_test/ordinary_4k30_3x_to_6x_gate10s.png` | `output/isz_test/ordinary_4k30_3x_to_6x_gate10s.log` |
| professional 4K30 HDR 1X/2X/3X | `output/isz_test/module_4k30_hdr_1x_recording3.png`, `module_4k30_hdr_2x_recording3.png`, `module_4k30_hdr_3x_recording3.png` | `output/isz_test/module_4k30_hdr_recording3_before_stop.log` |
| professional 4K30 HDR MP4 | `output/isz_test/4k30_hdr_latest.mp4` | `output/isz_test/module_4k30_hdr_recording3_after_stop.log` |
