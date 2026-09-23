package net.forbric.kernel.boot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Whether a jar can actually take the Fabric Rendering API's renderer slot.
 *
 * <p>{@code "fabric-renderer-api-v1:contains_renderer"} is a promise: whoever declares it will register a renderer,
 * so Indigo stands down. A Forge-family build may carry the declaration without the code — Sodium 0.9.1's NeoForge
 * build declares it in {@code [modproperties]} yet ships no FRAPI renderer at all (its {@code FRAPIProvider}
 * service has no implementation; the renderer lives only in the Fabric build). When that build wins arbitration,
 * forwarding the promise leaves the slot empty and the first block drawn through FRAPI (a block in an item frame)
 * dies on "Attempted to retrieve active rendering plug-in before one was registered".
 *
 * <p>The evidence is a direct call to one of the two registration entry points, anywhere in the jar or the jars
 * nested in it. A reflective registrar would be missed; the caller keeps the promise when the jar cannot be read.
 */
final class FrapiRendererEvidence {
	private static final byte[] NEEDLE = "fabric/api/client/renderer/v1/Renderer".getBytes(StandardCharsets.UTF_8);
	private static final byte[] IMPL_NEEDLE = "fabric/impl/client/renderer/RendererManager".getBytes(StandardCharsets.UTF_8);

	private FrapiRendererEvidence() { }

	/** @throws IOException when the jar itself is not a readable archive (the caller then keeps the declaration) */
	static boolean registersRenderer(Path jar) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (var entries = zip.entries(); entries.hasMoreElements(); ) {
				ZipEntry entry = entries.nextElement();
				String name = entry.getName();
				if (name.endsWith(".jar")) {
					try (InputStream in = zip.getInputStream(entry)) { if (scan(in, 1)) return true; }
				} else if (name.endsWith(".class")) {
					try (InputStream in = zip.getInputStream(entry)) { if (registers(in.readAllBytes())) return true; }
				}
			}
		}
		return false;
	}
	private static boolean registers(byte[] bytes) {
		return (contains(bytes, NEEDLE) || contains(bytes, IMPL_NEEDLE)) && callsRegister(bytes);
	}

	private static boolean scan(InputStream stream, int depth) throws IOException {
		ZipInputStream zip = new ZipInputStream(stream);
		for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
			String name = entry.getName();
			if (name.endsWith(".jar") && depth < 4) {
				if (scan(new ByteArrayInputStream(zip.readAllBytes()), depth + 1)) return true;
			} else if (name.endsWith(".class")) {
				if (registers(zip.readAllBytes())) return true;
			}
		}
		return false;
	}

	static boolean callsRegister(byte[] bytes) {
		boolean[] found = { false };
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9) {
					@Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
						if (owner.equals("net/fabricmc/fabric/api/client/renderer/v1/Renderer") && method.equals("register")
								|| owner.equals("net/fabricmc/fabric/impl/client/renderer/RendererManager") && method.equals("registerRenderer"))
							found[0] = true;
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return found[0];
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		outer:
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			for (int j = 0; j < needle.length; j++) if (haystack[i + j] != needle[j]) continue outer;
			return true;
		}
		return false;
	}
}
