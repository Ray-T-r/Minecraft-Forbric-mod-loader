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

package net.forbric.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Patches CROSS-RUNTIME-JAR interop gaps for the tri-in-one merged base: classes wholesale-copied from ONE
 * ecosystem's runtime jar (compiled with zero knowledge of the OTHER ecosystem) that no longer satisfy an
 * interface contract the MERGED game jar extended.
 *
 * <p><b>The gap, concretely.</b> {@code net.minecraft.core.Registry$PendingTags} is a plain vanilla interface;
 * NeoForge patches it to additionally {@code extends PendingTagsExtension<T>} (adding an abstract
 * {@code contents(): Map<TagKey<T>, List<Holder<T>>>}). {@code MergedBaseBuilder} correctly takes Neo's version
 * (only Neo hooks this interface) into the merged game jar. But {@code net.minecraftforge.registries.
 * NamespacedWrapper$3} — Forge's own compiled {@code Registry$PendingTags} implementation, living in
 * {@code forge-runtime.jar} (a SEPARATE jar {@code MergedBaseBuilder} never touches) — was compiled against
 * Forge's OWN (un-patched-by-Neo) understanding of that interface, so it lacks {@code contents()} entirely:
 * {@code AbstractMethodError} the first time NeoForge's own code (e.g. {@code ConditionContext}) calls it.
 *
 * <p><b>The fix.</b> {@code NamespacedWrapper$3} already carries a {@code val$newBindings} field of EXACTLY the
 * shape {@code contents()} must return ({@code ImmutableMap<TagKey<T>, List<Holder<T>>>}, itself a {@code Map}) —
 * a trivial delegating getter satisfies the contract with zero new logic. This class synthesizes exactly that.
 *
 * <p>Usage: {@code RuntimeInteropPatcher <forge-runtime.jar> <out.jar>}
 */
public final class RuntimeInteropPatcher {
	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("usage: RuntimeInteropPatcher <forge-runtime.jar> <out.jar>");
			System.exit(2);
		}
		new RuntimeInteropPatcher().run(Path.of(args[0]), Path.of(args[1]));
	}

	/** internal class name -> the trivial delegating method(s) to add: name+desc -> the field to return. */
	private static final Map<String, Map<String, String>> BRIDGE_METHODS = new LinkedHashMap<>();
	static {
		Map<String, String> namespacedWrapper3 = new LinkedHashMap<>();
		// contents():Ljava/util/Map; -> return this.val$newBindings (an ImmutableMap, itself a Map).
		namespacedWrapper3.put("contents()Ljava/util/Map;", "val$newBindings");
		BRIDGE_METHODS.put("net/minecraftforge/registries/NamespacedWrapper$3", namespacedWrapper3);
	}

	void run(Path inJar, Path outJar) throws IOException {
		int patched = 0;
		Files.createDirectories(outJar.toAbsolutePath().getParent());
		try (ZipFile zf = new ZipFile(inJar.toFile());
				ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outJar))) {
			var entries = zf.entries();
			while (entries.hasMoreElements()) {
				ZipEntry e = entries.nextElement();
				byte[] data = zf.getInputStream(e).readAllBytes();
				String internalName = e.getName().endsWith(".class")
						? e.getName().substring(0, e.getName().length() - 6) : null;

				Map<String, String> bridges = internalName != null ? BRIDGE_METHODS.get(internalName) : null;
				if (bridges != null) {
					data = addBridgeMethods(data, bridges);
					patched++;
					System.out.println("[interop-patch] " + internalName + ": added " + bridges.size() + " bridge method(s)");
				}

				zos.putNextEntry(new ZipEntry(e.getName()));
				zos.write(data);
				zos.closeEntry();
			}
		}
		System.out.println("[interop-patch] patched " + patched + " class(es) -> " + outJar);
	}

	private static byte[] addBridgeMethods(byte[] classBytes, Map<String, String> bridges) {
		ClassNode cn = new ClassNode();
		new ClassReader(classBytes).accept(cn, 0);

		for (Map.Entry<String, String> e : bridges.entrySet()) {
			String nameDesc = e.getKey();
			String fieldName = e.getValue();
			int split = nameDesc.indexOf('(');
			String name = nameDesc.substring(0, split);
			String desc = nameDesc.substring(split);

			FieldNode field = findField(cn, fieldName);
			if (field == null) {
				throw new IllegalStateException("bridge field '" + fieldName + "' not found on " + cn.name);
			}

			MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
			m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			m.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, fieldName, field.desc));
			m.instructions.add(new InsnNode(Opcodes.ARETURN));
			m.maxStack = 1;
			m.maxLocals = 1;
			cn.methods.add(m);
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	private static FieldNode findField(ClassNode cn, String name) {
		for (FieldNode f : cn.fields) if (f.name.equals(name)) return f;
		return null;
	}
}
