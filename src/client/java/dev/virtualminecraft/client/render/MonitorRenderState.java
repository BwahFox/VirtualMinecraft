package dev.virtualminecraft.client.render;

import java.util.UUID;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

public class MonitorRenderState extends BlockEntityRenderState {
	public Direction facing = Direction.NORTH;
	public @Nullable UUID vm;
	public boolean textMode;
	/** False when the linked machine is switched off: the screen goes dark instead of holding its last frame. */
	public boolean on = true;
	public dev.virtualminecraft.bus.@Nullable TextGrid grid;
	/** Rectangle size in blocks; only the origin block renders, and it renders the whole rectangle. */
	public boolean origin = true;
	public int mbW = 1;
	public int mbH = 1;
}
