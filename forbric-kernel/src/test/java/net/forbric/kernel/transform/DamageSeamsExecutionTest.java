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
 * {@link VanillaDamageReadInjector}'s output, run on a stand-in of NeoForge's {@code actuallyHurt}: the first read of the
 * damage parameter is before armour again, so a Fabric mod's {@code @ModifyVariable(at = LOAD, ordinal = 0)} changes the
 * damage that is armoured (TaCZ). (MinecraftForge's Hurt, Damage and player-Attack seams this test also ran went with
 * MinecraftForge.)
 *
 * <p>The hook is the kernel's real {@code KernelLivingDamage}, compiled from {@code src/runtime/java} against stand-ins for
 * the game and loader types it names. The Fabric mod's variable modifier is applied to the class file the way Mixin
 * applies it: a call after the method's first {@code fload 3}.
 */
@ExecutesInjector(VanillaDamageReadInjector.class)
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
			byte[] read = InjectorExecution.transform(new VanillaDamageReadInjector(), target, bytes, EnvType.SERVER);
			if (target.equals(LIVING)) assertNotSame(bytes, read, "VanillaDamageReadInjector on " + target);
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

	@Test void aFabricModChangingTheFirstReadChangesTheDamageThatIsArmoured(@TempDir Path work) throws Throwable {
		Game game = game(work);
		ClassLoader loader = InjectorExecution.load(withFabricModifier(game.seamed()));
		assertEquals("[armour 12.0] health 14.0", hit(loader, "arrow", 8),
				"the mod's +4 lands before armour, as on Fabric");
		Map<String, byte[]> unread = new HashMap<>(game.seamed());
		unread.put(LIVING.replace('.', '/'), game.original().get(LIVING.replace('.', '/')));
		assertEquals("[armour 8.0] health 16.0", hit(InjectorExecution.load(withFabricModifier(unread)), "arrow", 8),
				"premise: without the read, the mod's first LOAD is after armour, so its +4 changes nothing");
	}

}
