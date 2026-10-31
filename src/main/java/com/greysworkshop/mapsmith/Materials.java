package com.greysworkshop.mapsmith;

import net.minecraft.block.Block;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** Counting blocks: how many are needed, in stacks and shulker boxes, and how many you're carrying. */
final class Materials {
	private Materials() {}

	/** "1,234 · 19 stacks + 18 · 0.7 shulkers" (stacks of 64; a shulker box holds 27). */
	static String amount(long n) {
		StringBuilder s = new StringBuilder(String.format("%,d", n));
		if (n >= 64) {
			s.append(" · ").append(n / 64).append(n / 64 == 1 ? " stack" : " stacks");
			if (n % 64 > 0) s.append(" + ").append(n % 64);
		}
		if (n >= 64 * 27 / 2) s.append(String.format(" · %.1f shulkers", n / (64.0 * 27)));
		return s.toString();
	}

	/** Items the player has, counting what's in shulker boxes (and bundles) they're carrying. */
	static Map<Item, Long> carried(ClientPlayerEntity player) {
		Map<Item, Long> out = new HashMap<>();
		PlayerInventory inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) add(out, inv.getStack(i), 0);
		return out;
	}

	private static void add(Map<Item, Long> out, ItemStack stack, int depth) {
		if (stack.isEmpty()) return;
		out.merge(stack.getItem(), (long) stack.getCount(), Long::sum);
		ContainerComponent box = stack.get(DataComponentTypes.CONTAINER);
		if (box != null && depth == 0) for (ItemStack inner : box.iterateNonEmpty()) add(out, inner, 1);
	}

	static long have(Map<Item, Long> carried, Block block) {
		return carried.getOrDefault(block.asItem(), 0L);
	}
}
