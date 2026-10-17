/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.kernel.transform.GuestInjectorPruner;

/**
 * A kept mixin with an injector Mixin rejects outright: the PARTIAL verdict used to keep it, and Mixin then threw
 * "Invalid descriptor" and failed the whole mixin with it. Now the injector is taken out when it can go alone, the
 * mixin is left out when it cannot (or when a kernel repair supersedes all of it), and the switch puts it back in front
 * of Mixin. A mixin the kernel leaves out by name is never judged at all.
 */
@ResourceLock("ModCatalog")
@ResourceLock("system-properties")
class KernelGuestMixinAdapterRejectionTest {
	private static final String CI = "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;";
	private static final String TARGET = "net/minecraft/world/RejectionTarget";

	@AfterEach void reset() {
		ForbricMixinService.setGuestConfigs(List.of());
		ForeignMixinTargets.reset();
		System.clearProperty(GuestInjectorPruner.PROPERTY);
		System.clearProperty(GuestInjectorPruner.REFUSED_PROPERTY);
		System.clearProperty("forbric.suppressMixins");
		GuestInjectorPruner.forgetRefused();
		MixinCompatibility.reset();
		CompatibilityFindings.reset();
	}

	/** {@code render()V}, and {@code place(String, int)}, which the mixin's {@code onPlace(String, CallbackInfo)} does not fit. */
	private static ClassNode targetNode() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = TARGET;
		node.superName = "java/lang/Object";
		for (String[] m : new String[][] {{"render", "()V"}, {"place", "(Ljava/lang/String;I)V"}}) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, m[0], m[1], null, null);
			method.instructions.add(new InsnNode(Opcodes.RETURN));
			node.methods.add(method);
		}
		return node;
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static MethodNode handler(String name, String desc, String selector) {
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "HEAD"));
		AnnotationNode inject = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
		inject.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of(selector)), "at", new ArrayList<>(List.of(at))));
		MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, name, desc, null, null);
		m.visibleAnnotations = new ArrayList<>(List.of(inject));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		return m;
	}

	/** onRender binds render; onPlace's name binds place(String, int), which it was not written for. */
	private static ClassNode mixinNode(String pkg, String simpleName) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = pkg + "/" + simpleName;
		node.superName = "java/lang/Object";
		AnnotationNode type = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		type.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(TARGET)))));
		node.invisibleAnnotations = new ArrayList<>(List.of(type));
		node.methods.add(handler("onRender", "(" + CI + ")V", "render"));
		node.methods.add(handler("onPlace", "(Ljava/lang/String;" + CI + ")V", "place"));
		return node;
	}

	private record Run(List<String> dropped, ClassNode mixin, String config) {
		/** What the pruner takes out of the node Mixin receives. */
		int pruned() {
			return GuestInjectorPruner.pruneRefused(mixin, (n, h) -> MixinFit.stillRejected(n, h, t -> t.equals(TARGET) ? targetNode() : null));
		}

		boolean countedPartial() {
			return KernelGuestMixinAdapter.partiallyApplied().contains(config + ":" + mixin.name.substring(mixin.name.lastIndexOf('/') + 1));
		}
	}

	private static Run run(String config, String pkg, String simpleName, java.util.function.Consumer<ClassNode> edit) {
		ClassNode mixin = mixinNode(pkg, simpleName);
		if (edit != null) edit.accept(mixin);
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(TARGET + ".class", bytes(targetNode()));
		classes.put(pkg + "/" + simpleName + ".class", bytes(mixin));
		byte[] json = ("{\"package\":\"" + pkg.replace('/', '.') + "\",\"required\":true,\"injectors\":{\"defaultRequire\":1},"
				+ "\"mixins\":[\"" + simpleName + "\"]}").getBytes(StandardCharsets.UTF_8);
		List<String> dropped = KernelGuestMixinAdapter.unfitMixins(config, json, classes::get);
		return new Run(dropped, MixinFit.parse(bytes(mixin)), config);
	}

	@Test void theRejectedInjectorIsTakenOutAndTheRestApplies() {
		Run run = run("rejection-prune.mixins.json", "net/example/prune", "RefusingMixin", null);
		assertEquals(List.of(), run.dropped(), "kept");
		assertFalse(run.countedPartial(), "once it is out, what is left fits: not a PARTIAL");
		assertEquals(1, run.pruned());
		assertEquals(List.of("onRender"), run.mixin().methods.stream().map(m -> m.name).toList());
		CompatibilityFinding finding = CompatibilityFindings.all().stream()
				.filter(f -> f.id().startsWith("mixin-injector:rejection-prune.mixins.json:")).findFirst().orElseThrow();
		assertTrue(finding.required(), "defaultRequire 1: the author's own count");
		assertEquals(CompatibilityFinding.Confidence.CONFIRMED, finding.confidence());
	}

	/** RED control: the switch keeps the mixin whole in front of Mixin, a PARTIAL as before, and nothing is taken out. */
	@Test void withTheSwitchOffTheMixinIsKeptWholeAsBefore() {
		System.setProperty(GuestInjectorPruner.REFUSED_PROPERTY, "off");
		Run run = run("rejection-off.mixins.json", "net/example/off", "RefusingMixin", null);
		assertEquals(List.of(), run.dropped());
		assertTrue(run.countedPartial());
		assertEquals(0, run.pruned());
		assertTrue(KernelGuestMixinAdapter.partialSummary().contains("keep an injector Mixin rejects outright"),
				"the summary must not say \"no error\" of a mixin Mixin will fail: " + KernelGuestMixinAdapter.partialSummary());
	}

	/** With the whole pruner off the mixin is left out: never half-applied, as the table entries' fallback. */
	@Test void withThePrunerOffTheMixinIsLeftOut() {
		System.setProperty(GuestInjectorPruner.PROPERTY, "off");
		Run run = run("rejection-pruner-off.mixins.json", "net/example/prunoff", "RefusingMixin", null);
		assertEquals(List.of("RefusingMixin"), run.dropped());
		assertFalse(run.countedPartial());
		assertTrue(CompatibilityFindings.confirmedRequired().stream().anyMatch(f -> f.id().contains("RefusingMixin")),
				CompatibilityFindings.all().toString());
	}

	/** A handler something else in the mixin calls cannot go alone: the mixin is left out instead. */
	@Test void aHandlerTheMixinItselfCallsTakesTheMixinWithIt() {
		Run run = run("rejection-called.mixins.json", "net/example/called", "RefusingMixin", m -> {
			MethodNode caller = new MethodNode(Opcodes.ACC_PRIVATE, "helper", "()V", null, null);
			caller.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			caller.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			caller.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, m.name, "onPlace", "(Ljava/lang/String;" + CI + ")V", false));
			caller.instructions.add(new InsnNode(Opcodes.RETURN));
			m.methods.add(caller);
		});
		assertEquals(List.of("RefusingMixin"), run.dropped());
		assertEquals(0, run.pruned());
	}

	// --- the mixins kept on another ground: misses on another mod's class, or an UNFIT one another mod's mixin claims ---

	private static final String WIDGET = "com/othermod/Widget";

	/** Another mod's class: {@code render()V} only, so a {@code place} selector misses there. */
	private static ClassNode widgetNode() {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC;
		node.name = WIDGET;
		node.superName = "java/lang/Object";
		MethodNode render = new MethodNode(Opcodes.ACC_PUBLIC, "render", "()V", null, null);
		render.instructions.add(new InsnNode(Opcodes.RETURN));
		node.methods.add(render);
		return node;
	}

	private static ClassNode target(String name) {
		return name.equals(TARGET) ? targetNode() : name.equals(WIDGET) ? widgetNode() : null;
	}

	/** Each config in {@code configs} (name -> package, mixin node), registered as the guest configs of the boot. */
	private static List<String> unfit(String config, Map<String, Object[]> configs) {
		Map<String, byte[]> resources = new HashMap<>();
		resources.put(TARGET + ".class", bytes(targetNode()));
		resources.put(WIDGET + ".class", bytes(widgetNode()));
		for (var entry : configs.entrySet()) {
			String pkg = (String) entry.getValue()[0];
			ClassNode mixin = (ClassNode) entry.getValue()[1];
			String simple = mixin.name.substring(mixin.name.lastIndexOf('/') + 1);
			resources.put(mixin.name + ".class", bytes(mixin));
			resources.put(entry.getKey(), ("{\"package\":\"" + pkg.replace('/', '.') + "\",\"required\":true,"
					+ "\"injectors\":{\"defaultRequire\":1},\"mixins\":[\"" + simple + "\"]}").getBytes(StandardCharsets.UTF_8));
		}
		ForbricMixinService.setGuestConfigs(List.copyOf(configs.keySet()));
		ForeignMixinTargets.reset();
		return KernelGuestMixinAdapter.unfitMixins(config, resources.get(config), resources::get);
	}

	private static List<ForeignMixinBreaks.Break> breaks(String config) {
		return ForeignMixinBreaks.all().stream().filter(b -> b.config().equals(config)).toList();
	}

	private static int pruned(ClassNode mixin) {
		return GuestInjectorPruner.pruneRefused(mixin, (n, h) -> MixinFit.stillRejected(n, h, KernelGuestMixinAdapterRejectionTest::target));
	}

	/**
	 * A mixin kept for its misses on another mod's class still answers a rejection on the game's: Mixin fails the mixin
	 * there whichever class the other misses are on. The refused injector is taken out, the rest applies, and once it is
	 * out nothing misses on the other mod's class either, so no cross-mod report is made of it.
	 */
	@Test void aMixinWithMissesOnAnotherModStillAnswersARejection() {
		ClassNode mixin = mixinNode("net/example/foreign", "RefusingMixin");
		AnnotationNode type = mixin.invisibleAnnotations.getFirst();
		type.values.set(1, new ArrayList<>(List.of(Type.getObjectType(TARGET), Type.getObjectType(WIDGET))));
		MixinFit.Result fit = MixinFit.evaluate(bytes(mixin), name -> name.equals(TARGET + ".class") ? bytes(targetNode())
				: name.equals(WIDGET + ".class") ? bytes(widgetNode()) : null, net.forbric.kernel.classloading.DelegationPolicy::alwaysGame);
		assertEquals(List.of("@Inject target Widget.place"), fit.foreign(), "premise: kept for a miss on another mod's class");
		assertEquals(List.of("onPlace"), fit.rejected().stream().map(MixinFit.Rejection::handler).toList(), "premise: and a rejection");

		List<String> dropped = unfit("rejection-foreign.mixins.json", Map.of("rejection-foreign.mixins.json",
				new Object[] {"net/example/foreign", mixin}));
		assertEquals(List.of(), dropped);
		ClassNode served = MixinFit.parse(bytes(mixin));
		assertEquals(1, pruned(served), "the refused injector is taken out of the node Mixin receives");
		assertEquals(List.of("onRender"), served.methods.stream().map(m -> m.name).toList());
		assertEquals(List.of(), breaks("rejection-foreign.mixins.json"), "what missed on Widget was the injector that is gone");
	}

	/** Its switch: with the pruner's rejections off, the mixin is kept whole, as before, with its cross-mod report. */
	@Test void withTheSwitchOffTheForeignMixinIsKeptWhole() {
		System.setProperty(GuestInjectorPruner.REFUSED_PROPERTY, "off");
		ClassNode mixin = mixinNode("net/example/foreignoff", "RefusingMixin");
		mixin.invisibleAnnotations.getFirst().values.set(1, new ArrayList<>(List.of(Type.getObjectType(TARGET), Type.getObjectType(WIDGET))));
		assertEquals(List.of(), unfit("rejection-foreign-off.mixins.json", Map.of("rejection-foreign-off.mixins.json",
				new Object[] {"net/example/foreignoff", mixin})));
		assertEquals(0, pruned(MixinFit.parse(bytes(mixin))));
		assertEquals(1, breaks("rejection-foreign-off.mixins.json").size());
	}

	/** {@code onPlace} refused on {@code place}, and {@code onGone} on a method the target does not declare: nothing binds. */
	private static ClassNode unfitMixin(String pkg) {
		ClassNode mixin = mixinNode(pkg, "ClaimedMixin");
		mixin.methods.removeFirst();
		mixin.methods.add(handler("onGone", "(" + CI + ")V", "gone"));
		return mixin;
	}

	/** Another mod's mixin into the same class, which is what makes the UNFIT one a cross-mod layer the adapter keeps. */
	private static ClassNode claimingMixin() {
		ClassNode other = mixinNode("net/example/othermod", "OtherMixin");
		other.methods.removeLast();
		return other;
	}

	/**
	 * An UNFIT mixin kept because another mod's mixin targets the same class answers a rejection too: kept as it was, Mixin
	 * fails it on the refused binding. Taken out, the rest is kept for the other mod as before.
	 */
	@Test void anUnfitMixinAnotherModClaimsStillAnswersARejection() {
		ClassNode mixin = unfitMixin("net/example/claimed");
		Map<String, Object[]> configs = new java.util.LinkedHashMap<>();
		configs.put("rejection-claimed.mixins.json", new Object[] {"net/example/claimed", mixin});
		configs.put("othermod.mixins.json", new Object[] {"net/example/othermod", claimingMixin()});
		assertEquals(List.of(), unfit("rejection-claimed.mixins.json", configs), "kept for the other mod");
		ClassNode served = MixinFit.parse(bytes(mixin));
		assertEquals(1, pruned(served));
		assertEquals(List.of("onGone"), served.methods.stream().map(m -> m.name).toList());

		// Not alone (the mixin itself calls the handler): left out, as a PARTIAL one would be.
		GuestInjectorPruner.forgetRefused();
		ClassNode called = unfitMixin("net/example/claimedcalled");
		MethodNode caller = new MethodNode(Opcodes.ACC_PRIVATE, "helper", "()V", null, null);
		caller.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		caller.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		caller.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		caller.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, called.name, "onPlace", "(Ljava/lang/String;" + CI + ")V", false));
		caller.instructions.add(new InsnNode(Opcodes.RETURN));
		called.methods.add(caller);
		configs.put("rejection-claimed.mixins.json", new Object[] {"net/example/claimedcalled", called});
		assertEquals(List.of("ClaimedMixin"), unfit("rejection-claimed.mixins.json", configs));
	}

	/** Its switch: the claim keeps the mixin whole, as before; nothing is taken out. Without the claim it is UNFIT and left out. */
	@Test void withTheSwitchOffTheClaimedMixinIsKeptWhole() {
		System.setProperty(GuestInjectorPruner.REFUSED_PROPERTY, "off");
		ClassNode mixin = unfitMixin("net/example/claimedoff");
		Map<String, Object[]> configs = new java.util.LinkedHashMap<>();
		configs.put("rejection-claimed-off.mixins.json", new Object[] {"net/example/claimedoff", mixin});
		configs.put("othermod.mixins.json", new Object[] {"net/example/othermod", claimingMixin()});
		assertEquals(List.of(), unfit("rejection-claimed-off.mixins.json", configs));
		assertEquals(0, pruned(MixinFit.parse(bytes(mixin))));

		configs.remove("othermod.mixins.json");
		assertEquals(List.of("ClaimedMixin"), unfit("rejection-claimed-off.mixins.json", configs), "unclaimed: UNFIT, left out");
	}

	/**
	 * A mixin a kernel repair supersedes is left out whole -- the repair does all of its job, and pruning would let the
	 * rest apply beside it -- and, as a kernel decision with a named repair, its row asks the player nothing.
	 */
	@Test void aSupersededMixinIsLeftOutWholeAndAsksNothing() {
		// What config discovery notes for a mixin whose shape a repair's claim fits (SupersededMixinsTest reads the shapes).
		SupersededMixins.claimForTests("net.fabricmc.fabric.mixin.resource.conditions.SimpleJsonResourceReloadListenerMixin", "resource-conditions");
		Run run = run("fabric-resource-conditions-api-v1.mixins.json", "net/fabricmc/fabric/mixin/resource/conditions",
				"SimpleJsonResourceReloadListenerMixin", null);
		assertEquals(List.of("SimpleJsonResourceReloadListenerMixin"), run.dropped());
		assertEquals(0, run.pruned());
		CompatibilityFinding row = CompatibilityFindings.all().stream()
				.filter(f -> f.id().contains("SimpleJsonResourceReloadListenerMixin")).findFirst().orElseThrow();
		assertFalse(row.required(), row.toString());
	}

	/**
	 * A mixin the kernel leaves out by name never reaches Mixin and has its own row: it is not judged, so no verdict line,
	 * no place in the PARTIAL count and nothing remembered for the pruner. RED control: without the name, the same
	 * mixin is judged and its injector remembered.
	 */
	@Test void aMixinLeftOutByNameIsNotJudged() {
		System.setProperty("forbric.suppressMixins", "rejection-named.mixins.json:RefusingMixin");
		System.setProperty(GuestInjectorPruner.REFUSED_PROPERTY, "off");
		Run named = run("rejection-named.mixins.json", "net/example/named", "RefusingMixin", null);
		assertFalse(named.countedPartial(), "a suppressed mixin is not a partial one");
		System.clearProperty(GuestInjectorPruner.REFUSED_PROPERTY);
		Run again = run("rejection-named.mixins.json", "net/example/named", "RefusingMixin", null);
		assertEquals(0, again.pruned(), "nothing remembered for a mixin that is never judged");

		System.clearProperty("forbric.suppressMixins");
		Run judged = run("rejection-named.mixins.json", "net/example/named", "RefusingMixin", null);
		assertEquals(1, judged.pruned());
	}
}
