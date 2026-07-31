#include "qwen35_tokenizer.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <queue>
#include <stdexcept>

namespace qwen35lite {
namespace {

struct Hdr {
  char magic[8];
  uint32_t n_vocab, n_merges, n_special, n_lrange, n_nrange;
};

inline int cp_len(uint8_t b) {
  if (b < 0x80) return 1;
  if ((b & 0xE0) == 0xC0) return 2;
  if ((b & 0xF0) == 0xE0) return 3;
  if ((b & 0xF8) == 0xF0) return 4;
  return 1;
}

// Decode one UTF-8 codepoint at `i`; sets *len (>=1). Invalid -> U+FFFD, len 1.
inline uint32_t cp_at(const std::string& s, size_t i, int* len) {
  uint8_t b = (uint8_t)s[i];
  int l = cp_len(b);
  if (i + (size_t)l > s.size()) { *len = 1; return 0xFFFD; }
  for (int k = 1; k < l; ++k)
    if (((uint8_t)s[i + k] & 0xC0) != 0x80) { *len = 1; return 0xFFFD; }
  *len = l;
  switch (l) {
    case 1: return b;
    case 2: return ((b & 0x1Fu) << 6) | ((uint8_t)s[i + 1] & 0x3Fu);
    case 3: return ((b & 0x0Fu) << 12) | (((uint8_t)s[i + 1] & 0x3Fu) << 6) |
                   ((uint8_t)s[i + 2] & 0x3Fu);
    default: return ((b & 0x07u) << 18) | (((uint8_t)s[i + 1] & 0x3Fu) << 12) |
                    (((uint8_t)s[i + 2] & 0x3Fu) << 6) |
                    ((uint8_t)s[i + 3] & 0x3Fu);
  }
}

inline void append_cp(std::string* out, uint32_t cp) {
  if (cp < 0x80) {
    out->push_back((char)cp);
  } else if (cp < 0x800) {
    out->push_back((char)(0xC0 | (cp >> 6)));
    out->push_back((char)(0x80 | (cp & 0x3F)));
  } else if (cp < 0x10000) {
    out->push_back((char)(0xE0 | (cp >> 12)));
    out->push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
    out->push_back((char)(0x80 | (cp & 0x3F)));
  } else {
    out->push_back((char)(0xF0 | (cp >> 18)));
    out->push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
    out->push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
    out->push_back((char)(0x80 | (cp & 0x3F)));
  }
}

// Unicode White_Space property (what Rust's regex \s matches).
inline bool is_ws(uint32_t c) {
  return (c >= 0x09 && c <= 0x0D) || c == 0x20 || c == 0x85 || c == 0xA0 ||
         c == 0x1680 || (c >= 0x2000 && c <= 0x200A) || c == 0x2028 ||
         c == 0x2029 || c == 0x202F || c == 0x205F || c == 0x3000;
}
inline bool is_nl(uint32_t c) { return c == 0x0A || c == 0x0D; }

bool in_ranges(const std::vector<std::pair<uint32_t, uint32_t>>& r,
               uint32_t cp) {
  size_t lo = 0, hi = r.size();
  while (lo < hi) {
    size_t m = (lo + hi) / 2;
    if (cp < r[m].first) hi = m;
    else if (cp > r[m].second) lo = m + 1;
    else return true;
  }
  return false;
}

// GPT-2 bytes-to-unicode table (and its inverse).
struct ByteMap {
  uint32_t b2u[256];
  int16_t u2b[512];  // indexed by codepoint for cp < 512, -1 otherwise
  ByteMap() {
    for (int i = 0; i < 512; ++i) u2b[i] = -1;
    bool printable[256] = {false};
    for (int b = 33; b <= 126; ++b) printable[b] = true;
    for (int b = 161; b <= 172; ++b) printable[b] = true;
    for (int b = 174; b <= 255; ++b) printable[b] = true;
    int n = 0;
    for (int b = 0; b < 256; ++b) {
      if (printable[b]) b2u[b] = (uint32_t)b;
      else b2u[b] = (uint32_t)(256 + n++);
    }
    for (int b = 0; b < 256; ++b) u2b[b2u[b]] = (int16_t)b;
  }
};
const ByteMap& bytemap() {
  static const ByteMap m;
  return m;
}

}  // namespace

Tokenizer::Tokenizer(const std::string& bin_path) {
  FILE* f = fopen(bin_path.c_str(), "rb");
  if (!f) throw std::runtime_error("tokenizer: cannot open " + bin_path);
  Hdr h;
  if (fread(&h, sizeof h, 1, f) != 1 || memcmp(h.magic, "Q35TOK01", 8)) {
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
  special_.assign(h.n_vocab, 0);
  for (uint32_t i = 0; i < h.n_special; ++i) {
    uint32_t v[2];
    if (fread(v, 4, 2, f) != 2) goto trunc;
    if (v[0] >= h.n_vocab) goto trunc;
    // every added token is matched verbatim on encode; only the ones flagged
    // "special" are suppressed on decode (<think> etc. are added but visible)
    special_[v[0]] = (char)(v[1] & 1u);
    specials_by_len_.push_back({pieces_[v[0]], (int32_t)v[0]});
  }
  letter_.resize(h.n_lrange);
  for (uint32_t i = 0; i < h.n_lrange; ++i) {
    uint32_t v[2];
    if (fread(v, 4, 2, f) != 2) goto trunc;
    letter_[i] = {v[0], v[1]};
  }
  number_.resize(h.n_nrange);
  for (uint32_t i = 0; i < h.n_nrange; ++i) {
    uint32_t v[2];
    if (fread(v, 4, 2, f) != 2) goto trunc;
    number_[i] = {v[0], v[1]};
  }
  fclose(f);

  std::sort(merges_.begin(), merges_.end());
  sorted_ids_.resize(pieces_.size());
  for (size_t i = 0; i < pieces_.size(); ++i) sorted_ids_[i] = (int32_t)i;
  std::sort(sorted_ids_.begin(), sorted_ids_.end(),
            [this](int32_t a, int32_t b) { return pieces_[a] < pieces_[b]; });
  std::sort(specials_by_len_.begin(), specials_by_len_.end(),
            [](const std::pair<std::string, int32_t>& a,
               const std::pair<std::string, int32_t>& b) {
              return a.first.size() > b.first.size();
            });
  for (auto& s : specials_by_len_) {
    if (s.first == "<|im_start|>") im_start_ = s.second;
    else if (s.first == "<|im_end|>") im_end_ = s.second;
    else if (s.first == "<|endoftext|>") endoftext_ = s.second;
  }
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

bool Tokenizer::is_letter(uint32_t cp) const { return in_ranges(letter_, cp); }
bool Tokenizer::is_number(uint32_t cp) const { return in_ranges(number_, cp); }

// Hand-rolled equivalent of the Qwen/GPT-4 pretokenizer pattern:
//   (?i:'s|'t|'re|'ve|'m|'ll|'d)
// | [^\r\n\p{L}\p{N}]?\p{L}+
// | \p{N}
// | ?[^\s\p{L}\p{N}]+[\r\n]*
// | \s*[\r\n]+
// | \s+(?!\S)
// | \s+
// Alternatives are tried in order at `pos`, leftmost-first, greedy within an
// alternative -- exactly what the Rust regex engine does for this pattern.
size_t Tokenizer::split_next(const std::string& t, size_t pos) const {
  const size_t n = t.size();
  int l0 = 1;
  uint32_t c0 = cp_at(t, pos, &l0);

  // 1. contractions (ASCII apostrophe only, case-insensitive)
  if (c0 == '\'') {
    static const char* const kSuf[] = {"re", "ve", "ll", "s", "t", "m", "d"};
    for (const char* s : kSuf) {
      size_t sl = strlen(s);
      if (pos + 1 + sl > n) continue;
      bool ok = true;
      for (size_t k = 0; k < sl; ++k) {
        char a = t[pos + 1 + k], b = s[k];
        if (a != b && a != (char)(b - 32)) { ok = false; break; }
      }
      if (ok) return pos + 1 + sl;
    }
  }

  // 2. [^\r\n\p{L}\p{N}]? \p{L}+
  {
    size_t p = pos;
    if (!is_nl(c0) && !is_letter(c0) && !is_number(c0)) p = pos + l0;
    size_t q = p;
    while (q < n) {
      int l;
      uint32_t c = cp_at(t, q, &l);
      if (!is_letter(c)) break;
      q += l;
    }
    if (q > p) return q;
  }

  // 3. \p{N}  (single codepoint)
  if (is_number(c0)) return pos + l0;

  // 4. " ?" [^\s\p{L}\p{N}]+ [\r\n]*
  {
    size_t p = pos;
    if (c0 == ' ') p = pos + 1;
    size_t q = p;
    while (q < n) {
      int l;
      uint32_t c = cp_at(t, q, &l);
      if (is_ws(c) || is_letter(c) || is_number(c)) break;
      q += l;
    }
    if (q > p) {
      while (q < n) {
        int l;
        uint32_t c = cp_at(t, q, &l);
        if (!is_nl(c)) break;
        q += l;
      }
      return q;
    }
  }

  // whitespace run [pos, we)
  size_t we = pos, last_nl_end = 0;
  while (we < n) {
    int l;
    uint32_t c = cp_at(t, we, &l);
    if (!is_ws(c)) break;
    we += l;
    if (is_nl(c)) last_nl_end = we;
  }
  if (we > pos) {
    // 5. \s*[\r\n]+  -> up to and including the last newline of the run
    if (last_nl_end) return last_nl_end;
    // 6. \s+(?!\S)
    if (we == n) return we;
    // greedy \s+ backtracks by one codepoint so the lookahead holds
    size_t back = pos, prev = pos;
    while (back < we) {
      int l;
      cp_at(t, back, &l);
      prev = back;
      back += l;
    }
    if (prev > pos) return prev;
    // 7. \s+
    return we;
  }

  return pos + l0;  // unreachable for well-formed input
}

void Tokenizer::bpe_segment(const std::string& seg,
                            std::vector<int32_t>* out) const {
  struct Sym {
    int32_t id;
    size_t len;  // in bytes of seg, 0 = dead
    int prev, next;
  };
  std::vector<Sym> syms;
  for (size_t i = 0; i < seg.size();) {
    int l = cp_len((uint8_t)seg[i]);
    if (i + (size_t)l > seg.size()) l = 1;
    int32_t id = lookup(seg.substr(i, l));
    syms.push_back({id, (size_t)l, (int)syms.size() - 1, (int)syms.size() + 1});
    i += l;
  }
  if (syms.empty()) return;
  syms.back().next = -1;

  struct Cand {
    uint32_t rank;
    int left;
    size_t llen, rlen;
    // lowest rank first, leftmost first on ties (matches HF's BPE heap)
    bool operator>(const Cand& o) const {
      return rank != o.rank ? rank > o.rank : left > o.left;
    }
  };
  std::priority_queue<Cand, std::vector<Cand>, std::greater<Cand>> pq;
  auto push = [&](int li) {
    if (li < 0) return;
    int ri = syms[li].next;
    if (ri < 0) return;
    if (syms[li].id < 0 || syms[ri].id < 0) return;
    uint32_t rank;
    int32_t merged;
    if (find_merge(syms[li].id, syms[ri].id, &rank, &merged))
      pq.push({rank, li, syms[li].len, syms[ri].len});
  };
  for (int i = 0; i + 1 < (int)syms.size(); ++i) push(i);
  while (!pq.empty()) {
    Cand c = pq.top();
    pq.pop();
    int li = c.left, ri = syms[li].next;
    if (ri < 0 || syms[li].len != c.llen || syms[ri].len != c.rlen) continue;
    uint32_t rank;
    int32_t merged;
    if (!find_merge(syms[li].id, syms[ri].id, &rank, &merged) || rank != c.rank)
      continue;
    if (merged < 0) continue;
    syms[li].id = merged;
    syms[li].len += syms[ri].len;
    syms[li].next = syms[ri].next;
    if (syms[ri].next >= 0) syms[syms[ri].next].prev = li;
    syms[ri].len = 0;
    push(syms[li].prev);
    push(li);
  }
  for (int i = 0; i >= 0; i = syms[i].next) {
    if (syms[i].len == 0) continue;
    if (syms[i].id >= 0) out->push_back(syms[i].id);
  }
}

std::vector<int32_t> Tokenizer::encode(const std::string& text,
                                       bool allow_special) const {
  std::vector<int32_t> out;
  const ByteMap& bm = bytemap();

  auto encode_plain = [&](const std::string& chunk) {
    size_t pos = 0;
    while (pos < chunk.size()) {
      size_t end = split_next(chunk, pos);
      if (end <= pos) end = pos + 1;
      // ByteLevel: map each raw byte through the GPT-2 table
      std::string bl;
      bl.reserve((end - pos) * 2);
      for (size_t i = pos; i < end; ++i)
        append_cp(&bl, bm.b2u[(uint8_t)chunk[i]]);
      bpe_segment(bl, &out);
      pos = end;
    }
  };

  if (!allow_special) {
    encode_plain(text);
    return out;
  }
  std::string buf;
  for (size_t i = 0; i < text.size();) {
    bool matched = false;
    if (text[i] == '<') {
      for (const auto& sp : specials_by_len_) {
        if (text.compare(i, sp.first.size(), sp.first) == 0) {
          encode_plain(buf);
          buf.clear();
          out.push_back(sp.second);
          i += sp.first.size();
          matched = true;
          break;
        }
      }
    }
    if (!matched) buf.push_back(text[i++]);
  }
  encode_plain(buf);
  return out;
}

std::string Tokenizer::decode_step(int32_t id, std::string* pending) const {
  if (id < 0 || id >= (int32_t)pieces_.size()) return "";
  if (special_[id]) return "";
  const std::string& p = pieces_[id];
  const ByteMap& bm = bytemap();
  for (size_t i = 0; i < p.size();) {
    int l;
    uint32_t cp = cp_at(p, i, &l);
    i += l;
    int16_t b = (cp < 512) ? bm.u2b[cp] : -1;
    if (b >= 0) pending->push_back((char)(uint8_t)b);
  }
  // emit the complete UTF-8 prefix, keep an incomplete tail buffered
  size_t keep = pending->size(), i = 0, last_ok = 0;
  while (i < keep) {
    int l = cp_len((uint8_t)(*pending)[i]);
    if (i + (size_t)l > keep) break;
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

std::string Tokenizer::decode(const std::vector<int32_t>& ids) const {
  std::string pending, out;
  for (int32_t id : ids) out += decode_step(id, &pending);
  out += pending;
  return out;
}

}  // namespace qwen35lite
