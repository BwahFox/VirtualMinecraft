package dev.virtualminecraft.item;

import dev.virtualminecraft.computer.MachineSpec;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.block.Block;

/**
 * A Computer case in the hand (ROADMAP §9 U10(a), [name] 2026-08-29: <i>"the cases should only dictate the max spec
 * they can use"</i>). Crafting gives you an <em>empty</em> case and an empty case is a dead box, so the item has to
 * say what it still needs — the alternative is a player placing one, seeing nothing happen, and calling it broken.
 * It also says what the case is a ceiling for, which is the only thing that separates the three of them.
 */
public class CaseItem extends BlockItem {
	private final int tier;

	public CaseItem(final Block block, final int tier, final Properties properties) {
		super(block, properties);
		this.tier = tier;
	}

	@Override
	public void appendHoverText(final ItemStack stack, final TooltipContext context, final TooltipDisplay display, final Consumer<Component> lines, final TooltipFlag flag) {
		lines.accept(Component.literal("Empty. Fit at least a processor and memory").withStyle(ChatFormatting.GRAY));
		lines.accept(Component.literal("Takes up to " + MachineSpec.ceilingLabel(MachineSpec.Part.RAM, tier)
			+ ", " + MachineSpec.ceilingLabel(MachineSpec.Part.CPU, tier) + " of a core,").withStyle(ChatFormatting.DARK_GRAY));
		lines.accept(Component.literal(MachineSpec.ceilingLabel(MachineSpec.Part.GRAPHICS, tier)
			+ " and " + MachineSpec.ceilingLabel(MachineSpec.Part.DRIVE, tier) + " of disk").withStyle(ChatFormatting.DARK_GRAY));
	}
}
