/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel;

import java.lang.reflect.AnnotatedElement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.platform.commons.support.AnnotationSupport;
import org.opentest4j.AssertionFailedError;
import org.opentest4j.TestAbortedException;

import net.forbric.kernel.util.Caught;

/**
 * Fails a test during which the kernel caught a failure and carried on, unless the test says it meant to.
 *
 * <p>Fail-soft code is exactly what a test cannot see: the call returns, the assertion on its result passes, and
 * whatever went wrong on the way is only a line in output nobody reads. Every such catch reports to {@link Caught}
 * (EveryCatchIsVisibleTest holds them to it), so after each test this asks Caught what it heard. A test that feeds
 * the kernel something broken on purpose says so with {@link ExpectCaught}.
 *
 * <p>Loaded for every test through META-INF/services, like {@link FixturePolicyExtension}. Failures caught outside
 * any test method — in {@code @BeforeAll}, {@code @AfterAll}, or a thread that outlived its test — are judged at the
 * end of the class against the class's own {@code @ExpectCaught}.
 */
public final class CaughtErrorsExtension implements BeforeAllCallback, AfterEachCallback, AfterAllCallback {
	@Override
	public void beforeAll(ExtensionContext context) {
		Caught.reset();
	}

	@Override
	public void afterEach(ExtensionContext context) {
		judge(context.getRequiredTestMethod(), context, true);
	}

	@Override
	public void afterAll(ExtensionContext context) {
		judge(context.getRequiredTestClass(), context, false);
	}

	private static void judge(AnnotatedElement scope, ExtensionContext context, boolean method) {
		List<Caught.Site> caught = Caught.failureSites();
		Caught.reset();
		Optional<ExpectCaught> expect = AnnotationSupport.findAnnotation(scope, ExpectCaught.class);
		if (expect.isEmpty() && method) {
			expect = context.getTestClass().flatMap(c -> AnnotationSupport.findAnnotation(c, ExpectCaught.class));
		}
		if (expect.isEmpty()) {
			if (!caught.isEmpty()) throw new AssertionFailedError(describe("the kernel caught failure(s) during "
					+ context.getDisplayName() + " and carried on. If the test causes them on purpose, mark it "
					+ "@ExpectCaught(\"<what the failure says>\"); otherwise each one is a bug the test just found",
					caught));
			return;
		}
		String[] wanted = expect.get().value();
		if (wanted.length == 0) return;
		List<Caught.Site> unexpected = new ArrayList<>();
		boolean[] seen = new boolean[wanted.length];
		for (Caught.Site site : caught) {
			// What the log line says: the message, and the exception with an initialiser's or reflection's wrapper off.
			String text = site.toString();
			boolean matched = false;
			for (int i = 0; i < wanted.length; i++) {
				if (text.contains(wanted[i])) {
					seen[i] = true;
					matched = true;
				}
			}
			if (!matched) unexpected.add(site);
		}
		if (!unexpected.isEmpty()) throw new AssertionFailedError(describe(context.getDisplayName()
				+ " caught failure(s) its @ExpectCaught does not name", unexpected));
		// Only a method's own annotation promises its failure happens; a class's covers tests that cause none. A test
		// that was skipped (TestFixtures.require on a machine without the fixture) never got as far as causing it.
		if (!method || AnnotationSupport.findAnnotation(scope, ExpectCaught.class).isEmpty()) return;
		if (context.getExecutionException().filter(TestAbortedException.class::isInstance).isPresent()) return;
		List<String> missing = new ArrayList<>();
		for (int i = 0; i < wanted.length; i++) if (!seen[i]) missing.add(wanted[i]);
		if (!missing.isEmpty()) {
			throw new AssertionFailedError(context.getDisplayName() + " expected the kernel to catch a failure "
					+ "mentioning " + missing + ", and it caught " + (caught.isEmpty() ? "none" : caught));
		}
	}

	private static String describe(String headline, List<Caught.Site> sites) {
		StringBuilder out = new StringBuilder(headline).append(':');
		for (Caught.Site site : sites) out.append("\n  ").append(site);
		return out.toString();
	}
}
