# Bundled Python notices

These unmodified notices cover the CPython 3.14.8 macOS runtime and the
libraries named by its Python Build Standalone release `20261009` metadata.
`lab-runtime-python.json` pins the exact download URLs and SHA-256 hashes for
both Mac architectures. `lab-runtime.mjs` includes the notices beside the runtime. Python package
licenses remain with their installed packages and `.dist-info` directories.

The notices were extracted from the upstream
[full distribution](https://github.com/astral-sh/python-build-standalone/releases/download/20261009/cpython-3.14.8%2B20261009-aarch64-apple-darwin-pgo%2Blto-full.tar.zst),
whose SHA-256 is
`6888111560a89c16e471894cca0f50d66f3bc00e5a16e91fab24c50e567ef5ee`.
The `install_only` distribution used by uv omits this directory.

The full archive references two notices it also omits. Their copies come from
the exact CPython dependency sources selected by that release:
[Zstandard 1.5.7](https://github.com/python/cpython-source-deps/blob/zstd-1.5.7/LICENSE)
and [zlib-ng 2.2.4](https://github.com/python/cpython-source-deps/blob/zlib-ng-2.2.4/LICENSE.md).

When updating the pinned Python runtime, refresh these notices against the
matching full distribution's `PYTHON.json` and dependency sources.
