package dev.virtualminecraft.computer;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;
import org.squiddev.cobalt.LuaDouble;
import org.squiddev.cobalt.LuaError;
import org.squiddev.cobalt.LuaState;
import org.squiddev.cobalt.LuaString;
import org.squiddev.cobalt.LuaTable;
import org.squiddev.cobalt.LuaThread;
import org.squiddev.cobalt.LuaUserdata;
import org.squiddev.cobalt.LuaValue;
import org.squiddev.cobalt.Prototype;
import org.squiddev.cobalt.Varargs;
import org.squiddev.cobalt.debug.DebugFrame;
import org.squiddev.cobalt.debug.Upvalue;
import org.squiddev.cobalt.function.LuaClosure;

/**
 * The memory budget for a machine on Cobalt (ROADMAP §7h §1b). Cobalt has no allocator hook, so two things are
 * combined: <em>allocation</em> is exact (the scheduler measures the worker thread's allocated bytes around every
 * slice and reports them here), and <em>live size</em> is an estimate from a walk over everything reachable from the
 * machine's globals, registry and coroutines, run whenever the allocation since the last walk exceeds the budget.
 * The estimate is deliberately generous per object (Java overhead included) so the budget errs towards refusing.
 * <p>
 * Worker thread only, like the machine.
 */
public final class MemoryMeter {
	/** Stop walking past this many objects: the machine is over any sane budget anyway. */
	private static final int MAX_OBJECTS = 4_000_000;

	private long allocatedSinceWalk;
	private long estimate;
	private long lastWalkNanos;
	private int lastWalkObjects;

	public long estimate() {
		return estimate;
	}

	public long allocatedSinceWalk() {
		return allocatedSinceWalk;
	}

	public long lastWalkNanos() {
		return lastWalkNanos;
	}

	public int lastWalkObjects() {
		return lastWalkObjects;
	}

	/** The scheduler: bytes the worker thread allocated during a slice of this machine. */
	public void addAllocated(final long bytes) {
		allocatedSinceWalk += Math.max(0, bytes);
	}

	/** A walk is due when the machine has allocated a budget's worth since the last one. */
	public boolean walkDue(final long capBytes) {
		return allocatedSinceWalk >= capBytes;
	}

	/** Re-estimate the live size. Returns the estimate in bytes. */
	public long walk(final LuaState state, final LuaThread main) {
		final long t0 = System.nanoTime();
		final Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		final Deque<Object> todo = new ArrayDeque<>();
		long bytes = 0;
		todo.push(state.globals());
		todo.push(state.registry().get());
		todo.push(main);
		final LuaThread current = state.getCurrentThread();
		if (current != null) {
			todo.push(current);
		}
		while (!todo.isEmpty()) {
			final Object o = todo.pop();
			if (o == null || !seen.add(o)) {
				continue;
			}
			if (seen.size() > MAX_OBJECTS) {
				bytes = Long.MAX_VALUE / 2;
				break;
			}
			if (o instanceof LuaTable t) {
				bytes += 64;
				try {
					LuaValue k = org.squiddev.cobalt.Constants.NIL;
					while (true) {
						final Varargs kv = t.next(k);
						k = kv.first();
						if (k.isNil()) {
							break;
						}
						bytes += 40;
						todo.push(k);
						todo.push(kv.arg(2));
					}
				} catch (final LuaError e) {
					// a table being modified elsewhere: count what we saw
				}
				final LuaTable mt = t.getMetatable(state);
				if (mt != null) {
					todo.push(mt);
				}
			} else if (o instanceof LuaString s) {
				bytes += 32 + s.length();
			} else if (o instanceof LuaClosure c) {
				final Prototype p = c.getPrototype();
				bytes += 48 + 24L * p.upvalues();
				for (int i = 0; i < p.upvalues(); i++) {
					final Upvalue u = c.getUpvalue(i);
					if (u != null) {
						todo.push(u.getValue());
					}
				}
				todo.push(p);
			} else if (o instanceof Prototype p) {
				bytes += 128 + 4L * p.code.length + 8L * p.constants.length + 4L * (p.lineInfo == null ? 0 : p.lineInfo.length);
				for (final LuaValue k : p.constants) {
					todo.push(k);
				}
				for (final Prototype child : p.children) {
					todo.push(child);
				}
			} else if (o instanceof LuaThread th) {
				bytes += 256;
				DebugFrame f = null;
				try {
					f = th.getDebugState().getStackUnsafe();
				} catch (final RuntimeException ignored) {
					// a thread that has never run has no frames
				}
				while (f != null) {
					bytes += 64;
					if (f.stack != null) {
						bytes += 8L * f.stack.length;
						for (final LuaValue v : f.stack) {
							if (v != null) {
								todo.push(v);
							}
						}
					}
					pushVarargs(todo, f.varargs);
					pushVarargs(todo, f.extras);
					if (f.closure != null) {
						todo.push(f.closure);
					}
					f = f.previous;
				}
			} else if (o instanceof LuaDouble) {
				bytes += 24;
			} else if (o instanceof LuaUserdata) {
				bytes += 64;
			}
			// integers, booleans, nil, Java functions: shared or negligible
		}
		estimate = bytes;
		allocatedSinceWalk = 0;
		lastWalkNanos = System.nanoTime() - t0;
		lastWalkObjects = seen.size();
		return bytes;
	}

	private static void pushVarargs(final Deque<Object> todo, final Varargs v) {
		if (v == null) {
			return;
		}
		for (int i = 1; i <= v.count(); i++) {
			todo.push(v.arg(i));
		}
	}
}
