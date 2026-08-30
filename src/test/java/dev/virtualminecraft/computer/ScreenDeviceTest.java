package dev.virtualminecraft.computer;

import java.util.UUID;

/** S2 harness for {@link ScreenDevice} outside Minecraft: primitives, text, blit/copy, palette, snapshot round-trip. {@code ./gradlew screenDeviceTest}. */
public final class ScreenDeviceTest {
	private static int failures;

	private static void check(final boolean ok, final String what) {
		System.out.println((ok ? "  PASS " : "  FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	public static void main(final String[] args) {
		final ScreenDevice d = new ScreenDevice(UUID.randomUUID());
		check(!d.hasFramebuffer() && !d.active(), "no monitor: no framebuffer");
		d.clear(5);
		check(!d.active(), "drawing without a framebuffer is a no-op");
		final int[] r11 = ScreenDevice.resolutionFor(1, 1);
		final int[] r43 = ScreenDevice.resolutionFor(4, 3);
		final int[] r32 = ScreenDevice.resolutionFor(3, 2);
		final int[] r81 = ScreenDevice.resolutionFor(8, 1);
		check(r11[0] == 256 && r11[1] == 256 && r43[0] == 1024 && r43[1] == 768 && r32[0] == 768 && r32[1] == 512 && r81[0] == 1024 && r81[1] == 128,
			"resolution per monitor: 1x1=" + r11[0] + "x" + r11[1] + " 4x3=" + r43[0] + "x" + r43[1] + " 3x2=" + r32[0] + "x" + r32[1] + " 8x1=" + r81[0] + "x" + r81[1]);
		check(d.resize(256, 256) && d.hasFramebuffer() && !d.active(), "resize creates a blank framebuffer (not active until drawn)");
		d.clear(1);
		check(d.active() && d.get(0, 0) == 1 && d.get(255, 255) == 1, "clear fills every pixel");
		d.fillRect(10, 10, 20, 20, 9);
		d.rect(5, 5, 30, 30, 8);
		d.line(0, 0, 255, 255, 7);
		check(d.get(15, 20) == 9 && d.get(5, 20) == 8 && d.get(34, 20) == 8 && d.get(100, 100) == 7 && d.get(200, 100) == 1, "fill, rect outline, diagonal line");
		d.circle(128, 128, 20, 12, true);
		check(d.get(128, 128) == 12 && d.get(128, 148) == 12 && d.get(128, 150) != 12, "filled circle");
		final int w = d.text(0, 200, "Hi".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1), 7, 0, 0);
		int lit = 0;
		for (int y = 200; y < 216; y++) {
			for (int x = 0; x < 16; x++) {
				if (d.get(x, y) == 7) {
					lit++;
				}
			}
		}
		check(w == 16 && lit > 10 && lit < 120, "8x16 text draws glyphs (" + lit + " pixels lit for 'Hi')");
		final byte[] sprite = new byte[16];
		java.util.Arrays.fill(sprite, (byte) 3);
		sprite[0] = (byte) 0xFF;
		d.blit(240, 240, 4, 4, sprite, 4, 0xFF);
		check(d.get(241, 241) == 3 && d.get(240, 240) != 3, "blit with a transparent key");
		final byte[] back = d.read(240, 240, 4, 4);
		check(back.length == 16 && back[5] == 3, "read back");
		d.copy(240, 240, 4, 4, 0, 0);
		check(d.get(1, 1) == 3, "screen-to-screen copy");
		d.clip(0, 0, 10, 10);
		d.fillRect(0, 0, 256, 256, 14);
		d.clip(0, 0, 0, 0);
		check(d.get(5, 5) == 14 && d.get(20, 20) != 14, "clip limits drawing");
		check(d.palette(7) == 0xFFF1E8 && d.palette(255) != 0, "default palette");
		d.setPalette(7, 0x123456);
		check(d.palette(7) == 0x123456, "palette set");
		final byte[] snap = d.snapshot();
		final ScreenDevice e = new ScreenDevice(UUID.randomUUID());
		check(e.restore(snap) && e.width() == 256 && e.get(5, 5) == 14 && e.get(128, 128) == 12 && e.palette(7) == 0x123456 && e.active(),
			"snapshot round-trip (" + snap.length + " bytes for 256x256)");
		// the idle park: the framebuffer goes back to the heap, the picture survives deflated and comes back on a touch
		final long seqBefore = d.drawSeq();
		check(d.park() && d.parked() && d.parkedBytes() < 65536 && !d.park(),
			"park gives 64 KB of framebuffer back for " + d.parkedBytes() + " deflated bytes");
		check(d.parked() && java.util.Arrays.equals(d.snapshot(), snap), "a parked screen snapshots to the same bytes without unparking");
		check(d.get(5, 5) == 14 && !d.parked() && d.get(128, 128) == 12 && d.palette(7) == 0x123456 && d.active(),
			"a read unparks and the picture is exactly what it was");
		check(d.drawSeq() == seqBefore, "parking and unparking is not a draw");
		d.park();
		d.pixel(3, 3, 11);
		check(!d.parked() && d.get(3, 3) == 11 && d.get(5, 5) == 14, "a draw unparks too, on top of the old picture");
		check(d.resize(0, 0) && !d.hasFramebuffer(), "resize to 0x0 drops the framebuffer");
		// the tier ladder's caps (U3b): a Basic Computer is 256x256 and 16 settable colours; a wall fits inside the cap
		final ScreenDevice c = new ScreenDevice(UUID.randomUUID());
		c.resize(512, 512);
		c.setLimits(256, 256, 16);
		check(c.width() == 256 && c.height() == 256, "a smaller cap shrinks a live picture (" + c.width() + "x" + c.height() + ")");
		check(!c.resize(512, 384) && c.width() == 256, "resize above the cap is clamped to it");
		final int before = c.palette(200);
		c.setPalette(200, 0x654321);
		c.setPalette(3, 0x123456);
		check(c.palette(200) == before && c.palette(3) == 0x123456, "palette writes above the colour cap are ignored, below it land");
		final int[] wall = ScreenDevice.resolutionFor(2, 2, 256, 256);
		final int[] wall3 = ScreenDevice.resolutionFor(4, 3, 1024, 768);
		check(wall[0] == 256 && wall[1] == 256 && wall3[0] == 1024 && wall3[1] == 768, "resolutionFor fits the monitor inside the tier's cap (2x2 -> " + wall[0] + "x" + wall[1] + ")");
		System.out.println(failures == 0 ? "ALL PASS" : failures + " FAILED");
		System.exit(failures == 0 ? 0 : 1);
	}
}
