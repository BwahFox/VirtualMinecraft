package dev.virtualminecraft.vm;

public enum VmStatus {
	STOPPED,
	STARTING,
	RUNNING,
	ERROR,
	/** Not running, but a RAM+disk snapshot is on disk and will be resumed when the computer is next started/loaded. */
	SUSPENDED;

	public static VmStatus byOrdinal(final int ordinal) {
		final VmStatus[] values = values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : STOPPED;
	}
}
