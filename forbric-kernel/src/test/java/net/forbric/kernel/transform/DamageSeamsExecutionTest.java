/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.api.EnvType;

/**
 * The output of the two injectors that put the damage pipeline's missing positions back, run on a stand-in of NeoForge's
 * {@code actuallyHurt} and {@code Player.hurtServer}, in the order the chain applies them:
 * <ul>
 *   <li>{@link ForgeDamageSeamsInjector}: MinecraftForge's Hurt (before armour; zero ends the hit), Damage (the health
 *       damage after armour) and player-Attack (false ends the hit) are asked again, which is where Tombstone's ghost
 *       immunity, Voodoo Poppet and damage perks live;</li>
 *   <li>{@link VanillaDamageReadInjector}: the first read of the damage parameter is before armour again, so a Fabric
 *       mod's {@code @ModifyVariable(at = LOAD, ordinal = 0)} changes the damage that is armoured (TaCZ).</li>
 * </ul>
 * The hook is the kernel's real {@code KernelLivingDamage}, compiled from {@code src/runtime/java} against stand-ins for
 * the five game and loader types it names. The Fabric mod's variable modifier is applied to the class file the way Mixin
 * applies it: a call after the method's first {@code fload 3}.
 */
@ExecutesInjector({ForgeDamageSeamsInjector.class, VanillaDamageReadInjector.class})
class DamageSeamsExecutionTest {
	private static final Path HOOK_SOURCE = Path.of("src/runtime/java/net/forbric/kernel/runtime/KernelLivingDamage.java");
	private static final String LIVING = "net.minecraft.world.entity.LivingEntity";
	private static final String PLAYER = "net.minecraft.world.entity.player.Player";

	private static final Map<String, String> STAND_INS = Map.ofEntries(
			Map.entry("fixture.Trace", "package fixture; public final class Trace { public static final java.util.List<String> events = new java.util.ArrayList<>(); }"),
			Map.entry("net.minecraft.server.level.ServerLevel", "package net.minecraft.server.level; public class ServerLevel { }"),
			Map.entry("net.minecraft.world.damagesource.DamageSource",
					"package net.minecraft.world.damagesource; public record DamageSource(String type) { }"),
			Map.entry("com.google.common.base.Preconditions", """
					package com.google.common.base;
					public final class Preconditions {
						public static void checkArgument(boolean ok, Object message) {
							if (!ok) throw new IllegalArgumentException(String.valueOf(message));
						}
					}
					"""),
			Map.entry("net.neoforged.neoforge.common.damagesource.DamageContainer", """
					package net.neoforged.neoforge.common.damagesource;

					import net.minecraft.world.damagesource.DamageSource;

					public class DamageContainer {
						private final DamageSource source;
						private float damage;

						public DamageContainer(DamageSource source, float damage) {
							this.source = source;
							this.damage = damage;
						}

						public float getNewDamage() {
							return damage;
						}

						public void setNewDamage(float damage) {
							this.damage = damage;
						}
					}
					"""),
			Map.entry("net.neoforged.neoforge.common.CommonHooks", """
					package net.neoforged.neoforge.common;

					import net.minecraft.world.entity.LivingEntity;
					import net.neoforged.neoforge.common.damagesource.DamageContainer;

					public class CommonHooks {
						public static float onLivingDamagePre(LivingEntity entity, DamageContainer container) {
							return container.getNewDamage();
						}
					}
					"""),
			Map.entry("net.minecraftforge.common.ForgeHooks", """
					package net.minecraftforge.common;

					import fixture.Trace;
					import net.minecraft.world.damagesource.DamageSource;
					import net.minecraft.world.entity.LivingEntity;

					/** MinecraftForge's events, with Tombstone-like listeners: ghost immunity, a Voodoo Poppet, a friendly-fire guard. */
					public class ForgeHooks {
						public static float onLivingHurt(LivingEntity entity, DamageSource source, float amount) {
							Trace.events.add("Hurt " + amount);
							return source.type().equals("ghost") ? 0 : amount;
						}

						public static float onLivingDamage(LivingEntity entity, DamageSource source, float amount) {
							Trace.events.add("Damage " + amount);
							return amount >= entity.health ? 0 : amount;
						}

						public static boolean onPlayerAttack(LivingEntity player, DamageSource source, float amount) {
							Trace.events.add("Attack " + amount);
							return !source.type().equals("friendly");
						}
					}
					"""),
			Map.entry(LIVING, """
					package net.minecraft.world.entity;

					import java.util.Stack;
					import com.google.common.base.Preconditions;
					import fixture.Trace;
					import net.minecraft.server.level.ServerLevel;
					import net.minecraft.world.damagesource.DamageSource;
					import net.neoforged.neoforge.common.CommonHooks;
					import net.neoforged.neoforge.common.damagesource.DamageContainer;

					public class LivingEntity {
						public boolean dead;
						public float health = 20;
						protected final Stack<DamageContainer> damageContainers = new Stack<>();

						public boolean isInvulnerableTo(ServerLevel level, DamageSource source) {
							return source.type().equals("void_immune");
						}

						/** Named by the hook (the second absorption call's argument); this stand-in has no absorption. */
						public float getAbsorptionAmount() {
							return 0;
						}

						protected float getDamageAfterArmorAbsorb(DamageSource source, float damage) {
							Trace.events.add("armour " + damage);
							return damage / 2;
						}

						/** The caller's half: NeoForge pushes the hit's container around actuallyHurt. */
						public void hit(ServerLevel level, DamageSource source, float amount) {
							damageContainers.push(new DamageContainer(source, amount));
							try {
								actuallyHurt(level, source, amount);
							} finally {
								damageContainers.pop();
							}
						}

						/** NeoForge's shape: the amount lives in the container, and the parameter is first read after armour. */
						protected void actuallyHurt(ServerLevel level, DamageSource source, float amount) {
							if (!this.isInvulnerableTo(level, source)) {
								DamageContainer container = this.damageContainers.peek();
								container.setNewDamage(this.getDamageAfterArmorAbsorb(source, container.getNewDamage()));
								CommonHooks.onLivingDamagePre(this, container);
								amount = container.getNewDamage();
								Preconditions.checkArgument(amount >= 0, "negative damage");
								if (amount != 0) this.health -= amount;
							}
						}
					}
					"""),
			Map.entry(PLAYER, """
					package net.minecraft.world.entity.player;

					import net.minecraft.server.level.ServerLevel;
					import net.minecraft.world.damagesource.DamageSource;
					import net.minecraft.world.entity.LivingEntity;

					public class Player extends LivingEntity {
						static final Object CLINIT = new Object();

						public boolean hurtServer(ServerLevel level, DamageSource source, float amount) {
							if (amount == 0) return false;
							hit(level, source, amount);
							return true;
						}
					}
					"""),
			Map.entry("fixture.FabricMod", """
					package fixture;
					public final class FabricMod {
						/** A @ModifyVariable at actuallyHurt's first LOAD of the damage: four more, before armour. */
						public static float modify(float damage) {
							return damage + 4;
						}
					}
					"""));

	private record Game(Map<String, byte[]> original, Map<String, byte[]> seamed) {
	}

	private static Game game(Path work) throws Exception {
		assertTrue(Files.isRegularFile(HOOK_SOURCE), "the game-side hook's source is part of the checkout: " + HOOK_SOURCE.toAbsolutePath());
		Map<String, String> sources = new HashMap<>(STAND_INS);
		sources.put("net/forbric/kernel/runtime/KernelLivingDamage.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> original = InjectorExecution.compile(work, sources);
		Map<String, byte[]> seamed = new HashMap<>(original);
		for (String target : List.of(LIVING, PLAYER)) {
			String internal = target.replace('.', '/');
			byte[] bytes = original.get(internal);
			byte[] seams = InjectorExecution.transform(new ForgeDamageSeamsInjector(), target, bytes, EnvType.SERVER);
			assertNotSame(bytes, seams, "ForgeDamageSeamsInjector on " + target);
			byte[] read = InjectorExecution.transform(new VanillaDamageReadInjector(), target, seams, EnvType.SERVER);
			if (target.equals(LIVING)) assertNotSame(seams, read, "VanillaDamageReadInjector on " + target);
			seamed.put(internal, read);
		}
		return new Game(original, seamed);
	}

	/** Mixin's way with a {@code @ModifyVariable(at = LOAD, ordinal = 0, index = 3)} on actuallyHurt. */
	private static Map<String, byte[]> withFabricModifier(Map<String, byte[]> classes) {
		String internal = LIVING.replace('.', '/');
		ClassNode node = new ClassNode();
		new ClassReader(classes.get(internal)).accept(node, 0);
		MethodNode hurt = node.methods.stream().filter(m -> m.name.equals("actuallyHurt")).findFirst().orElseThrow();
		for (AbstractInsnNode insn : hurt.instructions) {
			if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.FLOAD && load.var == 3) {
				hurt.instructions.insert(load, new MethodInsnNode(Opcodes.INVOKESTATIC, "fixture/FabricMod", "modify", "(F)F", false));
				break;
			}
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		Map<String, byte[]> out = new HashMap<>(classes);
		out.put(internal, writer.toByteArray());
		return out;
	}

	private static String hit(ClassLoader loader, String source, float amount) throws Throwable {
		Object entity = InjectorExecution.construct(loader.loadClass(LIVING));
		InjectorExecution.invoke(entity, "hit", InjectorExecution.construct(loader.loadClass("net.minecraft.server.level.ServerLevel")),
				InjectorExecution.construct(loader.loadClass("net.minecraft.world.damagesource.DamageSource"), source), amount);
		@SuppressWarnings("unchecked")
		List<Object> trace = (List<Object>) InjectorExecution.getStatic(loader.loadClass("fixture.Trace"), "events");
		String seen = trace + " health " + entity.getClass().getField("health").getFloat(entity);
		trace.clear();
		return seen;
	}

	@Test void minecraftForgesHurtAndDamageAreAskedWhereTheyBelong(@TempDir Path work) throws Throwable {
		Game game = game(work);
		ClassLoader loader = InjectorExecution.load(game.seamed());
		assertEquals("", InjectorExecution.verify(game.seamed().get(LIVING.replace('.', '/')), loader));
		assertEquals("[Hurt 8.0, armour 8.0, Damage 4.0] health 16.0", hit(loader, "arrow", 8),
				"Hurt sees the hit before armour, Damage the health damage after it");
		assertEquals("[Hurt 8.0] health 20.0", hit(loader, "ghost", 8),
				"a Hurt listener answering zero ends the hit before armour (ghost immunity)");
		assertEquals("[Hurt 50.0, armour 50.0, Damage 25.0] health 20.0", hit(loader, "arrow", 50),
				"a Damage listener answering zero saves the entity (Voodoo Poppet)");

		assertEquals("[armour 8.0] health 16.0", hit(InjectorExecution.load(game.original()), "ghost", 8),
				"premise: as merged, MinecraftForge's listeners are never asked");
	}

	/**
	 * A merge that kept MinecraftForge's Damage call in NeoForge's body and refused its Hurt (whose answer that body never
	 * reads): the seam asks Hurt, and the body's own Damage call is asked through the kernel. A zero-damage hit goes on
	 * through NeoForge's pipeline, but MinecraftForge returns on it before Damage, so Damage is not asked; every other
	 * hit is asked exactly as the fully seamed body asks it.
	 */
	@Test void aMergedBodysOwnDamageCallSkipsTheZeroHitsMinecraftForgeEndsAtHurt(@TempDir Path work) throws Throwable {
		String check = "Preconditions.checkArgument(amount >= 0, \"negative damage\");";
		Map<String, String> sources = new HashMap<>(STAND_INS);
		assertTrue(sources.get(LIVING).contains(check));
		sources.put(LIVING, sources.get(LIVING).replace(check,
				check + " amount = net.minecraftforge.common.ForgeHooks.onLivingDamage(this, source, amount);"));
		sources.put("net/forbric/kernel/runtime/KernelLivingDamage.java", Files.readString(HOOK_SOURCE));
		Map<String, byte[]> merged = InjectorExecution.compile(work, sources);
		String internal = LIVING.replace('.', '/');
		byte[] seamed = InjectorExecution.transform(new ForgeDamageSeamsInjector(), LIVING, merged.get(internal), EnvType.SERVER);
		assertNotSame(merged.get(internal), seamed, "Hurt is missing, so the repair edits the body");
		Map<String, byte[]> game = new HashMap<>(merged);
		game.put(internal, seamed);
		ClassLoader loader = InjectorExecution.load(game);
		assertEquals("", InjectorExecution.verify(seamed, loader));
		assertEquals("[Hurt 8.0, armour 8.0, Damage 4.0] health 16.0", hit(loader, "arrow", 8));
		assertEquals("[Hurt 8.0] health 20.0", hit(loader, "ghost", 8), "a Hurt answer of zero still ends the hit");
		assertEquals("[Hurt 0.0, armour 0.0] health 20.0", hit(loader, "arrow", 0),
				"a zero-damage hit is not asked Damage, as MinecraftForge's own body returns before it");
		assertEquals("[armour 0.0, Damage 0.0] health 20.0", hit(InjectorExecution.load(merged), "arrow", 0),
				"premise: as merged, Hurt is never asked and the body's own Damage is asked on a zero-damage hit");
	}

	@Test void aFabricModChangingTheFirstReadChangesTheDamageThatIsArmoured(@TempDir Path work) throws Throwable {
		Game game = game(work);
		ClassLoader loader = InjectorExecution.load(withFabricModifier(game.seamed()));
		assertEquals("[Hurt 12.0, armour 12.0, Damage 6.0] health 14.0", hit(loader, "arrow", 8),
				"the mod's +4 lands before armour, as on Fabric");
		Map<String, byte[]> seamsOnly = new HashMap<>(game.seamed());
		seamsOnly.put(LIVING.replace('.', '/'), InjectorExecution.transform(new ForgeDamageSeamsInjector(), LIVING,
				game.original().get(LIVING.replace('.', '/')), EnvType.SERVER));
		assertEquals("[Hurt 8.0, armour 8.0, Damage 8.0] health 12.0", hit(InjectorExecution.load(withFabricModifier(seamsOnly)), "arrow", 8),
				"premise: without the read, the mod's first LOAD is after armour, so its +4 is not armoured");
	}

	@Test void aPlayerAttackMinecraftForgeRefusesEndsTheHit(@TempDir Path work) throws Throwable {
		Game game = game(work);
		ClassLoader loader = InjectorExecution.load(game.seamed());
		assertEquals("", InjectorExecution.verify(game.seamed().get(PLAYER.replace('.', '/')), loader));
		Class<?> player = loader.loadClass(PLAYER);
		Object steve = InjectorExecution.construct(player);
		Object level = InjectorExecution.construct(loader.loadClass("net.minecraft.server.level.ServerLevel"));
		Class<?> source = loader.loadClass("net.minecraft.world.damagesource.DamageSource");
		assertEquals(false, InjectorExecution.invoke(steve, "hurtServer", level, InjectorExecution.construct(source, "friendly"), 6f));
		assertEquals(20f, player.getField("health").getFloat(steve), "the refused attack did nothing");
		assertEquals(true, InjectorExecution.invoke(steve, "hurtServer", level, InjectorExecution.construct(source, "arrow"), 6f));
		assertEquals(17f, player.getField("health").getFloat(steve));
		assertEquals(true, InjectorExecution.invokeStatic(loader.loadClass("net.forbric.kernel.runtime.KernelLivingDamage"),
				"playerSeamInstalled"), "Player's initialiser told the attack forward the seam is in");
	}
}
