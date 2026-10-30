/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.fabricmc.api.EnvType;

/**
 * {@link DuplicateLambdaPruneInjector}'s output, run: a merged class carrying two bodies named {@code lambda$load$0} —
 * the chain its {@code load} runs, and the other ecosystem's, which nothing reaches — defines with one, and a lookup by
 * that name (what a descriptor-less mixin selector does) finds the body {@code load} actually runs. As merged it found
 * two, the orphan first, which is where Lithostitched's {@code @ModifyExpressionValue} landed.
 *
 * <p>javac cannot write two lambdas with one name, so the stand-in is compiled with the orphan as an ordinary method
 * and renamed into the duplicate in the class file, ahead of the live one as the merge left it.
 */
@ExecutesInjector(DuplicateLambdaPruneInjector.class)
@ResourceLock("system-properties")
@org.junit.jupiter.api.extension.ExtendWith(net.forbric.kernel.DroppedLambdaRecordRestore.class)
class DuplicateLambdaPruneInjectorExecutionTest {
	private static final String TASK = "net.minecraft.resources.ResourceManagerRegistryLoadTask";
	private static final String TASK_INTERNAL = TASK.replace('.', '/');
	private static final String LAMBDA = "lambda$load$0";

	private static final Map<String, String> STAND_INS = Map.of(TASK, """
			package net.minecraft.resources;

			import java.util.function.Function;

			public class ResourceManagerRegistryLoadTask {
				/** MinecraftForge's body, which the merge kept: its lambda is lambda$load$0(String). */
				public static String load(String element) {
					Function<String, String> decode = e -> e + " decoded through ConditionCodec";
					return decode.apply(element);
				}

				/** Vanilla's chain, renamed lambda$load$0 in the class file: nothing calls it. */
				private static String vanillaChain(String element, int depth) {
					return element + " decoded by vanilla at " + depth;
				}

				/** A lambda whose name nothing else shares: never touched. */
				public static Runnable unrelated() {
					return () -> { };
				}
			}
			""");

	@AfterEach void reset() {
		System.clearProperty(DuplicateLambdaPruneInjector.PROPERTY);
	}

	/** The merged shape: the orphaned chain under the live lambda's name, declared first. */
	private static Map<String, byte[]> merged(Path work) throws Exception {
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, STAND_INS));
		ClassNode node = new ClassNode();
		new ClassReader(classes.get(TASK_INTERNAL)).accept(node, 0);
		MethodNode orphan = node.methods.stream().filter(m -> m.name.equals("vanillaChain")).findFirst().orElseThrow();
		orphan.name = LAMBDA;
		orphan.access |= Opcodes.ACC_SYNTHETIC;
		node.methods.remove(orphan);
		node.methods.add(0, orphan);
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		classes.put(TASK_INTERNAL, writer.toByteArray());
		return classes;
	}

	private static List<Method> byName(Class<?> type) {
		return Arrays.stream(type.getDeclaredMethods()).filter(m -> m.getName().equals(LAMBDA)).toList();
	}

	@Test void aSelectorByNameFindsOnlyTheBodyTheClassRuns(@TempDir Path work) throws Throwable {
		Map<String, byte[]> original = merged(work);
		byte[] pruned = InjectorExecution.transform(new DuplicateLambdaPruneInjector(), TASK, original.get(TASK_INTERNAL), EnvType.SERVER);
		assertNotSame(original.get(TASK_INTERNAL), pruned, "the merged class carried a duplicate");
		Map<String, byte[]> classes = new HashMap<>(original);
		classes.put(TASK_INTERNAL, pruned);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(pruned, loader));

		Class<?> task = loader.loadClass(TASK);
		assertEquals("stone decoded through ConditionCodec", InjectorExecution.invokeStatic(task, "load", "stone"), "the kept chain still runs");
		List<Method> named = byName(task);
		assertEquals(1, named.size(), "one body answers to the name: " + named);
		named.get(0).setAccessible(true);
		assertEquals("stone decoded through ConditionCodec", named.get(0).invoke(null, "stone"),
				"and it is the one load() runs, so an injector bound by name changes what the game does");
		assertNotNull(InjectorExecution.invokeStatic(task, "unrelated"), "a lambda nothing shares a name with is kept");
		assertTrue(DuplicateLambdaPruneInjector.droppedDescriptors(TASK_INTERNAL, LAMBDA).contains("(Ljava/lang/String;I)Ljava/lang/String;"),
				"the drop is recorded for the diagnosis");

		Class<?> stock = InjectorExecution.load(original).loadClass(TASK);
		assertEquals(2, byName(stock).size(), "premise: as merged, the name is ambiguous");
		ClassNode mergedNode = new ClassNode();
		new ClassReader(original.get(TASK_INTERNAL)).accept(mergedNode, ClassReader.SKIP_CODE);
		assertEquals("(Ljava/lang/String;I)Ljava/lang/String;",
				mergedNode.methods.stream().filter(m -> m.name.equals(LAMBDA)).findFirst().orElseThrow().desc,
				"premise: and the first body by that name, the one a selector binds, is the orphan");
		assertSame(pruned, InjectorExecution.transform(new DuplicateLambdaPruneInjector(), TASK, pruned, EnvType.SERVER),
				"no duplicate is left, so the class comes back byte for byte");
	}

	@Test void switchedOffTheOrphanStays(@TempDir Path work) throws Exception {
		byte[] bytes = merged(work).get(TASK_INTERNAL);
		System.setProperty(DuplicateLambdaPruneInjector.PROPERTY, "off");
		assertSame(bytes, InjectorExecution.transform(new DuplicateLambdaPruneInjector(), TASK, bytes, EnvType.SERVER));
	}
}
