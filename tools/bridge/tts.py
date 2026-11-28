# Local neural TTS daemon for the bridge.
# English: Kokoro-82M (kokoro-onnx, model in .tools/tts), local and fast. Hebrew (or any text with Hebrew letters):
# Microsoft Edge neural voice he-IL-AvriNeural (male) via edge-tts, since Kokoro has no Hebrew.
# stdin: one JSON per line: {"text": "...", "voice": "am_michael", "speed": 1.1, "he_voice": "he-IL-AvriNeural"} or {"stop": true}
# Speaks sentence by sentence so the first words start fast; "stop" cuts off speech and drops the queue.
import asyncio, io, json, os, queue, re, sys, threading, traceback
import sounddevice as sd
import soundfile as sf
from kokoro_onnx import Kokoro

sys.stdin.reconfigure(encoding="utf-8", errors="replace")  # the bridge writes UTF-8 (Windows default would mangle Hebrew)
root = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", ".tools", "tts")
k = Kokoro(os.path.join(root, "kokoro-v1.0.onnx"), os.path.join(root, "voices-v1.0.bin"))
k.create("Ready.", voice="am_michael", speed=1.0, lang="en-us")  # warm up
HEBREW = re.compile("[" + chr(0x590) + "-" + chr(0x5FF) + "]")
q, gen = queue.Queue(), [0]


def hebrew(text, voice, speed):
    import edge_tts
    async def run():
        rate = "%+d%%" % round((speed - 1) * 100)
        buf = bytearray()
        async for c in edge_tts.Communicate(text, voice, rate=rate).stream():
            if c["type"] == "audio":
                buf.extend(c["data"])
        return bytes(buf)
    return sf.read(io.BytesIO(asyncio.run(run())), dtype="float32")


def envelope(audio, sr, hz=30):
    # loudness curve of the sentence (0..1, 30 per second) for the in-game orb to pulse with; stdout -> bridge -> game
    import numpy as np
    a = np.asarray(audio, dtype="float32")
    if a.ndim > 1:
        a = a.mean(axis=1)
    n = max(1, int(sr / hz))
    frames = len(a) // n
    if frames == 0:
        return
    rms = np.sqrt((a[: frames * n].reshape(frames, n) ** 2).mean(axis=1))
    peak = float(rms.max()) or 1.0
    print(json.dumps({"env": [round(float(x) / peak, 2) for x in rms], "hz": hz}), flush=True)


VOLUME = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "moddeus", "volume.json")


def game_volume():
    # the game's Master x Voice volume (written by client/TtsVolume when it changes); no file = full volume
    try:
        with open(VOLUME, encoding="utf-8") as f:
            return max(0.0, min(1.0, float(json.load(f).get("volume", 1.0))))
    except Exception:
        return 1.0


TTS_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "run", "moddeus", "tts")
seq = [0]


def game_alive():
    # the game rewrites volume.json every 5 s (client/TtsVolume): alive = it plays the voice itself (client/TtsPlayer)
    try:
        import time
        return time.time() - os.path.getmtime(VOLUME) < 15
    except OSError:
        return False


def play_in_game(audio, sr, g):
    # one WAV per sentence, atomic rename; wait for its length so sentences stay in order and "stop" can cut in
    import time
    os.makedirs(TTS_DIR, exist_ok=True)
    seq[0] += 1
    name = "%d_%06d" % (int(time.time()), seq[0])
    tmp = os.path.join(TTS_DIR, name + ".part")
    sf.write(tmp, audio, sr, format="WAV", subtype="PCM_16")
    os.replace(tmp, os.path.join(TTS_DIR, name + ".wav"))
    end = time.time() + len(audio) / float(sr) + 0.05
    while time.time() < end:
        if g != gen[0]:
            return
        time.sleep(0.02)


def worker():
    while True:
        g, job = q.get()
        text = job["text"]
        he = bool(HEBREW.search(text))
        parts = [text] if he else re.split(r"(?<=[.!?])\s+", text)  # edge-tts: one request for the whole reply
        for s in parts:
            if g != gen[0] or not s.strip():
                break
            try:
                if he:
                    audio, sr = hebrew(s, job.get("he_voice", "he-IL-AvriNeural"), job.get("speed", 1.1))
                else:
                    audio, sr = k.create(s, voice=job.get("voice", "am_michael"), speed=job.get("speed", 1.1), lang="en-us")
                if g != gen[0]:
                    break
                if game_alive():
                    envelope(audio, sr)
                    play_in_game(audio, sr, g)  # the game plays it: its sliders AND the Windows mixer mute apply
                    continue
                vol = game_volume()
                if vol <= 0.001:
                    continue  # game muted: Claude stays silent too
                envelope(audio, sr)
                sd.play(audio * vol, sr)
                sd.wait()
            except Exception:
                traceback.print_exc(file=sys.stderr)  # one bad sentence must never kill the voice
                sys.stderr.flush()


threading.Thread(target=worker, daemon=True).start()
print("ready", flush=True)
for line in sys.stdin:
    try:
        m = json.loads(line)
    except ValueError:
        continue
    if m.get("stop"):
        gen[0] += 1
        sd.stop()
        try:
            os.makedirs(TTS_DIR, exist_ok=True)
            for f in os.listdir(TTS_DIR):
                if f.endswith(".wav"):
                    os.remove(os.path.join(TTS_DIR, f))
            open(os.path.join(TTS_DIR, "stop"), "w").close()
        except OSError:
            pass
        print('{"stop": true}', flush=True)
    elif m.get("text"):
        q.put((gen[0], m))
