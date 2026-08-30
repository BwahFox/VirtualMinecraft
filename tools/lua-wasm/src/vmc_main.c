/*
 * The machine side of the Computer (ROADMAP §7h §1). Compiled together with Lua 5.4 into lua.wasm.
 *
 * Exports (called by Java, dev.virtualminecraft.computer.Machine):
 *   vmc_alloc/vmc_free      place bytes in linear memory
 *   vmc_boot(src, len, name, nlen)  fresh Lua state + sandboxed libraries, the chunk becomes the main coroutine
 *   vmc_run()               resume the main coroutine: 0 finished, 1 yielded (see vmc_yield_reason), 2 error
 *   vmc_yield_reason()      0 = timeslice (resume immediately), 1 = waiting for an event, 2 = a string (vmc_yield_value)
 *   vmc_eval(src, len, out, cap)   run a chunk on the main state (the /vmc computer lua harness); <0 = error length
 *   vmc_error(out, cap), vmc_yield_value(out, cap), vmc_mem_used(), vmc_mem_cap(), vmc_set_mem_cap(bytes)
 *
 * Imports (module "vmc", provided by Java):
 *   poll() -> 1 when the current slice is over (called every 10k Lua instructions by the count hook)
 *   log(level, ptr, len), seed(), clock(kind) -> i64, event_next(ptr, cap) -> len or -1,
 *   call(fn, ptr, len, out, cap) -> len or -errno   (the generic syscall: bus, files, machine)
 *
 * Memory: Lua's allocator is wrapped so the *Lua heap* is capped at vmc_set_mem_cap (default 4 MB); the wasm
 * memory's own maximum (build.sh, --max-memory) is the backstop. Out of memory is an ordinary Lua error.
 *
 * Yielding: the count hook yields with zero values when poll() says the slice is over — but only where Lua can
 * yield (lua_isyieldable); inside a C boundary (a sort comparator) the slice simply overruns until it can.
 * A zero-value yield means "resume me at once"; the ROM kernel passes such yields straight up.
 */
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "lauxlib.h"
#include "lua.h"
#include "lualib.h"

#define IMPORT(name) __attribute__((import_module("vmc"), import_name(name)))
#define EXPORT(name) __attribute__((export_name(name)))

IMPORT("poll") int vmc_poll(void);
IMPORT("log") void vmc_log(int level, const char *p, int len);
IMPORT("clock") int64_t vmc_clock(int kind);
IMPORT("event_next") int vmc_event_next(char *p, int cap);
IMPORT("call") int vmc_call(int fn, const char *p, int len, char *out, int cap);

/* ---- state ---- */
static lua_State *L;      /* the main state: eval runs here, never yields */
static lua_State *co;     /* the machine's main coroutine (the ROM kernel) */
static int co_ref __attribute__((unused)) = LUA_NOREF;
static int yield_reason;
static char ybuf[256];
static int ylen;
static char errbuf[2048];
static int errlen;
static size_t mem_used;
static size_t mem_cap = 4u << 20;

#define IOBUF 65536
static char evbuf[IOBUF + 16];   /* events in (paste is up to 64 KB) */
static char callbuf[IOBUF + 16]; /* syscall replies */

/* ---- output redirection (vmc_lua.h) ---- */
static char linebuf[1024];
static size_t linelen;

void vmc_write(const char *s, size_t len) {
	for (size_t i = 0; i < len; i++) {
		const char c = s[i];
		if (c == '\n' || linelen == sizeof linebuf) {
			vmc_log(1, linebuf, (int) linelen);
			linelen = 0;
			if (c == '\n') {
				continue;
			}
		}
		linebuf[linelen++] = c;
	}
}

void vmc_werr(const char *fmt, const char *p) {
	char b[1024];
	const int n = snprintf(b, sizeof b, fmt, p);
	vmc_log(3, b, n < 0 ? 0 : (n > (int) sizeof b ? (int) sizeof b : n));
}

/* libc's abort would import proc_exit; ours traps, which Java sees as a fault and reboots the machine. */
void abort(void) {
	vmc_log(3, "abort", 5);
	__builtin_trap();
}

/*
 * Two musl internals we define ourselves so their archive members are never linked: stdio's file lock (it
 * pulls futex → clock_gettime → the clock_time_get import) and the dtoa "Bug()" report path inside strtod
 * (fputs on stderr → the fd_* imports). Neither can run: there is no stdio, and stderr is a null pointer.
 */
int __lockfile(FILE *f) {
	(void) f;
	return 0;
}

void __unlockfile(FILE *f) {
	(void) f;
}

int fputs(const char *s, FILE *f) {
	(void) f;
	vmc_log(3, s, (int) strlen(s));
	return 0;
}

/* vfprintf references stderr's FILE object by its internal name; an opaque stub keeps stderr.c.obj (and with it the
 * fd_* imports) out of the link. The only path that would touch it writes to stderr, which nothing here does. */
unsigned char __stderr_FILE[512];

/* ---- allocator with the budget ---- */
static void *l_alloc(void *ud, void *ptr, size_t osize, size_t nsize) {
	(void) ud;
	const size_t old = ptr ? osize : 0; /* when ptr is NULL, osize is the object kind, not a size */
	if (nsize == 0) {
		if (ptr) {
			mem_used -= old;
			free(ptr);
		}
		return NULL;
	}
	if (mem_used - old + nsize > mem_cap) {
		return NULL; /* Lua runs an emergency GC and retries, then raises "not enough memory" */
	}
	void *p = realloc(ptr, nsize);
	if (p == NULL) {
		return NULL;
	}
	mem_used = mem_used - old + nsize;
	return p;
}

static int panic(lua_State *L1) {
	const char *msg = lua_tostring(L1, -1);
	vmc_werr("PANIC: %s", msg ? msg : "?");
	return 0; /* lua_atpanic then aborts */
}

/* ---- hooks ---- */
static void hook_co(lua_State *L1, lua_Debug *ar) {
	(void) ar;
	if (vmc_poll() && lua_isyieldable(L1)) {
		yield_reason = 0;
		ylen = 0;
		lua_yield(L1, 0);
	}
}

static void hook_main(lua_State *L1, lua_Debug *ar) {
	(void) ar;
	if (vmc_poll()) {
		luaL_error(L1, "eval: slice exceeded");
	}
}

/* ---- the vmc library ---- */
static int l_log(lua_State *L1) {
	const int level = (int) luaL_checkinteger(L1, 1);
	size_t len;
	const char *s = luaL_checklstring(L1, 2, &len);
	vmc_log(level, s, (int) (len > IOBUF ? IOBUF : len));
	return 0;
}

static int l_clock(lua_State *L1) {
	lua_pushinteger(L1, (lua_Integer) vmc_clock((int) luaL_optinteger(L1, 1, 0)));
	return 1;
}

static int l_event_next(lua_State *L1);

static int ev_cont(lua_State *L1, int status, lua_KContext ctx) {
	(void) status;
	(void) ctx;
	lua_settop(L1, 0);
	return l_event_next(L1);
}

static int l_event_next(lua_State *L1) {
	const int n = vmc_event_next(evbuf, IOBUF);
	if (n >= 0) {
		lua_pushlstring(L1, evbuf, (size_t) n);
		return 1;
	}
	if (!lua_isyieldable(L1)) {
		return luaL_error(L1, "event_next: cannot wait here");
	}
	yield_reason = 1;
	ylen = 0;
	lua_pushliteral(L1, "wait");
	return lua_yieldk(L1, 1, 0, ev_cont);
}

/* vmc.yield(reason, ...) -> whatever the host resumed with. Top-level only (the kernel); nested coroutines yield to their resumer. */
static int l_yield(lua_State *L1) {
	if (!lua_isyieldable(L1)) {
		return luaL_error(L1, "yield: cannot yield here");
	}
	size_t len = 0;
	const char *s = lua_tolstring(L1, 1, &len);
	if (s != NULL) {
		yield_reason = 2;
		ylen = (int) (len < sizeof ybuf ? len : sizeof ybuf);
		memcpy(ybuf, s, (size_t) ylen);
	} else {
		yield_reason = 0;
		ylen = 0;
	}
	return lua_yield(L1, lua_gettop(L1));
}

/* vmc.call(fn, payload) -> reply string | nil, errno */
static int l_call(lua_State *L1) {
	const int fn = (int) luaL_checkinteger(L1, 1);
	size_t len = 0;
	const char *p = luaL_optlstring(L1, 2, "", &len);
	if (len > IOBUF) {
		return luaL_error(L1, "call: payload too large");
	}
	const int n = vmc_call(fn, p, (int) len, callbuf, IOBUF);
	if (n < 0) {
		lua_pushnil(L1);
		lua_pushinteger(L1, -n);
		return 2;
	}
	lua_pushlstring(L1, callbuf, (size_t) n);
	return 1;
}

static int l_mem(lua_State *L1) {
	lua_pushinteger(L1, (lua_Integer) mem_used);
	lua_pushinteger(L1, (lua_Integer) mem_cap);
	return 2;
}

static int l_traceback(lua_State *L1) {
	const char *msg = lua_tostring(L1, 1);
	if (msg == NULL && !lua_isnoneornil(L1, 1)) {
		lua_pushvalue(L1, 1);
	} else {
		luaL_traceback(L1, L1, msg, (int) luaL_optinteger(L1, 2, 1));
	}
	return 1;
}

static const luaL_Reg vmclib[] = {
	{ "log", l_log },
	{ "clock", l_clock },
	{ "event_next", l_event_next },
	{ "yield", l_yield },
	{ "call", l_call },
	{ "mem", l_mem },
	{ NULL, NULL },
};

static void open_libs(lua_State *L1) {
	luaL_requiref(L1, LUA_GNAME, luaopen_base, 1);
	lua_pop(L1, 1);
	static const luaL_Reg libs[] = {
		{ LUA_COLIBNAME, luaopen_coroutine },
		{ LUA_TABLIBNAME, luaopen_table },
		{ LUA_STRLIBNAME, luaopen_string },
		{ LUA_MATHLIBNAME, luaopen_math },
		{ LUA_UTF8LIBNAME, luaopen_utf8 },
		{ NULL, NULL },
	};
	for (const luaL_Reg *lib = libs; lib->func; lib++) {
		luaL_requiref(L1, lib->name, lib->func, 1);
		lua_pop(L1, 1);
	}
	/* No io, os, package, debug: the sandbox (§7h §1d). debug.traceback is the one thing the ROM needs. */
	lua_newtable(L1);
	lua_pushcfunction(L1, l_traceback);
	lua_setfield(L1, -2, "traceback");
	lua_setglobal(L1, "debug");
	luaL_newlib(L1, vmclib);
	lua_setglobal(L1, "vmc");
}

static void set_error(lua_State *from) {
	const char *msg = lua_tostring(from, -1);
	if (msg == NULL) {
		msg = "(error object is not a string)";
	}
	errlen = (int) strlen(msg);
	if (errlen > (int) sizeof errbuf) {
		errlen = (int) sizeof errbuf;
	}
	memcpy(errbuf, msg, (size_t) errlen);
}

/* ---- exports ---- */
EXPORT("vmc_alloc") void *vmc_alloc(int n) {
	return malloc((size_t) (n > 0 ? n : 1));
}

EXPORT("vmc_free") void vmc_free(void *p) {
	free(p);
}

EXPORT("vmc_set_mem_cap") void vmc_set_mem_cap(int bytes) {
	mem_cap = (size_t) bytes;
}

EXPORT("vmc_mem_used") int vmc_mem_used(void) {
	return (int) mem_used;
}

EXPORT("vmc_mem_cap") int vmc_mem_cap(void) {
	return (int) mem_cap;
}

EXPORT("vmc_boot") int vmc_boot(const char *src, int len, const char *name, int nlen) {
	if (L != NULL) {
		lua_close(L);
		L = NULL;
		co = NULL;
	}
	mem_used = 0;
	errlen = 0;
	L = lua_newstate(l_alloc, NULL);
	if (L == NULL) {
		return 1;
	}
	lua_atpanic(L, panic);
	open_libs(L);
	lua_sethook(L, hook_main, LUA_MASKCOUNT, 10000);
	co = lua_newthread(L);
	co_ref = luaL_ref(L, LUA_REGISTRYINDEX);
	lua_sethook(co, hook_co, LUA_MASKCOUNT, 10000);
	char cname[128];
	const int cl = nlen < (int) sizeof cname - 2 ? nlen : (int) sizeof cname - 2;
	cname[0] = '=';
	memcpy(cname + 1, name, (size_t) cl);
	cname[cl + 1] = 0;
	if (luaL_loadbufferx(co, src, (size_t) len, cname, "t") != LUA_OK) {
		set_error(co);
		return 2;
	}
	return 0;
}

EXPORT("vmc_run") int vmc_run(void) {
	if (co == NULL) {
		return 2;
	}
	int nres = 0;
	yield_reason = 0;
	ylen = 0;
	const int st = lua_resume(co, L, 0, &nres);
	if (st == LUA_YIELD) {
		lua_pop(co, nres);
		return 1;
	}
	if (st == LUA_OK) {
		lua_settop(co, 0);
		return 0;
	}
	/* error: keep the traceback of the dead coroutine */
	const char *msg = lua_tostring(co, -1);
	luaL_traceback(L, co, msg ? msg : "(non-string error)", 0);
	set_error(L);
	lua_pop(L, 1);
	lua_settop(co, 0);
	return 2;
}

EXPORT("vmc_yield_reason") int vmc_yield_reason(void) {
	return yield_reason;
}

EXPORT("vmc_yield_value") int vmc_yield_value(char *out, int cap) {
	const int n = ylen < cap ? ylen : cap;
	memcpy(out, ybuf, (size_t) n);
	return n;
}

EXPORT("vmc_error") int vmc_error(char *out, int cap) {
	const int n = errlen < cap ? errlen : cap;
	memcpy(out, errbuf, (size_t) n);
	return n;
}

static int msgh(lua_State *L1) {
	const char *msg = lua_tostring(L1, 1);
	luaL_traceback(L1, L1, msg ? msg : "(non-string error)", 1);
	return 1;
}

EXPORT("vmc_eval") int vmc_eval(const char *src, int len, char *out, int cap) {
	if (L == NULL) {
		return -1;
	}
	const int base = lua_gettop(L);
	lua_pushcfunction(L, msgh);
	int st = luaL_loadbufferx(L, src, (size_t) len, "=eval", "t");
	if (st == LUA_OK) {
		st = lua_pcall(L, 0, LUA_MULTRET, base + 1);
	}
	int n;
	if (st != LUA_OK) {
		size_t l;
		const char *m = lua_tolstring(L, -1, &l);
		n = (int) (l < (size_t) cap ? l : (size_t) cap);
		memcpy(out, m ? m : "?", (size_t) n);
		lua_settop(L, base);
		return -n;
	}
	const int top = lua_gettop(L);
	n = 0;
	for (int i = base + 2; i <= top; i++) {
		size_t l;
		const char *s = luaL_tolstring(L, i, &l);
		if (n > 0 && n < cap) {
			out[n++] = '\t';
		}
		const int take = (int) (l < (size_t) (cap - n) ? l : (size_t) (cap - n));
		memcpy(out + n, s, (size_t) take);
		n += take;
		lua_pop(L, 1);
	}
	lua_settop(L, base);
	return n;
}
