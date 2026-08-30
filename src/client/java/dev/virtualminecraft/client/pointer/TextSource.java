package dev.virtualminecraft.client.pointer;

/**
 * Somewhere text arrives from that is not the physical keyboard (ROADMAP §9 U4.0): Vivecraft's floating keyboard
 * today, the placeable keyboard block later. The other half of the seam, and the same rule applies — it is a
 * string, because a string survives both a Minecraft update and a Vivecraft one.
 * <p>
 * Whatever this returns is replayed into whichever machine the pointer is on, as characters. Keys that are not
 * characters (Enter, Backspace, the arrows) are not this interface's job: a source that needs them sends them as
 * scancodes through the same path the real keyboard uses, because a machine that reads scancodes must not be able
 * to tell a poked key from a pressed one.
 */
public interface TextSource {
	/** A name for logs and the puppet. */
	String name();

	/**
	 * Characters typed since the last call, or an empty string. <em>Taken</em>, like {@link PointerSource#takeWheel}:
	 * a source that returned the same text twice would type it twice.
	 */
	String takeText();
}
