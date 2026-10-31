package com.greysworkshop.mapsmith;

/**
 * The colours a map can show, exactly as vanilla 1.21.11 draws them ({@code MapColor}): 61 base colours, each in four
 * shades. Which shade a pixel gets depends on its block's height against the block north of it (see {@link Shade}).
 * Plain data, so the converter runs (and is tested) without the game.
 */
final class MapPalette {
	private MapPalette() {}

	/** Colour ids 1..61; 0 is "clear" (nothing drawn). */
	static final int COLORS = 62;
	static final int WATER = 12;

	static final String[] NAMES = {
		"CLEAR",
		"PALE_GREEN",
		"PALE_YELLOW",
		"WHITE_GRAY",
		"BRIGHT_RED",
		"PALE_PURPLE",
		"IRON_GRAY",
		"DARK_GREEN",
		"WHITE",
		"LIGHT_BLUE_GRAY",
		"DIRT_BROWN",
		"STONE_GRAY",
		"WATER_BLUE",
		"OAK_TAN",
		"OFF_WHITE",
		"ORANGE",
		"MAGENTA",
		"LIGHT_BLUE",
		"YELLOW",
		"LIME",
		"PINK",
		"GRAY",
		"LIGHT_GRAY",
		"CYAN",
		"PURPLE",
		"BLUE",
		"BROWN",
		"GREEN",
		"RED",
		"BLACK",
		"GOLD",
		"DIAMOND_BLUE",
		"LAPIS_BLUE",
		"EMERALD_GREEN",
		"SPRUCE_BROWN",
		"DARK_RED",
		"TERRACOTTA_WHITE",
		"TERRACOTTA_ORANGE",
		"TERRACOTTA_MAGENTA",
		"TERRACOTTA_LIGHT_BLUE",
		"TERRACOTTA_YELLOW",
		"TERRACOTTA_LIME",
		"TERRACOTTA_PINK",
		"TERRACOTTA_GRAY",
		"TERRACOTTA_LIGHT_GRAY",
		"TERRACOTTA_CYAN",
		"TERRACOTTA_PURPLE",
		"TERRACOTTA_BLUE",
		"TERRACOTTA_BROWN",
		"TERRACOTTA_GREEN",
		"TERRACOTTA_RED",
		"TERRACOTTA_BLACK",
		"DULL_RED",
		"DULL_PINK",
		"DARK_CRIMSON",
		"TEAL",
		"DARK_AQUA",
		"DARK_DULL_PINK",
		"BRIGHT_TEAL",
		"DEEPSLATE_GRAY",
		"RAW_IRON_PINK",
		"LICHEN_GREEN",
	};

	/** Base colours, by id, as RGB. */
	static final int[] BASE = {
		0x000000, // 0 CLEAR
		0x7FB238, // 1 PALE_GREEN
		0xF7E9A3, // 2 PALE_YELLOW
		0xC7C7C7, // 3 WHITE_GRAY
		0xFF0000, // 4 BRIGHT_RED
		0xA0A0FF, // 5 PALE_PURPLE
		0xA7A7A7, // 6 IRON_GRAY
		0x007C00, // 7 DARK_GREEN
		0xFFFFFF, // 8 WHITE
		0xA4A8B8, // 9 LIGHT_BLUE_GRAY
		0x976D4D, // 10 DIRT_BROWN
		0x707070, // 11 STONE_GRAY
		0x4040FF, // 12 WATER_BLUE
		0x8F7748, // 13 OAK_TAN
		0xFFFCF5, // 14 OFF_WHITE
		0xD87F33, // 15 ORANGE
		0xB24CD8, // 16 MAGENTA
		0x6699D8, // 17 LIGHT_BLUE
		0xE5E533, // 18 YELLOW
		0x7FCC19, // 19 LIME
		0xF27FA5, // 20 PINK
		0x4C4C4C, // 21 GRAY
		0x999999, // 22 LIGHT_GRAY
		0x4C7F99, // 23 CYAN
		0x7F3FB2, // 24 PURPLE
		0x334CB2, // 25 BLUE
		0x664C33, // 26 BROWN
		0x667F33, // 27 GREEN
		0x993333, // 28 RED
		0x191919, // 29 BLACK
		0xFAEE4D, // 30 GOLD
		0x5CDBD5, // 31 DIAMOND_BLUE
		0x4A80FF, // 32 LAPIS_BLUE
		0x00D93A, // 33 EMERALD_GREEN
		0x815631, // 34 SPRUCE_BROWN
		0x700200, // 35 DARK_RED
		0xD1B1A1, // 36 TERRACOTTA_WHITE
		0x9F5224, // 37 TERRACOTTA_ORANGE
		0x95576C, // 38 TERRACOTTA_MAGENTA
		0x706C8A, // 39 TERRACOTTA_LIGHT_BLUE
		0xBA8524, // 40 TERRACOTTA_YELLOW
		0x677535, // 41 TERRACOTTA_LIME
		0xA04D4E, // 42 TERRACOTTA_PINK
		0x392923, // 43 TERRACOTTA_GRAY
		0x876B62, // 44 TERRACOTTA_LIGHT_GRAY
		0x575C5C, // 45 TERRACOTTA_CYAN
		0x7A4958, // 46 TERRACOTTA_PURPLE
		0x4C3E5C, // 47 TERRACOTTA_BLUE
		0x4C3223, // 48 TERRACOTTA_BROWN
		0x4C522A, // 49 TERRACOTTA_GREEN
		0x8E3C2E, // 50 TERRACOTTA_RED
		0x251610, // 51 TERRACOTTA_BLACK
		0xBD3031, // 52 DULL_RED
		0x943F61, // 53 DULL_PINK
		0x5C191D, // 54 DARK_CRIMSON
		0x167E86, // 55 TEAL
		0x3A8E8C, // 56 DARK_AQUA
		0x562C3E, // 57 DARK_DULL_PINK
		0x14B485, // 58 BRIGHT_TEAL
		0x646464, // 59 DEEPSLATE_GRAY
		0xD8AF93, // 60 RAW_IRON_PINK
		0x7FA796, // 61 LICHEN_GREEN
	};

	/**
	 * The shades, by vanilla's brightness id: what a pixel's colour is multiplied by (out of 255). LOWEST never comes
	 * from blocks, so it can't be built.
	 */
	enum Shade {
		LOW(0, 180), NORMAL(1, 220), HIGH(2, 255), LOWEST(3, 135);

		final int id, scale;

		Shade(int id, int scale) {
			this.id = id;
			this.scale = scale;
		}

		/** The shades a block can be built in: lower than its north neighbour, level with it, higher. */
		static final Shade[] BUILDABLE = {LOW, NORMAL, HIGH};

		static Shade of(int id) {
			return values()[id];
		}
	}

	/** The colour vanilla draws for {@code id} in {@code shade}, as RGB (as {@code ColorHelper.scaleRgb}). */
	static int rgb(int id, Shade shade) {
		if (id == 0) return 0;
		int c = BASE[id];
		int r = (c >> 16 & 255) * shade.scale / 255, g = (c >> 8 & 255) * shade.scale / 255, b = (c & 255) * shade.scale / 255;
		return r << 16 | g << 8 | b;
	}

	/** The byte a map stores for this colour and shade. */
	static byte colorByte(int id, Shade shade) {
		return (byte) (id << 2 | shade.id);
	}

	/** "TERRACOTTA_LIGHT_BLUE" as "Terracotta light blue". */
	static String displayName(int id) {
		String n = NAMES[id].toLowerCase().replace('_', ' ');
		return Character.toUpperCase(n.charAt(0)) + n.substring(1);
	}
}
