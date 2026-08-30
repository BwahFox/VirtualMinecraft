package dev.virtualminecraft.client.pointer;

import dev.virtualminecraft.block.MonitorBlock;
import dev.virtualminecraft.block.MonitorBlockEntity;
import dev.virtualminecraft.client.input.InputSender;
import dev.virtualminecraft.client.render.ScreenTextures;
import dev.virtualminecraft.screen.ScreenSource;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The main mod's half of the seam (ROADMAP §9 U4.0): a ray in, the guest's mouse out. It clips the ray against the
 * world, works out which monitor it landed on and which framebuffer pixel that is, and pushes motion, buttons and
 * wheel through the ordinary {@link InputSender} path — the same {@code VmInputPayload} a full-screen view sends,
 * so the server's own distance check still gates it and nothing new had to be trusted.
 * <p>
 * <b>Nothing here knows what is holding the ray.</b> That is the entire point: the camera, a VR controller and a
 * fake in a test all arrive at this class as three numbers and a direction, and everything downstream — the
 * rate limit, the pixel maths, the payload — is shared. The in-world hover that used to live in
 * {@code InputSender.hoverTick} is this class now, with the camera as one source among others.
 */
public final class WorldPointer {
	/** How far a source may point when it does not say; the camera's own reach is shorter and it says so. */
	public static final double DEFAULT_REACH = 8.0;

	private static @Nullable UUID screen;
	private static int lastX = -1;
	private static int lastY = -1;
	private static int lastButtons;
	private static long ticks;
	private static long lastMoveTick;

	private WorldPointer() {
	}

	/** What the pointer is on and where, for the puppet: {@code null} when it is not on a screen. */
	public static @Nullable UUID screen() {
		return screen;
	}

	public static int x() {
		return lastX;
	}

	public static int y() {
		return lastY;
	}

	public static int buttons() {
		return lastButtons;
	}

	/**
	 * Let go: any button still down is released on the screen it was pressed on. Called when the pointer leaves a
	 * screen, when the source changes and when the world goes away — a stuck mouse button in a guest is a bug the
	 * player cannot fix from inside.
	 */
	public static void release() {
		if (screen != null && lastButtons != 0) {
			InputSender.worldPointer(screen, 0, lastX, lastY);
		}
		screen = null;
		lastButtons = 0;
		lastX = -1;
		lastY = -1;
	}

	/**
	 * One tick of pointing. {@code wheel} is notches already taken from the source, positive up.
	 */
	public static void aim(final PointerRay ray, final int wantButtons, final int wheel) {
		ticks++;
		final Minecraft mc = Minecraft.getInstance();
		// A full-screen view owns the input while it is open, and so does any other GUI: pointing at a monitor
		// from inside a menu would drive two mice at once.
		// A session belonging to the in-world keyboard is not a reason to stop pointing -- §9 U4.3 exists so you
		// can aim and type at the same screen at once, which is the whole point of a keyboard that floats beside
		// it. Any *other* session means a full-screen view owns the input, and driving two mice is not a feature.
		final boolean ownedElsewhere = InputSender.target() != null
			&& !InputSender.target().equals(dev.virtualminecraft.client.input.WorldKeyboard.screen());
		if (mc.level == null || mc.player == null || mc.screen != null || ownedElsewhere) {
			release();
			return;
		}
		final Hit t = resolve(ray);
		if (t == null) {
			release();
			return;
		}
		if (!t.screen.equals(screen)) {
			release(); // moved to another monitor: let the old one go before touching the new one
			screen = t.screen;
		}
		final boolean moved = t.x != lastX || t.y != lastY;
		final int buttonsNow = wantButtons & 0b111;
		if (buttonsNow != lastButtons) {
			// Buttons are sent the moment they change, at the pixel they changed on -- WiVRn adds 30-50 ms and
			// the press has to feel like the press (§9 U4.5), so this never waits for the motion rate limit.
			lastX = t.x;
			lastY = t.y;
			lastButtons = buttonsNow;
			lastMoveTick = ticks;
			InputSender.worldPointer(t.screen, buttonsNow, t.x, t.y);
		} else if (moved && ticks - lastMoveTick >= 1) {
			// Motion once a tick at most, and only when it changed. This was every *other* tick -- inherited from
			// the crosshair hover, where it is invisible because a head turns slowly. A controller does not: at 10
			// updates a second a VR pointer reads as laggy, which is exactly how [name] described it (2026-08-29,
			// "mouse feels kinda laggy, might just be how we're polling where the mouse is" -- she was right about
			// where to look). One tick is the floor for anything driven from the client tick, so this is as far as
			// it goes without moving the sender onto the render thread, which is not worth it for a mouse.
			lastX = t.x;
			lastY = t.y;
			lastMoveTick = ticks;
			InputSender.worldPointer(t.screen, buttonsNow, t.x, t.y);
		}
		if (wheel != 0) {
			InputSender.worldWheel(t.screen, wheel > 0 ? 0b1000 : 0b10000, lastButtons, t.x, t.y, Math.min(8, Math.abs(wheel)));
		}
	}

	/** Text from a {@link TextSource}, replayed into whatever the pointer is on. */
	public static void type(final String text) {
		if (screen != null) {
			InputSender.worldChars(screen, text);
		}
	}

	/**
	 * What a ray landed on: which screen, which framebuffer pixel, and how far along the ray it was. The distance
	 * is there for {@link #probe}'s caller — the {@code vr} module tells "pointing at a monitor from across the
	 * room" (drive the guest's mouse) from "hand inside the monitor" (leave it to Vivecraft, so touching a screen
	 * still opens the panel) — and nothing in this class uses it.
	 */
	public record Hit(UUID screen, int x, int y, double distance) {
	}

	/**
	 * Where would this ray land, without touching anything? The {@code vr} module's {@code InteractModule} asks
	 * this once per hand per tick to decide whether the VR interact binding belongs to a monitor right now
	 * (ROADMAP §9 U4.2), which is how a monitor becomes something Vivecraft <em>knows</em> can be pointed at
	 * rather than something fighting everything else for the trigger.
	 * <p>
	 * Pure: no input is sent and no state changes, so calling it from Vivecraft's tracker mid-tick is safe.
	 */
	public static @Nullable Hit probe(final PointerRay ray) {
		return resolve(ray);
	}

	/**
	 * Ray to pixel. Blocks first, then entities on the same segment — a mob standing in front of a screen has
	 * always blocked the crosshair, and it has to block a controller too, or pointing would reach through it.
	 */
	private static @Nullable Hit resolve(final PointerRay ray) {
		final Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			return null;
		}
		final Vec3 from = ray.origin();
		final Vec3 to = ray.end();
		final BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
		if (hit.getType() != HitResult.Type.BLOCK) {
			return null;
		}
		final double blockDistSqr = hit.getLocation().distanceToSqr(from);
		final AABB box = new AABB(from, to).inflate(1.0);
		final EntityHitResult entity = ProjectileUtil.getEntityHitResult(mc.player, from, to, box, Entity::isPickable, blockDistSqr);
		if (entity != null && entity.getLocation().distanceToSqr(from) < blockDistSqr) {
			return null; // something is in the way
		}
		if (!(mc.level.getBlockEntity(hit.getBlockPos()) instanceof MonitorBlockEntity hitMonitor)) {
			return null;
		}
		final MonitorBlockEntity monitor = hitMonitor.origin() != null ? hitMonitor.origin() : hitMonitor;
		if (monitor.isTextMode()) {
			return null; // a text-mode screen has no framebuffer to point at
		}
		final UUID id = monitor.getScreenId();
		final ScreenSource source = monitor.getSource();
		if (id == null || source == null || !source.screenActive()) {
			return null;
		}
		final int[] full = ScreenTextures.fullSize(id);
		final int[] px = MonitorBlock.hitToPixel(hitMonitor, mc.level.getBlockState(hit.getBlockPos()), hit.getBlockPos(), hit, full[0], full[1]);
		return px == null ? null : new Hit(id, px[0], px[1], Math.sqrt(blockDistSqr));
	}
}
