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
	}

	private final Map<String, ClassEntry> byClass = new HashMap<>();

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
			} else {
				entry.fields.computeIfAbsent(d.memberName, k -> new Member()).merge(d);
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

	@Override
	public String name() {
		return "forbric:access-transformer";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		ClassEntry entry = byClass.get(className.replace('.', '/'));
		if (entry == null) return classBytes;

		ClassReader reader = new ClassReader(classBytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		reader.accept(new AtClassVisitor(writer, entry), 0);
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

		AtClassVisitor(ClassVisitor delegate, ClassEntry entry) {
			super(Opcodes.ASM9, delegate);
			this.entry = entry;
		}

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			super.visit(version, apply(access, entry.clazz), name, signature, superName, interfaces);
		}

		@Override
		public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
			Member member = combined(entry.fields.get(name), entry.allFields);
			return super.visitField(apply(access, member), name, descriptor, signature, value);
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			Member member = combined(entry.methods.get(name + descriptor), entry.allMethods);
			return super.visitMethod(apply(access, member), name, descriptor, signature, exceptions);
		}
	}
}
