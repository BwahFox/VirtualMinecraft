package dev.virtualminecraft.screen;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a screen UUID back to the loaded block entity that owns it. Sources note themselves on their first
 * server tick after loading; entries are only hints, because {@code setRemoved} never fires server-side on chunk
 * demotion in 26.2, so {@link #find} re-checks that a source with that id is really at that position.
 */
public final class ScreenSources {
	private record Ref(ResourceKey<Level> dimension, BlockPos pos) {
	}

	private static final Map<UUID, Ref> BY_ID = new ConcurrentHashMap<>();

	private ScreenSources() {
	}

	public static void note(final ScreenSource source, final ServerLevel level) {
		BY_ID.put(source.screenId(), new Ref(level.dimension(), source.screenPos().immutable()));
	}

	/** The loaded source with this id, or null if it is unloaded, gone, or was never seen. Never loads a chunk. */
	public static @Nullable ScreenSource find(final MinecraftServer server, final UUID id) {
		final Ref ref = BY_ID.get(id);
		if (ref == null) {
			return null;
		}
		final ServerLevel level = server.getLevel(ref.dimension());
		if (level == null || !level.hasChunkAt(ref.pos())) {
			return null;
		}
		return level.getBlockEntity(ref.pos()) instanceof ScreenSource s && id.equals(s.screenId()) ? s : null;
	}
}
