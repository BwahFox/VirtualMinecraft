package dev.virtualminecraft;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.virtualminecraft.block.DiskDriveBlockEntity;
import dev.virtualminecraft.item.DiskItem;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import com.mojang.brigadier.context.CommandContext;
import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.block.MonitorBlockEntity;
import dev.virtualminecraft.computer.ComputerManager.Kind;
import dev.virtualminecraft.vm.VmInstance;
import dev.virtualminecraft.vm.VmManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** {@code /vmc} — operator command to manage virtual machines without the GUI. */
public final class VmcCommand {
	private VmcCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	private static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("vmc")
			.requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
			.then(Commands.literal("start").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> withComputer(ctx, (m, be) -> m.start(be, ctx.getSource().getPlayer())))))
			.then(Commands.literal("shutdown").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> withComputer(ctx, (m, be) -> m.shutdown(be, ctx.getSource().getPlayer())))))
			.then(Commands.literal("stop").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> withComputer(ctx, (m, be) -> m.forceStop(be, ctx.getSource().getPlayer())))))
			.then(Commands.literal("suspend").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> withComputer(ctx, (m, be) -> m.suspend(be, ctx.getSource().getPlayer())))))
			.then(Commands.literal("reset").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> withComputer(ctx, (m, be) -> m.reset(be, ctx.getSource().getPlayer())))))
			// Per-VM QEMU arguments are operators only, and reachable only from here: they are host access, not a setting.
			.then(Commands.literal("args").then(Commands.argument("pos", BlockPosArgument.blockPos()).then(Commands.argument("args", StringArgumentType.greedyString()).executes(ctx -> withComputer(ctx, (m, be) -> {
				final var cfg = be.getConfig().copy();
				cfg.extraArgs = StringArgumentType.getString(ctx, "args").strip();
				if (cfg.extraArgs.equals("-")) {
					cfg.extraArgs = "";
				}
				be.setConfig(cfg);
				ctx.getSource().sendSuccess(() -> Component.literal("Extra QEMU args set to '" + cfg.extraArgs + "' (applies at the next start)"), true);
			})))))
			.then(Commands.literal("iso").then(Commands.argument("pos", BlockPosArgument.blockPos()).then(Commands.argument("iso", StringArgumentType.greedyString()).executes(ctx -> withComputer(ctx, (m, be) -> {
				final var cfg = be.getConfig().copy();
				cfg.iso = StringArgumentType.getString(ctx, "iso").strip();
				if (cfg.iso.equals("-")) {
					cfg.iso = "";
				}
				be.setConfig(cfg);
				ctx.getSource().sendSuccess(() -> Component.literal("ISO set to '" + cfg.iso + "'"), false);
			})))))
			.then(Commands.literal("link").then(Commands.argument("monitor", BlockPosArgument.blockPos()).then(Commands.argument("computer", BlockPosArgument.blockPos()).executes(ctx -> {
				final ServerLevel level = ctx.getSource().getLevel();
				final BlockPos mp = BlockPosArgument.getLoadedBlockPos(ctx, "monitor");
				final BlockPos cp = BlockPosArgument.getLoadedBlockPos(ctx, "computer");
				if (!(level.getBlockEntity(mp) instanceof MonitorBlockEntity monitor)) {
					ctx.getSource().sendFailure(Component.literal("No monitor at " + mp.toShortString()));
					return 0;
				}
				if (!(level.getBlockEntity(cp) instanceof dev.virtualminecraft.screen.ScreenSource)) {
					ctx.getSource().sendFailure(Component.literal("No computer at " + cp.toShortString()));
					return 0;
				}
				monitor.setSourcePos(cp);
				ctx.getSource().sendSuccess(() -> Component.literal("Linked monitor " + mp.toShortString() + " to computer " + cp.toShortString()), false);
				return 1;
			}))))
			.then(Commands.literal("status").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> withComputer(ctx, (m, be) -> {
				final VmInstance vm = m.get(be.getVmId());
				final String s = "VM " + be.getConfig().name + " [" + be.getVmId() + "]: " + be.getStatus() + (be.getStatusMessage().isEmpty() ? "" : " — " + be.getStatusMessage())
					+ (vm == null ? "" : " (process " + (vm.isAlive() ? "alive" : "dead") + (vm.isSuspending() ? ", suspending" : "") + ")")
					+ (m.hasSnapshot(be.getVmId()) ? " [snapshot on disk]" : "");
				ctx.getSource().sendSuccess(() -> Component.literal(s), false);
				if (vm != null) {
					for (final dev.virtualminecraft.vm.Attachment a : dev.virtualminecraft.vm.Attachments.bootOrder(vm.attachments())) {
						ctx.getSource().sendSuccess(() -> Component.literal("  boot " + a.bootIndex() + ": " + a.id() + " " + a.label() + (a.file() == null ? " (empty)" : " -> " + a.file())), false);
					}
				}
			}))))
			.then(Commands.literal("bus")
				// No position: what the registry knows, and how many run walks it has done (§9 U11 — the number
				// that is meant to stay put while programs hammer net.list()).
				.executes(ctx -> {
					ctx.getSource().sendSuccess(() -> Component.literal("Bus registry: "
						+ dev.virtualminecraft.bus.BusRegistry.describe()), false);
					return 1;
				})
				.then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> withComputer(ctx, (m, be) -> {
				final VmInstance vm = m.get(be.getVmId());
				final StringBuilder sb = new StringBuilder("Bus: ");
				sb.append(vm == null || vm.bus() == null ? "no VM" : vm.bus().describe());
				sb.append("; components:");
				for (final dev.virtualminecraft.bus.Component c : dev.virtualminecraft.bus.Components.collect(ctx.getSource().getLevel(), be)) {
					sb.append(' ').append(c.type()).append('@').append(c.location());
				}
				sb.append("; outputs:");
				for (final net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
					sb.append(' ').append(d.getSerializedName()).append('=').append(be.getOutput(d));
				}
				sb.append("; inputs:");
				for (final net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
					sb.append(' ').append(d.getSerializedName()).append('=').append(be.getInput(ctx.getSource().getLevel(), d));
				}
				ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
			}))))
			.then(Commands.literal("give")
				.then(Commands.literal("hdd").executes(ctx -> give(ctx, DiskItem.Kind.HARD_DRIVE, 32, ""))
					.then(Commands.argument("gb", IntegerArgumentType.integer(1, DiskItem.MAX_HDD_GB)).executes(ctx -> give(ctx, DiskItem.Kind.HARD_DRIVE, IntegerArgumentType.getInteger(ctx, "gb"), ""))))
				.then(Commands.literal("cd").then(Commands.argument("iso", StringArgumentType.greedyString()).executes(ctx -> give(ctx, DiskItem.Kind.CD, 0, StringArgumentType.getString(ctx, "iso")))))
				.then(Commands.literal("floppy").executes(ctx -> give(ctx, DiskItem.Kind.FLOPPY, 0, ""))
					.then(Commands.argument("template", StringArgumentType.word()).executes(ctx -> give(ctx, DiskItem.Kind.FLOPPY, 0, StringArgumentType.getString(ctx, "template"))))))
			.then(Commands.literal("eject").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> {
				final ServerLevel level = ctx.getSource().getLevel();
				final BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
				final BlockEntity be = level.getBlockEntity(pos);
				final ItemStack out;
				if (be instanceof ComputerBlockEntity computer) {
					out = computer.disksChangeable(level, ctx.getSource().getPlayer()) ? computer.ejectLastDisk() : ItemStack.EMPTY;
				} else if (be instanceof DiskDriveBlockEntity drive) {
					out = drive.eject(level, ctx.getSource().getPlayer());
				} else {
					ctx.getSource().sendFailure(Component.literal("No computer or disk drive at " + pos.toShortString()));
					return 0;
				}
				if (out.isEmpty()) {
					ctx.getSource().sendFailure(Component.literal("Nothing to eject"));
					return 0;
				}
				Block.popResource(level, pos, out);
				ctx.getSource().sendSuccess(() -> Component.literal("Ejected " + DiskItem.describe(out)), false);
				return 1;
			})))
			.then(dev.virtualminecraft.computer.ComputerCommand.build())
			// The disk housekeeping (§7h S1 leftover): what is under computers/ and items/, and what may go.
			.then(Commands.literal("gc")
				.executes(VmcCommand::gcReport)
				.then(Commands.literal("empty").executes(ctx -> {
					final int n = dev.virtualminecraft.computer.ComputerManager.get(ctx.getSource().getServer()).gcEmpty();
					ctx.getSource().sendSuccess(() -> Component.literal("Removed " + n + " empty computer director" + (n == 1 ? "y" : "ies")), true);
					return n;
				}))
				.then(Commands.literal("drop").then(Commands.argument("id", StringArgumentType.word()).executes(ctx -> {
					final String r = dev.virtualminecraft.computer.ComputerManager.get(ctx.getSource().getServer())
						.gcDrop(StringArgumentType.getString(ctx, "id"));
					ctx.getSource().sendSuccess(() -> Component.literal(r), true);
					return 1;
				}))))
			.then(Commands.literal("list").executes(ctx -> {
				final VmManager m = VmManager.get(ctx.getSource().getServer());
				ctx.getSource().sendSuccess(() -> Component.literal("Running VMs: " + m.runningCount()), false);
				return 1;
			})));
	}

	/**
	 * {@code /vmc gc}: everything under {@code computers/}, {@code items/} and the per-VM directories beside them,
	 * with its size, age and whether anything loaded claims it. Nothing is deleted here — a machine in an unloaded
	 * chunk is indistinguishable from an orphan, so the operator names what goes ({@code /vmc gc drop <uuid>}) or
	 * clears only the provably empty directories ({@code /vmc gc empty}).
	 * <p>
	 * The VM directories were missing from this report until session 19, which is how two of them survived a
	 * {@code /fill} as 2.4 GB nothing here could see: they are the largest thing the mod writes, so they are listed
	 * first and their total is called out on its own.
	 */
	private static int gcReport(final CommandContext<CommandSourceStack> ctx) {
		final var manager = dev.virtualminecraft.computer.ComputerManager.get(ctx.getSource().getServer());
		final var found = manager.gcScan();
		final StringBuilder sb = new StringBuilder();
		long machines = 0;
		long items = 0;
		long vms = 0;
		long empty = 0;
		long bytes = 0;
		long vmBytes = 0;
		// Biggest kind first: a VM directory is gigabytes where the other two are kilobytes, so it is what an
		// operator running gc because the disk is full has come to see.
		final var order = java.util.List.of(Kind.VM, Kind.MACHINE, Kind.ITEM);
		for (final var g : found.stream().sorted(java.util.Comparator.comparingInt(x -> order.indexOf(x.kind()))).toList()) {
			bytes += g.bytes();
			switch (g.kind()) {
				case ITEM -> items++;
				case VM -> {
					vms++;
					vmBytes += g.bytes();
				}
				case MACHINE -> {
					machines++;
					if (g.bytes() == 0) {
						empty++;
					}
				}
			}
			sb.append("\n  ").append(switch (g.kind()) {
				case MACHINE -> "machine ";
				case ITEM -> "item    ";
				case VM -> "VM      ";
			}).append(g.id(), 0, Math.min(8, g.id().length()));
			sb.append("  ").append(size(g.bytes()));
			sb.append("  ").append(g.ageDays()).append(" d  ").append(g.where());
		}
		final String head = machines + " computer director" + (machines == 1 ? "y" : "ies") + ", " + items
			+ " disk item file" + (items == 1 ? "" : "s") + " and " + vms + " VM director" + (vms == 1 ? "y" : "ies")
			+ ", " + (bytes == 0 ? "nothing" : size(bytes)) + " in all"
			+ (vmBytes > 0 ? " (" + size(vmBytes) + " of it VMs)" : "")
			+ (empty > 0 ? "; " + empty + " empty (/vmc gc empty)" : "");
		final String tail = "\n  \"not loaded\" only means no block with that id has ticked since this server started —"
			+ " a computer in an unloaded chunk looks the same. /vmc gc drop <uuid> deletes one — a machine, a disk"
			+ " item or a VM — and nothing else does.";
		ctx.getSource().sendSuccess(() -> Component.literal(head + sb + tail), false);
		return found.size();
	}

	private static String size(final long bytes) {
		return bytes == 0 ? "empty" : bytes < 1024 ? bytes + " B" : bytes < 1048576 ? bytes / 1024 + " KB" : bytes / 1048576 + " MB";
	}

	/** {@code /vmc give hdd [gb] | cd <iso> | floppy}: a disk item straight into the player's inventory. */
	private static int give(final CommandContext<CommandSourceStack> ctx, final DiskItem.Kind kind, final int sizeGb, String iso) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		final ServerPlayer player = ctx.getSource().getPlayerOrException();
		final java.nio.file.Path cdDir = dev.virtualminecraft.computer.MachineFiles.cdsDir(dev.virtualminecraft.config.VmcConfig.configDir().resolve("virtualminecraft")).resolve(iso.strip());
		// A Computer CD if the name matches a directory in the world's config dir OR one of the CDs that ship
		// inside the mod. Without the second half, `/vmc give cd mines` fails in a fresh world for no good reason.
		final boolean computerCd = kind == DiskItem.Kind.CD && !iso.isBlank() && !iso.contains("/") && !iso.contains("\\")
			&& (java.nio.file.Files.isDirectory(cdDir)
				|| dev.virtualminecraft.computer.MachineFiles.bundledCd(iso.strip()) != null);
		if (kind == DiskItem.Kind.FLOPPY && !iso.isBlank()) {
			// `/vmc give floppy starter`: a floppy with a template on it, seeded onto the disk when it is first mounted
			if (dev.virtualminecraft.computer.MachineFiles.floppyTemplate(iso.strip(), dev.virtualminecraft.config.VmcConfig.configDir().resolve("virtualminecraft")) == null) {
				ctx.getSource().sendFailure(Component.literal("No floppy template '" + iso.strip() + "' (config/virtualminecraft/floppies/<name> or one that ships in the mod)"));
				return 0;
			}
			iso = dev.virtualminecraft.computer.MachineFiles.FLOPPY_DIR_PREFIX + iso.strip();
		}
		final ItemStack stack = DiskItem.create(kind, sizeGb, computerCd ? dev.virtualminecraft.computer.MachineFiles.CD_DIR_PREFIX + iso.strip() : iso);
		if (kind == DiskItem.Kind.CD && !computerCd && dev.virtualminecraft.vm.QemuLauncher.resolveIso(iso, dev.virtualminecraft.config.VmcConfig.get().isoDir()) == null) {
			ctx.getSource().sendFailure(Component.literal("ISO not found: '" + iso + "' (name in " + dev.virtualminecraft.config.VmcConfig.get().isoDir() + " or an absolute path)"));
			return 0;
		}
		final String what = DiskItem.describe(stack);
		player.getInventory().placeItemBackInInventory(stack); // empties the stack as it moves it
		ctx.getSource().sendSuccess(() -> Component.literal("Gave " + what), false);
		return 1;
	}

	private interface ComputerAction {
		void run(VmManager manager, ComputerBlockEntity computer);
	}

	private static int withComputer(final CommandContext<CommandSourceStack> ctx, final ComputerAction action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		final BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		final BlockEntity be = ctx.getSource().getLevel().getBlockEntity(pos);
		if (!(be instanceof ComputerBlockEntity computer)) {
			ctx.getSource().sendFailure(Component.literal("No computer at " + pos.toShortString()));
			return 0;
		}
		action.run(VmManager.get(ctx.getSource().getServer()), computer);
		return 1;
	}
}
