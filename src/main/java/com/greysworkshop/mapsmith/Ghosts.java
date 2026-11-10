package com.greysworkshop.mapsmith;

import com.greysworkshop.mapsmith.MapPalette.Shade;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the art in the world: each block still to place as a see-through cube of its colour (supports in grey), each
 * block to break outlined in red, and, while you have art loaded, the borders of its maps (gold) at your feet. No chunk
 * lines are ever drawn.
 */
final class Ghosts {
	private Ghosts() {}

	private record Ghost(int x, int y, int z, int rgb, boolean breakIt) {}

	private static List<Ghost> ghosts = List.of();
	private static int stale;
	private static final int MAX = 6000;

	/** Refreshes what to draw (a few times a second; the world doesn't change faster than that matters). */
	static void tick(MinecraftClient client) {
		if (++stale < 4) return;
		stale = 0;
		Plan plan = Mapsmith.plan();
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		if (plan == null || player == null || world == null || !MapsmithConfig.INSTANCE.ghosts) {
			ghosts = List.of();
			return;
		}
		int r = MapsmithConfig.INSTANCE.ghostRange;
		List<Ghost> list = new ArrayList<>();
		BlockPos.Mutable pos = new BlockPos.Mutable();
		int px = player.getBlockX(), py = player.getBlockY(), pz = player.getBlockZ();
		int minY = Math.max(plan.y0 - 1, py - r), maxY = Math.min(plan.topY(), py + r);
		outer:
		for (int x = px - r; x <= px + r; x++) {
			for (int z = pz - r; z <= pz + r; z++) {
				if (!plan.inColumns(x, z) || (x - px) * (x - px) + (z - pz) * (z - pz) > r * r) continue;
				for (int y = minY; y <= maxY; y++) {
					pos.set(x, y, z);
					Builder.Need need = Builder.need(plan, world, pos, world.getBlockState(pos));
					if (need == Builder.Need.NOTHING) continue;
					Plan.Kind kind = plan.kind(x, y, z);
					int rgb = kind == Plan.Kind.ART ? MapPalette.rgb(plan.colorAt(x, z), Shade.HIGH) : 0x9A9A9A;
					list.add(new Ghost(x, y, z, rgb, need == Builder.Need.BREAK));
					if (list.size() >= MAX) break outer;
				}
			}
		}
		ghosts = list;
	}

	static void render(WorldRenderContext context) {
		MinecraftClient client = MinecraftClient.getInstance();
		Plan plan = Mapsmith.plan();
		if (client.world == null || client.player == null) return;
		MatrixStack matrices = context.matrices();
		if (matrices == null) return;
		Vec3d cam = client.gameRenderer.getCamera().getCameraPos();
		VertexConsumerProvider consumers = context.consumers();
		VertexConsumerProvider.Immediate own = null;
		if (consumers == null) {
			own = client.getBufferBuilders().getEntityVertexConsumers();
			consumers = own;
		}
		List<Ghost> list = ghosts;
		VertexConsumer lines = consumers.getBuffer(RenderLayers.lines());
		MatrixStack.Entry entry = matrices.peek();
		for (Ghost g : list) {
			if (g.breakIt) box(lines, entry, g.x - cam.x, g.y - cam.y, g.z - cam.z, 1, 1, 1, 0xFFFF2020, 3f);
			else box(lines, entry, g.x + 0.12 - cam.x, g.y + 0.12 - cam.y, g.z + 0.12 - cam.z, 0.76, 0.76, 0.76, 0xC0000000 | g.rgb, 1.5f);
		}
		if (MapsmithConfig.INSTANCE.grid && plan != null) grid(lines, entry, client.player, plan, cam);
		if (own != null) own.draw(RenderLayers.lines());
		if (list.isEmpty()) return;
		VertexConsumer quads = consumers.getBuffer(RenderLayers.debugQuads());
		for (Ghost g : list) {
			if (g.breakIt) continue;
			cube(quads, entry, (float) (g.x - cam.x), (float) (g.y - cam.y), (float) (g.z - cam.z), 0x60000000 | g.rgb, 0.12f);
		}
		if (own != null) own.draw(RenderLayers.debugQuads());
	}

	/** Map borders (gold) and the art's own outline near the player, just above their feet; only map borders, never chunks. */
	private static void grid(VertexConsumer lines, MatrixStack.Entry entry, ClientPlayerEntity player, Plan plan, Vec3d cam) {
		double y = Math.floor(player.getY()) + 0.02 - cam.y;
		int px = player.getBlockX(), pz = player.getBlockZ();
		int range = 48;
		for (int c = Math.floorDiv(px - range + 64, 128) * 128 - 64; c <= px + range; c += 128) {
			if (c < px - range) continue;
			line(lines, entry, c - cam.x, y, pz - range - cam.z, c - cam.x, y, pz + range - cam.z, 0xFFFFC030, 2.5f);
		}
		for (int c = Math.floorDiv(pz - range + 64, 128) * 128 - 64; c <= pz + range; c += 128) {
			if (c < pz - range) continue;
			line(lines, entry, px - range - cam.x, y, c - cam.z, px + range - cam.x, y, c - cam.z, 0xFFFFC030, 2.5f);
		}
		if (plan != null) {
			// The art's outline, and the reference row north of it.
			double top = plan.y0 + plan.layout.maxLevel + 1;
			box(lines, entry, plan.x0 - cam.x, plan.y0 - cam.y, plan.z0 - cam.z, plan.width(), top - plan.y0, plan.height(), 0xFF40FF60, 2f);
			box(lines, entry, plan.x0 - cam.x, plan.y0 - cam.y, plan.z0 - 1 - cam.z, plan.width(), 1, 1, 0xFF40C0FF, 2f);
		}
	}

	private static void line(VertexConsumer lines, MatrixStack.Entry entry, double x1, double y1, double z1, double x2, double y2, double z2, int argb, float width) {
		Vector3f n = new Vector3f((float) (x2 - x1), (float) (y2 - y1), (float) (z2 - z1)).normalize();
		lines.vertex(entry, (float) x1, (float) y1, (float) z1).color(argb).normal(entry, n).lineWidth(width);
		lines.vertex(entry, (float) x2, (float) y2, (float) z2).color(argb).normal(entry, n).lineWidth(width);
	}

	private static void box(VertexConsumer lines, MatrixStack.Entry entry, double x, double y, double z, double w, double h, double d, int argb, float width) {
		double x2 = x + w, y2 = y + h, z2 = z + d;
		line(lines, entry, x, y, z, x2, y, z, argb, width);
		line(lines, entry, x, y, z2, x2, y, z2, argb, width);
		line(lines, entry, x, y2, z, x2, y2, z, argb, width);
		line(lines, entry, x, y2, z2, x2, y2, z2, argb, width);
		line(lines, entry, x, y, z, x, y, z2, argb, width);
		line(lines, entry, x2, y, z, x2, y, z2, argb, width);
		line(lines, entry, x, y2, z, x, y2, z2, argb, width);
		line(lines, entry, x2, y2, z, x2, y2, z2, argb, width);
		line(lines, entry, x, y, z, x, y2, z, argb, width);
		line(lines, entry, x2, y, z, x2, y2, z, argb, width);
		line(lines, entry, x, y, z2, x, y2, z2, argb, width);
		line(lines, entry, x2, y, z2, x2, y2, z2, argb, width);
	}

	private static void cube(VertexConsumer b, MatrixStack.Entry e, float x, float y, float z, int argb, float inset) {
		float x0 = x + inset, y0 = y + inset, z0 = z + inset, x1 = x + 1 - inset, y1 = y + 1 - inset, z1 = z + 1 - inset;
		quad(b, e, argb, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1);
		quad(b, e, argb, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0);
		quad(b, e, argb, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0);
		quad(b, e, argb, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1);
		quad(b, e, argb, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0);
		quad(b, e, argb, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1);
	}

	private static void quad(VertexConsumer b, MatrixStack.Entry e, int argb, float ax, float ay, float az, float bx, float by, float bz,
			float cx, float cy, float cz, float dx, float dy, float dz) {
		b.vertex(e, ax, ay, az).color(argb);
		b.vertex(e, bx, by, bz).color(argb);
		b.vertex(e, cx, cy, cz).color(argb);
		b.vertex(e, dx, dy, dz).color(argb);
	}
}
