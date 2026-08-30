package dev.virtualminecraft.client.render;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.net.ScreenInfoPayload;
import dev.virtualminecraft.net.ScreenPalettePayload;
import dev.virtualminecraft.net.ScreenRectPayload;
import dev.virtualminecraft.net.ViewerPayload;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

/**
 * Client-side registry of screen textures plus the "I'm watching this" heartbeat to the server, which also carries
 * the level of detail this client wants for each screen.
 * <p>
 * <b>Level of detail</b> (milestone 5 A3): every render of a monitor reports how far the camera is per block of
 * monitor width, the closest observation since the last heartbeat picks the level, with hysteresis so a player
 * hovering at a boundary does not make the server re-send full frames. [name]'s calibration (ROADMAP §7l): full
 * resolution within ~5–7 blocks of a one-block monitor, then halving; the far end is the server's
 * {@code viewDistance}. The full-screen view reports distance 0, so it is always level 0.
 */
public final class ScreenTextures {
	private static final int HEARTBEAT_INTERVAL = 10;
	private static final int WATCH_WINDOW = 40;
	private static final int EXPIRE_TICKS = 20 * 60;
	private static final int MAX_DIMENSION = 8192;
	/** Distance per block of monitor beyond which the next coarser level is requested... */
	private static final double[] LOD_UP = { 8, 16, 32 };
	/** ...and below which the next finer one is; the gap between the two is the hysteresis. */
	private static final double[] LOD_DOWN = { 6, 12, 24 };

	private static final class Entry {
		@Nullable ScreenTexture texture;
		long lastTouched;
		boolean running;
		/** Full screen size and the level the server is sending at, from the last {@code ScreenInfo}. */
		int fullWidth;
		int fullHeight;
		int serverLod;
		int flags;
		/** The level this client is asking for, and the closest observation (distance / monitor blocks) since the last heartbeat. */
		int wantLod;
		double nearest = Double.MAX_VALUE;
		/** Indexed-colour screens (the Computer): the retained index buffer at the texture's size, and the palette. */
		byte @Nullable [] indices;
		int @Nullable [] palette;
		/** The hardware cursor (U1.3): position in full-resolution pixels, hot spot, and the sprite texture. */
		int cursorX;
		int cursorY;
		boolean cursorVisible;
		int cursorHotX;
		int cursorHotY;
		@Nullable ScreenTexture cursorTexture;
	}

	/** What the renderers draw over the picture; null while hidden or before the sprite arrived. */
	public record Cursor(int x, int y, int hotX, int hotY, int w, int h, ScreenTexture tex) {
	}

	public static @Nullable Cursor cursor(final UUID vm) {
		final Entry e = ENTRIES.get(vm);
		if (e == null || !e.cursorVisible || e.cursorTexture == null) {
			return null;
		}
		return new Cursor(e.cursorX, e.cursorY, e.cursorHotX, e.cursorHotY, e.cursorTexture.width, e.cursorTexture.height, e.cursorTexture);
	}

	public static void onCursor(final dev.virtualminecraft.net.ScreenCursorPayload p) {
		onCursor(p.vm(), p.x(), p.y(), p.visible(), p.hotX(), p.hotY(), p.w(), p.h(), p.rgba());
	}

	/** {@code rgba} empty = the shape did not change; otherwise a tightly packed {@code w × h} RGBA8 sprite. */
	public static void onCursor(final UUID vm, final int x, final int y, final boolean visible, final int hotX, final int hotY, final int w, final int h, final byte[] rgba) {
		final Entry e = ENTRIES.computeIfAbsent(vm, u -> new Entry());
		e.cursorX = x;
		e.cursorY = y;
		e.cursorVisible = visible;
		e.cursorHotX = hotX;
		e.cursorHotY = hotY;
		if (rgba.length >= w * h * 4 && w > 0 && h > 0 && w <= 64 && h <= 64) {
			if (e.cursorTexture != null && (e.cursorTexture.width != w || e.cursorTexture.height != h)) {
				Minecraft.getInstance().getTextureManager().release(e.cursorTexture.id);
				e.cursorTexture = null;
			}
			if (e.cursorTexture == null) {
				final ScreenTexture tex = new ScreenTexture(VirtualMinecraft.id("vm/" + vm + "/cursor"), w, h, true);
				Minecraft.getInstance().getTextureManager().register(tex.id, tex);
				e.cursorTexture = tex;
			}
			final ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
			try {
				buf.put(rgba, 0, w * h * 4).flip();
				e.cursorTexture.upload(0, 0, w, h, buf);
			} finally {
				MemoryUtil.memFree(buf);
			}
		}
	}

	private static final Map<UUID, Entry> ENTRIES = new HashMap<>();
	private static final Inflater INFLATER = new Inflater();
	private static long tick;
	private static byte[] rgbBuf = new byte[1 << 16];

	private ScreenTextures() {
	}

	/** Mark a screen as being looked at this frame from point blank (the full-screen view): always full resolution. */
	public static void touch(final UUID vm) {
		touch(vm, 0.0, 1.0);
	}

	/**
	 * Mark a screen as rendered this frame at {@code distance} blocks on a monitor {@code monitorBlocks} wide — the
	 * level of detail is chosen from the ratio, so a big wall screen stays sharp from further away.
	 */
	public static void touch(final UUID vm, final double distance, final double monitorBlocks) {
		final Entry e = ENTRIES.computeIfAbsent(vm, u -> new Entry());
		e.lastTouched = tick;
		e.nearest = Math.min(e.nearest, distance / Math.max(1.0, monitorBlocks));
	}

	/** The full-resolution screen size behind a texture (the texture itself may be smaller at a coarser level). */
	public static int[] fullSize(final UUID vm) {
		final Entry e = ENTRIES.get(vm);
		return e == null || e.texture == null ? new int[] { 0, 0 } : new int[] { e.fullWidth, e.fullHeight };
	}

	/** Whether the source wants QEMU scancodes rather than keysyms (a D-Bus display is attached). */
	public static boolean scancodes(final UUID vm) {
		final Entry e = ENTRIES.get(vm);
		return e != null && (e.flags & ScreenInfoPayload.FLAG_SCANCODES) != 0;
	}

	/** Whether the source also wants typed characters as {@code CHAR} events (the Computer). */
	public static boolean chars(final UUID vm) {
		final Entry e = ENTRIES.get(vm);
		return e != null && (e.flags & ScreenInfoPayload.FLAG_CHARS) != 0;
	}

	/** The level the server is currently sending this screen at, for diagnostics. */
	public static int lod(final UUID vm) {
		final Entry e = ENTRIES.get(vm);
		return e == null ? 0 : e.serverLod;
	}

	public static @Nullable ScreenTexture get(final UUID vm) {
		final Entry e = ENTRIES.get(vm);
		return e == null ? null : e.texture;
	}

	public static boolean isRunning(final UUID vm) {
		final Entry e = ENTRIES.get(vm);
		return e != null && e.running;
	}

	private static Identifier textureId(final UUID vm) {
		return VirtualMinecraft.id("vm/" + vm);
	}

	public static void onInfo(final ScreenInfoPayload p) {
		final Entry e = ENTRIES.computeIfAbsent(p.vm(), u -> new Entry());
		e.running = p.running();
		if (p.width() <= 0 || p.height() <= 0 || p.width() > MAX_DIMENSION || p.height() > MAX_DIMENSION) {
			release(p.vm(), e);
			return;
		}
		e.fullWidth = p.width();
		e.fullHeight = p.height();
		e.serverLod = p.lod();
		e.flags = p.flags();
		final int w = p.scaledWidth();
		final int h = p.scaledHeight();
		if (e.texture != null && (e.texture.width != w || e.texture.height != h)) {
			release(p.vm(), e);
			e.indices = null;
		}
		if (e.texture == null) {
			final ScreenTexture tex = new ScreenTexture(textureId(p.vm()), w, h);
			Minecraft.getInstance().getTextureManager().register(tex.id, tex);
			e.texture = tex;
		}
	}

	/** Same as {@link #onRect} but for an uncompressed RGB rectangle handed over in-process (singleplayer fast path). */
	/** Dev only: how many rectangles have arrived for a screen, and a hook the puppet uses to time the next one. */
	private static final java.util.Map<UUID, java.util.concurrent.atomic.AtomicLong> UPDATES = new java.util.concurrent.ConcurrentHashMap<>();
	public static volatile java.util.function.Consumer<UUID> updateListener;

	public static long updates(final UUID vm) {
		final java.util.concurrent.atomic.AtomicLong c = UPDATES.get(vm);
		return c == null ? 0 : c.get();
	}

	private static void noteUpdate(final UUID vm) {
		UPDATES.computeIfAbsent(vm, u -> new java.util.concurrent.atomic.AtomicLong()).incrementAndGet();
		final java.util.function.Consumer<UUID> l = updateListener;
		if (l != null) {
			l.accept(vm);
		}
	}

	public static void onRawRect(final UUID vm, final int x, final int y, final int w, final int h, final byte[] rgb) {
		noteUpdate(vm);
		final Entry e = ENTRIES.get(vm);
		if (e == null || e.texture == null || w <= 0 || h <= 0 || rgb.length < w * h * 3) {
			return;
		}
		uploadRgb(e.texture, x, y, w, h, rgb);
	}

	private static void uploadRgb(final ScreenTexture tex, final int x, final int y, final int w, final int h, final byte[] rgb) {
		final ByteBuffer rgba = MemoryUtil.memAlloc(w * h * 4);
		try {
			int i = 0;
			for (int px = 0; px < w * h; px++) {
				rgba.put(rgb[i++]);
				rgba.put(rgb[i++]);
				rgba.put(rgb[i++]);
				rgba.put((byte) 0xFF);
			}
			rgba.flip();
			tex.upload(x, y, w, h, rgba);
		} finally {
			MemoryUtil.memFree(rgba);
		}
	}

	/** The palette of an indexed screen; the retained index buffer is re-expanded so a change costs no frame. */
	public static void onPalette(final UUID vm, final int[] rgb) {
		final Entry e = ENTRIES.computeIfAbsent(vm, u -> new Entry());
		e.palette = rgb.length == 256 ? rgb.clone() : Arrays.copyOf(rgb, 256);
		if (e.texture != null && e.indices != null) {
			uploadIndexed(e, 0, 0, e.texture.width, e.texture.height, e.indices, e.texture.width, 0);
		}
	}

	public static void onPalette(final ScreenPalettePayload p) {
		onPalette(p.vm(), p.rgb());
	}

	/** An uncompressed indexed rectangle handed over in-process (singleplayer fast path). */
	public static void onRawIndexed(final UUID vm, final int x, final int y, final int w, final int h, final byte[] idx) {
		noteUpdate(vm);
		final Entry e = ENTRIES.get(vm);
		if (e == null || e.texture == null || w <= 0 || h <= 0 || idx.length < w * h) {
			return;
		}
		storeIndexed(e, x, y, w, h, idx);
	}

	/** Keep the indices (so a palette change can re-expand) and upload the expanded rectangle. */
	private static void storeIndexed(final Entry e, final int x, final int y, final int w, final int h, final byte[] idx) {
		final ScreenTexture tex = e.texture;
		if (x < 0 || y < 0 || x + w > tex.width || y + h > tex.height) {
			return;
		}
		if (e.indices == null || e.indices.length != tex.width * tex.height) {
			e.indices = new byte[tex.width * tex.height];
		}
		for (int row = 0; row < h; row++) {
			System.arraycopy(idx, row * w, e.indices, (y + row) * tex.width + x, w);
		}
		uploadIndexed(e, x, y, w, h, idx, w, 0);
	}

	private static void uploadIndexed(final Entry e, final int x, final int y, final int w, final int h, final byte[] idx, final int stride, final int offset) {
		final int[] pal = e.palette;
		final ByteBuffer rgba = MemoryUtil.memAlloc(w * h * 4);
		try {
			for (int row = 0; row < h; row++) {
				int i = offset + row * stride;
				for (int col = 0; col < w; col++, i++) {
					final int c = pal == null ? 0 : pal[idx[i] & 0xFF];
					rgba.put((byte) (c >> 16));
					rgba.put((byte) (c >> 8));
					rgba.put((byte) c);
					rgba.put((byte) 0xFF);
				}
			}
			rgba.flip();
			e.texture.upload(x, y, w, h, rgba);
		} finally {
			MemoryUtil.memFree(rgba);
		}
	}

	public static void onRect(final ScreenRectPayload p) {
		noteUpdate(p.vm());
		final Entry e = ENTRIES.get(p.vm());
		if (e == null || e.texture == null) {
			return;
		}
		final int w = p.width();
		final int h = p.height();
		if (w <= 0 || h <= 0 || (long) w * h > (long) MAX_DIMENSION * MAX_DIMENSION) {
			return;
		}
		final boolean indexed = p.format() == ScreenRectPayload.FORMAT_ZLIB_INDEXED;
		if (!indexed && p.format() != ScreenRectPayload.FORMAT_ZLIB_RGB) {
			return;
		}
		final int rgbLen = w * h * (indexed ? 1 : 3);
		if (rgbBuf.length < rgbLen) {
			rgbBuf = new byte[Math.max(rgbLen, rgbBuf.length * 2)];
		}
		INFLATER.reset();
		INFLATER.setInput(p.data());
		int got = 0;
		try {
			while (got < rgbLen && !INFLATER.finished()) {
				final int n = INFLATER.inflate(rgbBuf, got, rgbLen - got);
				if (n == 0 && (INFLATER.needsInput() || INFLATER.needsDictionary())) {
					break;
				}
				got += n;
			}
		} catch (final DataFormatException ex) {
			VirtualMinecraft.LOGGER.debug("Bad screen rect: {}", ex.toString());
			return;
		}
		if (got < rgbLen) {
			return;
		}
		if (indexed) {
			storeIndexed(e, p.x(), p.y(), w, h, rgbBuf);
		} else {
			uploadRgb(e.texture, p.x(), p.y(), w, h, rgbBuf);
		}
	}

	public static void clientTick() {
		tick++;
		final boolean heartbeat = tick % HEARTBEAT_INTERVAL == 0;
		final Iterator<Map.Entry<UUID, Entry>> it = ENTRIES.entrySet().iterator();
		while (it.hasNext()) {
			final Map.Entry<UUID, Entry> me = it.next();
			final Entry e = me.getValue();
			if (tick - e.lastTouched > EXPIRE_TICKS) {
				release(me.getKey(), e);
				it.remove();
				continue;
			}
			if (heartbeat && tick - e.lastTouched <= WATCH_WINDOW && ClientPlayNetworking.canSend(ViewerPayload.TYPE)) {
				e.wantLod = chooseLod(e.wantLod, e.nearest);
				e.nearest = Double.MAX_VALUE;
				ClientPlayNetworking.send(new ViewerPayload(me.getKey(), e.texture == null, e.wantLod));
			}
		}
	}

	/** Steps the level at most one notch per heartbeat, coarser past {@link #LOD_UP}, finer below {@link #LOD_DOWN}. */
	private static int chooseLod(final int current, final double nearest) {
		if (current < ViewerPayload.MAX_LOD && nearest > LOD_UP[current]) {
			return current + 1;
		}
		if (current > 0 && nearest < LOD_DOWN[current - 1]) {
			return current - 1;
		}
		return current;
	}

	private static void release(final UUID vm, final Entry e) {
		if (e.texture != null) {
			Minecraft.getInstance().getTextureManager().release(e.texture.id);
			e.texture = null;
		}
		if (e.cursorTexture != null) {
			Minecraft.getInstance().getTextureManager().release(e.cursorTexture.id);
			e.cursorTexture = null;
		}
	}

	public static void clear() {
		for (final Map.Entry<UUID, Entry> me : ENTRIES.entrySet()) {
			release(me.getKey(), me.getValue());
		}
		ENTRIES.clear();
	}
}
