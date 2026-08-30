package dev.virtualminecraft.client.screen;

import dev.virtualminecraft.block.ComputerBlockEntity;
import dev.virtualminecraft.item.DiskItem;
import net.minecraft.world.item.ItemStack;
import dev.virtualminecraft.net.VmControlPayload;
import dev.virtualminecraft.vm.VmConfig;
import dev.virtualminecraft.vm.VmStatus;
import java.util.ArrayList;
import java.util.List;
import dev.virtualminecraft.client.ClientNet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/** Configure and power a computer block. */
public class ComputerConfigScreen extends Screen {
	private static final String[] VGA_OPTIONS = { "std", "virtio", "qxl", "cirrus" };
	private static final String[] NIC_OPTIONS = { "e1000", "virtio-net-pci", "rtl8139", "none" };

	private final BlockPos pos;
	private VmConfig cfg = new VmConfig();

	private EditBox nameBox;
	private EditBox memBox;
	private EditBox cpuBox;
	private EditBox diskBox;
	private EditBox isoBox;
	private EditBox extraBox;
	private Button uefiButton;
	private Button bootButton;
	private Button autostartButton;
	private Button vgaButton;
	private Button nicButton;
	private Button suspendButton;
	private Button sleepButton;
	private EditBox wakeBox;
	private int statusY;
	private int disksY;
	private final List<int[]> labels = new ArrayList<>();
	private final List<String> labelText = new ArrayList<>();

	public ComputerConfigScreen(final BlockPos pos) {
		super(Component.translatable("virtualminecraft.screen.computer"));
		this.pos = pos;
	}

	private @Nullable ComputerBlockEntity blockEntity() {
		final Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.getBlockEntity(pos) instanceof ComputerBlockEntity be ? be : null;
	}

	@Override
	protected void init() {
		final ComputerBlockEntity be = blockEntity();
		if (be != null) {
			cfg = be.getConfig().copy();
		}
		labels.clear();
		labelText.clear();

		final int left = this.width / 2 - 150;
		final int fieldX = left + 90;
		final int fieldW = 210;
		// 21 px rows: the whole screen must fit 240 GUI units (auto GUI scale at 720p).
		int y = 18;

		nameBox = field(fieldX, y, fieldW, "Name", cfg.name, 32);
		y += 21;
		memBox = field(fieldX, y, 60, "RAM (MB)", Integer.toString(cfg.memMb), 6);
		cpuBox = field(fieldX + 70 + 50, y, 40, "CPUs", Integer.toString(cfg.cpus), 2);
		label(fieldX + 70, y, "CPUs");
		diskBox = field(fieldX + 170 + 40, y, 40, "Disk (GB)", Integer.toString(cfg.diskGb), 3);
		label(fieldX + 170, y, "Disk GB");
		y += 21;
		isoBox = field(fieldX, y, fieldW, "ISO", cfg.iso, 1024);
		y += 21;
		extraBox = field(fieldX, y, fieldW, "Extra args", cfg.extraArgs, 2048);
		// QEMU arguments are host access, not a setting: server config or /vmc args only, never from a packet.
		extraBox.setEditable(false);
		extraBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
			Component.translatable("virtualminecraft.tooltip.extra_args")));
		y += 23;

		uefiButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			cfg.uefi = !cfg.uefi;
			refreshToggles();
		}).bounds(left, y, 95, 20).build());
		bootButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			cfg.bootFromCd = !cfg.bootFromCd;
			refreshToggles();
		}).bounds(left + 100, y, 95, 20).build());
		autostartButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			cfg.autostart = !cfg.autostart;
			refreshToggles();
		}).bounds(left + 200, y, 100, 20).build());
		y += 21;
		vgaButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			cfg.vga = cycle(VGA_OPTIONS, cfg.vga);
			refreshToggles();
		}).bounds(left, y, 95, 20).build());
		nicButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			cfg.nic = cycle(NIC_OPTIONS, cfg.nic);
			refreshToggles();
		}).bounds(left + 100, y, 95, 20).build());
		suspendButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			cfg.suspend = !cfg.suspend;
			refreshToggles();
		}).bounds(left + 200, y, 100, 20).build());
		y += 21;
		// Redstone: "Wake ≥ N" starts the computer when any face rises to N (0 = off); sleep = ACPI shutdown when all drop below.
		label(left, y, "Wake at ≥");
		wakeBox = new EditBox(this.font, left + 50, y, 30, 18, Component.literal("Wake threshold"));
		wakeBox.setMaxLength(2);
		wakeBox.setValue(Integer.toString(cfg.wakeThreshold));
		addRenderableWidget(wakeBox);
		label(left + 84, y, "(0 = off)");
		sleepButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
			cfg.redstoneSleep = !cfg.redstoneSleep;
			refreshToggles();
		}).bounds(left + 135, y, 165, 20).build());
		statusY = y + 22;
		disksY = statusY + 11;
		y += 44;

		addRenderableWidget(Button.builder(Component.translatable("virtualminecraft.button.save"), b -> send(VmControlPayload.Action.SAVE)).bounds(left, y, 70, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("virtualminecraft.button.start"), b -> send(VmControlPayload.Action.START)).bounds(left + 75, y, 70, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("virtualminecraft.button.shutdown"), b -> send(VmControlPayload.Action.SHUTDOWN)).bounds(left + 150, y, 70, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("virtualminecraft.button.reset"), b -> send(VmControlPayload.Action.RESET)).bounds(left + 225, y, 75, 20).build());
		y += 22;
		addRenderableWidget(Button.builder(Component.translatable("virtualminecraft.button.force_stop"), b -> send(VmControlPayload.Action.FORCE_STOP)).bounds(left, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("virtualminecraft.button.open_screen"), b -> {
			send(VmControlPayload.Action.SAVE);
			final ComputerBlockEntity be2 = blockEntity();
			if (be2 != null) {
				Minecraft.getInstance().setScreen(new VmScreen(be2.getVmId(), cfg.name, pos));
			}
		}).bounds(left + 105, y, 100, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> {
			send(VmControlPayload.Action.SAVE);
			this.onClose();
		}).bounds(left + 210, y, 90, 20).build());

		refreshToggles();
	}

	private EditBox field(final int x, final int y, final int w, final String labelKey, final String value, final int maxLength) {
		final EditBox box = new EditBox(this.font, x, y, w, 18, Component.literal(labelKey));
		box.setMaxLength(maxLength);
		box.setValue(value);
		addRenderableWidget(box);
		if (!labelKey.equals("CPUs") && !labelKey.equals("Disk (GB)")) {
			label(x - 90, y, labelKey);
		}
		return box;
	}

	private void label(final int x, final int y, final String text) {
		labels.add(new int[] { x, y + 5 });
		labelText.add(text);
	}

	private static String cycle(final String[] options, final String current) {
		for (int i = 0; i < options.length; i++) {
			if (options[i].equals(current)) {
				return options[(i + 1) % options.length];
			}
		}
		return options[0];
	}

	private void refreshToggles() {
		uefiButton.setMessage(Component.literal("UEFI: " + (cfg.uefi ? "ON" : "OFF")));
		bootButton.setMessage(Component.literal("Boot: " + (cfg.bootFromCd ? "removable" : "disk first")));
		autostartButton.setMessage(Component.literal("Autostart: " + (cfg.autostart ? "ON" : "OFF")));
		vgaButton.setMessage(Component.literal("Graphics: " + cfg.vga));
		nicButton.setMessage(Component.literal("Network: " + cfg.nic));
		suspendButton.setMessage(Component.literal("Suspend: " + (cfg.suspend ? "ON" : "OFF")));
		sleepButton.setMessage(Component.literal("Sleep when low: " + (cfg.redstoneSleep ? "ON" : "OFF")));
	}

	private VmConfig collect() {
		final VmConfig c = cfg.copy();
		c.name = nameBox.getValue();
		c.memMb = parseInt(memBox.getValue(), c.memMb);
		c.cpus = parseInt(cpuBox.getValue(), c.cpus);
		c.diskGb = parseInt(diskBox.getValue(), c.diskGb);
		c.iso = isoBox.getValue().strip();
		c.wakeThreshold = parseInt(wakeBox.getValue(), c.wakeThreshold);
		c.sanitize();
		return c;
	}

	private static int parseInt(final String s, final int fallback) {
		try {
			return Integer.parseInt(s.strip());
		} catch (final NumberFormatException e) {
			return fallback;
		}
	}

	private void send(final VmControlPayload.Action action) {
		cfg = collect();
		if (ClientNet.canSend(VmControlPayload.ID)) {
			ClientNet.send(new VmControlPayload(pos, action, cfg));
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void render(final GuiGraphics graphics, final int mouseX, final int mouseY, final float a) {
		renderBackground(graphics);
		super.render(graphics, mouseX, mouseY, a);
		final String title = this.title.getString();
		graphics.drawString(this.font, title, (this.width - this.font.width(title)) / 2, 4, 0xFFFFFFFF);
		for (int i = 0; i < labels.size(); i++) {
			final int[] p = labels.get(i);
			graphics.drawString(this.font, labelText.get(i), p[0], p[1], 0xFFA0A0A0);
		}
		final ComputerBlockEntity be = blockEntity();
		String status = "Status: unknown";
		int color = 0xFFAAAAAA;
		if (be != null) {
			final VmStatus s = be.getStatus();
			final String msg = be.getStatusMessage();
			status = "Status: " + s.name().charAt(0) + s.name().substring(1).toLowerCase() + (msg.isEmpty() ? "" : " — " + msg);
			color = switch (s) {
				case RUNNING -> 0xFF55FF55;
				case STARTING -> 0xFFFFFF55;
				case ERROR -> 0xFFFF5555;
				case STOPPED -> 0xFFAAAAAA;
				case SUSPENDED -> 0xFF55AAFF;
			};
		}
		final int left = this.width / 2 - 150;
		graphics.drawString(this.font, this.font.plainSubstrByWidth(status, 300), left, statusY, color);
		final StringBuilder disks = new StringBuilder("Disks: ");
		int n = 0;
		if (be != null) {
			for (final ItemStack s : be.getDisks()) {
				if (!s.isEmpty()) {
					disks.append(n > 0 ? ", " : "").append(DiskItem.describe(s));
					n++;
				}
			}
		}
		if (n == 0) {
			disks.append("none (right-click the computer holding a hard drive or CD)");
		}
		graphics.drawString(this.font, this.font.plainSubstrByWidth(disks.toString(), 300), left, disksY, 0xFFA0A0A0);
	}
}
