package dev.virtualminecraft.computer;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * {@code /vmc computer …} — the S1 harness (ROADMAP §7h §8, §11): {@code state}, {@code lua}, {@code event},
 * {@code freeze}, {@code thaw}, {@code reboot}, {@code shutdown}, {@code list}. Operators only, like the rest of
 * {@code /vmc}.
 */
public final class ComputerCommand {
	private ComputerCommand() {
	}

	@FunctionalInterface
	private interface Action {
		void run(ComputerManager manager, LuaComputerBlockEntity be, ServerLevel level) throws com.mojang.brigadier.exceptions.CommandSyntaxException;
	}

	public static LiteralArgumentBuilder<CommandSourceStack> build() {
		return Commands.literal("computer")
			.then(Commands.literal("state").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> with(ctx, (m, be, level) -> {
				final LuaComputer c = m.get(be.machineId());
				final StringBuilder sb = new StringBuilder();
				sb.append("Computer '").append(be.busName()).append("' ").append(be.machineId()).append(": ").append(be.status());
				sb.append(", powered ").append(be.powered()).append(", owner ").append(be.ownerName());
				// "screen 0x0" is what a computer with nowhere to draw reported, and it reads as a broken machine
				// rather than a missing link (HANDOFF (p)); say the actual thing.
				if (be.linkedMonitors(level).isEmpty()) {
					sb.append("\n  NO MONITOR is linked to this computer \u2014 place one beside it, or /vmc link <monitor> <computer>");
				}
				sb.append("\n  ").append(be.spec().describe()).append(be.bootsToDesktop() ? ", boots to the desktop" : ", boots to the shell");
				if (c != null && c.machine() != null) {
					sb.append("\n  scheduler: ").append(m.scheduler().describe(c.machine())).append(", frames ").append(c.screen().frames());
					sb.append(c.screen().parked() ? ", screen parked (" + c.screen().parkedBytes() / 1024 + " KB deflated)"
						: ", screen " + c.screen().width() + "x" + c.screen().height()
							+ " (" + c.screen().drawSeq() + " draws, still " + c.screenIdleTicks() + " ticks)");
					final List<String> console = c.console();
					final int from = Math.max(0, console.size() - 8);
					for (int i = from; i < console.size(); i++) {
						sb.append("\n  | ").append(console.get(i));
					}
				}
				ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
			}))))
			.then(Commands.literal("lua").then(Commands.argument("pos", BlockPosArgument.blockPos()).then(Commands.argument("code", StringArgumentType.greedyString()).executes(ctx -> with(ctx, (m, be, level) -> {
				final LuaComputer c = m.get(be.machineId());
				if (c == null) {
					ctx.getSource().sendFailure(Component.literal("Computer is not running"));
					return;
				}
				final String code = StringArgumentType.getString(ctx, "code");
				final CommandSourceStack src = ctx.getSource();
				c.eval(code).whenComplete((r, t) -> m.post(() -> src.sendSuccess(() -> Component.literal(t != null ? "ERROR: " + t : r), false)));
			})))))
			.then(Commands.literal("event").then(Commands.argument("pos", BlockPosArgument.blockPos()).then(Commands.argument("name", StringArgumentType.word())
				.executes(ctx -> with(ctx, (m, be, level) -> sendEvent(ctx, be, StringArgumentType.getString(ctx, "name"), "{}")))
				.then(Commands.argument("json", StringArgumentType.greedyString()).executes(ctx -> with(ctx, (m, be, level) -> sendEvent(ctx, be, StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "json"))))))))
			.then(Commands.literal("freeze").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> with(ctx, (m, be, level) -> {
				m.remove(be.machineId(), true);
				ctx.getSource().sendSuccess(() -> Component.literal("Frozen: " + be.status()), false);
			}))))
			.then(Commands.literal("thaw").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> with(ctx, (m, be, level) -> {
				if (!be.thaw(level)) {
					// §9 U10(a): say which of the two it is -- an empty case and a full server look the same otherwise
					final String dead = be.bootRefusal();
					ctx.getSource().sendFailure(Component.literal(dead != null ? "This case has " + dead : "Refused by the computer cap"));
					return;
				}
				ctx.getSource().sendSuccess(() -> Component.literal("Thawed: " + be.status()), false);
			}))))
			.then(Commands.literal("reboot").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> with(ctx, (m, be, level) -> {
				m.remove(be.machineId(), false);
				if (!be.thaw(level)) {
					// §9 U10(a): say which of the two it is -- an empty case and a full server look the same otherwise
					final String dead = be.bootRefusal();
					ctx.getSource().sendFailure(Component.literal(dead != null ? "This case has " + dead : "Refused by the computer cap"));
					return;
				}
				ctx.getSource().sendSuccess(() -> Component.literal("Rebooted"), false);
			}))))
			.then(Commands.literal("shutdown").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> with(ctx, (m, be, level) -> {
				be.setPowered(false);
				m.remove(be.machineId(), false);
				ctx.getSource().sendSuccess(() -> Component.literal("Shut down"), false);
			}))))
			.then(Commands.literal("files").then(Commands.argument("pos", BlockPosArgument.blockPos()).executes(ctx -> with(ctx, (m, be, level) -> {
				final LuaComputer c = m.get(be.machineId());
				if (c == null) {
					ctx.getSource().sendFailure(Component.literal("Computer is not running"));
					return;
				}
				final StringBuilder sb = new StringBuilder("Mounts (booted from " + c.bootedFrom() + "):");
				for (final MachineFiles.Mount mt : c.files().mounts().values()) {
					sb.append("\n  /").append(mt.name()).append("  ").append(mt.label()).append(mt.readOnly() ? "  read-only" : "").append(mt.foreign() ? "  FOREIGN" : "")
						.append(mt.quota() > 0 ? "  quota " + mt.quota() / 1024 + " KB" : "").append(mt.root() == null ? "" : "  " + mt.root());
				}
				ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
			}))))
			.then(Commands.literal("list").executes(ctx -> {
				final ComputerManager m = ComputerManager.get(ctx.getSource().getServer());
				final StringBuilder sb = new StringBuilder("Computers live: " + m.liveCount());
				for (final LuaComputer c : m.all()) {
					sb.append("\n  ").append(c.blockEntity().getBlockPos().toShortString()).append(" '").append(c.name()).append("' ").append(c.status());
					if (c.machine() != null) {
						sb.append(" — ").append(m.scheduler().describe(c.machine()));
					}
				}
				ctx.getSource().sendSuccess(() -> Component.literal(sb.toString()), false);
				return 1;
			}));
	}

	private static void sendEvent(final CommandContext<CommandSourceStack> ctx, final LuaComputerBlockEntity be, final String name, final String json) {
		JsonObject p;
		try {
			p = JsonParser.parseString(json).getAsJsonObject();
		} catch (final RuntimeException e) {
			ctx.getSource().sendFailure(Component.literal("Not a JSON object: " + json));
			return;
		}
		be.emitEvent(name, p);
		ctx.getSource().sendSuccess(() -> Component.literal("Sent " + name), false);
	}

	private static int with(final CommandContext<CommandSourceStack> ctx, final Action action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		final BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
		final ServerLevel level = ctx.getSource().getLevel();
		final BlockEntity be = level.getBlockEntity(pos);
		if (!(be instanceof LuaComputerBlockEntity computer)) {
			ctx.getSource().sendFailure(Component.literal("No Computer at " + pos.toShortString()));
			return 0;
		}
		action.run(ComputerManager.get(ctx.getSource().getServer()), computer, level);
		return 1;
	}
}
