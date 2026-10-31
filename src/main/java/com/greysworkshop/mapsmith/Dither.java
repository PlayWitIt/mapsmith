package com.greysworkshop.mapsmith;

/** The ways leftover colour error is spread, or patterned, so a few colours read as many. */
enum Dither {
	NONE("None", null, 0),
	FLOYD_STEINBERG("Floyd–Steinberg", new int[][] {{1, 0, 7}, {-1, 1, 3}, {0, 1, 5}, {1, 1, 1}}, 16),
	ATKINSON("Atkinson", new int[][] {{1, 0, 1}, {2, 0, 1}, {-1, 1, 1}, {0, 1, 1}, {1, 1, 1}, {0, 2, 1}}, 8),
	JARVIS("Jarvis–Judice–Ninke", new int[][] {{1, 0, 7}, {2, 0, 5}, {-2, 1, 3}, {-1, 1, 5}, {0, 1, 7}, {1, 1, 5}, {2, 1, 3},
			{-2, 2, 1}, {-1, 2, 3}, {0, 2, 5}, {1, 2, 3}, {2, 2, 1}}, 48),
	STUCKI("Stucki", new int[][] {{1, 0, 8}, {2, 0, 4}, {-2, 1, 2}, {-1, 1, 4}, {0, 1, 8}, {1, 1, 4}, {2, 1, 2},
			{-2, 2, 1}, {-1, 2, 2}, {0, 2, 4}, {1, 2, 2}, {2, 2, 1}}, 42),
	BURKES("Burkes", new int[][] {{1, 0, 8}, {2, 0, 4}, {-2, 1, 2}, {-1, 1, 4}, {0, 1, 8}, {1, 1, 4}, {2, 1, 2}}, 32),
	SIERRA("Sierra", new int[][] {{1, 0, 5}, {2, 0, 3}, {-2, 1, 2}, {-1, 1, 4}, {0, 1, 5}, {1, 1, 4}, {2, 1, 2},
			{-1, 2, 2}, {0, 2, 3}, {1, 2, 2}}, 32),
	SIERRA_LITE("Sierra Lite", new int[][] {{1, 0, 2}, {-1, 1, 1}, {0, 1, 1}}, 4),
	BAYER_4("Ordered 4×4", null, 0),
	BAYER_8("Ordered 8×8", null, 0);

	final String label;
	/** {dx, dy, weight} for error diffusion; null for none or ordered. */
	final int[][] kernel;
	final int divisor;

	Dither(String label, int[][] kernel, int divisor) {
		this.label = label;
		this.kernel = kernel;
		this.divisor = divisor;
	}

	boolean ordered() {
		return this == BAYER_4 || this == BAYER_8;
	}

	private static final int[][] B4 = {{0, 8, 2, 10}, {12, 4, 14, 6}, {3, 11, 1, 9}, {15, 7, 13, 5}};
	private static final int[][] B8 = new int[8][8];

	static {
		for (int y = 0; y < 8; y++) {
			for (int x = 0; x < 8; x++) {
				B8[y][x] = 4 * B4[y % 4][x % 4] + new int[] {0, 2, 3, 1}[(y / 4) * 2 + x / 4];
			}
		}
	}

	/** The ordered pattern's threshold at a pixel, -0.5..0.5. */
	double threshold(int x, int y) {
		return this == BAYER_4 ? (B4[y & 3][x & 3] + 0.5) / 16 - 0.5 : (B8[y & 7][x & 7] + 0.5) / 64 - 0.5;
	}
}
