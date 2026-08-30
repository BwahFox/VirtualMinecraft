package dev.virtualminecraft.client.render;

import java.util.UUID;
import net.minecraft.core.Direction;
import org.jspecify.annotations.Nullable;

/**
 * What {@link MonitorRenderer} draws from. On 26.2 this is a {@code BlockEntityRenderState} the game extracts and
 * hands back; 1.20.1 renders straight from the block entity, so the renderer fills one of these itself at the top of
 * {@code render} and the drawing code below it is unchanged.
 */
public class MonitorRenderState {
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
