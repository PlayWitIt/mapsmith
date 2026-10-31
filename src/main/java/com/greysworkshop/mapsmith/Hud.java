package com.greysworkshop.mapsmith;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.map.MapState;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.ArrayList;
import java.util.List;

/** A few lines at the top left: how the art is coming along, what the builder's doing, and how a held map compares. */
final class Hud {
	private Hud() {}

	static void render(DrawContext context, RenderTickCounter counter) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (!MapsmithConfig.INSTANCE.hud || client.player == null || client.options.hudHidden || client.getDebugHud().shouldShowDebugHud()) return;
		List<Text> lines = lines(client);
		int y = 4;
		for (Text line : lines) {
			int w = client.textRenderer.getWidth(line);
			context.fill(2, y - 2, 6 + w, y + 9, 0x90000000);
			context.drawTextWithShadow(client.textRenderer, line, 4, y, 0xFFFFFFFF);
			y += 11;
		}
	}

	static List<Text> lines(MinecraftClient client) {
		List<Text> lines = new ArrayList<>();
		Plan plan = Mapsmith.plan();
		Art art = Mapsmith.art();
		if (plan != null && art != null) {
			boolean near = plan.inColumns(client.player.getBlockX(), client.player.getBlockZ())
					|| Builder.state != Builder.State.OFF;
			if (near) {
				lines.add(Text.literal("Mapsmith · ").formatted(Formatting.GOLD).append(Text.literal(art.project.name).formatted(Formatting.WHITE)));
				MapCheck check = Mapsmith.check();
				if (check != null && check.complete) {
					int total = plan.width() * plan.height();
					double pct = 100.0 * check.right / total;
					Formatting f = check.right == total ? Formatting.GREEN : Formatting.YELLOW;
					lines.add(Text.translatable("mapsmith.hud.check", String.format("%.1f", pct), check.wrong, check.unknown).formatted(f));
				}
				if (Builder.state != Builder.State.OFF) {
					Text s = Builder.state == Builder.State.DONE ? Text.translatable("mapsmith.hud.done").formatted(Formatting.GREEN) : Builder.status;
					if (Builder.running()) {
						s = Text.translatable("mapsmith.hud.builder", Builder.placed, String.format("%.1f", Builder.rate())).append(" · ").append(s);
					}
					lines.add(s);
				}
			}
		}
		ItemStack held = client.player.getMainHandStack();
		if (plan != null && held.isOf(Items.FILLED_MAP)) {
			MapIdComponent id = held.get(DataComponentTypes.MAP_ID);
			MapState state = id == null ? null : client.world.getMapState(id);
			int[] cmp = state == null ? null : MapCheck.compare(plan, state);
			if (cmp != null) {
				int drawn = cmp[0] + cmp[1];
				if (drawn == 0) lines.add(Text.translatable("mapsmith.hud.map_blank").formatted(Formatting.GRAY));
				else if (cmp[1] == 0 && cmp[2] == 0) lines.add(Text.translatable("mapsmith.hud.map_perfect").formatted(Formatting.GREEN));
				else lines.add(Text.translatable("mapsmith.hud.map", String.format("%.1f", 100.0 * cmp[0] / drawn), cmp[1], cmp[2]).formatted(Formatting.YELLOW));
			} else if (state != null) {
				lines.add(Text.translatable("mapsmith.hud.map_other").formatted(Formatting.GRAY));
			}
		}
		if (MapsmithConfig.INSTANCE.grid && (plan == null || !plan.inColumns(client.player.getBlockX(), client.player.getBlockZ()))) {
			int mx = Plan.mapCorner(client.player.getBlockX()), mz = Plan.mapCorner(client.player.getBlockZ());
			if (client.player.getMainHandStack().isOf(Items.MAP) || client.player.getMainHandStack().isOf(Items.FILLED_MAP) || Mapsmith.art() != null) {
				lines.add(Text.translatable("mapsmith.hud.square", mx, mz, mx + 127, mz + 127, mx + 64, mz + 64).formatted(Formatting.GRAY));
			}
		}
		return lines;
	}
}
