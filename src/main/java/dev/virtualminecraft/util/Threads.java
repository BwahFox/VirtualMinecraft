package dev.virtualminecraft.util;

/**
 * The two thread idioms the shared code used from Java 19–21, written for Java 17 (the 1.20.1 build's target).
 * {@code Thread.ofVirtual()} became a named daemon platform thread: every use was a short, blocking QMP call or a
 * process reap, one at a time per VM, so a platform thread costs nothing that matters and behaves the same.
 * {@code Thread.threadId()} is {@code getId()}, whose deprecation (since 19, not for removal) is confined here.
 */
public final class Threads {
	private Threads() {
	}

	/** An unstarted daemon thread named {@code name} that will run {@code body}. */
	public static Thread daemon(final String name, final Runnable body) {
		final Thread t = new Thread(body, name);
		t.setDaemon(true);
		return t;
	}

	/** {@link #daemon}, started. */
	public static Thread startDaemon(final String name, final Runnable body) {
		final Thread t = daemon(name, body);
		t.start();
		return t;
	}

	/** {@code t.threadId()} (Java 19) on any Java the mod runs on. */
	@SuppressWarnings("deprecation")
	public static long id(final Thread t) {
		return t.getId();
	}
}
