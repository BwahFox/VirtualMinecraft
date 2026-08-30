package dev.virtualminecraft.bus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/** Registry of {@link ComponentProvider}s. The built-in redstone component is registered at mod init. */
public final class Components {
	private static final List<ComponentProvider> PROVIDERS = new CopyOnWriteArrayList<>();

	private Components() {
	}

	public static void register(final ComponentProvider provider) {
		PROVIDERS.add(provider);
	}

	public static List<Component> collect(final ServerLevel level, final BusHost computer) {
		final List<Component> out = new ArrayList<>();
		for (final ComponentProvider p : PROVIDERS) {
			p.collect(level, computer, out);
		}
		return out;
	}

	public static void registerBuiltins() {
		register((level, computer, out) -> out.add(new RedstoneComponent(level, computer)));
		register((level, computer, out) -> out.add(new WorldComponent(level, computer)));
		register((level, computer, out) -> out.add(new SpeakerComponent(level, computer)));
		register(ChatComponent::collect);
		register(NetComponent::collect);
		register(InventoryComponent::collect);
		register(ScreenComponent::collect);
		register(DriveComponent::collect);
	}

	/**
	 * Resolves a guest-supplied target: a full address, {@code type@location}, a bare type (first match), or —
	 * when {@code defaultType} is given — a bare location of that type. Returns null if nothing matches.
	 */
	public static @Nullable Component find(final List<Component> components, final String target, final @Nullable String defaultType) {
		for (final Component c : components) {
			if (c.address().toString().equalsIgnoreCase(target) || (c.type() + "@" + c.location()).equalsIgnoreCase(target)) {
				return c;
			}
		}
		for (final Component c : components) {
			if (c.type().equals(target)) {
				return c;
			}
		}
		if (defaultType != null) {
			for (final Component c : components) {
				if (c.type().equals(defaultType) && c.location().equalsIgnoreCase(target)) {
					return c;
				}
			}
		}
		return null;
	}
}
