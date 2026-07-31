#!/usr/bin/env python
"""Rewrite the Qwen3.5-0.8B LiteRT export to use an int8-quantized KV cache.

    python rewrite_q35_int8kv.py in.tflite out.tflite [cache_len]

Replaces, in BOTH the `prefill_<P>` and `decode` subgraphs, each of the 6
full-attention blocks

    DYNAMIC_UPDATE_SLICE(kv_cache_k_L, k_new, idx)  -> k_full (1,2,C,256)
    BATCH_MATMUL(q, k_full)                         -> scores (1,2,G,C)
    ADD(scores, mask)                               -> masked
    STABLEHLO_COMPOSITE(odml.softmax)               -> probs
    BATCH_MATMUL(probs, v_full)                     -> ctx    (1,2,G,256)
    DYNAMIC_UPDATE_SLICE(kv_cache_v_L, v_new, idx)  -> v_full (1,2,256,C)

with ONE custom op

    voxsum.q35_int8kv(q, k_new, v_new, mask, input_pos, packed_k, packed_v)
        -> ctx

and swaps the fp32 `kv_cache_{k,v}_L` signature I/O for uint8 `packed_{k,v}_L`
INPUTS of shape (2, C, 260):  4-byte fp32 scale + 256 int8 codes per (head,
position) row.  The kernel writes the new tokens into the packed buffer in
place, so the packed caches are inputs only -- there is no fp32 KV tensor and
no cache output anywhere in the graph.

    fp32 KV @32k : 6 layers x 2 heads x 32768 x 256 x 4 B x (k+v) = 768 MiB
    int8 packed  : 6 x 2 x 32768 x 260        x (k+v)             = 195 MiB

The 18 linear-attention layers (kv_cache_c_i / kv_cache_r_i, a constant
19.27 MiB of conv + recurrent state) are NOT a KV cache and are deliberately
left completely untouched.
"""
import sys
import flatbuffers
from litert_converter import schema_py_generated as s

HEAD_DIM = 256
KV_HEADS = 2
# int8 code bytes + one fp32 scale, scale first so it stays 4-byte aligned.
import os
BLOCK_BYTES = 4 * HEAD_DIM if os.environ.get("Q35_KV_FP32") == "1" else (4 * (HEAD_DIM // 32) + HEAD_DIM)
CUSTOM_CODE = "voxsum.q35_int8kv"
# In `prefill_<P>` the LAST layer's attention is dead code (prefill produces no
# logits), so only the cache write survives. That block gets a write-only op
# whose 1-element output is added to the signature purely to keep it live.
CUSTOM_CODE_W = "voxsum.q35_int8kv_w"


def nm(x):
    return x.decode() if isinstance(x, bytes) else x


def main(inp, outp, cache_len=None):
    raw = open(inp, "rb").read()
    model = s.ModelT.InitFromObj(s.Model.GetRootAsModel(bytearray(raw), 0))

    # Extended flatbuffer format (weight blobs appended after the flatbuffer,
    # absolute u64 offset/size) vs. plain inline buffers.
    offs = [(b.offset, b.size) for b in model.buffers if b.offset and b.offset > 1]
    if offs:
        data_start = min(o for o, _ in offs)
        assert max(o + z for o, z in offs) <= len(raw)
        data = raw[data_start:]
    else:
        data_start, data = None, None

    custom_idx = {}
    for code in (CUSTOM_CODE, CUSTOM_CODE_W):
        oc = s.OperatorCodeT()
        oc.builtinCode = s.BuiltinOperator.CUSTOM
        oc.deprecatedBuiltinCode = s.BuiltinOperator.CUSTOM
        oc.customCode = code
        oc.version = 1
        custom_idx[code] = len(model.operatorCodes)
        model.operatorCodes.append(oc)

    def bcode(op):
        c = model.operatorCodes[op.opcodeIndex]
        return max(c.builtinCode or 0, c.deprecatedBuiltinCode or 0)

    B = s.BuiltinOperator
    total = 0
    for g in model.subgraphs:
        name = nm(g.name)
        if not (name == "decode" or name.startswith("prefill_")):
            continue
        tn = {i: nm(t.name) for i, t in enumerate(g.tensors)}
        byname = {v: k for k, v in tn.items()}
        cons, prod = {}, {}
        for oi, op in enumerate(g.operators):
            for ti in op.inputs:
                if ti >= 0:
                    cons.setdefault(ti, []).append(oi)
            for ti in op.outputs:
                prod[ti] = oi

        prefix = name + "_"
        pos_t = byname[prefix + "input_pos"]
        layers = sorted(
            int(n.rsplit("_", 1)[1])
            for n in byname
            if n.startswith(prefix + "kv_cache_k_") and not n.endswith("_output")
        )
        C = int(cache_len) if cache_len else int(
            g.tensors[byname[prefix + f"kv_cache_k_{layers[0]}"]].shape[2])

        def anchor_for(inputs, fallback):
            """Earliest list position at which the fused op is legal.

            TFLite executes operators in list order, so the op must sit after
            every producer of its inputs -- anchoring it at the K
            DYNAMIC_UPDATE_SLICE (the first op of the block) put it ahead of
            the RESHAPE that builds `q` and the CONCATENATION that broadcasts
            `mask`. It must also sit as EARLY as legal: pushing it all the way
            down to the value BATCH_MATMUL extends the live ranges of `k_new` /
            `v_new` across the surviving ops in between, and the arena then
            recycles those buffers under it (measured: prefill logit
            correlation collapsed 0.994 -> 0.197).
            """
            pos = fallback
            for ti in inputs:
                pi = prod.get(ti)
                if pi is not None and pi + 1 > pos:
                    pos = pi + 1
            return pos

        kill = set()
        maybe_dead = set()
        new_ops = {}
        removed_in, added_in, removed_out, added_out = [], [], [], []
        sig_in_map, sig_out_add, sig_out_drop = {}, [], set()
        n_blocks = n_write = 0

        only = os.environ.get("Q35_FUSE_ONLY")
        if only:
            layers = [L for L in layers if str(L) in only.split(",")]
        for layer in layers:
            kck = byname[f"{prefix}kv_cache_k_{layer}"]
            kcv = byname[f"{prefix}kv_cache_v_{layer}"]

            packed = []
            for role in ("k", "v"):
                t = s.TensorT()
                t.shape = [KV_HEADS, C, BLOCK_BYTES]
                t.type = s.TensorType.UINT8
                t.buffer = 0
                t.name = f"{prefix}packed_{role}_{layer}"
                g.tensors.append(t)
                packed.append(len(g.tensors) - 1)
            removed_in += [kck, kcv]
            added_in += packed
            sig_in_map[f"kv_cache_k_{layer}"] = (f"packed_k_{layer}", packed[0])
            sig_in_map[f"kv_cache_v_{layer}"] = (f"packed_v_{layer}", packed[1])
            sig_out_drop |= {f"kv_cache_k_{layer}", f"kv_cache_v_{layer}"}

            # -- K side: DUS -> score BMM
            (oDK,) = cons[kck]
            dk = g.operators[oDK]
            assert bcode(dk) == B.DYNAMIC_UPDATE_SLICE, bcode(dk)
            k_new, k_full = dk.inputs[1], dk.outputs[0]
            maybe_dead.add(prod[dk.inputs[2]])           # the index PACK
            # -- V side DUS (needed by both the fused and the write-only path)
            (oDV,) = cons[kcv]
            dv = g.operators[oDV]
            assert bcode(dv) == B.DYNAMIC_UPDATE_SLICE
            v_new, v_full = dv.inputs[1], dv.outputs[0]
            maybe_dead.add(prod[dv.inputs[2]])

            if not cons.get(k_full):
                # Attention pruned (last layer of `prefill_<P>`): keep only the
                # cache write. A live 1-element output keeps the op scheduled.
                assert not cons.get(v_full), tn[v_full]
                t = s.TensorT()
                t.shape = [1]
                t.type = s.TensorType.FLOAT32
                t.buffer = 0
                t.name = f"{prefix}packed_write_{layer}"
                g.tensors.append(t)
                wt = len(g.tensors) - 1
                op = s.OperatorT()
                op.opcodeIndex = custom_idx[CUSTOM_CODE_W]
                op.inputs = [k_new, v_new, pos_t, packed[0], packed[1]]
                op.outputs = [wt]
                op.customOptions = []
                op.customOptionsFormat = 0
                new_ops.setdefault(anchor_for(op.inputs, oDK), []).append(op)
                kill |= {oDK, oDV}
                removed_out += [k_full, v_full]
                added_out.append(wt)
                sig_out_add.append((f"packed_write_{layer}", wt))
                n_write += 1
                continue

            (oS,) = cons[k_full]
            sc = g.operators[oS]
            assert bcode(sc) == B.BATCH_MATMUL
            q = sc.inputs[0] if sc.inputs[1] == k_full else sc.inputs[1]

            # -- mask add -> softmax composite
            (oA,) = cons[sc.outputs[0]]
            ad = g.operators[oA]
            assert bcode(ad) == B.ADD
            mask_in = [t for t in ad.inputs if t != sc.outputs[0]][0]
            (oX,) = cons[ad.outputs[0]]
            sm = g.operators[oX]
            assert bcode(sm) == B.STABLEHLO_COMPOSITE, bcode(sm)

            # -- context BMM against the V cache
            (oV,) = cons[sm.outputs[0]]
            vm = g.operators[oV]
            assert bcode(vm) == B.BATCH_MATMUL
            assert v_full in vm.inputs, [tn[t] for t in vm.inputs]
            ctx_out = vm.outputs[0]

            # shape sanity: q/ctx (1,2,G,256); k_new (1,2,T,256); v_new (1,2,256,T)
            qs = list(g.tensors[q].shape)
            assert qs[1] == KV_HEADS and qs[3] == HEAD_DIM, qs
            assert list(g.tensors[ctx_out].shape) == qs, (qs, list(g.tensors[ctx_out].shape))
            ks, vs = list(g.tensors[k_new].shape), list(g.tensors[v_new].shape)
            assert ks[1] == KV_HEADS and ks[3] == HEAD_DIM, ks
            assert vs[1] == KV_HEADS and vs[2] == HEAD_DIM, vs
            assert ks[2] == vs[3], (ks, vs)
            ms = list(g.tensors[mask_in].shape)
            assert ms[-1] == C and ms[-2] == qs[2], ms

            kill |= {oDK, oS, oA, oX, oV, oDV}
            removed_out += [k_full, v_full]

            op = s.OperatorT()
            op.opcodeIndex = custom_idx[CUSTOM_CODE]
            op.inputs = [q, k_new, v_new, mask_in, pos_t, packed[0], packed[1]]
            op.outputs = [ctx_out]
            op.customOptions = []
            op.customOptionsFormat = 0
            new_ops.setdefault(anchor_for(op.inputs, oDK), []).append(op)
            n_blocks += 1

        for oi in maybe_dead:
            if all(all(c in kill for c in cons.get(t, []))
                   for t in g.operators[oi].outputs):
                kill.add(oi)

        ops2 = []
        for oi, op in enumerate(g.operators):
            ops2.extend(new_ops.get(oi, []))
            if oi not in kill:
                ops2.append(op)
        g.operators = ops2
        rm = set(removed_in)
        g.inputs = [t for t in g.inputs if t not in rm] + added_in
        rmo = set(removed_out)
        g.outputs = [t for t in g.outputs if t not in rmo] + added_out

        for sd in model.signatureDefs:
            if nm(sd.signatureKey) != name:
                continue
            ins2 = [tm for tm in sd.inputs if nm(tm.name) not in sig_in_map]
            for k2, (pn, ti) in sorted(sig_in_map.items()):
                tm = s.TensorMapT()
                tm.name = pn
                tm.tensorIndex = ti
                ins2.append(tm)
            sd.inputs = ins2
            outs2 = [tm for tm in sd.outputs if nm(tm.name) not in sig_out_drop]
            for pn, ti in sig_out_add:
                tm = s.TensorMapT()
                tm.name = pn
                tm.tensorIndex = ti
                outs2.append(tm)
            sd.outputs = outs2
        total += n_blocks + n_write
        print(f"{name}: fused {n_blocks} attention blocks + {n_write} "
              f"write-only (C={C}), removed "
              f"{len(kill)} ops, {len(added_in)} packed inputs, dropped "
              f"{len(sig_out_drop)} cache outputs; ops now {len(g.operators)}")
    assert total, "no attention blocks matched -- graph shape changed?"

    def pack():
        b = flatbuffers.Builder(64 * 1024 * 1024)
        b.Finish(model.Pack(b), file_identifier=b"TFL3")
        return b.Output()

    if data is None:
        fb = bytes(pack())
        with open(outp, "wb") as fo:
            fo.write(fb)
        print(f"wrote {outp}: flatbuffer {len(fb)} B (inline buffers)")
        return

    fb1 = bytes(pack())
    ALIGN = 64
    new_start = (len(fb1) + ALIGN - 1) // ALIGN * ALIGN
    delta = new_start - data_start
    for buf in model.buffers:
        if buf.offset and buf.offset > 1:
            buf.offset += delta
    fb2 = bytes(pack())
    assert len(fb2) == len(fb1), (len(fb1), len(fb2))
    with open(outp, "wb") as fo:
        fo.write(fb2)
        fo.write(b"\0" * (new_start - len(fb2)))
        fo.write(data)
    print(f"wrote {outp}: flatbuffer {len(fb2)} B + data {len(data)} B "
          f"(delta {delta:+d})")


if __name__ == "__main__":
    main(*sys.argv[1:])
