package dev.virtualminecraft.screen;

import dev.virtualminecraft.util.Nums;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Who is looking at which screen, server-side, keyed by screen UUID and independent of what produces the frames.
 * A client heartbeats ({@code ViewerPayload}) every 10 ticks for every screen it rendered recently; a viewer that
 * stops heartbeating is dropped after {@link #TIMEOUT_TICKS}. Everything that costs something per watcher — the
 * framebuffer, text-mode rows, audio, "is anyone watching this machine" — asks here, so a screen nobody is
 * looking at sends nothing and a machine nobody is looking at may sleep (ROADMAP §7l).
 * <p>
 * Heartbeats and {@link #tick} run on the server thread, like the payload receivers that feed it; {@link #of} and
 * {@link #anyone} are also called from a Computer's worker thread (the per-frame flush, U1.2) — the maps are
 * concurrent, and {@code ScreenDevice}'s flush lock serialises the {@link Viewer} mutations a flush makes.
 */
public final class ScreenViewers {
	public static final int TIMEOUT_TICKS = 60;
	private static final Map<MinecraftServer, ScreenViewers> INSTANCES = new WeakHashMap<>();

	public static final class Viewer {
		public final ServerPlayer player;
		long lastHeartbeat;
		/** The next flush must send this viewer the whole screen (new viewer, resize, or the client lost its texture). */
		public boolean needFull;
		/** Requested level of detail, 0 = full resolution; each level halves both dimensions (milestone 5 A3). */
		public int lod;
		/** The level the last {@code ScreenInfo} told this viewer about; -1 until one was sent. A change forces a full frame. */
		public int sentLod = -1;

		Viewer(final ServerPlayer player, final long tick) {
			this.player = player;
			this.lastHeartbeat = tick;
			this.needFull = true;
		}
	}

	private final Map<UUID, Map<UUID, Viewer>> byScreen = new ConcurrentHashMap<>();
	private long tick;

	private ScreenViewers() {
	}

	public static synchronized ScreenViewers get(final MinecraftServer server) {
		return INSTANCES.computeIfAbsent(server, s -> new ScreenViewers());
	}

	public void heartbeat(final UUID screen, final ServerPlayer player, final boolean needFull, final int lod) {
		final Viewer v = byScreen.computeIfAbsent(screen, s -> new ConcurrentHashMap<>()).computeIfAbsent(player.getUUID(), u -> new Viewer(player, tick));
		v.lastHeartbeat = tick;
		if (needFull) {
			v.needFull = true;
		}
		v.lod = Nums.clamp(lod, 0, dev.virtualminecraft.net.ViewerPayload.MAX_LOD);
	}

	/** The live viewers of a screen (expired ones are pruned on the way out). Empty, never null. */
	public Collection<Viewer> of(final UUID screen) {
		final Map<UUID, Viewer> m = byScreen.get(screen);
		if (m == null) {
			return List.of();
		}
		prune(m);
		return m.values();
	}

	public boolean anyone(final UUID screen) {
		return !of(screen).isEmpty();
	}

	/** Once per server tick: advance the clock and drop screens nobody watches any more. */
	public void tick() {
		tick++;
		final Iterator<Map.Entry<UUID, Map<UUID, Viewer>>> it = byScreen.entrySet().iterator();
		while (it.hasNext()) {
			final Map<UUID, Viewer> m = it.next().getValue();
			prune(m);
			if (m.isEmpty()) {
				it.remove();
			}
		}
	}

	private void prune(final Map<UUID, Viewer> m) {
		m.values().removeIf(v -> v.player.hasDisconnected() || tick - v.lastHeartbeat > TIMEOUT_TICKS);
	}
}
