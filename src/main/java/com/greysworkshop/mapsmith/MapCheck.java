package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.map.MapState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Works out what a map of the art would show right now, the way the game draws one (FilledMapItem.updateColors), from
 * the blocks that are loaded, and compares it with the art. This is the real test of a build: a block can look right
 * and still shade wrong.
 */
final class MapCheck {
	static final byte UNKNOWN = 0, RIGHT = 1, WRONG = 2;

	final Plan plan;
	/** Per pixel: UNKNOWN (not loaded yet), RIGHT or WRONG. */
	final byte[] status;
	/** What the map would show at each pixel (colour byte), for wrong ones. */
	final byte[] seen;
	private int nextColumn;
	/** Results of the last complete pass. */
	int right, wrong, unknown;
	private int passRight, passWrong, passUnknown;
	boolean complete;

	MapCheck(Plan plan) {
		this.plan = plan;
		status = new byte[plan.width() * plan.height()];
		seen = new byte[status.length];
	}

	/** Checks {@code columns} more columns, going round and round the art. */
	void step(ClientWorld world, int columns) {
		int w = plan.width();
		for (int c = 0; c < columns; c++) {
			if (nextColumn == 0) {
				passRight = passWrong = passUnknown = 0;
			}
			column(world, nextColumn);
			if (++nextColumn >= w) {
				nextColumn = 0;
				right = passRight;
				wrong = passWrong;
				unknown = passUnknown;
				complete = true;
			}
		}
	}

	private void column(ClientWorld world, int px) {
		int x = plan.x0 + px;
		BlockPos.Mutable pos = new BlockPos.Mutable();
		double north = 0;
		boolean northKnown = false;
		int h = plan.height(), w = plan.width();
		int[] out = new int[3];
		for (int node = 0; node <= h; node++) {
			int z = plan.z0 + node - 1;
			if (!look(world, x, z, pos, out)) {
				northKnown = false;
				if (node > 0) {
					status[(node - 1) * w + px] = UNKNOWN;
					passUnknown++;
				}
				continue;
			}
			int colorId = out[0], y = out[1], depth = out[2];
			if (node > 0) {
				int i = (node - 1) * w + px;
				int pz = node - 1;
				Shade shade;
				if (colorId == MapPalette.WATER) {
					double f = depth * 0.1 + ((px + pz) & 1) * 0.2;
					shade = f < 0.5 ? Shade.HIGH : f > 0.9 ? Shade.LOW : Shade.NORMAL;
				} else {
					double f = (y - north) * 4.0 / 5 + (((px + pz) & 1) - 0.5) * 0.4;
					shade = f > 0.6 ? Shade.HIGH : f < -0.6 ? Shade.LOW : Shade.NORMAL;
				}
				byte got = MapPalette.colorByte(colorId, shade);
				seen[i] = got;
				if (!northKnown) {
					status[i] = UNKNOWN;
					passUnknown++;
				} else if (got == MapPalette.colorByte(plan.art.color[i], Shade.of(plan.art.shade[i]))) {
					status[i] = RIGHT;
					passRight++;
				} else {
					status[i] = WRONG;
					passWrong++;
				}
			}
			north = y;
			northKnown = true;
		}
	}

	/**
	 * The top block the map sees at x, z: {colour id, its y, water depth}. False if that chunk isn't loaded. Looks down
	 * from above the art as well as from the heightmap, since some servers send wrong heightmaps.
	 */
	private boolean look(ClientWorld world, int x, int z, BlockPos.Mutable pos, int[] out) {
		if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) return false;
		WorldChunk chunk = world.getChunk(x >> 4, z >> 4);
		if (chunk.isEmpty()) return false;
		int bottom = world.getBottomY();
		int y = Math.max(chunk.sampleHeightmap(Heightmap.Type.WORLD_SURFACE, x & 15, z & 15), plan.topY() + 1) + 1;
		BlockState state;
		if (y <= bottom) {
			state = Blocks.BEDROCK.getDefaultState();
		} else {
			do {
				pos.set(x, --y, z);
				state = chunk.getBlockState(pos);
			} while (state.getMapColor(world, pos) == MapColor.CLEAR && y > bottom);
		}
		int depth = 0;
		if (y > bottom && !state.getFluidState().isEmpty()) {
			int d = y - 1;
			BlockState below;
			BlockPos.Mutable p2 = new BlockPos.Mutable();
			do {
				p2.set(x, d--, z);
				below = chunk.getBlockState(p2);
				depth++;
			} while (d > bottom && !below.getFluidState().isEmpty());
			// The game shows the fluid's own block when it's visible (water is the only fluid with its own colour).
			BlockState fluidBlock = state.getFluidState().getBlockState();
			if (!state.isSideSolidFullSquare(world, pos, net.minecraft.util.math.Direction.UP)) state = fluidBlock;
		}
		out[0] = state.getMapColor(world, pos).id;
		out[1] = y;
		out[2] = depth;
		return true;
	}

	/** How a map item compares with the part of the art it covers: {right, wrong, not drawn yet}; null if it covers none. */
	static int[] compare(Plan plan, MapState map) {
		if (map.scale != 0) return null;
		int mx = map.centerX - 64, mz = map.centerZ - 64;
		int ox = mx - plan.x0, oz = mz - plan.z0;
		if (ox < 0 || oz < 0 || ox >= plan.width() || oz >= plan.height() || ox % 128 != 0 || oz % 128 != 0) return null;
		int right = 0, wrong = 0, blank = 0;
		for (int pz = 0; pz < 128; pz++) {
			for (int px = 0; px < 128; px++) {
				byte b = map.colors[px + pz * 128];
				int i = (oz + pz) * plan.width() + ox + px;
				if (b == 0) blank++;
				else if (b == MapPalette.colorByte(plan.art.color[i], Shade.of(plan.art.shade[i]))) right++;
				else wrong++;
			}
		}
		return new int[] {right, wrong, blank};
	}
}
