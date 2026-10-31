package com.greysworkshop.mapsmith;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CarpetBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.LeavesBlock;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Which blocks give each map colour, and which one to use by default. */
final class BlockPalette {
	private BlockPalette() {}

	/**
	 * Favourites by colour id, best first: common in survival, don't fall, don't change by themselves. Each is only
	 * used if the game agrees it's that colour.
	 */
	private static final String[][] FAVOURITES = new String[MapPalette.COLORS][];
	/** Blocks never offered: creative-only, dangerous, or they change colour by themselves. */
	private static final Set<String> NEVER = Set.of("bedrock", "barrier", "command_block", "chain_command_block",
			"repeating_command_block", "structure_block", "structure_void", "jigsaw", "spawner", "trial_spawner", "vault",
			"reinforced_deepslate", "end_portal_frame", "budding_amethyst", "petrified_oak_slab", "light", "test_block",
			"test_instance_block", "tnt", "frosted_ice", "dirt_path", "farmland", "copper_block", "exposed_copper",
			"weathered_copper", "cut_copper", "exposed_cut_copper", "weathered_cut_copper", "chiseled_copper",
			"exposed_chiseled_copper", "weathered_chiseled_copper", "copper_grate", "exposed_copper_grate",
			"weathered_copper_grate", "copper_bulb", "exposed_copper_bulb", "weathered_copper_bulb", "redstone_lamp",
			"sculk_sensor", "calibrated_sculk_sensor", "sculk_shrieker", "dragon_egg", "suspicious_sand", "suspicious_gravel",
			"ice", "creaking_heart", "respawn_anchor");

	static {
		fav(1, "grass_block", "slime_block");
		fav(2, "birch_planks", "sandstone", "end_stone", "glowstone", "sand");
		fav(3, "mushroom_stem", "cobweb");
		fav(4, "redstone_block");
		fav(5, "packed_ice", "blue_ice");
		fav(6, "iron_block", "heavy_core");
		fav(7, "oak_leaves", "spruce_leaves", "birch_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves");
		fav(8, "white_wool", "white_concrete", "snow_block");
		fav(9, "clay");
		fav(10, "granite", "coarse_dirt", "polished_granite", "jungle_planks", "dirt");
		fav(11, "cobblestone", "stone", "andesite", "stone_bricks");
		fav(13, "oak_planks");
		fav(14, "diorite", "quartz_block", "polished_diorite");
		fav(15, "orange_wool", "acacia_planks", "orange_concrete", "terracotta", "pumpkin");
		fav(16, "magenta_wool", "magenta_concrete", "purpur_block");
		fav(17, "light_blue_wool", "light_blue_concrete");
		fav(18, "yellow_wool", "hay_block", "yellow_concrete", "sponge");
		fav(19, "lime_wool", "lime_concrete", "melon");
		fav(20, "pink_wool", "pink_concrete", "cherry_leaves");
		fav(21, "gray_wool", "gray_concrete", "tinted_glass");
		fav(22, "light_gray_wool", "light_gray_concrete", "pale_oak_wood");
		fav(23, "cyan_wool", "cyan_concrete", "prismarine");
		fav(24, "purple_wool", "purple_concrete", "amethyst_block");
		fav(25, "blue_wool", "blue_concrete");
		fav(26, "brown_wool", "dark_oak_planks", "brown_concrete", "soul_soil");
		fav(27, "green_wool", "green_concrete", "dried_kelp_block");
		fav(28, "red_wool", "red_concrete", "nether_wart_block", "bricks", "mangrove_planks");
		fav(29, "black_wool", "black_concrete", "coal_block", "obsidian", "blackstone");
		fav(30, "gold_block", "raw_gold_block");
		fav(31, "prismarine_bricks", "dark_prismarine", "diamond_block");
		fav(32, "lapis_block");
		fav(33, "emerald_block");
		fav(34, "spruce_planks");
		fav(35, "netherrack", "nether_bricks", "magma_block");
		String[] dyes = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan",
				"purple", "blue", "brown", "green", "red", "black"};
		// The terracotta colours, 36..51, are in dye order.
		for (int i = 0; i < 16; i++) fav(36 + i, dyes[i] + "_terracotta");
		fav(52, "crimson_nylium");
		fav(53, "crimson_planks");
		fav(54, "crimson_hyphae", "stripped_crimson_hyphae");
		fav(55, "warped_nylium");
		fav(56, "warped_planks");
		fav(57, "warped_hyphae", "stripped_warped_hyphae");
		fav(58, "warped_wart_block");
		fav(59, "cobbled_deepslate", "deepslate", "deepslate_bricks", "polished_deepslate");
		fav(60, "raw_iron_block");
		fav(61, "verdant_froglight");
	}

	private static void fav(int id, String... blocks) {
		FAVOURITES[id] = blocks;
	}

	private static List<Block>[] candidates;

	/** Blocks that are this colour and can be built with, favourites first. */
	@SuppressWarnings("unchecked")
	static synchronized List<Block> candidates(int colorId) {
		if (candidates == null) {
			List<Block>[] lists = new List[MapPalette.COLORS];
			for (int i = 0; i < lists.length; i++) lists[i] = new ArrayList<>();
			for (Block block : Registries.BLOCK) {
				if (usable(block)) lists[colorOf(block.getDefaultState())].add(block);
			}
			for (int id = 0; id < lists.length; id++) {
				String[] favs = FAVOURITES[id] == null ? new String[0] : FAVOURITES[id];
				List<String> order = List.of(favs);
				lists[id].sort(Comparator.comparingInt((Block b) -> {
					int at = order.indexOf(Registries.BLOCK.getId(b).getPath());
					return at < 0 ? 1000 + (falls(b) ? 500 : 0) + (b instanceof CarpetBlock ? 100 : 0) : at;
				}).thenComparing(b -> Registries.BLOCK.getId(b).getPath()));
			}
			candidates = lists;
		}
		return candidates[colorId];
	}

	static int colorOf(BlockState state) {
		return state.getMapColor(EmptyBlockView.INSTANCE, BlockPos.ORIGIN).id;
	}

	private static boolean usable(Block block) {
		Identifier id = Registries.BLOCK.getId(block);
		if (!id.getNamespace().equals("minecraft") || NEVER.contains(id.getPath()) || block.asItem() == Items.AIR) return false;
		if (id.getPath().startsWith("infested_") || block instanceof BlockEntityProvider) return false;
		BlockState state = block.getDefaultState();
		int color = colorOf(state);
		if (color == 0 || color == MapPalette.WATER || !state.getFluidState().isEmpty()) return false;
		return block instanceof CarpetBlock || block instanceof LeavesBlock || state.isFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
	}

	/** The block to use for a colour when nothing's been picked: a carpet in flat art if one's that colour. */
	static Block defaultFor(int colorId, boolean carpets) {
		List<Block> list = candidates(colorId);
		if (list.isEmpty()) return null;
		if (carpets) for (Block b : list) if (b instanceof CarpetBlock) return b;
		for (Block b : list) if (!(b instanceof CarpetBlock)) return b;
		return list.get(0);
	}

	static boolean falls(Block block) {
		return block instanceof FallingBlock;
	}

	/** Falls or pops off without a block under it. */
	static boolean needsBelow(Block block) {
		return block instanceof FallingBlock || block instanceof CarpetBlock;
	}

	static Block byId(String id) {
		if (id == null || id.isEmpty()) return null;
		Identifier ident = Identifier.tryParse(id);
		if (ident == null) return null;
		Block b = Registries.BLOCK.get(ident);
		return b == Blocks.AIR ? null : b;
	}

	static String id(Block block) {
		return Registries.BLOCK.getId(block).toString();
	}

	/** Whether a block in the world is solid enough to stand in for a support (anything you can't walk through). */
	static boolean solid(BlockState state) {
		return !state.isAir() && !state.isReplaceable() && state.getFluidState().isEmpty();
	}
}
