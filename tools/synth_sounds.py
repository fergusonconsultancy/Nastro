"""
Mechanism sounds for Nastro, built by modal synthesis: each part (key, latch, spring, roller,
door, motor) is a few decaying resonances excited by a short noise burst, timed as the real
mechanism moves. No recordings, so no licences to worry about.
"""
import numpy as np
from scipy.signal import butter, sosfilt
import wave, sys, os

SR = 44100
rng = np.random.default_rng(1961)  # the year Philips... no: just a seed
out = sys.argv[1] if len(sys.argv) > 1 else "."


def t(sec):
    return np.arange(int(sec * SR)) / SR


def modes(dur, freqs, decays_ms, amps, burst_ms=0.8, jitter=0.01):
    """A struck object: resonances at freqs, each decaying, excited by a tiny noise burst."""
    n = int(dur * SR)
    tt = np.arange(n) / SR
    y = np.zeros(n)
    for f, d, a in zip(freqs, decays_ms, amps):
        f2 = f * (1 + rng.uniform(-jitter, jitter))
        # plastic damps its high modes hard: the higher the mode, the shorter its ring
        d2 = d * (0.55 if f2 > 1500 else 1.0)
        y += a * np.sin(2 * np.pi * f2 * tt + rng.uniform(0, 2 * np.pi)) * np.exp(-tt / (d2 / 1000))
    b = int(burst_ms / 1000 * SR)
    burst = rng.standard_normal(b) * np.hanning(b)
    exc = np.convolve(y, burst, mode="full")[:n] if b > 1 else y
    # the contact itself: a broadband crack, gone in a few milliseconds
    nb = int(0.012 * SR)
    crack = rng.standard_normal(nb) * np.exp(-np.arange(nb) / (0.0018 * SR))
    exc[:nb] += crack * 0.35 * max(amps)
    return exc


def band(x, lo, hi, order=2):
    sos = butter(order, [lo, hi], btype="band", fs=SR, output="sos")
    return sosfilt(sos, x)


def hp(x, f, order=2):
    return sosfilt(butter(order, f, btype="high", fs=SR, output="sos"), x)


def lp(x, f, order=2):
    return sosfilt(butter(order, f, btype="low", fs=SR, output="sos"), x)


def place(buf, x, at, gain=1.0):
    i = int(at * SR)
    end = min(len(buf), i + len(x))
    buf[i:end] += x[: end - i] * gain


def finish(x, peak=0.9, fade_ms=8):
    x = x - np.mean(x)
    f = int(fade_ms / 1000 * SR)
    x[-f:] *= np.linspace(1, 0, f)
    m = np.max(np.abs(x)) or 1
    return x / m * peak


def save(name, x):
    x = np.clip(x, -1, 1)
    with wave.open(os.path.join(out, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((x * 32767).astype("<i2").tobytes())


# --- Play: "ka-chunk". The key's plastic hits bottom, the mechanism drops, the latch catches,
#     then the pinch roller meets the capstan.
def play():
    b = np.zeros(int(0.24 * SR))
    place(b, modes(0.06, [2350, 3120, 4800, 6900], [12, 9, 6, 3], [1, .7, .4, .15]), 0.0, 0.55)
    thud = lp(rng.standard_normal(int(0.04 * SR)) * np.exp(-np.arange(int(0.04 * SR)) / (0.006 * SR)), 1200)
    place(b, thud, 0.007, 0.45)
    place(b, lp(modes(0.12, [128, 296, 610, 1150], [38, 24, 14, 8], [1, .6, .35, .2], burst_ms=2.5), 2400), 0.007, 1.0)
    place(b, modes(0.04, [3900, 5600, 2700], [6, 4, 9], [.6, .4, .35]), 0.041, 0.55)
    place(b, lp(modes(0.08, [92, 210], [32, 14], [1, .4], burst_ms=3), 900), 0.072, 0.35)
    return finish(b)


# --- Stop: the latch lets go and the key springs back up: a drier clack and a faint spring ring.
def stop():
    b = np.zeros(int(0.2 * SR))
    place(b, modes(0.06, [1900, 2800, 4300, 6100], [14, 10, 6, 3], [1, .7, .45, .2]), 0.0, 0.7)
    place(b, lp(modes(0.1, [176, 420, 860], [32, 18, 10], [1, .55, .3], burst_ms=2), 2600), 0.004, 0.8)
    place(b, modes(0.06, [5200, 7800], [22, 14], [1, .5], burst_ms=0.3), 0.012, 0.015)
    return finish(b, 0.8)


# --- Fast wind key: like play but lighter, no roller.
def wind_key():
    b = np.zeros(int(0.16 * SR))
    place(b, modes(0.05, [2600, 3500, 5200], [10, 7, 4], [1, .6, .3]), 0.0, 0.6)
    place(b, lp(modes(0.1, [150, 340, 700], [40, 22, 12], [1, .5, .3], burst_ms=2), 2400), 0.006, 0.8)
    place(b, modes(0.03, [4300, 6000], [5, 3], [.6, .4]), 0.03, 0.45)
    return finish(b, 0.85)


# --- Door open: the catch releases and the sprung door pops out with a little rattle.
def door_open():
    b = np.zeros(int(0.32 * SR))
    place(b, modes(0.04, [3000, 4500, 6200], [7, 5, 3], [1, .6, .3]), 0.0, 0.6)
    tt = t(0.2)
    f = 150 + 30 * (1 - np.exp(-tt / 0.03))
    boing = np.sin(2 * np.pi * np.cumsum(f) / SR) * np.exp(-tt / 0.07)
    place(b, lp(boing, 1200), 0.006, 0.55)
    place(b, lp(modes(0.08, [240, 520], [30, 15], [1, .5], burst_ms=2), 2000), 0.01, 0.5)
    for i, at in enumerate([0.06, 0.088, 0.14]):
        place(b, modes(0.03, [2200 + 400 * i, 3600], [5, 3], [1, .5]), at, 0.22 / (i + 1))
    return finish(b, 0.85)


# --- Cassette seating and the door shut: the shell slides on its guides, drops onto the spindles,
#     the door closes with a heavy thunk and the catch latches.
def seat():
    """The traditional cassette sound: hollow plastic, not a drum. The shell drops into the holder
    with a hollow tap and its loose reels rattle; the door shuts with a sharp plastic clack and a
    small body knock; the catch clicks twice; the reels settle."""
    b = np.zeros(int(0.36 * SR))
    hollow = [880, 1430, 2250, 3350, 4700]                # a plastic box: mid resonances, short
    # the shell drops into the holder
    place(b, modes(0.05, hollow, [16, 12, 9, 6, 4], [1, .8, .55, .35, .2], burst_ms=0.6), 0.0, 0.55)
    # its reels shift inside it: a scatter of tiny high clicks
    for at, g in [(0.012, .32), (0.026, .22), (0.041, .18), (0.058, .12), (0.077, .08)]:
        place(b, modes(0.015, [3600 + rng.uniform(-500, 900), 5800 + rng.uniform(-600, 600)], [3, 2], [1, .5], burst_ms=0.3), at, g)
    # the door swinging shut: a brief brush of plastic
    swish = band(rng.standard_normal(int(0.05 * SR)), 2500, 7000) * np.hanning(int(0.05 * SR))
    place(b, swish, 0.13, 0.05)
    # the door shuts: a sharp plastic clack, mostly mid, with only a small knock of the body under it
    place(b, modes(0.08, [620, 1050, 1720, 2600, 3900], [24, 18, 12, 8, 5], [1, .85, .6, .4, .25], burst_ms=0.8), 0.18, 1.0)
    place(b, lp(modes(0.06, [260, 410], [22, 14], [1, .5], burst_ms=2), 1500), 0.181, 0.32)
    # the catch: two clicks, a few milliseconds apart
    place(b, modes(0.02, [4200, 6100], [3, 2], [1, .5], burst_ms=0.25), 0.196, 0.42)
    place(b, modes(0.02, [3800, 5600], [3, 2], [1, .5], burst_ms=0.25), 0.209, 0.3)
    # the reels settle after the jolt
    for at, g in [(0.232, .14), (0.255, .09), (0.29, .05)]:
        place(b, modes(0.012, [4400 + rng.uniform(-700, 700)], [2.5], [1], burst_ms=0.25), at, g)
    return finish(b)


# --- The door shutting with a tape already in: no drop, just the clack, the catch and the reels settling.
def door_shut():
    b = np.zeros(int(0.2 * SR))
    place(b, modes(0.08, [620, 1050, 1720, 2600, 3900], [24, 18, 12, 8, 5], [1, .85, .6, .4, .25], burst_ms=0.8), 0.0, 1.0)
    place(b, lp(modes(0.06, [260, 410], [22, 14], [1, .5], burst_ms=2), 1500), 0.001, 0.32)
    place(b, modes(0.02, [4200, 6100], [3, 2], [1, .5], burst_ms=0.25), 0.016, 0.42)
    place(b, modes(0.02, [3800, 5600], [3, 2], [1, .5], burst_ms=0.25), 0.029, 0.3)
    for at, g in [(0.052, .12), (0.08, .07)]:
        place(b, modes(0.012, [4400 + rng.uniform(-700, 700)], [2.5], [1], burst_ms=0.25), at, g)
    return finish(b, 0.85)


# --- Turning the tape over: plastic lifted, flipped, set down.
def flip():
    b = np.zeros(int(0.2 * SR))
    place(b, modes(0.04, [2100, 3300], [8, 5], [1, .5]), 0.0, 0.4)
    swish = band(rng.standard_normal(int(0.08 * SR)), 900, 3000) * np.hanning(int(0.08 * SR))
    place(b, swish, 0.02, 0.15)
    place(b, lp(modes(0.08, [180, 400, 2200], [30, 16, 6], [1, .5, .3], burst_ms=2), 4000), 0.11, 0.7)
    return finish(b, 0.75)


# --- Fast wind: the motor's whine with spindle clatter. A seamless one-second loop; the app
#     changes its rate with the reels' speed.
def wind_loop():
    dur = 1.0
    tt = t(dur)
    f0 = 220.0  # whole cycles per loop: seamless
    motor = sum(a * np.sin(2 * np.pi * f0 * h * tt + rng.uniform(0, 6.28)) for h, a in [(1, 1), (2, .45), (3, .3), (5, .12), (7, .06)])
    flutter = 1 + 0.25 * np.sin(2 * np.pi * 12 * tt)   # 12 whole cycles
    n = rng.standard_normal(len(tt) + 4410)
    clatter = band(n, 1500, 4500)[4410:]
    clatter = clatter * (0.6 + 0.4 * np.sin(2 * np.pi * 24 * tt)) ** 2
    # make the noise loop: crossfade its own tail onto its head
    x = 0.35 * motor * flutter + 0.9 * clatter / np.std(clatter) * 0.25
    return finish(x, 0.6, fade_ms=1)


# --- Tape hiss: ferric hiss, high and soft, a seamless two-second loop.
def hiss_loop():
    n = int(2.0 * SR)
    raw = rng.standard_normal(n + 8820)
    h = hp(raw, 2500, 2)
    h = lp(h, 11000, 2)[8820:]
    fade = int(0.05 * SR)
    x = h.copy()
    x[:fade] = h[:fade] * np.linspace(0, 1, fade) + h[-fade:] * np.linspace(1, 0, fade)
    x = x[: n - fade]
    return finish(x, 0.5, fade_ms=1)


if __name__ == "__main__":
    for name, fn in [("key_play", play), ("key_stop", stop), ("key_wind", wind_key), ("door_open", door_open),
                     ("tape_seat", seat), ("door_shut", door_shut), ("tape_flip", flip), ("wind_loop", wind_loop), ("hiss_loop", hiss_loop)]:
        save(name, fn())
        print("wrote", name)
