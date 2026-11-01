/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.mixin.LambdaShapeEvidence;
import net.forbric.kernel.mixin.NativeGameReferences;

/**
 * The FABRIC native reference a unit test hands {@link NativeGameReferences} (and so {@link LambdaShapeEvidence} and the
 * shims that read the class a Fabric mod was compiled against): vanilla's shape of a lambda, declared by owner, name and
 * descriptor, as the vanilla-reference sidecar would answer it in a launch. A test that declares one carries
 * {@code @ExtendWith(VanillaLambdaReference.class)}, which puts the declarations back as they were after every test and
 * every class (VanillaLambdaReferenceGuardTest holds them to it).
 *
 * <p>The seam is process-wide and every test class in a Gradle worker shares one JVM, so a declaration left behind would
 * decide what MixinHandlerShim, MixinOverloadPin, InsertedLambdaArgumentShim and MixinFit see in whatever class runs next.
 * Until Stage 4 the same evidence came from the duplicate-lambda pruner's drop record, restored by an extension of this
 * shape; NeoForge's base carries no lambda name twice, the pruner went, and the reference is the evidence that remains.
 */
public final class VanillaLambdaReference implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {
	private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(VanillaLambdaReference.class);
	private static final String SAVED = "saved";
	/** owner -> lambda name -> the descriptors vanilla declares it with. */
	private static final Map<String, Map<String, List<String>>> DECLARED = new LinkedHashMap<>();

	/** Vanilla declares {@code lambda} with {@code descriptor} in {@code owner}. */
	public static synchronized void declare(String owner, String lambda, String descriptor) {
		DECLARED.computeIfAbsent(owner, o -> new LinkedHashMap<>()).computeIfAbsent(lambda, n -> new ArrayList<>()).add(descriptor);
		install();
	}

	/** No declaration at all: the reference answers nothing. */
	public static synchronized void forget() {
		DECLARED.clear();
		install();
	}

	/** The descriptors declared for {@code owner.lambda}. */
	public static synchronized List<String> declared(String owner, String lambda) {
		return List.copyOf(DECLARED.getOrDefault(owner, Map.of()).getOrDefault(lambda, List.of()));
	}

	/** A copy of every declaration, for {@link #restore}. */
	public static synchronized Map<String, Map<String, List<String>>> snapshot() {
		Map<String, Map<String, List<String>>> copy = new LinkedHashMap<>();
		DECLARED.forEach((owner, lambdas) -> {
			Map<String, List<String>> inner = new LinkedHashMap<>();
			lambdas.forEach((name, descriptors) -> inner.put(name, List.copyOf(descriptors)));
			copy.put(owner, inner);
		});
		return copy;
	}

	/** The declarations become exactly {@code saved}, a {@link #snapshot}. */
	public static synchronized void restore(Map<String, Map<String, List<String>>> saved) {
		DECLARED.clear();
		saved.forEach((owner, lambdas) -> {
			Map<String, List<String>> inner = new LinkedHashMap<>();
			lambdas.forEach((name, descriptors) -> inner.put(name, new ArrayList<>(descriptors)));
			DECLARED.put(owner, inner);
		});
		install();
	}

	/** The seam reads the declarations while there are any; with none the launch's reference is back. */
	private static void install() {
		NativeGameReferences.referenceForTest(DECLARED.isEmpty() ? null
				: (ecosystem, owner) -> ecosystem == Ecosystem.FABRIC ? reference(owner) : null);
	}

	private static synchronized ClassNode reference(String owner) {
		Map<String, List<String>> lambdas = DECLARED.get(owner);
		if (lambdas == null) return null;
		ClassNode node = new ClassNode();
		node.name = owner;
		node.methods = new ArrayList<>();
		lambdas.forEach((name, descriptors) -> {
			for (String descriptor : descriptors) node.methods.add(new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, name, descriptor, null, null));
		});
		return node;
	}

	@Override
	public void beforeAll(ExtensionContext context) {
		save(context);
	}

	@Override
	public void beforeEach(ExtensionContext context) {
		save(context);
	}

	@Override
	public void afterEach(ExtensionContext context) {
		restore(context);
	}

	@Override
	public void afterAll(ExtensionContext context) {
		restore(context);
	}

	/** Each context has its own store, so a test's copy never overwrites its class's. */
	private static void save(ExtensionContext context) {
		context.getStore(NAMESPACE).put(SAVED, snapshot());
	}

	@SuppressWarnings("unchecked")
	private static void restore(ExtensionContext context) {
		Map<String, Map<String, List<String>>> saved = context.getStore(NAMESPACE).remove(SAVED, Map.class);
		if (saved != null) restore(saved);
	}
}
