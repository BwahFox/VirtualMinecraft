/*
 * Force-included into every Lua source file (build.sh passes -include). Redirects the three places where
 * stock Lua would talk to stdio and the one where it would read the clock, so that the finished module imports
 * nothing but our own "vmc" functions (ROADMAP §7h §1a: the acceptance test is "only vmc.* imports").
 */
#ifndef VMC_LUA_H
#define VMC_LUA_H
#include <stddef.h>

void vmc_write(const char *s, size_t len);            /* print(): buffered per line, flushed to vmc.log */
void vmc_werr(const char *fmt, const char *p);         /* lua_writestringerror(): panic / warnings */
__attribute__((import_module("vmc"), import_name("seed"))) unsigned vmc_seed(void);

#define lua_writestring(s, l) vmc_write((s), (l))
#define lua_writeline() vmc_write("\n", 1)
#define lua_writestringerror(s, p) vmc_werr((s), (p))
#define luai_makeseed(L) vmc_seed()
#define l_signalT int   /* lstate.h would include <signal.h>, which wasm lacks; hooks are polled, never signalled */
#define l_randomizePivot() vmc_seed()   /* ltablib.c would use clock()+time() */

#endif
