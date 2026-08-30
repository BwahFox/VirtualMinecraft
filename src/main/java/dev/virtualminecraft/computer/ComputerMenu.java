package dev.virtualminecraft.computer;

import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.item.PartItem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The case's GUI (ROADMAP §9 U3b, [name]: "clicking on the computer opens a GUI similar to OpenComputers"): one
 * slot per part kind — RAM, CPU, graphics, drive — over the player's inventory, and two buttons the client sends as
 * menu-button clicks (power, and what the machine boots into). Everything the screen shows about the machine
 * (tier, the spec the parts add up to, status, name) it reads from the client's copy of the block entity, which
 * the case syncs whenever a part changes; this class only moves items and relays the buttons.
 */
public class ComputerMenu extends AbstractContainerMenu {
	public static final int PART_SLOTS = MachineSpec.Part.ALL.length;
	public static final int BUTTON_POWER = 0;
	public static final int BUTTON_DESKTOP = 1;
	/** Slot geometry the screen draws around: the four part slots in a row, the inventory below. */
	public static final int PART_X = 44;
	public static final int PART_Y = 16;
	public static final int PART_GAP = 26;
	public static final int INVENTORY_Y = 84;

	public final BlockPos pos;
	private final @Nullable LuaComputerBlockEntity be;

	/** The client's copy: an empty stand-in container that the server's slot sync fills. */
	public ComputerMenu(final int id, final Inventory inventory, final BlockPos pos) {
		this(id, inventory, pos, new SimpleContainer(PART_SLOTS), null);
	}

	/** The server's: the case's own part slots. */
	public ComputerMenu(final int id, final Inventory inventory, final LuaComputerBlockEntity be) {
		this(id, inventory, be.getBlockPos(), be.parts(), be);
	}

	private ComputerMenu(final int id, final Inventory inventory, final BlockPos pos, final Container parts, final @Nullable LuaComputerBlockEntity be) {
		super(ModContent.COMPUTER_MENU, id);
		this.pos = pos;
		this.be = be;
		for (int i = 0; i < PART_SLOTS; i++) {
			final MachineSpec.Part kind = MachineSpec.Part.ALL[i];
			addSlot(new Slot(parts, i, PART_X + i * PART_GAP, PART_Y) {
				@Override
				public boolean mayPlace(final ItemStack stack) {
					return PartItem.isPart(stack, kind);
				}

				@Override
				public int getMaxStackSize() {
					return 1;
				}
			});
		}
		addStandardInventorySlots(inventory, 8, INVENTORY_Y);
	}

	public @Nullable LuaComputerBlockEntity blockEntity() {
		return be;
	}

	@Override
	public boolean stillValid(final Player player) {
		if (be == null) {
			return true;
		}
		return be.getLevel() != null && be.getLevel().getBlockEntity(pos) == be && player.distanceToSqr(Vec3.atCenterOf(pos)) <= 64.0;
	}

	@Override
	public ItemStack quickMoveStack(final Player player, final int index) {
		final Slot slot = slots.get(index);
		if (!slot.hasItem()) {
			return ItemStack.EMPTY;
		}
		final ItemStack stack = slot.getItem();
		final ItemStack before = stack.copy();
		final int invStart = PART_SLOTS;
		final int invEnd = slots.size();
		if (index < PART_SLOTS) {
			if (!moveItemStackTo(stack, invStart, invEnd, true)) {
				return ItemStack.EMPTY;
			}
		} else {
			boolean placed = false;
			for (int i = 0; i < PART_SLOTS; i++) {
				final Slot part = slots.get(i);
				if (part.mayPlace(stack) && !part.hasItem()) {
					placed = moveItemStackTo(stack, i, i + 1, false);
					break;
				}
			}
			if (!placed) {
				// hotbar <-> main inventory, the vanilla convention
				final int hotbarStart = invEnd - 9;
				if (index < hotbarStart ? !moveItemStackTo(stack, hotbarStart, invEnd, false) : !moveItemStackTo(stack, invStart, hotbarStart, false)) {
					return ItemStack.EMPTY;
				}
			}
		}
		if (stack.isEmpty()) {
			slot.setByPlayer(ItemStack.EMPTY);
		} else {
			slot.setChanged();
		}
		if (stack.getCount() == before.getCount()) {
			return ItemStack.EMPTY;
		}
		slot.onTake(player, stack);
		return before;
	}

	@Override
	public boolean clickMenuButton(final Player player, final int id) {
		if (be == null || !(player instanceof ServerPlayer sp)) {
			return false;
		}
		switch (id) {
			case BUTTON_POWER -> be.togglePower(sp);
			case BUTTON_DESKTOP -> be.cycleDesktopMode();
			default -> {
				return false;
			}
		}
		return true;
	}
}
