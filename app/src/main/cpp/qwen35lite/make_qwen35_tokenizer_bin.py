#!/usr/bin/env python3
"""Convert a Qwen3.5 HF tokenizer.json (GPT-2 byte-level BPE, no byte_fallback)
into the compact binary consumed by qwen35lite::Tokenizer.

Mirrors turboquant/android/make_tokenizer_bin.py (the Gemma/TQ3 builder), with
two extra sections Qwen needs: the added-token id list and the Unicode
\\p{L} / \\p{N} range tables used by the hand-rolled pretokenizer split.

Layout (all little-endian):

    magic   "Q35TOK01"                              8 bytes
    header  u32 n_vocab, n_merges, n_special,
            u32 n_lrange, n_nrange                 20 bytes
    pieces  n_vocab x (u16 len + len bytes UTF-8)
              -- byte-level piece text for ordinary ids,
                 literal content for added/special ids
    merges  n_merges x (u32 left_id, u32 right_id, u32 merged_id)
              -- in rank order; merged_id is 0xFFFFFFFF if absent from vocab
    special n_special x (u32 id, u32 flags)
              -- one entry per added token; flags bit0 = "special" (matched
                 verbatim on encode AND suppressed on decode); non-special
                 added tokens are still matched verbatim but do decode
    Lrange  n_lrange x (u32 lo, u32 hi)   inclusive, sorted
    Nrange  n_nrange x (u32 lo, u32 hi)   inclusive, sorted

Usage: make_qwen35_tokenizer_bin.py tokenizer.json qwen35_tokenizer.bin
"""
import json
import struct
import sys
import unicodedata


def cat_ranges(prefix):
    out, lo, prev = [], None, None
    for cp in range(0x110000):
        hit = unicodedata.category(chr(cp)).startswith(prefix)
        if hit and lo is None:
            lo = cp
        elif not hit and lo is not None:
            out.append((lo, prev))
            lo = None
        if hit:
            prev = cp
    if lo is not None:
        out.append((lo, 0x10FFFF))
    return out


def main():
    src, out_path = sys.argv[1], sys.argv[2]
    t = json.load(open(src, encoding="utf-8"))
    m = t["model"]
    assert m["type"] == "BPE", m["type"]
    assert not m.get("byte_fallback"), "expected GPT-2 byte-level, not byte_fallback"
    assert not m.get("ignore_merges"), "ignore_merges not implemented in C++"
    assert not m.get("continuing_subword_prefix")
    assert not m.get("end_of_word_suffix")

    vocab = dict(m["vocab"])  # piece -> id
    added = t.get("added_tokens", [])
    for a in added:
        vocab.setdefault(a["content"], a["id"])
    n = max(vocab.values()) + 1
    inv = [None] * n
    for p, i in vocab.items():
        inv[i] = p
    holes = [i for i, p in enumerate(inv) if p is None]
    assert not holes, f"vocab holes at {holes[:8]}"

    merges = m["merges"]
    merges = [tuple(x) if isinstance(x, (list, tuple)) else tuple(x.split(" ", 1))
              for x in merges]

    lrange = cat_ranges("L")
    nrange = cat_ranges("N")
    special_ids = sorted((a["id"], 1 if a.get("special") else 0) for a in added)

    with open(out_path, "wb") as f:
        f.write(b"Q35TOK01")
        f.write(struct.pack("<IIIII", n, len(merges), len(special_ids),
                            len(lrange), len(nrange)))
        for p in inv:
            b = p.encode("utf-8")
            assert len(b) < 65536
            f.write(struct.pack("<H", len(b)) + b)
        miss = 0
        for l, r in merges:
            mi = vocab.get(l + r, 0xFFFFFFFF)
            if mi == 0xFFFFFFFF:
                miss += 1
            f.write(struct.pack("<III", vocab[l], vocab[r], mi))
        for i, flags in special_ids:
            f.write(struct.pack("<II", i, flags))
        for lo, hi in lrange:
            f.write(struct.pack("<II", lo, hi))
        for lo, hi in nrange:
            f.write(struct.pack("<II", lo, hi))

    print("vocab", n, "merges", len(merges), "merged-piece-missing", miss,
          "special", len(special_ids), "L-ranges", len(lrange),
          "N-ranges", len(nrange))


if __name__ == "__main__":
    main()
