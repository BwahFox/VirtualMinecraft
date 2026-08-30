package dev.virtualminecraft.dbus;

/**
 * The one question to ask before touching {@link Libc}: does this JVM have {@code java.lang.foreign} at all?
 * <p>
 * It is final in Java 22. The 26.2 build runs on 25 and the answer is always yes; the 1.20.1 backport targets Java
 * 21 and compiles {@code Libc} separately for 22, so on a 21 JVM the class must never be <em>loaded</em> -- the
 * {@code &&} below short-circuits before the JVM resolves it. Every path into {@code Libc} goes through here.
 */
public final class Ffm {
	private Ffm() {
	}

	/** Whether FFM and libc are usable here at all (Linux JVMs of 22 or later; never on Windows, where none of this is needed). */
	public static boolean available() {
		try {
			return Runtime.version().feature() >= 22 && Libc.available();
		} catch (final Throwable t) {
			return false;
		}
	}
}
