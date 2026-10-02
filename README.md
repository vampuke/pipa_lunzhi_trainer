# Pipa Lunzhi Trainer (琵琶轮指练习分析)

An Android app to analyze pipa **lunzhi** (tremolo / 轮指) practice — either from
a recorded audio file or live from the microphone.

## Features

**File analysis mode**
- Pick any audio file (m4a / mp3 / wav / ogg — anything the device can decode).
- Runs the same pipeline as the reference Python analysis:
  - spectral-flux onset detection (FFT win=1024, hop=128)
  - adaptive peak-picking → stroke onsets
  - strokes/sec, strokes/min, roll-cycles/sec (5-stroke)
  - evenness via inter-onset-interval **coefficient of variation (CV)**
  - per-position loudness via 5-stroke fold (finger-strength profile)
- Charts: onset envelope with stroke markers, IOI-over-time scatter, per-position loudness bars.
- Graded report (A/B/C/D) with concrete advice.

**Live microphone mode**
- Streaming `AudioRecord` capture at 44.1 kHz, 100 ms blocks.
- Real-time rolling **strokes/min**, **evenness CV**, stroke count, and a
  live loudness bar.
- Tap **Stop** to get a full-session evaluation (same metrics + finger profile).

**Interval training (指力训练)**
- Build a plan of training rounds before you start: each round has its own
  target speed (音/秒) and length (default 2:00), and rounds can be added or
  removed at any time.
- A built-in lead-in counts **5 seconds** down, then round 1 starts; after each
  round the app rests **30 s** (adjustable) and moves on automatically.
- Guide click in 每拍一轮 (one click per 轮) or 每击一响 (one click per stroke,
  i.e. at the target stroke rate).
- With the microphone on, the live readout shows your *actual* strokes/sec and
  evenness during each round, and a per-round summary when the session ends.

## Metrics — how to read them

- **CV (evenness):** lower is better. ≤0.12 A (near-professional), ≤0.20 B,
  ≤0.30 C, >0.30 D.
- **Finger profile:** if position-to-position spread is < ~1.2×, unevenness is
  mostly random stroke-to-stroke; a persistently weak position points at a
  weak finger (typically ring/little for late positions).

## Build

CI builds debug + release APKs on every push (see Actions → artifacts).
Release signing falls back to the debug key unless `KEYSTORE_PATH` and
related env vars are provided, so any branch produces an installable APK.

Local build (needs Android SDK + JDK 17):

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest   # DSP validation on synthetic click trains
```

## Stack

- Kotlin, minSdk 24, targetSdk 34
- Pure-Kotlin FFT + DSP (no native deps)
- MPAndroidChart for visualization
