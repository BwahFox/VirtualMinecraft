#!/usr/bin/env bash
# THE S0 SPIKE (2026-08-25) — kept as the record of why the Computer runs Cobalt, not Lua-as-wasm (PERFORMANCE.md).
# Builds run/toolchain/lua.wasm: Lua 5.4 + tools/lua-wasm/src/vmc_main.c compiled to
# WebAssembly with wasi-sdk (ROADMAP §7h §1a). Reproducible, no root: the toolchain and the Lua tarball are
# fetched into run/toolchain/ on first use (cached like the guest-image installer). Lua's longjmp needs the
# wasm exception-handling proposal, hence the sjlj flags; Chicory >= 1.5 runs it.
#
#   tools/lua-wasm/build.sh            # build
#   tools/lua-wasm/build.sh --check    # build, then fail unless the module imports nothing but vmc.*
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
TC="$ROOT/run/toolchain"
SDK_VER=34
SDK="$TC/wasi-sdk-${SDK_VER}.0-x86_64-linux"
LUA_VER=5.4.7
LUA_SHA256=9fbf5e28ef86c69858f6d3d34eccc32e911c1a28b4120ff3e84aaa70cfbf1e30
OUT="$TC/lua.wasm"
WORK="$TC/lua-work"

mkdir -p "$TC"
if [ ! -x "$SDK/bin/clang" ]; then
	echo "fetching wasi-sdk $SDK_VER (~190 MB) into $TC"
	curl -sL -o "$TC/wasi-sdk.tar.gz" "https://github.com/WebAssembly/wasi-sdk/releases/download/wasi-sdk-${SDK_VER}/wasi-sdk-${SDK_VER}.0-x86_64-linux.tar.gz"
	tar xzf "$TC/wasi-sdk.tar.gz" -C "$TC"
	rm -f "$TC/wasi-sdk.tar.gz"
fi
if [ ! -d "$TC/lua-$LUA_VER/src" ]; then
	echo "fetching lua-$LUA_VER"
	curl -sL -o "$TC/lua-$LUA_VER.tar.gz" "https://www.lua.org/ftp/lua-$LUA_VER.tar.gz"
	echo "$LUA_SHA256  $TC/lua-$LUA_VER.tar.gz" | sha256sum -c -
	tar xzf "$TC/lua-$LUA_VER.tar.gz" -C "$TC"
fi

rm -rf "$WORK"
mkdir -p "$WORK"
cp -r "$TC/lua-$LUA_VER/src" "$WORK/src"
python3 "$ROOT/tools/lua-wasm/patch.py" "$WORK/src"

CC="$SDK/bin/clang"
SRC="$ROOT/tools/lua-wasm/src"
# Excluded on purpose: lua.c/luac.c (standalone), liolib/loslib/loadlib/ldblib (not in the sandbox), linit (we open libs ourselves).
LUA_SRCS=$(ls "$WORK"/src/*.c | grep -v -E '/(lua|luac|liolib|loslib|loadlib|ldblib|linit)\.c$')
CFLAGS=(
	--target=wasm32-wasip1 ${VMC_OPT:--O2} -DNDEBUG
	-include "$SRC/vmc_lua.h" -I"$SRC" -I"$WORK/src"
	-mexception-handling -mllvm -wasm-enable-sjlj -mllvm -wasm-use-legacy-eh=false
	-ffunction-sections -fdata-sections
	-Wall -Wno-unused-function
)
LDFLAGS=(
	-mexec-model=reactor -lsetjmp
	-Wl,--gc-sections -Wl,--stack-first -Wl,-z,stack-size=262144
	-Wl,--initial-memory=2097152 -Wl,--max-memory=67108864
	${EXTRA_LDFLAGS:-}
)
mkdir -p "$(dirname "$OUT")"
# shellcheck disable=SC2086
"$CC" "${CFLAGS[@]}" "${LDFLAGS[@]}" -o "$OUT" $LUA_SRCS "$SRC/vmc_main.c"
ls -la "$OUT"
sha256sum "$OUT"

# List the imports straight from the binary (no wasm-tools needed): a minimal reader of the import section.
python3 - "$OUT" "${1:-}" <<'PY'
import sys
data = open(sys.argv[1], "rb").read()
assert data[:8] == b"\0asm\1\0\0\0", "not a wasm module"
pos = 8
def leb():
    global pos
    r = s = 0
    while True:
        b = data[pos]; pos += 1
        r |= (b & 0x7f) << s; s += 7
        if not b & 0x80:
            return r
imports = []
while pos < len(data):
    sid = data[pos]; pos += 1
    size = leb(); end = pos + size
    if sid == 2:
        for _ in range(leb()):
            ml = leb(); mod = data[pos:pos+ml].decode(); pos += ml
            nl = leb(); name = data[pos:pos+nl].decode(); pos += nl
            kind = data[pos]; pos += 1
            if kind == 0: leb()
            elif kind == 1: pos += 1; f = leb(); leb(); (leb() if f & 1 else None)
            elif kind == 2: f = leb(); leb(); (leb() if f & 1 else None)
            elif kind == 3: pos += 2
            elif kind == 4: pos += 1; leb()
            imports.append((mod, name, kind))
    pos = end
print("imports:", ", ".join(f"{m}.{n}" for m, n, k in imports))
bad = [f"{m}.{n}" for m, n, k in imports if m != "vmc"]
if bad:
    print("NOT SANDBOXED — foreign imports:", bad)
    if sys.argv[2] == "--check":
        sys.exit(1)
PY
