package com.greysworkshop.mapsmith;

import net.minecraft.block.Block;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A project with its picture loaded and converted. Converting runs on a background thread; each change of settings
 * starts it again and the one before is dropped.
 */
final class Art {
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Mapsmith converter");
		t.setDaemon(true);
		t.setPriority(Thread.NORM_PRIORITY - 1);
		return t;
	});

	final Project project;
	final Img picture;
	private final AtomicInteger generation = new AtomicInteger();
	/** The latest finished conversion; null until the first is done. */
	private volatile Done done;
	private volatile String error;
	private volatile boolean working;

	/** A conversion and everything made from it, all from the same settings. */
	record Done(int generation, Converter.Result result, Layout layout, Block[] blocks, Block filler) {}

	private Art(Project project, Img picture) {
		this.project = project;
		this.picture = picture;
	}

	static Art open(Project project) throws IOException {
		return new Art(project, ImageLoader.read(project.imagePath()));
	}

	Done done() {
		return done;
	}

	String error() {
		return error;
	}

	boolean working() {
		return working;
	}

	/** Converts again with the project's current settings; {@code then} runs (on the worker) when it's done. */
	void convert(Runnable then) {
		int gen = generation.incrementAndGet();
		ArtSettings s = project.settings.copy();
		boolean[] usable = project.usable();
		Block[] blocks = new Block[MapPalette.COLORS];
		boolean[] below = new boolean[MapPalette.COLORS];
		for (int id = 1; id < blocks.length; id++) {
			blocks[id] = usable[id] ? project.block(id) : null;
			below[id] = blocks[id] != null && BlockPalette.needsBelow(blocks[id]);
		}
		Block filler = project.fillerBlock();
		working = true;
		WORKER.execute(() -> {
			if (generation.get() != gen) return;
			try {
				Img framed = picture.frame(s.pixelsWide(), s.pixelsTall(), s.fit, s.resample, s.zoom, s.panX, s.panY, s.background)
						.adjusted(s.brightness, s.contrast, s.saturation);
				Converter.Result result = Converter.convert(framed, s, usable, () -> generation.get() != gen);
				Layout layout = new Layout(result, s.mode == ArtSettings.Mode.FLAT, below, BlockPalette.needsBelow(filler));
				if (generation.get() != gen) return;
				done = new Done(gen, result, layout, blocks, filler);
				error = null;
			} catch (Converter.Cancelled ignored) {
				return;
			} catch (RuntimeException | OutOfMemoryError e) {
				error = e.getMessage() == null ? e.toString() : e.getMessage();
				LoggerFactory.getLogger("mapsmith").warn("Converting {} failed", project.name, e);
			} finally {
				if (generation.get() == gen) working = false;
			}
			if (then != null) then.run();
		});
	}

	/** Waits for the conversion under way (for commands and tests). */
	Done await(long millis) throws InterruptedException {
		long end = System.currentTimeMillis() + millis;
		while (working && System.currentTimeMillis() < end) Thread.sleep(10);
		return done;
	}

	/** The art placed where its project says, or null if it isn't placed or isn't converted yet. */
	Plan plan() {
		Done d = done;
		Project.Placement p = project.placement;
		if (d == null || p == null) return null;
		return new Plan(d.result(), d.layout(), d.blocks(), d.filler(), p.x, p.y, p.z);
	}
}
