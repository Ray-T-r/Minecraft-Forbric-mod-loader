/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.api;

import java.util.function.Function;

/** A stateful superclass removed during merging requires an explicit, verifiable composition protocol. */
@FunctionalInterface
public interface AncestorComposition {
    record Requirement(String owner, String retainedSuperclass, String sourceSuperclass) { }
    /** Return true only when the final definition and the actual source state protocol are both accounted for. */
    boolean proves(Requirement requirement, byte[] finalDefinition, Function<String, byte[]> resources);
}
