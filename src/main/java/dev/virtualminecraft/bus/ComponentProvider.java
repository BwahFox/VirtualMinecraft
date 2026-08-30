package dev.virtualminecraft.bus;

import java.util.List;
import net.minecraft.server.level.ServerLevel;

/** Contributes components for a computer. Called on the server thread for every {@code list}/{@code invoke}. */
@FunctionalInterface
public interface ComponentProvider {
	void collect(ServerLevel level, BusHost computer, List<Component> out);
}
