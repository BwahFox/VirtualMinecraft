package dev.virtualminecraft;

import dev.virtualminecraft.item.DiskData;
import dev.virtualminecraft.item.StackData;
import java.util.UUID;
import net.fabricmc.fabric.api.object.builder.v1.trade.TradeOfferHelper;
import net.minecraft.world.entity.npc.VillagerTrades;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;

/**
 * 1.20.1 only: the Clerk's trade ladder. On 26.2 this is data — {@code data/virtualminecraft/villager_trade/clerk/<level>/*.json}
 * and the {@code trade_set} files that draw two per level — and 1.20.1 has no data-driven villager trades, so the
 * same twenty-five offers are written down here. <b>Keep the two in step</b>: a new CD in the store on 26.2 is a
 * JSON file there and a line here. Every number below (emeralds, uses, XP, the 5 % reputation discount, the
 * template ids) is copied from the JSON, and Minecraft draws two of each level's list per villager level exactly
 * as the {@code trade_set}'s {@code amount: 2} does.
 */
public final class ClerkTrades {
	private ClerkTrades() {
	}

	/** {@code max_uses} / {@code xp} / {@code reputation_discount} from the JSON: the same three numbers on every CD. */
	private static final int CD_USES = 8;
	private static final int CD_XP = 5;
	private static final float DISCOUNT = 0.05f;

	/** A CD (or floppy) the villager sells, carrying a template disk that becomes a real one when it is inserted. */
	private static VillagerTrades.ItemListing sells(final int emeralds, final Item disk, final int sizeMb, final String iso, final String templateId, final int uses, final int xp) {
		return (entity, random) -> {
			final ItemStack out = new ItemStack(disk);
			StackData.setDisk(out, new DiskData(UUID.fromString(templateId), sizeMb, iso));
			return new MerchantOffer(new ItemStack(Items.EMERALD, emeralds), out, uses, xp, DISCOUNT);
		};
	}

	private static VillagerTrades.ItemListing cd(final int emeralds, final String name, final String templateId) {
		return sells(emeralds, ModContent.CD, 0, "cds:" + name, templateId, CD_USES, CD_XP);
	}

	/** The villager buys something for emeralds. */
	private static VillagerTrades.ItemListing buys(final Item item, final int count, final int emeralds, final int uses, final int xp) {
		return (entity, random) -> new MerchantOffer(new ItemStack(item, count), new ItemStack(Items.EMERALD, emeralds), uses, xp, DISCOUNT);
	}

	private static VillagerTrades.ItemListing sellsPlain(final int emeralds, final Item item, final int uses, final int xp) {
		return (entity, random) -> new MerchantOffer(new ItemStack(Items.EMERALD, emeralds), new ItemStack(item), uses, xp, DISCOUNT);
	}

	public static void register() {
		TradeOfferHelper.registerVillagerOffers(ModContent.CLERK, 1, f -> {
			f.add(buys(ModContent.CD, 1, 1, 12, 2));                                              // cd_for_emerald
			f.add(cd(4, "pinball", "283f0cad-e6c9-5573-b611-849f411a6b9d"));
			f.add(cd(3, "mines", "a84b14e8-6c47-565c-a971-50440e9d9510"));
			f.add(cd(2, "lightsout", "e1947627-4f13-5d34-8f7a-5a6e305abba0"));
			f.add(cd(2, "2048", "f18a9f8b-c77e-5cdc-bad9-ace3c83f6490"));
			f.add(cd(2, "hangman", "afea2d6a-c590-59fe-b1c6-4aaa3e993f92"));
			f.add(sells(3, ModContent.FLOPPY, 1, "floppies:starter", "00000000-0000-0000-0000-000000000000", CD_USES, CD_XP)); // starter_floppy
		});
		TradeOfferHelper.registerVillagerOffers(ModContent.CLERK, 2, f -> {
			f.add(buys(ModContent.FLOPPY, 2, 1, 12, 2));                                          // floppy_for_emeralds
			f.add(cd(4, "barrage", "8b31bf22-4d1d-58de-9489-e0a8dd0f07e9"));
			f.add(cd(4, "blocks", "26a3b42c-213b-555e-8cee-9235109cabdf"));
			f.add(cd(4, "drift", "839902fe-c833-5d0e-8e78-528e85be1ae3"));
			f.add(cd(5, "maze", "8453735c-bcb7-5982-9481-245e262436c0"));
			f.add(cd(4, "solitaire", "3d87c170-3ddd-5282-9ab7-5017e0f03a92"));
		});
		TradeOfferHelper.registerVillagerOffers(ModContent.CLERK, 3, f -> {
			f.add(cd(5, "calculator", "3235edc1-c598-512e-b44f-65caea665269"));
			f.add(cd(5, "keypad", "97fc3d31-f7c3-5599-929d-66a24ced557e"));
			f.add(cd(5, "life", "9e1e3e26-d71c-590c-b356-36322480b1ea"));
			f.add(cd(6, "notes", "7a359e67-501f-51ff-9932-4980581bf751"));
			f.add(cd(6, "reader", "30f9acf6-a2c5-5fb7-805f-7662c0788f16"));
		});
		TradeOfferHelper.registerVillagerOffers(ModContent.CLERK, 4, f -> {
			f.add(cd(10, "browser", "1886bde4-55c7-5892-b4eb-2bc302d960df"));
			f.add(cd(8, "drift3d", "8df52556-02f3-5d1d-ac0d-58194a7edbeb"));
			f.add(cd(8, "sentry", "1ba7d73f-78c8-5f7a-ba1d-de0c1c82836d"));
			f.add(cd(12, "sheet", "555c19c4-ea7b-5a38-98b6-ab3029fd98a5"));
			f.add(cd(12, "sprite", "79ac1b71-f525-52be-b84a-ba1402757ab5"));
		});
		TradeOfferHelper.registerVillagerOffers(ModContent.CLERK, 5, f -> {
			f.add(sellsPlain(24, ModContent.HARD_DRIVE, 3, 30));                                  // hard_drive
			f.add(cd(16, "server", "eb3bafa7-4a35-5e51-81f8-3216b58310c8"));
		});
	}
}
