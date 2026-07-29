#!/usr/bin/env python3
"""Validate a candidate xasr tflite against the shipped xasr_q8_octav.tflite.

Checks (weight cache OFF — plain ai_edge_litert Interpreter):
  1. identical signature names / input-output shapes / dtypes
  2. per-bucket encoder output max|Δ| and relative error on real speech fbank
  3. per-bucket greedy-decoded text equality on real clips (the shipping gate)

Usage: python validate_xasr.py CANDIDATE [SHIPPED]
Needs: ai_edge_litert, numpy, soundfile, torch+torchaudio (povey fbank).
"""
import sys, os, numpy as np, soundfile as sf

SHIPPED = os.path.expanduser("~/asr-exports-fix/xasr_q8_octav.tflite")
TOKENS = os.path.expanduser(
    "~/.cache/huggingface/hub/models--Luigi--xasr-litert/snapshots/f2c05028630d09c69bdd384d71701d116e5e0578/tokens.txt")
BLANK, UNK = 0, 4015
SIGS = [(375, "enc_375"), (750, "enc_750"), (1500, "enc_1500"), (3000, "enc_3000")]

def povey_fbank(audio, sr=16000):
    import torch, torchaudio.compliance.kaldi as kaldi
    w = torch.from_numpy(np.asarray(audio, np.float32)).unsqueeze(0)
    return kaldi.fbank(w, num_mel_bins=80, frame_length=25.0, frame_shift=10.0, dither=0.0,
                       energy_floor=1.0, sample_frequency=sr, window_type="povey",
                       snip_edges=False).numpy()

def load_tokens():
    toks = {}
    for line in open(TOKENS, encoding="utf-8"):
        p = line.rstrip("\n").split(" ")
        if len(p) >= 2: toks[int(p[-1])] = " ".join(p[:-1])
    return toks

class M:
    def __init__(self, path):
        from ai_edge_litert.interpreter import Interpreter
        self.it = Interpreter(model_path=path, num_threads=8)
        self.enc = {T: self.it.get_signature_runner(n) for T, n in SIGS}
        self.dec = self.it.get_signature_runner("decoder")
        self.join = self.it.get_signature_runner("joiner")
    def sigdefs(self):
        d = {}
        for name in sorted(self.it.get_signature_list()):
            r = self.it.get_signature_runner(name)
            d[name] = ({k: (str(v["dtype"]), tuple(v["shape"])) for k, v in r.get_input_details().items()},
                       {k: (str(v["dtype"]), tuple(v["shape"])) for k, v in r.get_output_details().items()})
        return d
    def encode(self, T, feats):
        x = np.zeros((1, T, 80), np.float32); n = min(feats.shape[0], T); x[0, :n] = feats[:n]
        o = self.enc[T](args_0=x, args_1=np.array([n], np.int32))
        eo = next(v for v in o.values() if v.ndim == 3)
        L = int(next(v for v in o.values() if v.ndim == 1)[0])
        return eo[0, :L]
    def greedy(self, enc, toks):
        ctx = np.array([[-1, BLANK]], np.int32)
        d = list(self.dec(args_0=ctx).values())[0]; ids = []
        for t in range(enc.shape[0]):
            lg = list(self.join(args_0=enc[t].reshape(1, -1).astype(np.float32),
                                args_1=d.astype(np.float32)).values())[0].ravel()
            k = int(np.argmax(lg))
            if k in (BLANK, UNK): continue
            ids.append(k); ctx = np.array([[ctx[0, 1], k]], np.int32)
            d = list(self.dec(args_0=ctx).values())[0]
        return "".join(toks.get(i, "") for i in ids).replace("▁", " ").strip()

def clip(path, t0=0.0, dur=None):
    a, sr = sf.read(path); a = a.mean(1) if a.ndim > 1 else a
    a = a.astype(np.float32)
    if dur: a = a[int(t0*sr):int((t0+dur)*sr)]
    assert sr == 16000
    return a

def main():
    cand_p = sys.argv[1]; ship_p = sys.argv[2] if len(sys.argv) > 2 else SHIPPED
    cand, ship = M(cand_p), M(ship_p)
    sc, ss = cand.sigdefs(), ship.sigdefs()
    print("[1] signature defs identical:", sc == ss)
    if sc != ss:
        print("  candidate:", sc); print("  shipped:", ss)
    toks = load_tokens()
    snap = os.path.expanduser("~/.cache/huggingface/hub/models--csukuangfj2--sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-2026-06-03/snapshots/4e6b664eba94d37eff74937f49ed47006067adf1/test_wavs")
    bench = os.path.expanduser("~/vibe/bench-audio")
    clips = [("test0", clip(f"{snap}/0.wav")), ("test1", clip(f"{snap}/1.wav"))]
    if os.path.isdir(bench):
        clips += [("en25s", clip(f"{bench}/en_5min.wav", 0, 25)), ("zh25s", clip(f"{bench}/zhtw_5min.wav", 0, 25))]
    ok = True
    for tag, a in clips:
        feats = povey_fbank(a)
        for T, _ in SIGS:
            if feats.shape[0] > T: continue
            ec, es = cand.encode(T, feats), ship.encode(T, feats)
            d = np.abs(ec - es); rel = d.max() / (np.abs(es).max() + 1e-9)
            tc, ts = cand.greedy(ec, toks), ship.greedy(es, toks)
            match = "MATCH" if tc == ts else "DIFF"
            if tc != ts: ok = False
            print(f"[{tag} enc_{T}] frames={feats.shape[0]} outL={ec.shape[0]} "
                  f"max|D|={d.max():.4f} mean|D|={d.mean():.5f} rel={rel:.4%} text {match}")
            if tc != ts:
                print("   cand:", tc); print("   ship:", ts)
    print("VERDICT:", "PASS (transcription-equivalent)" if ok else "TEXT DIFFS — inspect above")

if __name__ == "__main__":
    main()
