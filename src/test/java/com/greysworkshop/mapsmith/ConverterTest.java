package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class ConverterTest {
	/** A busy test picture: hue sweep, grey ramp and noise. */
	static Img picture(int w, int h, long seed) {
		Random r = new Random(seed);
		int[] px = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				float hue = (float) x / w, sat = 1 - (float) y / h;
				int c = java.awt.Color.HSBtoRGB(hue, sat, 0.3f + 0.7f * ((x + y) % 97) / 97f);
				if ((x / 16 + y / 16) % 5 == 0) c = r.nextInt(0x1000000);
				px[y * w + x] = 0xFF000000 | c;
			}
		}
		return new Img(w, h, px);
	}

	static boolean[] all() {
		boolean[] u = new boolean[MapPalette.COLORS];
		Arrays.fill(u, true);
		u[0] = false;
		return u;
	}

	/** Vanilla's shading for one column, from block heights (FilledMapItem.updateColors at scale 0). */
	static Shade vanillaShade(int height, int northHeight, int x, int z) {
		double f = (height - northHeight) * 4.0 / (1 + 4) + (((x + z) & 1) - 0.5) * 0.4;
		return f > 0.6 ? Shade.HIGH : f < -0.6 ? Shade.LOW : Shade.NORMAL;
	}

	static void checkLayout(Converter.Result art, Layout layout) {
		for (int x = 0; x < art.width; x++) {
			for (int z = 0; z < art.height; z++) {
				Shade got = vanillaShade(layout.level[x][z + 1], layout.level[x][z], x, z);
				assertEquals(art.shadeAt(x, z), got, "pixel " + x + "," + z);
			}
			for (int i = 0; i <= art.height; i++) {
				assertTrue(layout.level[x][i] >= 0);
				assertTrue(layout.supportFrom[x][i] >= 0 && layout.supportFrom[x][i] <= layout.level[x][i]);
			}
		}
	}

	@Test
	void staircaseShadesComeOutRight() {
		for (Dither d : Dither.values()) {
			ArtSettings s = new ArtSettings();
			s.dither = d;
			Converter.Result art = Converter.convert(picture(128, 128, 1), s, all(), null);
			checkLayout(art, new Layout(art, false, new boolean[MapPalette.COLORS], false));
			assertTrue(art.meanDifference < 12, d + " " + art.meanDifference);
		}
	}

	@Test
	void heightLimitIsKept() {
		Img pic = picture(128, 128, 2);
		double unlimited = Converter.convert(pic, new ArtSettings(), all(), null).meanDifference;
		for (int limit : new int[] {2, 3, 5, 20}) {
			ArtSettings s = new ArtSettings();
			s.maxHeight = limit;
			Converter.Result art = Converter.convert(pic, s, all(), null);
			Layout layout = new Layout(art, false, new boolean[MapPalette.COLORS], false);
			checkLayout(art, layout);
			assertTrue(layout.maxLevel + 1 <= limit, "limit " + limit + " got " + (layout.maxLevel + 1) + " high");
			System.out.printf("limit %d: difference %.2f (no limit %.2f)%n", limit, art.meanDifference, unlimited);
		}
	}

	@Test
	void flatIsLevelAndOneShade() {
		ArtSettings s = new ArtSettings();
		s.mode = ArtSettings.Mode.FLAT;
		Converter.Result art = Converter.convert(picture(256, 128, 3), s, all(), null);
		Layout layout = new Layout(art, true, new boolean[MapPalette.COLORS], false);
		checkLayout(art, layout);
		assertEquals(0, layout.maxLevel);
	}

	@Test
	void staircaseIsCloserThanFlat() {
		ArtSettings flat = new ArtSettings();
		flat.mode = ArtSettings.Mode.FLAT;
		Img p = picture(128, 128, 4);
		double f = Converter.convert(p, flat, all(), null).meanDifference;
		double st = Converter.convert(p, new ArtSettings(), all(), null).meanDifference;
		assertTrue(st < f, "staircase " + st + " flat " + f);
	}

	/** Built north to south, every block of every column touches the ground or the blocks before it. */
	@Test
	void buildsNorthToSouth() {
		boolean[] below = new boolean[MapPalette.COLORS];
		for (int i = 0; i < below.length; i += 4) below[i] = true;
		for (int limit : new int[] {0, 4}) {
			ArtSettings s = new ArtSettings();
			s.maxHeight = limit;
			Converter.Result art = Converter.convert(picture(128, 128, 7), s, all(), null);
			Layout layout = new Layout(art, false, below, false);
			for (int x = 0; x < art.width; x++) {
				assertEquals(0, layout.supportFrom[x][0], "reference block on the ground");
				for (int i = 1; i <= art.height; i++) {
					int[] lv = layout.level[x], sup = layout.supportFrom[x];
					assertTrue(sup[i] == 0 || sup[i] <= lv[i - 1] && sup[i - 1] <= lv[i], "column " + x + " node " + i);
				}
			}
		}
	}

	/** Every block of every column touches the ground or a block placed before it. */
	@Test
	void everyBlockCanBePlaced() {
		boolean[] below = new boolean[MapPalette.COLORS];
		for (int i = 0; i < below.length; i += 3) below[i] = true;
		ArtSettings s = new ArtSettings();
		Converter.Result art = Converter.convert(picture(128, 128, 5), s, all(), null);
		Layout layout = new Layout(art, false, below, true);
		checkLayout(art, layout);
		for (int x = 0; x < art.width; x++) {
			int n = art.height + 1;
			boolean[] reached = new boolean[n];
			for (int i = 0; i < n; i++) reached[i] = layout.supportFrom[x][i] == 0;
			for (boolean changed = true; changed; ) {
				changed = false;
				for (int i = 0; i < n; i++) {
					for (int j : new int[] {i - 1, i + 1}) {
						if (reached[i] || j < 0 || j >= n || !reached[j]) continue;
						if (layout.supportFrom[x][i] <= layout.level[x][j] && layout.supportFrom[x][j] <= layout.level[x][i]) {
							reached[i] = changed = true;
						}
					}
				}
			}
			for (int i = 0; i < n; i++) assertTrue(reached[i], "column " + x + " node " + i);
			for (int i = 1; i < n; i++) {
				if (below[art.colorAt(x, i - 1)] && layout.level[x][i] > 0) assertTrue(layout.supportFrom[x][i] < layout.level[x][i]);
			}
		}
	}

	@Test
	void solidColourPicksItsOwnMapColour() {
		int[] px = new int[128 * 128];
		Arrays.fill(px, 0xFF000000 | MapPalette.rgb(25, Shade.HIGH));
		Converter.Result art = Converter.convert(new Img(128, 128, px), new ArtSettings(), all(), null);
		for (int i = 0; i < px.length; i++) {
			assertEquals(25, art.color[i]);
			assertEquals(Shade.HIGH.id, art.shade[i]);
		}
	}

	@Test
	void framingKeepsAverageColour() {
		Img p = picture(300, 200, 6);
		Img framed = p.frame(128, 128, Img.Fit.FILL, Img.Resample.SMOOTH, 1, 0, 0, 0xFFFFFF);
		assertEquals(128, framed.width);
		Img fit = p.frame(128, 128, Img.Fit.FIT, Img.Resample.SMOOTH, 1, 0, 0, 0x123456);
		assertEquals(0x123456, fit.get(64, 0) & 0xFFFFFF);
		Img sharp = new Img(2, 2, new int[] {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF}).frame(128, 128, Img.Fit.STRETCH, Img.Resample.SHARP, 1, 0, 0, 0);
		assertEquals(0xFF0000, sharp.get(10, 10) & 0xFFFFFF);
		assertEquals(0xFFFFFF, sharp.get(100, 100) & 0xFFFFFF);
	}
}
