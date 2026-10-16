/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.transform.ForgeModelDataFlowTransformer;
import net.forbric.kernel.transform.InjectorExecution;

/**
 * Which injectors {@link MixinForgeModelDataEdge} gives the edge twin, read over the weave fixture's mesher as
 * {@link ForgeModelDataFlowTransformer} leaves it: only an {@code @Inject} at the overload's start whose count, locals and
 * parameters a second target cannot change the meaning of. The weave itself is ForgeModelDataFlowWeaveTest.
 */
@ResourceLock("system-properties")
class MixinForgeModelDataEdgeTest {
	private static final Path FIXTURE = Path.of("src/test/resources/weave/modeldataedge");
	private static final String MESHER = "net/minecraft/client/renderer/pebble/PebbleMesher";
	private static final String OVERLOAD = "mesh(Ljava/util/List;FLnet/minecraft/client/renderer/block/BlockAndTintGetter;"
			+ "Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;"
			+ "Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;JLnet/minecraftforge/client/model/data/ModelData;)V";
	private static final String ARGS = "List<String> out, float shade, BlockAndTintGetter level, BlockPos pos, BlockState state, "
			+ "BlockStateModel model, long seed, ModelData data";

	@TempDir static Path work;
	private static Map<String, byte[]> classes;

	@BeforeAll static void compile() throws Exception {
		Map<String, String> sources = new HashMap<>();
		try (var walk = Files.walk(FIXTURE)) {
			for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
				String rel = FIXTURE.relativize(file).toString().replace('\\', '/');
				if (rel.startsWith("fixture/")) continue;
				sources.put(rel, Files.readString(file));
			}
		}
		sources.put("fixture/edge/Mixins.java", mixins());
		// MixinExtras for the sugar case: the same jar the weave tests compile and run against.
		String extras = System.getProperty("forbric.mixinExtrasForTests", "");
		assertFalse(extras.isBlank(), "forbric.mixinExtrasForTests is not set: run this through Gradle");
		classes = new HashMap<>(InjectorExecution.compile(work, sources, List.of(Path.of(extras))));
	}

	/** One handler per case, all on the mesher; the names say which case. */
	private static String mixins() {
		String imports = """
				package fixture.edge;

				import java.util.List;
				import net.minecraft.client.renderer.block.BlockAndTintGetter;
				import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
				import net.minecraft.client.renderer.pebble.PebbleMesher;
				import net.minecraft.core.BlockPos;
				import net.minecraft.world.level.block.state.BlockState;
				import net.minecraftforge.client.model.data.ModelData;
				import org.spongepowered.asm.mixin.Mixin;
				import org.spongepowered.asm.mixin.injection.*;
				import org.spongepowered.asm.mixin.injection.callback.*;
				import com.llamalad7.mixinextras.sugar.Local;

				@Mixin(PebbleMesher.class)
				class Mixins {
				""";
		String m = "\"" + OVERLOAD + "\"";
		return imports
				+ "@Inject(method = " + m + ", at = @At(\"HEAD\"), cancellable = true) private void allArguments(" + ARGS + ", CallbackInfo ci) { }\n"
				+ "@Inject(method = " + m + ", at = @At(\"HEAD\")) private void aPrefix(List<String> out, float shade, CallbackInfo ci) { }\n"
				+ "@Inject(method = " + m + ", at = @At(\"HEAD\")) private void noArguments(CallbackInfo ci) { }\n"
				+ "@Inject(method = " + m + ", at = @At(\"TAIL\")) private void atTheEnd(" + ARGS + ", CallbackInfo ci) { }\n"
				+ "@Inject(method = " + m + ", at = @At(\"HEAD\"), allow = 1) private void counted(" + ARGS + ", CallbackInfo ci) { }\n"
				+ "@Inject(method = " + m + ", at = @At(\"HEAD\")) @Group(name = \"g\", min = 1, max = 1) private void grouped(" + ARGS + ", CallbackInfo ci) { }\n"
				+ "@Inject(method = " + m + ", at = @At(\"HEAD\")) private void sugared(CallbackInfo ci, @Local(argsOnly = true) BlockPos pos) { }\n"
				+ "@Inject(method = " + m + ", at = @At(\"HEAD\")) private void wrongArgument(List<String> out, int shade, CallbackInfo ci) { }\n"
				+ "@Inject(method = \"mesh(Ljava/util/List;FLnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
				+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;J)V\", "
				+ "at = @At(\"HEAD\")) private void theVanillaSignature(List<String> out, float shade, CallbackInfo ci) { }\n"
				+ "}\n";
	}

	@AfterEach void restore() {
		System.clearProperty(MixinForgeModelDataEdge.PROPERTY);
		System.clearProperty(ForgeModelDataFlowTransformer.PROPERTY);
	}

	private static ClassNode target(boolean transformed) {
		byte[] bytes = classes.get(MESHER);
		if (transformed) bytes = new ForgeModelDataFlowTransformer().transform(MESHER.replace('/', '.'), bytes, null);
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static ClassNode mixin() {
		ClassNode node = new ClassNode();
		new ClassReader(classes.get("fixture/edge/Mixins")).accept(node, 0);
		return node;
	}

	private static List<String> selectors(ClassNode mixin, String handler) {
		MethodNode method = mixin.methods.stream().filter(m -> m.name.equals(handler)).findFirst().orElseThrow();
		AnnotationNode injector = MixinFit.injectorOf(method);
		for (int i = 0; i + 1 < injector.values.size(); i += 2) {
			if ("method".equals(injector.values.get(i))) {
				List<String> out = new ArrayList<>();
				for (Object o : (List<?>) injector.values.get(i + 1)) out.add(String.valueOf(o));
				return out;
			}
		}
		return List.of();
	}

	@Test void onlyAnInjectAtTheOverloadsStartWhoseMeaningASecondTargetKeepsGetsTheTwin() {
		ClassNode mixin = mixin();
		ClassNode target = target(true);
		assertEquals(3, MixinForgeModelDataEdge.adapt(mixin, name -> name.equals(MESHER) ? target : null));
		String twin = "mesh" + ForgeModelDataFlowTransformer.EDGE_SUFFIX + OVERLOAD.substring(OVERLOAD.indexOf('('));
		for (String moved : List.of("allArguments", "aPrefix", "noArguments")) {
			assertEquals(List.of(OVERLOAD, twin), selectors(mixin, moved), moved + " keeps the overload and gains the twin");
		}
		for (String kept : List.of("atTheEnd", "counted", "grouped", "sugared", "wrongArgument", "theVanillaSignature")) {
			assertEquals(1, selectors(mixin, kept).size(), kept + " is left as compiled");
		}
		assertEquals(0, MixinForgeModelDataEdge.adapt(mixin, name -> name.equals(MESHER) ? target : null),
				"a second pass finds the twin already bound and adds nothing");
		assertEquals(List.of(OVERLOAD, twin), selectors(mixin, "allArguments"));
	}

	@Test void aTargetWithoutTheTwinIsLeftAlone() {
		ClassNode mixin = mixin();
		ClassNode untransformed = target(false);
		assertEquals(0, MixinForgeModelDataEdge.adapt(mixin, name -> name.equals(MESHER) ? untransformed : null));
	}

	@Test void eitherSwitchLeavesEverySelectorAsCompiled() {
		ClassNode target = target(true);
		System.setProperty(MixinForgeModelDataEdge.PROPERTY, "off");
		assertEquals(0, MixinForgeModelDataEdge.adapt(mixin(), name -> name.equals(MESHER) ? target : null));
		System.clearProperty(MixinForgeModelDataEdge.PROPERTY);
		System.setProperty(ForgeModelDataFlowTransformer.PROPERTY, "off");
		assertEquals(0, MixinForgeModelDataEdge.adapt(mixin(), name -> name.equals(MESHER) ? target : null));
	}
}
