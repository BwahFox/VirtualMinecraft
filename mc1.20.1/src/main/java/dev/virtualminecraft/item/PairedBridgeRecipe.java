package dev.virtualminecraft.item;

import com.google.gson.JsonObject;
import dev.virtualminecraft.ModContent;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.ShapedRecipe;

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
 * recipe except for its {@code type}. (1.20.1: the serializer reads and writes through the vanilla shaped one
 * and wraps what comes out; 26.2 does the same with codecs.)
 */
public class PairedBridgeRecipe extends ShapedRecipe {
	public static final RecipeSerializer<ShapedRecipe> SERIALIZER = new RecipeSerializer<>() {
		@Override
		public ShapedRecipe fromJson(final ResourceLocation id, final JsonObject json) {
			return new PairedBridgeRecipe(RecipeSerializer.SHAPED_RECIPE.fromJson(id, json));
		}

		@Override
		public ShapedRecipe fromNetwork(final ResourceLocation id, final FriendlyByteBuf buf) {
			return new PairedBridgeRecipe(RecipeSerializer.SHAPED_RECIPE.fromNetwork(id, buf));
		}

		@Override
		public void toNetwork(final FriendlyByteBuf buf, final ShapedRecipe recipe) {
			RecipeSerializer.SHAPED_RECIPE.toNetwork(buf, recipe);
		}
	};

	public PairedBridgeRecipe(final ShapedRecipe shaped) {
		super(shaped.getId(), shaped.getGroup(), shaped.category(), shaped.getWidth(), shaped.getHeight(), shaped.getIngredients(),
			shaped.getResultItem(RegistryAccess.EMPTY), shaped.showNotification());
	}

	@Override
	public ItemStack assemble(final CraftingContainer input, final RegistryAccess registries) {
		final ItemStack out = getResultItem(registries).copy();
		StackData.setUuid(out, StackData.BRIDGE_PAIR, UUID.randomUUID());
		return out;
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return SERIALIZER;
	}
}
