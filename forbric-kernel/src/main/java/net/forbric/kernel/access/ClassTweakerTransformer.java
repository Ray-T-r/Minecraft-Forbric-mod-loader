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

import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import net.fabricmc.classtweaker.api.ClassTweaker;
import net.fabricmc.classtweaker.api.ClassTweakerReader;

import net.forbric.kernel.transform.ClassTransformer;
import net.forbric.kernel.transform.TransformContext;
import net.forbric.kernel.util.ForbricLog;

/**
 * Applies the Fabric ecosystem's access wideners — today's {@code .classtweaker} files (and the legacy
 * {@code .accesswidener} format the same reader accepts).
 *
 * <p>This is not optional decoration: fabric-api's mixins reach private game members through it. Without it,
 * {@code fabric-registry-sync-v0}'s redirect of the private {@code BuiltInRegistries.createContents()} throws
 * {@code IllegalAccessError} the moment {@code Bootstrap} runs. 33 of fabric-api's 43 modules ship one.
 *
 * <p>Beyond widening, a class tweaker can inject interfaces ({@code transitive-inject-interface}, e.g. adding
 * {@code FabricRegistry} to {@code net.minecraft.core.Registry}) and extend enums — the library's class visitor
 * does all three. Enum extension synthesizes classes, which are handed to {@code generatedSink} for the
 * transforming loader to define on demand.
 *
 * <p>Runs in the {@code ACCESS} phase, i.e. before Mixin: the weaver must see the widened members.
 */
public final class ClassTweakerTransformer implements ClassTransformer {
	private final ClassTweaker tweaker;
	private final Set<String> targets;
	private final BiConsumer<String, byte[]> generatedSink;

	private ClassTweakerTransformer(ClassTweaker tweaker, BiConsumer<String, byte[]> generatedSink) {
		this.tweaker = tweaker;
		this.targets = tweaker.getTargets();
		this.generatedSink = generatedSink;
	}

	/**
	 * Merges every mod's class tweaker into one, or returns {@code null} when none were declared.
	 *
	 * @param files        raw {@code .classtweaker}/{@code .accesswidener} contents, in mod order
	 * @param generatedSink receives classes synthesized by enum extension ({@code internalName}, bytes)
	 */
	public static ClassTweakerTransformer create(List<byte[]> files, BiConsumer<String, byte[]> generatedSink) {
		if (files.isEmpty()) return null;

		ClassTweaker tweaker = ClassTweaker.newInstance();
		ClassTweakerReader reader = ClassTweakerReader.create(tweaker);
		String namespace = null;
		int applied = 0;

		for (byte[] file : files) {
			try {
				String fileNamespace = ClassTweakerReader.readHeader(file).getNamespace();

				if (namespace == null) {
					namespace = fileNamespace;
				} else if (!namespace.equals(fileNamespace)) {
					// Merging tweakers written against different namespaces would silently widen the wrong members.
					ForbricLog.warn("[Forbric/Access] skipping a class tweaker in namespace '%s'; the others are '%s'",
							fileNamespace, namespace);
					continue;
				}

				reader.read(file);
				applied++;
			} catch (Throwable t) {
				ForbricLog.warn("[Forbric/Access] could not read a class tweaker: %s", String.valueOf(t));
			}
		}

		if (applied == 0) return null;

		ForbricLog.info("[Forbric/Access] merged %d class tweaker(s) in namespace '%s' over %d target class(es)",
				applied, namespace, tweaker.getTargets().size());
		return new ClassTweakerTransformer(tweaker, generatedSink);
	}

	/** The classes some tweaker touches. The kernel runs Mojmap, which is the {@code official} namespace here. */
	public Set<String> targets() {
		return targets;
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		String internalName = className.replace('.', '/');
		if (!targets.contains(internalName)) return classBytes;

		ClassReader reader = new ClassReader(classBytes);
		// No COMPUTE_FRAMES: widening access flags, injecting an interface and adding enum stubs never change the
		// stack map, and COMPUTE_FRAMES would need to load game classes from inside a class definition.
		ClassWriter writer = new ClassWriter(0);
		ClassVisitor visitor = tweaker.createClassVisitor(Opcodes.ASM9, writer, generatedSink);
		reader.accept(visitor, 0);

		return writer.toByteArray();
	}

	@Override
	public String name() {
		return "class-tweaker";
	}
}
