package com.greysworkshop.mapsmith;

/** How a picture becomes map art. Saved with each project, as JSON. */
final class ArtSettings {
	/** The art's size in maps (each 128×128 blocks). */
	int mapsWide = 1, mapsTall = 1;
	Img.Fit fit = Img.Fit.FILL;
	Img.Resample resample = Img.Resample.SMOOTH;
	double zoom = 1, panX, panY;
	/** What transparent parts of the picture become. */
	int background = 0xFFFFFF;
	double brightness, contrast, saturation = 1;

	Mode mode = Mode.STAIRCASE;
	/** For staircase art: how many blocks high it may get, counting the bottom (0 for no limit). Lower is easier to build. */
	int maxHeight;
	Dither dither = Dither.FLOYD_STEINBERG;
	/** How much of each pixel's error is passed on, 0..1. */
	double ditherStrength = 1;
	/** Error diffusion runs alternate rows right to left, which avoids diagonal streaks. */
	boolean serpentine = true;

	enum Mode {
		/** Every block level: one shade of each colour, the easiest to build (carpets work). */
		FLAT,
		/** Blocks stepped up and down to get all three shades of each colour: three times the colours. */
		STAIRCASE
	}

	int pixelsWide() {
		return mapsWide * 128;
	}

	int pixelsTall() {
		return mapsTall * 128;
	}

	ArtSettings copy() {
		ArtSettings c = new ArtSettings();
		c.mapsWide = mapsWide;
		c.mapsTall = mapsTall;
		c.fit = fit;
		c.resample = resample;
		c.zoom = zoom;
		c.panX = panX;
		c.panY = panY;
		c.background = background;
		c.brightness = brightness;
		c.contrast = contrast;
		c.saturation = saturation;
		c.mode = mode;
		c.maxHeight = maxHeight;
		c.dither = dither;
		c.ditherStrength = ditherStrength;
		c.serpentine = serpentine;
		return c;
	}

	/** Fixes anything out of range (a hand-edited file). */
	void sanitize() {
		mapsWide = Math.max(1, Math.min(16, mapsWide));
		mapsTall = Math.max(1, Math.min(16, mapsTall));
		if (fit == null) fit = Img.Fit.FILL;
		if (resample == null) resample = Img.Resample.SMOOTH;
		if (mode == null) mode = Mode.STAIRCASE;
		if (dither == null) dither = Dither.FLOYD_STEINBERG;
		zoom = Math.max(1, Math.min(8, zoom));
		panX = Math.max(-1, Math.min(1, panX));
		panY = Math.max(-1, Math.min(1, panY));
		brightness = Math.max(-1, Math.min(1, brightness));
		contrast = Math.max(-1, Math.min(1, contrast));
		saturation = Math.max(0, Math.min(2, saturation));
		ditherStrength = Math.max(0, Math.min(1, ditherStrength));
		maxHeight = maxHeight == 1 ? 2 : Math.max(0, Math.min(380, maxHeight));
	}
}
