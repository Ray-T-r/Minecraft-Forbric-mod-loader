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

package net.forbric.kernel.access;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.forbric.kernel.transform.ClassTransformer;
import net.forbric.kernel.transform.TransformContext;

/**
 * Applies parsed Access Transformer {@link AtDirective}s (already remapped to the runtime namespace) to the
 * game and mod classes flowing through the {@code ACCESS} phase of the unified transform chain. Widening is
 * <em>monotonic</em> (a member is never made less visible), and {@code final} is stripped/added per the
 * {@code -f}/{@code +f} directive. Classes with no directive are passed through untouched.
 */
public final class AccessTransformer implements ClassTransformer {
	private static final class Member {
		AtAccess access;
		AtDirective.FinalOp finalOp = AtDirective.FinalOp.LEAVE;

		void merge(AtDirective d) {
			if (access == null || d.access.rank() > access.rank()) access = d.access;
			if (d.finalOp != AtDirective.FinalOp.LEAVE) finalOp = d.finalOp;
		}
	}

	private static final class ClassEntry {
		final Member clazz = new Member();
		final Member allFields = new Member();
		final Member allMethods = new Member();
		final Map<String, Member> fields = new HashMap<>();
		final Map<String, Member> methods = new HashMap<>();
		/** The specific-member directives, so a member the class turns out not to have can be named. */
		final List<AtDirective> specific = new ArrayList<>();
	}

	private final Map<String, ClassEntry> byClass = new HashMap<>();
	private final java.util.Set<AtDirective> missedMembers = java.util.concurrent.ConcurrentHashMap.newKeySet();

	public AccessTransformer(List<AtDirective> remappedDirectives) {
		for (AtDirective d : remappedDirectives) {
			ClassEntry entry = byClass.computeIfAbsent(d.className, k -> new ClassEntry());

			if (d.isClass()) {
				entry.clazz.merge(d);
			} else if (d.isAllFields()) {
				entry.allFields.merge(d);
			} else if (d.isAllMethods()) {
				entry.allMethods.merge(d);
			} else if (d.method) {
				entry.methods.computeIfAbsent(d.memberName + d.memberDesc, k -> new Member()).merge(d);
				entry.specific.add(d);
			} else {
				entry.fields.computeIfAbsent(d.memberName, k -> new Member()).merge(d);
				entry.specific.add(d);
			}
		}
	}

	/** Whether any directive targets the given internal class name (fast path for the chain). */
	public boolean handles(String internalName) {
		return byClass.containsKey(internalName);
	}

	public int size() {
		return byClass.size();
	}

	/** Restore missed explicit rules only; wildcards must not widen unrelated new kernel members. */
	public byte[] replayRestored(String className, byte[] bytes, TransformContext context) {
		ClassEntry entry = byClass.get(className.replace('.', '/'));
		if (entry == null) return bytes;
		var node = new org.objectweb.asm.tree.ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		java.util.Set<String> fields = new java.util.HashSet<>(), methods = new java.util.HashSet<>();
		for (var field : node.fields) fields.add(field.name);
		for (var method : node.methods) methods.add(method.name + method.desc);
		List<AtDirective> restored = entry.specific.stream().filter(d ->
				(d.method ? methods.contains(d.memberName + d.memberDesc) : fields.contains(d.memberName))
				&& missedMembers.contains(d)).toList();
		if (restored.isEmpty()) return bytes;
		byte[] result = new AccessTransformer(restored).transform(className, bytes, context);
		for (AtDirective d : restored) AccessCensus.restored("AT", d.source, d.toString());
		return java.util.Arrays.equals(result, bytes) ? bytes : result;
	}

	@Override
	public String name() {
		return "forbric:access-transformer";
	}

	@Override
	public net.forbric.kernel.transform.AnchorSet anchors() {
		return net.forbric.kernel.transform.AnchorSet.scanned("every class an accesstransformer.cfg names; a directive that meets "
				+ "no member is counted by AccessCensus, not by the anchor ledger");
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		ClassEntry entry = byClass.get(className.replace('.', '/'));
		if (entry == null) return classBytes;

		ClassReader reader = new ClassReader(classBytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		reader.accept(new AtClassVisitor(writer, entry, missedMembers::add), 0);
		AccessCensus.transformed();
		return writer.toByteArray();
	}

	private static int apply(int access, Member member) {
		int result = access;
		if (member.access != null) result = member.access.apply(result);

		if (member.finalOp == AtDirective.FinalOp.STRIP) {
			result &= ~Opcodes.ACC_FINAL;
		} else if (member.finalOp == AtDirective.FinalOp.MAKE) {
			result |= Opcodes.ACC_FINAL;
		}

		return result;
	}

	private static Member combined(Member specific, Member wildcard) {
		if (specific == null) return wildcard;

		Member result = new Member();
		result.access = wildcard.access;
		result.finalOp = wildcard.finalOp;
		if (specific.access != null && (result.access == null || specific.access.rank() > result.access.rank())) {
			result.access = specific.access;
		}
		if (specific.finalOp != AtDirective.FinalOp.LEAVE) result.finalOp = specific.finalOp;
		return result;
	}

	private static final class AtClassVisitor extends ClassVisitor {
		private final ClassEntry entry;
		private final java.util.function.Consumer<AtDirective> missed;
		private final java.util.Set<String> seenFields = new java.util.HashSet<>();
		private final java.util.Set<String> seenMethods = new java.util.HashSet<>();
		private final java.util.Set<String> seenMethodNames = new java.util.HashSet<>();

		AtClassVisitor(ClassVisitor delegate, ClassEntry entry, java.util.function.Consumer<AtDirective> missed) {
			super(Opcodes.ASM9, delegate);
			this.entry = entry;
			this.missed = missed;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			super.visit(version, apply(access, entry.clazz), name, signature, superName, interfaces);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			seenFields.add(name);
			Member member = combined(entry.fields.get(name), entry.allFields);
			return super.visitField(apply(access, member), name, descriptor, signature, value);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			seenMethods.add(name + descriptor);
			seenMethodNames.add(name);
			Member member = combined(entry.methods.get(name + descriptor), entry.allMethods);
			return super.visitMethod(apply(access, member), name, descriptor, signature, exceptions);
		}

		@Override
		public void visitEnd() {
			for (AtDirective d : entry.specific) {
				boolean seen = d.method ? seenMethods.contains(d.memberName + d.memberDesc) : seenFields.contains(d.memberName);
				// An AT names a field by name alone, so a field that is there IS matched whatever its descriptor;
				// a method present under another descriptor is reported but not judged (see AccessCensus).
				if (!seen) {
					missed.accept(d);
					AccessCensus.unmatched("AT", d.source, d.toString(), false, d.method && seenMethodNames.contains(d.memberName));
				}
			}
			super.visitEnd();
		}
	}
}
