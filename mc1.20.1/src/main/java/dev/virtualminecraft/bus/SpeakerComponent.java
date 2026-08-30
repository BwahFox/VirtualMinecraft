package dev.virtualminecraft.bus;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.virtualminecraft.config.VmcConfig;
import dev.virtualminecraft.util.Nums;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundStopSoundPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;

/**
 * A speaker in the computer case: note-block notes and any sound event in the registry, played at the
 * computer's position (CC: Tweaked's speaker). This is one of the two components that reach *out* at
 * players, so it is budgeted — {@link VmcConfig#speakerSoundsPerSecond} calls a second, then
 * {@code -32001 RATE_LIMITED} — and the volume is capped so a guest cannot out-shout the game.
 * <p>
 * Sounds go out on {@link SoundSource#RECORDS} (the "Jukebox/Note Blocks" slider), like note blocks, so a
 * player who wants the computers quiet has a vanilla way to do it.
 */
public final class SpeakerComponent implements Component {
	public static final String TYPE = "speaker";
	/** Vanilla note blocks use 3.0; more than that carries absurdly far (range is 16 blocks per unit of volume). */
	public static final float MAX_VOLUME = 3.0F;
	private static final Map<String, String> METHODS = new LinkedHashMap<>();

	static {
		METHODS.put("playNote", "playNote(instrument, note[, volume]) -> true; note 0-24 (12 = F#), see getInstruments()");
		METHODS.put("playSound", "playSound(id[, volume[, pitch]]) -> true; id is a sound event like 'minecraft:block.bell.use'");
		METHODS.put("getInstruments", "getInstruments() -> the note-block instrument names playNote accepts");
		METHODS.put("stop", "stop() -> true; silences sounds this speaker started, for players in earshot");
	}

	private final ServerLevel level;
	private final BusHost be;
	private final UUID address;

	public SpeakerComponent(final ServerLevel level, final BusHost be) {
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
		final BlockPos pos = be.hostPos();
		switch (method) {
			case "getInstruments": {
				final JsonArray out = new JsonArray();
				for (final NoteBlockInstrument i : NoteBlockInstrument.values()) {
					if (!i.hasCustomSound()) {
						out.add(i.getSerializedName());
					}
				}
				return out;
			}
			case "playNote": {
				final NoteBlockInstrument instrument = instrument(arg(args, 0));
				final int note = (int) Nums.clamp(number(arg(args, 1), "note"), 0, 24);
				final float volume = volume(arg(args, 2));
				budget();
				// Note-block pitch: two octaves centred on F#, 2^((note - 12) / 12).
				final float pitch = (float) Math.pow(2.0, (note - 12) / 12.0);
				level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
					instrument.getSoundEvent().value(), SoundSource.RECORDS, volume, pitch);
				return new JsonPrimitive(true);
			}
			case "playSound": {
				final Holder<SoundEvent> sound = sound(arg(args, 0));
				final float volume = volume(arg(args, 1));
				final float pitch = (float) Nums.clamp(arg(args, 2) == null ? 1.0 : number(arg(args, 2), "pitch"), 0.5, 2.0);
				budget();
				level.playSound(null, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, sound.value(), SoundSource.RECORDS, volume, pitch);
				return new JsonPrimitive(true);
			}
			case "stop": {
				// Untargeted stop of our category: the client has no notion of "this block's sounds".
				final ClientboundStopSoundPacket packet = new ClientboundStopSoundPacket((ResourceLocation) null, SoundSource.RECORDS);
				final double range = MAX_VOLUME * 16.0;
				for (final ServerPlayer p : level.players()) {
					if (p.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= range * range) {
						p.connection.send(packet);
					}
				}
				return new JsonPrimitive(true);
			}
			default:
				throw new BusException(BusException.METHOD_NOT_FOUND, "speaker has no method '" + method + "'");
		}
	}

	private void budget() throws BusException {
		if (!be.soundBudget().tryAcquire(level.getGameTime())) {
			throw new BusException(BusException.RATE_LIMITED,
				"the speaker is playing too fast (max " + VmcConfig.get().speakerSoundsPerSecond + "/s); retry in " + be.soundBudget().retryInSeconds() + "s");
		}
	}

	private static NoteBlockInstrument instrument(final JsonElement e) throws BusException {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw BusException.invalidParams("instrument name required (see getInstruments)");
		}
		final String want = e.getAsString().strip().toLowerCase();
		for (final NoteBlockInstrument i : NoteBlockInstrument.values()) {
			if (i.getSerializedName().equals(want) && !i.hasCustomSound()) {
				return i;
			}
		}
		throw BusException.invalidParams("unknown instrument '" + want + "' (see getInstruments)");
	}

	private static Holder<SoundEvent> sound(final JsonElement e) throws BusException {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
			throw BusException.invalidParams("sound id required, e.g. 'minecraft:block.bell.use'");
		}
		final ResourceLocation id = ResourceLocation.tryParse(e.getAsString().strip());
		if (id == null) {
			throw BusException.invalidParams("'" + e.getAsString() + "' is not a valid sound id");
		}
		// Only registered sounds: an arbitrary string would be a client-side resource lookup we do not control.
		final java.util.Optional<Holder.Reference<SoundEvent>> found = BuiltInRegistries.SOUND_EVENT.getHolder(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.SOUND_EVENT, id));
		if (found.isEmpty()) {
			throw BusException.invalidParams("no such sound '" + id + "'");
		}
		return found.get();
	}

	private static float volume(final JsonElement e) throws BusException {
		return (float) Nums.clamp(e == null ? 1.0 : number(e, "volume"), 0.0, MAX_VOLUME);
	}

	private static JsonElement arg(final JsonArray args, final int i) {
		return i < args.size() ? args.get(i) : null;
	}

	private static double number(final JsonElement e, final String what) throws BusException {
		if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
			throw BusException.invalidParams(what + " must be a number");
		}
		return e.getAsDouble();
	}
}
