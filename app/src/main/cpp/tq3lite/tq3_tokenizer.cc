#include "tq3_tokenizer.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <queue>
#include <stdexcept>

namespace tq3lite {
namespace {

struct Hdr {
  char magic[8];
  uint32_t n_vocab, n_merges;
};

// UTF-8 codepoint length from the lead byte (invalid -> 1, byte fallback).
inline int cp_len(uint8_t b) {
  if (b < 0x80) return 1;
  if ((b & 0xE0) == 0xC0) return 2;
  if ((b & 0xF0) == 0xE0) return 3;
  if ((b & 0xF8) == 0xF0) return 4;
  return 1;
}

const char* const kSpecials[][2] = {
    // string form -> canonical piece present in the vocab
    {"<start_of_turn>", "<|turn>"}, {"<end_of_turn>", "<turn|>"},
    {"<|turn>", "<|turn>"},         {"<turn|>", "<turn|>"},
    {"<bos>", "<bos>"},             {"<eos>", "<eos>"},
};

}  // namespace

Tokenizer::Tokenizer(const std::string& bin_path) {
  FILE* f = fopen(bin_path.c_str(), "rb");
  if (!f) throw std::runtime_error("tokenizer: cannot open " + bin_path);
  Hdr h;
  if (fread(&h, sizeof h, 1, f) != 1 || memcmp(h.magic, "TQ3TOK01", 8)) {
    fclose(f);
    throw std::runtime_error("tokenizer: bad header in " + bin_path);
  }
  pieces_.resize(h.n_vocab);
  for (uint32_t i = 0; i < h.n_vocab; ++i) {
    uint16_t len;
    if (fread(&len, 2, 1, f) != 1) goto trunc;
    pieces_[i].resize(len);
    if (len && fread(&pieces_[i][0], 1, len, f) != len) goto trunc;
  }
  merges_.reserve(h.n_merges);
  for (uint32_t r = 0; r < h.n_merges; ++r) {
    uint32_t v[3];
    if (fread(v, 4, 3, f) != 3) goto trunc;
    merges_.push_back({((uint64_t)v[0] << 32) | v[1], {r, (int32_t)v[2]}});
  }
  fclose(f);
  std::sort(merges_.begin(), merges_.end());
  sorted_ids_.resize(pieces_.size());
  for (size_t i = 0; i < pieces_.size(); ++i) sorted_ids_[i] = (int32_t)i;
  std::sort(sorted_ids_.begin(), sorted_ids_.end(),
            [this](int32_t a, int32_t b) { return pieces_[a] < pieces_[b]; });
  return;
trunc:
  fclose(f);
  throw std::runtime_error("tokenizer: truncated " + bin_path);
}

int32_t Tokenizer::lookup(const std::string& p) const {
  auto it = std::lower_bound(
      sorted_ids_.begin(), sorted_ids_.end(), p,
      [this](int32_t id, const std::string& s) { return pieces_[id] < s; });
  if (it != sorted_ids_.end() && pieces_[*it] == p) return *it;
  return -1;
}

bool Tokenizer::find_merge(int32_t l, int32_t r, uint32_t* rank,
                           int32_t* merged) const {
  uint64_t key = ((uint64_t)(uint32_t)l << 32) | (uint32_t)r;
  auto it = std::lower_bound(
      merges_.begin(), merges_.end(), key,
      [](const std::pair<uint64_t, std::pair<uint32_t, int32_t>>& a,
         uint64_t k) { return a.first < k; });
  if (it == merges_.end() || it->first != key) return false;
  *rank = it->second.first;
  *merged = it->second.second;
  return true;
}

// SentencePiece-style BPE over one normalized segment: initial symbols are
// codepoints (unknown codepoints fall back to <0xXX> byte ids at the end).
void Tokenizer::bpe_segment(const std::string& seg,
                            std::vector<int32_t>* out) const {
  struct Sym {
    int32_t id;      // -1 = not in vocab (byte fallback later)
    size_t off, len; // span in seg
    int prev, next;
  };
  std::vector<Sym> syms;
  for (size_t i = 0; i < seg.size();) {
    int l = cp_len((uint8_t)seg[i]);
    if (i + l > seg.size()) l = 1;
    std::string cp = seg.substr(i, l);
    syms.push_back({lookup(cp), i, (size_t)l, (int)syms.size() - 1,
                    (int)syms.size() + 1});
    i += l;
  }
  if (syms.empty()) return;
  syms.back().next = -1;

  struct Cand {
    uint32_t rank;
    int left;
    size_t llen, rlen;  // validity snapshot
    bool operator>(const Cand& o) const { return rank > o.rank; }
  };
  std::priority_queue<Cand, std::vector<Cand>, std::greater<Cand>> pq;
  auto push = [&](int li) {
    if (li < 0) return;
    int ri = syms[li].next;
    if (ri < 0) return;
    if (syms[li].id < 0 || syms[ri].id < 0) return;
    uint32_t rank; int32_t merged;
    if (find_merge(syms[li].id, syms[ri].id, &rank, &merged))
      pq.push({rank, li, syms[li].len, syms[ri].len});
  };
  for (int i = 0; i + 1 < (int)syms.size(); ++i) push(i);
  while (!pq.empty()) {
    Cand c = pq.top();
    pq.pop();
    int li = c.left, ri = syms[li].next;
    if (ri < 0 || syms[li].len != c.llen || syms[ri].len != c.rlen) continue;
    uint32_t rank; int32_t merged;
    if (!find_merge(syms[li].id, syms[ri].id, &rank, &merged) || rank != c.rank)
      continue;
    // merge ri into li
    syms[li].id = merged;
    syms[li].len += syms[ri].len;
    syms[li].next = syms[ri].next;
    if (syms[ri].next >= 0) syms[syms[ri].next].prev = li;
    syms[ri].len = 0;
    push(syms[li].prev >= 0 && syms[li].prev != li ? syms[li].prev : -1);
    push(li);
  }
  for (int i = 0; i >= 0; i = syms[i].next) {
    if (syms[i].len == 0) continue;
    if (syms[i].id >= 0) {
      out->push_back(syms[i].id);
    } else {  // byte fallback
      for (size_t b = 0; b < syms[i].len; ++b) {
        char buf[8];
        snprintf(buf, sizeof buf, "<0x%02X>", (uint8_t)seg[syms[i].off + b]);
        int32_t id = lookup(buf);
        out->push_back(id >= 0 ? id : kUnk);
      }
    }
  }
}

std::vector<int32_t> Tokenizer::encode(const std::string& text,
                                       bool allow_special) const {
  std::vector<int32_t> out;
  std::string seg;
  auto flush = [&]() {
    if (seg.empty()) return;
    // normalize: " " -> "▁" (the model's only normalizer)
    std::string norm;
    norm.reserve(seg.size() + 8);
    for (char ch : seg) {
      if (ch == ' ') norm += "\xE2\x96\x81";
      else norm += ch;
    }
    bpe_segment(norm, &out);
    seg.clear();
  };
  for (size_t i = 0; i < text.size();) {
    bool matched = false;
    if (allow_special && text[i] == '<') {
      for (auto& sp : kSpecials) {
        size_t n = strlen(sp[0]);
        if (text.compare(i, n, sp[0]) == 0) {
          flush();
          int32_t id = lookup(sp[1]);
          if (id >= 0) out.push_back(id);
          i += n;
          matched = true;
          break;
        }
      }
    }
    if (!matched) seg += text[i++];
  }
  flush();
  return out;
}

std::string Tokenizer::decode_step(int32_t id, std::string* pending) const {
  if (id < 0 || id >= (int32_t)pieces_.size()) return "";
  const std::string& p = pieces_[id];
  // byte-fallback piece <0xXX>
  if (p.size() == 6 && p[0] == '<' && p[1] == '0' && p[2] == 'x' &&
      p[5] == '>') {
    unsigned b = 0;
    sscanf(p.c_str() + 3, "%02X", &b);
    pending->push_back((char)b);
  } else {
    // pieces with id <= 4 or turn markers are control tokens: drop
    if (id <= 4 || id == kStartOfTurn || id == kEndOfTurn) return "";
    for (size_t i = 0; i < p.size();) {
      if (p.compare(i, 3, "\xE2\x96\x81") == 0) {
        pending->push_back(' ');
        i += 3;
      } else {
        pending->push_back(p[i++]);
      }
    }
  }
  // emit complete UTF-8 prefix, keep incomplete tail
  size_t keep = pending->size();
  size_t i = 0, last_ok = 0;
  while (i < keep) {
    int l = cp_len((uint8_t)(*pending)[i]);
    if (i + l > keep) break;
    bool ok = true;
    for (int k = 1; k < l; ++k)
      if (((uint8_t)(*pending)[i + k] & 0xC0) != 0x80) ok = false;
    if (!ok) { i += 1; last_ok = i; continue; }
    i += l;
    last_ok = i;
  }
  std::string ready = pending->substr(0, last_ok);
  pending->erase(0, last_ok);
  return ready;
}

}  // namespace tq3lite
