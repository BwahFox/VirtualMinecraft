package dev.virtualminecraft.util;

/**
 * {@code Math.clamp} for Java 17. The 1.20.1 build ({@code mc1.20.1/}) compiles the shared sources at
 * {@code --release 17}, and {@code Math.clamp} arrived in 21; these four overloads have exactly its shapes and
 * semantics (note the {@code (long, int, int) -> int} form, which is the one every int call resolves to), so
 * every call site reads the same and behaves the same on both builds. Do not add anything else here that
 * {@code java.lang.Math} already has.
 */
public final class Nums {
	private Nums() {
	}

	/** {@code Math.clamp(long, int, int)}: clamps {@code value} into {@code [min, max]} and returns it as an int. */
	public static int clamp(final long value, final int min, final int max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return (int) Math.min(max, Math.max(value, min));
	}

	/** {@code Math.clamp(long, long, long)}. */
	public static long clamp(final long value, final long min, final long max) {
		if (min > max) {
			throw new IllegalArgumentException(min + " > " + max);
		}
		return Math.min(max, Math.max(value, min));
	}

	/** {@code Math.clamp(double, double, double)}: NaN bounds are rejected, a NaN value comes back as NaN. */
	public static double clamp(final double value, final double min, final double max) {
		if (!(min < max)) {
			if (Double.isNaN(min)) {
				throw new IllegalArgumentException("min is NaN");
			}
			if (Double.isNaN(max)) {
				throw new IllegalArgumentException("max is NaN");
			}
			if (Double.compare(min, max) > 0) {
				throw new IllegalArgumentException(min + " > " + max);
			}
		}
		return Math.min(max, Math.max(value, min));
	}

	/** {@code Math.clamp(float, float, float)}: NaN bounds are rejected, a NaN value comes back as NaN. */
	public static float clamp(final float value, final float min, final float max) {
		if (!(min < max)) {
			if (Float.isNaN(min)) {
				throw new IllegalArgumentException("min is NaN");
			}
			if (Float.isNaN(max)) {
				throw new IllegalArgumentException("max is NaN");
			}
			if (Float.compare(min, max) > 0) {
				throw new IllegalArgumentException(min + " > " + max);
			}
		}
		return Math.min(max, Math.max(value, min));
	}
}
