package com.greysworkshop.mapsmith;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.Vec3d;

/**
 * Keeping the player going on a long build, hands free: eats when hungry, from food it carries (never anything that
 * poisons, or chorus fruit, which teleports).
 */
final class Survive {
	private Survive() {}

	private static int eatingTicks;

	private static int fightTicks, swingCooldown;

	/**
	 * Fights back: a monster close enough to hit that hit us lately (or any monster right on top of us) gets the best
	 * weapon carried, when the swing is ready. True while fighting: the builder waits.
	 */
	static boolean defend(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || player.getAbilities().creativeMode || client.currentScreen != null) return false;
		var src = player.getRecentDamageSource();
		boolean hitLately = player.hurtTime > 0 || src != null && src.getAttacker() instanceof net.minecraft.entity.mob.Monster && player.age - player.getLastAttackedTime() < 100;
		double reach = player.getEntityInteractionRange();
		net.minecraft.entity.LivingEntity foe = null;
		double best = Double.MAX_VALUE;
		for (var e : client.world.getEntitiesByClass(net.minecraft.entity.LivingEntity.class, player.getBoundingBox().expand(reach + 1),
				e -> e instanceof net.minecraft.entity.mob.Monster && e.isAlive() && !(e instanceof net.minecraft.entity.mob.CreeperEntity))) {
			double d = player.getEyePos().squaredDistanceTo(e.getBoundingBox().getCenter());
			if (d > reach * reach || !player.canSee(e)) continue;
			if (!hitLately && d > 2.0 * 2.0) continue;
			if (d < best) {
				best = d;
				foe = e;
			}
		}
		if (foe == null) return chase(client, player, src);
		if (fightTicks++ == 0) Builder.debug("fighting off {}", foe.getType().getUntranslatedName());
		Builder.look(player, foe.getBoundingBox().getCenter());
		client.options.forwardKey.setPressed(false);
		client.options.jumpKey.setPressed(false);
		int weapon = bestWeapon(player.getInventory());
		if (weapon >= 0) Builder.select(client, player, weapon);
		if (player.getAttackCooldownProgress(0.5f) >= 0.95f) {
			client.interactionManager.attackEntity(player, foe);
			player.swingHand(net.minecraft.util.Hand.MAIN_HAND);
		}
		return true;
	}

	/**
	 * Hit from further off (a skeleton's arrows, a drowned's trident): go after it, if it can be seen and isn't far, and
	 * fight it when in reach. A creeper close by: back away from it. True while doing either.
	 */
	private static boolean chase(MinecraftClient client, ClientPlayerEntity player, net.minecraft.entity.damage.DamageSource src) {
		var creeper = client.world.getEntitiesByClass(net.minecraft.entity.mob.CreeperEntity.class, player.getBoundingBox().expand(4),
				e -> e.isAlive()).stream().findFirst().orElse(null);
		if (creeper != null) {
			if (fightTicks++ == 0) Builder.debug("backing away from a creeper");
			Vec3d away = player.getEntityPos().subtract(creeper.getEntityPos());
			player.setYaw((float) (net.minecraft.util.math.MathHelper.atan2(away.z, away.x) * 180 / Math.PI) - 90);
			client.options.forwardKey.setPressed(true);
			client.options.sprintKey.setPressed(true);
			client.options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround());
			return true;
		}
		var shooter = src == null ? null : src.getAttacker();
		boolean recent = player.age - player.getLastAttackedTime() < 100;
		if (!recent || !(shooter instanceof net.minecraft.entity.mob.Monster) || !(shooter instanceof net.minecraft.entity.LivingEntity foe)
				|| !foe.isAlive() || foe.squaredDistanceTo(player) > 24 * 24 || !player.canSee(foe)) {
			if (fightTicks > 0) {
				client.options.forwardKey.setPressed(false);
				client.options.sprintKey.setPressed(false);
				client.options.jumpKey.setPressed(false);
			}
			fightTicks = 0;
			return false;
		}
		if (fightTicks++ == 0) Builder.debug("going after {}", foe.getType().getUntranslatedName());
		// Never off an edge chasing it: stop at drops.
		Vec3d to = foe.getEntityPos().subtract(player.getEntityPos());
		player.setYaw((float) (net.minecraft.util.math.MathHelper.atan2(to.z, to.x) * 180 / Math.PI) - 90);
		net.minecraft.util.math.BlockPos ahead = net.minecraft.util.math.BlockPos.ofFloored(player.getX() + to.x / to.horizontalLength() * 0.8,
				player.getY() - 0.5, player.getZ() + to.z / to.horizontalLength() * 0.8);
		boolean drop = client.world.getBlockState(ahead).getCollisionShape(client.world, ahead).isEmpty()
				&& client.world.getBlockState(ahead.down()).getCollisionShape(client.world, ahead.down()).isEmpty()
				&& client.world.getBlockState(ahead.down(2)).getCollisionShape(client.world, ahead.down(2)).isEmpty();
		client.options.forwardKey.setPressed(!drop);
		client.options.sprintKey.setPressed(!drop);
		client.options.jumpKey.setPressed(!drop && player.horizontalCollision && player.isOnGround());
		return true;
	}

	/** The slot of the hardest-hitting weapon carried (a sword or an axe), -1 if none. */
	private static int bestWeapon(PlayerInventory inv) {
		int best = -1;
		double bestDamage = 1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			if (!st.isIn(net.minecraft.registry.tag.ItemTags.SWORDS) && !st.isIn(net.minecraft.registry.tag.ItemTags.AXES)) continue;
			double[] dmg = {0};
			st.applyAttributeModifiers(net.minecraft.entity.EquipmentSlot.MAINHAND, (attr, mod) -> {
				if (attr.matches(net.minecraft.entity.attribute.EntityAttributes.ATTACK_DAMAGE)) dmg[0] += mod.value();
			});
			// A sword over an axe that hits a little harder: it swings much faster.
			if (st.isIn(net.minecraft.registry.tag.ItemTags.SWORDS)) dmg[0] += 2;
			if (dmg[0] > bestDamage) {
				bestDamage = dmg[0];
				best = i;
			}
		}
		return best;
	}

	/** Health it stops to heal at, and heals up to before carrying on. */
	private static final float LOW = 8, HEALED = 16;
	private static boolean healing;

	/**
	 * Low on health: stop, eat to fill up (health only comes back with the hunger bar nearly full), and wait for it to
	 * come back. True while healing. {@link #cantHeal} if it can't: no food, and too hungry to heal.
	 */
	static boolean heal(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || player.getAbilities().creativeMode || player.getAbilities().invulnerable) return healing = false;
		float health = player.getHealth();
		if (!healing && health >= LOW || healing && health >= Math.min(HEALED, player.getMaxHealth())) {
			if (healing) Builder.debug("healed up ({})", health);
			return healing = false;
		}
		if (!healing) Builder.debug("low on health ({}): stopping to heal", health);
		healing = true;
		cantHeal = false;
		if (eating(client, player, 20)) return true;
		if (player.getHungerManager().getFoodLevel() < 18 && bestFood(player.getInventory(), 20 - player.getHungerManager().getFoodLevel()) < 0) {
			cantHeal = true;
			healing = false;
		}
		return healing;
	}

	/** Set when it needed to heal and couldn't. */
	static boolean cantHeal;

	/** True while eating: the builder waits. */
	static boolean tick(MinecraftClient client) {
		ClientPlayerEntity player = client.player;
		if (player == null || player.getAbilities().creativeMode) return false;
		// Eat below 15 (natural regeneration stops under 18; under 7 you can't sprint).
		return eating(client, player, 15);
	}

	/**
	 * Eats when the hunger bar is under {@code below}, or carries on eating; true while it is (or something else is: a
	 * client's auto-eat, say, which mustn't be interrupted by switching what's in hand).
	 */
	private static boolean eating(MinecraftClient client, ClientPlayerEntity player, int below) {
		if (eatingTicks == 0 && player.isUsingItem() && player.getActiveItem().contains(DataComponentTypes.FOOD)) return true;
		int food = player.getHungerManager().getFoodLevel();
		if (eatingTicks > 0) {
			eatingTicks++;
			// Done (it's gone from hand or hunger went up), or it's taking too long.
			if (!player.isUsingItem() && eatingTicks > 5 || eatingTicks > 80) {
				client.options.useKey.setPressed(false);
				eatingTicks = 0;
				return false;
			}
			client.options.useKey.setPressed(true);
			return true;
		}
		if (food >= below || !player.canConsume(false) || client.currentScreen != null) return false;
		int slot = bestFood(player.getInventory(), 20 - food);
		if (slot < 0) return false;
		Builder.select(client, player, slot);
		client.options.useKey.setPressed(true);
		client.interactionManager.interactItem(player, net.minecraft.util.Hand.MAIN_HAND);
		eatingTicks = 1;
		Builder.debug("eating {} (hunger {})", player.getInventory().getStack(player.getInventory().getSelectedSlot()), food);
		return true;
	}

	private static boolean surfacing;

	/**
	 * True while swimming up for air: head under water and the air bar going down, or {@code now} (a pause waiting to
	 * happen). Looks up and swims until the head's out.
	 */
	static boolean surface(MinecraftClient client, boolean now) {
		ClientPlayerEntity player = client.player;
		if (player == null || player.getAbilities().creativeMode || player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.WATER_BREATHING)
				|| player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.CONDUIT_POWER)) return stopSurfacing(client);
		boolean under = player.isSubmergedInWater();
		// Start when the air bar is down by a fifth; keep going until the head is out.
		if (!under || !surfacing && !now && player.getAir() > player.getMaxAir() * 4 / 5) return stopSurfacing(client);
		if (!surfacing) Builder.debug("coming up for air (air {})", player.getAir());
		surfacing = true;
		player.setPitch(-60);
		client.options.jumpKey.setPressed(true);
		client.options.forwardKey.setPressed(false);
		client.options.sneakKey.setPressed(false);
		return true;
	}

	private static boolean stopSurfacing(MinecraftClient client) {
		if (surfacing) client.options.jumpKey.setPressed(false);
		surfacing = false;
		return false;
	}

	private static java.util.Set<net.minecraft.item.Item> needed = java.util.Set.of();
	private static int neededAt = -1000;

	/**
	 * With the inventory nearly full, throws out a stack the art doesn't need (dirt and seeds from clearing the
	 * ground, say), so building and gathering can go on. Never tools, food, maps, shulker boxes, or a block the art
	 * still needs.
	 */
	static void tossJunk(MinecraftClient client, Plan plan, int tick) {
		ClientPlayerEntity player = client.player;
		if (player == null || player.getAbilities().creativeMode || player.currentScreenHandler != player.playerScreenHandler) return;
		PlayerInventory inv = player.getInventory();
		int free = 0;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) if (inv.getStack(i).isEmpty()) free++;
		if (free > 1) return;
		if (tick - neededAt > 200) {
			java.util.Set<net.minecraft.item.Item> n = new java.util.HashSet<>();
			for (net.minecraft.block.Block b : Builder.remaining(plan, client.world).keySet()) n.add(b.asItem());
			n.add(plan.filler.asItem());
			needed = n;
			neededAt = tick;
		}
		int worst = -1;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			if (st.isEmpty() || i == inv.getSelectedSlot() || keep(st)) continue;
			if (worst < 0 || st.getCount() > inv.getStack(worst).getCount()) worst = i;
		}
		if (worst < 0) return;
		Builder.debug("throwing away {} (inventory full)", inv.getStack(worst));
		client.interactionManager.clickSlot(player.playerScreenHandler.syncId, worst < 9 ? 36 + worst : worst, 1,
				net.minecraft.screen.slot.SlotActionType.THROW, player);
	}

	private static boolean keep(ItemStack st) {
		if (st.isDamageable() || st.get(DataComponentTypes.FOOD) != null && !bad(st)) return true;
		if (st.isOf(Items.FILLED_MAP) || st.isOf(Items.MAP) || st.isOf(Items.SHEARS) || st.isOf(Items.EXPERIENCE_BOTTLE)) return true;
		if (st.getItem() instanceof net.minecraft.item.BlockItem bi) {
			if (bi.getBlock() instanceof net.minecraft.block.ShulkerBoxBlock || bi.getBlock() instanceof net.minecraft.block.EnderChestBlock) return true;
			return needed.contains(st.getItem());
		}
		// Anything else valuable stays; the common junk of digging goes.
		return !(st.isOf(Items.WHEAT_SEEDS) || st.isOf(Items.FLINT) || st.isOf(Items.STICK) || st.isOf(Items.CLAY_BALL)
				|| st.isOf(Items.SNOWBALL) || st.isOf(Items.GLOWSTONE_DUST) || st.isOf(Items.MELON_SLICE) || st.isOf(Items.STRING)
				|| st.isOf(Items.ROTTEN_FLESH) || st.isOf(Items.BONE) || st.isOf(Items.ARROW) || st.isOf(Items.SPIDER_EYE)
				|| st.isOf(Items.GUNPOWDER));
	}

	static void cancel(MinecraftClient client) {
		if (eatingTicks > 0) client.options.useKey.setPressed(false);
		eatingTicks = 0;
	}

	/** The food that fills the gap best without wasting much; -1 if none. */
	static int bestFood(PlayerInventory inv, int gap) {
		int best = -1, bestScore = Integer.MIN_VALUE;
		for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
			ItemStack st = inv.getStack(i);
			FoodComponent f = st.get(DataComponentTypes.FOOD);
			if (f == null || bad(st)) continue;
			int n = f.nutrition();
			int score = n <= gap ? n * 10 : 100 - (n - gap) * 10;
			if (score > bestScore) {
				bestScore = score;
				best = i;
			}
		}
		return best;
	}

	static boolean bad(ItemStack st) {
		return st.isOf(Items.ROTTEN_FLESH) || st.isOf(Items.SPIDER_EYE) || st.isOf(Items.POISONOUS_POTATO) || st.isOf(Items.PUFFERFISH)
				|| st.isOf(Items.CHORUS_FRUIT) || st.isOf(Items.SUSPICIOUS_STEW) || st.isOf(Items.ENCHANTED_GOLDEN_APPLE)
				|| st.isOf(Items.GOLDEN_APPLE) || st.isOf(Items.CHICKEN);
	}
}
