package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jspecify.annotations.Nullable;

/**
 * Server -> client: a chunk of a machine's audio output, μ-law, mono, {@link #SAMPLE_RATE} Hz. {@code pos} is
 * where the sound comes from when the client may not know (a Computer heard from 32 blocks away, ROADMAP §7h §5);
 * null keeps the position the client already has (the VM tier: a viewer's monitor set it).
 */
public record AudioPayload(UUID vm, byte[] ulaw, @Nullable BlockPos pos) implements Payload {
	public static final ResourceLocation ID = VirtualMinecraft.id("audio");
	public static final int SAMPLE_RATE = 22050;

	public AudioPayload(final UUID vm, final byte[] ulaw) {
		this(vm, ulaw, null);
	}

	public AudioPayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), buf.readByteArray(1 << 16), buf.readBoolean() ? buf.readBlockPos() : null);
	}

	@Override
	public void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		buf.writeByteArray(ulaw);
		buf.writeBoolean(pos != null);
		if (pos != null) {
			buf.writeBlockPos(pos);
		}
	}

	@Override
	public ResourceLocation id() {
		return ID;
	}
}
