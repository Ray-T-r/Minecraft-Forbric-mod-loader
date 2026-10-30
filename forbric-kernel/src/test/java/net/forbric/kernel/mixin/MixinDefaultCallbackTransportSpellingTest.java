/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * fabric-api's trapdoor-ladder capability callback, renamed to an unrelated mixin and written the other ways Mixin reads
 * the same thing, still follows its predicate to NeoForge's default. Its host is the method its selector binds in the
 * class the mod was compiled against — owner-qualified, dotted or doubled, or a bare name binding the first method of
 * that name — and its anchor the one call the point selects there, spaced, dotted, or without the owner or descriptor
 * that method decides. Before, the host was found by comparing the selector with {@code name}/{@code name + desc} and
 * the anchor by comparing the point with the call's full spelling: none of these moved. A selector naming another owner
 * or binding a look-alike method, a bare name whose first method is another overload, and a short point that also
 * selects another call in the host are left byte for byte as they were.
 */
class MixinDefaultCallbackTransportSpellingTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String EXTENSION = "net/neoforged/neoforge/common/extensions/IBlockExtension";
	private static final String HOST = "trapdoorUsableAsLadder";
	private static final String HOST_ARGS = "(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)";

	/** The renamed mixin, the merged classes it reads, and the class the mod was compiled against. */
	private record Inputs(ClassNode mixin, Map<String, ClassNode> types, ClassNode original) {
		int adapt() { return MixinDefaultCallbackTransport.adapt(mixin, types::get, name -> name.equals(original.name) ? original : null); }
		MethodNode handler() { return TransportSpellings.handler(mixin, TransportSpellings.INJECT); }
	}

	private static Inputs inputs() throws Exception {
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(
				TransportSpellings.fabricMixin("fabric-block-api-v1", "net/fabricmc/fabric/mixin/block/LivingEntityMixin"));
		Map<String, ClassNode> types = new HashMap<>();
		types.put(LIVING, TransportSpellings.merged(LIVING));
		types.put("net/minecraft/world/level/block/LadderBlock", TransportSpellings.merged("net/minecraft/world/level/block/LadderBlock"));
		Path neo = TestFixtures.stagedRoot().resolve("neoforge-runtime/neoforge-runtime.jar");
		TestFixtures.requireFile(Fixture.STAGED, neo, "the staged NeoForge runtime required: " + neo);
		try (ZipFile jar = new ZipFile(neo.toFile())) {
			ZipEntry entry = jar.getEntry(EXTENSION + ".class");
			TestFixtures.require(Fixture.STAGED, entry != null, EXTENSION + " in the staged NeoForge runtime");
			types.put(EXTENSION, TransportSpellings.parse(jar.getInputStream(entry).readAllBytes()));
		}
		return new Inputs(mixin, types, TransportSpellings.nativeClass(Ecosystem.FABRIC, LIVING));
	}

	@Test void theRenamedCallbackTransportsAsShipped() throws Exception {
		assertEquals(1, inputs().adapt(), "premise: renamed, written as released");
	}

	@Test void aSelectorWrittenAnotherWayBindsTheSameHost() throws Exception {
		for (Map.Entry<String, BiFunction<String, String, List<String>>> spelling : PointRespelling.SELECTORS.entrySet()) {
			Inputs input = inputs();
			assertEquals(1, PointRespelling.selectors(input.mixin(), PointRespelling::any, spelling.getValue()), "premise");
			assertEquals(1, input.adapt(), spelling.getKey());
		}
	}

	/** A method of the same name declared after the host: the bare name still binds the host (before: two matches, refused). */
	@Test void aBareNameBindsTheFirstMethodOfThatName() throws Exception {
		Inputs input = inputs();
		TransportSpellings.overload(input.original(), HOST, "(I)Z", Opcodes.ACC_PRIVATE, false);
		assertEquals(1, input.adapt());
	}

	/** The anchor spaced, dotted, or without the owner or descriptor that the host the mod was compiled against decides. */
	@Test void anAnchorWrittenAnotherWayNamesTheSameCall() throws Exception {
		for (PointRespelling.Form form : PointRespelling.allForms()) {
			Inputs input = inputs();
			assertEquals(1, PointRespelling.points(input.mixin(), PointRespelling::any, form, m -> PointRespelling.bound(m, input.original())),
					"premise: " + form.id() + " applies");
			assertEquals(1, input.adapt(), form.id());
		}
	}

	// ---- look-alikes: left alone ---------------------------------------------------------------------------------------

	@Test void aBareNameWhoseFirstMethodIsAnotherOverloadIsLeftAlone() throws Exception {
		Inputs input = inputs();
		TransportSpellings.overload(input.original(), HOST, "(I)Z", Opcodes.ACC_PRIVATE, true);
		assertUnchanged(input, "the bare name binds the overload declared first");
	}

	/**
	 * A private Boolean method of the host's very shape that makes no such call, selected owner-qualified; and the host's
	 * name under another owner, which Mixin refuses.
	 */
	@Test void aSelectorForALookAlikeOrAnotherOwnerIsLeftAlone() throws Exception {
		Inputs input = inputs();
		TransportSpellings.overload(input.original(), "ladderLookAlike", HOST_ARGS + "Z", Opcodes.ACC_PRIVATE, false);
		TransportSpellings.select(input.handler(), "L" + LIVING + ";ladderLookAlike" + HOST_ARGS + "Z");
		assertUnchanged(input, "a selector binding a look-alike method");
		input = inputs();
		TransportSpellings.select(input.handler(), "Lorg/example/elsewhere/Other;" + HOST);
		assertUnchanged(input, "a selector naming another owner");
	}

	/** Where the host also calls {@code getBlockState} on another owner (or of another descriptor), the short anchor names two calls. */
	@Test void aShortAnchorThatAlsoSelectsAnotherCallIsLeftAlone() throws Exception {
		for (boolean otherOwner : List.of(true, false)) {
			Inputs input = inputs();
			PointRespelling.Form form = PointRespelling.READ_IN_THE_WRITTEN_METHOD.get(otherOwner ? 0 : 1);
			assertEquals(1, PointRespelling.crowd(PointRespelling.copy(input.mixin()), PointRespelling::any, input.original(), otherOwner), "premise");
			TransportSpellings.respell(input.handler(), form);
			assertUnchanged(input, "the anchor " + form.id() + " beside a getBlockState " + (otherOwner ? "on another owner" : "of another descriptor"));
		}
	}

	private static void assertUnchanged(Inputs input, String why) {
		byte[] before = TransportSpellings.bytes(input.mixin());
		assertEquals(0, input.adapt(), why);
		assertArrayEquals(before, TransportSpellings.bytes(input.mixin()), why + " — the mixin is not touched");
	}
}
