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

	private static java.util.Set<net.minecraft.item.Item> needed = java.util.Set.of();
	private static int neededAt = -1000;

	/**
	 * With the inventory nearly full, throws out a stack the art doesn't need (dirt and seeds from clearing the
	 * ground, say), so building and gathering can go on. Never tools, food, maps, shulker boxes, or a block the art
	 * still needs.
	 */
	static void tossJunk(MinecraftClient client, Plan plan, int tick) {
		ClientPlayerEntity player = client.player;
		if (player == null || player.getAbilities().creativeMode || player.currentScreenHandler != player.playerScreenHandler) return;
		PlayerInventory inv = player.getInventory();
		int free = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) free++;
		if (free > 1) return;
		if (tick - neededAt > 200) {
			java.util.Set<net.minecraft.item.Item> n = new java.util.HashSet<>();
			for (net.minecraft.block.Block b : Builder.remaining(plan, client.world).keySet()) n.add(b.asItem());
			n.add(plan.filler.asItem());
			needed = n;
			neededAt = tick;
		}
		int worst = -1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			if (st.isEmpty() || i == inv.getSelectedSlot() || keep(st)) continue;
			if (worst < 0 || st.getCount() > inv.getStack(worst).getCount()) worst = i;
		}
		if (worst < 0) return;
		Builder.debug("throwing away {} (inventory full)", inv.getStack(worst));
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, worst < 9 ? 36 + worst : worst, 1,
				net.minecraft.screen.slot.SlotActionType.THROW, player);
	}

	private static boolean keep(ItemStack st) {
		if (st.isDamageable() || st.get(DataComponentTypes.FOOD) != null && !bad(st)) return true;
		if (st.isOf(Items.FILLED_MAP) || st.isOf(Items.MAP) || st.isOf(Items.SHEARS) || st.isOf(Items.EXPERIENCE_BOTTLE)) return true;
		if (st.getItem() instanceof net.minecraft.item.BlockItem bi) {
			if (bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock || bi.getBlock() instanceof net.minecraft.block.EnderChestBlock) return true;
			return needed.contains(st.getItem());
		}
		// Anything else valuable stays; the common junk of digging goes.
		return !(st.isOf(Items.WHEAT_SEEDS) || st.isOf(Items.FLINT) || st.isOf(Items.STICK) || st.isOf(Items.CLAY_BALL)
				|| st.isOf(Items.SNOWBALL) || st.isOf(Items.GLOWSTONE_DUST) || st.isOf(Items.MELON_SLICE) || st.isOf(Items.STRING)
				|| st.isOf(Items.ROTTEN_FLESH) || st.isOf(Items.BONE) || st.isOf(Items.ARROW) || st.isOf(Items.SPIDER_EYE)
				|| st.isOf(Items.GUNPOWDER));
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
