/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import net.forbric.api.Ecosystem;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import net.forbric.kernel.classloading.ForbricClassLoader;

/**
 * {@link MergedBaseMixinCompat#discover} judges a source mixin before the mixin's own family is noted, so the class it
 * was compiled against comes from the family of the mod that declared its config, published beforehand
 * ({@link MixinConfigOwners#publish}). Driven end to end — the config's JSON, the mixin's bytes and the staged merged
 * base's native-reference index behind the bound {@link NativeGameReferences} — with a deferred registry freeze that is
 * not fabric-registry-sync's: another mod's names, its point written without a descriptor, which only the native
 * {@code Bootstrap.bootStrap()} it was written for can decide, and no tracker callback, so it cannot be adapted.
 *
 * <p>Declared by a Fabric mod, the gate reads that point in vanilla's method and refuses the mixin. Declared by nobody
 * (or by more than one mod) there is no family to ask and no native class: only the full spelling is the protocol, so the
 * shortened one goes through unrecognised, while the full one is refused either way.
 */
@ResourceLock("native-game-references")
class MergedBaseDiscoveryFamilyTest {
	private static final String BOOTSTRAP = "net/minecraft/server/Bootstrap";
	private static final String REGISTRIES = "net/minecraft/core/registries/BuiltInRegistries";
	private static final String MIXIN = "org/example/pantry/mixin/LateFreezeMixin";
	private static final String CONFIG = "pantry.mixins.json";
	private static final byte[] JSON = "{\"package\":\"org.example.pantry.mixin\",\"mixins\":[\"LateFreezeMixin\"]}".getBytes(StandardCharsets.UTF_8);
	private static final String DEFERS = "source callback repeats or defers the kernel-owned registry freeze; its tracker protocol could not be adapted";
	private static final String NO_DESCRIPTOR = "L" + REGISTRIES + ";bootStrap";
	private static final String FULL = "L" + REGISTRIES + ";bootStrap()V";

	private Path merged;
	private ForbricClassLoader loader;

	@BeforeEach void bindTheMergedBasesNativeReferences() throws Exception {
		merged = TestFixtures.stagedRoot().resolve("merged-base/patched-mc-merged-26.2.jar");
		TestFixtures.requireFiles(Fixture.STAGED, "the staged merged base and its native-reference index", merged);
		MergedBaseMixinCompat.reset();
		MixinConfigOwners.reset();
		MixinStubRebind.forget();
		loader = new ForbricClassLoader(new java.net.URL[] { merged.toUri().toURL() }, getClass().getClassLoader());
		NativeGameReferences.bind(loader);
		assertNotNull(NativeGameReferences.reference(Ecosystem.FABRIC, BOOTSTRAP), "premise: the merged base indexes vanilla's Bootstrap");
	}

	@AfterEach void reset() throws Exception {
		NativeGameReferences.bind(null);
		if (loader != null) loader.close();
		MergedBaseMixinCompat.reset();
		MixinConfigOwners.reset();
		MixinStubRebind.forget();
	}

	@Test void aShortenedDeferredFreezeIsRefusedThroughTheFamilyOfTheModThatDeclaredItsConfig() throws Exception {
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, "pantry", Ecosystem.FABRIC)));
		assertNull(MixinStubRebind.ecosystemOf(MIXIN), "premise: the mixin's own family is not noted yet");
		discover(NO_DESCRIPTOR);
		assertEquals(DEFERS, MergedBaseMixinCompat.reason(CONFIG, "LateFreezeMixin"));
		assertEquals(List.of("LateFreezeMixin"), ForbricMixinService.suppressedMixinsFor(CONFIG));
	}

	@Test void withNoDeclaringFamilyTheShortenedPointIsNotTheProtocol() throws Exception {
		discover(NO_DESCRIPTOR);
		assertNull(MergedBaseMixinCompat.reason(CONFIG, "LateFreezeMixin"));
		assertTrue(ForbricMixinService.suppressedMixinsFor(CONFIG).isEmpty());
		// Two mods claiming the config: no single family either.
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, "pantry", Ecosystem.FABRIC),
				new MixinConfigOwners.Owned(CONFIG, "larder", Ecosystem.FABRIC)));
		discover(NO_DESCRIPTOR);
		assertNull(MergedBaseMixinCompat.reason(CONFIG, "LateFreezeMixin"));
	}

	/** The full spelling needs no native class: refused whoever declared it, so the controls above are not a silent discovery. */
	@Test void theFullSpellingIsRefusedWithOrWithoutTheDeclaringFamily() throws Exception {
		discover(FULL);
		assertEquals(DEFERS, MergedBaseMixinCompat.reason(CONFIG, "LateFreezeMixin"));
		MixinConfigOwners.publish(List.of(new MixinConfigOwners.Owned(CONFIG, "pantry", Ecosystem.FABRIC)));
		discover(FULL);
		assertEquals(DEFERS, MergedBaseMixinCompat.reason(CONFIG, "LateFreezeMixin"));
	}

	// ---- fixtures ----------------------------------------------------------------------------------------------------------

	/** Discovers {@link #CONFIG} with the mixin's bytes and, for every other class, the staged merged base's. */
	private void discover(String point) throws Exception {
		byte[] mixin = bytes(lateFreeze(point));
		try (ZipFile jar = new ZipFile(merged.toFile())) {
			Function<String, byte[]> resources = name -> {
				if (name.equals(MIXIN + ".class")) return mixin;
				try {
					var entry = jar.getEntry(name);
					return entry == null ? null : jar.getInputStream(entry).readAllBytes();
				} catch (java.io.IOException unreadable) {
					throw new java.io.UncheckedIOException(unreadable);
				}
			};
			MergedBaseMixinCompat.discover(CONFIG, JSON, resources);
		}
	}

	/**
	 * A static {@code @Redirect} of {@code bootStrap()}'s call at {@code point} that builds the registries' contents instead
	 * of freezing them — the deferred freeze — and nothing else: no tracker callback the adapter could keep.
	 */
	private static ClassNode lateFreeze(String point) {
		ClassNode mixin = new ClassNode();
		mixin.version = Opcodes.V21;
		mixin.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		mixin.name = MIXIN;
		mixin.superName = "java/lang/Object";
		AnnotationNode target = new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");
		target.values = new ArrayList<>(List.of("value", new ArrayList<>(List.of(Type.getObjectType(BOOTSTRAP)))));
		mixin.invisibleAnnotations = new ArrayList<>(List.of(target));
		MethodNode redirect = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "stockShelves", "()V", null, null);
		redirect.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, REGISTRIES, "createContents", "()V", false));
		redirect.instructions.add(new InsnNode(Opcodes.RETURN));
		AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
		at.values = new ArrayList<>(List.of("value", "INVOKE", "target", point));
		AnnotationNode injector = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
		injector.values = new ArrayList<>(List.of("method", new ArrayList<>(List.of("bootStrap")), "at", at));
		redirect.visibleAnnotations = new ArrayList<>(List.of(injector));
		mixin.methods.add(redirect);
		return mixin;
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}
}
