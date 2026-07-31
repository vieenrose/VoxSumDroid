#!/usr/bin/env python3
"""Generate encode/decode parity fixtures for qwen35lite::Tokenizer using the
real HF tokenizer. Usage: make_qwen35_tok_fixtures.py tokenizer.json out.json
"""
import json
import sys

from tokenizers import Tokenizer

TEXTS = [
    "Hello world",
    " leading space",
    "trailing space ",
    "double  space",
    "line\nbreak\n\ndouble",
    "\ttab\there",
    "   \n\n  mixed whitespace runs   ",
    "會議摘要:三位元量化方案將錯誤率從12%降至7%。",
    "臺灣的中文語音辨識與摘要,今天在臺北舉行。",
    "[0:00] Speaker A: 大家好,今天我們討論頻譜分群。\n[1:23] Speaker B: OK, numbers intact 3.14159!",
    "émojis 🎉 and accents: café, naïve, Führer",
    "🎉🎊🥳 emoji run 👨‍👩‍👧‍👦 ZWJ family 🇹🇼 flag",
    "mixed 中英 mixed English 123,456.78 end",
    "0123456789 and 007 and 1e10 and -42",
    "don't can't I'll we've they're it's WE'RE HE'D",
    "<|im_start|>user\nSummarize this.<|im_end|>\n<|im_start|>assistant\n",
    "<|im_start|>system\n你是一個會議摘要助手。<|im_end|>\n<|im_start|>user\n請摘要。<|im_end|>\n",
    "<|endoftext|>",
    "<think>推理中...</think>最終答案",
    "「標題」:《會議紀要》——重點事項;負責人:王小明",
    "def f(x): return x**2  # comment\n\tif x > 0: pass",
    "https://example.com/path?a=1&b=2#frag",
    "a" * 200,
    "  ",
    "\n",
    "",
    "Tab\tand\ttabs\t\tdouble",
    "混合English和中文123abc漢字ABC",
]


def main():
    tok = Tokenizer.from_file(sys.argv[1])
    out = []
    for s in TEXTS:
        ids = tok.encode(s, add_special_tokens=False).ids
        out.append({"text": s, "ids": ids, "decoded": tok.decode(ids)})
    json.dump(out, open(sys.argv[2], "w", encoding="utf-8"), ensure_ascii=False)
    print("fixtures", len(out), "lens", [len(o["ids"]) for o in out])


if __name__ == "__main__":
    main()
