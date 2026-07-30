// Gemma sentencepiece-style BPE tokenizer over a compact binary vocab
// (tokenizer.bin, built by turboquant/android/make_tokenizer_bin.py from the
// HF tokenizer.json of google/gemma-4-E2B-it: 262144 pieces + 514906 merges,
// byte_fallback). Normalization is the model's own: " " -> "▁" only.
// Special turn tokens are matched verbatim before BPE.
#ifndef TQ3_TOKENIZER_H
#define TQ3_TOKENIZER_H

#include <cstdint>
#include <string>
#include <vector>

namespace tq3lite {

class Tokenizer {
 public:
  // Throws std::runtime_error on malformed files.
  explicit Tokenizer(const std::string& bin_path);

  // Encode plain text (no BOS). Special token strings (<start_of_turn>,
  // <end_of_turn>, <bos>, <eos>, and the raw <|turn>/<turn|> pieces) are
  // matched as single ids when allow_special is true.
  std::vector<int32_t> encode(const std::string& text,
                              bool allow_special) const;

  // Streaming-safe detokenizer: feed one id, returns UTF-8 text ready to
  // emit (may be empty while byte-fallback bytes accumulate).
  std::string decode_step(int32_t id, std::string* pending_bytes) const;

  const std::string& piece(int32_t id) const { return pieces_[id]; }
  int32_t n_vocab() const { return (int32_t)pieces_.size(); }

  static constexpr int32_t kPad = 0, kEos = 1, kBos = 2, kUnk = 3;
  static constexpr int32_t kStartOfTurn = 105, kEndOfTurn = 106;

 private:
  struct MergeKey {
    int32_t l, r;
    bool operator<(const MergeKey& o) const {
      return l != o.l ? l < o.l : r < o.r;
    }
  };
  std::vector<std::string> pieces_;
  // piece -> id (sorted vector binary search keeps memory flat)
  std::vector<int32_t> sorted_ids_;
  // (left,right) -> (rank, merged_id)
  std::vector<std::pair<uint64_t, std::pair<uint32_t, int32_t>>> merges_;

  int32_t lookup(const std::string& p) const;
  bool find_merge(int32_t l, int32_t r, uint32_t* rank, int32_t* merged) const;
  void bpe_segment(const std::string& seg, std::vector<int32_t>* out) const;
};

}  // namespace tq3lite
#endif
