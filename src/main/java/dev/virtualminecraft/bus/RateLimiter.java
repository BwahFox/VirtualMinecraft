package dev.virtualminecraft.bus;

/**
 * A token bucket for the components a guest can use to bother players (sounds, chat). The guest is
 * untrusted and can call as fast as the bus lets it, so anything that leaves the computer's own block is
 * budgeted here: {@code capacity} calls, refilled at {@code perSecond}, and a rejection is a normal
 * JSON-RPC error the guest can back off on rather than a silently dropped call.
 * <p>
 * Server thread only; time comes from the level's game time so a paused/idle server does not refill.
 */
public final class RateLimiter {
	private final float capacity;
	private final float perTick;
	private float tokens;
	private long lastTick = Long.MIN_VALUE;

	public RateLimiter(final float capacity, final float perSecond) {
		this.capacity = capacity;
		this.perTick = perSecond / 20f;
		this.tokens = capacity;
	}

	/** Takes one token; false if the bucket is empty. {@code gameTime} is {@code level.getGameTime()}. */
	public boolean tryAcquire(final long gameTime) {
		if (lastTick == Long.MIN_VALUE) {
			lastTick = gameTime;
		}
		final long elapsed = Math.max(0, gameTime - lastTick);
		lastTick = gameTime;
		tokens = Math.min(capacity, tokens + elapsed * perTick);
		if (tokens < 1f) {
			return false;
		}
		tokens -= 1f;
		return true;
	}

	/** Seconds until the next token, for the error message. */
	public int retryInSeconds() {
		if (tokens >= 1f || perTick <= 0f) {
			return 0;
		}
		return Math.max(1, Math.round((1f - tokens) / perTick / 20f));
	}
}
