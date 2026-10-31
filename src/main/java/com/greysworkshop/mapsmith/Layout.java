package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;

/**
 * Where every block of the art goes, relative to its north-west pixel at the base level: the art block's height, and
 * the support blocks that go under it so every block can be placed.
 *
 * <p>Each column of pixels (north to south) starts with a <b>reference block</b> one block north of the art, just over
 * the edge of the map: vanilla shades a pixel by comparing its block with the one north of it, and for the top row
 * that block is outside the map. Heights are the lowest that give every shade (a valley: each run of steps goes back
 * down to the ground), so staircase art stays as low as it can.
 *
 * <p>Node {@code z + 1} of a column is pixel row {@code z}; node 0 is the reference block.
 */
final class Layout {
	final int width, height;
	/** [x][node]: the block's height above the base. */
	final int[][] level;
	/** [x][node]: the lowest support under the block (== level when none). */
	final int[][] supportFrom;
	final boolean flat;
	int maxLevel;

	/**
	 * {@code needsBelow[colorId]}: that colour's block falls or breaks without a block under it (sand, carpet).
	 * {@code referenceNeedsBelow}: the same, for the reference blocks.
	 */
	Layout(Converter.Result art, boolean flat, boolean[] needsBelow, boolean referenceNeedsBelow) {
		this.flat = flat;
		width = art.width;
		height = art.height;
		level = new int[width][height + 1];
		supportFrom = new int[width][height + 1];
		for (int x = 0; x < width; x++) {
			int[] lv = level[x], sup = supportFrom[x];
			if (!flat) heights(art, x, lv);
			for (int i = 0; i <= height; i++) sup[i] = lv[i];
			for (int i = 1; i <= height; i++) {
				// The higher of two neighbours needs a pillar down to the lower one's level, so they touch.
				int a = lv[i - 1], b = lv[i];
				if (a > b) sup[i - 1] = Math.min(sup[i - 1], b);
				else if (b > a) sup[i] = Math.min(sup[i], a);
			}
			for (int i = 0; i <= height; i++) {
				boolean below = i == 0 ? referenceNeedsBelow : needsBelow[art.colorAt(x, i - 1)];
				if (below && lv[i] > 0 && sup[i] == lv[i]) sup[i] = lv[i] - 1;
				maxLevel = Math.max(maxLevel, lv[i]);
			}
			// The reference block stands on the ground, so each column can be built north to south: every block then
			// touches the one before it (the pillar rule above makes neighbours' spans meet).
			sup[0] = 0;
			connect(lv, sup);
		}
	}

	/** The lowest heights that give each pixel of column {@code x} its shade. */
	static void heights(Converter.Result art, int x, int[] out) {
		int n = art.height;
		int[] left = new int[n + 1], right = new int[n + 1];
		for (int i = 1; i <= n; i++) {
			int s = art.shade[(i - 1) * art.width + x];
			left[i] = s == Shade.HIGH.id ? left[i - 1] + 1 : s == Shade.NORMAL.id ? left[i - 1] : 0;
		}
		for (int i = n - 1; i >= 0; i--) {
			int s = art.shade[i * art.width + x];
			right[i] = s == Shade.LOW.id ? right[i + 1] + 1 : s == Shade.NORMAL.id ? right[i + 1] : 0;
		}
		for (int i = 0; i <= n; i++) out[i] = Math.max(left[i], right[i]);
	}

	/**
	 * Makes sure every block of a column can be reached from the ground by placing one block against another: lengthens
	 * pillars under any block that couldn't be.
	 */
	private static void connect(int[] lv, int[] sup) {
		int n = lv.length;
		while (true) {
			boolean[] reached = new boolean[n];
			// A node's blocks are reached as a whole: they're one pillar, placed from the bottom.
			boolean changed = true;
			for (int i = 0; i < n; i++) if (sup[i] == 0) reached[i] = true;
			while (changed) {
				changed = false;
				for (int i = 0; i < n; i++) {
					if (reached[i]) continue;
					for (int j = i - 1; j <= i + 1; j += 2) {
						if (j < 0 || j >= n || !reached[j]) continue;
						// Side by side if their spans of levels overlap.
						if (sup[i] <= lv[j] && sup[j] <= lv[i]) {
							reached[i] = true;
							changed = true;
							break;
						}
					}
				}
			}
			boolean all = true;
			for (int i = 0; i < n; i++) {
				if (!reached[i]) {
					all = false;
					sup[i]--;
				}
			}
			if (all) return;
		}
	}

	/** Blocks in the art: art blocks, reference blocks and supports. */
	long supportCount() {
		long n = 0;
		for (int x = 0; x < width; x++) for (int i = 0; i <= height; i++) n += level[x][i] - supportFrom[x][i];
		return n;
	}
}
