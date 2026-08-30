package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.vm.VmConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: apply configuration and/or perform a power action on a computer block. */
public record VmControlPayload(BlockPos pos, Action action, VmConfig config) implements Payload {
	public static final ResourceLocation ID = VirtualMinecraft.id("vm_control");

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

	public VmControlPayload(final FriendlyByteBuf buf) {
		this(buf.readBlockPos(), Action.byOrdinal(buf.readByte()), VmConfig.read(buf));
	}

	@Override
	public void write(final FriendlyByteBuf buf) {
		buf.writeBlockPos(pos);
		buf.writeByte(action.ordinal());
		config.write(buf);
	}

	@Override
	public ResourceLocation id() {
		return ID;
	}
}
