#!/usr/bin/env python3
"""The two source patches build.sh applies to a *copy* of the Lua 5.4 sources (ROADMAP §7h §1a).

1. Sandbox: `dofile`/`loadfile` are removed from the base library (so nothing references fopen and the
   module stays free of WASI imports), and `load` only accepts text chunks — precompiled bytecode is the
   classic Lua sandbox escape.
2. Step caps in the one standard-library loop that can run for seconds without executing a single Lua
   instruction: pattern matching. A pathological pattern on a long subject now raises "pattern too expensive"
   after 50 M match steps (~0.3 s) instead of hiding from the count hook. `rep`/`concat`/`sort` are bounded by
   the memory cap already (their cost is proportional to bytes that must fit in the budget).
3. `math.randomseed()` with no argument seeded from `time()`; it now asks the host (vmc.seed), like lstate.c.

Every replacement asserts it matched exactly once, so a Lua point release that moves a line fails the build
loudly rather than silently shipping an unpatched file.
"""
import pathlib
import re
import sys

src = pathlib.Path(sys.argv[1])


def edit(name, pairs):
    p = src / name
    s = p.read_text()
    for old, new, count in pairs:
        n = s.count(old)
        assert n == count, f"{name}: expected {count} of {old!r}, found {n}"
        s = s.replace(old, new)
    p.write_text(s)


edit("lbaselib.c", [
    ('  {"dofile", luaB_dofile},\n', "", 1),
    ('  {"loadfile", luaB_loadfile},\n', "", 1),
    ('  const char *mode = luaL_optstring(L, 3, "bt");', '  const char *mode = "t";  /* vmc: text chunks only */', 1),
])

edit("lstrlib.c", [
    ("#define L_ESC\t\t'%'\n", "#define L_ESC\t\t'%'\n\nstatic unsigned long vmc_match_steps;  /* vmc: step cap, see patch.py */\n#define VMC_MAX_MATCH_STEPS 50000000UL\n", 1),
    ("  init: /* using goto to optimize tail recursion */\n",
     "  if (l_unlikely(++vmc_match_steps > VMC_MAX_MATCH_STEPS))\n    luaL_error(ms->L, \"pattern too expensive\");\n  init: /* using goto to optimize tail recursion */\n", 1),
    ("static void prepstate (MatchState *ms, lua_State *L,\n                       const char *s, size_t ls, const char *p, size_t lp) {\n",
     "static void prepstate (MatchState *ms, lua_State *L,\n                       const char *s, size_t ls, const char *p, size_t lp) {\n  vmc_match_steps = 0;\n", 1),
    ("static void reprepstate (MatchState *ms) {\n", "static void reprepstate (MatchState *ms) {\n  vmc_match_steps = 0;\n", 1),
])

# lauxlib's luaL_loadfilex is the only user of stdio in the whole library; nothing in the sandbox can reach it,
# but as a non-static function it survives --gc-sections and drags wasi-libc's file layer in — whose global
# constructor then roots fd_prestat_get/proc_exit/fd_write. Cut the whole LoadF block out.
s = (src / "lauxlib.c").read_text()
a = s.index("typedef struct LoadF {")
b = s.index("typedef struct LoadS {")
assert 0 < a < b, "lauxlib.c: LoadF/LoadS blocks not found"
s = s[:a] + "/* vmc: luaL_loadfilex removed (no files in the sandbox, no stdio in the module) */\n\n" + s[b:]
(src / "lauxlib.c").write_text(s)

# lmathlib seeds from the clock when randomseed() gets no argument; the host provides the entropy instead.
s = (src / "lmathlib.c").read_text()
n = len(re.findall(r"\btime\(NULL\)", s))
assert n >= 1, "lmathlib.c: expected time(NULL)"
s = re.sub(r"\btime\(NULL\)", "vmc_seed()", s)
(src / "lmathlib.c").write_text(s)
print(f"patched lbaselib.c, lstrlib.c, lmathlib.c ({n} seed sites)")
