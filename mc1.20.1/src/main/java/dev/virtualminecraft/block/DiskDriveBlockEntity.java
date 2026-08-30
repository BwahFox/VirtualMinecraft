package dev.virtualminecraft.block;

import com.google.gson.JsonObject;
import dev.virtualminecraft.ModContent;
import dev.virtualminecraft.bus.BusNetwork;
import dev.virtualminecraft.bus.Component;
import dev.virtualminecraft.bus.DriveComponent;
import dev.virtualminecraft.item.DiskData;
import dev.virtualminecraft.item.DiskItem;
import dev.virtualminecraft.vm.Attachment;
import dev.virtualminecraft.vm.Attachments;
import dev.virtualminecraft.vm.VmInstance;
import dev.virtualminecraft.vm.VmManager;
import dev.virtualminecraft.vm.VmStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import dev.virtualminecraft.util.Nbt;
import org.jspecify.annotations.Nullable;

/**
 * A disk drive: holds one removable disk (floppy or CD) and attaches it to the adjacent computer. While that
 * computer runs, inserting/ejecting swaps the medium live (QMP); otherwise the medium is simply there at the
 * next start. Each drive block is one CD unit plus — for the first two drives — one floppy unit (see
 * {@link Attachments}). The computer it serves is the one it touches, or the nearest one along a bus cable.
 */
public class DiskDriveBlockEntity extends BlockEntity {
	private ItemStack media = ItemStack.EMPTY;

	public DiskDriveBlockEntity(final BlockPos pos, final BlockState state) {
		super(ModContent.DISK_DRIVE_BLOCK_ENTITY, pos, state);
	}

	public ItemStack getMedia() {
		return media;
	}

	public boolean hasMedia() {
		return !media.isEmpty();
	}

	/** The machine this drive belongs to (either tier): one it touches, else the nearest one along the bus cable. */
	public dev.virtualminecraft.bus.@Nullable BusHost findComputer() {
		return level instanceof ServerLevel serverLevel ? BusNetwork.computerFor(serverLevel, worldPosition) : null;
	}

	/** Bus location of this drive as seen by {@code computer}: a side name, or a {@code dx,dy,dz} offset on cable. */
	public String locationOn(final dev.virtualminecraft.bus.BusHost computer) {
		return BusNetwork.locationOf(computer.getBlockPos(), worldPosition);
	}

	/** Server: puts {@code one} (a single removable disk) into the drive. Reports to the player and returns success. */
	public boolean insert(final ServerLevel level, final ItemStack one, final @Nullable Player player) {
		final DiskItem.Kind kind = DiskItem.kindOf(one);
		if (kind == null || !kind.removable) {
			message(player, "virtualminecraft.msg.not_removable");
			return false;
		}
		if (!media.isEmpty()) {
			message(player, "virtualminecraft.msg.drive_full");
			return false;
		}
		DiskItem.ensureData(one);
		media = one;
		sync();
		hotSwap(level, true, kind, player);
		event("disk_inserted", one);
		message(player, "virtualminecraft.msg.disk_inserted");
		return true;
	}

	/** Server: takes the disk out (empty stack if there was none); the guest sees the medium go away at once. */
	public ItemStack eject(final ServerLevel level, final @Nullable Player player) {
		if (media.isEmpty()) {
			message(player, "virtualminecraft.msg.drive_empty");
			return ItemStack.EMPTY;
		}
		final ItemStack out = media;
		media = ItemStack.EMPTY;
		sync();
		hotSwap(level, false, DiskItem.kindOf(out), player);
		event("disk_ejected", out);
		message(player, "virtualminecraft.msg.disk_ejected");
		return out;
	}

	/** Live medium change on the computer's running VM; no-op when it is not running (the next start picks the medium up). */
	private void hotSwap(final ServerLevel level, final boolean attach, final DiskItem.@Nullable Kind kind, final @Nullable Player player) {
		if (!(findComputer() instanceof ComputerBlockEntity c) || kind == null) {
			return; // a Computer (the Lua tier) sees the medium through its mounts, nothing to hot-swap
		}
		final String location = locationOn(c);
		final VmManager manager = VmManager.get(level.getServer());
		final VmInstance vm = manager.get(c.getVmId());
		if (vm == null || !vm.isAlive()) {
			if (c.getStatus() == VmStatus.SUSPENDED) {
				message(player, "virtualminecraft.msg.suspended_medium");
			}
			return;
		}
		final String deviceId = "dev-" + (kind == DiskItem.Kind.FLOPPY ? Attachments.floppyId(location) : Attachments.cdId(location));
		if (!vm.hasDevice(deviceId)) {
			message(player, "virtualminecraft.msg.restart_for_drive");
			return;
		}
		final Attachment medium = attach ? Attachments.mediumFor(manager, media, location) : null;
		if (attach && medium == null) {
			return; // blank CD: nothing to mount
		}
		vm.changeMedium(deviceId, medium);
	}

	private void event(final String name, final ItemStack stack) {
		final dev.virtualminecraft.bus.BusHost c = findComputer();
		if (c == null) {
			return;
		}
		final String location = locationOn(c);
		final DiskItem.Kind kind = DiskItem.kindOf(stack);
		final DiskData d = DiskItem.data(stack);
		final JsonObject p = new JsonObject();
		p.addProperty("address", Component.addressOf(c.busId(), DriveComponent.TYPE, location).toString());
		p.addProperty("location", location);
		p.addProperty("side", location); // same string; a side name when the drive touches the computer
		p.addProperty("kind", kind == null ? "" : kind.id);
		p.addProperty("description", DiskItem.describe(stack));
		if (d != null && kind != DiskItem.Kind.CD) {
			p.addProperty("serial", d.serial());
		}
		if (level instanceof ServerLevel serverLevel) {
			c.mediaChanged(serverLevel); // the mount table before the event, so a listener that looks sees the disk
		}
		c.emitEvent(name, p);
	}

	private static void message(final @Nullable Player player, final String key) {
		if (player != null) {
			player.displayClientMessage(net.minecraft.network.chat.Component.translatable(key), true);
		}
	}

	private void sync() {
		setChanged();
		if (level != null && !level.isClientSide()) {
			level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
		}
	}

	/** 1.20.1: called by the block's {@code onRemove} on a real removal -- 26.2's {@code preRemoveSideEffects}. */
	public void onBlockRemoved(final BlockPos pos, final BlockState state) {
		if (level instanceof ServerLevel serverLevel && !media.isEmpty()) {
			final ItemStack out = eject(serverLevel, null);
			Containers.dropItemStack(serverLevel, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, out);
		}
	}

	@Override
	protected void saveAdditional(final CompoundTag output) {
		super.saveAdditional(output);
		output.put("media", media.save(new CompoundTag()));
	}

	@Override
	public void load(final CompoundTag input) {
		super.load(input);
		media = Nbt.child(input, "media").map(ItemStack::of).orElse(ItemStack.EMPTY);
	}

	@Override
	public CompoundTag getUpdateTag() {
		return saveWithoutMetadata();
	}

	@Override
	public @Nullable ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
