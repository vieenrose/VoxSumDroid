// Host-side parity test for qwen35lite::Tokenizer.
//   g++ -std=c++17 -O2 -o test_tok test_qwen35_tokenizer.cc qwen35_tokenizer.cc
//   ./test_tok qwen35_tokenizer.bin qwen35_tok_fixtures.json
// Checks encode(text, allow_special=true) against HF ids and that streaming
// decode_step round-trips back to the HF-decoded text.
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "qwen35_tokenizer.h"

namespace {

struct Fixture {
  std::string text, decoded;
  std::vector<int32_t> ids;
};

void append_utf8(std::string* o, unsigned cp) {
  if (cp < 0x80) o->push_back((char)cp);
  else if (cp < 0x800) {
    o->push_back((char)(0xC0 | (cp >> 6)));
    o->push_back((char)(0x80 | (cp & 0x3F)));
  } else {
    o->push_back((char)(0xE0 | (cp >> 12)));
    o->push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
    o->push_back((char)(0x80 | (cp & 0x3F)));
  }
}

// Minimal reader for the machine-generated fixture JSON.
struct P {
  const std::string& s;
  size_t i = 0;
  void ws() { while (i < s.size() && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) ++i; }
  bool eat(char c) { ws(); if (i < s.size() && s[i] == c) { ++i; return true; } return false; }
  void expect(char c) { if (!eat(c)) { fprintf(stderr, "json: expected %c at %zu\n", c, i); exit(2); } }
  std::string str() {
    expect('"');
    std::string o;
    while (i < s.size() && s[i] != '"') {
      if (s[i] == '\\') {
        ++i;
        char e = s[i++];
        switch (e) {
          case 'n': o.push_back('\n'); break;
          case 't': o.push_back('\t'); break;
          case 'r': o.push_back('\r'); break;
          case 'b': o.push_back('\b'); break;
          case 'f': o.push_back('\f'); break;
          case 'u': {
            unsigned cp = std::stoul(s.substr(i, 4), nullptr, 16);
            i += 4;
            if (cp >= 0xD800 && cp < 0xDC00 && s[i] == '\\' && s[i + 1] == 'u') {
              unsigned lo = std::stoul(s.substr(i + 2, 4), nullptr, 16);
              i += 6;
              unsigned full = 0x10000 + ((cp - 0xD800) << 10) + (lo - 0xDC00);
              o.push_back((char)(0xF0 | (full >> 18)));
              o.push_back((char)(0x80 | ((full >> 12) & 0x3F)));
              o.push_back((char)(0x80 | ((full >> 6) & 0x3F)));
              o.push_back((char)(0x80 | (full & 0x3F)));
            } else {
              append_utf8(&o, cp);
            }
            break;
          }
          default: o.push_back(e);
        }
      } else {
        o.push_back(s[i++]);
      }
    }
    expect('"');
    return o;
  }
};

std::vector<Fixture> load(const std::string& path) {
  std::ifstream f(path, std::ios::binary);
  std::stringstream ss;
  ss << f.rdbuf();
  std::string raw = ss.str();
  P p{raw};
  std::vector<Fixture> out;
  p.expect('[');
  if (p.eat(']')) return out;
  do {
    Fixture fx;
    p.expect('{');
    do {
      std::string key = p.str();
      p.expect(':');
      if (key == "ids") {
        p.expect('[');
        if (!p.eat(']')) {
          do {
            p.ws();
            size_t j = p.i;
            while (j < raw.size() && (isdigit((unsigned char)raw[j]) || raw[j] == '-')) ++j;
            fx.ids.push_back(std::stoi(raw.substr(p.i, j - p.i)));
            p.i = j;
          } while (p.eat(','));
          p.expect(']');
        }
      } else if (key == "text") {
        fx.text = p.str();
      } else {
        fx.decoded = p.str();
      }
    } while (p.eat(','));
    p.expect('}');
    out.push_back(fx);
  } while (p.eat(','));
  p.expect(']');
  return out;
}

std::string show(const std::vector<int32_t>& v, size_t n = 40) {
  std::string o = "[";
  for (size_t i = 0; i < v.size() && i < n; ++i)
    o += (i ? ", " : "") + std::to_string(v[i]);
  if (v.size() > n) o += ", ...";
  return o + "]";
}

}  // namespace

int main(int argc, char** argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s tokenizer.bin fixtures.json\n", argv[0]);
    return 2;
  }
  qwen35lite::Tokenizer tok(argv[1]);
  printf("vocab=%d im_start=%d im_end=%d eot=%d\n", tok.n_vocab(),
         tok.im_start(), tok.im_end(), tok.endoftext());
  auto fx = load(argv[2]);
  int enc_ok = 0, dec_ok = 0, fail = 0;
  for (size_t k = 0; k < fx.size(); ++k) {
    auto ids = tok.encode(fx[k].text, true);
    bool e = ids == fx[k].ids;
    // streaming decode
    std::string pending, got;
    for (int32_t id : ids) got += tok.decode_step(id, &pending);
    got += pending;
    bool d = got == fx[k].decoded;
    enc_ok += e;
    dec_ok += d;
    if (!e || !d) {
      ++fail;
      printf("FAIL[%zu] %s%s text=%s\n", k, e ? "" : "encode ", d ? "" : "decode",
             fx[k].text.substr(0, 120).c_str());
      if (!e) {
        printf("   want %s\n   got  %s\n", show(fx[k].ids).c_str(),
               show(ids).c_str());
      }
      if (!d) printf("   want-dec %s\n   got-dec  %s\n",
                     fx[k].decoded.c_str(), got.c_str());
    }
  }
  printf("fixtures=%zu encode_pass=%d decode_pass=%d failing_fixtures=%d\n",
         fx.size(), enc_ok, dec_ok, fail);
  return fail ? 1 : 0;
}
