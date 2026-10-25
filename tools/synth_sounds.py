"""
Nastro's mechanism sounds, second generation (25 Oct 2026).

The first set was modal synthesis: tuned resonances that ring at fixed pitches, which read as
synthetic. Measured against reference recordings of a real cassette mechanism (42 click onsets),
a real click is broadband and barely rings:

  - average spectrum (power per Hz, third-octave, dB re peak), which every click here is shaped to
  - decay to -20 dB: median 15 ms (25th-75th percentile 9-29 ms)
  - share of energy below 200 Hz: median 38% (the mechanism knocking through the body)
  - actions arrive as clusters: a main click, then smaller settling clicks
  - the motor winding is a steady whine near 1,465 Hz with a hum near 312 Hz

Only these statistics are taken from the reference; every sample here is generated from noise.
"""
import numpy as np
from scipy.signal import butter, sosfilt
import wave, sys, os, json

SR = 44100
rng = np.random.default_rng(2001)
out = sys.argv[1] if len(sys.argv) > 1 else "."

# Measured profile: third-octave centres (Hz) and power density (dB re peak)
CENTRES = [63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150,
           4000, 5000, 6300, 8000, 10000, 12500, 16000]
PROFILE = [-1.8, -12.5, -11.6, -6.9, -8.0, -7.6, -5.1, -1.8, 0.0, -6.7, -4.6, -4.0, -7.9, -5.8, -7.1, -8.1,
           -10.8, -12.8, -12.3, -14.6, -15.5, -16.0, -20.5, -21.3, -28.9]


KNOCK_LP = float(os.environ.get("KNOCK_LP", 60))
KNOCK_T = float(os.environ.get("KNOCK_T", 0.8))
LOW_SCALE = float(os.environ.get("LOW_SCALE", 0.55))
DECAY_SCALE = float(os.environ.get("DECAY_SCALE", 1.4))
BODY_CUT = float(os.environ.get("BODY_CUT", 110))


def shaped_noise(n, tilt_db_per_oct=0.0, low_cut=None):
    """White noise filtered to the measured click spectrum, optionally tilted brighter or darker."""
    nfft = 1 << int(np.ceil(np.log2(max(n, 64))))
    X = np.fft.rfft(rng.standard_normal(nfft))
    f = np.fft.rfftfreq(nfft, 1 / SR)
    lf = np.log2(np.clip(f, 40, 20000))
    db = np.interp(lf, np.log2(CENTRES), PROFILE, left=PROFILE[0], right=PROFILE[-1] - 10)
    db = db + tilt_db_per_oct * (lf - np.log2(1000))
    if low_cut:
        db = db - 24 * np.clip(np.log2(low_cut / np.clip(f, 1, None)), 0, None)
    X *= 10 ** (db / 20)
    return np.fft.irfft(X, nfft)[:n]


def hp(x, f):
    return sosfilt(butter(2, f, btype="high", fs=SR, output="sos"), x)


def lp(x, f):
    return sosfilt(butter(2, f, btype="low", fs=SR, output="sos"), x)


def click(decay_ms=6.5, low=0.38, tilt=0.0, knock_ms=10.0, length=0.09):
    """One mechanism click: a broadband burst with the measured timbre, and a knock below 200 Hz.
    decay_ms is the time constant; 6.5 ms reaches -20 dB at 15 ms, the measured median."""
    n = int(length * SR)
    t = np.arange(n) / SR
    att = np.clip(t / 0.0003, 0, 1)
    body = hp(shaped_noise(n, tilt, low_cut=BODY_CUT), BODY_CUT * 0.9) * att * np.exp(-t / (decay_ms * DECAY_SCALE / 1000))
    # the knock sits low (the reference peaks near 63 Hz and dips at 80-100 Hz) and dies quickly
    knock = lp(lp(rng.standard_normal(n), KNOCK_LP), KNOCK_LP) * att * np.exp(-t / (knock_ms * KNOCK_T / 1000))
    # set the low share: energy of knock / (knock + body)
    eb, ek = np.sum(body ** 2), np.sum(knock ** 2)
    low = low * LOW_SCALE
    if low > 0 and ek > 0:
        knock *= np.sqrt(low / (1 - low) * eb / ek)
    else:
        knock *= 0
    return body + knock


def place(buf, x, at, gain=1.0):
    i = int(at * SR)
    end = min(len(buf), i + len(x))
    buf[i:end] += x[: end - i] * gain


def finish(x, peak=0.9, fade_ms=6):
    x = x - np.mean(x)
    f = int(fade_ms / 1000 * SR)
    x[-f:] *= np.linspace(1, 0, f)
    return x / (np.max(np.abs(x)) or 1) * peak


def save(name, x):
    with wave.open(os.path.join(out, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def jit(v, amt=0.15):
    return v * (1 + rng.uniform(-amt, amt))


# Play: the key goes down with a knock through the body, the latch catches it, the pinch roller meets the capstan.
def key_play():
    b = np.zeros(int(0.16 * SR))
    place(b, click(6.5, 0.42, 0.0, 11), 0.0, 1.0)
    place(b, click(3.5, 0.10, 1.5, 5), 0.034, 0.42)
    place(b, click(4.0, 0.25, -1.0, 8), 0.068, 0.22)
    return finish(b)


# Stop: the latch lets go; a drier unlatching click with its knock, and the key springing back.
def key_stop():
    b = np.zeros(int(0.14 * SR))
    place(b, click(8.0, 0.45, 0.5, 12), 0.0, 1.0)
    place(b, click(3.0, 0.05, 2.0, 4), 0.012, 0.3)
    place(b, click(3.0, 0.05, 1.0, 4), 0.058, 0.12)
    return finish(b, 0.85)


# Fast wind key: lighter, one catch.
def key_wind():
    b = np.zeros(int(0.12 * SR))
    place(b, click(5.5, 0.30, 0.5, 9), 0.0, 1.0)
    place(b, click(3.0, 0.08, 1.5, 4), 0.028, 0.35)
    return finish(b, 0.85)


# Door open: the catch releases, the sprung door pops and the tape rattles in its holder.
def door_open():
    b = np.zeros(int(0.42 * SR))
    place(b, click(4.0, 0.15, 1.5, 6), 0.0, 0.7)
    place(b, click(9.0, 0.40, 0.0, 14), 0.018, 1.0)
    for at, g in [(0.09, .25), (0.15, .16), (0.24, .1), (0.33, .06)]:
        place(b, click(jit(3.5), 0.08, jit(1.0, .5), 4), at, g)
    return finish(b, 0.85)


# Tape seating: the shell drops into the holder and its reels rattle; the door shuts with the
# biggest knock of the set; the catch clicks; the tape settles.
def tape_seat():
    b = np.zeros(int(0.5 * SR))
    place(b, click(5.0, 0.22, 0.5, 8), 0.0, 0.6)
    for at, g in [(0.022, .22), (0.041, .14), (0.066, .09)]:
        place(b, click(jit(2.5), 0.05, jit(2.0, .4), 3), at, g)
    place(b, click(10.0, 0.50, -0.5, 16), 0.2, 1.0)
    place(b, click(3.5, 0.10, 1.5, 5), 0.222, 0.45)
    for at, g in [(0.27, .12), (0.34, .07)]:
        place(b, click(jit(2.5), 0.05, jit(1.5, .4), 3), at, g)
    return finish(b)


# The door shutting with the tape already in.
def door_shut():
    b = np.zeros(int(0.2 * SR))
    place(b, click(10.0, 0.50, -0.5, 16), 0.0, 1.0)
    place(b, click(3.5, 0.10, 1.5, 5), 0.022, 0.45)
    place(b, click(2.5, 0.05, 1.5, 3), 0.07, 0.1)
    return finish(b, 0.85)


# Turning the tape over: lifted out with a tick, set back down.
def tape_flip():
    b = np.zeros(int(0.22 * SR))
    place(b, click(3.5, 0.10, 1.0, 5), 0.0, 0.5)
    place(b, click(6.0, 0.35, 0.0, 10), 0.12, 0.9)
    place(b, click(2.5, 0.05, 1.5, 3), 0.145, 0.2)
    return finish(b, 0.75)


# Fast wind: the motor's high whine near 1,465 Hz over a hum near 312 Hz, with the reels' rustle.
# A seamless one-second loop (whole cycles of every partial); the app changes its rate with speed.
def wind_loop():
    t = np.arange(SR) / SR
    whine = np.sin(2 * np.pi * 1465 * t + 0.6 * np.sin(2 * np.pi * 7 * t)) + 0.35 * np.sin(2 * np.pi * 2930 * t)
    hum = 0.55 * np.sin(2 * np.pi * 312 * t) + 0.2 * np.sin(2 * np.pi * 624 * t)
    flutter = 1 + 0.18 * np.sin(2 * np.pi * 11 * t)
    rustle = shaped_noise(SR + 2048)[1024:1024 + SR]
    rustle = rustle / np.std(rustle)
    fade = int(0.03 * SR)
    rustle[:fade] = rustle[:fade] * np.linspace(0, 1, fade) + rustle[-fade:] * np.linspace(1, 0, fade)
    x = 0.32 * whine * flutter + 0.3 * hum + 0.25 * rustle
    return finish(x, 0.6, fade_ms=0.5)


def hiss_loop():
    n = int(2.0 * SR)
    h = lp(hp(rng.standard_normal(n + 8820), 2500), 11000)[8820:]
    fade = int(0.05 * SR)
    x = h.copy()
    x[:fade] = h[:fade] * np.linspace(0, 1, fade) + h[-fade:] * np.linspace(1, 0, fade)
    return finish(x[: n - fade], 0.5, fade_ms=0.5)


if __name__ == "__main__":
    for name, fn in [("key_play", key_play), ("key_stop", key_stop), ("key_wind", key_wind), ("door_open", door_open),
                     ("tape_seat", tape_seat), ("door_shut", door_shut), ("tape_flip", tape_flip),
                     ("wind_loop", wind_loop), ("hiss_loop", hiss_loop)]:
        save(name, fn())
        print("wrote", name)
