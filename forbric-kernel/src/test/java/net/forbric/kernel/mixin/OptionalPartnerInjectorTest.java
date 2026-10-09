/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.api.CompatibilityFinding;
import net.forbric.api.CompatibilityFindings;
import net.forbric.api.DiscoveredMod;
import net.forbric.api.Ecosystem;
import net.forbric.api.ModPresence;
import net.forbric.api.UnifiedDependency;

/**
 * A mod's compatibility mixin for a second mod that is not installed: an injector into the method that second mod's
 * own mixin would add to a game class, in a config whose {@code injectors.defaultRequire} is {@code -1}.
 *
 * <p>Native Mixin reads that {@code -1} as the injector's required count, and fails an injector only on a count above 0
 * that finds no target or on fewer injections than the count — so it requires nothing, and with the second mod absent
 * the injector is dropped without a word while the rest of the mixin applies. The kernel used to read a written
 * {@code -1} as "unknown", so {@link NativeAbsentTargets} never answered, the mixin was UNFIT, it was left out, and a
 * CONFIRMED finding marked the first mod DEGRADED for a loss native never has. Nothing about the fix depends on which
 * mods these are or on the first mod declaring the second as an optional dependency (the fixture declares none).
 *
 * <p>The fixture: mod {@code alpha}, config {@code alpha.mixins.json} without {@code required}, and its
 * {@code PlaqueCompatMixin} — no field, no interface, one {@code @Inject} into {@code beta$cacheable} on the game class
 * {@code Plaque}, which declares only {@code draw()}. Mod {@code beta} is not installed. Classes are synthesized with
 * ASM and judged by the adapter as the boot calls it, on a base the shipped table speaks for.
 *
 * <p>Look-alikes that must stay losses: the same mixin whose injector natively must inject (the config's default above
 * 0, its own {@code require}, a {@code @Group}), whose config inherits an unknown default from a {@code parent}, with
 * the switch off, and an injector with the same {@code -1} into a method the mod's own platform declares and the merge
 * lost — natively that one applies, so here it is the merge's loss whatever the default says.
 */
@ResourceLock("ModCatalog")
@ResourceLock("ModPresence")
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class OptionalPartnerInjectorTest {
	private static final String MOD = "alpha";
	private static final String CONFIG = "alpha.mixins.json";
	private static final String PKG = "net/example/alpha/compat";
	private static final String MIXIN = "PlaqueCompatMixin";
	private static final String PLAQUE = "net/minecraft/fixture/alpha/Plaque";
	private static final String PARTNER_SELECTOR = "beta$cacheable";
	private static final String LEFT_OUT = "mixin:" + CONFIG + ":" + PKG.replace('/', '.') + "." + MIXIN;

	@BeforeEach @AfterEach void clean() {
		CompatibilityFindings.reset();
		MixinConfigOwners.reset();
		System.clearProperty(NativeAbsentTargets.PROPERTY);
		System.clearProperty(NativeAbsentTargets.BASE_PROPERTY);
		System.clearProperty("mixin.debug.countInjections");
		System.clearProperty("mixin.debug");
		ModPresence.publishFabric(List.of());
		ModPresence.publishForgeFamily(List.of());
	}

	// --- no requirement: kept whole, nothing reported ----------------------------------------------------------------

	/** The regression itself, on every platform: an explicit -1 requires nothing, and nothing is recorded. */
	@Test void anExplicitMinusOneDefaultMakesAnInjectorIntoAnAbsentModsMethodNoLoss() {
		List<String> wrong = new ArrayList<>();
		for (Ecosystem platform : Ecosystem.values()) {
			clean();
			owned(platform);
			Map<String, byte[]> classes = partnerShape(null, false);
			List<String> left = judge(config(",\"injectors\":{\"defaultRequire\":-1}"), classes);
			if (!left.isEmpty() || !CompatibilityFindings.all().isEmpty()) {
				wrong.add(platform + " left " + left + " " + CompatibilityFindings.all());
			}
		}
		assertEquals(List.of(), wrong, "an injector that requires nothing, into a method no platform declares");
	}

	/**
	 * Any default below 0 requires nothing, the config's {@code required} makes no injector mandatory, and a child
	 * config's own -1 stands over its parent ({@code InjectorOptions.mergeFrom} replaces only a 0).
	 */
	@Test void everyDefaultBelowZeroRequiresNothingWhateverElseTheConfigSays() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = partnerShape(null, false);
		for (String extra : List.of(",\"injectors\":{\"defaultRequire\":-3}",
				",\"required\":true,\"injectors\":{\"defaultRequire\":-1}",
				",\"parent\":\"alpha.parent.mixins.json\",\"injectors\":{\"defaultRequire\":-1}")) {
			CompatibilityFindings.reset();
			assertEquals(List.of(), judge(config(extra), classes), extra);
			assertEquals(List.of(), CompatibilityFindings.all(), extra);
		}
		CompatibilityFindings.reset();
		assertEquals(List.of(), judge(config(",\"injectors\":{\"defaultRequire\":-1}"), partnerShape(-1, false)),
				"an injector's own require of -1 defers to the default, here -1 too");
		CompatibilityFindings.reset();
		assertEquals(List.of(), judge(config(",\"injectors\":{\"defaultRequire\":1}"), partnerShape(0, false)),
				"an injector's own require of 0 wins over the default");
	}

	/** A target class of the absent mod is no loss either: there is nothing to judge, so nothing is left out. */
	@Test void aTargetClassThatIsAbsentBecauseItsModIsAbsentIsNoLoss() {
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(PKG + "/" + MIXIN + ".class", compatMixin("net/example/beta/render/CacheTable", "refresh", 1, false));
		assertEquals(List.of(), judge(config(",\"required\":true,\"injectors\":{\"defaultRequire\":1}"), classes));
		assertEquals(List.of(), CompatibilityFindings.all());
	}

	// --- look-alikes that must stay losses -----------------------------------------------------------------------------

	/** Natively an injector that must inject throws "Critical injection failure": a loss on both, as before. */
	@Test void anInjectorThatMustInjectIsStillLeftOutAsALoss() {
		owned(Ecosystem.FABRIC);
		assertLeftOut(",\"injectors\":{\"defaultRequire\":1}", partnerShape(null, false), false,
				"the config's default above 0");
		assertLeftOut(",\"injectors\":{\"defaultRequire\":-1}", partnerShape(1, false), false,
				"the injector's own require=1 wins over the config's -1");
		assertLeftOut(",\"injectors\":{\"defaultRequire\":1}", partnerShape(-1, false), false,
				"an injector's own require of -1 defers to the config's default, here 1");
		assertLeftOut(",\"required\":true,\"injectors\":{\"defaultRequire\":2}", partnerShape(null, false), true,
				"a required config's default above 0");
		assertLeftOut(",\"injectors\":{\"defaultRequire\":-1}", partnerShape(null, true), false,
				"a @Group needs one injection between its members");
	}

	/** A parent may supply the default for an omitted or 0 one: unknown, and an unknown requirement is never none. */
	@Test void aDefaultAParentMaySupplyIsUnknownAndStaysALoss() {
		owned(Ecosystem.FABRIC);
		assertLeftOut(",\"parent\":\"alpha.parent.mixins.json\"", partnerShape(null, false), false, "omitted, with a parent");
		assertLeftOut(",\"parent\":\"alpha.parent.mixins.json\",\"injectors\":{\"defaultRequire\":0}",
				partnerShape(null, false), false, "0, with a parent");
		assertLeftOut(",\"injectors\":{\"defaultRequire\":\"none\"}", partnerShape(null, false), false, "not a number");
	}

	/** RED control: the switch restores the old verdict for this shape too. */
	@Test void switchedOffTheSameMixinIsLeftOutAgain() {
		owned(Ecosystem.FABRIC);
		System.setProperty(NativeAbsentTargets.PROPERTY, "off");
		assertLeftOut(",\"injectors\":{\"defaultRequire\":-1}", partnerShape(null, false), false, "nativeAbsent=off");
	}

	/**
	 * The same -1 into a method the mod's own platform declares and the merged base lost: natively that injector
	 * applies, so here it is the merge's loss, and a required config's loss is a required one. The method is a row of
	 * the shipped table, picked by shape, so the case follows the table rather than one name.
	 */
	@Test void aPlatformMethodTheMergeLostStaysALossWhateverTheDefault() {
		String[] row = plainRow(Ecosystem.FABRIC);
		owned(Ecosystem.FABRIC);
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(row[0] + ".class", gameClass(row[0], "draw"));
		classes.put(PKG + "/" + MIXIN + ".class", compatMixin(row[0], row[1], null, false));
		assertLeftOut(",\"required\":true,\"injectors\":{\"defaultRequire\":-1}", classes, true,
				row[0] + "#" + row[1] + " is vanilla's, and the merged base lacks it");
	}

	// ---------------------------------------------------------------------------------------------------------------

	private static void assertLeftOut(String extra, Map<String, byte[]> classes, boolean required, String why) {
		CompatibilityFindings.reset();
		assertEquals(List.of(MIXIN), judge(config(extra), classes), why);
		assertTrue(CompatibilityFindings.all().stream().anyMatch(f -> f.id().equals(LEFT_OUT) && f.modId().equals(MOD)
				&& f.confidence() == CompatibilityFinding.Confidence.CONFIRMED && f.required() == required),
				why + ": " + CompatibilityFindings.all());
	}

	/** A method of {@code platform}'s rows with a plain name ({@code [owner, name]}) on a class the rows do not list. */
	private static String[] plainRow(Ecosystem platform) {
		NativeAbsentTargets.Rows rows = NativeAbsentTargets.shipped().of(platform);
		assertNotNull(rows, "the shipped table speaks for " + platform);
		for (Map.Entry<String, Set<String>> owner : rows.methods().entrySet()) {
			if (owner.getKey().contains("$") || rows.mergedOnly().contains(owner.getKey())) continue;
			for (String method : owner.getValue()) {
				String name = method.substring(0, method.indexOf('('));
				if (name.matches("[a-z][A-Za-z0-9]*")) return new String[] {owner.getKey(), name};
			}
		}
		throw new AssertionError("no plain-named row for " + platform);
	}

	/** The config's owner, and its manifest as discovery publishes it: no dependency on the partner mod at all. */
	private static void owned(Ecosystem platform) {
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, MOD, platform)));
		DiscoveredMod mod = new DiscoveredMod(platform, MOD, "1.0", MOD, List.<UnifiedDependency>of(), List.of(CONFIG),
				null, MOD + ".jar");
		if (platform == Ecosystem.FABRIC) ModPresence.publishFabric(List.of(mod));
		else ModPresence.publishForgeFamily(List.of(mod));
	}

	/** The adapter as the boot calls it: the raw view is the classes themselves, on the base the table speaks for. */
	private static List<String> judge(byte[] config, Map<String, byte[]> classes) {
		String staged = NativeAbsentTargets.shipped().base();
		return KernelGuestMixinAdapter.unfitMixins(CONFIG, config, classes::get, null, classes::get, owner -> staged);
	}

	private static byte[] config(String extra) {
		return ("{\"package\":\"" + PKG.replace('/', '.') + "\",\"mixins\":[\"" + MIXIN + "\"]" + extra + "}")
				.getBytes(StandardCharsets.UTF_8);
	}

	/** The game's Plaque (draw() only) and alpha's mixin injecting into beta's added method. */
	private static Map<String, byte[]> partnerShape(Integer require, boolean grouped) {
		Map<String, byte[]> classes = new HashMap<>();
		classes.put(PLAQUE + ".class", gameClass(PLAQUE, "draw"));
		classes.put(PKG + "/" + MIXIN + ".class", compatMixin(PLAQUE, PARTNER_SELECTOR, require, grouped));
		return classes;
	}

	private static byte[] gameClass(String owner, String... methods) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);
		for (String name : methods) {
			MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()V", null, null);
			mv.visitCode();
			mv.visitInsn(Opcodes.RETURN);
			mv.visitMaxs(0, 1);
			mv.visitEnd();
		}
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** No field, no interface: one handler with one {@code @Inject} naming {@code selector}, as a compat mixin is. */
	private static byte[] compatMixin(String target, String selector, Integer require, boolean grouped) {
		ClassWriter cw = new ClassWriter(0);
		cw.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, PKG + "/" + MIXIN, null, "java/lang/Object", null);
		AnnotationVisitor mixin = cw.visitAnnotation("Lorg/spongepowered/asm/mixin/Mixin;", false);
		AnnotationVisitor targets = mixin.visitArray("value");
		targets.visit(null, Type.getObjectType(target));
		targets.visitEnd();
		mixin.visitEnd();
		MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PRIVATE, "alpha$afterCacheable",
				"(Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;)V", null, null);
		AnnotationVisitor inject = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Inject;", true);
		AnnotationVisitor methods = inject.visitArray("method");
		methods.visit(null, selector);
		methods.visitEnd();
		if (require != null) inject.visit("require", require);
		inject.visitEnd();
		if (grouped) {
			AnnotationVisitor group = mv.visitAnnotation("Lorg/spongepowered/asm/mixin/injection/Group;", false);
			group.visit("name", "cache");
			group.visitEnd();
		}
		mv.visitCode();
		mv.visitInsn(Opcodes.RETURN);
		mv.visitMaxs(0, 2);
		mv.visitEnd();
		cw.visitEnd();
		return cw.toByteArray();
	}
}
