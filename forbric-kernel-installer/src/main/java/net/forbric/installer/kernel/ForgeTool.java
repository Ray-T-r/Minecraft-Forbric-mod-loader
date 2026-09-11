package net.forbric.installer.kernel;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Runs the Forge build tools the patched-MC pipeline needs. Two execution models, matching the plan:
 * <ul>
 *   <li><b>subprocess</b> — {@code installertools}/{@code mergetool}/{@code binarypatcher} are fatjars that call
 *       {@code System.exit()}; running them in-process would kill the installer, so they are spawned as a child
 *       {@code java -jar} using this JVM's own {@code java} (no external JDK needed).</li>
 *   <li><b>in-process</b> — Forge's {@code AccessTransformerEngine} is driven reflectively on a child
 *       {@link URLClassLoader} (it does not exit; and an ASM-based applier must share the engine's ASM types).
 *       This reproduces the tiny {@code ApplyATForge.java} the dev script compiles on the fly.</li>
 * </ul>
 */
final class ForgeTool {

	private final Consumer<String> log;

	ForgeTool(Consumer<String> log) {
		this.log = log;
	}

	/** Absolute path to the {@code java} launcher of the JVM the installer is running under. */
	static String javaBin() {
		String home = System.getProperty("java.home");
		boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
		return home + java.io.File.separator + "bin" + java.io.File.separator + (win ? "java.exe" : "java");
	}

	/** Spawn {@code java -jar toolJar <args>}; stream output into the log; throw on a non-zero exit. */
	void runJar(Path toolJar, List<String> args, String label) throws IOException {
		List<String> cmd = new ArrayList<>();
		cmd.add(javaBin());
		cmd.add("-jar");
		cmd.add(toolJar.toString());
		cmd.addAll(args);
		runProcess(cmd, label);
	}

	/**
	 * Package-visible because {@link MergedBaseTool} spawns the byte-merge tools the same way — same streaming,
	 * same tail-on-failure — and a second copy of that loop is a second place for it to drift.
	 */
	void runProcess(List<String> cmd, String label) throws IOException {
		log.accept("[patched] " + label + " …");
		ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
		Process p = pb.start();
		List<String> tail = new ArrayList<>();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				tail.add(line);
				if (tail.size() > 40) tail.remove(0);
			}
		}
		int code;
		try {
			code = p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
			throw new IOException("interrupted while running " + label, e);
		}
		if (code != 0) {
			throw new IOException(label + " failed (exit " + code + "):\n  " + String.join("\n  ", tail));
		}
	}

	/**
	 * Apply Forge access transformers to {@code inJar} → {@code outJar} using Forge's own
	 * {@code AccessTransformerEngine} (loaded from {@code engineClasspath}: accesstransformers + asm + log4j).
	 * Reproduces {@code ApplyATForge.java}: for every {@code .class} the engine {@code handlesClass}, read it into
	 * an ASM {@code ClassNode}, {@code transform} it, and re-serialize; all other entries pass through verbatim.
	 */
	void applyAccessTransformers(List<Path> engineClasspath, Path atCfg, Path inJar, Path outJar) throws IOException {
		log.accept("[patched] applying access transformers (Forge AccessTransformerEngine) …");
		URL[] urls = new URL[engineClasspath.size()];
		for (int i = 0; i < engineClasspath.size(); i++) urls[i] = engineClasspath.get(i).toUri().toURL();
		// Parent = platform loader so ASM/AT are the child's, not the installer's (the installer has no ASM anyway).
		try (URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
			Class<?> engineCls = cl.loadClass("net.minecraftforge.accesstransformer.AccessTransformerEngine");
			Object engine = engineCls.getField("INSTANCE").get(null);
			Class<?> identityCls = cl.loadClass("net.minecraftforge.accesstransformer.IdentityNameHandler");
			Object identity = identityCls.getDeclaredConstructor().newInstance();
			method(engineCls, "acceptNaming", 1).invoke(engine, identity);
			method(engineCls, "addResource", 2).invoke(engine, atCfg, "forge_at");

			Class<?> typeCls = cl.loadClass("org.objectweb.asm.Type");
			Method getObjectType = typeCls.getMethod("getObjectType", String.class);
			Class<?> readerCls = cl.loadClass("org.objectweb.asm.ClassReader");
			Class<?> nodeCls = cl.loadClass("org.objectweb.asm.tree.ClassNode");
			Class<?> visitorCls = cl.loadClass("org.objectweb.asm.ClassVisitor");
			Class<?> writerCls = cl.loadClass("org.objectweb.asm.ClassWriter");
			Method handlesClass = method(engineCls, "handlesClass", 1);
			Method transform = method(engineCls, "transform", 2);
			Method readerAccept = readerCls.getMethod("accept", visitorCls, int.class);
			Method nodeAccept = nodeCls.getMethod("accept", visitorCls);
			Method toByteArray = writerCls.getMethod("toByteArray");

			LinkedHashMap<String, byte[]> in = Zips.readAll(inJar);
			LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
			int transformed = 0;
			for (Map.Entry<String, byte[]> e : in.entrySet()) {
				String n = e.getKey();
				byte[] d = e.getValue();
				if (n.endsWith(".class")) {
					Object type = getObjectType.invoke(null, n.substring(0, n.length() - 6));
					if ((Boolean) handlesClass.invoke(engine, type)) {
						Object node = nodeCls.getDeclaredConstructor().newInstance();
						Object reader = readerCls.getDeclaredConstructor(byte[].class).newInstance((Object) d);
						readerAccept.invoke(reader, node, 0);
						transform.invoke(engine, node, type);
						Object writer = writerCls.getDeclaredConstructor(int.class).newInstance(0);
						nodeAccept.invoke(node, writer);
						d = (byte[]) toByteArray.invoke(writer);
						transformed++;
					}
				}
				out.put(n, d);
			}
			Zips.writeJar(outJar, out);
			log.accept("[patched] access transformers applied to " + transformed + " class(es)");
		} catch (IOException e) {
			throw e;
		} catch (ReflectiveOperationException e) {
			throw new IOException("access-transformer step failed: " + e, e);
		}
	}

	/**
	 * Inject a concrete covariant {@code self()} into every patched-MC class that DIRECTLY implements a
	 * {@code net.minecraftforge.common.extensions.IForge*} interface exposing a PUBLIC (non-private) {@code self()}.
	 * On the Forge-patched Mojmap base, under Forbric's flat Knot classloader, such a public interface default does
	 * not reliably resolve on subclasses (observed: {@code ServerPlayer} {@code AbstractMethodError} on
	 * {@code IForgeLivingEntity.self()} during a world tick); a concrete class method always satisfies the
	 * {@code invokeinterface}. The body is {@code return this;} ({@code ALOAD 0; ARETURN}). Idempotent (skips a class
	 * that already declares a matching concrete {@code self()}). In practice this matches only
	 * {@code net.minecraft.world.entity.LivingEntity} / {@code IForgeLivingEntity} today, but the scan is
	 * future-proof. Uses the same reflective-ASM child loader as {@link #applyAccessTransformers}
	 * ({@code asmClasspath} = asm + asm-tree, already downloaded). Mirrors the self() step in
	 * {@code run/build-patched-forge.sh}.
	 */
	int injectCovariantSelf(List<Path> asmClasspath, Path forgeRuntimeJar, Path inJar, Path outJar) throws IOException {
		log.accept("[patched] injecting concrete covariant self() (Forge interface default-dispatch gap) …");
		URL[] urls = new URL[asmClasspath.size()];
		for (int i = 0; i < asmClasspath.size(); i++) urls[i] = asmClasspath.get(i).toUri().toURL();
		try (URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
			Class<?> readerCls = cl.loadClass("org.objectweb.asm.ClassReader");
			Class<?> writerCls = cl.loadClass("org.objectweb.asm.ClassWriter");
			Class<?> visitorCls = cl.loadClass("org.objectweb.asm.ClassVisitor");
			Class<?> nodeCls = cl.loadClass("org.objectweb.asm.tree.ClassNode");
			Class<?> methodNodeCls = cl.loadClass("org.objectweb.asm.tree.MethodNode");
			Class<?> insnListCls = cl.loadClass("org.objectweb.asm.tree.InsnList");
			Class<?> insnNodeCls = cl.loadClass("org.objectweb.asm.tree.InsnNode");
			Class<?> varInsnNodeCls = cl.loadClass("org.objectweb.asm.tree.VarInsnNode");
			Class<?> absInsnCls = cl.loadClass("org.objectweb.asm.tree.AbstractInsnNode");
			Class<?> opcodesCls = cl.loadClass("org.objectweb.asm.Opcodes");

			int accPrivate = opcodesCls.getField("ACC_PRIVATE").getInt(null);
			int accStatic = opcodesCls.getField("ACC_STATIC").getInt(null);
			int accPublic = opcodesCls.getField("ACC_PUBLIC").getInt(null);
			int asmApi = opcodesCls.getField("ASM9").getInt(null);
			int aload = opcodesCls.getField("ALOAD").getInt(null);
			int areturn = opcodesCls.getField("ARETURN").getInt(null);

			Method readerAccept = readerCls.getMethod("accept", visitorCls, int.class);
			Method nodeAccept = nodeCls.getMethod("accept", visitorCls);
			Method toByteArray = writerCls.getMethod("toByteArray");
			Method insnAdd = insnListCls.getMethod("add", absInsnCls);

			java.lang.reflect.Field fName = nodeCls.getField("name");
			java.lang.reflect.Field fInterfaces = nodeCls.getField("interfaces");
			java.lang.reflect.Field fMethods = nodeCls.getField("methods");
			java.lang.reflect.Field mnName = methodNodeCls.getField("name");
			java.lang.reflect.Field mnDesc = methodNodeCls.getField("desc");
			java.lang.reflect.Field mnAccess = methodNodeCls.getField("access");
			java.lang.reflect.Field mnInsns = methodNodeCls.getField("instructions");
			java.lang.reflect.Field mnMaxStack = methodNodeCls.getField("maxStack");
			java.lang.reflect.Field mnMaxLocals = methodNodeCls.getField("maxLocals");

			// Pass 1: Forge extension interfaces that expose a PUBLIC self() → (interface internal name, self() desc).
			Map<String, String> ifaceSelf = new LinkedHashMap<>();
			for (Map.Entry<String, byte[]> e : Zips.readAll(forgeRuntimeJar).entrySet()) {
				String n = e.getKey();
				if (!n.endsWith(".class") || !n.startsWith("net/minecraftforge/common/extensions/IForge")) continue;
				Object node = nodeCls.getDeclaredConstructor().newInstance();
				Object reader = readerCls.getDeclaredConstructor(byte[].class).newInstance((Object) e.getValue());
				readerAccept.invoke(reader, node, 0);
				for (Object mn : (List<?>) fMethods.get(node)) {
					if (!"self".equals(mnName.get(mn))) continue;
					int acc = (Integer) mnAccess.get(mn);
					if ((acc & accPrivate) != 0 || (acc & accStatic) != 0) continue; // private/static self() is not dispatched
					ifaceSelf.put((String) fName.get(node), (String) mnDesc.get(mn));
					break;
				}
			}

			LinkedHashMap<String, byte[]> in = Zips.readAll(inJar);
			LinkedHashMap<String, byte[]> out = new LinkedHashMap<>();
			int injected = 0;
			if (ifaceSelf.isEmpty()) {
				log.accept("[patched] no public covariant self() interfaces in the Forge runtime — nothing to inject");
				out.putAll(in);
			} else {
				for (Map.Entry<String, byte[]> e : in.entrySet()) {
					String n = e.getKey();
					byte[] d = e.getValue();
					if (n.endsWith(".class")) {
						Object node = nodeCls.getDeclaredConstructor().newInstance();
						Object reader = readerCls.getDeclaredConstructor(byte[].class).newInstance((Object) d);
						readerAccept.invoke(reader, node, 0);

						String selfDesc = null;
						for (Object i : (List<?>) fInterfaces.get(node)) {
							String s = ifaceSelf.get(i);
							if (s != null) { selfDesc = s; break; } // directly implements a public-self() interface
						}
						boolean present = false;
						if (selfDesc != null) {
							for (Object mn : (List<?>) fMethods.get(node)) {
								if ("self".equals(mnName.get(mn)) && selfDesc.equals(mnDesc.get(mn))) { present = true; break; }
							}
						}
						if (selfDesc != null && !present) {
							Object mn = methodNodeCls
									.getConstructor(int.class, int.class, String.class, String.class, String.class, String[].class)
									.newInstance(asmApi, accPublic, "self", selfDesc, null, null);
							Object insns = mnInsns.get(mn);
							insnAdd.invoke(insns, varInsnNodeCls.getConstructor(int.class, int.class).newInstance(aload, 0));
							insnAdd.invoke(insns, insnNodeCls.getConstructor(int.class).newInstance(areturn));
							mnMaxStack.setInt(mn, 1);
							mnMaxLocals.setInt(mn, 1);
							@SuppressWarnings("unchecked")
							List<Object> methods = (List<Object>) fMethods.get(node);
							methods.add(mn);
							Object writer = writerCls.getDeclaredConstructor(int.class).newInstance(0);
							nodeAccept.invoke(node, writer);
							d = (byte[]) toByteArray.invoke(writer);
							injected++;
							log.accept("[patched] injected self()" + selfDesc + " into " + n.substring(0, n.length() - 6));
						}
					}
					out.put(n, d);
				}
			}
			Zips.writeJar(outJar, out);
			return injected;
		} catch (IOException e) {
			throw e;
		} catch (ReflectiveOperationException e) {
			throw new IOException("self()-injection step failed: " + e, e);
		}
	}

	/** Find a public method by name + parameter count (avoids brittle exact-signature lookup across loaders). */
	private static Method method(Class<?> c, String name, int argc) throws IOException {
		for (Method m : c.getMethods()) {
			if (m.getName().equals(name) && m.getParameterCount() == argc) {
				m.setAccessible(true);
				return m;
			}
		}
		throw new IOException("no method " + name + "/" + argc + " on " + c.getName());
	}

	/** Verify a file's SHA-1 (used for the Mojang server jar). */
	static void verifySha1(Path file, String expected, String what) throws IOException {
		if (expected == null) return;
		String actual = Util.sha1(file);
		if (!expected.equalsIgnoreCase(actual)) {
			throw new IOException("sha1 mismatch on " + what + " (expected " + expected + ", got " + actual + ")");
		}
	}

	/** Ensure the parent dir of {@code p} exists. */
	static void mkParents(Path p) throws IOException {
		Files.createDirectories(p.getParent());
	}
}
