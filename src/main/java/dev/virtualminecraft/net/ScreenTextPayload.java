package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.bus.TextGrid;
import java.util.BitSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server -> client: a monitor's text-mode state — size, whether text mode is on, and a set of rows. The
 * rows are encoded straight from the server grid into the packet buffer, and decoded straight into the
 * client's grid, so the payload carries the raw bytes in between.
 */
public record ScreenTextPayload(BlockPos pos, boolean textMode, int cols, int rows, byte[] rowData, int rowCount) implements CustomPacketPayload {
	public static final Type<ScreenTextPayload> TYPE = new Type<>(VirtualMinecraft.id("screen_text"));
	public static final StreamCodec<FriendlyByteBuf, ScreenTextPayload> CODEC = CustomPacketPayload.codec(ScreenTextPayload::write, ScreenTextPayload::new);

	public static ScreenTextPayload of(final BlockPos pos, final boolean textMode, final TextGrid grid, final BitSet dirtyRows) {
		final FriendlyByteBuf tmp = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		int n = 0;
		for (int y = dirtyRows.nextSetBit(0); y >= 0 && y < grid.rows; y = dirtyRows.nextSetBit(y + 1)) {
			grid.writeRow(tmp, y);
			n++;
		}
		final byte[] data = new byte[tmp.readableBytes()];
		tmp.readBytes(data);
		tmp.release();
		return new ScreenTextPayload(pos, textMode, grid.cols, grid.rows, data, n);
	}

	private ScreenTextPayload(final FriendlyByteBuf buf) {
		this(buf.readBlockPos(), buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readByteArray(), buf.readVarInt());
	}

	private void write(final FriendlyByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeBoolean(textMode);
		buf.writeVarInt(cols);
		buf.writeVarInt(rows);
		buf.writeByteArray(rowData);
		buf.writeVarInt(rowCount);
	}

	/** Applies the rows to a client grid of matching size. */
	public void applyTo(final TextGrid grid) {
		final FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(rowData));
		for (int i = 0; i < rowCount; i++) {
			grid.readRow(buf);
		}
	}

	@Override
	public Type<ScreenTextPayload> type() {
		return TYPE;
	}
}
