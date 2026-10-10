/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * fabric-api's fluid-overlay expression modifier, renamed to an unrelated mixin and its selector written the other ways
 * Mixin binds the same method, still follows the predicate NeoForge moved behind its default. The body is the one method
 * the selector binds in the merged class, and the native body the same member bound in the class the mod was compiled
 * against: owner-qualified, dotted or doubled, or a bare name binding the first method of that name. Before, the body was
 * found by comparing the selector with {@code name}/{@code name + desc}: owner-qualified and dotted selectors bound
 * nothing, and an overload of the same name made two matches. A bare name whose first method is another overload — in
 * both classes, or only in the one the mod was compiled against — and a selector for another method or owner are left
 * as they were.
 */
@ResourceLock("system-properties")
class MixinDefaultPredicateTransportSpellingTest {
	private static final String HOST = "net/minecraft/client/renderer/block/FluidRenderer";
	private static final String MODIFY = "Lcom/llamalad7/mixinextras/injector/ModifyExpressionValue;";
	private static final List<ZipFile> OPEN = new ArrayList<>();

	@AfterEach void close() throws Exception {
		for (ZipFile zip : OPEN) zip.close();
		OPEN.clear();
	}

	/** The renamed mixin, and the merged and native classes each read once and kept, so a test can edit them. */
	private record Inputs(ClassNode mixin, Function<String, ClassNode> current, Function<String, ClassNode> nativeClasses) {
		int adapt() { return MixinDefaultPredicateTransport.adapt(mixin, current, nativeClasses); }
		MethodNode handler() { return TransportSpellings.handler(mixin, MODIFY); }
		ClassNode merged() { return current.apply(HOST); }
		ClassNode original() { return nativeClasses.apply(HOST); }
	}

	private static Inputs inputs() throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(
				TransportSpellings.fabricMixin("fabric-rendering-fluids-v1", "net/fabricmc/fabric/mixin/client/rendering/fluid/FluidRendererMixin"));
		ZipFile game = open(TransportSpellings.mergedBase());
		ZipFile neo = open(TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar"));
		ZipFile forge = open(TestFixtures.stagedRoot().resolve("forge-runtime/forge-runtime.jar"));
		NativeGameReferences references = new NativeGameReferences(name -> read(game, name));
		ClassNode host = references.get(Ecosystem.FABRIC, HOST);
		TestFixtures.require(Fixture.STAGED, host != null, "the hash-pinned native " + HOST + " the merged base indexes");
		Map<String, ClassNode> currentCache = new HashMap<>(), nativeCache = new HashMap<>();
		nativeCache.put(HOST, host);
		Function<String, ClassNode> current = name -> currentCache.computeIfAbsent(name, n -> {
			byte[] bytes = read(game, n + ".class");
			if (bytes == null) bytes = read(neo, n + ".class");
			if (bytes == null) bytes = read(forge, n + ".class");
			if (bytes == null) try (InputStream in = ClassLoader.getSystemResourceAsStream(n + ".class")) {
				if (in != null) bytes = in.readAllBytes();
			} catch (Exception unavailable) {
				return null;
			}
			return bytes == null ? null : TransportSpellings.parse(bytes);
		});
		Function<String, ClassNode> nativeClasses = name -> nativeCache.computeIfAbsent(name, n -> references.get(Ecosystem.FABRIC, n));
		return new Inputs(mixin, current, nativeClasses);
	}

	private static ZipFile open(Path jar) throws Exception {
		TestFixtures.require(Fixture.STAGED, Files.isRegularFile(jar), "staged input required: " + jar);
		ZipFile zip = new ZipFile(jar.toFile());
		OPEN.add(zip);
		return zip;
	}

	private static byte[] read(ZipFile zip, String name) {
		try {
			var entry = zip.getEntry(name);
			return entry == null ? null : zip.getInputStream(entry).readAllBytes();
		} catch (Exception unreadable) {
			throw new IllegalStateException(unreadable);
		}
	}

	@Test void theRenamedModifierTransportsAsShipped() throws Exception {
		assertEquals(1, inputs().adapt(), "premise: renamed, written as released");
	}

	@Test void aSelectorWrittenAnotherWayBindsTheSameBody() throws Exception {
		for (Map.Entry<String, BiFunction<String, String, List<String>>> spelling : PointRespelling.SELECTORS.entrySet()) {
			Inputs input = inputs();
			assertEquals(1, PointRespelling.selectors(input.mixin(), TransportSpellings.injector(MODIFY), spelling.getValue()), "premise");
			assertEquals(1, input.adapt(), spelling.getKey());
		}
	}

	/** An overload declared after the body in both classes: the bare name binds the body (before: two matches, refused). */
	@Test void aBareNameBindsTheFirstMethodOfThatName() throws Exception {
		Inputs input = inputs();
		TransportSpellings.overload(input.merged(), "tesselate", "(I)V", Opcodes.ACC_PUBLIC, false);
		TransportSpellings.overload(input.original(), "tesselate", "(I)V", Opcodes.ACC_PUBLIC, false);
		assertEquals(1, input.adapt());
	}

	// ---- look-alikes: left alone ---------------------------------------------------------------------------------------

	@Test void aBareNameWhoseFirstMethodIsAnotherOverloadIsLeftAlone() throws Exception {
		for (boolean inMerged : List.of(true, false)) {
			Inputs input = inputs();
			if (inMerged) TransportSpellings.overload(input.merged(), "tesselate", "(I)V", Opcodes.ACC_PUBLIC, true);
			TransportSpellings.overload(input.original(), "tesselate", "(I)V", Opcodes.ACC_PUBLIC, true);
			assertUnchanged(input, inMerged ? "the bare name binds the overload declared first, in both classes"
					: "the class the mod was compiled against binds the other overload: not written for the merged body");
		}
	}

	@Test void aSelectorForAnotherMethodOrOwnerIsLeftAlone() throws Exception {
		Inputs input = inputs();
		MethodNode other = TransportSpellings.another(input.merged(), "tesselate", false);
		TransportSpellings.select(input.handler(), "L" + HOST + ";" + other.name + other.desc);
		assertUnchanged(input, "a selector binding " + other.name);
		input = inputs();
		TransportSpellings.select(input.handler(), "org.example.elsewhere.Renderer.tesselate");
		assertUnchanged(input, "a selector naming another owner");
	}

	private static void assertUnchanged(Inputs input, String why) {
		byte[] before = TransportSpellings.bytes(input.mixin());
		assertEquals(0, input.adapt(), why);
		assertArrayEquals(before, TransportSpellings.bytes(input.mixin()), why + " — the mixin is not touched");
	}
}
