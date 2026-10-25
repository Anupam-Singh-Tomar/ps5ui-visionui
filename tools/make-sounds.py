"""
ps5UI's interface sounds, made from scratch: minimal, soft UI clicks (a few milliseconds of muffled noise over a tiny
damped tone, like a soft button). Writes res/raw/*.wav (48 kHz, mono, 16-bit).

    python tools/make-sounds.py        (from the repository folder)
"""
import os
import wave

import numpy as np

RATE = 48_000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "res", "raw")
rng = np.random.default_rng(5)


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def tone(length, f, tau, start=0.0):
    """A sine dying away with time constant tau, starting at `start`."""
    x = t(length)
    return np.where(x >= start, np.sin(2 * np.pi * f * (x - start)) * np.exp(-(x - start) / tau), 0)


def noise(length, lo, hi, tau, start=0.0):
    """White noise kept between lo and hi Hz (muffled: no sharp top), dying away, starting at `start`."""
    n = rng.standard_normal(int(RATE * length))
    spec = np.fft.rfft(n)
    freqs = np.fft.rfftfreq(len(n), 1 / RATE)
    spec[(freqs < lo) | (freqs > hi)] = 0
    x = t(length)
    return np.where(x >= start, np.fft.irfft(spec, len(n)) * np.exp(-(x - start) / tau), 0)


def click(length, f, tau, hi, start=0.0, air=0.5):
    """One soft click: a tiny damped tone (its pitch) and a breath of muffled noise (its touch)."""
    return tone(length, f, tau, start) + air * noise(length, f * 0.5, hi, tau * 0.6, start)


def finish(x, peak_db):
    attack = int(RATE * 0.0008)   # soft, but still a click
    x[:attack] *= np.linspace(0, 1, attack)
    tail = int(RATE * 0.006)      # nothing cut off at the end
    x[-tail:] *= np.linspace(1, 0, tail)
    return x / np.max(np.abs(x)) * 10 ** (peak_db / 20)


def save(name, x):
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


# Along a row: the smallest, lightest click
save("move", finish(click(0.04, 1700, 0.0035, 5000), -19))

# Between rows: a little lower and fuller
save("row", finish(click(0.05, 1150, 0.0055, 3800, air=0.6), -18))

# OK: a firmer double click, the second a touch higher
save("select", finish(click(0.11, 1500, 0.006, 5000) + 0.8 * click(0.11, 1900, 0.008, 5500, start=0.045), -15))

# Back: a double click going down, softer
save("back", finish(click(0.09, 1300, 0.005, 4000) + 0.6 * click(0.09, 900, 0.006, 3000, start=0.035), -18))

# The end of a row: a dull, padded tap
save("edge", finish(click(0.05, 430, 0.008, 1500, air=0.8), -17))

print("wrote", sorted(os.listdir(OUT)))
