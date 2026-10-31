package com.greysworkshop.mapsmith;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A converted art at its place in the world: what every block position in and around it should hold.
 *
 * <p>Maps cover a fixed grid: each map shows a 128×128 square starting at a multiple of 128, offset by 64 (x and z
 * from 128k−64 to 128k+63), so a map's square is always exactly 8×8 chunks. The art's north-west corner sits on such
 * a corner, and the row of reference blocks runs just north of it, in the last block row of the chunks north of the
 * map.
 */
final class Plan {
	enum Kind {
		/** Not part of the art. */
		NONE,
		/** A pixel's block: must be its colour. */
		ART,
		/** The block north of the top row, that the top row is shaded against: any solid block at its height. */
		REFERENCE,
		/** Holds up another block, or lets it be placed: any solid block. */
		SUPPORT,
		/** Under the lowest blocks: any solid block, so they can be placed (and carpets stay). */
		FOUNDATION,
		/**
		 * Above a pixel's (or reference) block: must be clear, or the map would show (or shade against) what's there.
		 */
		CLEAR
	}

	final Converter.Result art;
	final Layout layout;
	final int x0, y0, z0;
	final Block[] blockFor;
	final Block filler;
	/** How far above the art to keep clear (room to walk on it, too). */
	static final int HEADROOM = 2;

	Plan(Converter.Result art, Layout layout, Block[] blockFor, Block filler, int x0, int y0, int z0) {
		this.art = art;
		this.layout = layout;
		this.blockFor = blockFor;
		this.filler = filler;
		this.x0 = x0;
		this.y0 = y0;
		this.z0 = z0;
	}

	/** The x (or z) where the map square holding {@code coord} begins. */
	static int mapCorner(int coord) {
		return Math.floorDiv(coord + 64, 128) * 128 - 64;
	}

	int width() {
		return art.width;
	}

	int height() {
		return art.height;
	}

	/** Columns are pixels west to east; nodes are the reference block (0) and then pixel rows north to south. */
	boolean inColumns(int x, int z) {
		int px = x - x0, node = z - z0 + 1;
		return px >= 0 && px < art.width && node >= 0 && node <= art.height;
	}

	int level(int px, int node) {
		return layout.level[px][node];
	}

	int topY() {
		return y0 + layout.maxLevel + HEADROOM;
	}

	Kind kind(int x, int y, int z) {
		if (!inColumns(x, z)) return Kind.NONE;
		int px = x - x0, node = z - z0 + 1, lvl = y - y0;
		int top = layout.level[px][node];
		if (lvl == top) return node == 0 ? Kind.REFERENCE : Kind.ART;
		if (lvl > top) return lvl <= layout.maxLevel + HEADROOM ? Kind.CLEAR : Kind.NONE;
		int from = layout.supportFrom[px][node];
		if (lvl >= from) return Kind.SUPPORT;
		if (lvl == -1 && from == 0) return Kind.FOUNDATION;
		return Kind.NONE;
	}

	/** The colour id the block at a pixel must be. */
	int colorAt(int x, int z) {
		return art.colorAt(x - x0, z - z0);
	}

	/** The block to place at a position, or null if nothing's placed there. */
	Block blockToPlace(int x, int y, int z) {
		return switch (kind(x, y, z)) {
			case ART -> blockFor[colorAt(x, z)];
			case REFERENCE, SUPPORT, FOUNDATION -> filler;
			default -> null;
		};
	}

	/** Whether what's in the world at a position is right for the art. */
	boolean satisfied(BlockView world, BlockPos pos, BlockState state) {
		return switch (kind(pos.getX(), pos.getY(), pos.getZ())) {
			case NONE -> true;
			case ART -> !state.isAir() && state.getFluidState().isEmpty()
					&& state.getMapColor(world, pos).id == colorAt(pos.getX(), pos.getZ());
			case REFERENCE, SUPPORT, FOUNDATION -> BlockPalette.solid(state);
			case CLEAR -> state.getMapColor(world, pos) == MapColor.CLEAR && state.getFluidState().isEmpty();
		};
	}

	/** Blocks needed, by block, for the whole art (foundation not counted: it's whatever ground is missing). */
	Map<Block, Long> materials() {
		long[] perColor = new long[MapPalette.COLORS];
		for (byte c : art.color) perColor[c]++;
		Map<Block, Long> out = new LinkedHashMap<>();
		for (int id = 1; id < perColor.length; id++) {
			if (perColor[id] > 0 && blockFor[id] != null) out.merge(blockFor[id], perColor[id], Long::sum);
		}
		out.merge(filler, layout.supportCount() + art.width, Long::sum);
		return out;
	}

	/** The map the pixel at world x, z is on: [column, row] counted from the art's north-west map. */
	int[] mapOf(int x, int z) {
		return new int[] {Math.floorDiv(x - x0, 128), Math.floorDiv(z - z0, 128)};
	}

	int mapsWide() {
		return art.width / 128;
	}

	int mapsTall() {
		return art.height / 128;
	}
}
