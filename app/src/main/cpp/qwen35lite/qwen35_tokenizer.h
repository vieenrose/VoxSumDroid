// Qwen3.5 GPT-2 style BYTE-LEVEL BPE tokenizer over a compact binary vocab
// (qwen35_tokenizer.bin, built by make_qwen35_tokenizer_bin.py from the HF
// tokenizer.json of Qwen/Qwen3.5-0.8B: 248044 pieces + 33 added specials +
// 247587 merges, no byte_fallback, no `_` normalization).
//
// Differences vs tq3lite (Gemma sentencepiece BPE):
//   * every input byte goes through the GPT-2 bytes-to-unicode table before
//     BPE, and decoding maps the pieces back to raw bytes;
//   * a pretokenizer regex (the Qwen/GPT-4 split pattern) chops the text into
//     segments first; it is hand-rolled here (no std::regex) using Unicode
//     \p{L}/\p{N} range tables carried inside the .bin;
//   * there is no unk and no byte fallback -- the 256 byte pieces always exist.
//
// NOTE: the HF tokenizer applies an NFC normalizer before pretokenization.
// This implementation does NOT normalize (no ICU); feed it NFC text.
#ifndef QWEN35_TOKENIZER_H
#define QWEN35_TOKENIZER_H

#include <cstdint>
#include <string>
#include <utility>
#include <vector>

namespace qwen35lite {

class Tokenizer {
 public:
  // Throws std::runtime_error on malformed files.
  explicit Tokenizer(const std::string& bin_path);

  // Encode plain text (no BOS/EOS). When allow_special is true the added
  // token strings (<|im_start|>, <|im_end|>, <|endoftext|>, <think>, ...)
  // are matched verbatim as single ids; otherwise they are BPE'd as text.
  std::vector<int32_t> encode(const std::string& text,
                              bool allow_special) const;

  // Streaming-safe detokenizer: feed one id, returns UTF-8 text ready to
  // emit (may be empty while a multi-byte codepoint is still incomplete).
  // Special/control ids produce no output.
  std::string decode_step(int32_t id, std::string* pending_bytes) const;

  // Convenience: full decode (specials skipped).
  std::string decode(const std::vector<int32_t>& ids) const;

  const std::string& piece(int32_t id) const { return pieces_[id]; }
  int32_t n_vocab() const { return (int32_t)pieces_.size(); }
  bool is_special(int32_t id) const {
    return id >= 0 && id < (int32_t)special_.size() && special_[id];
  }

  // Canonical ChatML ids for Qwen3.5 (validated against the .bin at load).
  int32_t eos() const { return im_end_; }
  int32_t im_start() const { return im_start_; }
  int32_t im_end() const { return im_end_; }
  int32_t endoftext() const { return endoftext_; }

 private:
  std::vector<std::string> pieces_;   // byte-level text (specials: literal)
  std::vector<char> special_;         // per-id flag
  std::vector<int32_t> sorted_ids_;   // piece -> id, binary search
  // (left<<32|right) -> (rank, merged_id), sorted by key
  std::vector<std::pair<uint64_t, std::pair<uint32_t, int32_t>>> merges_;
  // added-token strings, longest first, for verbatim matching
  std::vector<std::pair<std::string, int32_t>> specials_by_len_;
  // Unicode general-category ranges (inclusive), sorted
  std::vector<std::pair<uint32_t, uint32_t>> letter_, number_;

  int32_t im_start_ = -1, im_end_ = -1, endoftext_ = -1;

  int32_t lookup(const std::string& p) const;
  bool find_merge(int32_t l, int32_t r, uint32_t* rank, int32_t* merged) const;
  bool is_letter(uint32_t cp) const;
  bool is_number(uint32_t cp) const;
  // Hand-rolled pretokenizer: returns the end offset of the segment at `pos`.
  size_t split_next(const std::string& t, size_t pos) const;
  void bpe_segment(const std::string& byte_level, std::vector<int32_t>* out)
      const;
};

}  // namespace qwen35lite
#endif
