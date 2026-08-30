package dev.virtualminecraft.computer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** S3 harness for {@link MachineFiles} outside Minecraft: paths, quotas, the ROM from the classpath, format. {@code ./gradlew machineFilesTest}. */
public final class MachineFilesTest {
	private static int failures;

	private static void check(final boolean ok, final String what) {
		System.out.println((ok ? "  PASS " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	private static String err(final Runnable r) {
		try {
			r.run();
			return "";
		} catch (final RuntimeException e) {
			return e.getMessage() == null ? e.toString() : e.getMessage();
		}
	}

	@FunctionalInterface
	interface Op {
		void run() throws LuaMachine.MachineError;
	}

	private static String fail(final Op op) {
		try {
			op.run();
			return "";
		} catch (final LuaMachine.MachineError e) {
			return e.getMessage();
		}
	}

	public static void main(final String[] args) throws Exception {
		final Path tmp = Files.createTempDirectory("vmc-files");
		final Path machine = tmp.resolve("computers").resolve("m1");
		final Path items = tmp.resolve("items");
		Files.createDirectories(items);
		final MachineFiles f = new MachineFiles(machine, items, tmp.resolve("config"), 8L << 20);

		// floppy templates (U3c step 2): the shipped Starter resolves, seeds a fresh disk, and a world's own copy wins
		check(MachineFiles.bundledFloppy("starter") != null, "the Starter floppy template ships in the mod");
		check(MachineFiles.bundledFloppy("no-such") == null && MachineFiles.bundledFloppy("../x") == null, "unknown and bad template names are null");
		final Path seeded = items.resolve("11111111-1111-1111-1111-111111111111");
		MachineFiles.seedFloppy(seeded, "starter", tmp.resolve("config"));
		check(Files.isRegularFile(seeded.resolve("main.lua")) && Files.isRegularFile(seeded.resolve("program.txt")), "seeding copies the template onto the disk");
		final Path own = MachineFiles.floppiesDir(tmp.resolve("config")).resolve("starter");
		Files.createDirectories(own);
		Files.writeString(own.resolve("main.lua"), "return 'mine'");
		check(MachineFiles.floppyTemplate("starter", tmp.resolve("config")).equals(own), "a template in the world's config dir overrides the shipped one");
		final Path blank = items.resolve("22222222-2222-2222-2222-222222222222");
		MachineFiles.seedFloppy(blank, "no-such", tmp.resolve("config"));
		check(Files.isDirectory(blank) && !Files.isRegularFile(blank.resolve("main.lua")), "a missing template leaves an empty disk, not a failure");

		// ROM from the classpath (a directory in dev)
		final String boot = MachineFiles.utf8(f.read("/rom/boot.lua"));
		check(boot.contains("The ROM"), "read /rom/boot.lua from the classpath (" + boot.length() + " bytes)");
		check(f.list("/rom").contains("boot.lua"), "list /rom");
		check(fail(() -> f.write("/rom/x", new byte[1], false)).contains("read-only"), "ROM is read-only");

		// internal disk: write, list, stat, read, rename, remove
		f.write("/disk/hello.txt", "hi there".getBytes(StandardCharsets.UTF_8), false);
		f.write("/disk/hello.txt", "!".getBytes(StandardCharsets.UTF_8), true);
		check(MachineFiles.utf8(f.read("/disk/hello.txt")).equals("hi there!"), "write + append + read");
		f.mkdir("/disk/sub/deeper");
		f.write("/disk/sub/deeper/a.lua", "return 1".getBytes(StandardCharsets.UTF_8), false);
		check(f.list("/disk").contains("\"name\":\"hello.txt\"") && f.list("/disk").contains("\"dir\":true"), "list shows files and dirs: " + f.list("/disk"));
		check(f.stat("/disk/sub").contains("\"dir\":true") && f.stat("/disk/nope").equals("null"), "stat");
		f.rename("/disk/hello.txt", "/disk/sub/hello.txt");
		check(f.stat("/disk/hello.txt").equals("null") && !f.stat("/disk/sub/hello.txt").equals("null"), "rename");
		f.remove("/disk/sub");
		check(f.stat("/disk/sub").equals("null"), "remove a tree");

		// path rules
		check(fail(() -> f.read("/disk/../secret")).contains("bad name"), "'..' refused");
		check(fail(() -> f.read("/disk/a b")).contains("bad name"), "space refused");
		check(fail(() -> f.read("/nowhere/x")).contains("no such mount"), "unknown mount refused");
		check(fail(() -> f.read("/disk/" + "d/".repeat(20) + "x")).contains("too deep"), "depth cap");

		// quota: the default 8 MB disk refuses a 9 MB write, accepts 1 MB
		check(fail(() -> f.write("/disk/big", new byte[9 << 20], false)).contains("disk full"), "quota refuses 9 MB on an 8 MB disk");
		f.write("/disk/ok", new byte[1 << 20], false);
		check(f.read("/disk/ok").length == 1 << 20, "1 MB write fits");

		// format wipes the disk
		f.format("disk");
		check(f.list("/disk").equals("[]"), "format empties /disk");

		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		Files.walk(tmp).sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		System.exit(failures == 0 ? 0 : 1);
	}
}
