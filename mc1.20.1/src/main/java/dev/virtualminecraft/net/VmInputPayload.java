package dev.virtualminecraft.net;

import dev.virtualminecraft.VirtualMinecraft;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: batched keyboard / pointer events destined for a VM. */
public record VmInputPayload(UUID vm, List<Event> events) implements Payload {
	public static final ResourceLocation ID = VirtualMinecraft.id("vm_input");
	public static final int MAX_EVENTS = 256;

	public static final byte KEY = 0;
	public static final byte POINTER = 1;
	public static final byte SCANCODE = 2;
	/** a = Unicode codepoint typed (sent when {@code ScreenInfo} has {@code FLAG_CHARS}); the Computer's kernel gets it as a {@code char} event. */
	public static final byte CHAR = 3;

	/**
	 * {@code KEY}: a = X11 keysym, b = 1 down / 0 up (the VNC path).
	 * {@code SCANCODE}: a = QEMU key number (XT set-1 scancode, extended keys in bit 7), b = 1 down / 0 up (the D-Bus path; sent only when {@code ScreenInfo} says so).
	 * {@code POINTER}: a = button mask, b = x, c = y (framebuffer pixels).
	 */
	public record Event(byte type, int a, int b, int c) {
		public static Event key(final int keysym, final boolean down) {
			return new Event(KEY, keysym, down ? 1 : 0, 0);
		}

		public static Event scancode(final int qcode, final boolean down) {
			return new Event(SCANCODE, qcode, down ? 1 : 0, 0);
		}

		public static Event pointer(final int mask, final int x, final int y) {
			return new Event(POINTER, mask, x, y);
		}

		public static Event chr(final int codepoint) {
			return new Event(CHAR, codepoint, 1, 0);
		}
	}

	public VmInputPayload(final FriendlyByteBuf buf) {
		this(buf.readUUID(), readEvents(buf));
	}

	private static List<Event> readEvents(final FriendlyByteBuf buf) {
		final int n = Math.min(buf.readVarInt(), MAX_EVENTS);
		final List<Event> list = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			list.add(new Event(buf.readByte(), buf.readInt(), buf.readInt(), buf.readInt()));
		}
		return list;
	}

	@Override
	public void write(final FriendlyByteBuf buf) {
		buf.writeUUID(vm);
		final int n = Math.min(events.size(), MAX_EVENTS);
		buf.writeVarInt(n);
		for (int i = 0; i < n; i++) {
			final Event e = events.get(i);
			buf.writeByte(e.type());
			buf.writeInt(e.a());
			buf.writeInt(e.b());
			buf.writeInt(e.c());
		}
	}

	@Override
	public ResourceLocation id() {
		return ID;
	}
}
