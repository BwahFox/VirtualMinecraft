package dev.virtualminecraft.client.pointer;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The registry behind the seam (ROADMAP §9 U4.0). The main mod registers the camera; the {@code vr} module
 * registers a controller; the puppet registers a fake. Once a tick {@link #tick()} asks the highest-priority
 * source that is actually pointing, and hands its ray to {@link WorldPointer}.
 * <p>
 * <b>The load-bearing promise: with nothing else registered, this is exactly what the game did before it
 * existed.</b> {@link CameraPointerSource} is the crosshair, its buttons are always zero, and clicks stay the
 * server's business — so a build without the VR jar behaves identically, and TESTING's monitor recipes are the
 * regression test for that.
 */
public final class Pointers {
	private static final List<PointerSource> SOURCES = new ArrayList<>();
	private static final List<TextSource> TEXT = new ArrayList<>();
	private static @Nullable PointerSource lastActive;

	private Pointers() {
	}

	public static void register(final PointerSource source) {
		if (source != null && !SOURCES.contains(source)) {
			SOURCES.add(source);
			SOURCES.sort((a, b) -> Integer.compare(b.priority(), a.priority()));
			VirtualMinecraft.LOGGER.info("pointer source registered: {} (priority {})", source.name(), source.priority());
		}
	}

	public static void unregister(final PointerSource source) {
		if (SOURCES.remove(source)) {
			VirtualMinecraft.LOGGER.info("pointer source removed: {}", source.name());
			if (lastActive == source) {
				WorldPointer.release();
				lastActive = null;
			}
		}
	}

	public static void registerText(final TextSource source) {
		if (source != null && !TEXT.contains(source)) {
			TEXT.add(source);
			VirtualMinecraft.LOGGER.info("text source registered: {}", source.name());
		}
	}

	public static void unregisterText(final TextSource source) {
		TEXT.remove(source);
	}

	/** Which source drove the pointer last tick, for the puppet and for logs. */
	public static @Nullable PointerSource active() {
		return lastActive;
	}

	public static List<PointerSource> sources() {
		return List.copyOf(SOURCES);
	}

	/** Once a client tick, after {@code InputSender.tick()}. */
	public static void tick() {
		PointerSource chosen = null;
		PointerRay ray = null;
		for (final PointerSource s : SOURCES) {
			final PointerRay r = s.ray();
			if (r != null) {
				chosen = s;
				ray = r;
				break; // sorted by priority, so the first one pointing is the winner
			}
		}
		if (chosen != lastActive) {
			// The old source stops owning the guest's mouse: release its buttons rather than leaving one stuck
			// down on a screen nobody is pointing at any more.
			WorldPointer.release();
			lastActive = chosen;
		}
		if (chosen == null) {
			return;
		}
		WorldPointer.aim(ray, chosen.buttons(), chosen.takeWheel());
		for (final TextSource t : TEXT) {
			final String text = t.takeText();
			if (text != null && !text.isEmpty()) {
				WorldPointer.type(text);
			}
		}
	}
}
