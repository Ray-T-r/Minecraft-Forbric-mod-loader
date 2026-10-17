package net.forbric.kernel.transfer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import net.forbric.kernel.transform.TransferTransactionHooks;

/**
 * The transfer test classpath as URLs for a scenario's own game loader, optionally with Fabric's transfer module
 * replaced by a copy whose implementation lives under other names: every class under {@code net/fabricmc/fabric/impl/}
 * moved to {@code org/quarry/hidden/} with a meaningless simple name, and every private method of those classes renamed.
 * The public API keeps its names, as it would for a mod calling it. Hooks that key on what the engine does pair the
 * relocated engine exactly as the original; hooks that key on its names would find nothing.
 */
final class TransferTestClasspath {
	static final String IMPL = "net/fabricmc/fabric/impl/";
	static final String HIDDEN = "org/quarry/hidden/";
	private static final Path RELOCATED = Path.of("build/transfer-relocated/fabric-transfer-api-relocated.jar");

	private TransferTestClasspath() {
	}

	static URL[] urls(boolean relocated) throws IOException {
		String[] entries = System.getProperty("forbric.transferTestClasspath").split(File.pathSeparator);
		URL[] urls = new URL[entries.length];
		boolean replaced = false;
		for (int i = 0; i < entries.length; i++) {
			Path entry = Path.of(entries[i]);
			if (relocated && entry.getFileName().toString().startsWith("fabric-transfer-api-") && entry.toString().endsWith(".jar")) {
				entry = relocatedTransferApi(entry);
				replaced = true;
			}
			urls[i] = entry.toUri().toURL();
		}
		if (relocated && !replaced) throw new IllegalStateException("no fabric-transfer-api jar on the transfer test classpath");
		return urls;
	}

	/** The kernel's pairing hooks, reading class bytes from {@code game}'s own URLs. */
	static TransferTransactionHooks hooks(URLClassLoader game) {
		return new TransferTransactionHooks(name -> {
			URL resource = game.findResource(name + ".class");
			if (resource == null) return null;
			try (InputStream in = resource.openStream()) { return in.readAllBytes(); }
			catch (IOException unreadable) { return null; }
		});
	}

	private static synchronized Path relocatedTransferApi(Path original) throws IOException {
		if (Files.isRegularFile(RELOCATED) && Files.getLastModifiedTime(RELOCATED).compareTo(Files.getLastModifiedTime(original)) >= 0) return RELOCATED;
		Map<String, byte[]> entries = new java.util.LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(Files.newInputStream(original))) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) if (!entry.isDirectory()) entries.put(entry.getName(), in.readAllBytes());
		}
		Set<String> impl = new TreeSet<>();
		Set<String> privates = new HashSet<>();
		for (var entry : entries.entrySet()) {
			if (!entry.getKey().endsWith(".class")) continue;
			ClassNode node = new ClassNode();
			new ClassReader(entry.getValue()).accept(node, ClassReader.SKIP_CODE);
			if (!node.name.startsWith(IMPL)) continue;
			impl.add(node.name);
			for (MethodNode method : node.methods) {
				if ((method.access & Opcodes.ACC_PRIVATE) != 0 && !method.name.startsWith("<") && !method.name.startsWith("lambda$")) {
					privates.add(node.name + '.' + method.name + method.desc);
				}
			}
		}
		Map<String, String> names = new HashMap<>();
		int top = 0;
		for (String name : impl) if (name.indexOf('$') < 0) names.put(name, HIDDEN + "Q" + top++);
		Map<String, Integer> nested = new HashMap<>();
		for (String name : impl) {
			int dollar = name.indexOf('$');
			if (dollar < 0) continue;
			String outer = name.substring(0, dollar);
			int index = nested.merge(outer, 1, Integer::sum);
			names.put(name, names.getOrDefault(outer, HIDDEN + "Q" + top++) + "$N" + index);
		}
		Remapper remapper = new Remapper() {
			@Override public String map(String internalName) { return names.getOrDefault(internalName, internalName); }
			@Override public String mapMethodName(String owner, String name, String descriptor) {
				return privates.contains(owner + '.' + name + descriptor) ? "m" + Integer.toHexString((owner + name + descriptor).hashCode()) : name;
			}
		};
		Files.createDirectories(RELOCATED.getParent());
		Path partial = RELOCATED.resolveSibling(RELOCATED.getFileName() + ".part");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(partial))) {
			for (var entry : entries.entrySet()) {
				String name = entry.getKey();
				if (name.startsWith("META-INF/") && (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA"))) continue;
				byte[] bytes = entry.getValue();
				if (name.endsWith(".class")) {
					ClassReader reader = new ClassReader(bytes);
					ClassWriter writer = new ClassWriter(0);
					reader.accept(new ClassRemapper(writer, remapper), 0);
					bytes = writer.toByteArray();
					name = remapper.map(reader.getClassName()) + ".class";
				}
				out.putNextEntry(new ZipEntry(name));
				out.write(bytes);
				out.closeEntry();
			}
		}
		Files.move(partial, RELOCATED, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		return RELOCATED;
	}

	/** The class entries of a jar, internal names. */
	static List<String> classes(URL jar) throws IOException {
		List<String> out = new ArrayList<>();
		try (InputStream raw = jar.openStream(); ZipInputStream in = new ZipInputStream(raw)) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
				if (entry.getName().endsWith(".class")) out.add(entry.getName().substring(0, entry.getName().length() - 6));
			}
		}
		return out;
	}
}
