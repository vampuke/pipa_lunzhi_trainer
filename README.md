# 轮指大作战 (Pipa Lunzhi Trainer)

Android app for pipa practice. The display name is **轮指大作战**.

## Features

**基本功（分次定时训练）**
- Build a plan of training rounds before you start: each round has its own
  target speed (**metronome BPM**, 60 BPM = 5 strokes/s) and length (default
  2:00), and rounds can be added or removed at any time.
- A built-in lead-in counts **5 seconds** down, then round 1 starts; after each
  round the app rests **30 s** (adjustable) and moves on automatically.
- Guide click in 每拍一轮 (one click per 轮) or 每击一响 (one click per stroke),
  with the accent set to 强调首拍 or 每拍相同.
- **Remembers your last plan** and keeps up to 8 reusable configs (tap a chip to
  load, long-press to edit / duplicate / delete); configs are recorded
  automatically when a session starts.

**调音器**
- Pipa (D 调正调 A-d-e-a), guzheng (21 strings) and guitar (EADGBE) tunings.
- Note name + cents, per-string rows, reference tones.

**节拍器**
- 四分·整拍 / 二八·分拍 / 轮指（每拍 5 响）, accent switching, common tempo presets.

**名曲跟练**
- Preset pieces with section structure and a reference tempo; overall and
  per-section progress.
- Import your own scores as JSON, or build them in the visual score editor.

## Metrics — how to read them

- **BPM ↔ speed:** 轮指每拍 5 响，所以 音/秒 = BPM ÷ 12、音/分 = BPM × 5
  (60 BPM = 5 音/秒 = 300 音/分).

## Build

CI builds debug + release APKs on every push (see Actions → artifacts).
Release signing falls back to the debug key unless `KEYSTORE_PATH` and
related env vars are provided, so any branch produces an installable APK.

Local build (needs Android SDK + JDK 17):

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## Stack

- Kotlin, minSdk 24, targetSdk 34
- Pure-Kotlin audio (no native deps)
