package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.vm.VmConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client -> server: apply configuration and/or perform a power action on a computer block. */
public record VmControlPayload(BlockPos pos, Action action, VmConfig config) implements CustomPacketPayload {
	public static final Type<VmControlPayload> TYPE = new Type<>(VirtualMinecraft.id("vm_control"));
	public static final StreamCodec<FriendlyByteBuf, VmControlPayload> CODEC = CustomPacketPayload.codec(VmControlPayload::write, VmControlPayload::new);

	public enum Action {
		SAVE,
		START,
		SHUTDOWN,
		FORCE_STOP,
		RESET;

		static Action byOrdinal(final int i) {
			final Action[] v = values();
			return i >= 0 && i < v.length ? v[i] : SAVE;
		}
	}

	private VmControlPayload(final FriendlyByteBuf buf) {
		this(buf.readBlockPos(), Action.byOrdinal(buf.readByte()), VmConfig.read(buf));
	}

	private void write(final FriendlyByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeByte(action.ordinal());
		config.write(buf);
	}

	@Override
	public Type<VmControlPayload> type() {
		return TYPE;
	}
}
