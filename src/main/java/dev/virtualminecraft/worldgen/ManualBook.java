package dev.virtualminecraft.worldgen;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.config.VmcConfig;
import java.util.Set;
import net.fabricmc.fabric.api.loot.v3.LootTableEvents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.loot.BuiltInLootTables;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.entries.NestedLootTable;
import net.minecraft.world.level.storage.loot.predicates.LootItemRandomChanceCondition;
import net.minecraft.world.level.storage.loot.providers.number.ConstantValue;

/**
 * Books as loot (ROADMAP §9 U3c, [name]'s own framing: <i>"a written book you find in a chest is the teaser for
 * it"</i>). The Manual itself is on the machine, which is no use at all to somebody who has never seen a machine —
 * so a written book turns up in ordinary village house chests, says in four pages what the grey box is, and points
 * at the Manual and at {@code man}. Somebody who finds the book before the computer then knows what the computer is
 * for; that is the whole job, and it is why this goes in <em>vanilla</em> chests rather than in the software
 * store's, where it would only ever be read by someone who had already found the store.
 * <p>
 * The book's text is a loot table of its own ({@code chests/manual_book}) so it stays editable data; this class is
 * only the wiring. It touches the built-in tables only — {@code source.isBuiltin()} — so a datapack that has
 * deliberately replaced a village chest keeps what it wrote.
 */
public final class ManualBook {
	/** The vanilla chests this may appear in: houses, and the cartographer, who would keep notes. */
	private static final Set<ResourceKey<LootTable>> TABLES = Set.of(
		BuiltInLootTables.VILLAGE_PLAINS_HOUSE, BuiltInLootTables.VILLAGE_DESERT_HOUSE,
		BuiltInLootTables.VILLAGE_SAVANNA_HOUSE, BuiltInLootTables.VILLAGE_TAIGA_HOUSE,
		BuiltInLootTables.VILLAGE_SNOWY_HOUSE, BuiltInLootTables.VILLAGE_CARTOGRAPHER);

	private static final ResourceKey<LootTable> BOOK = ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE, VirtualMinecraft.id("chests/manual_book"));

	private ManualBook() {
	}

	public static void register() {
		LootTableEvents.MODIFY.register((key, builder, source, holder) -> {
			final double chance = VmcConfig.get().manualBookChance;
			if (chance <= 0 || !source.isBuiltin() || !TABLES.contains(key)) {
				return;
			}
			// Its own pool, so the chest keeps everything it would have had: the book is an addition to a village
			// house, not a replacement for whatever was in the box.
			builder.withPool(LootPool.lootPool()
				.setRolls(ConstantValue.exactly(1))
				.when(LootItemRandomChanceCondition.randomChance((float) Math.min(1.0, chance)))
				.add(NestedLootTable.lootTableReference(BOOK)));
		});
	}
}
