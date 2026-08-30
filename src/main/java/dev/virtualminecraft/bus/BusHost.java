package dev.virtualminecraft.bus;

import com.google.gson.JsonObject;
import dev.virtualminecraft.block.MonitorBlockEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * A block entity that owns a bus: what the {@link Component}s need from the machine they belong to, whichever
 * tier it is. The VM computer ({@code ComputerBlockEntity}) and the in-JVM Computer (milestone 7) both implement
 * it, so the components — redstone, inventory, world, speaker, chat, screen, drive — are written once. Every
 * method is called on the server thread, like {@link Component#invoke}.
 */
public interface BusHost {
	/** Stable per machine; component addresses derive from it. */
	UUID busId();

	/**
	 * Whether this machine is up enough to be handed an event. A machine that is thawing has a block entity but
	 * no running kernel yet, and an event delivered to it in that instant is simply dropped — which is how a
	 * woken web server managed to answer nothing at all (§9 U9/U8, session 21). {@link dev.virtualminecraft.bus.BusWake}
	 * waits for this rather than for the block entity.
	 */
	default boolean busReady() {
		return true;
	}

	/** Shown as the prefix of chat lines and in messages. */
	String busName();

	BlockPos getBlockPos();

	BlockState getBlockState();

	@Nullable Level getLevel();

	/** The block's facing, for relative side names. */
	Direction facing();

	/** Everything on this machine's bus, nearest first: position → location string (side name or {@code dx,dy,dz}). */
	LinkedHashMap<BlockPos, String> attached(ServerLevel level);

	/** The loaded monitors that registered with this machine. */
	List<MonitorBlockEntity> linkedMonitors(ServerLevel level);

	RateLimiter soundBudget();

	RateLimiter chatBudget();

	/** Budget for the {@code net} component's messages. */
	RateLimiter netBudget();

	int getOutput(Direction side);

	int setOutput(Direction side, int level);

	int getInput(ServerLevel level, Direction side);

	/** Redstone wake (OpenComputers' wake threshold): 1–15 = a rising edge to it starts a machine that is off; 0 = never. */
	default int getWakeThreshold() {
		return 0;
	}

	default void setWakeThreshold(final int threshold) {
	}

	/** With a wake threshold set, a falling edge back below it also asks the machine to shut down. */
	default boolean getRedstoneSleep() {
		return false;
	}

	default void setRedstoneSleep(final boolean on) {
	}

	/** Push a bus event to whatever program is listening (a guest over virtio-serial, or the Lua machine's queue). */
	void emitEvent(String name, JsonObject params);

	/**
	 * A removable disk went in or out of a drive on this machine's bus. The mount table is normally rebuilt on a
	 * timer, which is late by up to 8 ticks — long enough for the program that receives {@code disk_inserted} to
	 * look and see nothing. Machines that mount media themselves rebuild it here, before the event goes out.
	 */
	default void mediaChanged(ServerLevel level) {
	}

	/** A block next to this machine, or on its cable run, changed: re-sample inputs and components. */
	void onNeighborChanged(ServerLevel level);
}
