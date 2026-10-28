#!/usr/bin/env python3
"""M3.6 音效 5 件套合成（自写素材，Grok 原声有版权不复用）。
输出到 iosApp/iosApp/Sounds/：22.05kHz 单声道 16-bit wav，峰值 0.35，轻不打扰。
用法：python3 scripts/make-sfx.py
"""
import math
import struct
import wave
from pathlib import Path

SR = 22050
PEAK = 0.35
OUT = Path(__file__).resolve().parent.parent / "iosApp" / "iosApp" / "Sounds"


def env(t: float, dur: float, attack: float = 0.005, decay: float = 6.0) -> float:
    """attack 段线性爬升，其余指数衰减。"""
    if t < attack:
        return t / attack
    return math.exp(-decay * (t - attack))


def tone(samples: list[float], start_s: float, dur: float, freqs: list[float],
         amp: float = 1.0, decay: float = 6.0) -> None:
    start = int(start_s * SR)
    n = int(dur * SR)
    for i in range(n):
        t = i / SR
        v = sum(math.sin(2 * math.pi * f * t) for f in freqs) / len(freqs)
        idx = start + i
        if idx < len(samples):
            samples[idx] += amp * v * env(t, dur, decay=decay)


def render(name: str, samples: list[float]) -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"sfx_{name}.wav"
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        frames = bytearray()
        for s in samples:
            x = max(-1.0, min(1.0, s * PEAK))
            frames += struct.pack("<h", int(x * 32767))
        w.writeframes(bytes(frames))
    print(f"{path.name}: {len(samples)/SR*1000:.0f}ms, {path.stat().st_size}B")


# blip：880→1400Hz 上行扫频 120ms（连接成功）
dur = 0.12
n = int(dur * SR)
buf = [0.0] * n
for i in range(n):
    t = i / SR
    f = 880 + (1400 - 880) * (t / dur)
    buf[i] += math.sin(2 * math.pi * f * t) * env(t, dur, decay=9)
render("blip", buf)

# tick：1950Hz 短滴答 35ms
dur = 0.035
buf = [math.sin(2 * math.pi * 1950 * (i / SR)) * env(i / SR, dur, decay=22) for i in range(int(dur * SR))]
render("tick", buf)

# dbltick：两次滴答，间隔 90ms（默认通知音）
n = int(0.20 * SR)
buf = [0.0] * n
for start in (0.0, 0.09):
    d = 0.035
    s0 = int(start * SR)
    for i in range(int(d * SR)):
        t = i / SR
        buf[s0 + i] += math.sin(2 * math.pi * 1950 * t) * env(t, d, decay=22)
render("dbltick", buf)

# chimea：C6→G6 上行双音风铃 420ms（审批请求）
n = int(0.42 * SR)
buf = [0.0] * n
tone(buf, 0.00, 0.22, [1046.5, 2093.0], amp=0.9, decay=8)
tone(buf, 0.20, 0.22, [1568.0, 3136.0], amp=0.8, decay=8)
render("chimea", buf)

# chimeb：G6→C6 下行双音风铃 460ms（通知备选）
n = int(0.46 * SR)
buf = [0.0] * n
tone(buf, 0.00, 0.24, [1568.0, 2093.0], amp=0.8, decay=7)
tone(buf, 0.22, 0.24, [1046.5, 1568.0], amp=0.9, decay=7)
render("chimeb", buf)
