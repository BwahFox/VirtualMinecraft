package dev.virtualminecraft.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * The {@code virtualminecraft:disk} item component: which image file a disk item stands for.
 * <p>
 * {@code id} names the file {@code <world>/virtualminecraft/items/<id>.qcow2} for floppies and hard drives
 * (created lazily the first time a computer starts with the disk attached). A CD carries no file of its own:
 * {@code iso} is a name in the ISO directory (or an absolute path), exactly like the computer's ISO field.
 * {@code sizeMb} is the virtual size for image-backed kinds (fixed 1.44 MB for floppies).
 */
public record DiskData(UUID id, int sizeMb, String iso) {
	public static final Codec<DiskData> CODEC = RecordCodecBuilder.create(i -> i.group(
		UUIDUtil.STRING_CODEC.fieldOf("id").forGetter(DiskData::id),
		Codec.INT.optionalFieldOf("sizeMb", 0).forGetter(DiskData::sizeMb),
		Codec.STRING.optionalFieldOf("iso", "").forGetter(DiskData::iso)
	).apply(i, DiskData::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, DiskData> STREAM_CODEC = StreamCodec.composite(
		UUIDUtil.STREAM_CODEC, DiskData::id,
		ByteBufCodecs.VAR_INT, DiskData::sizeMb,
		ByteBufCodecs.STRING_UTF8, DiskData::iso,
		DiskData::new);

	/**
	 * The id a disk carries before it is anybody's: a floppy template out of a loot table or a trade (U3c step 2,
	 * 2026-08-28). Two such items must never share a file, so {@link DiskItem#ensureData} mints a real id the
	 * moment one is inserted somewhere. Blank items from the creative tab carry no component at all instead.
	 */
	public static final UUID TEMPLATE = new UUID(0L, 0L);

	public boolean isTemplate() {
		return TEMPLATE.equals(id);
	}

	/** File name of the image for image-backed kinds. */
	public String fileName() {
		return id + ".qcow2";
	}

	/** Short id shown on tooltips and in the BIOS list. */
	public String serial() {
		return id.toString().substring(0, 8);
	}
}
