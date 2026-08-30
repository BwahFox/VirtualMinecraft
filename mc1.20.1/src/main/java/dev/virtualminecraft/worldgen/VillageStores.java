package dev.virtualminecraft.worldgen;

import com.mojang.datafixers.util.Pair;
import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.config.VmcConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;

/**
 * Puts the software store into villages (ROADMAP §9 U3c step 2, [name] 2026-08-28, on the experiments branch): when a
 * server starts, the store piece is appended to every house pool named in {@link VmcConfig#villageStorePools} with
 * {@link VmcConfig#villageStoreWeight} tickets. The piece carries the two jigsaw blocks a village house needs -- a
 * {@code building_entrance} at the door step, which is how it hooks onto a street, and a {@code bottom} socket under
 * the floor that pulls a villager from the village's own villager pool, which is how every vanilla house gets one.
 * <p>
 * Done at runtime rather than by overriding the vanilla pool files, so it composes with whatever else changed those
 * pools (CTOV's villages are just more pool ids for the config list). A pool named in the config that does not exist
 * is skipped with a log line -- a modpack without that biome's villages is not an error.
 * <p>
 * The pool's two lists are private and final, and they are reached by reflection <b>by type</b>, not by name: a
 * mixin accessor was tried first and the built jar carried its field names unremapped with no refmap, which would have
 * failed to apply in the real game. The class has exactly one {@code List} field (the weighted pairs the codec read)
 * and one {@code ObjectArrayList} (the draw list, one entry per ticket); type references survive remapping.
 */
public final class VillageStores {
	private static final String PIECE = "virtualminecraft:software_store";
	private static final Set<StructureTemplatePool> DONE = Collections.newSetFromMap(new WeakHashMap<>());
	private static final Field RAW = fieldOfType(List.class);
	private static final Field DRAW = fieldOfType(ObjectArrayList.class);

	private static Field fieldOfType(final Class<?> type) {
		Field found = null;
		for (final Field f : StructureTemplatePool.class.getDeclaredFields()) {
			if (f.getType() == type) {
				if (found != null) {
					throw new IllegalStateException("StructureTemplatePool has two " + type.getSimpleName() + " fields; the store cannot tell them apart");
				}
				found = f;
			}
		}
		if (found == null) {
			throw new IllegalStateException("StructureTemplatePool has no " + type.getSimpleName() + " field");
		}
		found.setAccessible(true);
		return found;
	}

	private VillageStores() {
	}

	public static void addToPools(final MinecraftServer server) {
		final VmcConfig cfg = VmcConfig.get();
		final int weight = Math.max(0, cfg.villageStoreWeight);
		if (weight == 0 || cfg.villageStorePools == null || cfg.villageStorePools.isEmpty()) {
			return;
		}
		final Registry<StructureTemplatePool> pools = server.registryAccess().registryOrThrow(Registries.TEMPLATE_POOL);
		int added = 0;
		for (final String id : cfg.villageStorePools) {
			final ResourceLocation poolId;
			try {
				poolId = new ResourceLocation(id);
			} catch (final RuntimeException e) {
				VirtualMinecraft.LOGGER.warn("villageStorePools: '{}' is not a valid id", id);
				continue;
			}
			final StructureTemplatePool pool = pools.getOptional(poolId).orElse(null);
			if (pool == null) {
				VirtualMinecraft.LOGGER.info("villageStorePools: no pool {} in this world (skipped)", poolId);
				continue;
			}
			if (!DONE.add(pool)) {
				continue; // this world's pool already has it
			}
			final StructurePoolElement piece = StructurePoolElement.single(PIECE).apply(StructureTemplatePool.Projection.RIGID);
			try {
				@SuppressWarnings("unchecked")
				final List<Pair<StructurePoolElement, Integer>> raw = new ArrayList<>((List<Pair<StructurePoolElement, Integer>>) RAW.get(pool));
				raw.add(Pair.of(piece, weight));
				RAW.set(pool, raw);
				@SuppressWarnings("unchecked")
				final ObjectArrayList<StructurePoolElement> draw = (ObjectArrayList<StructurePoolElement>) DRAW.get(pool);
				for (int i = 0; i < weight; i++) {
					draw.add(piece); // one entry per ticket, as the constructor builds it
				}
			} catch (final ReflectiveOperationException | RuntimeException e) {
				VirtualMinecraft.LOGGER.warn("villageStorePools: could not add the store to {}: {}", poolId, e.toString());
				continue;
			}
			added++;
		}
		if (added > 0) {
			VirtualMinecraft.LOGGER.info("software store added to {} village house pool(s), weight {}", added, weight);
		}
	}
}
