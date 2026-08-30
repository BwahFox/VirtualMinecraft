package dev.virtualminecraft.screen;

import dev.virtualminecraft.block.MonitorBlockEntity;
import dev.virtualminecraft.net.VmInputPayload;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * A block entity that owns a screen: the thing a {@link MonitorBlockEntity} links to and shows. The streaming stack
 * ({@code ScreenInfo}/{@code ScreenRect} payloads, {@code ScreenTextures}, {@link ScreenViewers}) is keyed only by
 * {@link #screenId()}, so anything that can push frames under a UUID can drive a monitor — the VM computer today,
 * the in-JVM computer of milestone 7 next — without the monitor knowing which it is.
 * <p>
 * Implementations are block entities. The methods deliberately do NOT share a name with any {@code BlockEntity}
 * method ({@code getBlockPos} and friends): the 1.20.1 jar is remapped to intermediary names, which renames
 * {@code BlockEntity.getBlockPos} to {@code method_11016} but leaves an interface's own {@code getBlockPos} alone —
 * so nothing implements it any more and the first call is an {@code AbstractMethodError} (v1.0.1, 2026-08-30).
 * The 1.20.1 release jar is checked for this by {@code tools/check-remap-collisions.py}.
 */
public interface ScreenSource {
	/** The UUID frames for this screen are streamed under. Stable across restarts. */
	UUID screenId();

	/** Where the machine is — the monitor's audio position, and the origin for component locations. */
	BlockPos screenPos();

	/** Shown as the title of the full-screen view. */
	String screenName();

	/** A monitor linked to this source is ticking; sources keep the set so they can draw on their monitors. */
	void registerMonitor(BlockPos monitorPos);

	/** A player clicked a text-mode monitor of this source at a (1-based) cell. */
	void monitorTouched(ServerLevel level, MonitorBlockEntity monitor, int cellX, int cellY, ServerPlayer player);

	/** Whether there is a live picture to click on (both sides; decides click-vs-open-the-view on a monitor face). */
	boolean screenActive();

	/** Full screen size in pixels, {@code {0, 0}} when nothing is being shown. Server side only. */
	int[] screenSize();

	/** Keyboard / pointer events for this screen, in full-resolution pixel coordinates. Server side only. */
	void screenInput(ServerPlayer player, List<VmInputPayload.Event> events);

	/** Clipboard text pasted into the full-screen view. Sources that cannot take it ignore it. */
	default void screenPaste(final ServerPlayer player, final String text) {
	}
}
