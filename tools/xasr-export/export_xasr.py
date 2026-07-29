#!/usr/bin/env python3
"""Reconstruct `xasr_q8_octav.tflite` — X-ASR zh-en punct zipformer2 transducer on LiteRT.

This script regenerates the shipped VoxSumDroid default ASR model (HF `Luigi/xasr-litert`,
`xasr_q8_octav.tflite`) from its true rebuild sources, since the original export script was lost.

Recipe (validated 2026-07-29 against the shipped file, see README.md):
  1. Encoder weights: `Luigi/xasr-litert / xasr_encoder_torch.pt` — a plain state_dict of the
     icefall zipformer2 encoder (encoder_embed.* + encoder.* + encoder_proj.*), produced by ONNX
     weight transplant from `csukuangfj2/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-2026-06-03`
     (NOT from `Luigi/x-asr-zh-en-icefall-trainable`'s xasr_native.pt — that is the GilgameshWind
     chunk-480ms model with different weights).
  2. Decoder/joiner weights: taken directly from the source sherpa-onnx punct repo's
     decoder-epoch-99-avg-1.onnx / joiner-epoch-99-avg-1.onnx named initializers.
  3. Torch modules: vendored icefall zipformer (egs/librispeech/ASR/zipformer), built with the
     exact X-ASR config, causal=True, chunk_size=(-1,), left_context_frames=(-1,)  [offline
     full-context — chunk=(24,) was probed and is WRONG: mean|Δ| 0.11 vs 3.5e-07].
  4. Six signatures in ONE tflite via litert_torch (ai-edge-torch) multi-signature convert:
       enc_375/750/1500/3000: (args_0 fp32 [1,T,80] povey fbank of NORMALIZED [-1,1] samples,
                               args_1 int32 [1] valid frame count)
                              -> (output_0 fp32 [1,T',512] = encoder_proj(encoder_out) padded,
                                  output_1 int32 [1] valid output frames);  T' = ((T-7)//2+1)//2
       decoder: args_0 int32 [1,2] (context, -1 pad) -> [1,512] = decoder_proj(decoder(y))
       joiner:  args_0 [1,512] enc, args_1 [1,512] dec -> [1,5000] = output_linear(tanh(enc+dec))
     Masked buckets: features are zero-padded to the bucket; embed runs on the full bucket length,
     the encoder gets valid length ve=(n-7)//2 plus src_key_padding_mask built from ve.
  5. Quantization: ai_edge_quantizer, `dynamic_wi8_afp32` recipe with algorithm_key = OCTAV
     (per-channel dynamic-range int8 weights, fp32 activations). README of Luigi/xasr-litert:
     q8-octav CER identical to fp32; min-max/MSE measurably worse.

Usage (ai-workstation):
  ~/venvs/litert-moss/bin/python export_xasr.py --icefall ~/xasr-reexport/icefall \
      --out ~/xasr-reexport/xasr_q8_octav.candidate.tflite [--keep-fp32]

Deps: torch, litert_torch(ai-edge-torch 0.9.x), ai_edge_quantizer, ai_edge_litert, onnx,
      huggingface_hub. k2 and the icefall package are NOT required (stubbed below).
"""
import argparse, os, sys, types, contextlib
import numpy as np

BUCKETS = (375, 750, 1500, 3000)
ED = (192, 256, 512, 768, 512, 256)
ENC_REPO = "Luigi/xasr-litert"
ENC_FILE = "xasr_encoder_torch.pt"
SRC_REPO = "csukuangfj2/sherpa-onnx-x-asr-zipformer-transducer-zh-en-punct-2026-06-03"


def install_stubs():
    """Stub k2 (swoosh activations only) and icefall.utils.torch_autocast so the vendored
    icefall zipformer modules import without k2/lhotse installed."""
    import torch
    k2 = types.ModuleType("k2")
    k2.swoosh_l = lambda x: torch.logaddexp(torch.zeros_like(x), x - 4.0) - 0.08 * x - 0.035
    k2.swoosh_r = lambda x: torch.logaddexp(torch.zeros_like(x), x - 1.0) - 0.08 * x - 0.313261687
    k2.swoosh_l_forward = k2.swoosh_l
    k2.swoosh_r_forward = k2.swoosh_r
    sys.modules.setdefault("k2", k2)
    ic = types.ModuleType("icefall"); icu = types.ModuleType("icefall.utils")
    @contextlib.contextmanager
    def torch_autocast(*a, **kw):
        with torch.amp.autocast("cpu", enabled=False):
            yield
    icu.torch_autocast = torch_autocast
    ic.utils = icu
    sys.modules.setdefault("icefall", ic); sys.modules.setdefault("icefall.utils", icu)


def build_modules(icefall_dir):
    sys.path.insert(0, os.path.join(icefall_dir, "egs/librispeech/ASR/zipformer"))
    install_stubs()
    from zipformer import Zipformer2
    from subsampling import Conv2dSubsampling
    from decoder import Decoder

    # torch.export cannot guard the data-dependent `assert x.size(1) == x_lens.max().item()`
    # in Conv2dSubsampling.forward; replace forward with an identical version minus the assert.
    def _embed_forward(self, x, x_lens):
        x = x.unsqueeze(1)
        x = self.conv(x)
        x = self.convnext(x)
        b, c, t, f = x.size()
        x = x.transpose(1, 2).reshape(b, t, c * f)
        x = self.out(x)
        x = self.out_whiten(x)
        x = self.out_norm(x)
        x = self.dropout(x)
        return x, (x_lens - 7) // 2
    Conv2dSubsampling.forward = _embed_forward

    # scaling._no_op does `x.chunk(1, dim=-1)[0]` outside scripting/tracing; the litert_torch
    # 0.9.x lowering of single-chunk split_with_sizes crashes. It is a functional no-op — patch it.
    import scaling, zipformer as _zf, subsampling as _ss
    for mod in (scaling, _zf, _ss):
        if hasattr(mod, "_no_op"):
            mod._no_op = lambda x: x

    encoder = Zipformer2(
        output_downsampling_factor=2, downsampling_factor=(1, 2, 4, 8, 4, 2),
        encoder_dim=ED, num_encoder_layers=(2, 2, 4, 5, 4, 2),
        encoder_unmasked_dim=(192, 192, 256, 320, 256, 192),
        query_head_dim=32, pos_head_dim=4, value_head_dim=12, num_heads=(4, 4, 4, 8, 4, 4),
        feedforward_dim=(512, 768, 1536, 2048, 1536, 768),
        cnn_module_kernel=(31, 31, 15, 15, 15, 31), pos_dim=48,
        causal=True, chunk_size=(-1,), left_context_frames=(-1,),  # offline full-context
    )
    embed = Conv2dSubsampling(80, ED[0])
    dec = Decoder(vocab_size=5000, decoder_dim=512, blank_id=0, context_size=2)
    return embed, encoder, dec


def load_weights(embed, encoder, dec):
    import torch
    from huggingface_hub import hf_hub_download
    import onnx
    from onnx import numpy_helper
    A = torch.load(hf_hub_download(ENC_REPO, ENC_FILE), map_location="cpu")
    sub = lambda p: {k[len(p):]: v for k, v in A.items() if k.startswith(p)}
    embed.load_state_dict(sub("encoder_embed."), strict=True)
    encoder.load_state_dict(sub("encoder."), strict=True)
    eproj = torch.nn.Linear(max(ED), 512)
    eproj.load_state_dict({"weight": A["encoder_proj.weight"], "bias": A["encoder_proj.bias"]})
    inits = lambda p: {i.name: numpy_helper.to_array(i).copy()
                       for i in onnx.load(p).graph.initializer}
    di = inits(hf_hub_download(SRC_REPO, "decoder-epoch-99-avg-1.onnx"))
    ji = inits(hf_hub_download(SRC_REPO, "joiner-epoch-99-avg-1.onnx"))
    dec.load_state_dict({"embedding.weight": torch.tensor(di["decoder.embedding.weight"]),
                         "conv.weight": torch.tensor(di["decoder.conv.weight"])}, strict=True)
    dproj = torch.nn.Linear(512, 512)
    dproj.load_state_dict({"weight": torch.tensor(di["decoder_proj.weight"]),
                           "bias": torch.tensor(di["decoder_proj.bias"])})
    olin = torch.nn.Linear(512, 5000)
    olin.load_state_dict({"weight": torch.tensor(ji["output_linear.weight"]),
                          "bias": torch.tensor(ji["output_linear.bias"])})
    return eproj, dproj, olin


def make_wrappers(embed, encoder, eproj, dec, dproj, olin):
    import torch

    class EncBucket(torch.nn.Module):
        def __init__(self, T):
            super().__init__()
            self.embed, self.encoder, self.proj = embed, encoder, eproj
            self.register_buffer("full", torch.tensor([T]), persistent=False)

        def forward(self, x, x_lens):
            xe, _ = self.embed(x, self.full)          # full-bucket embed (padded region included)
            ve = torch.clamp((x_lens.to(torch.int64) - 7) // 2, min=1)  # valid embed frames
            mask = torch.arange(xe.shape[1])[None].to(ve.device) >= ve[:, None]
            eo, el = self.encoder(xe.permute(1, 0, 2), ve, src_key_padding_mask=mask)
            return self.proj(eo.permute(1, 0, 2)), el.to(torch.int32)

    class DecSig(torch.nn.Module):
        def __init__(self):
            super().__init__(); self.dec, self.proj = dec, dproj
        def forward(self, y):
            return self.proj(self.dec(y.to(torch.int64), need_pad=False).squeeze(1))

    class JoinSig(torch.nn.Module):
        def __init__(self):
            super().__init__(); self.olin = olin
        def forward(self, enc, dcr):
            return self.olin(torch.tanh(enc + dcr))

    return EncBucket, DecSig(), JoinSig()


def octav_q8_recipe():
    from ai_edge_quantizer import recipe
    from ai_edge_quantizer.recipe import AlgorithmName
    r = recipe.dynamic_wi8_afp32()
    for entry in r:
        entry["algorithm_key"] = AlgorithmName.OCTAV
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--icefall", default=os.path.expanduser("~/xasr-reexport/icefall"))
    ap.add_argument("--out", default=os.path.expanduser("~/xasr-reexport/xasr_q8_octav.candidate.tflite"))
    ap.add_argument("--keep-fp32", action="store_true")
    a = ap.parse_args()
    import torch, litert_torch
    embed, encoder, dec = build_modules(a.icefall)
    eproj, dproj, olin = load_weights(embed, encoder, dec)
    for m in (embed, encoder, dec, eproj, dproj, olin):
        m.eval()
    EncBucket, dsig, jsig = make_wrappers(embed, encoder, eproj, dec, dproj, olin)

    conv = None
    with torch.no_grad():
        for T in BUCKETS:
            args = (torch.zeros(1, T, 80), torch.tensor([T], dtype=torch.int32))
            w = EncBucket(T).eval()
            conv = (litert_torch.signature(f"enc_{T}", w, args) if conv is None
                    else conv.signature(f"enc_{T}", w, args))
            print(f"added signature enc_{T}", flush=True)
        conv = conv.signature("decoder", dsig, (torch.tensor([[-1, 0]], dtype=torch.int32),))
        conv = conv.signature("joiner", jsig, (torch.zeros(1, 512), torch.zeros(1, 512)))
        print("converting (this takes a while)...", flush=True)
        edge = conv.convert()
    fp32_path = a.out + ".fp32.tflite"
    edge.export(fp32_path)
    print(f"fp32: {os.path.getsize(fp32_path)/1e6:.0f} MB", flush=True)

    from ai_edge_quantizer import quantizer
    q = quantizer.Quantizer(float_model=fp32_path)
    q.load_quantization_recipe(octav_q8_recipe())
    with open(a.out, "wb") as f:
        f.write(q.quantize().quantized_model)
    print(f"q8-octav: {a.out}  {os.path.getsize(a.out)/1e6:.0f} MB", flush=True)
    if not a.keep_fp32:
        os.remove(fp32_path)


if __name__ == "__main__":
    main()
