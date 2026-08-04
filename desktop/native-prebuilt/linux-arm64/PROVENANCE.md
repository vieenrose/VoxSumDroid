# Desktop LiteRT runtime provenance (linux-aarch64, glibc)

`libLiteRt.so` — extracted UNMODIFIED from the official PyPI wheel
`ai-edge-litert` **2.1.6** (`ai_edge_litert/libLiteRt.so`), Apache-2.0,
`manylinux_2_27_aarch64` / cp312 build.
sha256 `c3e365e05249ea97d8dd6d0590df7f9bc817ff61e9610a355ff3937b72864d11`.

The aarch64 counterpart of `../linux-x64/libLiteRt.so`, **same upstream version
(2.1.6)**, so the vendored `mosslite/litert/` C headers are valid for both and no
source change was needed. Added because the desktop build could not be built at all
on arm64 Linux: CMake linked the x86-64 prebuilt unconditionally and `ld` refused it
with "file in wrong format", taking `libvoxsum-mosslite.so` — and with it every ASR
backend — down with it. Found on a Raspberry Pi 4 (Cortex-A72), where only the
summarizer half (`voxsum-llm`) could be built.

Verified before vendoring:
- `file` → ELF 64-bit LSB shared object, ARM aarch64
- 406 `LiteRt*` dynamic symbols, symbol version `VERS_1.0` — identical count to the
  x86-64 build, with no symbol present in one and absent from the other
- all 29 LiteRT entry points referenced by `mosslite/*.cc|*.cpp|*.h` are exported
  (checked against the sources, stripping the `@@VERS_1.0` suffix)

NOT yet verified: an end-to-end decode on arm64 hardware. The x86-64 counterpart was
signed off with a Nemotron run (66 s zh clip, RTF 0.093 at 8 threads); the equivalent
on arm64 is still open — see task #165.

To refresh:

```sh
pip download ai-edge-litert==2.1.6 --no-deps \
  --platform manylinux_2_27_aarch64 --only-binary=:all: -d /tmp/litert
unzip -o /tmp/litert/ai_edge_litert-2.1.6-*aarch64.whl -d /tmp/litert/x
cp /tmp/litert/x/ai_edge_litert/libLiteRt.so .
```
