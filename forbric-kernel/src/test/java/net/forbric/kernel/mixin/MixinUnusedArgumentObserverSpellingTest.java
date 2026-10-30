/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;

/**
 * {@link MixinUnusedArgumentObserverAdapter} finds an observer's host, and tells which injectors share its value, by the
 * method Mixin binds each of their selectors to — never by comparing their {@code method} lists — and reads the
 * expression's {@code @Definition} members as Mixin reads a member. fabric-api's fluid-jump observer under other class
 * and handler names, its sharers' selectors written another way (a dotted owner, an owner prefix, whitespace, two
 * spellings of one method), each sharer differently from the next, and its definitions with a dotted owner and
 * whitespace: each is carried exactly as the released spelling is. A sharer bound to another method, or to that method
 * and another, a source naming another owner, and a definition naming another owner's member leave it as written.
 */
@ResourceLock("system-properties")
class MixinUnusedArgumentObserverSpellingTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String WRAP = "Lcom/llamalad7/mixinextras/injector/wrapoperation/WrapOperation;";
	private static final String MODIFY_ARG = "Lorg/spongepowered/asm/mixin/injection/ModifyArg;";
	private static final String DEFINITIONS = "Lcom/llamalad7/mixinextras/expression/Definitions;";
	private static final String DEFINITION = "Lcom/llamalad7/mixinextras/expression/Definition;";

	private record Inputs(ClassNode mixin, Map<String, ClassNode> nodes, ClassNode original) {
		int adapt() { return MixinUnusedArgumentObserverAdapter.adapt(mixin, nodes::get, n -> original); }
	}

	@Test void theReleasedObserverUnderOtherNamesIsCarried() throws Exception {
		assertCarried(inputs());
	}

	@TestFactory Stream<DynamicTest> everySharerWrittenAnotherWayIsCarried() {
		Map<String, BiFunction<String, String, List<String>>> forms = new LinkedHashMap<>(PointRespelling.SELECTORS);
		forms.put("owner prefix with whitespace", (owner, s) -> List.of(" L" + owner + "; " + s + " "));
		return forms.entrySet().stream().map(form -> DynamicTest.dynamicTest(form.getKey(), () -> {
			Inputs input = inputs();
			assertTrue(PointRespelling.selectors(input.mixin(), MixinUnusedArgumentObserverSpellingTest::onAiStep, form.getValue()) >= 2, "premise: the observer and its sharers");
			assertCarried(input);
		}));
	}

	/** The source and each of its sharers spelled differently from one another: still one host for Mixin. */
	@Test void sharersSpelledDifferentlyFromOneAnotherAreOneHost() throws Exception {
		Inputs input = inputs();
		List<String> spellings = List.of("net.minecraft.world.entity.LivingEntity.aiStep", " L" + LIVING + "; aiStep ()V", "aiStep()V", "L" + LIVING + ";aiStep");
		int index = 0;
		for (MethodNode sharer : sharers(input.mixin())) select(sharer, List.of(spellings.get(index++ % spellings.size())));
		assertTrue(index >= 2, "premise: the observer has sharers");
		assertCarried(input);
	}

	@Test void definitionsWrittenAnotherWayAreTheSameMembers() throws Exception {
		Inputs input = inputs();
		assertEquals(2, respellDefinitions(source(input.mixin()), member -> {
			MixinFit.Member m = MixinFit.parseMember(member);
			return " " + m.owner().replace('/', '.') + " . " + m.name() + (m.desc().startsWith("(") ? " " : " : ") + m.desc();
		}), "premise: the method and the field are respelled");
		assertCarried(input);
	}

	// ---- look-alikes ------------------------------------------------------------------------------------------------------

	/** A sharer bound to another method of LivingEntity, or to aiStep and another: no longer one host for its value. */
	@Test void aSharerOnAnotherMethodLeavesTheObserver() throws Exception {
		for (List<String> selectors : List.of(List.of("net.minecraft.world.entity.LivingEntity.baseTick"), List.of("aiStep", "baseTick"))) {
			Inputs input = inputs();
			MethodNode other = sharers(input.mixin()).stream().filter(m -> m != source(input.mixin())).findFirst().orElseThrow();
			select(other, selectors);
			assertLeftAlone(input);
		}
	}

	@Test void anObserverNamingAnotherOwnerIsLeftAlone() throws Exception {
		for (String selector : List.of("Lnet/minecraft/world/entity/Entity;aiStep", "org.example.elsewhere.Swimmer.aiStep()V")) {
			Inputs input = inputs();
			select(source(input.mixin()), List.of(selector));
			assertLeftAlone(input);
		}
	}

	@Test void aDefinitionNamingAnotherOwnersMemberIsLeftAlone() throws Exception {
		Inputs input = inputs();
		assertEquals(1, respellDefinitions(source(input.mixin()), member -> {
			MixinFit.Member m = MixinFit.parseMember(member);
			return m.desc().startsWith("(") ? "org.example.elsewhere.Swimmer." + m.name() + m.desc() : member;
		}), "premise: the method is respelled");
		assertLeftAlone(input);
	}

	// ---- reading ----------------------------------------------------------------------------------------------------------

	private static void assertCarried(Inputs input) {
		MethodNode observer = source(input.mixin());
		Object selectors = MixinFit.value(MixinFit.injectorOf(observer), "method");
		String fingerprint = MixinInstructionFingerprint.hash(observer);
		assertEquals(1, input.adapt());
		List<MethodNode> wrappers = input.mixin().methods.stream().filter(m -> MixinFit.injectorOf(m) != null && MixinFit.injectorOf(m).desc.equals(WRAP)).toList();
		assertEquals(1, wrappers.size(), "one wrap at the carrier's typed call");
		assertEquals(MixinFit.stringList(selectors), MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(wrappers.getFirst()), "method")), "on the observer's own host, as written");
		assertNull(MixinFit.injectorOf(observer), "the observer itself runs inside the wrap");
		assertEquals(fingerprint, MixinInstructionFingerprint.hash(observer), "its body unchanged");
	}

	private static void assertLeftAlone(Inputs input) {
		byte[] before = bytes(input.mixin());
		assertEquals(0, input.adapt());
		assertArrayEquals(before, bytes(input.mixin()), "the mixin was rewritten");
	}

	/** The observer: the one {@code @ModifyArg} whose point is a MixinExtras expression. */
	private static MethodNode source(ClassNode mixin) {
		List<MethodNode> found = mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && MixinFit.injectorOf(m).desc.equals(MODIFY_ARG)
				&& MixinFit.atNodes(MixinFit.injectorOf(m)).stream().anyMatch(at -> "MIXINEXTRAS:EXPRESSION".equals(MixinFit.value(at, "value")))).toList();
		assertEquals(1, found.size(), "one observer");
		return found.getFirst();
	}

	/** The observer and every injector that shares a value with it in the mixin's own namespace. */
	private static List<MethodNode> sharers(ClassNode mixin) {
		var keys = MixinStubRebind.shareKeys(source(mixin));
		assertFalse(keys.isEmpty(), "premise: the observer shares a value");
		return mixin.methods.stream().filter(m -> MixinFit.injectorOf(m) != null && !java.util.Collections.disjoint(MixinStubRebind.shareKeys(m), keys)).toList();
	}

	private static boolean onAiStep(MethodNode method) {
		return List.of("aiStep").equals(MixinFit.stringList(MixinFit.value(MixinFit.injectorOf(method), "method")));
	}

	/** Rewrites each {@code @Definition} member of {@code handler}; how many changed. */
	private static int respellDefinitions(MethodNode handler, UnaryOperator<String> respell) {
		int changed = 0;
		List<AnnotationNode> all = new ArrayList<>();
		if (handler.visibleAnnotations != null) all.addAll(handler.visibleAnnotations);
		if (handler.invisibleAnnotations != null) all.addAll(handler.invisibleAnnotations);
		for (AnnotationNode annotation : all) {
			List<AnnotationNode> definitions = new ArrayList<>();
			if (annotation.desc.equals(DEFINITION)) definitions.add(annotation);
			if (annotation.desc.equals(DEFINITIONS) && MixinFit.value(annotation, "value") instanceof List<?> list)
				for (Object one : list) if (one instanceof AnnotationNode definition) definitions.add(definition);
			for (AnnotationNode definition : definitions) for (String key : List.of("method", "field")) {
				List<String> members = MixinFit.stringList(MixinFit.value(definition, key));
				if (members.isEmpty()) continue;
				List<String> out = new ArrayList<>();
				for (String member : members) {
					String respelled = respell.apply(member);
					if (!respelled.equals(member)) changed++;
					out.add(respelled);
				}
				MixinPlayerWorldCallbackAdapter.set(definition, key, out);
			}
		}
		return changed;
	}

	private static void select(MethodNode handler, List<String> selectors) {
		MixinPlayerWorldCallbackAdapter.set(MixinFit.injectorOf(handler), "method", new ArrayList<>(selectors));
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** fabric-api's fluid mixin under other names, the merged classes it reads, and LivingEntity as the mod was compiled against it. */
	private static Inputs inputs() throws Exception {
		Path api = Path.of(System.getProperty("forbric.fabricApi", TestFixtures.fabricApi().toString()));
		Path base = Path.of(System.getProperty("forbric.predicateBase", TestFixtures.stagedBase().toString()));
		TestFixtures.requireFiles(TestFixtures.Fixture.STAGED, "actual unused-argument observer inputs required", api, base);
		ClassNode released = null;
		try (ZipFile jar = new ZipFile(api.toFile())) {
			var module = jar.stream().filter(e -> e.getName().startsWith("META-INF/jars/fabric-content-registries-v0-")).findFirst().orElseThrow();
			try (ZipInputStream nested = new ZipInputStream(jar.getInputStream(module))) {
				for (var entry = nested.getNextEntry(); entry != null; entry = nested.getNextEntry())
					if (entry.getName().equals("net/fabricmc/fabric/mixin/content/registry/fluid/LivingEntityMixin.class")) released = parse(nested.readAllBytes());
			}
		}
		assertNotNull(released, "the released fluid mixin");
		Map<String, ClassNode> nodes = new HashMap<>();
		ClassNode original;
		try (ZipFile jar = new ZipFile(base.toFile())) {
			for (String owner : List.of(LIVING, "net/minecraft/world/entity/Entity", "net/minecraft/world/entity/EntityFluidInteraction"))
				nodes.put(owner, parse(jar.getInputStream(jar.getEntry(owner + ".class")).readAllBytes()));
			original = new NativeGameReferences(p -> {
				try {
					var e = jar.getEntry(p);
					return e == null ? null : jar.getInputStream(e).readAllBytes();
				} catch (Exception e) {
					return null;
				}
			}, null, TestFixtures.launchSources(base)).get(Ecosystem.FABRIC, LIVING);
		}
		assertNotNull(original, "LivingEntity as the mod was compiled against it");
		ClassNode mixin = MixinCallbackSelectorSpellingTest.unrelatedNames(released);
		assertTrue(source(mixin).name.startsWith("respelled"), "renamed");
		return new Inputs(mixin, nodes, original);
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}
}
