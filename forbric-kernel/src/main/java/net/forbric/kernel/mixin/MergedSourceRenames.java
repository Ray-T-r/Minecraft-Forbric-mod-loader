/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.regex.Pattern;

import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * The names Mixin gave, in the class it applied a mixin to, the mixin's own methods that some transported bodies refer
 * to. A transported handler, bridge or wrapper is witnessed by comparing its source body, with the mixin's names
 * rewritten to the merged ones, against the body the defined class carries; Mixin renames every synthetic method it
 * merges (a lambda's implementation becomes {@code md<hash>$<mod>$lambda$...$<n>}), so a handler whose body creates a
 * lambda, or calls one of its mixin's own helpers, compared unequal however faithfully it arrived: PuzzlesLib's
 * Fabric fog-colour callback, absorbed into NeoForge's {@code ClientHooks.getFogColor}, was declared drifted on every
 * definition and skipped on every frame, so its FOG_COLOR event never fired.
 */
final class MergedSourceRenames {
	private MergedSourceRenames() {
	}

	/**
	 * Adds to {@code renamed} each method of {@code source} that {@code bodies} call or take a handle to, and that
	 * {@code renamed} does not name yet, under the one merged copy {@code host} carries of it: a method {@code merged}
	 * attributes to {@code source}, of the same descriptor, named as the original or Mixin's renamed form of it. A
	 * reference with no such copy (a shadowed target member keeps its name) or with two is left as it is.
	 */
	static void addReferenced(List<MethodNode> bodies, String source, ClassNode host, Map<String, String> renamed,
			BiPredicate<MethodNode, String> merged) {
		List<String[]> wanted = new ArrayList<>();
		for (MethodNode body : bodies) {
			if (body == null || body.instructions == null) continue;
			for (AbstractInsnNode instruction : body.instructions) {
				if (instruction instanceof MethodInsnNode call && call.owner.equals(source)) wanted.add(new String[] {call.name, call.desc});
				if (instruction instanceof InvokeDynamicInsnNode dynamic) {
					for (Object argument : dynamic.bsmArgs) {
						if (argument instanceof Handle handle && handle.getOwner().equals(source)) wanted.add(new String[] {handle.getName(), handle.getDesc()});
					}
				}
			}
		}
		for (String[] member : wanted) {
			if (renamed.containsKey(member[0])) continue;
			Pattern renamedForm = Pattern.compile("md[0-9a-f]{1,16}\\$[^$]+\\$" + Pattern.quote(member[0]) + "\\$\\d+");
			MethodNode found = null;
			int candidates = 0;
			for (MethodNode method : host.methods) {
				if (!method.desc.equals(member[1]) || !merged.test(method, source)) continue;
				if (!method.name.equals(member[0]) && !renamedForm.matcher(method.name).matches()) continue;
				candidates++;
				found = method;
			}
			if (candidates == 1) renamed.put(member[0], found.name);
		}
	}
}
