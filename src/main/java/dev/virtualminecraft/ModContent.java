package dev.virtualminecraft;

import dev.virtualminecraft.block.BusCableBlock;
import dev.virtualminecraft.block.ComputerBlock;
import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.block.DiskDriveBlock;
import dev.virtualminecraft.block.DiskDriveBlockEntity;
import dev.virtualminecraft.block.ModemBlock;
import dev.virtualminecraft.block.ModemBlockEntity;
import dev.virtualminecraft.block.MonitorBlock;
import dev.virtualminecraft.block.MonitorBlockEntity;
import dev.virtualminecraft.item.DiskData;
import dev.virtualminecraft.item.DiskItem;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;

public final class ModContent {
	private ModContent() {
	}

	/** Item component carried by floppy / CD / hard-drive items (see {@link DiskData}). */
	public static final DataComponentType<DiskData> DISK_DATA = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, VirtualMinecraft.id("disk"),
		DataComponentType.<DiskData>builder().persistent(DiskData.CODEC).networkSynchronized(DiskData.STREAM_CODEC).build());

	/** The Computer (milestone 7): an item that carries these keeps its machine when broken and re-placed. */
	public static final DataComponentType<java.util.UUID> COMPUTER_ID = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, VirtualMinecraft.id("computer_id"),
		DataComponentType.<java.util.UUID>builder().persistent(net.minecraft.core.UUIDUtil.STRING_CODEC).networkSynchronized(net.minecraft.core.UUIDUtil.STREAM_CODEC).build());
	/** §9 U11 bridges: the pair id both halves come out of the recipe carrying. */
	public static final DataComponentType<java.util.UUID> BRIDGE_PAIR = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, VirtualMinecraft.id("bridge_pair"),
		DataComponentType.<java.util.UUID>builder().persistent(net.minecraft.core.UUIDUtil.STRING_CODEC).networkSynchronized(net.minecraft.core.UUIDUtil.STREAM_CODEC).build());
	public static final DataComponentType<String> COMPUTER_LABEL = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, VirtualMinecraft.id("computer_label"),
		DataComponentType.<String>builder().persistent(com.mojang.serialization.Codec.STRING).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.STRING_UTF8).build());
	public static final DataComponentType<Integer> COMPUTER_MEM_MB = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, VirtualMinecraft.id("computer_mem_mb"),
		DataComponentType.<Integer>builder().persistent(com.mojang.serialization.Codec.INT).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT).build());

	/** The tier ladder (ROADMAP §9 U3b): three cases, one block entity; parts go inside through the case's GUI. */
	public static final Block BASIC_COMPUTER = registerBlock("basic_computer", p -> new dev.virtualminecraft.computer.LuaComputerBlock(p, 1),
		BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_ORANGE).strength(1.0F).sound(SoundType.METAL));
	public static final Block LUA_COMPUTER = registerBlock("computer", p -> new dev.virtualminecraft.computer.LuaComputerBlock(p, 2),
		BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(1.0F).sound(SoundType.METAL));
	public static final Block ADVANCED_COMPUTER = registerBlock("advanced_computer", p -> new dev.virtualminecraft.computer.LuaComputerBlock(p, 3),
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.5F).sound(SoundType.METAL));
	public static final Block COMPUTER = registerBlock("command_computer", ComputerBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.5F).sound(SoundType.METAL));
	public static final Block MONITOR = registerBlock("monitor", MonitorBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLACK).strength(1.0F).sound(SoundType.METAL));
	public static final Block DISK_DRIVE = registerBlock("disk_drive", DiskDriveBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.0F).sound(SoundType.METAL));
	/** Gives the machines on its bus wireless {@code net} reach; see {@link dev.virtualminecraft.bus.Modems}. */
	public static final Block MODEM = registerBlock("modem", ModemBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(1.0F).sound(SoundType.METAL));
	/** Extends a computer's reach; see {@link dev.virtualminecraft.bus.BusNetwork}. No block entity: it is pure geometry. */
	public static final Block BUS_CABLE = registerBlock("bus_cable", BusCableBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.6F).sound(SoundType.METAL).noOcclusion());
	/** Joins two cable runs however far apart they are (§9 U11); crafted two at a time, already paired. */
	public static final Block BUS_BRIDGE = registerBlock("bus_bridge", dev.virtualminecraft.block.BridgeBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_LIGHT_BLUE).strength(1.5F).sound(SoundType.METAL));

	/** The clerk's workstation (U3c step 2): no behaviour of its own; a villager working at it is a Clerk. */
	public static final Block CASH_REGISTER = registerBlock("cash_register", dev.virtualminecraft.block.CashRegisterBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_WHITE).strength(1.5F).sound(SoundType.METAL));
	public static final Item CASH_REGISTER_ITEM = registerBlockItem("cash_register", CASH_REGISTER);
	/** A keyboard on a desk (§9 U4.3): pure furniture here; in VR the {@code vr} module anchors the floating keyboard to it. */
	public static final Block KEYBOARD = registerBlock("keyboard", dev.virtualminecraft.block.KeyboardBlock::new,
		BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_GRAY).strength(0.5F).sound(SoundType.STONE).noOcclusion());
	public static final Item KEYBOARD_ITEM = registerBlockItem("keyboard", KEYBOARD);
	/** The Clerk: works at the cash register, trades are the data-driven ladder in {@code data/virtualminecraft/trade_set/clerk}. */
	public static final ResourceKey<net.minecraft.world.entity.ai.village.poi.PoiType> CLERK_POI = ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, VirtualMinecraft.id("clerk"));
	public static final ResourceKey<net.minecraft.world.entity.npc.villager.VillagerProfession> CLERK = ResourceKey.create(Registries.VILLAGER_PROFESSION, VirtualMinecraft.id("clerk"));
	static {
		net.fabricmc.fabric.api.object.builder.v1.world.poi.PoiHelper.register(VirtualMinecraft.id("clerk"), 1, 1, CASH_REGISTER);
		final it.unimi.dsi.fastutil.ints.Int2ObjectMap<ResourceKey<net.minecraft.world.item.trading.TradeSet>> trades = new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>();
		for (int level = 1; level <= 5; level++) {
			trades.put(level, ResourceKey.create(Registries.TRADE_SET, VirtualMinecraft.id("clerk/level_" + level)));
		}
		Registry.register(BuiltInRegistries.VILLAGER_PROFESSION, CLERK, new net.minecraft.world.entity.npc.villager.VillagerProfession(
			net.minecraft.network.chat.Component.translatable("entity.virtualminecraft.villager.clerk"),
			holder -> holder.is(CLERK_POI), holder -> holder.is(CLERK_POI),
			com.google.common.collect.ImmutableSet.of(), com.google.common.collect.ImmutableSet.of(),
			net.minecraft.sounds.SoundEvents.VILLAGER_WORK_LIBRARIAN, trades));
	}

	public static final Item BASIC_COMPUTER_ITEM = registerCase("basic_computer", BASIC_COMPUTER, 1);
	public static final Item LUA_COMPUTER_ITEM = registerCase("computer", LUA_COMPUTER, 2);
	public static final Item ADVANCED_COMPUTER_ITEM = registerCase("advanced_computer", ADVANCED_COMPUTER, 3);
	/** The parts, by kind then level (I, II, III): {@code PARTS[kind.ordinal()][level - 1]}. */
	public static final Item[][] PARTS = new Item[dev.virtualminecraft.computer.MachineSpec.Part.ALL.length][dev.virtualminecraft.computer.MachineSpec.LEVELS];
	static {
		for (final dev.virtualminecraft.computer.MachineSpec.Part part : dev.virtualminecraft.computer.MachineSpec.Part.ALL) {
			for (int level = 1; level <= dev.virtualminecraft.computer.MachineSpec.LEVELS; level++) {
				final int l = level;
				PARTS[part.ordinal()][level - 1] = registerItem(part.name().toLowerCase(java.util.Locale.ROOT) + "_" + level, p -> new dev.virtualminecraft.item.PartItem(part, l, p));
			}
		}
	}
	public static final Item COMPUTER_ITEM = registerBlockItem("command_computer", COMPUTER);
	public static final Item MONITOR_ITEM = registerBlockItem("monitor", MONITOR);
	public static final Item DISK_DRIVE_ITEM = registerBlockItem("disk_drive", DISK_DRIVE);
	public static final Item BUS_CABLE_ITEM = registerBlockItem("bus_cable", BUS_CABLE);
	public static final Item MODEM_ITEM = registerBlockItem("modem", MODEM);
	public static final Item BUS_BRIDGE_ITEM = registerBlockItem("bus_bridge", BUS_BRIDGE);
	public static final Item FLOPPY = registerItem("floppy", p -> new DiskItem(DiskItem.Kind.FLOPPY, p));
	public static final Item CD = registerItem("cd", p -> new DiskItem(DiskItem.Kind.CD, p));
	public static final Item HARD_DRIVE = registerItem("hard_drive", p -> new DiskItem(DiskItem.Kind.HARD_DRIVE, p));

	public static final BlockEntityType<dev.virtualminecraft.computer.LuaComputerBlockEntity> LUA_COMPUTER_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, VirtualMinecraft.id("computer"), new BlockEntityType<>(dev.virtualminecraft.computer.LuaComputerBlockEntity::new, Set.of(BASIC_COMPUTER, LUA_COMPUTER, ADVANCED_COMPUTER)));
	/** The case's GUI (parts in, power, what the machine is); the client gets the block position with the menu. */
	public static final net.minecraft.world.inventory.MenuType<dev.virtualminecraft.computer.ComputerMenu> COMPUTER_MENU = Registry.register(
		BuiltInRegistries.MENU, VirtualMinecraft.id("computer"),
		new net.fabricmc.fabric.api.menu.v1.ExtendedMenuType<>(dev.virtualminecraft.computer.ComputerMenu::new, net.minecraft.core.BlockPos.STREAM_CODEC));
	public static final BlockEntityType<ComputerBlockEntity> COMPUTER_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, VirtualMinecraft.id("command_computer"), new BlockEntityType<>(ComputerBlockEntity::new, Set.of(COMPUTER)));
	public static final BlockEntityType<MonitorBlockEntity> MONITOR_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, VirtualMinecraft.id("monitor"), new BlockEntityType<>(MonitorBlockEntity::new, Set.of(MONITOR)));
	public static final BlockEntityType<ModemBlockEntity> MODEM_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, VirtualMinecraft.id("modem"), new BlockEntityType<>(ModemBlockEntity::new, Set.of(MODEM)));
	public static final BlockEntityType<dev.virtualminecraft.block.BridgeBlockEntity> BRIDGE_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, VirtualMinecraft.id("bus_bridge"),
		new BlockEntityType<>(dev.virtualminecraft.block.BridgeBlockEntity::new, Set.of(BUS_BRIDGE)));
	public static final BlockEntityType<DiskDriveBlockEntity> DISK_DRIVE_BLOCK_ENTITY = Registry.register(
		BuiltInRegistries.BLOCK_ENTITY_TYPE, VirtualMinecraft.id("disk_drive"), new BlockEntityType<>(DiskDriveBlockEntity::new, Set.of(DISK_DRIVE)));

	private static Block registerBlock(final String name, final Function<BlockBehaviour.Properties, Block> factory, final BlockBehaviour.Properties properties) {
		final ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, VirtualMinecraft.id(name));
		return Registry.register(BuiltInRegistries.BLOCK, key, factory.apply(properties.setId(key)));
	}

	private static Item registerBlockItem(final String name, final Block block) {
		final ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, VirtualMinecraft.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, new BlockItem(block, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
	}

	/** A Computer case: a {@link dev.virtualminecraft.item.CaseItem} so the tooltip can say it is empty (§9 U10(a)). */
	private static Item registerCase(final String name, final Block block, final int tier) {
		final ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, VirtualMinecraft.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key,
			new dev.virtualminecraft.item.CaseItem(block, tier, new Item.Properties().setId(key).useBlockDescriptionPrefix()));
	}

	private static Item registerItem(final String name, final Function<Item.Properties, Item> factory) {
		final ResourceKey<Item> key = ResourceKey.create(Registries.ITEM, VirtualMinecraft.id(name));
		return Registry.register(BuiltInRegistries.ITEM, key, factory.apply(new Item.Properties().setId(key)));
	}

	/** The bridge recipe's serializer: an ordinary shaped recipe whose output carries a fresh pair id (§9 U11). */
	public static final net.minecraft.world.item.crafting.RecipeSerializer<net.minecraft.world.item.crafting.ShapedRecipe> PAIRED_BRIDGE_SERIALIZER =
		Registry.register(BuiltInRegistries.RECIPE_SERIALIZER, VirtualMinecraft.id("paired_bridge"),
			dev.virtualminecraft.item.PairedBridgeRecipe.SERIALIZER);

	public static void init() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.REDSTONE_BLOCKS).register(output -> {
			output.accept(BASIC_COMPUTER_ITEM);
			output.accept(LUA_COMPUTER_ITEM);
			output.accept(ADVANCED_COMPUTER_ITEM);
			output.accept(COMPUTER_ITEM);
			output.accept(MONITOR_ITEM);
			output.accept(DISK_DRIVE_ITEM);
			output.accept(BUS_CABLE_ITEM);
			output.accept(MODEM_ITEM);
			output.accept(BUS_BRIDGE_ITEM);
			output.accept(CASH_REGISTER_ITEM);
			output.accept(KEYBOARD_ITEM);
			output.accept(HARD_DRIVE);
			output.accept(FLOPPY);
			// the CD was the one piece of removable media missing from the tab, so the only blank CDs in a
			// creative world came from `/vmc give` ([name], session 18). All three are craftable as well.
			output.accept(CD);
			for (final Item[] kind : PARTS) {
				for (final Item part : kind) {
					output.accept(part);
				}
			}
		});
	}
}
