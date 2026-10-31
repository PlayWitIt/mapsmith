package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;

import java.util.function.BooleanSupplier;

/**
 * Turns a framed picture into map pixels: a colour id and shade for each, picked from the colours that have a block,
 * with dithering. In staircase mode each pixel's shade is a step up, level or down from the block north of it, and a
 * height limit is kept by never letting a column's steps span more than that.
 */
final class Converter {
	private Converter() {}

	/** The converted art. */
	static final class Result {
		final int width, height;
		final byte[] color, shade;
		/** The framed picture it was made from. */
		final Img source;
		/** How far the art's colours are from the picture's on average (OKLab ×100: under 3 is very close). */
		double meanDifference;
		int colorsUsed;

		Result(int width, int height, Img source) {
			this.width = width;
			this.height = height;
			this.source = source;
			color = new byte[width * height];
			shade = new byte[width * height];
		}

		int colorAt(int x, int y) {
			return color[y * width + x];
		}

		Shade shadeAt(int x, int y) {
			return Shade.of(shade[y * width + x]);
		}

		/** The pixel as the map will draw it, RGB. */
		int rgb(int x, int y) {
			return MapPalette.rgb(colorAt(x, y), shadeAt(x, y));
		}
	}

	/** The conversion was cancelled (settings changed again before it finished). */
	static final class Cancelled extends RuntimeException {
		Cancelled() {
			super(null, null, false, false);
		}
	}

	/**
	 * Converts {@code framed} (already the art's size). {@code usable[id]}: colour id has a block. {@code cancel} is
	 * checked now and then; when it says so, this throws {@link Cancelled}.
	 */
	static Result convert(Img framed, ArtSettings s, boolean[] usable, BooleanSupplier cancel) {
		int w = framed.width, h = framed.height;
		boolean stairs = s.mode == ArtSettings.Mode.STAIRCASE;
		Shade[] shades = stairs ? Shade.BUILDABLE : new Shade[] {Shade.NORMAL};

		// The candidates: every usable colour in every shade it can be built in.
		int n = 0;
		for (int id = 1; id < MapPalette.COLORS; id++) if (usable[id] && id != MapPalette.WATER) n += shades.length;
		if (n == 0) throw new IllegalArgumentException("No colours to use");
		float[] lab = new float[n * 3];
		int[] candColor = new int[n], candShade = new int[n];
		int k = 0;
		for (int id = 1; id < MapPalette.COLORS; id++) {
			if (!usable[id] || id == MapPalette.WATER) continue;
			for (Shade shade : shades) {
				ColorMath.rgbToLab(MapPalette.rgb(id, shade), lab, k * 3);
				candColor[k] = id;
				candShade[k] = shade.id;
				k++;
			}
		}

		// The picture in OKLab, with room for the error passed on.
		float[] img = new float[w * h * 3];
		for (int i = 0; i < w * h; i++) ColorMath.rgbToLab(framed.argb[i], img, i * 3);
		float[] orig = img.clone();

		Result result = new Result(w, h, framed);
		// Staircase height limit: the heights (0..top) each column's current block could still be at. A step up can be by
		// any amount, as can a step down, so only a climb (or drop) longer than the limit allows can't be built.
		int top = stairs && s.maxHeight > 0 ? Math.max(1, s.maxHeight - 1) : Integer.MAX_VALUE;
		int[] lo = new int[w], hi = new int[w];
		java.util.Arrays.fill(hi, top);
		int[][] kernel = s.dither.kernel;
		double strength = s.ditherStrength;
		// Ordered dithering nudges the target by up to about one gap between neighbouring map colours in lightness.
		double orderedAmount = 0.09 * strength;
		boolean[] used = new boolean[MapPalette.COLORS];
		double totalDiff = 0;

		for (int y = 0; y < h; y++) {
			if ((y & 15) == 0 && cancel != null && cancel.getAsBoolean()) throw new Cancelled();
			boolean reverse = kernel != null && s.serpentine && (y & 1) == 1;
			for (int step = 0; step < w; step++) {
				int x = reverse ? w - 1 - step : step;
				int i = (y * w + x) * 3;
				double L = img[i], A = img[i + 1], B = img[i + 2];
				if (s.dither.ordered()) L += s.dither.threshold(x, y) * orderedAmount;
				// Keep runaway error from smearing across the picture.
				L = Math.max(-0.05, Math.min(1.05, L));
				A = Math.max(-0.5, Math.min(0.5, A));
				B = Math.max(-0.5, Math.min(0.5, B));

				int best = -1;
				double bestD = Double.MAX_VALUE;
				for (int c = 0; c < n; c++) {
					if (top != Integer.MAX_VALUE) {
						if (candShade[c] == Shade.HIGH.id && lo[x] + 1 > top) continue;
						if (candShade[c] == Shade.LOW.id && hi[x] - 1 < 0) continue;
					}
					double dl = L - lab[c * 3], da = A - lab[c * 3 + 1], db = B - lab[c * 3 + 2];
					double d = dl * dl + da * da + db * db;
					if (d < bestD) {
						bestD = d;
						best = c;
					}
				}
				int p = y * w + x;
				result.color[p] = (byte) candColor[best];
				result.shade[p] = (byte) candShade[best];
				used[candColor[best]] = true;
				if (top != Integer.MAX_VALUE) {
					if (candShade[best] == Shade.HIGH.id) {
						lo[x]++;
						hi[x] = top;
					} else if (candShade[best] == Shade.LOW.id) {
						hi[x]--;
						lo[x] = 0;
					}
				}
				double el = orig[i] - lab[best * 3], ea = orig[i + 1] - lab[best * 3 + 1], eb = orig[i + 2] - lab[best * 3 + 2];
				totalDiff += Math.sqrt(el * el + ea * ea + eb * eb);

				if (kernel == null || strength <= 0) continue;
				double rl = (img[i] - lab[best * 3]) * strength, ra = (img[i + 1] - lab[best * 3 + 1]) * strength,
						rb = (img[i + 2] - lab[best * 3 + 2]) * strength;
				for (int[] e : kernel) {
					int nx = x + (reverse ? -e[0] : e[0]), ny = y + e[1];
					if (nx < 0 || nx >= w || ny >= h) continue;
					int j = (ny * w + nx) * 3;
					float f = (float) e[2] / s.dither.divisor;
					img[j] += (float) (rl * f);
					img[j + 1] += (float) (ra * f);
					img[j + 2] += (float) (rb * f);
				}
			}
		}
		result.meanDifference = totalDiff / (w * h) * 100;
		for (boolean u : used) if (u) result.colorsUsed++;
		return result;
	}
}
