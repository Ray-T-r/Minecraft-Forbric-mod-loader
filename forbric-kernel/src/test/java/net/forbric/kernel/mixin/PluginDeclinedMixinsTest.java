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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.forbric.api.Ecosystem;
import net.forbric.api.ModCatalog;

/**
 * A mod that switched a mixin off itself must not be marked for losing it.
 *
 * <p>Only a clear {@code false} from the mod's own config plugin clears the mark. Every other answer — no
 * plugin instance, a throw, a non-boolean — attributes, because the whole value of the Mods screen is that a
 * line on it means something, and guessing in the mod's favour is how that stops being true. Each of those
 * paths gets its own test here, and they are the reason this class is not two lines of code.
 */
@org.junit.jupiter.api.parallel.ResourceLock("system-properties")
class PluginDeclinedMixinsTest {
	@org.junit.jupiter.api.BeforeEach
	@org.junit.jupiter.api.AfterEach
	void clearCompatibilityEvidence() { net.forbric.api.CompatibilityFindings.reset(); }

	private static final String CONFIG = "flashback.mixins.json";
	private static final String MOD = "flashback";
	private static final String MIXIN_ENTRY = "compat.bobby.MixinIntegratedServer";
	private static final String MIXIN_CLASS = "com.moulberry.flashback.mixin." + MIXIN_ENTRY;
	private static final String TARGET = "de.johni0702.minecraft.bobby.mixin.IntegratedServerMixin";
	private static final String OTHER_TARGET = "net.minecraft.client.server.IntegratedServer";
	private static final String DETAIL = "guest mixin " + MIXIN_ENTRY + " did not fit the merged game and was left out";

	private List<ModCatalog.Entry> previous;

	/** A stand-in for {@code mixinconstraints}: says no to the one mixin whose {@code @IfModLoaded} is absent. */
	public static final class DecliningPlugin {
		final List<String> asked = new ArrayList<>();

		public boolean shouldApplyMixin(String target, String mixin) {
			asked.add(target + "|" + mixin);
			return !MIXIN_CLASS.equals(mixin);
		}
	}

	public static final class AcceptingPlugin {
		public boolean shouldApplyMixin(String target, String mixin) {
			return true;
		}
	}

	/** Decides by target, as a plugin reading {@code targetClassName} does: no for {@link #TARGET}, yes elsewhere. */
	public static final class TargetDecidingPlugin {
		final List<String> asked = new ArrayList<>();

		public boolean shouldApplyMixin(String target, String mixin) {
			asked.add(target);
			return !TARGET.equals(target);
		}
	}

	public static final class ThrowingPlugin {
		public boolean shouldApplyMixin(String target, String mixin) {
			throw new IllegalStateException("probed the wrong platform");
		}
	}

	@BeforeEach
	void seed() {
		previous = ModCatalog.everything();
		PluginDeclinedMixins.reset();
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, MOD, Ecosystem.FABRIC)));
		ModCatalog.publish(List.of(
				new ModCatalog.Entry(Ecosystem.FABRIC, MOD, "Flashback", "0.43.4", "", List.of(), "f.jar", "", "")));
	}

	@AfterEach
	void restore() {
		PluginDeclinedMixins.reset();
		MixinConfigOwners.reset();
		ModCatalog.publish(previous);
		System.clearProperty(PluginDeclinedMixins.PROPERTY);
	}

	@Test
	void anAttributionIsHeldBackUntilTheReportAsks() {
		assertTrue(defer(DecliningPlugin.class), "a config with a plugin defers");
		assertEquals(1, PluginDeclinedMixins.pending());
		assertTrue(ModCatalog.failures().isEmpty(),
				"nothing may be marked before the plugin has been asked — the Mods screen is read as final");
	}

	@Test
	void aPluginThatDeclinesTheMixinClearsTheMod() {
		DecliningPlugin plugin = new DecliningPlugin();
		PluginDeclinedMixins.rememberPlugin(plugin);
		defer(DecliningPlugin.class);

		PluginDeclinedMixins.resolve();

		assertTrue(ModCatalog.failures().isEmpty(),
				"the mod switched this mixin off itself, so leaving it out cost it nothing");
		assertEquals(List.of(TARGET + "|" + MIXIN_CLASS), plugin.asked,
				"the plugin must be asked the same question Mixin would have: its target and its mixin");
	}

	@Test
	void aPluginThatWantsTheMixinStillMarksTheMod() {
		PluginDeclinedMixins.rememberPlugin(new AcceptingPlugin());
		defer(AcceptingPlugin.class);

		PluginDeclinedMixins.resolve();

		assertEquals(1, ModCatalog.failures().size(), "the mod wanted this mixin and lost it");
		assertEquals(DETAIL, ModCatalog.failures().get(0).statusDetail());
	}

	/**
	 * The kernel left the mixin out of BOTH targets, and Mixin would have asked about each one. A plugin that
	 * declines the first and accepts the second still wanted the mixin on the second, so the loss stands — and
	 * asking about the first target alone read it as "the mod switched this off itself".
	 */
	@Test
	void aPluginThatDeclinesOnlySomeTargetsStillMarksTheMod() {
		TargetDecidingPlugin plugin = new TargetDecidingPlugin();
		PluginDeclinedMixins.rememberPlugin(plugin);
		assertTrue(PluginDeclinedMixins.defer(CONFIG, TargetDecidingPlugin.class.getName(), MIXIN_ENTRY, MIXIN_CLASS,
				List.of(TARGET, OTHER_TARGET), DETAIL, net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, true,
				List.of("kernel suppressed the mixin")));

		PluginDeclinedMixins.resolve();

		assertEquals(List.of(TARGET, OTHER_TARGET), plugin.asked, "asked once per target, as Mixin asks");
		assertEquals(1, ModCatalog.failures().size(), "the mixin was wanted on " + OTHER_TARGET + " and lost there");
		var finding = net.forbric.api.CompatibilityFindings.confirmedRequired().getFirst();
		assertTrue(finding.evidence().stream().anyMatch(e -> e.contains("declines it for " + TARGET)), finding.evidence().toString());
		assertTrue(finding.evidence().stream().anyMatch(e -> e.endsWith("for " + OTHER_TARGET)), finding.evidence().toString());
	}

	/** The other half: a plugin that declines every target clears the mod exactly as the single-target case does. */
	@Test
	void aPluginThatDeclinesEveryTargetClearsTheMod() {
		DecliningPlugin plugin = new DecliningPlugin();
		PluginDeclinedMixins.rememberPlugin(plugin);
		assertTrue(PluginDeclinedMixins.defer(CONFIG, DecliningPlugin.class.getName(), MIXIN_ENTRY, MIXIN_CLASS,
				List.of(TARGET, OTHER_TARGET), DETAIL, net.forbric.api.CompatibilityFinding.Confidence.CONFIRMED, true,
				List.of("kernel suppressed the mixin")));

		PluginDeclinedMixins.resolve();

		assertEquals(List.of(TARGET + "|" + MIXIN_CLASS, OTHER_TARGET + "|" + MIXIN_CLASS), plugin.asked);
		assertTrue(ModCatalog.failures().isEmpty());
		assertTrue(net.forbric.api.CompatibilityFindings.confirmedRequired().isEmpty());
	}

	@Test
	void aPluginThatThrowsMarksTheMod() {
		PluginDeclinedMixins.rememberPlugin(new ThrowingPlugin());
		defer(ThrowingPlugin.class);

		PluginDeclinedMixins.resolve();

		assertEquals(1, ModCatalog.failures().size(),
				"a plugin with no usable answer is not a licence to drop the finding");
	}

	@Test
	void aPluginThatWasNeverBuiltMarksTheMod() {
		defer(DecliningPlugin.class);

		PluginDeclinedMixins.resolve();

		assertEquals(1, ModCatalog.failures().size(),
				"no instance means no answer, and no answer attributes exactly as before this class existed");
	}

	@Test
	void resolveIsIdempotent() {
		PluginDeclinedMixins.rememberPlugin(new AcceptingPlugin());
		defer(AcceptingPlugin.class);

		PluginDeclinedMixins.resolve();
		PluginDeclinedMixins.resolve();

		assertEquals(0, PluginDeclinedMixins.pending());
		assertEquals(1, ModCatalog.failures().size(), "the report is written up to three times a boot");
	}

	@Test
	void aConfigWithoutAPluginIsNotDeferred() {
		assertFalse(PluginDeclinedMixins.defer(CONFIG, null, MIXIN_ENTRY, MIXIN_CLASS, TARGET, DETAIL),
				"with no plugin there is nobody to ask, so the caller must mark the mod itself");
		assertEquals(0, PluginDeclinedMixins.pending());
	}

	@Test
	void theSwitchAttributesEverything() {
		System.setProperty(PluginDeclinedMixins.PROPERTY, "off");
		assertFalse(defer(DecliningPlugin.class),
				"-Dforbric.pluginDeclinedMixins=off puts the mark back where it was");
		assertEquals(0, PluginDeclinedMixins.pending());
	}

	/**
	 * The capture itself, through the real guard: a plugin class transformed by
	 * {@link net.forbric.kernel.transform.GuestMixinPluginGuard}, defined, and asked {@code onLoad} the way
	 * Mixin asks it. Nothing else in the chain has a chance to hand the instance over, so if the generated
	 * {@code onLoad} wrapper loses it, the mod is marked for a mixin it switched off — and that is what this
	 * asserts, end to end, rather than looking at the bytecode.
	 */
	@Test
	void theGuardsOnLoadWrapperIsWhatCapturesTheInstance() throws Exception {
		byte[] plugin = decliningPluginClass();
		byte[] guarded = new net.forbric.kernel.transform.GuestMixinPluginGuard().transform(GUEST_PLUGIN, plugin,
				new net.forbric.kernel.transform.TransformContext(net.fabricmc.api.EnvType.CLIENT, false, "intermediary"));
		assertTrue(guarded != plugin, "the stand-in must actually be recognised as a config plugin");

		Class<?> type = new ClassLoader(PluginDeclinedMixinsTest.class.getClassLoader()) {
			Class<?> define(byte[] b) {
				return defineClass(GUEST_PLUGIN, b, 0, b.length);
			}
		}.define(guarded);
		Object instance = type.getDeclaredConstructor().newInstance();
		type.getMethod("onLoad", String.class).invoke(instance, "com.moulberry.flashback.mixin");

		assertTrue(PluginDeclinedMixins.defer(CONFIG, GUEST_PLUGIN, MIXIN_ENTRY, MIXIN_CLASS, TARGET, DETAIL));
		PluginDeclinedMixins.resolve();

		assertTrue(ModCatalog.failures().isEmpty(),
				"onLoad did not hand the instance over, so nobody could ask whether the mod wanted this mixin");
	}

	private static final String GUEST_PLUGIN = "com.example.GuestConstraintsPlugin";

	/** A minimal {@code IMixinConfigPlugin} whose {@code shouldApplyMixin} always says no. */
	private static byte[] decliningPluginClass() {
		org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(org.objectweb.asm.ClassWriter.COMPUTE_MAXS);
		int acc = org.objectweb.asm.Opcodes.ACC_PUBLIC;
		cw.visit(org.objectweb.asm.Opcodes.V21, acc, GUEST_PLUGIN.replace('.', '/'), null, "java/lang/Object",
				new String[] {"org/spongepowered/asm/mixin/extensibility/IMixinConfigPlugin"});

		org.objectweb.asm.MethodVisitor ctor = cw.visitMethod(acc, "<init>", "()V", null, null);
		ctor.visitCode();
		ctor.visitVarInsn(org.objectweb.asm.Opcodes.ALOAD, 0);
		ctor.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
		ctor.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		ctor.visitMaxs(0, 0);
		ctor.visitEnd();

		org.objectweb.asm.MethodVisitor should = cw.visitMethod(acc, "shouldApplyMixin",
				"(Ljava/lang/String;Ljava/lang/String;)Z", null, null);
		should.visitCode();
		should.visitInsn(org.objectweb.asm.Opcodes.ICONST_0);
		should.visitInsn(org.objectweb.asm.Opcodes.IRETURN);
		should.visitMaxs(0, 0);
		should.visitEnd();

		org.objectweb.asm.MethodVisitor onLoad = cw.visitMethod(acc, "onLoad", "(Ljava/lang/String;)V", null, null);
		onLoad.visitCode();
		onLoad.visitInsn(org.objectweb.asm.Opcodes.RETURN);
		onLoad.visitMaxs(0, 0);
		onLoad.visitEnd();

		cw.visitEnd();
		return cw.toByteArray();
	}

	private static boolean defer(Class<?> plugin) {
		return PluginDeclinedMixins.defer(CONFIG, plugin.getName(), MIXIN_ENTRY, MIXIN_CLASS, TARGET, DETAIL);
	}
}
