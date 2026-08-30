package dev.virtualminecraft.bus;

import dev.virtualminecraft.util.Nums;
import java.util.Arrays;
import java.util.BitSet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A character grid with per-cell foreground/background colours: what a monitor shows in text mode. Lives in
 * {@code MonitorBlockEntity} on both sides. Coordinates are 0-based here; the bus API adds 1. Cells are
 * {@link #CELL_W}×{@link #CELL_H} pixels of the Minecraft font, which both the renderer and the touch-to-cell
 * math rely on. Server side tracks dirty rows so only changed rows go over the network.
 */
public final class TextGrid {
	public static final int CELL_W = 6;
	public static final int CELL_H = 9;
	public static final int MAX_COLS = 200;
	public static final int MAX_ROWS = 100;
	public static final int DEFAULT_COLS = 40;
	public static final int DEFAULT_ROWS = 24;
	public static final int DEFAULT_FG = 0xFFFFFF;
	public static final int DEFAULT_BG = 0x000000;

	public int cols;
	public int rows;
	public int[] chars;
	public int[] fg;
	public int[] bg;
	public int cursorX;
	public int cursorY;
	public int curFg = DEFAULT_FG;
	public int curBg = DEFAULT_BG;
	/** Bumped on every change (client side: tells the renderer to rebuild its cache). */
	public int version;
	private final BitSet dirtyRows = new BitSet();
	private boolean dirtySize;

	public TextGrid(final int cols, final int rows) {
		resize(cols, rows);
	}

	public void resize(final int newCols, final int newRows) {
		final int c = Nums.clamp(newCols, 1, MAX_COLS);
		final int r = Nums.clamp(newRows, 1, MAX_ROWS);
		final int[] nc = new int[c * r];
		final int[] nf = new int[c * r];
		final int[] nb = new int[c * r];
		Arrays.fill(nc, ' ');
		Arrays.fill(nf, curFg);
		Arrays.fill(nb, curBg);
		if (chars != null) {
			for (int y = 0; y < Math.min(rows, r); y++) {
				for (int x = 0; x < Math.min(cols, c); x++) {
					nc[y * c + x] = chars[y * cols + x];
					nf[y * c + x] = fg[y * cols + x];
					nb[y * c + x] = bg[y * cols + x];
				}
			}
		}
		cols = c;
		rows = r;
		chars = nc;
		fg = nf;
		bg = nb;
		cursorX = Math.min(cursorX, cols - 1);
		cursorY = Math.min(cursorY, rows - 1);
		dirtySize = true;
		dirtyRows.set(0, rows);
		version++;
	}

	public void clear() {
		Arrays.fill(chars, ' ');
		Arrays.fill(fg, curFg);
		Arrays.fill(bg, curBg);
		cursorX = 0;
		cursorY = 0;
		dirtyRows.set(0, rows);
		version++;
	}

	public void clearLine(final int y) {
		if (y < 0 || y >= rows) {
			return;
		}
		fill(0, y, cols, 1, ' ');
	}

	/** Writes {@code text} at (x, y) with the current colours, clipped to the row. Returns the x after the text. */
	public int set(final int x, final int y, final String text) {
		if (y < 0 || y >= rows) {
			return x;
		}
		int cx = x;
		final int[] cps = text.codePoints().toArray();
		for (final int cp : cps) {
			if (cx >= 0 && cx < cols) {
				final int i = y * cols + cx;
				chars[i] = cp < 32 ? ' ' : cp;
				fg[i] = curFg;
				bg[i] = curBg;
			}
			cx++;
		}
		dirtyRows.set(y);
		version++;
		return cx;
	}

	public void fill(final int x, final int y, final int w, final int h, final int cp) {
		final int x0 = Math.max(0, x);
		final int y0 = Math.max(0, y);
		final int x1 = Math.min(cols, x + w);
		final int y1 = Math.min(rows, y + h);
		for (int yy = y0; yy < y1; yy++) {
			for (int xx = x0; xx < x1; xx++) {
				final int i = yy * cols + xx;
				chars[i] = cp < 32 ? ' ' : cp;
				fg[i] = curFg;
				bg[i] = curBg;
			}
			dirtyRows.set(yy);
		}
		version++;
	}

	/** Scrolls the content up by {@code n} rows (down if negative); vacated rows are cleared with the current colours. */
	public void scroll(final int n) {
		if (n == 0) {
			return;
		}
		final int[] nc = new int[cols * rows];
		final int[] nf = new int[cols * rows];
		final int[] nb = new int[cols * rows];
		Arrays.fill(nc, ' ');
		Arrays.fill(nf, curFg);
		Arrays.fill(nb, curBg);
		for (int y = 0; y < rows; y++) {
			final int src = y + n;
			if (src >= 0 && src < rows) {
				System.arraycopy(chars, src * cols, nc, y * cols, cols);
				System.arraycopy(fg, src * cols, nf, y * cols, cols);
				System.arraycopy(bg, src * cols, nb, y * cols, cols);
			}
		}
		chars = nc;
		fg = nf;
		bg = nb;
		dirtyRows.set(0, rows);
		version++;
	}

	/** Terminal-style write at the cursor: wraps at the right edge, honours \n, scrolls at the bottom. */
	public void write(final String text) {
		final int[] cps = text.codePoints().toArray();
		for (final int cp : cps) {
			if (cp == '\n') {
				cursorX = 0;
				newline();
				continue;
			}
			if (cp == '\r') {
				cursorX = 0;
				continue;
			}
			if (cursorX >= cols) {
				cursorX = 0;
				newline();
			}
			final int i = cursorY * cols + cursorX;
			chars[i] = cp < 32 ? ' ' : cp;
			fg[i] = curFg;
			bg[i] = curBg;
			dirtyRows.set(cursorY);
			cursorX++;
		}
		version++;
	}

	private void newline() {
		if (cursorY + 1 >= rows) {
			scroll(1);
		} else {
			cursorY++;
		}
	}

	public int get(final int x, final int y) {
		return x < 0 || y < 0 || x >= cols || y >= rows ? ' ' : chars[y * cols + x];
	}

	// ---- dirty tracking (server) ----

	public boolean hasDirty() {
		return dirtySize || !dirtyRows.isEmpty();
	}

	public boolean takeDirtySize() {
		final boolean d = dirtySize;
		dirtySize = false;
		return d;
	}

	public BitSet takeDirtyRows() {
		final BitSet out = (BitSet) dirtyRows.clone();
		dirtyRows.clear();
		return out;
	}

	// ---- persistence ----

	public void save(final ValueOutput out) {
		out.putInt("cols", cols);
		out.putInt("rows", rows);
		out.putIntArray("chars", chars);
		out.putIntArray("fg", fg);
		out.putIntArray("bg", bg);
		out.putInt("cursorX", cursorX);
		out.putInt("cursorY", cursorY);
		out.putInt("curFg", curFg);
		out.putInt("curBg", curBg);
	}

	public static TextGrid load(final ValueInput in) {
		final TextGrid g = new TextGrid(in.getIntOr("cols", DEFAULT_COLS), in.getIntOr("rows", DEFAULT_ROWS));
		final int n = g.cols * g.rows;
		in.getIntArray("chars").ifPresent(a -> System.arraycopy(a, 0, g.chars, 0, Math.min(n, a.length)));
		in.getIntArray("fg").ifPresent(a -> System.arraycopy(a, 0, g.fg, 0, Math.min(n, a.length)));
		in.getIntArray("bg").ifPresent(a -> System.arraycopy(a, 0, g.bg, 0, Math.min(n, a.length)));
		g.cursorX = Nums.clamp(in.getIntOr("cursorX", 0), 0, g.cols - 1);
		g.cursorY = Nums.clamp(in.getIntOr("cursorY", 0), 0, g.rows - 1);
		g.curFg = in.getIntOr("curFg", DEFAULT_FG);
		g.curBg = in.getIntOr("curBg", DEFAULT_BG);
		return g;
	}

	// ---- network rows ----

	public void writeRow(final FriendlyByteBuf buf, final int y) {
		buf.writeVarInt(y);
		for (int x = 0; x < cols; x++) {
			final int i = y * cols + x;
			buf.writeVarInt(chars[i]);
			buf.writeInt(fg[i]);
			buf.writeInt(bg[i]);
		}
	}

	/** Reads one row written by {@link #writeRow} into this grid (client). */
	public void readRow(final FriendlyByteBuf buf) {
		final int y = buf.readVarInt();
		for (int x = 0; x < cols; x++) {
			final int cp = buf.readVarInt();
			final int f = buf.readInt();
			final int b = buf.readInt();
			if (y >= 0 && y < rows) {
				final int i = y * cols + x;
				chars[i] = cp;
				fg[i] = f;
				bg[i] = b;
			}
		}
		version++;
	}
}
