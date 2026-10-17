/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * This test makes the kernel catch failures on purpose, so {@link CaughtErrorsExtension} lets them through.
 *
 * <p>With no value, any failure may be caught. With values, every failure caught must mention one of them (in what
 * it was logged with or in the exception), and on a test method each of them must be mentioned by at least one: the
 * test then also proves the failure it is about really happened, and a different, unrelated one still fails it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@Inherited
public @interface ExpectCaught {
	String[] value() default {};
}
