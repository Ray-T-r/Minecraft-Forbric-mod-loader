/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

/**
 * A hook the merge restores by composition is not also delivered by the kernel.
 *
 * <p>The merge tool restores the other family's leading hook block only where the surviving family's runtime declares
 * no counterpart of the event the hook posts: the kernel's bridges forward the surviving family's event, so with no
 * counterpart there is nothing to forward from. That proof reads the two families' runtimes and cannot see the
 * kernel. The kernel compensates a lost hook two ways -- a bridge that re-posts the event ({@link DeadEventAudit#BRIDGED}),
 * and a repair that posts it where the merge lost it ({@link DeadEventAudit#REPAIRED}, the transformers that redirect
 * calls into kernel runtime classes or build the hook call themselves). Either one, for an event a restored hook now
 * posts natively, would hand a mod that event twice.
 *
 * <p>So this reads every restoration the staged merge reports and searches the kernel's compiled classes and its
 * declared tables for any delivery of what the restored hook posts. A staged base built before the grammar existed
 * reports none, which is why the scanner is also pinned on a hook the kernel does compensate.
 */
class RestoredHookCompensationStagedTest {
	/** The report line of a leading-hook-block restoration and the hook symbol it names. */
	private static final Pattern RESTORED = Pattern.compile(
			"^\\S+ ACCEPTED leading hook block .*: restored ([^.\\s]+)\\.([^(\\s]+)(\\(\\S*)$");

	private record Hook(String owner, String name, String descriptor) {
		@Override public String toString() { return owner + "." + name + descriptor; }
	}

	@Test
	void noRestoredHookIsAlsoDeliveredByTheKernel() throws Exception {
		Path root = TestFixtures.stagedRoot();
		Path report = root.resolve("merged-base/merge-conflicts.txt");
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(report), "staged merge report absent");
		List<Hook> restored = new ArrayList<>();
		for (String line : Files.readAllLines(report)) {
			Matcher m = RESTORED.matcher(line);
			if (m.matches()) restored.add(new Hook(m.group(1), m.group(2), m.group(3)));
		}
		System.out.println("[Forbric/Hooks] leading-hook-block restorations in the staged merge: " + restored);
		List<Path> kernel = kernelClassDirectories();
		List<String> doubled = new ArrayList<>();
		for (Hook hook : restored) {
			Set<String> events = events(root, hook);
			assertFalse(events.isEmpty(), "the merge restored " + hook + " on the strength of the events it posts, "
					+ "and its carrier constructs none");
			for (String event : events) {
				if (DeadEventAudit.BRIDGED.containsKey(event)) doubled.add(hook + ": " + event + " is bridged");
				if (DeadEventAudit.REPAIRED.contains(event)) doubled.add(hook + ": " + event + " is repaired");
			}
			doubled.addAll(deliveries(kernel, hook, events));
		}
		assertEquals(List.of(), doubled, "a hook the merge restored natively is also delivered by the kernel, so its "
				+ "subscribers would receive the event twice");
	}

	@Test
	void theScannerFindsTheKernelsOwnDeliveryOfACompensatedHook() throws Exception {
		// LivingDeathEvent is lost from the merged base and bridged: the kernel calls MinecraftForge's hook itself.
		Hook death = new Hook("net/minecraftforge/event/ForgeEventFactory", "onLivingDeath",
				"(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)Z");
		Path root = TestFixtures.stagedRoot();
		Set<String> events = events(root, death);
		assertTrue(events.contains("net/minecraftforge/event/entity/living/LivingDeathEvent"), events.toString());
		assertTrue(DeadEventAudit.BRIDGED.containsKey("net/minecraftforge/event/entity/living/LivingDeathEvent"));
		assertFalse(deliveries(kernelClassDirectories(), death, events).isEmpty(),
				"the scanner no longer sees the kernel's own call of a hook it compensates");
	}

	/** build/classes/java/main and build/classes/java/runtime: everything the kernel can deliver an event from. */
	private static List<Path> kernelClassDirectories() throws Exception {
		Path main = Path.of(DeadEventAudit.class.getProtectionDomain().getCodeSource().getLocation().toURI());
		Path runtime = Path.of(System.getProperty("forbric.testRuntimeClasses", "build/classes/java/runtime"));
		TestFixtures.require(Fixture.GAME_SIDE, Files.isDirectory(runtime), "game-side classes absent: " + runtime);
		return List.of(main, runtime);
	}

	/** The family event classes the hook's own body in its carrier constructs. */
	private static Set<String> events(Path root, Hook hook) throws IOException {
		String carrier = hook.owner().startsWith("net/minecraftforge/") ? "forge-runtime/forge-runtime.jar"
				: "neoforge-runtime/neoforge-runtime.jar";
		Path jar = root.resolve(carrier);
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged carrier absent: " + jar);
		String family = hook.owner().substring(0, hook.owner().indexOf('/', hook.owner().indexOf('/') + 1) + 1);
		Set<String> out = new TreeSet<>();
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			var entry = zip.getEntry(hook.owner() + ".class");
			if (entry == null) return out;
			ClassNode node = new ClassNode();
			new ClassReader(zip.getInputStream(entry).readAllBytes()).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			for (MethodNode method : node.methods) {
				if (!method.name.equals(hook.name()) || !method.desc.equals(hook.descriptor())) continue;
				for (AbstractInsnNode instruction : method.instructions) {
					if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.startsWith(family)
							&& type.desc.contains("/event/")) out.add(type.desc);
				}
			}
		}
		return out;
	}

	/**
	 * Every place the kernel could deliver what {@code hook} delivers: constructing one of its events, calling the hook
	 * (directly or as a method reference), or naming the hook as strings the way a transformer builds the call.
	 */
	private static List<String> deliveries(List<Path> directories, Hook hook, Set<String> events) throws IOException {
		List<String> out = new ArrayList<>();
		for (Path directory : directories) {
			List<Path> classes;
			try (Stream<Path> walk = Files.walk(directory)) {
				classes = walk.filter(p -> p.toString().endsWith(".class")).toList();
			}
			for (Path file : classes) {
				ClassNode node = new ClassNode();
				new ClassReader(Files.readAllBytes(file)).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
				Set<String> strings = new HashSet<>();
				for (FieldNode field : node.fields) if (field.value instanceof String s) strings.add(s);
				for (MethodNode method : node.methods) {
					if (method.instructions == null) continue;
					for (AbstractInsnNode instruction : method.instructions) {
						if (instruction instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && events.contains(type.desc)) {
							out.add(node.name + "#" + method.name + " constructs " + type.desc);
						} else if (instruction instanceof MethodInsnNode call && call.owner.equals(hook.owner())
								&& call.name.equals(hook.name()) && call.desc.equals(hook.descriptor())) {
							out.add(node.name + "#" + method.name + " calls " + hook);
						} else if (instruction instanceof InvokeDynamicInsnNode indy) {
							for (Object argument : indy.bsmArgs) {
								if (argument instanceof Handle handle && handle.getOwner().equals(hook.owner())
										&& handle.getName().equals(hook.name()) && handle.getDesc().equals(hook.descriptor())) {
									out.add(node.name + "#" + method.name + " refers to " + hook);
								}
							}
						} else if (instruction instanceof LdcInsnNode ldc && ldc.cst instanceof String s) {
							strings.add(s);
						}
					}
				}
				if (strings.contains(hook.name()) && (strings.contains(hook.owner()) || strings.contains(hook.owner().replace('/', '.')))) {
					out.add(node.name + " names " + hook + " as strings");
				}
			}
		}
		return out;
	}
}
