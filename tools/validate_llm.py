import urllib.request, json, time, sys, re, csv

URL = "http://127.0.0.1:8088/v1/chat/completions"
SC = "/tmp/claude-1000/-home-luigi-VoxSum-bak/c9f25bf5-ffa6-4fbf-8821-25f0890030a1/scratchpad"
MODEL = sys.argv[1] if len(sys.argv) > 1 else "qwen3.5-0.8b"
TASK = sys.argv[2] if len(sys.argv) > 2 else "summarize"   # summarize | title | actions | speakers

SAMPLERS = {
    "qwen3.5-0.8b": dict(temperature=0.7, top_p=0.8, top_k=20, repeat_penalty=1.0, presence_penalty=1.0),
    "gemma-4-e2b":  dict(temperature=0.7, top_p=0.9, top_k=40, repeat_penalty=1.3, presence_penalty=0.0),
    "gemma-4-e4b":  dict(temperature=0.7, top_p=0.9, top_k=40, repeat_penalty=1.3, presence_penalty=0.0),
}
SOURCES = {"zh": "transcript.txt", "en": "transcript_en.txt", "ja": "transcript_ja.txt",
           "ko": "transcript_ko.txt", "fr": "transcript_fr.txt"}
TARGETS = {"auto": "", "en": "English", "fr": "French (français)",
           "zh-Hant": "Traditional Chinese (繁體中文)", "zh-Hans": "Simplified Chinese (简体中文)",
           "ja": "Japanese (日本語)", "ko": "Korean (한국어)"}

MAP_SUM = ("%s\nWrite the summary of the transcript section below as a few short bullet points. Output only"
           " the summary itself — no headings, no multiple versions, no preamble.\n\nTranscript:\n%s\n\nSummary:")
TITLE_T = ("Write ONE short title (at most 8 words) for the summary below.%s Output only the title text —"
           " no quotes, no list, no preamble.\n\nSummary:\n%s\n\nTitle:")
ACTION_T = ("From the transcript section below, list the concrete ACTION ITEMS (who needs to do what, with"
            " any deadline) and any key DECISIONS made, as short bullet points.%s Output only the bullets —"
            " no headings, no preamble. If there are none, output exactly \"-\".\n\nTranscript:\n%s\n\nItems:")
SPK_SYS = ("You are an expert at analyzing speech patterns and identifying speaker identities from"
           " transcripts. Be precise and only suggest names when you have clear evidence. IMPORTANT: You"
           " MUST respond in the EXACT SAME LANGUAGE as the input text. Do not translate to English.")
SPK_USER = ("Analyze the following utterances from a single speaker and suggest a name for this speaker."
            " Provide your answer in this exact format:\nNAME: [suggested name]\nCONFIDENCE: [high/medium/low]"
            "\nREASON: [brief explanation]\n\nUtterances from this speaker:\n%s")

def lc_strong(tp):  # Summarizer / title clause (strengthened)
    return (" Write it in the same language as the transcript." if tp == "" else
            f" Write the ENTIRE output in {tp}. The transcript may be in another language — translate as you"
            f" summarize. Do not use any language other than {tp}.")
def lc_weak(tp):    # ActionItemExtractor clause (the app's actual, un-strengthened)
    return "" if tp == "" else f" Write them in {tp}."

def kana(s):   return any('ぁ' <= c <= 'ゟ' or '゠' <= c <= 'ヺ' or 'ー' <= c <= 'ヿ' for c in s)
def hangul(s): return any('가' <= c <= '힣' or 'ᄀ' <= c <= 'ᇿ' for c in s)
def han(s):    return any('一' <= c <= '鿿' for c in s)
def latin(s):  return any('a' <= c.lower() <= 'z' for c in s)
def expected_ok(lang, o):
    if lang in ("zh", "zh-Hant", "zh-Hans"): return han(o) and not kana(o) and not hangul(o)
    if lang == "ja": return kana(o)
    if lang == "ko": return hangul(o)
    if lang in ("en", "fr"): return latin(o) and not han(o) and not kana(o) and not hangul(o)
    return True
def lang_ok(o, tgt, src): return expected_ok(src if tgt == "auto" else tgt, o)

def chat(prompt, maxtok):
    p = {"messages": [{"role": "user", "content": prompt}], "max_tokens": maxtok, **SAMPLERS.get(MODEL, {})}
    if "qwen" in MODEL:
        p["chat_template_kwargs"] = {"enable_thinking": False}
    req = urllib.request.Request(URL, data=json.dumps(p).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=180))["choices"][0]["message"]["content"].strip()

for _ in range(120):
    try:
        urllib.request.urlopen("http://127.0.0.1:8088/health", timeout=2); break
    except Exception:
        time.sleep(1)

rows = []
print(f"### MODEL={MODEL}  TASK={TASK} ###")
if TASK == "speakers":   # source-language only (no target)
    for src, path in SOURCES.items():
        txt = open(f"{SC}/{path}").read()[:4000]
        try: out = chat(SPK_SYS + "\n\n" + SPK_USER % txt, 100)
        except Exception as e: out = f"ERROR:{e}"
        L = lang_ok(out, "auto", src)
        F = bool(re.search(r'(?i)NAME\s*:', out)) and bool(re.search(r'(?i)CONFIDENCE\s*:', out))
        rows.append([src, "-", "Y" if L else "N", "Y" if F else "N", "-", "-", 0, out[:70].replace("\n", " ")])
        print(f"{src:>2}  srclang={'Y' if L else 'N'} fmt={'Y' if F else 'N'} | {out[:60]}")
else:
    for src, path in SOURCES.items():
        transcript = open(f"{SC}/{path}").read()
        for tgt, tp in TARGETS.items():
            t0 = time.time()
            try:
                if TASK == "summarize":
                    out = chat(MAP_SUM % ("Summarize the key points of this transcript." + lc_strong(tp), transcript), 256)
                elif TASK == "title":
                    summ = chat(MAP_SUM % ("Summarize the key points of this transcript." + lc_strong(tp), transcript), 256)
                    out = chat(TITLE_T % (lc_strong(tp), summ), 32)
                elif TASK == "actions":
                    out = chat(ACTION_T % (lc_weak(tp), transcript), 384)
            except Exception as e:
                out = f"ERROR:{e}"
            dt = time.time() - t0
            L = lang_ok(out, tgt, src)
            if TASK == "title":
                F = "\n" not in out.strip() and len(out.strip()) < 120 and not out.strip()[:1] in "•·-*"
            else:
                F = bool(re.search(r'(?m)^\s*[•·\-\*]', out)) or out.strip() == "-"
            rows.append([src, tgt, "Y" if L else "N", "Y" if F else "N", "-", "-", round(dt, 1), out[:70].replace("\n", " ")])
            print(f"{src:>2}->{tgt:<7} lang={'Y' if L else 'N'} fmt={'Y' if F else 'N'} {dt:4.0f}s | {out[:50]}")

with open(f"{SC}/results_{MODEL}_{TASK}.csv", "w") as f:
    w = csv.writer(f); w.writerow(["src", "tgt", "lang_ok", "fmt_ok", "leak", "empty", "sec", "snippet"]); w.writerows(rows)
ok = sum(1 for r in rows if r[2] == "Y")
print(f"\n=== {MODEL} / {TASK}: language adherence = {ok}/{len(rows)} ===")
if TASK != "speakers":
    tl = list(TARGETS)
    print("src\\tgt  " + " ".join(f"{t:>7}" for t in tl))
    for src in SOURCES:
        c = {r[1]: r[2] for r in rows if r[0] == src}
        print(f"{src:>6}   " + " ".join(f"{c.get(t,'?'):>7}" for t in tl))
