package com.greysworkshop.mapsmith;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** /mapsmith: everything the studio does, by command. All client-side. */
final class Commands {
	private Commands() {}

	static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("mapsmith")
				.executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					client.send(() -> client.setScreen(new StudioScreen(null)));
					return 1;
				})
				.then(literal("build").executes(c -> {
					Builder.start(MinecraftClient.getInstance());
					return 1;
				}))
				.then(literal("stop").executes(c -> {
					MinecraftClient client = MinecraftClient.getInstance();
					Builder.stop(client, Builder.State.OFF, Text.empty());
					c.getSource().sendFeedback(Text.translatable("mapsmith.builder.stopped"));
					return 1;
				}))
				.then(literal("place").executes(c -> place(c.getSource(), 0, 0))
						.then(argument("mapsEast", IntegerArgumentType.integer(-64, 64))
								.then(argument("mapsSouth", IntegerArgumentType.integer(-64, 64))
										.executes(c -> place(c.getSource(), IntegerArgumentType.getInteger(c, "mapsEast"),
												IntegerArgumentType.getInteger(c, "mapsSouth"))))))
				.then(literal("materials").executes(c -> materials(c.getSource())))
				.then(literal("export").executes(c -> export(c.getSource())))
				.then(literal("grid").executes(c -> {
					MapsmithConfig.INSTANCE.grid = !MapsmithConfig.INSTANCE.grid;
					MapsmithConfig.save();
					c.getSource().sendFeedback(Text.translatable(MapsmithConfig.INSTANCE.grid ? "mapsmith.grid.on" : "mapsmith.grid.off"));
					return 1;
				}))
				.then(literal("ghosts").executes(c -> {
					MapsmithConfig.INSTANCE.ghosts = !MapsmithConfig.INSTANCE.ghosts;
					MapsmithConfig.save();
					c.getSource().sendFeedback(Text.translatable(MapsmithConfig.INSTANCE.ghosts ? "mapsmith.ghosts.on" : "mapsmith.ghosts.off"));
					return 1;
				})));
	}

	private static int place(FabricClientCommandSource source, int east, int south) {
		MinecraftClient client = MinecraftClient.getInstance();
		if (Mapsmith.art() == null) {
			source.sendError(Text.translatable("mapsmith.no_project"));
			return 0;
		}
		Mapsmith.placeHere(client, east, south);
		Project.Placement p = Mapsmith.art().project.placement;
		source.sendFeedback(Text.translatable("mapsmith.placed", p.x, p.y, p.z, p.x + Mapsmith.art().project.settings.pixelsWide() - 1,
				p.z + Mapsmith.art().project.settings.pixelsTall() - 1));
		return 1;
	}

	private static int materials(FabricClientCommandSource source) {
		Plan plan = Mapsmith.plan();
		Art art = Mapsmith.art();
		if (art == null || art.done() == null) {
			source.sendError(Text.translatable("mapsmith.no_project"));
			return 0;
		}
		Plan p = plan != null ? plan : new Plan(art.done().result(), art.done().layout(), art.done().blocks(), art.done().filler(), 0, 0, 0);
		source.sendFeedback(Text.translatable("mapsmith.materials.title", art.project.name).formatted(Formatting.GOLD));
		for (Map.Entry<Block, Long> e : p.materials().entrySet()) {
			source.sendFeedback(Text.literal(" • ").append(e.getKey().getName()).append(Text.literal(" " + Materials.amount(e.getValue())).formatted(Formatting.GRAY)));
		}
		return 1;
	}

	private static int export(FabricClientCommandSource source) {
		Plan plan = Mapsmith.plan();
		Art art = Mapsmith.art();
		if (art == null || art.done() == null) {
			source.sendError(Text.translatable("mapsmith.no_project"));
			return 0;
		}
		try {
			Path dir = MinecraftClient.getInstance().runDirectory.toPath().resolve("schematics");
			Plan p = plan != null ? plan : new Plan(art.done().result(), art.done().layout(), art.done().blocks(), art.done().filler(), 0, 0, 0);
			Path lit = Schematics.litematic(p, art.project.name, dir);
			Schematics.sponge(p, art.project.name, dir);
			Schematics.png(art.done().result(), art.project.dir().resolve("map preview.png"));
			source.sendFeedback(Text.translatable("mapsmith.exported", Text.literal(lit.getParent().toString()).formatted(Formatting.UNDERLINE)
					.styled(s -> s.withClickEvent(new ClickEvent.OpenFile(lit.getParent().toFile())))));
			return 1;
		} catch (IOException e) {
			source.sendError(Text.literal(e.getMessage()));
			return 0;
		}
	}
}
