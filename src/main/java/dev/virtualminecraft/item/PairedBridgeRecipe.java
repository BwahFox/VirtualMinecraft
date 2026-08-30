package dev.virtualminecraft.item;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.virtualminecraft.ModContent;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;

/**
 * A shaped recipe whose output carries a freshly minted pair id (ROADMAP §9 U11 bridges; [name] chose "crafted
 * as a linked pair", 2026-08-28).
 * <p>
 * The trick is that the result is <b>one stack of two</b>: both bridges are the same ItemStack, so they cannot
 * disagree about the id, and pairing is finished before either is placed. Craft again and you get a different
 * pair. Bridges that <em>do</em> share an id are a group rather than a couple — the registry joins every run
 * carrying it, so N of them make one hub — which is deliberate; the way to build one on purpose is the
 * right-click pairing on {@code BridgeBlock}. What a shift-craft produces is <b>unverified</b>: each
 * {@code assemble()} mints its own id, so the batches most likely do not stack together into one group.
 * <p>
 * Everything else is {@link ShapedRecipe}'s, including the JSON shape: the recipe file is an ordinary shaped
 * recipe except for its {@code type}.
 */
public class PairedBridgeRecipe extends ShapedRecipe {
	public static final MapCodec<ShapedRecipe> MAP_CODEC = RecordCodecBuilder.mapCodec(
		i -> i.group(
			Recipe.CommonInfo.MAP_CODEC.forGetter(o -> ((PairedBridgeRecipe) o).info),
			CraftingRecipe.CraftingBookInfo.MAP_CODEC.forGetter(o -> ((PairedBridgeRecipe) o).book),
			ShapedRecipePattern.MAP_CODEC.forGetter(o -> ((PairedBridgeRecipe) o).pat),
			ItemStackTemplate.CODEC.fieldOf("result").forGetter(o -> ((PairedBridgeRecipe) o).res)
		).apply(i, (a, b, c, d) -> new PairedBridgeRecipe(a, b, c, d)));

	public static final StreamCodec<RegistryFriendlyByteBuf, ShapedRecipe> STREAM_CODEC = StreamCodec.composite(
		Recipe.CommonInfo.STREAM_CODEC, o -> ((PairedBridgeRecipe) o).info,
		CraftingRecipe.CraftingBookInfo.STREAM_CODEC, o -> ((PairedBridgeRecipe) o).book,
		ShapedRecipePattern.STREAM_CODEC, o -> ((PairedBridgeRecipe) o).pat,
		ItemStackTemplate.STREAM_CODEC, o -> ((PairedBridgeRecipe) o).res,
		(a, b, c, d) -> new PairedBridgeRecipe(a, b, c, d));

	public static final RecipeSerializer<ShapedRecipe> SERIALIZER = new RecipeSerializer<>(MAP_CODEC, STREAM_CODEC);

	private final Recipe.CommonInfo info;
	private final CraftingRecipe.CraftingBookInfo book;
	private final ShapedRecipePattern pat;
	private final ItemStackTemplate res;

	public PairedBridgeRecipe(final Recipe.CommonInfo info, final CraftingRecipe.CraftingBookInfo book,
			final ShapedRecipePattern pattern, final ItemStackTemplate result) {
		super(info, book, pattern, result);
		this.info = info;
		this.book = book;
		this.pat = pattern;
		this.res = result;
	}

	@Override
	public ItemStack assemble(final CraftingInput input) {
		final ItemStack out = res.create();
		out.set(ModContent.BRIDGE_PAIR, UUID.randomUUID());
		return out;
	}

	@Override
	public RecipeSerializer<ShapedRecipe> getSerializer() {
		return SERIALIZER;
	}
}
