# Desktop LiteRT runtime provenance (linux-x86_64, glibc)

`libLiteRt.so` — extracted UNMODIFIED from the official PyPI wheel
`ai-edge-litert` **2.1.6** (`ai_edge_litert/libLiteRt.so`), Apache-2.0.
sha256 `d46eca60880d6e28c1992d136459f06ab642e30eb423bdc14d56e928f398ce19`.

This is the **glibc linux-x86_64** counterpart of the Android runtime in
`app/src/main/jniLibs/{arm64-v8a,x86_64}/libLiteRt.so` (see
`app/src/main/cpp/mosslite/PROVENANCE.md`). The Android AAR libs are bionic
builds and cannot be loaded by the desktop JVM, so the desktop build links this
one instead. **Same upstream version (2.1.6)**, so the vendored
`mosslite/litert/` C headers are valid for both.

Verified before vendoring:
- `file` → ELF 64-bit LSB shared object, x86-64
- exports the LiteRT-Next C API with symbol version `VERS_1.0` (406 `LiteRt*`
  symbols); **all 29 LiteRT entry points** used by `mosslite/*.cc|*.cpp` are
  present (`LiteRtCreateEnvironment`, `LiteRtCreateCompiledModel`,
  `LiteRtCreateTensorBufferFromHostMemory`, …)
- end-to-end: the Nemotron q4-mix graphs decode correctly through it on desktop
  (66 s zh clip, RTF 0.093 at 8 threads — output matches the Android/ARM run)

To refresh:

```sh
pip download ai-edge-litert==2.1.6 --no-deps -d /tmp/lrt
unzip -j /tmp/lrt/ai_edge_litert-*.whl 'ai_edge_litert/libLiteRt.so' -d desktop/native-prebuilt/linux-x64/
```
