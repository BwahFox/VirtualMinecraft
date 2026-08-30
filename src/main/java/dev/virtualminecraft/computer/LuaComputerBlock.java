package dev.virtualminecraft.computer;

import com.google.gson.JsonObject;
import com.mojang.serialization.MapCodec;
import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.VirtualMinecraft;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.redstone.Orientation;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/**
 * The Computer (ROADMAP §7h §8): the block everyone places. Boots the moment it is placed; right-click opens the
 * full-screen view (the keyboard), sneak + right-click toggles power. Breaking it drops an item that keeps the
 * machine's id, so its files and frozen state follow it. Redstone on the six faces is driven through the bus,
 * exactly like the Command Computer.
 */
public class LuaComputerBlock extends BaseEntityBlock {
	public static final MapCodec<LuaComputerBlock> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.mapCodec(i -> i.group(
		propertiesCodec(), com.mojang.serialization.Codec.INT.fieldOf("tier").forGetter(b -> b.tier)).apply(i, LuaComputerBlock::new));
	public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
	/** The case: 1 Basic Computer, 2 Computer, 3 Advanced Computer (ROADMAP §9 U3b). */
	public final int tier;

	public LuaComputerBlock(final Properties properties, final int tier) {
		super(properties);
		this.tier = Math.clamp(tier, 1, MachineSpec.TIERS);
		this.registerDefaultState(this.stateDefinition.any().setValue(FACING, Direction.NORTH));
	}

	/** The case block of a tier, for drops and migrations. */
	public static Block forTier(final int tier) {
		return switch (Math.clamp(tier, 1, MachineSpec.TIERS)) {
			case 1 -> ModContent.BASIC_COMPUTER;
			case 3 -> ModContent.ADVANCED_COMPUTER;
			default -> ModContent.LUA_COMPUTER;
		};
	}

	@Override
	protected MapCodec<? extends BaseEntityBlock> codec() {
		return CODEC;
	}

	@Override
	protected void createBlockStateDefinition(final StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public @Nullable BlockState getStateForPlacement(final BlockPlaceContext context) {
		return this.defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	@Override
	protected RenderShape getRenderShape(final BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public @Nullable BlockEntity newBlockEntity(final BlockPos pos, final BlockState state) {
		return new LuaComputerBlockEntity(pos, state);
	}

	@Override
	public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(final Level level, final BlockState state, final BlockEntityType<T> type) {
		if (level.isClientSide() || type != ModContent.LUA_COMPUTER_BLOCK_ENTITY) {
			return null;
		}
		return (lvl, pos, st, be) -> ((LuaComputerBlockEntity) be).serverTick((ServerLevel) lvl);
	}

	@Override
	public void setPlacedBy(final Level level, final BlockPos pos, final BlockState state, final @Nullable LivingEntity placer, final ItemStack stack) {
		super.setPlacedBy(level, pos, state, placer, stack);
		if (level.isClientSide() || !(level.getBlockEntity(pos) instanceof LuaComputerBlockEntity be)) {
			return;
		}
		final UUID carried = stack.get(ModContent.COMPUTER_ID);
		if (carried != null) {
			if (level instanceof ServerLevel sl && ComputerManager.get(sl.getServer()).get(carried) != null) {
				// a duplicated item (creative) while the original block still runs: this one is a new machine
				VirtualMinecraft.LOGGER.info("Computer item {} placed while its machine is live elsewhere: new machine at {}", carried, pos.toShortString());
			} else {
				be.adoptId(carried);
			}
		}
		final String label = stack.get(ModContent.COMPUTER_LABEL);
		if (label != null && !label.isEmpty()) {
			be.setName(label);
		}
		final Integer mem = stack.get(ModContent.COMPUTER_MEM_MB);
		if (mem != null) {
			be.installLegacyMemory(mem); // an item from before the ladder: its memory becomes a RAM part
		}
		if (placer instanceof Player p) {
			be.setOwner(p.getUUID(), p.getName().getString());
		}
	}

	/** The placement cap (§1d) is checked before the block goes down; a refusal is a sentence in the action bar. */
	@Override
	protected boolean canSurvive(final BlockState state, final net.minecraft.world.level.LevelReader level, final BlockPos pos) {
		return true;
	}

	static void dropWithId(final ServerLevel level, final BlockPos pos, final UUID id, final String name, final int tier) {
		final ItemStack stack = new ItemStack(forTier(tier));
		stack.set(ModContent.COMPUTER_ID, id);
		stack.set(ModContent.COMPUTER_LABEL, name);
		Block.popResource(level, pos, stack);
	}

	// ---- redstone: the machine drives the six faces through the bus ----

	@Override
	protected boolean isSignalSource(final BlockState state) {
		return true;
	}

	@Override
	protected int getSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction) {
		return level.getBlockEntity(pos) instanceof LuaComputerBlockEntity be ? be.getOutput(direction.getOpposite()) : 0;
	}

	@Override
	protected int getDirectSignal(final BlockState state, final BlockGetter level, final BlockPos pos, final Direction direction) {
		return getSignal(state, level, pos, direction);
	}

	@Override
	protected void neighborChanged(final BlockState state, final Level level, final BlockPos pos, final Block block, final @Nullable Orientation orientation, final boolean movedByPiston) {
		if (level instanceof ServerLevel serverLevel && level.getBlockEntity(pos) instanceof LuaComputerBlockEntity be) {
			be.onNeighborChanged(serverLevel);
		}
	}

	@Override
	protected InteractionResult useWithoutItem(final BlockState state, final Level level, final BlockPos pos, final Player player, final BlockHitResult hitResult) {
		if (level.isClientSide()) {
			return InteractionResult.SUCCESS;
		}
		if (!(level instanceof ServerLevel serverLevel) || !(level.getBlockEntity(pos) instanceof LuaComputerBlockEntity be)) {
			return InteractionResult.SUCCESS;
		}
		if (!player.isShiftKeyDown()) {
			// the case opens (U3b, [name]: "clicking on the computer opens a GUI similar to OpenComputers"); the
			// monitor is where the screen is, and the GUI has an "Open screen" button for the keyboard
			player.openMenu(be);
			final JsonObject p = new JsonObject();
			p.addProperty("player", player.getName().getString());
			p.addProperty("side", hitResult.getDirection().getSerializedName());
			p.addProperty("sneaking", false);
			be.emitEvent("player_used", p);
			return InteractionResult.CONSUME;
		}
		if (player.isShiftKeyDown()) {
			final ComputerManager manager = ComputerManager.get(serverLevel.getServer());
			if (be.powered()) {
				be.setPowered(false);
				manager.remove(be.machineId(), false);
				player.sendOverlayMessage(Component.literal("Computer powered off"));
			} else {
				final String refusal = manager.placementRefusal(be.owner());
				if (refusal != null) {
					player.sendOverlayMessage(Component.literal(refusal));
				} else {
					player.sendOverlayMessage(Component.literal(be.thaw(serverLevel) ? "Computer powered on" : "Refused by the computer cap"));
				}
			}
			return InteractionResult.CONSUME;
		}
		return InteractionResult.SUCCESS;
	}
}
