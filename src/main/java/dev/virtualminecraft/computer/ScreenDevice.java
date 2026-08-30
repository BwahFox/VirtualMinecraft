package dev.virtualminecraft.computer;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.net.ScreenInfoPayload;
import dev.virtualminecraft.net.ScreenCursorPayload;
import dev.virtualminecraft.net.ScreenPalettePayload;
import dev.virtualminecraft.net.ScreenRectPayload;
import dev.virtualminecraft.net.ViewerPayload;
import dev.virtualminecraft.screen.ScreenViewers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/**
 * The Computer's graphics device (ROADMAP §7h §3): an indexed-colour framebuffer with a 256-entry palette, the
 * drawing primitives the machine calls through {@code vmc.gfx_*}, dirty-rectangle tracking, and the per-tick flush
 * that reuses the VM tier's streaming stack — level 0 viewers get {@link ScreenRectPayload#FORMAT_ZLIB_INDEXED}
 * bands plus a {@link ScreenPalettePayload}; coarser levels get the palette applied and box-filtered into the
 * existing RGB format. The framebuffer is the server's cost, not the machine's budget; it exists only while the
 * machine has a monitor, is deflated to a few KB for a freeze, and is *parked* the same way — given back to the
 * heap, picture and all — once nobody has watched it and nothing has been drawn for a while ({@link #park()}).
 * <p>
 * Threads: primitives run on the machine's worker; {@link #flush} on the server thread. Everything that touches
 * the pixels or the dirty list is synchronized on this object; the primitives are short, so the lock is cheap.
 */
public final class ScreenDevice {
	public static final int MAX_WIDTH = 1024;
	public static final int MAX_HEIGHT = 768;
	private static final int MAX_BAND_BYTES = 384 * 1024;
	private static final int MAX_DIRTY_RECTS = 32;
	private static final byte[] FONT_8X16 = loadFont("font8x16.bin", 256 * 16);
	private static final byte[] FONT_6X8 = loadFont("font6x8.bin", 256 * 8);

	private final UUID id;
	private int width;
	private int height;
	private byte[] fb = new byte[0];
	/** The picture, deflated, while the framebuffer is parked ({@link #park()}); null while {@link #fb} is live. */
	private byte[] parked;
	/** Bumped by every {@link #mark}: {@link LuaComputer} watches it to know the picture has gone quiet. */
	private long drawSeq;
	private final int[] palette = new int[256];
	private final List<int[]> dirty = new ArrayList<>();
	private boolean resized;
	private boolean paletteDirty;
	private boolean drawn;
	/** Anything drawn since the last {@link #takeDrawn()}: the block entity uses it to switch monitors out of text mode once per burst. */
	private boolean drawnSinceCheck;
	private int clipX;
	private int clipY;
	private int clipW;
	private int clipH;
	private int flags = ScreenInfoPayload.FLAG_SCANCODES | ScreenInfoPayload.FLAG_CHARS;
	private final Deflater deflater = new Deflater(Deflater.BEST_SPEED);
	private static final int MIN_DEFLATE_BUF = 1 << 16;
	private byte[] deflateBuf = new byte[MIN_DEFLATE_BUF];
	private long frames;
	// The hardware cursor (§3, U1.3): the client draws the sprite at the last pointer position; nothing here touches
	// the framebuffer, so a hover is a 20-byte message instead of a frame. The sprite is indexed with a key colour.
	private int cursorX;
	private int cursorY;
	private boolean cursorVisible;
	private int cursorHotX;
	private int cursorHotY;
	private int cursorW;
	private int cursorH;
	private int cursorKey = -1;
	private byte[] cursorShape = new byte[0];
	private boolean cursorMoved;
	private boolean cursorShapeChanged;
	private static final byte[] NO_SHAPE = new byte[0];

	public ScreenDevice(final UUID id) {
		this.id = id;
		defaultPalette(palette);
	}

	private static byte[] loadFont(final String name, final int size) {
		try (InputStream in = ScreenDevice.class.getResourceAsStream("/virtualminecraft/" + name)) {
			final byte[] b = in == null ? new byte[0] : in.readAllBytes();
			return b.length == size ? b : new byte[size];
		} catch (final IOException e) {
			return new byte[size];
		}
	}

	/** The default palette: 16 DOS/pico-style colours, a 6×6×6 cube (16..231), 24 greys (232..255). */
	public static void defaultPalette(final int[] p) {
		final int[] base = {
			0x000000, 0x1D2B53, 0x7E2553, 0x008751, 0xAB5236, 0x5F574F, 0xC2C3C7, 0xFFF1E8,
			0xFF004D, 0xFFA300, 0xFFEC27, 0x00E436, 0x29ADFF, 0x83769C, 0xFF77A8, 0xFFCCAA
		};
		System.arraycopy(base, 0, p, 0, 16);
		int i = 16;
		for (int r = 0; r < 6; r++) {
			for (int g = 0; g < 6; g++) {
				for (int b = 0; b < 6; b++) {
					p[i++] = (r * 51) << 16 | (g * 51) << 8 | (b * 51);
				}
			}
		}
		for (int k = 0; k < 24; k++) {
			final int v = 8 + k * 10;
			p[i++] = v << 16 | v << 8 | v;
		}
	}

	// ---- size and state ----

	public synchronized int width() {
		return width;
	}

	public synchronized int height() {
		return height;
	}

	/** There is a picture to show (a framebuffer exists and something was drawn into it). */
	public synchronized boolean active() {
		return width > 0 && drawn;
	}

	public synchronized boolean hasFramebuffer() {
		return width > 0;
	}

	/**
	 * Give the framebuffer back to the heap — up to 768 KB per loaded machine — while nobody is watching it and
	 * nothing is being drawn into it. The picture is kept deflated (a flat desktop is a few KB, the same trick a
	 * freeze uses) and comes back on the first touch, so a park is invisible to the machine and to a viewer who
	 * walks up afterwards. {@link LuaComputer#tick} decides when. Returns true if this call did the parking.
	 */
	public synchronized boolean park() {
		if (parked != null || width == 0 || fb.length == 0) {
			return false;
		}
		parked = deflatePixels(fb);
		fb = new byte[0];
		if (deflateBuf.length > MIN_DEFLATE_BUF) {
			deflateBuf = new byte[MIN_DEFLATE_BUF];
		}
		return true;
	}

	/** True while the picture is deflated and no framebuffer is allocated. */
	public synchronized boolean parked() {
		return parked != null;
	}

	/** What the parked picture costs in the meantime (0 when it is not parked). */
	public synchronized int parkedBytes() {
		return parked == null ? 0 : parked.length;
	}

	/** Every draw and every read goes through here: a parked picture is inflated back into place first. */
	private byte[] pixels() {
		if (parked == null) {
			return fb;
		}
		final byte[] px = new byte[width * height];
		final Inflater inf = new Inflater();
		try {
			inf.setInput(parked);
			int got = 0;
			while (got < px.length && !inf.finished()) {
				final int n = inf.inflate(px, got, px.length - got);
				if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
					break;
				}
				got += n;
			}
		} catch (final DataFormatException e) {
			VirtualMinecraft.LOGGER.warn("Screen {}: parked picture unreadable, showing black: {}", id, e.toString());
		} finally {
			inf.end();
		}
		parked = null;
		fb = px;
		return fb;
	}

	private static byte[] deflatePixels(final byte[] px) {
		final Deflater z = new Deflater(Deflater.BEST_SPEED);
		z.setInput(px);
		z.finish();
		final byte[] buf = new byte[65536];
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		while (!z.finished()) {
			out.write(buf, 0, z.deflate(buf));
		}
		z.end();
		return out.toByteArray();
	}

	/** How many times {@link #mark} has fired: {@link LuaComputer} watches it to know the picture has gone quiet. */
	public synchronized long drawSeq() {
		return drawSeq;
	}

	public synchronized long frames() {
		return frames;
	}

	/** The tier ladder's caps (ROADMAP §9 U3b): a picture never exceeds them, palette writes above {@code colours} are ignored. */
	private int maxW = MAX_WIDTH;
	private int maxH = MAX_HEIGHT;
	private int colours = 256;

	/** Cap the resolution and the settable palette (a Basic Computer: 256×256, 16 colours). A smaller cap shrinks a live picture. */
	public synchronized void setLimits(final int w, final int h, final int settableColours) {
		maxW = Math.clamp(w, 16, MAX_WIDTH);
		maxH = Math.clamp(h, 16, MAX_HEIGHT);
		colours = Math.clamp(settableColours, 2, 256);
		if (width > maxW || height > maxH) {
			resize(Math.min(width, maxW), Math.min(height, maxH));
		}
	}

	public synchronized int colours() {
		return colours;
	}

	public synchronized int maxWidth() {
		return maxW;
	}

	public synchronized int maxHeight() {
		return maxH;
	}

	/** Set (or drop, with 0×0) the resolution; the contents are cleared. Returns true if it changed. */
	public synchronized boolean resize(final int w, final int h) {
		final int nw = Math.clamp(w, 0, maxW);
		final int nh = Math.clamp(h, 0, maxH);
		if (nw == width && nh == height) {
			return false;
		}
		width = nw;
		height = nh;
		fb = new byte[nw * nh];
		parked = null;
		clipX = 0;
		clipY = 0;
		clipW = nw;
		clipH = nh;
		dirty.clear();
		resized = true;
		drawn = false;
		return true;
	}

	/** The resolution for a monitor rectangle of {@code mbW × mbH} blocks: 256 px per block, scaled to fit 1024×768. */
	public static int[] resolutionFor(final int mbW, final int mbH) {
		return resolutionFor(mbW, mbH, MAX_WIDTH, MAX_HEIGHT);
	}

	/** The same, fitted inside a tier's cap: a 2×2 wall on a Basic Computer shows a 256×256 picture scaled up. */
	public static int[] resolutionFor(final int mbW, final int mbH, final int capW, final int capH) {
		if (mbW <= 0 || mbH <= 0) {
			return new int[] { 0, 0 };
		}
		final double s = Math.min(1.0, Math.min((double) Math.min(capW, MAX_WIDTH) / (256.0 * mbW), (double) Math.min(capH, MAX_HEIGHT) / (256.0 * mbH)));
		return new int[] { Math.max(16, (int) Math.round(256.0 * mbW * s)), Math.max(16, (int) Math.round(256.0 * mbH * s)) };
	}

	// ---- primitives (worker thread) ----

	private void mark(final int x, final int y, final int w, final int h) {
		final int x0 = Math.max(x, clipX);
		final int y0 = Math.max(y, clipY);
		final int x1 = Math.min(x + w, clipX + clipW);
		final int y1 = Math.min(y + h, clipY + clipH);
		if (x1 > x0 && y1 > y0) {
			dirty.add(new int[] { x0, y0, x1 - x0, y1 - y0 });
			drawSeq++;
			drawn = true;
			drawnSinceCheck = true;
		}
	}

	public synchronized boolean takeDrawn() {
		final boolean d = drawnSinceCheck;
		drawnSinceCheck = false;
		return d;
	}

	private boolean inClip(final int x, final int y) {
		return x >= clipX && y >= clipY && x < clipX + clipW && y < clipY + clipH;
	}

	public synchronized void clip(final int x, final int y, final int w, final int h) {
		if (w <= 0 || h <= 0) {
			clipX = 0;
			clipY = 0;
			clipW = width;
			clipH = height;
			return;
		}
		clipX = Math.clamp(x, 0, width);
		clipY = Math.clamp(y, 0, height);
		clipW = Math.clamp(x + w, 0, width) - clipX;
		clipH = Math.clamp(y + h, 0, height) - clipY;
	}

	public synchronized void clear(final int c) {
		if (width == 0) {
			return;
		}
		fillRect(0, 0, width, height, c);
	}

	public synchronized void pixel(final int x, final int y, final int c) {
		if (inClip(x, y)) {
			pixels()[y * width + x] = (byte) c;
			mark(x, y, 1, 1);
		}
	}

	public synchronized int get(final int x, final int y) {
		return x >= 0 && y >= 0 && x < width && y < height ? pixels()[y * width + x] & 0xFF : 0;
	}

	public synchronized void fillRect(final int x, final int y, final int w, final int h, final int c) {
		final int x0 = Math.max(x, clipX);
		final int y0 = Math.max(y, clipY);
		final int x1 = Math.min(x + w, clipX + clipW);
		final int y1 = Math.min(y + h, clipY + clipH);
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		final byte v = (byte) c;
		final byte[] px = pixels();
		for (int yy = y0; yy < y1; yy++) {
			Arrays.fill(px, yy * width + x0, yy * width + x1, v);
		}
		mark(x0, y0, x1 - x0, y1 - y0);
	}

	public synchronized void rect(final int x, final int y, final int w, final int h, final int c) {
		if (w <= 0 || h <= 0) {
			return;
		}
		line(x, y, x + w - 1, y, c);
		line(x, y + h - 1, x + w - 1, y + h - 1, c);
		line(x, y, x, y + h - 1, c);
		line(x + w - 1, y, x + w - 1, y + h - 1, c);
	}

	public synchronized void line(int x0, int y0, final int x1, final int y1, final int c) {
		final int dx = Math.abs(x1 - x0);
		final int dy = -Math.abs(y1 - y0);
		final int sx = x0 < x1 ? 1 : -1;
		final int sy = y0 < y1 ? 1 : -1;
		int err = dx + dy;
		final int minX = Math.min(x0, x1);
		final int minY = Math.min(y0, y1);
		int steps = 0;
		final byte[] px = pixels();
		while (steps++ < 4096) {
			if (inClip(x0, y0)) {
				px[y0 * width + x0] = (byte) c;
			}
			if (x0 == x1 && y0 == y1) {
				break;
			}
			final int e2 = 2 * err;
			if (e2 >= dy) {
				err += dy;
				x0 += sx;
			}
			if (e2 <= dx) {
				err += dx;
				y0 += sy;
			}
		}
		mark(minX, minY, dx + 1, -dy + 1);
	}

	public synchronized void circle(final int cx, final int cy, final int r, final int c, final boolean fill) {
		if (r < 0) {
			return;
		}
		int x = r;
		int y = 0;
		int err = 1 - r;
		while (x >= y) {
			if (fill) {
				hline(cx - x, cx + x, cy + y, c);
				hline(cx - x, cx + x, cy - y, c);
				hline(cx - y, cx + y, cy + x, c);
				hline(cx - y, cx + y, cy - x, c);
			} else {
				put(cx + x, cy + y, c);
				put(cx - x, cy + y, c);
				put(cx + x, cy - y, c);
				put(cx - x, cy - y, c);
				put(cx + y, cy + x, c);
				put(cx - y, cy + x, c);
				put(cx + y, cy - x, c);
				put(cx - y, cy - x, c);
			}
			y++;
			if (err < 0) {
				err += 2 * y + 1;
			} else {
				x--;
				err += 2 * (y - x) + 1;
			}
		}
		mark(cx - r, cy - r, 2 * r + 1, 2 * r + 1);
	}

	private void put(final int x, final int y, final int c) {
		if (inClip(x, y)) {
			pixels()[y * width + x] = (byte) c;
		}
	}

	private void hline(int x0, int x1, final int y, final int c) {
		if (y < clipY || y >= clipY + clipH) {
			return;
		}
		x0 = Math.max(x0, clipX);
		x1 = Math.min(x1, clipX + clipW - 1);
		if (x1 >= x0) {
			Arrays.fill(pixels(), y * width + x0, y * width + x1 + 1, (byte) c);
		}
	}

	/**
	 * Draws Latin-1 text with the built-in font ({@code font} 0 = 8×16, 1 = 6×8); {@code bg} &lt; 0 is transparent.
	 * Returns the width drawn in pixels.
	 */
	public synchronized int text(final int x, final int y, final byte[] latin1, final int fg, final int bg, final int font) {
		final boolean big = font != 1;
		final int gw = big ? 8 : 6;
		final int gh = big ? 16 : 8;
		final byte[] f = big ? FONT_8X16 : FONT_6X8;
		int px = x;
		for (final byte ch : latin1) {
			final int cp = ch & 0xFF;
			for (int row = 0; row < gh; row++) {
				final int bits = f[cp * gh + row] & 0xFF;
				final int yy = y + row;
				for (int col = 0; col < gw; col++) {
					final boolean on = (bits & (0x80 >> col)) != 0;
					if (on) {
						put(px + col, yy, fg);
					} else if (bg >= 0) {
						put(px + col, yy, bg);
					}
				}
			}
			px += gw;
		}
		mark(x, y, px - x, gh);
		return px - x;
	}

	public static int textWidth(final int chars, final int font) {
		return chars * (font == 1 ? 6 : 8);
	}

	/** Copies {@code w × h} indexed pixels (row-major, {@code stride} bytes per row) at {@code dx, dy}; {@code key} ≥ 0 is transparent. */
	public synchronized void blit(final int dx, final int dy, final int w, final int h, final byte[] data, final int stride, final int key) {
		if (w <= 0 || h <= 0 || data.length < (long) (h - 1) * stride + w) {
			return;
		}
		for (int row = 0; row < h; row++) {
			final int yy = dy + row;
			if (yy < clipY || yy >= clipY + clipH) {
				continue;
			}
			final int base = row * stride;
			final byte[] px = pixels();
			for (int col = 0; col < w; col++) {
				final int xx = dx + col;
				if (xx < clipX || xx >= clipX + clipW) {
					continue;
				}
				final int v = data[base + col] & 0xFF;
				if (v != key) {
					px[yy * width + xx] = (byte) v;
				}
			}
		}
		mark(dx, dy, w, h);
	}

	/** Move / show / hide the hardware cursor ({@code gfx.cursor}). */
	public synchronized void cursor(final int x, final int y, final boolean visible) {
		if (x != cursorX || y != cursorY || visible != cursorVisible) {
			cursorX = x;
			cursorY = y;
			cursorVisible = visible;
			cursorMoved = true;
		}
	}

	/** The cursor sprite ({@code gfx.cursorshape}): indexed pixels, {@code key} transparent, at most 32×32. */
	public synchronized void cursorShape(final int w, final int h, final int hotX, final int hotY, final byte[] data, final int key) {
		if (w <= 0 || h <= 0 || w > ScreenCursorPayload.MAX_SIZE || h > ScreenCursorPayload.MAX_SIZE || data.length < w * h) {
			return;
		}
		cursorW = w;
		cursorH = h;
		cursorHotX = hotX;
		cursorHotY = hotY;
		cursorShape = Arrays.copyOf(data, w * h);
		cursorKey = key;
		cursorShapeChanged = true;
	}

	/** The cursor sprite as indexed pixels ({@code w × h}, {@link #cursorKey()} transparent) — the emulator draws it itself. */
	public synchronized byte[] cursorSprite() {
		return cursorShape.clone();
	}

	public synchronized int cursorKey() {
		return cursorKey;
	}

	public synchronized int[] cursorHotspot() {
		return new int[] { cursorHotX, cursorHotY };
	}

	/** {@code {x, y, visible ? 1 : 0, w, h}} — for the harness. */
	public synchronized int[] cursorState() {
		return new int[] { cursorX, cursorY, cursorVisible ? 1 : 0, cursorW, cursorH };
	}

	private static byte[] cursorRgba(final byte[] shape, final int n, final int key, final int[] pal) {
		final byte[] out = new byte[n * 4];
		for (int i = 0; i < n; i++) {
			final int v = shape[i] & 0xFF;
			if (v == key) {
				continue; // transparent
			}
			final int c = pal[v];
			out[i * 4] = (byte) (c >> 16);
			out[i * 4 + 1] = (byte) (c >> 8);
			out[i * 4 + 2] = (byte) c;
			out[i * 4 + 3] = (byte) 0xFF;
		}
		return out;
	}

	/** Reads {@code w × h} indexed pixels back (for sprites captured from the screen). */
	public synchronized byte[] read(final int x, final int y, final int w, final int h) {
		final int x0 = Math.clamp(x, 0, width);
		final int y0 = Math.clamp(y, 0, height);
		final int x1 = Math.clamp(x + w, 0, width);
		final int y1 = Math.clamp(y + h, 0, height);
		if (x1 <= x0 || y1 <= y0) {
			return new byte[0];
		}
		final byte[] out = new byte[(x1 - x0) * (y1 - y0)];
		final byte[] px = pixels();
		for (int yy = y0; yy < y1; yy++) {
			System.arraycopy(px, yy * width + x0, out, (yy - y0) * (x1 - x0), x1 - x0);
		}
		return out;
	}

	/** Screen-to-screen copy (scrolling). Overlaps are handled. */
	public synchronized void copy(final int sx, final int sy, final int w, final int h, final int dx, final int dy) {
		if (w <= 0 || h <= 0) {
			return;
		}
		final byte[] tmp = read(sx, sy, w, h);
		final int rw = Math.clamp(sx + w, 0, width) - Math.clamp(sx, 0, width);
		final int rh = tmp.length / Math.max(1, rw);
		if (rw <= 0 || rh <= 0) {
			return;
		}
		blit(dx, dy, rw, rh, tmp, rw, -1);
	}

	public synchronized int palette(final int i) {
		return palette[i & 0xFF];
	}

	public synchronized void setPalette(final int i, final int rgb) {
		final int v = rgb & 0xFFFFFF;
		if ((i & 0xFF) >= colours) {
			return; // above the tier's colour count the palette is the default cube and stays it (caps degrade, never refuse)
		}
		if (palette[i & 0xFF] != v) {
			palette[i & 0xFF] = v;
			paletteDirty = true;
		}
	}

	public synchronized void resetPalette() {
		defaultPalette(palette);
		paletteDirty = true;
	}

	public synchronized int[] paletteCopy() {
		return palette.clone();
	}

	// ---- flush (server thread) ----

	/** A horizontal slice of a rectangle small enough for one packet: indexed bytes at level 0, RGB above. */
	private record Band(int x, int y, int w, int h, byte[] data, boolean indexed) {
	}

	/**
	 * Send what changed to every viewer at its level, exactly like {@code VmInstance.flushFrames}. Returns true if
	 * anything was sent (the machine's "flip" is acknowledged then).
	 */
	public boolean flush(final Collection<ScreenViewers.Viewer> viewers, final dev.virtualminecraft.VirtualMinecraft.LocalBridge bridge) {
		// The server-tick flush and a worker's per-frame flush (U1.2) must not interleave: both mutate the viewers'
		// needFull/sentLod and both take the dirty list. Not synchronized on this: drawing may go on meanwhile.
		synchronized (flushLock) {
			return flushLocked(viewers, bridge);
		}
	}

	private final Object flushLock = new Object();

	private boolean flushLocked(final Collection<ScreenViewers.Viewer> viewers, final dev.virtualminecraft.VirtualMinecraft.LocalBridge bridge) {
		final List<int[]> rects;
		final boolean wasResized;
		final boolean palChanged;
		final int w;
		final int h;
		final int[] pal;
		final int cx;
		final int cy;
		final boolean cvis;
		final int chx;
		final int chy;
		final int cw;
		final int ch;
		final int ckey;
		final byte[] cshape;
		final boolean cmoved;
		final boolean cshapeChanged;
		synchronized (this) {
			w = width;
			h = height;
			if (viewers.isEmpty() || w == 0 || h == 0) {
				dirty.clear();
				resized = false;
				paletteDirty = false;
				cursorMoved = false;
				cursorShapeChanged = false;
				return false;
			}
			rects = coalesce(dirty, w, h);
			dirty.clear();
			wasResized = resized;
			resized = false;
			palChanged = paletteDirty;
			paletteDirty = false;
			pal = palette.clone();
			cx = cursorX;
			cy = cursorY;
			cvis = cursorVisible;
			chx = cursorHotX;
			chy = cursorHotY;
			cw = cursorW;
			ch = cursorH;
			ckey = cursorKey;
			cshape = cursorShape;
			cmoved = cursorMoved;
			cshapeChanged = cursorShapeChanged;
			cursorMoved = false;
			cursorShapeChanged = false;
		}
		byte[] cursorRgba = null;
		if (!rects.isEmpty() || wasResized || palChanged) {
			frames++; // frames that carried pixels: /vmc computer state's fps probe (an empty tick flush is not a frame)
		}
		final List<Band>[] fullBands = new List[ViewerPayload.MAX_LOD + 1];
		final List<Band>[] dirtyBands = new List[ViewerPayload.MAX_LOD + 1];
		final List<ScreenRectPayload>[] fullPayloads = new List[ViewerPayload.MAX_LOD + 1];
		final List<ScreenRectPayload>[] dirtyPayloads = new List[ViewerPayload.MAX_LOD + 1];
		boolean sent = false;
		for (final ScreenViewers.Viewer v : viewers) {
			final int lod = v.lod;
			final boolean local = bridge.isLocalViewer(v.player.getUUID());
			if (wasResized || v.sentLod != lod) {
				v.sentLod = lod;
				v.needFull = true;
				if (local) {
					bridge.screenInfo(id, w, h, true, lod, flags);
				} else {
					ServerPlayNetworking.send(v.player, new ScreenInfoPayload(id, w, h, true, lod, flags));
				}
			}
			// palette: level 0 viewers re-expand on the client; coarser levels were sent RGB and need a fresh frame
			if (v.needFull || palChanged) {
				if (lod == 0) {
					if (local) {
						bridge.screenPalette(id, pal);
					} else {
						ServerPlayNetworking.send(v.player, new ScreenPalettePayload(id, pal));
					}
				} else if (palChanged) {
					v.needFull = true;
				}
			}
			final boolean fresh = v.needFull;
			if (cw > 0 && (fresh || cshapeChanged || cmoved)) {
				final boolean withShape = fresh || cshapeChanged;
				if (withShape && cursorRgba == null) {
					cursorRgba = cursorRgba(cshape, cw * ch, ckey, pal);
				}
				if (local) {
					bridge.screenCursor(id, cx, cy, cvis, chx, chy, cw, ch, withShape ? cursorRgba : NO_SHAPE);
				} else {
					ServerPlayNetworking.send(v.player, new ScreenCursorPayload(id, cx, cy, cvis, chx, chy, cw, ch, withShape ? cursorRgba : NO_SHAPE));
				}
				sent = true;
			}
			if (v.needFull) {
				v.needFull = false;
				if (fullBands[lod] == null) {
					fullBands[lod] = copyBands(0, 0, w, h, lod, pal);
				}
				sent |= send(v, local, bridge, fullBands[lod], fullPayloads, lod);
			} else if (!rects.isEmpty()) {
				if (dirtyBands[lod] == null) {
					dirtyBands[lod] = new ArrayList<>();
					for (final int[] rc : rects) {
						dirtyBands[lod].addAll(copyBands(rc[0], rc[1], rc[2], rc[3], lod, pal));
					}
				}
				sent |= send(v, local, bridge, dirtyBands[lod], dirtyPayloads, lod);
			}
		}
		return sent;
	}

	private boolean send(final ScreenViewers.Viewer v, final boolean local, final VirtualMinecraft.LocalBridge bridge, final List<Band> bands,
		final List<ScreenRectPayload>[] cache, final int lod) {
		if (local) {
			for (final Band b : bands) {
				if (b.indexed) {
					bridge.screenRectIndexed(id, b.x, b.y, b.w, b.h, b.data);
				} else {
					bridge.screenRect(id, b.x, b.y, b.w, b.h, b.data);
				}
			}
		} else {
			if (cache[lod] == null) {
				cache[lod] = deflate(bands);
			}
			for (final ScreenRectPayload p : cache[lod]) {
				ServerPlayNetworking.send(v.player, p);
			}
		}
		return !bands.isEmpty();
	}

	private static List<int[]> coalesce(final List<int[]> rects, final int width, final int height) {
		final List<int[]> out = new ArrayList<>();
		if (rects.isEmpty()) {
			return out;
		}
		if (rects.size() <= MAX_DIRTY_RECTS) {
			for (final int[] rc : rects) {
				final int x0 = Math.clamp(rc[0], 0, width);
				final int y0 = Math.clamp(rc[1], 0, height);
				final int x1 = Math.clamp(rc[0] + rc[2], 0, width);
				final int y1 = Math.clamp(rc[1] + rc[3], 0, height);
				if (x1 > x0 && y1 > y0) {
					out.add(new int[] { x0, y0, x1 - x0, y1 - y0 });
				}
			}
			return out;
		}
		int minX = width;
		int minY = height;
		int maxX = 0;
		int maxY = 0;
		for (final int[] rc : rects) {
			minX = Math.min(minX, rc[0]);
			minY = Math.min(minY, rc[1]);
			maxX = Math.max(maxX, rc[0] + rc[2]);
			maxY = Math.max(maxY, rc[1] + rc[3]);
		}
		minX = Math.clamp(minX, 0, width);
		minY = Math.clamp(minY, 0, height);
		maxX = Math.clamp(maxX, 0, width);
		maxY = Math.clamp(maxY, 0, height);
		if (maxX > minX && maxY > minY) {
			out.add(new int[] { minX, minY, maxX - minX, maxY - minY });
		}
		return out;
	}

	/** Level 0: indexed bands straight from the framebuffer. Level L: palette applied, box-filtered {@code 2^L}, RGB. */
	private synchronized List<Band> copyBands(final int x, final int y, final int w, final int h, final int lod, final int[] pal) {
		final List<Band> out = new ArrayList<>();
		if (width == 0) {
			return out;
		}
		final byte[] fbp = pixels(); // a viewer is watching again: the picture comes back if it was parked
		if (lod <= 0) {
			final int rowsPerBand = Math.max(1, MAX_BAND_BYTES / Math.max(1, w));
			for (int y0 = y; y0 < y + h; y0 += rowsPerBand) {
				final int bh = Math.min(rowsPerBand, y + h - y0);
				final byte[] data = new byte[w * bh];
				for (int row = 0; row < bh; row++) {
					System.arraycopy(fbp, (y0 + row) * width + x, data, row * w, w);
				}
				out.add(new Band(x, y0, w, bh, data, true));
			}
			return out;
		}
		final int s = 1 << lod;
		final int sx0 = x & ~(s - 1);
		final int sy0 = y & ~(s - 1);
		final int sx1 = ((x + w + s - 1) / s) * s;
		final int sy1 = ((y + h + s - 1) / s) * s;
		final int ox = sx0 >> lod;
		final int oy = sy0 >> lod;
		final int ow = (sx1 - sx0) >> lod;
		final int oh = (sy1 - sy0) >> lod;
		if (ow <= 0 || oh <= 0) {
			return out;
		}
		final int rowsPerBand = Math.max(1, MAX_BAND_BYTES / (ow * 3));
		for (int oy0 = oy; oy0 < oy + oh; oy0 += rowsPerBand) {
			final int bh = Math.min(rowsPerBand, oy + oh - oy0);
			final byte[] rgb = new byte[ow * bh * 3];
			int o = 0;
			for (int row = 0; row < bh; row++) {
				final int syStart = (oy0 + row) << lod;
				final int syEnd = Math.min(syStart + s, height);
				for (int col = 0; col < ow; col++) {
					final int sxStart = (ox + col) << lod;
					final int sxEnd = Math.min(sxStart + s, width);
					int rSum = 0;
					int gSum = 0;
					int bSum = 0;
					int n = 0;
					for (int sy = syStart; sy < syEnd; sy++) {
						int i = sy * width + sxStart;
						for (int sx = sxStart; sx < sxEnd; sx++, i++) {
							final int c = pal[fbp[i] & 0xFF];
							rSum += (c >> 16) & 0xFF;
							gSum += (c >> 8) & 0xFF;
							bSum += c & 0xFF;
							n++;
						}
					}
					if (n == 0) {
						o += 3;
						continue;
					}
					rgb[o++] = (byte) (rSum / n);
					rgb[o++] = (byte) (gSum / n);
					rgb[o++] = (byte) (bSum / n);
				}
			}
			out.add(new Band(ox, oy0, ow, bh, rgb, false));
		}
		return out;
	}

	private List<ScreenRectPayload> deflate(final List<Band> bands) {
		final List<ScreenRectPayload> out = new ArrayList<>(bands.size());
		for (final Band b : bands) {
			deflater.reset();
			deflater.setInput(b.data);
			deflater.finish();
			int len = 0;
			while (!deflater.finished()) {
				if (len == deflateBuf.length) {
					deflateBuf = Arrays.copyOf(deflateBuf, deflateBuf.length * 2);
				}
				len += deflater.deflate(deflateBuf, len, deflateBuf.length - len);
			}
			out.add(new ScreenRectPayload(id, b.x, b.y, b.w, b.h, b.indexed ? ScreenRectPayload.FORMAT_ZLIB_INDEXED : ScreenRectPayload.FORMAT_ZLIB_RGB, Arrays.copyOf(deflateBuf, len)));
		}
		return out;
	}

	// ---- persistence (a freeze keeps the picture; §2) ----

	/** {@code w, h, palette[256], deflated pixels} — a flat desktop deflates to a few KB. */
	public synchronized byte[] snapshot() {
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final java.io.DataOutputStream d = new java.io.DataOutputStream(out);
		try {
			d.writeInt(width);
			d.writeInt(height);
			d.writeBoolean(drawn);
			for (final int c : palette) {
				d.writeInt(c);
			}
			// already parked? those are exactly the bytes the snapshot wants
			final byte[] px = parked != null ? parked : deflatePixels(fb);
			d.writeInt(px.length);
			d.write(px);
		} catch (final IOException e) {
			return new byte[0];
		}
		return out.toByteArray();
	}

	public synchronized boolean restore(final byte[] data) {
		try {
			final java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(data));
			final int w = d.readInt();
			final int h = d.readInt();
			final boolean wasDrawn = d.readBoolean();
			if (w < 0 || h < 0 || w > MAX_WIDTH || h > MAX_HEIGHT) {
				return false;
			}
			for (int i = 0; i < 256; i++) {
				palette[i] = d.readInt();
			}
			final byte[] z = new byte[d.readInt()];
			d.readFully(z);
			final Inflater inf = new Inflater();
			inf.setInput(z);
			final byte[] px = new byte[w * h];
			int got = 0;
			while (got < px.length && !inf.finished()) {
				final int n = inf.inflate(px, got, px.length - got);
				if (n == 0 && (inf.needsInput() || inf.needsDictionary())) {
					break;
				}
				got += n;
			}
			inf.end();
			width = w;
			height = h;
			fb = px;
			parked = null;
			clip(0, 0, 0, 0);
			drawn = wasDrawn;
			resized = true;
			paletteDirty = true;
			dirty.clear();
			return true;
		} catch (final IOException | DataFormatException | RuntimeException e) {
			VirtualMinecraft.LOGGER.warn("Screen snapshot unreadable: {}", e.toString());
			return false;
		}
	}
}
