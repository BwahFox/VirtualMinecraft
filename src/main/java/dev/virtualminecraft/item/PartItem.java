package dev.virtualminecraft.item;

import dev.virtualminecraft.computer.MachineSpec;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * A part for a Computer's case (ROADMAP §9 U3b): RAM, CPU, graphics or drive, at level I, II or III. The item is
 * the whole story — no data on it, the level is the registry entry — so recipes stay plain and a part is a part
 * wherever it is. It goes into the case through the Computer's GUI; {@link MachineSpec} says what it does there.
 */
public class PartItem extends Item {
	private final MachineSpec.Part part;
	private final int level;

	public PartItem(final MachineSpec.Part part, final int level, final Properties properties) {
		super(properties);
		this.part = part;
		this.level = level;
	}

	public MachineSpec.Part part() {
		return part;
	}

	public int level() {
		return level;
	}

	/** The level of a stack as a part of {@code kind}, or 0 when it is not one. */
	public static int levelOf(final ItemStack stack, final MachineSpec.Part kind) {
		return stack.getItem() instanceof PartItem p && p.part == kind ? p.level : 0;
	}

	public static boolean isPart(final ItemStack stack, final MachineSpec.Part kind) {
		return stack.getItem() instanceof PartItem p && p.part == kind;
	}

	@Override
	public void appendHoverText(final ItemStack stack, final TooltipContext context, final TooltipDisplay display, final Consumer<Component> lines, final TooltipFlag flag) {
		lines.accept(Component.literal(MachineSpec.partLabel(part, level)).withStyle(ChatFormatting.GRAY));
		lines.accept(Component.literal("Right-click a Computer to open its case").withStyle(ChatFormatting.DARK_GRAY));
	}
}
