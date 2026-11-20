#!/usr/bin/env python3
"""Generates the looping lamp hum (assets/backrooms/sounds/hum.ogg). Stdlib only; ffmpeg encodes.

Every frequency and LFO rate is a whole number of cycles per loop, so the last sample flows
into the first without a click and without a crossfade.
"""
import array, math, os, subprocess, tempfile, wave

SR = 44100
DUR = 60
TARGET_RMS = 0.26  # matches the old 4 s hum
OUT = os.path.join("src", "main", "resources", "assets", "backrooms", "sounds", "hum.ogg")

# (frequency Hz, amplitude); vibrato deviation scales with the harmonic number
HARMONICS = [(120.0, 0.32), (240.0, 0.18), (360.0, 0.05)]
VIBRATO_HZ = 2.5   # slow pitch wobble at the fundamental
VIBRATO_M = 4      # cycles per loop: 15 s
DRIFT_HZ = 0.6     # second, slower wobble
DRIFT_M = 1        # 60 s
SWELL_DB = 1.5     # volume breathing
SWELL_M = 2        # 30 s


def synth():
    n = SR * DUR
    buf = array.array("d")
    two_pi = 2.0 * math.pi
    # phase amplitudes: a * m / DUR is the peak frequency deviation
    va = VIBRATO_HZ * DUR / VIBRATO_M
    da = DRIFT_HZ * DUR / DRIFT_M
    for i in range(n):
        t = i / SR
        wobble = (va * math.sin(two_pi * VIBRATO_M * t / DUR)
                  + da * math.sin(two_pi * DRIFT_M * t / DUR))
        s = 0.0
        for f, a in HARMONICS:
            s += a * math.sin(two_pi * f * t + f / HARMONICS[0][0] * wobble)
        s *= 10.0 ** (SWELL_DB / 20.0 * math.sin(two_pi * SWELL_M * t / DUR))
        buf.append(s)
    rms = math.sqrt(sum(x * x for x in buf) / n)
    k = TARGET_RMS / rms
    peak = max(abs(x) for x in buf) * k
    print(f"rms {rms:.3f} -> {TARGET_RMS:.3f}, peak {peak:.3f}")
    return buf, k


def main():
    buf, k = synth()
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        wav_path = tmp.name
    frames = array.array("h", (max(-32768, min(32767, int(x * k * 32767))) for x in buf))
    with wave.open(wav_path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(frames.tobytes())
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav_path,
                    "-c:a", "libvorbis", "-q:a", "3", "-ac", "1", OUT], check=True)
    os.unlink(wav_path)
    print(f"wrote {OUT} ({os.path.getsize(OUT)} bytes)")


if __name__ == "__main__":
    main()
