package dev.virtualminecraft.client.screen;

import dev.virtualminecraft.VirtualMinecraft;
import dev.virtualminecraft.computer.ComputerMenu;
import dev.virtualminecraft.computer.LuaComputerBlockEntity;
import dev.virtualminecraft.computer.MachineSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jspecify.annotations.Nullable;

/**
 * The case, opened (ROADMAP §9 U3b): four part slots with what they add up to written underneath, power, what the
 * machine boots into, and a button to the screen. The numbers come from the client's block entity (synced when a
 * part changes); the slots are the menu's.
 */
public class ComputerScreen extends AbstractContainerScreen<ComputerMenu> {
	private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");
	private static final String[] CAPTIONS = { "RAM", "CPU", "GFX", "HDD" };
	/** What each part slot takes, for the empty-slot tooltip: the item name without its level. */
	private static final String[] SLOT_NAMES = { "Memory", "Processor", "Graphics Card", "Hard Drive" };
	private static final int PANEL = 0xFFC6C6C6;
	private static final int PANEL_DARK = 0xFF555555;
	private static final int PANEL_LIGHT = 0xFFFFFFFF;
	private static final int TEXT = 0xFF404040;
	/** The status colour when there is no screen: a warning, not the ordinary blue. */
	private static final int NO_MONITOR = 0xFFA03000;

	private Button powerButton;
	private Button desktopButton;

	public ComputerScreen(final ComputerMenu menu, final Inventory inventory, final Component title) {
		super(menu, inventory, title);
		this.inventoryLabelY = 10_000; // no "Inventory" caption: the buttons sit where it would go
	}

	private @Nullable LuaComputerBlockEntity blockEntity() {
		final Minecraft mc = Minecraft.getInstance();
		return mc.level != null && mc.level.getBlockEntity(menu.pos) instanceof LuaComputerBlockEntity be ? be : null;
	}

	@Override
	protected void init() {
		super.init();
		// 162 px between the panel's margins for three buttons and two gaps: the boot button's old label
		// ("Auto: desktop") did not fit its 56 and scrolled back and forth. The mode is the button now and what
		// it resolves to is the tooltip, which is short enough that all three sit still.
		final int y = topPos + 63;
		powerButton = addRenderableWidget(Button.builder(Component.empty(), b -> click(ComputerMenu.BUTTON_POWER)).bounds(leftPos + 7, y, 56, 18).build());
		desktopButton = addRenderableWidget(Button.builder(Component.empty(), b -> click(ComputerMenu.BUTTON_DESKTOP)).bounds(leftPos + 66, y, 48, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Screen"), b -> {
			onClose();
			VirtualMinecraft.clientHooks.openMonitorScreen(menu.pos);
		}).bounds(leftPos + 117, y, 52, 18).build());
		refresh();
	}

	private void click(final int id) {
		final Minecraft mc = Minecraft.getInstance();
		if (mc.gameMode != null) {
			mc.gameMode.handleInventoryButtonClick(menu.containerId, id);
		}
	}

	private void refresh() {
		final LuaComputerBlockEntity be = blockEntity();
		final boolean on = be != null && be.powered();
		powerButton.setMessage(Component.translatable(on ? "virtualminecraft.button.power_off" : "virtualminecraft.button.power_on"));
		final int mode = be == null ? 0 : be.desktopMode();
		final boolean desktop = be != null && be.bootsToDesktop();
		desktopButton.setMessage(Component.literal(mode == 0 ? "Auto" : desktop ? "Desktop" : "Shell"));
		desktopButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(
			mode == 0 ? "Boot: the case decides \u2014 this one starts in the " + (desktop ? "desktop" : "shell")
				: "Boot: always the " + (desktop ? "desktop" : "shell"))));
	}

	/**
	 * The four part slots say what the case can take, so an empty one is not a dead square and a part that is
	 * clamped explains itself: "Memory III \u2014 up to 2 MB in this case" on a Basic Computer holding one.
	 */
	@Override
	protected void extractTooltip(final GuiGraphicsExtractor g, final int mouseX, final int mouseY) {
		final int slot = hoveredSlot == null ? -1 : menu.slots.indexOf(hoveredSlot);
		if (slot < 0 || slot >= MachineSpec.Part.ALL.length) {
			super.extractTooltip(g, mouseX, mouseY);
			return;
		}
		if (!menu.getCarried().isEmpty()) {
			return;
		}
		final MachineSpec.Part part = MachineSpec.Part.ALL[slot];
		final LuaComputerBlockEntity be = blockEntity();
		final int tier = be == null ? 2 : be.tier();
		final java.util.List<Component> lines = new java.util.ArrayList<>();
		final net.minecraft.world.item.ItemStack held = hoveredSlot.getItem();
		if (held.getItem() instanceof dev.virtualminecraft.item.PartItem pi) {
			// the item's own tooltip ends with "Right-click a Computer to open its case", which is not news here
			lines.add(Component.literal(held.getHoverName().getString()));
			lines.add(Component.literal(MachineSpec.partLabel(pi.part(), pi.level())).withStyle(net.minecraft.ChatFormatting.GRAY));
		} else if (!held.isEmpty()) {
			lines.addAll(getTooltipFromContainerItem(held));
		} else {
			lines.add(Component.literal(SLOT_NAMES[slot]));
		}
		lines.add(Component.literal("Up to " + MachineSpec.ceilingLabel(part, tier) + " in this case").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
		g.setTooltipForNextFrame(font, lines, java.util.Optional.empty(), mouseX, mouseY);
	}

	@Override
	protected void containerTick() {
		super.containerTick();
		refresh();
	}

	@Override
	public void extractBackground(final GuiGraphicsExtractor g, final int mouseX, final int mouseY, final float a) {
		final int x0 = leftPos;
		final int y0 = topPos;
		final int x1 = leftPos + imageWidth;
		final int y1 = topPos + imageHeight;
		g.fill(x0, y0, x1, y1, PANEL);
		g.fill(x0, y0, x1, y0 + 1, PANEL_LIGHT);
		g.fill(x0, y0, x0 + 1, y1, PANEL_LIGHT);
		g.fill(x0, y1 - 1, x1, y1, PANEL_DARK);
		g.fill(x1 - 1, y0, x1, y1, PANEL_DARK);
		for (final Slot slot : menu.slots) {
			g.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18);
		}
	}

	@Override
	protected void extractLabels(final GuiGraphicsExtractor g, final int mouseX, final int mouseY) {
		super.extractLabels(g, mouseX, mouseY);
		for (int i = 0; i < CAPTIONS.length && i < menu.slots.size(); i++) {
			final Slot slot = menu.slots.get(i);
			final String c = CAPTIONS[i];
			g.text(font, c, slot.x + 8 - font.width(c) / 2, slot.y + 19, TEXT, false);
		}
		final LuaComputerBlockEntity be = blockEntity();
		if (be == null) {
			return;
		}
		final MachineSpec spec = be.spec();
		// two short lines: the panel is 176 wide and the font fits ~29 characters in it. §9 U10(a): a case that
		// will not boot spends both of them saying so, because the numbers of a machine that cannot run are noise.
		final String dead = spec.bootRefusal();
		final String line1 = dead != null ? Character.toUpperCase(dead.charAt(0)) + dead.substring(1) + "."
			: be.memMb() + " MB · CPU " + spec.cpuPercent() + " % · " + (spec.hasDrive() ? "disk " + MachineSpec.kb(spec.diskKb()) : "no disk");
		final String line2 = dead != null ? "Fit parts to make it run."
			: spec.hasGraphics()
				? spec.maxW() + "×" + spec.maxH() + ", " + spec.colours() + " colours, " + spec.synthChannels() + " voices"
				: "no graphics card, " + spec.synthChannels() + " voices";
		g.text(font, font.plainSubstrByWidth(line1, imageWidth - 14), 7, 45, TEXT, false);
		g.text(font, font.plainSubstrByWidth(line2, imageWidth - 14), 7, 54, TEXT, false);
		// The machine's state, right-aligned in the title row (the name is on the block; the title is the case).
		// A powered machine with nowhere to draw says so instead: "waiting" is the truth about the kernel and a lie
		// about the case, and it cost [name] a quarter of an hour of thinking a healthy computer was broken.
		// §9 U10(a) comes first: a case with no processor in it is not "waiting", it is a box, and the reason has
		// to be the thing a player reads. Then the missing screen, then whatever the machine itself says.
		// A machine with nowhere to draw must not sit at "waiting" either — the missing card is the answer, and
		// "waiting" is what cost [name] a quarter of an hour once already (HANDOFF (p)).
		final boolean noCard = be.powered() && !spec.hasGraphics() && dead == null;
		final boolean noScreen = be.powered() && !be.hasMonitor() && !noCard;
		final boolean warn = dead != null || noScreen || noCard;
		final String status = font.plainSubstrByWidth(
			dead != null ? spec.shortRefusal() : noCard ? "no GFX card" : noScreen ? "no monitor" : be.status().replace("picture ", ""), 74); // the synced string carries a "picture" prefix for the renderer
		g.text(font, status, imageWidth - 7 - font.width(status), titleLabelY, warn ? NO_MONITOR : 0xFF2060A0, false);
	}
}
