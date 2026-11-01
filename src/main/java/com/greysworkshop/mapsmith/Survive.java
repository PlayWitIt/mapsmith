package com.greysworkshop.mapsmith;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

/**
 * Keeping the player going on a long build, hands free: eats when hungry, from food it carries (never anything that
 * poisons, or chorus fruit, which teleports).
 */
final class Survive {
	private Survive() {}

	private static int eatingTicks;

	/** True while eating: the builder waits. */
	static boolean tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || player.getAbilities().creativeMode) return false;
		int food = player.getHungerManager().getFoodLevel();
		if (eatingTicks > 0) {
			eatingTicks++;
			// Done (it's gone from hand or hunger went up), or it's taking too long.
			if (!player.isUsingItem() && eatingTicks > 5 || eatingTicks > 80) {
				client.options.useKey.setPressed(false);
				eatingTicks = 0;
				return false;
			}
			client.options.useKey.setPressed(true);
			return true;
		}
		// Eat below 15 (natural regeneration stops under 18; under 7 you can't sprint).
		if (food >= 15 || client.currentScreen != null) return false;
		int slot = bestFood(player.getInventory(), 20 - food);
		if (slot < 0) return false;
		Builder.select(client, player, slot);
		client.options.useKey.setPressed(true);
		client.interactionManager.interactItem(player, net.minecraft.util.Hand.MAIN_HAND);
		eatingTicks = 1;
		Builder.debug("eating {} (hunger {})", player.getInventory().getStack(player.getInventory().getSelectedSlot()), food);
		return true;
	}

	static void cancel(MinecraftClient client) {
		if (eatingTicks > 0) client.options.useKey.setPressed(false);
		eatingTicks = 0;
	}

	/** The food that fills the gap best without wasting much; -1 if none. */
	static int bestFood(PlayerInventory inv, int gap) {
		int best = -1, bestScore = Integer.MIN_VALUE;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			FoodComponent f = st.get(DataComponentTypes.FOOD);
			if (f == null || bad(st)) continue;
			int n = f.nutrition();
			int score = n <= gap ? n * 10 : 100 - (n - gap) * 10;
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	static boolean bad(ItemStack st) {
		return st.isOf(Items.ROTTEN_FLESH) || st.isOf(Items.SPIDER_EYE) || st.isOf(Items.POISONOUS_POTATO) || st.isOf(Items.PUFFERFISH)
				|| st.isOf(Items.CHORUS_FRUIT) || st.isOf(Items.SUSPICIOUS_STEW) || st.isOf(Items.ENCHANTED_GOLDEN_APPLE)
				|| st.isOf(Items.GOLDEN_APPLE) || st.isOf(Items.CHICKEN);
	}
}
