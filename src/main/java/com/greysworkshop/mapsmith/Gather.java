package com.greysworkshop.mapsmith;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FallingBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Gathering, hands free: when nothing it carries or can fetch has the blocks the art needs, it finds them in the world
 * near the art, walks there, mines them with the right tool, picks up what drops, and comes back. Only blocks that drop
 * themselves when mined (or with Silk Touch, if it carries a Silk Touch tool), and stone for cobblestone. Never the
 * art, never next to lava or water, never what it stands on.
 */
final class Gather {
	private Gather() {}

	enum Step { IDLE, FIND, GO, MINE, COLLECT, RETURN, DIG }

	static Step step = Step.IDLE;
	static Text failure;
	private static final Walker walker = new Walker();
	private static Map<Item, Integer> want = new HashMap<>();
	private static final Set<BlockPos> skip = new HashSet<>();
	private static BlockPos target;
	private static Item targetDrop;
	private static int ticks, tries, mined, collectWait;
	private static boolean gotAny;
	private static int findTries;
	/** How far from the art it goes looking. */
	static final int RANGE = 96;

	static boolean active() {
		return step != Step.IDLE;
	}

	/** Stands still for a moment (healing, say), keeping what it was doing. */
	static void pause(MinecraftClient client) {
		if (step != Step.IDLE) walker.stop(client);
		unnudge(client);
	}

	private static void unnudge(MinecraftClient client) {
		if (!nudging) return;
		client.options.forwardKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
		nudging = false;
	}

	static void forget(MinecraftClient client) {
		unnudge(client);
		if (step != Step.IDLE) {
			walker.clear(client);
			if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
		}
		step = Step.IDLE;
		skip.clear();
	}

	// ---- what can be gathered

	/** Blocks that drop something else unless mined with Silk Touch. */
	private static final Set<Block> SILK_ONLY = Set.of(Blocks.GRASS_BLOCK, Blocks.PODZOL, Blocks.MYCELIUM, Blocks.CRIMSON_NYLIUM,
			Blocks.WARPED_NYLIUM, Blocks.STONE, Blocks.DEEPSLATE, Blocks.PACKED_ICE, Blocks.BLUE_ICE, Blocks.SNOW_BLOCK, Blocks.CLAY,
			Blocks.GLOWSTONE, Blocks.MELON, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.MUSHROOM_STEM, Blocks.SCULK,
			Blocks.SEA_LANTERN, Blocks.GRAVEL, Blocks.BOOKSHELF, Blocks.COBWEB);
	/** Natural blocks that drop themselves, by any tool that can mine them. */
	private static final Set<Block> SELF = new HashSet<>();

	static {
		for (Block b : new Block[] {Blocks.DIRT, Blocks.COARSE_DIRT, Blocks.ROOTED_DIRT, Blocks.MUD, Blocks.SAND, Blocks.RED_SAND,
				Blocks.GRANITE, Blocks.DIORITE, Blocks.ANDESITE, Blocks.TUFF, Blocks.CALCITE, Blocks.SANDSTONE, Blocks.RED_SANDSTONE,
				Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.BLACKSTONE, Blocks.BASALT, Blocks.SMOOTH_BASALT,
				Blocks.OBSIDIAN, Blocks.END_STONE, Blocks.TERRACOTTA, Blocks.PUMPKIN, Blocks.MOSS_BLOCK, Blocks.DRIPSTONE_BLOCK,
				Blocks.AMETHYST_BLOCK, Blocks.MAGMA_BLOCK, Blocks.NETHER_WART_BLOCK, Blocks.WARPED_WART_BLOCK, Blocks.SHROOMLIGHT,
				Blocks.PRISMARINE, Blocks.PRISMARINE_BRICKS, Blocks.DARK_PRISMARINE, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
				Blocks.COBBLED_DEEPSLATE, Blocks.MUDDY_MANGROVE_ROOTS, Blocks.PACKED_MUD, Blocks.PURPUR_BLOCK, Blocks.HAY_BLOCK,
				Blocks.BONE_BLOCK, Blocks.SPONGE, Blocks.WET_SPONGE, Blocks.RAW_IRON_BLOCK, Blocks.PALE_MOSS_BLOCK}) {
			SELF.add(b);
		}
		for (Block b : Registries.BLOCK) {
			String id = Registries.BLOCK.getId(b).getPath();
			if (id.endsWith("_terracotta") && !id.contains("glazed") || id.endsWith("_log") || id.endsWith("_wood") || id.endsWith("_stem")
					|| id.endsWith("_hyphae")) {
				if (b != Blocks.MUSHROOM_STEM) SELF.add(b);
			}
		}
	}

	/** The world blocks that give {@code item} when mined, with or without a Silk Touch tool. */
	static List<Block> sources(Item item, boolean silk) {
		List<Block> out = new ArrayList<>();
		if (!(item instanceof BlockItem bi)) return out;
		Block b = bi.getBlock();
		if (SELF.contains(b) || silk && SILK_ONLY.contains(b)) out.add(b);
		// Stone and deepslate give their cobbled kind.
		if (b == Blocks.COBBLESTONE) out.add(Blocks.STONE);
		if (b == Blocks.COBBLED_DEEPSLATE) out.add(Blocks.DEEPSLATE);
		// Gravel drops gravel nine times in ten.
		if (b == Blocks.GRAVEL && !silk) out.add(Blocks.GRAVEL);
		return out;
	}

	/** Whether the builder could gather a block itself (for the palette's "only blocks I can gather"). */
	static boolean gatherable(Block block, boolean silk) {
		return !sources(block.asItem(), silk).isEmpty();
	}

	/** Enough of it to mine within reach of where it's found: a block seen fewer times than this isn't counted on. */
	static final int ENOUGH = 24;

	/** Whether there's enough of a block's sources lying about near the art (see {@link #available}). */
	static boolean gatherableHere(Block block, boolean silk, Map<Block, Integer> available) {
		int n = 0;
		for (Block src : sources(block.asItem(), silk)) n += available.getOrDefault(src, 0);
		return n >= ENOUGH;
	}

	/**
	 * What can be mined near a spot: every block exposed to the air in the loaded chunks within {@link #RANGE}, from
	 * the surface down to 8 below it, counted.
	 */
	static Map<Block, Integer> available(net.minecraft.world.World world, BlockPos center) {
		Map<Block, Integer> out = new HashMap<>();
		BlockPos.Mutable p = new BlockPos.Mutable();
		for (int x = center.getX() - RANGE; x <= center.getX() + RANGE; x++) {
			for (int z = center.getZ() - RANGE; z <= center.getZ() + RANGE; z++) {
				if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
				int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
				for (int y = top - 8; y <= top; y++) {
					p.set(x, y, z);
					BlockState s = world.getBlockState(p);
					if (s.isAir() || !s.getFluidState().isEmpty()) continue;
					boolean exposed = false;
					for (Direction d : Direction.values()) {
						BlockState n = world.getBlockState(p.offset(d));
						if (n.isAir()) {
							exposed = true;
							break;
						}
					}
					if (exposed) out.merge(s.getBlock(), 1, Integer::sum);
				}
			}
		}
		return out;
	}

	static boolean hasSilk(PlayerInventory inv) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (silk(inv.getStack(i))) return true;
		return false;
	}

	private static boolean silk(ItemStack st) {
		for (RegistryEntry<Enchantment> e : st.getEnchantments().getEnchantments()) if (e.matchesKey(Enchantments.SILK_TOUCH)) return true;
		return false;
	}

	// ---- running

	/** Starts gathering the blocks the builder is out of. False if none of them can be gathered. */
	static boolean begin(MinecraftClient client, Plan plan, Set<Block> missing) {
		ClientPlayerEntity player = client.player;
		boolean silk = hasSilk(player.getInventory());
		Map<Block, Integer> need = Builder.remaining(plan, client.world);
		want = new HashMap<>();
		for (Block b : missing) {
			if (sources(b.asItem(), silk).isEmpty()) continue;
			int n = need.getOrDefault(b, 0) + (b == plan.filler ? 64 : 0) - Restock.count(player.getInventory(), b.asItem());
			if (n > 0) want.put(b.asItem(), n);
		}
		failure = null;
		gotAny = false;
		digging = false;
		skip.clear();
		lost.clear();
		if (want.isEmpty()) return false;
		Builder.debug("gather: want {}", want);
		to(Step.FIND);
		return true;
	}

	private static void to(Step s) {
		unnudge(MinecraftClient.getInstance());
		var player = MinecraftClient.getInstance().player;
		Builder.debug("gather: {} -> {} (target {} for {}, mined {}, want {}, at {})", step, s, target, targetDrop, mined, want,
				player == null ? "?" : player.getBlockPos().toShortString());
		step = s;
		ticks = 0;
		tries = 0;
	}

	static void tick(MinecraftClient client, Plan plan) {
		ClientPlayerEntity player = client.player;
		ClientWorld world = client.world;
		ticks++;
		switch (step) {
			case FIND -> {
				if (ticks >= 0) find(client, player, world, plan);
			}
			case GO -> go(client, player, world, plan);
			case MINE -> mine(client, player, world, plan);
			case COLLECT -> collect(client, player, world);
			case RETURN -> back(client, player, world, plan);
			case DIG -> dig(client, player, world, plan);
			default -> {}
		}
	}

	private static int free(PlayerInventory inv) {
		int n = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) n++;
		return n;
	}

	private static void find(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan) {
		PlayerInventory inv = player.getInventory();
		want.entrySet().removeIf(e -> e.getValue() <= 0);
		if (want.isEmpty() || free(inv) == 0) {
			to(Step.RETURN);
			return;
		}
		boolean silk = hasSilk(inv);
		Map<Block, Item> from = new HashMap<>();
		for (Item item : want.keySet()) for (Block b : sources(item, silk)) from.put(b, item);
		// Nearest exposed source, spiralling out a shell at a time so the search stays cheap.
		BlockPos feet = Walker.feet(player);
		BlockPos found = null;
		BlockPos.Mutable p = new BlockPos.Mutable();
		int minY = world.getBottomY() + 1;
		for (int r = 1; r <= RANGE && found == null; r++) {
			double best = Double.MAX_VALUE;
			for (int dx = -r; dx <= r; dx++) {
				for (int dz = -r; dz <= r; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
					int x = feet.getX() + dx, z = feet.getZ() + dz;
					if (!world.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) continue;
					// Keep clear of the art and the strip around it.
					if (x >= plan.x0 - 2 && x < plan.x0 + plan.width() + 2 && z >= plan.z0 - 3 && z < plan.z0 + plan.height() + 2) continue;
					int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
					for (int y = Math.max(minY, Math.min(feet.getY() - 6, top - 8)); y <= Math.max(feet.getY() + 6, top + 4); y++) {
						p.set(x, y, z);
						BlockState s = world.getBlockState(p);
						if (!from.containsKey(s.getBlock()) || skip.contains(p)) continue;
						if (!safe(world, p) || !collectable(world, p)) continue;
						// Never digging down: at most one below our feet, from the top, and not right beside us.
						if (y < feet.getY() - 1) continue;
						if (y == feet.getY() - 1 && (!world.getBlockState(p.up()).isAir() || Math.abs(dx) <= 1 && Math.abs(dz) <= 1)) continue;
						double d = p.getSquaredDistance(feet) + Math.abs(y - feet.getY()) * 8;
						if (d < best) {
							best = d;
							found = p.toImmutable();
						}
					}
				}
			}
		}
		if (found == null && digging) {
			// On the way down: nothing showing yet, so another step.
			to(Step.DIG);
			return;
		}
		if (found == null && ++findTries < 4) {
			// The world round about may still be loading.
			ticks = -40;
			return;
		}
		if (found == null && (digging || want.keySet().stream().noneMatch(Gather::underground))) {
			// Dug for it already, or it's not something that's underground everywhere (sandstone is only under deserts).
			Builder.debug("gather: nothing showing for {} within {}, and nothing to dig for", want.keySet(), RANGE);
			to(Step.RETURN);
			return;
		}
		if (found == null) {
			// Nothing showing anywhere near: dig down to it, a staircase at a time (stone is under everything).
			want.keySet().removeIf(i -> !underground(i));
			Builder.debug("gather: nothing showing for {} within {}: digging down", want.keySet(), RANGE);
			findTries = 0;
			startDig(plan, player);
			return;
		}
		findTries = 0;
		target = found;
		targetDrop = from.get(world.getBlockState(found).getBlock());
		to(Step.GO);
	}

	/** Exposed to air, nothing liquid beside it, not holding up sand or gravel that would land on us. */
	private static boolean safe(ClientWorld world, BlockPos p) {
		boolean exposed = false;
		for (Direction d : Direction.values()) {
			BlockPos n = p.offset(d);
			BlockState s = world.getBlockState(n);
			if (!s.getFluidState().isEmpty()) return false;
			if (s.isOf(Blocks.LAVA) || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE)) return false;
			if (d == Direction.UP && s.getBlock() instanceof FallingBlock && !(world.getBlockState(p).getBlock() instanceof FallingBlock)) return false;
			if (s.isAir() || s.isReplaceable()) exposed = true;
		}
		// Not right over a drop into the dark: the block under it must be solid, or it's a cave ceiling.
		BlockState below = world.getBlockState(p.down());
		return exposed && !below.isAir() && below.getFluidState().isEmpty();
	}

	/**
	 * Whether what drops from {@code p} can be picked up: there's somewhere to stand level with it, right beside it (or
	 * in its place, once it's gone). From anywhere else, it's out of reach of picking up.
	 */
	private static boolean collectable(ClientWorld world, BlockPos p) {
		if (Walker.passable(world, p.up()) && !world.getBlockState(p.down()).getCollisionShape(world, p.down()).isEmpty()) return true;
		for (Direction d : Direction.Type.HORIZONTAL) if (Walker.standable(world, p.offset(d))) return true;
		return false;
	}

	/** Blocks it may pillar up with, to climb out of anywhere it can't walk out of. */
	private static boolean pillarBlock(ItemStack st, Plan plan) {
		return st.isOf(plan.filler.asItem()) || st.isOf(Items.DIRT) || st.isOf(Items.COBBLESTONE) || st.isOf(Items.NETHERRACK)
				|| st.isOf(Items.COBBLED_DEEPSLATE) || st.isOf(Items.ANDESITE) || st.isOf(Items.DIORITE) || st.isOf(Items.GRANITE);
	}

	private static int pillarSlot(PlayerInventory inv, Plan plan) {
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (pillarBlock(inv.getStack(i), plan)) return i;
		return -1;
	}

	/** Plans a walk; it may pillar up (outside the art) when there's no other way. */
	private static boolean walkPlan(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan,
			java.util.function.Predicate<BlockPos> goal, int[] bounds, BlockPos aim) {
		walker.placeUnder = cell -> {
			int slot = pillarSlot(player.getInventory(), plan);
			BlockPos below = cell.down();
			if (slot < 0 || !BlockPalette.solid(world.getBlockState(below)) || Builder.opensScreen(world, below)) return false;
			Builder.select(client, player, slot);
			client.interactionManager.interactBlock(player, Hand.MAIN_HAND,
					new net.minecraft.util.hit.BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false));
			player.swingHand(Hand.MAIN_HAND);
			return true;
		};
		boolean canPillar = pillarSlot(player.getInventory(), plan) >= 0;
		walker.aim = aim;
		boolean ok = walker.plan(world, player, goal, bounds, canPillar ? cell -> !plan.inColumns(cell.getX(), cell.getZ()) : null, null);
		walker.aim = null;
		return ok;
	}

	private static void go(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan) {
		double r = Builder.reach(player) - 0.5;
		if (!world.getBlockState(target).isOf(Block.getBlockFromItem(targetDrop)) && sourceGone(world)) {
			to(Step.FIND);
			return;
		}
		// In reach, and level with it (or at most one below): what drops has to be picked up from there.
		if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(target)) <= r * r && player.isOnGround()
				&& Walker.feet(player).getY() >= target.getY() - 1) {
			walker.stop(client);
			to(Step.MINE);
			return;
		}
		if (walker.arrived() || !walker.moving() && ticks % 20 == 1) {
			if (++tries > 3) {
				skip.add(target);
				walker.clear(client);
				to(Step.FIND);
				return;
			}
			BlockPos t = target;
			double w = r - 0.6;
			BlockPos feet = Walker.feet(player);
			int[] bounds = {Math.min(feet.getX(), t.getX()) - 12, Math.min(feet.getY(), t.getY()) - 8, Math.min(feet.getZ(), t.getZ()) - 12,
					Math.max(feet.getX(), t.getX()) + 12, Math.max(feet.getY(), t.getY()) + 8, Math.max(feet.getZ(), t.getZ()) + 12};
			if (!walkPlan(client, player, world, plan, f -> !f.down().equals(t) && !f.equals(t) && !f.up().equals(t) && f.getY() >= t.getY() - 1
					&& new Vec3d(f.getX() + 0.5, f.getY() + 1.62, f.getZ() + 0.5).squaredDistanceTo(Vec3d.ofCenter(t)) <= w * w
					&& !plan.inColumns(f.getX(), f.getZ()), bounds, t)) {
				skip.add(target);
				to(Step.FIND);
				return;
			}
		}
		if (!walker.tick(client)) walker.clear(client);
	}

	private static boolean sourceGone(ClientWorld world) {
		BlockState s = world.getBlockState(target);
		return s.isAir() || s.isReplaceable();
	}

	private static void mine(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan) {
		BlockState s = world.getBlockState(target);
		if (s.isAir() || s.isReplaceable()) {
			client.interactionManager.cancelBlockBreaking();
			mined++;
			collectWait = 0;
			to(Step.COLLECT);
			return;
		}
		if (ticks > 600 || player.getEyePos().distanceTo(Vec3d.ofCenter(target)) > Builder.reach(player) + 0.5) {
			client.interactionManager.cancelBlockBreaking();
			skip.add(target);
			to(Step.FIND);
			return;
		}
		Direction side = Direction.getFacing(player.getEyePos().subtract(Vec3d.ofCenter(target)));
		Builder.look(player, Vec3d.ofCenter(target));
		if (ticks == 1) {
			if (!tool(client, player, s)) {
				Builder.debug("gather: no tool that gets {} from {}", targetDrop, s);
				skip.add(target);
				to(Step.FIND);
				return;
			}
			client.interactionManager.attackBlock(target, side);
		} else {
			client.interactionManager.updateBlockBreakingProgress(target, side);
		}
		player.swingHand(Hand.MAIN_HAND);
	}

	/** Holds a tool that both mines the block and gets the drop wanted: Silk Touch when that's the only way. */
	private static boolean tool(MinecraftClient client, ClientPlayerEntity player, BlockState s) {
		PlayerInventory inv = player.getInventory();
		Block b = s.getBlock();
		boolean needSilk = SILK_ONLY.contains(b) && b.asItem() == targetDrop;
		int best = -1;
		float bestSpeed = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			if (needSilk && !silk(st)) continue;
			// Stone for cobblestone: Silk Touch would give stone.
			if (!needSilk && SILK_ONLY.contains(b) && b != Blocks.GRAVEL && silk(st)) continue;
			if (st.isDamageable() && st.getMaxDamage() - st.getDamage() < 10) continue;
			if (st.getItem() instanceof BlockItem) continue;
			if (s.isToolRequired() && !st.isSuitableFor(s)) continue;
			float sp = st.isEmpty() ? 1 : st.getMiningSpeedMultiplier(s);
			// Gravel with Silk Touch is fine either way; plain gravel shouldn't waste a silk tool's durability.
			if (sp > bestSpeed) {
				bestSpeed = sp;
				best = i;
			}
		}
		if (best < 0) {
			// Bare hands do for blocks that don't need a tool.
			if (needSilk || s.isToolRequired()) return false;
			int emptySlot = -1;
			for (int i = 0; i < 9; i++) if (inv.getStack(i).isEmpty() || !(inv.getStack(i).isDamageable())) emptySlot = i;
			if (emptySlot >= 0) inv.setSelectedSlot(emptySlot);
			return true;
		}
		Builder.select(client, player, best);
		return true;
	}

	private static boolean nudging;

	/** Nothing in the way of walking straight from one spot to another (at the lower one's height, and head height). */
	private static boolean straightTo(ClientWorld world, Vec3d a, Vec3d b) {
		double y = Math.min(a.y, b.y);
		int steps = (int) Math.ceil(Math.hypot(b.x - a.x, b.z - a.z) * 4) + 1;
		BlockPos.Mutable p = new BlockPos.Mutable();
		BlockPos itsCell = BlockPos.ofFloored(b);
		for (int i = 1; i <= steps; i++) {
			double t = (double) i / steps;
			// Up against the side of the cell it's in is near enough: a block over it there doesn't matter.
			if (BlockPos.ofFloored(a.x + (b.x - a.x) * t, b.y, a.z + (b.z - a.z) * t).equals(itsCell)) break;
			double x = a.x + (b.x - a.x) * t, z = a.z + (b.z - a.z) * t;
			for (double oy : new double[] {0.1, 1.0}) {
				p.set(MathHelper.floor(x), MathHelper.floor(Math.max(y, i < steps / 2 ? a.y : b.y) + oy), MathHelper.floor(z));
				if (!Walker.passable(world, p)) return false;
			}
		}
		return true;
	}
	/** Drops it couldn't get to: not tried again. */
	private static final Set<Integer> lost = new HashSet<>();

	private static void collect(MinecraftClient client, ClientPlayerEntity player, ClientWorld world) {
		collectWait++;

		ItemEntity drop = world.getEntitiesByClass(ItemEntity.class, new Box(target).expand(4, 3, 4), e -> e.getStack().isOf(targetDrop)).stream()
				.filter(e -> !lost.contains(e.getId())).min(java.util.Comparator.comparingDouble(e -> e.squaredDistanceTo(player))).orElse(null);
		if (drop == null) {
			if (collectWait > 6) {
				want.computeIfPresent(targetDrop, (k, v) -> v - 1);
				gotAny = true;
				walker.clear(client);
				to(Step.FIND);
			}
			return;
		}
		if (collectWait > 120) {
			lost.add(drop.getId());
			Builder.debug("gather: gave up picking up {} at {} (age {}, on ground {}), player at {} (feet {}), walker arrived {} moving {}, free slots {}",
					drop.getStack(), drop.getEntityPos(), drop.getItemAge(), drop.isOnGround(), player.getEntityPos(), Walker.feet(player), walker.arrived(),
					walker.moving(), free(player.getInventory()));
			walker.clear(client);
			to(Step.FIND);
			return;
		}
		if (player.getBoundingBox().expand(1, 0.5, 1).intersects(drop.getBoundingBox())) {
			unnudge(client);
			walker.stop(client);
			return;
		}
		// Close, level with it or down in the hole it came out of, and nothing in the way: walk straight at it (stopping
		// at the right block isn't close enough, from its edge; nor is standing on the edge of the hole).
		Vec3d to = drop.getEntityPos().subtract(player.getEntityPos());
		if (Math.hypot(to.x, to.z) < 2.6 && to.y < 0.6 && to.y > -1.6 && (walker.arrived() || nudging)
				&& straightTo(world, player.getEntityPos(), drop.getEntityPos())) {
			walker.clear(client);
			player.setYaw((float) (MathHelper.atan2(to.z, to.x) * 180 / Math.PI) - 90);
			client.options.forwardKey.setPressed(true);
			// Not off an edge after it, unless it's down in a hole.
			client.options.sneakKey.setPressed(to.y > -0.5);
			nudging = true;
			return;
		}
		unnudge(client);
		if (walker.arrived() || collectWait % 30 == 1) {
			BlockPos cell = drop.getBlockPos();
			BlockPos feet = Walker.feet(player);
			// Level with it: items are only picked up from half a block below your feet, so not from the edge of the hole
			// the block came out of.
			walker.plan(world, player, f -> Math.abs(f.getX() - cell.getX()) + Math.abs(f.getZ() - cell.getZ()) <= 1 && f.getY() == cell.getY(),
					new int[] {feet.getX() - 8, feet.getY() - 6, feet.getZ() - 8, feet.getX() + 8, feet.getY() + 6, feet.getZ() + 8});
		}
		walker.tick(client);
	}

	// ---- digging down

	/** Under the ground almost anywhere: worth digging down for. */
	private static boolean underground(Item item) {
		return item == Items.COBBLESTONE || item == Items.COBBLED_DEEPSLATE || item == Items.DIRT || item == Items.ANDESITE
				|| item == Items.DIORITE || item == Items.GRANITE || item == Items.TUFF || item == Items.GRAVEL;
	}

	/** Which way the staircase goes, how many steps so far, and how many times it's had to turn. */
	private static Direction digDir = Direction.NORTH;
	private static int digSteps, digTurns;
	private static boolean digging;
	private static BlockPos digCell;
	private static final int MAX_STEPS = 64;

	private static void startDig(Plan plan, ClientPlayerEntity player) {
		// Away from the art.
		double dx = player.getX() - (plan.x0 + plan.width() / 2.0), dz = player.getZ() - (plan.z0 + plan.height() / 2.0);
		digDir = Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
		digSteps = 0;
		digTurns = 0;
		digging = true;
		digCell = null;
		to(Step.DIG);
	}

	private static void stopDig() {
		digging = false;
		digCell = null;
		to(Step.RETURN);
	}

	/** Too close to the art to dig: a staircase there would cut into it or the ground beside it. */
	private static boolean nearArt(Plan plan, BlockPos p) {
		return p.getX() >= plan.x0 - 6 && p.getX() < plan.x0 + plan.width() + 6 && p.getZ() >= plan.z0 - 7 && p.getZ() < plan.z0 + plan.height() + 6;
	}

	private static boolean wet(ClientWorld world, BlockPos p) {
		if (!world.getFluidState(p).isEmpty()) return true;
		for (Direction d : Direction.values()) {
			BlockState n = world.getBlockState(p.offset(d));
			if (!n.getFluidState().isEmpty() || n.isOf(Blocks.FIRE) || n.isOf(Blocks.MAGMA_BLOCK)) return true;
		}
		return false;
	}

	/**
	 * One staircase step down, away from the art: clears the three blocks ahead (head height, feet, and one down),
	 * sand or gravel that would fall in first, then steps down. Turns at water, lava, a drop or something it can't
	 * mine; gives up after too many turns or steps, or near the bottom of the world. Each step it looks again for what
	 * it wants in the walls.
	 */
	private static void dig(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan) {
		BlockPos feet = Walker.feet(player);
		if (nearArt(plan, feet)) {
			// Walk out from the art first.
			if (walker.arrived() || !walker.moving() && ticks % 20 == 1) {
				if (++tries > 6) {
					stopDig();
					return;
				}
				int[] bounds = {feet.getX() - 24, feet.getY() - 6, feet.getZ() - 24, feet.getX() + 24, feet.getY() + 6, feet.getZ() + 24};
				walkPlan(client, player, world, plan, f -> !nearArt(plan, f), bounds, feet.offset(digDir, 12));
			}
			if (!walker.tick(client)) walker.clear(client);
			return;
		}
		if (digSteps >= MAX_STEPS || feet.getY() <= world.getBottomY() + 8 || feet.getY() <= 0 && digSteps > 0) {
			Builder.debug("gather: dug {} steps down to {}, stopping", digSteps, feet);
			stopDig();
			return;
		}
		if (!player.isOnGround() && !player.isTouchingWater()) return;
		BlockPos ahead = feet.offset(digDir);
		BlockPos[] cells = {ahead.up(), ahead, ahead.down()};
		// Breaking one already: carry on with it.
		if (digCell != null) {
			BlockState s = world.getBlockState(digCell);
			if (s.isAir() || s.isReplaceable() && s.getFluidState().isEmpty()) {
				client.interactionManager.cancelBlockBreaking();
				digCell = null;
			} else {
				if (++ticks > 400) {
					client.interactionManager.cancelBlockBreaking();
					turn("couldn't break " + s);
					return;
				}
				Direction side = Direction.getFacing(player.getEyePos().subtract(Vec3d.ofCenter(digCell)));
				Builder.look(player, Vec3d.ofCenter(digCell));
				client.interactionManager.updateBlockBreakingProgress(digCell, side);
				player.swingHand(Hand.MAIN_HAND);
				return;
			}
		}
		// Sand or gravel over the top of the way ahead comes down first, while we're not under it.
		BlockPos over = ahead.up(2);
		if (world.getBlockState(over).getBlock() instanceof FallingBlock) {
			if (wet(world, over)) {
				turn("water over the way");
				return;
			}
			startBreaking(client, player, world, over);
			return;
		}
		for (BlockPos c : cells) {
			BlockState s = world.getBlockState(c);
			if (wet(world, c)) {
				turn("water or lava at " + c.toShortString());
				return;
			}
			if (s.isAir() || s.isReplaceable() || Walker.passable(world, c)) continue;
			float hardness = s.getHardness(world, c);
			if (hardness < 0 || hardness > 20) {
				turn("can't mine " + s.getBlock());
				return;
			}
			startBreaking(client, player, world, c);
			return;
		}
		// Clear: something to stand on below, and not a drop into a cave.
		BlockPos step = ahead.down();
		BlockState floor = world.getBlockState(step.down());
		if (floor.isAir() || !floor.getFluidState().isEmpty() || floor.getCollisionShape(world, step.down()).isEmpty()) {
			turn("a drop at " + step.down().toShortString());
			return;
		}
		// Step down into it.
		if (feet.equals(step)) return;
		walker.plan(world, player, f -> f.equals(step), new int[] {feet.getX() - 2, feet.getY() - 3, feet.getZ() - 2, feet.getX() + 2, feet.getY() + 1, feet.getZ() + 2});
		if (walker.arrived() && !Walker.feet(player).equals(step)) {
			walker.clear(client);
			if (++tries > 40) turn("can't step down");
			return;
		}
		if (!walker.tick(client)) walker.clear(client);
		if (Walker.feet(player).equals(step)) {
			walker.clear(client);
			digSteps++;
			digTurns = 0;
			// Anything wanted showing in the walls now?
			to(Step.FIND);
		}
	}

	private static void startBreaking(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, BlockPos c) {
		walker.stop(client);
		BlockState s = world.getBlockState(c);
		Item was = targetDrop;
		Block b = s.getBlock();
		// The tool for it: one that keeps the drop if it's something wanted (stone gives cobblestone without Silk Touch).
		targetDrop = want.keySet().stream().filter(i -> sources(i, hasSilk(player.getInventory())).contains(b)).findFirst().orElse(null);
		tool(client, player, s);
		if (targetDrop != null) want.computeIfPresent(targetDrop, (k, v) -> v - 1);
		targetDrop = was;
		Direction side = Direction.getFacing(player.getEyePos().subtract(Vec3d.ofCenter(c)));
		Builder.look(player, Vec3d.ofCenter(c));
		client.interactionManager.attackBlock(c, side);
		player.swingHand(Hand.MAIN_HAND);
		digCell = c.toImmutable();
		ticks = 0;
	}

	private static void turn(String why) {
		Builder.debug("gather: turning the staircase ({})", why);
		digCell = null;
		digDir = digDir.rotateYClockwise();
		if (++digTurns > 3) {
			Builder.debug("gather: no way down from here");
			stopDig();
		}
	}

	/** Back to the art, so the builder carries on. */
	private static void back(MinecraftClient client, ClientPlayerEntity player, ClientWorld world, Plan plan) {
		digging = false;
		BlockPos feet = Walker.feet(player);
		if (plan.inColumns(feet.getX(), feet.getZ()) || distanceToArt(plan, player) < 3) {
			walker.clear(client);
			step = Step.IDLE;
			if (!gotAny) failure = Text.translatable("mapsmith.gather.none", Builder.missingText());
			Builder.debug("gather done: mined {}", mined);
			return;
		}
		if (walker.arrived() || !walker.moving() && ticks % 20 == 1) {
			if (++tries > 8) {
				walker.clear(client);
				step = Step.IDLE;
				failure = Text.translatable("mapsmith.gather.lost");
				return;
			}
			// Toward the nearest edge of the art, a stretch at a time.
			int tx = MathHelper.clamp(feet.getX(), plan.x0, plan.x0 + plan.width() - 1);
			int tz = MathHelper.clamp(feet.getZ(), plan.z0 - 1, plan.z0 + plan.height() - 1);
			int sx = feet.getX() + MathHelper.clamp(tx - feet.getX(), -40, 40), sz = feet.getZ() + MathHelper.clamp(tz - feet.getZ(), -40, 40);
			boolean last = sx == tx && sz == tz;
			int[] bounds = {Math.min(feet.getX(), sx) - 10, Math.min(feet.getY(), plan.y0) - 10, Math.min(feet.getZ(), sz) - 10,
					Math.max(feet.getX(), sx) + 10, Math.max(feet.getY(), plan.topY()) + 10, Math.max(feet.getZ(), sz) + 10};
			int fx = sx, fz = sz;
			if (!walkPlan(client, player, world, plan, f -> last ? plan.inColumns(f.getX(), f.getZ()) || Math.abs(f.getX() - fx) + Math.abs(f.getZ() - fz) <= 2
					: Math.abs(f.getX() - fx) + Math.abs(f.getZ() - fz) <= 6, bounds, new BlockPos(fx, plan.y0, fz))) {
				Builder.debug("gather: no way back from {}", feet);
			}
		}
		if (!walker.tick(client)) walker.clear(client);
	}

	static double distanceToArt(Plan plan, ClientPlayerEntity player) {
		double dx = Math.max(0, Math.max(plan.x0 - player.getX(), player.getX() - (plan.x0 + plan.width())));
		double dz = Math.max(0, Math.max(plan.z0 - 1 - player.getZ(), player.getZ() - (plan.z0 + plan.height())));
		return Math.sqrt(dx * dx + dz * dz);
	}

	static Text status() {
		return switch (step) {
			case GO, MINE -> Text.translatable("mapsmith.gather.mining", Block.getBlockFromItem(targetDrop).getName(), target.getX(), target.getY(), target.getZ());
			case COLLECT -> Text.translatable("mapsmith.gather.collecting");
			case RETURN -> Text.translatable("mapsmith.gather.returning");
			case DIG -> Text.translatable("mapsmith.gather.digging");
			default -> Text.translatable("mapsmith.gather.looking");
		};
	}
}
