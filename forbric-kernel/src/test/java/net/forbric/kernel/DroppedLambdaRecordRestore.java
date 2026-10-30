/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import net.forbric.kernel.transform.DuplicateLambdaPruneInjector;

/**
 * Puts {@link DuplicateLambdaPruneInjector}'s drop record back as it was, after every test and after every class.
 *
 * <p>The record is process-wide and every test class in a Gradle worker shares one JVM. A test that records a drop
 * (through the seam, or by running the pruner over a merged class), or forgets them all, hands its record to whatever
 * runs next: MixinHandlerShim, MixinOverloadPin, InsertedLambdaArgumentShim and MixinFit all read it through
 * LambdaShapeEvidence, so a later test's verdict then depends on which test the worker happened to run before it. One
 * leaked entry sat under the real {@code LevelRenderer.lambda$addSkyPass$0}, the key the staged tests decide on.
 *
 * <p>Every test class whose source calls a record seam or constructs the pruner carries
 * {@code @ExtendWith(DroppedLambdaRecordRestore.class)}; DroppedLambdaRecordRestoreGuardTest holds them to it. Per test
 * as well as per class, so two tests of one class cannot depend on each other's order either; what a class's
 * {@code @BeforeAll} records is part of what each of its tests starts from, and is taken away after the class.
 */
public final class DroppedLambdaRecordRestore implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, AfterAllCallback {
	private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(DroppedLambdaRecordRestore.class);
	private static final String SAVED = "saved";

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
		context.getStore(NAMESPACE).put(SAVED, DuplicateLambdaPruneInjector.droppedForTest());
	}

	@SuppressWarnings("unchecked")
	private static void restore(ExtensionContext context) {
		Map<String, List<String>> saved = context.getStore(NAMESPACE).remove(SAVED, Map.class);
		if (saved != null) DuplicateLambdaPruneInjector.restoreDroppedForTest(saved);
	}
}
