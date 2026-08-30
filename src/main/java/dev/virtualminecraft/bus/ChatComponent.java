package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.vm.VmInstance;
import dev.virtualminecraft.vm.VmManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * The computer talking to players: it can say things in chat and hear what is said near it (OpenComputers'
 * chat box). Both halves are range-limited to {@link VmcConfig#chatRange} blocks around the computer — a
 * computer in a basement cannot spam the server — and outgoing messages are budgeted by
 * {@link VmcConfig#chatMessagesPerMinute}. {@code allowChat=false} in the global config turns the whole
 * component off (it disappears from {@code list}).
 * <p>
 * Everything a guest sends is treated as hostile text: section signs and control characters are stripped and
 * the line is truncated, so a guest cannot forge another player's message, inject formatting, or paste a
 * screenful. Messages are prefixed with the computer's name so players always know who is talking.
 */
public final class ChatComponent implements Component {
	public static final String TYPE = "chat";
	/** Longest line a guest may send. Chat itself allows 256; the prefix has to fit too. */
	public static final int MAX_LENGTH = 200;
	private static final Map<String, String> METHODS = new LinkedHashMap<>();

	static {
		METHODS.put("say", "say(text) -> number of players who heard it; goes to everyone in range of the computer");
		METHODS.put("send", "send(player, text) -> true; a private message to one player in range");
		METHODS.put("getPlayers", "getPlayers() -> names of the players in chat range");
		METHODS.put("getRange", "getRange() -> how far this computer can be heard, in blocks (-1 = server-wide)");
	}

	private final ServerLevel level;
	private final BusHost be;
	private final UUID address;

	public ChatComponent(final ServerLevel level, final BusHost be) {
		this.level = level;
		this.be = be;
		this.address = Component.addressOf(be.busId(), TYPE, "self");
	}

	@Override
	public UUID address() {
		return address;
	}

	@Override
	public String type() {
		return TYPE;
	}

	@Override
	public String location() {
		return "self";
	}

	@Override
	public Map<String, String> methods() {
		return METHODS;
	}

	@Override
	public JsonElement invoke(final String method, final JsonArray args) throws BusException {
		switch (method) {
			case "getRange":
				return new JsonPrimitive(VmcConfig.get().chatRange);
			case "getPlayers": {
				final JsonArray out = new JsonArray();
				for (final ServerPlayer p : inRange()) {
					out.add(p.getName().getString());
				}
				return out;
			}
			case "say": {
				final String text = text(arg(args, 0));
				budget();
				final List<ServerPlayer> targets = inRange();
				final MutableComponent message = prefixed(text);
				for (final ServerPlayer p : targets) {
					p.sendSystemMessage(message);
				}
				return new JsonPrimitive(targets.size());
			}
			case "send": {
				final String name = string(arg(args, 0), "player name");
				final String text = text(arg(args, 1));
				budget();
				for (final ServerPlayer p : inRange()) {
					if (p.getName().getString().equalsIgnoreCase(name)) {
						p.sendSystemMessage(prefixed(text));
						return new JsonPrimitive(true);
					}
				}
				throw new BusException(BusException.COMPONENT_ERROR, "no player called '" + name + "' is in range");
			}
			default:
				throw new BusException(BusException.METHOD_NOT_FOUND, "chat has no method '" + method + "'");
		}
	}

	private MutableComponent prefixed(final String text) {
		return net.minecraft.network.chat.Component.literal("[" + clean(be.busName(), 32) + "] ")
			.withStyle(ChatFormatting.AQUA)
			.append(net.minecraft.network.chat.Component.literal(text).withStyle(ChatFormatting.WHITE));
	}

	private List<ServerPlayer> inRange() {
		return playersInRange(level, be.getBlockPos());
	}

	static List<ServerPlayer> playersInRange(final ServerLevel level, final BlockPos pos) {
		final int range = VmcConfig.get().chatRange;
		final List<ServerPlayer> out = new ArrayList<>();
		for (final ServerPlayer p : level.players()) {
			if (range < 0 || p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= (double) range * range) {
				out.add(p);
			}
		}
		return out;
	}

	private void budget() throws BusException {
		if (!be.chatBudget().tryAcquire(level.getGameTime())) {
			throw new BusException(BusException.RATE_LIMITED,
				"this computer is talking too much (max " + VmcConfig.get().chatMessagesPerMinute + "/min); retry in " + be.chatBudget().retryInSeconds() + "s");
		}
	}

	private static JsonElement arg(final JsonArray args, final int i) {
		return i < args.size() ? args.get(i) : null;
	}

	private static String string(final JsonElement e, final String what) throws BusException {
		if (e == null || !e.isJsonPrimitive()) {
			throw BusException.invalidParams(what + " required");
		}
		return e.getAsString();
	}

	private static String text(final JsonElement e) throws BusException {
		final String cleaned = clean(string(e, "text"), MAX_LENGTH);
		if (cleaned.isEmpty()) {
			throw BusException.invalidParams("the message is empty");
		}
		return cleaned;
	}

	/** Guest text is untrusted: no section signs (formatting/colour injection), no control characters, bounded length. */
	static String clean(final String raw, final int max) {
		final StringBuilder sb = new StringBuilder(Math.min(raw.length(), max));
		for (int i = 0; i < raw.length() && sb.length() < max; i++) {
			final char c = raw.charAt(i);
			if (c == 167 || c < ' ' || c == 127) {
				continue; // 167 = the section sign Minecraft uses for formatting codes
			}
			sb.append(c);
		}
		return sb.toString().strip();
	}

	// ---------------------------------------------------------------------------------------------
	// Hearing: player chat near a computer becomes a bus event
	// ---------------------------------------------------------------------------------------------

	/**
	 * Registered once at mod init. Every running VM whose guest subscribed to {@code chat} and whose computer
	 * is within {@link VmcConfig#chatRange} of the speaker gets {@code chat {player, message, distance}}.
	 * Runs on the server thread, straight from the chat broadcast.
	 */
	public static void register() {
		ServerMessageEvents.CHAT_MESSAGE.register((message, sender, boundChatType) -> {
			final VmManager manager = VmManager.get(sender.level().getServer());
			final int range = VmcConfig.get().chatRange;
			final String text = clean(message.signedContent(), MAX_LENGTH);
			if (text.isEmpty()) {
				return;
			}
			for (final VmInstance vm : manager.instances()) {
				final VmBus bus = vm.bus();
				if (bus == null || !bus.wantsEvent("chat")) {
					continue;
				}
				final BusHost be = vm.computer();
				if (be == null || be.getLevel() != sender.level()) {
					continue;
				}
				final BlockPos pos = be.getBlockPos();
				final double d2 = sender.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
				if (range >= 0 && d2 > (double) range * range) {
					continue;
				}
				final JsonObject p = new JsonObject();
				p.addProperty("address", Component.addressOf(be.busId(), TYPE, "self").toString());
				p.addProperty("player", sender.getName().getString());
				p.addProperty("message", text);
				p.addProperty("distance", Math.round(Math.sqrt(d2) * 100) / 100.0);
				bus.event("chat", p);
			}
		});
	}

	/** The component only exists when the server allows it, so {@code list} tells the guest the truth. */
	public static void collect(final ServerLevel level, final BusHost computer, final List<Component> out) {
		if (VmcConfig.get().allowChat) {
			out.add(new ChatComponent(level, computer));
		}
	}
}
