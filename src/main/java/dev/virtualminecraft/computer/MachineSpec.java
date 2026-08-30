package dev.virtualminecraft.computer;

import dev.virtualminecraft.util.Nums;
import com.google.gson.JsonObject;

/**
 * What a Computer is, once its case and parts are added up (ROADMAP §9 U3b; corrected by §9 U10(a), 2026-08-29).
 * <b>The parts are the machine and the case is only a ceiling</b> — [name]: <i>"the cases should only dictate the max
 * spec they can use"</i>. Each axis is the part's own value capped by what the case allows, and an empty slot is
 * <em>nothing</em>, not a free floor: no RAM or no processor and the machine does not boot at all ("dead box, full
 * stop"), no graphics card and it runs with no picture to send a monitor, no drive and it boots the ROM with no
 * {@code /disk}. Pure numbers, no Minecraft types: the block entity turns its slots into levels, the harness and
 * the emulator build one straight from levels.
 *
 * @param tier           1..3
 * @param memMb          the Lua heap budget; 0 when there is no memory in the case
 * @param cpuPercent     the scheduler share of one core; 0 when there is no processor
 * @param maxW           the screen cap (the monitor's 256 px per block is fitted inside it); 0 with no graphics card
 * @param maxH           the screen cap; 0 with no graphics card
 * @param colours        how many palette entries a program may set (the rest stay the default cube); 0 with no card
 * @param diskKb         the {@code /disk} quota; 0 when there is no drive, and then there is no {@code /disk} at all
 * @param synthChannels  sound chip synth voices that play (1..4) — the case's, not a part's
 * @param sampleChannels sound chip sample channels that play (0..2) — the case's
 */
public record MachineSpec(int tier, int memMb, int cpuPercent, int maxW, int maxH, int colours, int diskKb, int synthChannels, int sampleChannels) {
	public static final int TIERS = 3;
	public static final int LEVELS = 3;

	/** The four part kinds — one slot each in every case, in this order in the GUI. */
	public enum Part {
		RAM, CPU, GRAPHICS, DRIVE;

		public static final Part[] ALL = values();
	}

	// per tier: { the old bare-case floor (kept only for the U10 migration), the ceiling }
	private static final int[][] MEM_MB = { { 1, 2 }, { 4, 8 }, { 8, 16 } };
	private static final int[][] CPU_PCT = { { 15, 25 }, { 25, 35 }, { 25, 50 } };
	private static final int[][] SCREEN_W = { { 256, 256 }, { 256, 512 }, { 512, 1024 } };
	private static final int[][] SCREEN_H = { { 256, 256 }, { 256, 512 }, { 512, 768 } };
	private static final int[][] COLOURS = { { 16, 256 }, { 256, 256 }, { 256, 256 } };
	private static final int[][] DISK_KB = { { 256, 2048 }, { 1024, 8192 }, { 4096, 32768 } };
	private static final int[] SYNTH = { 3, 4, 4 };
	private static final int[] SAMPLES = { 0, 2, 2 };
	// per part level 1..3
	private static final int[] RAM_MB = { 2, 8, 16 };
	private static final int[] CPU_LEVEL_PCT = { 25, 35, 50 };
	private static final int[] GFX_W = { 256, 512, 1024 };
	private static final int[] GFX_H = { 256, 512, 768 };
	private static final int[] GFX_COLOURS = { 16, 256, 256 };
	private static final int[] DRIVE_KB = { 2048, 8192, 32768 };

	public static final String[] TIER_NAMES = { "Basic Computer", "Computer", "Advanced Computer" };

	/** Value of one part of a kind at a level (1..3), in the axis's unit; the GUI and tooltips use it. */
	public static int partValue(final Part part, final int level) {
		final int i = Nums.clamp(level, 1, LEVELS) - 1;
		return switch (part) {
			case RAM -> RAM_MB[i];
			case CPU -> CPU_LEVEL_PCT[i];
			case GRAPHICS -> GFX_W[i];
			case DRIVE -> DRIVE_KB[i];
		};
	}

	/** The human line for a part: "8 MB", "35 % CPU", "512×512, 256 colours", "8 MB disk". */
	public static String partLabel(final Part part, final int level) {
		final int i = Nums.clamp(level, 1, LEVELS) - 1;
		return switch (part) {
			case RAM -> RAM_MB[i] + " MB";
			case CPU -> CPU_LEVEL_PCT[i] + " % of a core";
			case GRAPHICS -> GFX_W[i] + "×" + GFX_H[i] + ", " + GFX_COLOURS[i] + " colours";
			case DRIVE -> kb(DRIVE_KB[i]) + " disk";
		};
	}

	/** The case is a ceiling and nothing else (§9 U10): the part's value, capped by what this case allows. */
	private static int cap(final int[][] table, final int tier, final int value) {
		return Math.min(value, table[tier - 1][1]);
	}

	/**
	 * The spec of a case with these part levels (0 = the slot is empty, and an empty slot contributes nothing).
	 *
	 * @param memCeilingMb the config's {@code maxComputerMemMb}: a server may cap memory under the case's ceiling
	 */
	public static MachineSpec of(final int tier, final int ram, final int cpu, final int graphics, final int drive, final int memCeilingMb) {
		final int t = Nums.clamp(tier, 1, TIERS);
		final int mem = ram > 0 ? Math.min(Math.max(1, memCeilingMb), cap(MEM_MB, t, RAM_MB[Nums.clamp(ram, 1, LEVELS) - 1])) : 0;
		final int cpuPct = cpu > 0 ? cap(CPU_PCT, t, CPU_LEVEL_PCT[Nums.clamp(cpu, 1, LEVELS) - 1]) : 0;
		final int gi = Nums.clamp(graphics, 1, LEVELS) - 1;
		final int w = graphics > 0 ? cap(SCREEN_W, t, GFX_W[gi]) : 0;
		final int h = graphics > 0 ? cap(SCREEN_H, t, GFX_H[gi]) : 0;
		final int colours = graphics > 0 ? cap(COLOURS, t, GFX_COLOURS[gi]) : 0;
		final int disk = drive > 0 ? cap(DISK_KB, t, DRIVE_KB[Nums.clamp(drive, 1, LEVELS) - 1]) : 0;
		return new MachineSpec(t, mem, cpuPct, w, h, colours, disk, SYNTH[t - 1], SAMPLES[t - 1]);
	}

	/** An empty case of this tier — a dead box now, and the name is kept because that is exactly what it is. */
	public static MachineSpec bare(final int tier) {
		return of(tier, 0, 0, 0, 0, 64);
	}

	/** The best machine this case can hold: every slot at level III. The emulator's and the harness's default. */
	public static MachineSpec fitted(final int tier) {
		return of(tier, LEVELS, LEVELS, LEVELS, LEVELS, 64);
	}

	// ---- what a case needs before it is a machine at all (§9 U10(a), [name]: "dead box, full stop") ----

	/** Memory and a processor. Graphics and a drive are optional: without them it runs blind, or with no {@code /disk}. */
	public boolean canBoot() {
		return memMb > 0 && cpuPercent > 0;
	}

	public boolean hasGraphics() {
		return maxW > 0 && maxH > 0;
	}

	public boolean hasDrive() {
		return diskKb > 0;
	}

	/**
	 * Why this case will not boot, in the words the case GUI, the monitors and {@code /vmc} all use — or null when
	 * it will. Never a bare "waiting": HANDOFF (p) records what that one cost.
	 */
	public @org.jspecify.annotations.Nullable String bootRefusal() {
		if (cpuPercent <= 0 && memMb <= 0) {
			return "no processor or memory";
		}
		if (cpuPercent <= 0) {
			return "no processor";
		}
		return memMb <= 0 ? "no memory" : null;
	}

	/**
	 * The same, in the words the case GUI has room for. The status sits right of the case's name in a 176 px panel,
	 * which is about twelve characters — "no processor or memory" was drawn as "no process" until this existed.
	 */
	public @org.jspecify.annotations.Nullable String shortRefusal() {
		if (cpuPercent <= 0 && memMb <= 0) {
			return "no CPU/RAM";
		}
		if (cpuPercent <= 0) {
			return "no CPU";
		}
		return memMb <= 0 ? "no RAM" : null;
	}

	/** The smallest level of {@code part} worth at least {@code value} — how the §9 U10 migration picks what to fit. */
	public static int levelFor(final Part part, final int value) {
		for (int lv = 1; lv <= LEVELS; lv++) {
			if (partValue(part, lv) >= value) {
				return lv;
			}
		}
		return LEVELS;
	}

	/**
	 * What an existing case must be given so nothing already built in a world goes dark (§9 U10(a): [name] accepted
	 * the migration when she chose the dead box) — the cheapest parts worth at least what a bare case of this tier
	 * used to hand out for free. In {@link Part#ALL} order.
	 */
	public static int[] migrationLevels(final int tier) {
		final int t = Nums.clamp(tier, 1, TIERS) - 1;
		// The graphics card has to satisfy three numbers at once, and the widest is not always the deepest: a bare
		// Computer used to draw 256x256 in 256 colours, which is a Graphics Card I by width and a II by palette.
		int gfx = 1;
		while (gfx < LEVELS && (GFX_W[gfx - 1] < SCREEN_W[t][0] || GFX_H[gfx - 1] < SCREEN_H[t][0] || GFX_COLOURS[gfx - 1] < COLOURS[t][0])) {
			gfx++;
		}
		return new int[] {
			levelFor(Part.RAM, MEM_MB[t][0]),
			levelFor(Part.CPU, CPU_PCT[t][0]),
			gfx,
			levelFor(Part.DRIVE, DISK_KB[t][0]),
		};
	}

	/** The smallest RAM level that gives at least {@code memMb} (the migration of a saved {@code memMb} into a part). */
	public static int ramLevelFor(final int memMb) {
		for (int i = 0; i < LEVELS; i++) {
			if (RAM_MB[i] >= memMb) {
				return i + 1;
			}
		}
		return LEVELS;
	}

	public static int baseMemMb(final int tier) {
		return MEM_MB[Nums.clamp(tier, 1, TIERS) - 1][0];
	}

	public static int ceilingMemMb(final int tier) {
		return MEM_MB[Nums.clamp(tier, 1, TIERS) - 1][1];
	}

	/** The ceiling of an axis for the GUI's "up to" hints. */
	public static String ceilingLabel(final Part part, final int tier) {
		final int t = Nums.clamp(tier, 1, TIERS) - 1;
		return switch (part) {
			case RAM -> MEM_MB[t][1] + " MB";
			case CPU -> CPU_PCT[t][1] + " %";
			case GRAPHICS -> SCREEN_W[t][1] + "×" + SCREEN_H[t][1] + ", " + COLOURS[t][1] + " colours";
			case DRIVE -> kb(DISK_KB[t][1]);
		};
	}

	public String tierName() {
		return TIER_NAMES[tier - 1];
	}

	/** The tier's default: a Basic Computer boots into the shell, the others into the desktop. */
	public boolean desktopByDefault() {
		return tier > 1;
	}

	public double cpuShare() {
		return cpuPercent / 100.0;
	}

	public long memoryCapBytes() {
		return memMb * 1024L * 1024L;
	}

	public long diskQuotaBytes() {
		return diskKb * 1024L;
	}

	public static String kb(final int kb) {
		return kb >= 1024 && kb % 1024 == 0 ? (kb / 1024) + " MB" : kb + " KB";
	}

	/** One line, for {@code /vmc computer state} and the log. Missing parts are named, not written as zeroes. */
	public String describe() {
		final String refusal = bootRefusal();
		final String head = refusal != null ? refusal + " — will not boot"
			: memMb + " MB, CPU " + cpuPercent + " %";
		return tierName() + ": " + head
			+ ", screen " + (hasGraphics() ? "≤ " + maxW + "×" + maxH + " " + colours + " colours" : "none")
			+ ", disk " + (hasDrive() ? kb(diskKb) : "none")
			+ ", sound " + synthChannels + "+" + sampleChannels;
	}

	/** What the machine sees through {@code vmc.info()} / {@code os.info()}. */
	public JsonObject json() {
		final JsonObject o = new JsonObject();
		o.addProperty("tier", tier);
		o.addProperty("tierName", tierName());
		o.addProperty("mem", memMb);
		o.addProperty("cpu", cpuPercent);
		o.addProperty("maxw", maxW);
		o.addProperty("maxh", maxH);
		o.addProperty("colours", colours);
		o.addProperty("disk", diskKb);
		o.addProperty("synth", synthChannels);
		o.addProperty("samples", sampleChannels);
		// §9 U10(a): a program can ask what the case is actually holding, and the ROM needs "is there a /disk".
		o.addProperty("graphics", hasGraphics());
		o.addProperty("drive", hasDrive());
		o.addProperty("boots", canBoot());
		return o;
	}
}
