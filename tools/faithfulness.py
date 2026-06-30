import urllib.request, json, sys, time

URL = "http://127.0.0.1:8088/v1/chat/completions"
SC = "/tmp/claude-1000/-home-luigi-VoxSum-bak/c9f25bf5-ffa6-4fbf-8821-25f0890030a1/scratchpad"
PHASE = sys.argv[1] if len(sys.argv) > 1 else "gen"
SOURCES = {"zh": "transcript.txt", "en": "transcript_en.txt", "ja": "transcript_ja.txt",
           "ko": "transcript_ko.txt", "fr": "transcript_fr.txt"}
TARGETS = {"auto": "", "en": "English"}   # native summary + cross-lingual-to-English
MAP = ("%s\nWrite the summary of the transcript section below as a few short bullet points. Output only"
       " the summary itself — no headings, no multiple versions, no preamble.\n\nTranscript:\n%s\n\nSummary:")

def lc(tp):
    return (" Write it in the same language as the transcript." if tp == "" else
            f" Write the ENTIRE output in {tp}. The transcript may be in another language — translate as you"
            f" summarize. Do not use any language other than {tp}.")

def chat(prompt, maxtok, sampler):
    p = {"messages": [{"role": "user", "content": prompt}], "max_tokens": maxtok,
         "chat_template_kwargs": {"enable_thinking": False}, **sampler}
    req = urllib.request.Request(URL, data=json.dumps(p).encode(), headers={"Content-Type": "application/json"})
    return json.load(urllib.request.urlopen(req, timeout=300))["choices"][0]["message"]["content"].strip()

for _ in range(180):
    try:
        urllib.request.urlopen("http://127.0.0.1:8088/health", timeout=2); break
    except Exception:
        time.sleep(1)

if PHASE == "gen":   # Qwen3.5-0.8B produces the summaries to be judged
    samp = dict(temperature=0.7, top_p=0.8, top_k=20, repeat_penalty=1.0, presence_penalty=1.0)
    out = []
    for src, path in SOURCES.items():
        t = open(f"{SC}/{path}").read()
        for tgt, tp in TARGETS.items():
            s = chat(MAP % ("Summarize the key points of this transcript." + lc(tp), t), 256, samp)
            out.append({"src": src, "tgt": tgt, "transcript": t, "summary": s})
            print(f"{src}->{tgt}: {s[:70].replace(chr(10),' ')}")
    json.dump(out, open(f"{SC}/faith_inputs.json", "w"), ensure_ascii=False)
    print(f"\nsaved {len(out)} (transcript, summary) pairs")

else:   # local judge — Qwen3.5-9B on the host server (multilingual, non-thinking, no rate limit)
    data = json.load(open(f"{SC}/faith_inputs.json"))
    samp = dict(temperature=0.3, top_p=0.9, top_k=20)
    RUB = ("You are a strict evaluator. Compare the SUMMARY to the TRANSCRIPT. Rate the summary's FAITHFULNESS"
           " from 1 to 5 (5 = every claim is supported by the transcript and nothing is invented; 1 = mostly"
           " fabricated). Then list any HALLUCINATED facts (stated in the summary but NOT in the transcript)."
           " The summary may be in a different language than the transcript — that is fine, judge the MEANING."
           " Output EXACTLY two lines:\nSCORE: <1-5>\nHALLUCINATIONS: <short list, or none>\n\n"
           "TRANSCRIPT:\n%s\n\nSUMMARY:\n%s\n\nEvaluation:")
    for d in data:
        v = chat(RUB % (d["transcript"], d["summary"]), 128, samp)
        print(f"===== {d['src']}->{d['tgt']} =====\n{v[:300]}\n", flush=True)
