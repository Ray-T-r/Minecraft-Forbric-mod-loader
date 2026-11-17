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

package net.forbric.kernel.linkcheck;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * The static link check: does every reference the scanned classes make link the way the JVM will link it, before a
 * profile names those classes or a launch defines them.
 *
 * <p><b>What is scanned.</b> The method bodies and class headers of every class of the scanned inputs ({@link #scan}).
 * When two inputs define the same class the first one added wins, as the game's loader serves the first jar that has it,
 * and only the winner is scanned (the loser is never defined, so it never links).
 *
 * <p><b>What is judged.</b> A reference is judged when its owner is a class of a <i>judged</i> input ({@link #judge}, by
 * default every scanned input), or, when the owner is defined nowhere, when its package is a package a judged input
 * defines (a class missing from a package the game owns is a {@code NoClassDefFoundError}; a class from a package
 * nobody here defines belongs to a library this check was not given). Owners in the JDK and in library inputs
 * ({@link #library}) are read only to resolve what judged classes inherit, never judged themselves, unless
 * {@link Options#judgeRuntime} asks for the JDK. A resolution that reaches a supertype the check cannot read is not
 * judged either: it is counted, never guessed.
 *
 * <p><b>The checks</b> ({@link Check}), each with JVM semantics (JVMS 5.4.3, 5.4.4) rather than "a member of that name
 * exists somewhere":
 * <ul>
 *   <li>{@link Check#EXISTENCE} — the owner class, the field (declared, or inherited through superinterfaces then
 *       superclasses) and the method (class methods: the class and its superclasses, then the maximally specific
 *       superinterface methods; interface methods: the interface, then {@code Object}'s public methods, then its
 *       superinterfaces; static and private interface methods are not inherited; constructors only on the class
 *       named; {@code MethodHandle}/{@code VarHandle} signature-polymorphic methods match any descriptor; array
 *       {@code clone()} is always there). Catch types, {@code new}/casts/{@code instanceof}/array creation, {@code ldc}
 *       of a class, superclasses and interfaces are class references too;</li>
 *   <li>{@link Check#ACCESS} — the class referenced (public, or the same runtime package) and the member resolved,
 *       judged from the calling class: private (the same class or a nestmate, the Java 11 rule javac relies on),
 *       package-private (the same package), protected (the same package, or a subclass of the declaring class whose
 *       instance access names a class related to the caller), public. This is what turns a member that is wider on
 *       one base and narrower on another into an {@code IllegalAccessError} before the game meets it. The scanned
 *       inputs are taken to share one defining loader, as the game, its carrier and the kernel's game side do;</li>
 *   <li>{@link Check#FINAL} — a write to a final field from another class (or, from class file version 53, from
 *       another method than the initializer), a class extending a final class, a method overriding an accessible
 *       final method;</li>
 *   <li>{@link Check#INDY} — every {@code invokedynamic} and every loaded constant that the JVM resolves as a method
 *       handle, a method type or a dynamic constant: its bootstrap method, each handle argument (field or method, by
 *       its kind, with the same existence, access and final rules as the instruction it stands for), each class and
 *       method type named. With this off, a handle is not judged at all.</li>
 * </ul>
 * Every violation of every check counts in the one number the verdict line reports, {@code dangling references: K}:
 * the installer and the gates read that line, so an inaccessible member is as fatal there as a missing one.
 *
 * <p>Depends on ASM's core and the JDK only, so the installer can carry it as a small standalone jar
 * ({@code forbric-link-check.jar}, built from these same classes) and run it before it has any of Forbric's jars.
 *
 * <p>Usage: {@code LinkCheck [--judge <input>]... [--lib <jar|dir>]... [--judge-runtime] <input>...} where an input is
 * a jar, or a directory of {@code .class} files and jars. Exit 0: nothing dangles; 1: something does; 2: usage, or
 * nothing was judged.
 */
public final class LinkCheck {

	/** The checks, each of which can be switched off on its own (the red controls of its tests do exactly that). */
	public enum Check { EXISTENCE, ACCESS, FINAL, INDY }

	/** What a violation is, for its report line. */
	public enum Kind {
		MISSING_CLASS("missing class"), MISSING_FIELD("missing field"), MISSING_METHOD("missing method"),
		CLASS_ACCESS("inaccessible class"), MEMBER_ACCESS("inaccessible member"), FINAL("final"),
		INDY_MISSING("missing in a method handle"), INDY_ACCESS("inaccessible in a method handle"),
		INDY_FINAL("final in a method handle");

		final String label;

		Kind(String label) {
			this.label = label;
		}
	}

	/**
	 * One reference that will not link.
	 *
	 * @param kind   what is wrong
	 * @param from   the referencing class and method, {@code owner#name(desc)}, or the class alone for a header reference
	 * @param target what it names
	 * @param detail why it does not link
	 */
	public record Violation(Kind kind, String from, String target, String detail) {
		/** The one-line form the report prints and a baseline would hold. */
		public String line() {
			return "[" + kind.name() + "] " + from + "  ->  " + target + (detail.isEmpty() ? "" : "  (" + detail + ")");
		}
	}

	/** What a run found. */
	public record Result(int classesLoaded, int classesScanned, int inputs, long judged, long unresolved, long outOfScope,
			int shadowed, List<Violation> violations) {
		/** Violations of one kind. */
		public List<Violation> of(Kind kind) {
			return violations.stream().filter(v -> v.kind == kind).toList();
		}

		/** The verdict line: the one line the installer and the gates read. Every violation counts in its last number. */
		public String summary() {
			return "[link-check] loaded " + classesLoaded + " classes (" + classesScanned + " scanned; " + judged
					+ " references judged, " + unresolved
					+ " unresolvable through an unread supertype, " + outOfScope + " out of scope); dangling references: "
					+ violations.size();
		}

		/** Counts by check, for the line printed above the verdict. */
		public String breakdown() {
			int missing = of(Kind.MISSING_CLASS).size() + of(Kind.MISSING_FIELD).size() + of(Kind.MISSING_METHOD).size();
			int access = of(Kind.CLASS_ACCESS).size() + of(Kind.MEMBER_ACCESS).size();
			int handles = of(Kind.INDY_MISSING).size() + of(Kind.INDY_ACCESS).size() + of(Kind.INDY_FINAL).size();
			return "[link-check] missing " + missing + ", inaccessible " + access + ", final " + of(Kind.FINAL).size()
					+ ", in invokedynamic and method handles " + handles
					+ (shadowed == 0 ? "" : "; " + shadowed + " class(es) shadowed by an earlier input, not scanned");
		}
	}

	/**
	 * Options of one run.
	 *
	 * @param checks       the checks that judge (all by default)
	 * @param judgeRuntime also judge references whose owner is a JDK class
	 */
	public record Options(Set<Check> checks, boolean judgeRuntime) {
		public static Options all() {
			return new Options(EnumSet.allOf(Check.class), false);
		}

		public Options without(Check check) {
			EnumSet<Check> fewer = EnumSet.noneOf(Check.class);
			fewer.addAll(checks);
			fewer.remove(check);
			return new Options(fewer, judgeRuntime);
		}

		public Options withJudgeRuntime() {
			return new Options(checks, true);
		}

		boolean on(Check check) {
			return checks.contains(check);
		}
	}

	private enum Source { SCANNED, LIBRARY, RUNTIME }

	/** The header of one class: everything resolution and access need, no code. */
	private static final class Info {
		final String name;
		final int access;
		final int version;
		final String superName;
		final String[] interfaces;
		String nestHost;
		final Map<String, Integer> fields = new HashMap<>();
		final Map<String, Integer> methods = new LinkedHashMap<>();
		final Source source;
		final boolean judged;

		Info(String name, int access, int version, String superName, String[] interfaces, Source source, boolean judged) {
			this.name = name;
			this.access = access;
			this.version = version;
			this.superName = superName;
			this.interfaces = interfaces == null ? new String[0] : interfaces;
			this.source = source;
			this.judged = judged;
		}

		boolean isInterface() {
			return (access & Opcodes.ACC_INTERFACE) != 0;
		}

		String host() {
			return nestHost != null ? nestHost : name;
		}
	}

	private final Options options;
	private final Map<String, Info> classes = new HashMap<>();
	/** Scanned classes' bytes, in the order added. */
	private final Map<String, byte[]> scanned = new LinkedHashMap<>();
	/** Packages a judged input defines. */
	private final Set<String> judgedPackages = new HashSet<>();
	private final Map<String, Info> runtime = new HashMap<>();
	private final Set<String> runtimeMissing = new HashSet<>();
	private final List<Violation> violations = new ArrayList<>();
	private final Set<String> reported = new HashSet<>();
	private int inputs;
	private int shadowed;
	private long judged;
	/** References whose owner no judged input defines (the JDK, a library, an unjudged input). */
	private long outOfScope;
	/** Judged-owner references whose resolution or access needed a supertype the check could not read. */
	private long unresolved;

	public LinkCheck(Options options) {
		this.options = options;
	}

	public LinkCheck() {
		this(Options.all());
	}

	// --- inputs ----------------------------------------------------------------------------------------------------

	/** Adds a jar or a directory whose classes are scanned and judged. */
	public LinkCheck scan(Path input) throws IOException {
		return add(input, Source.SCANNED, true);
	}

	/** Adds a jar or directory whose classes are scanned, but references into it are not judged. */
	public LinkCheck scanUnjudged(Path input) throws IOException {
		return add(input, Source.SCANNED, false);
	}

	/** Adds a jar or directory read only to resolve what scanned classes inherit; neither scanned nor judged. */
	public LinkCheck library(Path input) throws IOException {
		return add(input, Source.LIBRARY, false);
	}

	/** Adds one class's bytes, scanned and (when {@code judgedClass}) judged; an earlier definition of the name wins. */
	public LinkCheck scanClass(byte[] bytes, boolean judgedClass) {
		define(bytes, Source.SCANNED, judgedClass);
		return this;
	}

	/** Adds one class's bytes for resolution only. */
	public LinkCheck libraryClass(byte[] bytes) {
		define(bytes, Source.LIBRARY, false);
		return this;
	}

	private LinkCheck add(Path input, Source source, boolean judgedInput) throws IOException {
		inputs++;
		if (Files.isDirectory(input)) {
			List<Path> files;
			try (Stream<Path> walk = Files.walk(input)) {
				files = walk.filter(Files::isRegularFile).sorted().toList();
			}
			for (Path file : files) {
				String name = file.getFileName().toString();
				if (name.endsWith(".class")) define(Files.readAllBytes(file), source, judgedInput);
				else if (name.endsWith(".jar")) readJar(file, source, judgedInput);
			}
		} else {
			readJar(input, source, judgedInput);
		}
		return this;
	}

	private void readJar(Path jar, Source source, boolean judgedInput) throws IOException {
		try (ZipFile zip = new ZipFile(jar.toFile())) {
			for (ZipEntry entry : Collections.list(zip.entries())) {
				String name = entry.getName();
				// Multi-release overlays and module descriptors are not classes this loader defines by name.
				if (!name.endsWith(".class") || name.startsWith("META-INF/") || name.endsWith("module-info.class")) continue;
				try (InputStream in = zip.getInputStream(entry)) {
					define(in.readAllBytes(), source, judgedInput);
				}
			}
		}
	}

	private void define(byte[] bytes, Source source, boolean judgedClass) {
		Info info = header(bytes, source, judgedClass);
		if (classes.containsKey(info.name)) {
			if (source == Source.SCANNED) shadowed++;
			return;
		}
		classes.put(info.name, info);
		if (source == Source.SCANNED) scanned.put(info.name, bytes);
		if (judgedClass) judgedPackages.add(packageOf(info.name));
	}

	private static Info header(byte[] bytes, Source source, boolean judgedClass) {
		Info[] out = new Info[1];
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				out[0] = new Info(name, access, version & 0xFFFF, superName, interfaces, source, judgedClass);
			}

			@Override
			public void visitNestHost(String nestHost) {
				out[0].nestHost = nestHost;
			}

			@Override
			public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
				out[0].fields.put(name + " " + descriptor, access);
				return null;
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				out[0].methods.put(name + descriptor, access);
				return null;
			}
		}, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
		return out[0];
	}

	// --- lookup ----------------------------------------------------------------------------------------------------

	/** A class by internal name: an input's, else the running JDK's, else null. */
	private Info lookup(String name) {
		Info known = classes.get(name);
		if (known != null) return known;
		Info jdk = runtime.get(name);
		if (jdk != null || runtimeMissing.contains(name)) return jdk;
		// The JDK's own modules only, never this JVM's class path: in a test or a build that class path can hold a game
		// jar, and a class the inputs lack must not be found there.
		Module module = RUNTIME_PACKAGES.get(packageOf(name).replace('/', '.'));
		if (module != null) {
			try (InputStream in = module.getResourceAsStream(name + ".class")) {
				if (in != null) jdk = header(in.readAllBytes(), Source.RUNTIME, options.judgeRuntime);
			} catch (IOException unreadable) {
				// The running JDK's own class file: unreadable means the JVM is broken, not that the class is absent.
				throw new java.io.UncheckedIOException("could not read the JDK's " + name, unreadable);
			}
		}
		if (jdk == null) runtimeMissing.add(name);
		else runtime.put(name, jdk);
		return jdk;
	}

	/** Package (dotted) to the boot-layer module that holds it: the running JDK, nothing else. */
	private static final Map<String, Module> RUNTIME_PACKAGES = new HashMap<>();

	static {
		for (Module module : ModuleLayer.boot().modules()) {
			for (String pkg : module.getPackages()) RUNTIME_PACKAGES.put(pkg, module);
		}
	}

	static String packageOf(String internalName) {
		int slash = internalName.lastIndexOf('/');
		return slash < 0 ? "" : internalName.substring(0, slash);
	}

	/** The verdict on a class name: judged and present, judged and missing, or out of scope. */
	private enum Scope { JUDGED, MISSING, OUT }

	private Scope scopeOf(String owner) {
		Info info = lookup(owner);
		if (info != null) return info.judged ? Scope.JUDGED : Scope.OUT;
		return judgedPackages.contains(packageOf(owner)) ? Scope.MISSING : Scope.OUT;
	}

	/** The class name a type descriptor or internal name refers to, arrays unwrapped; null for a primitive. */
	private static String classOf(String internalOrDescriptor) {
		if (internalOrDescriptor.startsWith("[")) {
			Type element = Type.getType(internalOrDescriptor).getElementType();
			return element.getSort() == Type.OBJECT ? element.getInternalName() : null;
		}
		return internalOrDescriptor;
	}

	// --- resolution ------------------------------------------------------------------------------------------------

	/** A resolved member: where it is declared and its flags; or unresolvable (a supertype could not be read). */
	private record Resolved(Info declarer, int access) {
	}

	private static final Resolved UNKNOWN = new Resolved(null, -1);

	/** JVMS 5.4.3.2: the class, then its superinterfaces, then its superclass. Null when absent. */
	private Resolved resolveField(String owner, String name, String desc, Set<String> seen) {
		if (owner == null || !seen.add(owner)) return null;
		Info info = lookup(owner);
		if (info == null) return UNKNOWN;
		Integer access = info.fields.get(name + " " + desc);
		if (access != null) return new Resolved(info, access);
		boolean unknown = false;
		for (String itf : info.interfaces) {
			Resolved found = resolveField(itf, name, desc, seen);
			if (found == UNKNOWN) unknown = true;
			else if (found != null) return found;
		}
		Resolved up = resolveField(info.superName, name, desc, seen);
		if (up != null) return up;
		return unknown ? UNKNOWN : null;
	}

	/** JVMS 5.4.3.3 (class methods) and 5.4.3.4 (interface methods). Null when absent. */
	private Resolved resolveMethod(String owner, String name, String desc, boolean itf) {
		Info info = lookup(owner);
		if (info == null) return UNKNOWN;
		String key = name + desc;
		if (name.equals("<init>") || name.equals("<clinit>")) {
			Integer access = info.methods.get(key);
			return access == null ? null : new Resolved(info, access);
		}
		if (itf) {
			Integer access = info.methods.get(key);
			if (access != null) return new Resolved(info, access);
			Info object = lookup("java/lang/Object");
			if (object != null) {
				Integer fromObject = object.methods.get(key);
				if (fromObject != null && (fromObject & Opcodes.ACC_PUBLIC) != 0 && (fromObject & Opcodes.ACC_STATIC) == 0) {
					return new Resolved(object, fromObject);
				}
			}
			return superinterfaceMethod(info, key, new HashSet<>());
		}
		boolean unknown = false;
		for (String cursor = owner; cursor != null; ) {
			Info c = lookup(cursor);
			if (c == null) {
				unknown = true;
				break;
			}
			Resolved polymorphic = signaturePolymorphic(c, name);
			if (polymorphic != null) return polymorphic;
			Integer access = c.methods.get(key);
			if (access != null) return new Resolved(c, access);
			cursor = c.superName;
		}
		// Maximally specific superinterface methods of the class and its superclasses: any non-private, non-static one.
		Set<String> seen = new HashSet<>();
		for (String cursor = owner; cursor != null; ) {
			Info c = lookup(cursor);
			if (c == null) break;
			Resolved found = superinterfaceMethod(c, key, seen);
			if (found == UNKNOWN) unknown = true;
			else if (found != null) return found;
			cursor = c.superName;
		}
		return unknown ? UNKNOWN : null;
	}

	private Resolved superinterfaceMethod(Info info, String key, Set<String> seen) {
		boolean unknown = false;
		for (String itf : info.interfaces) {
			if (!seen.add(itf)) continue;
			Info i = lookup(itf);
			if (i == null) {
				unknown = true;
				continue;
			}
			Integer access = i.methods.get(key);
			if (access != null && (access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) return new Resolved(i, access);
			Resolved deeper = superinterfaceMethod(i, key, seen);
			if (deeper == UNKNOWN) unknown = true;
			else if (deeper != null) return deeper;
		}
		return unknown ? UNKNOWN : null;
	}

	/** JVMS 2.9.3: a native varargs {@code (Object[])} method of MethodHandle or VarHandle matches any descriptor. */
	private static Resolved signaturePolymorphic(Info c, String name) {
		if (!c.name.equals("java/lang/invoke/MethodHandle") && !c.name.equals("java/lang/invoke/VarHandle")) return null;
		Resolved only = null;
		for (Map.Entry<String, Integer> m : c.methods.entrySet()) {
			String key = m.getKey();
			int paren = key.indexOf('(');
			if (!key.substring(0, paren).equals(name)) continue;
			if (only != null) return null; // more than one method of that name: not signature polymorphic
			int access = m.getValue();
			boolean polymorphic = (access & Opcodes.ACC_VARARGS) != 0 && (access & Opcodes.ACC_NATIVE) != 0
					&& key.substring(paren).startsWith("([Ljava/lang/Object;)");
			if (!polymorphic) return null;
			only = new Resolved(c, access);
		}
		return only;
	}

	/** True, false, or null when the hierarchy cannot be read: is {@code sub} {@code sup} or a subclass of it. */
	private Boolean isSubclass(String sub, String sup) {
		for (String cursor = sub; cursor != null; ) {
			if (cursor.equals(sup)) return true;
			Info c = lookup(cursor);
			if (c == null) return null;
			cursor = c.superName;
		}
		return false;
	}

	// --- access ----------------------------------------------------------------------------------------------------

	private static boolean samePackage(String a, String b) {
		return packageOf(a).equals(packageOf(b));
	}

	/** JVMS 5.4.4 for a class: public, or the same runtime package. Null when the class is unreadable. */
	private Boolean classAccessible(Info caller, String target) {
		Info t = lookup(target);
		if (t == null) return null;
		return (t.access & Opcodes.ACC_PUBLIC) != 0 || samePackage(caller.name, target);
	}

	/**
	 * JVMS 5.4.4 for a member declared in {@code declarer} with {@code access}, named through {@code symbolic}, from
	 * {@code caller}. Null when the answer needs a hierarchy the check cannot read.
	 */
	private Boolean memberAccessible(Info caller, Info declarer, int access, String symbolic) {
		if ((access & Opcodes.ACC_PUBLIC) != 0) return true;
		if ((access & Opcodes.ACC_PRIVATE) != 0) {
			if (caller.name.equals(declarer.name)) return true;
			// Nestmates (Java 11): one nest host. A class without NestHost is its own host.
			return caller.host().equals(declarer.host());
		}
		if (samePackage(caller.name, declarer.name)) return true;
		if ((access & Opcodes.ACC_PROTECTED) == 0) return false;
		Boolean sub = isSubclass(caller.name, declarer.name);
		if (sub == null || !sub) return sub;
		if ((access & Opcodes.ACC_STATIC) != 0 || symbolic == null || symbolic.startsWith("[")) return true;
		Boolean down = isSubclass(symbolic, caller.name);
		if (down == null) return null;
		if (down) return true;
		return isSubclass(caller.name, symbolic);
	}

	// --- the scan --------------------------------------------------------------------------------------------------

	/** Runs every enabled check over every scanned class. */
	public Result run() {
		for (Map.Entry<String, byte[]> entry : scanned.entrySet()) {
			Info caller = classes.get(entry.getKey());
			checkHeader(caller);
			new ClassReader(entry.getValue()).accept(new Scanner(caller), ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		}
		List<Violation> sorted = new ArrayList<>(violations);
		sorted.sort((a, b) -> a.line().compareTo(b.line()));
		return new Result(classes.size(), scanned.size(), inputs, judged, unresolved, outOfScope, shadowed, List.copyOf(sorted));
	}

	private void report(Kind kind, String from, String target, String detail) {
		Violation v = new Violation(kind, from, target, detail);
		if (reported.add(v.line())) violations.add(v);
	}

	/** Superclass and interfaces: existence, access, a final superclass, and final methods this class overrides. */
	private void checkHeader(Info c) {
		if (c.superName != null) classReference(c, c.name, c.superName, false);
		for (String itf : c.interfaces) classReference(c, c.name, itf, false);
		if (!options.on(Check.FINAL) || c.superName == null) return;
		Info sup = lookup(c.superName);
		if (sup != null && sup.judged && (sup.access & Opcodes.ACC_FINAL) != 0) {
			judged++;
			report(Kind.FINAL, c.name, "CLASS " + sup.name, "extends a final class");
		}
		for (Map.Entry<String, Integer> m : c.methods.entrySet()) {
			int access = m.getValue();
			String key = m.getKey();
			if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0 || key.startsWith("<")) continue;
			for (String cursor = c.superName; cursor != null; ) {
				Info s = lookup(cursor);
				if (s == null) break;
				Integer overridden = s.methods.get(key);
				if (overridden != null && (overridden & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
					boolean visible = (overridden & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0
							|| samePackage(c.name, s.name);
					if (visible) {
						if (s.judged && (overridden & Opcodes.ACC_FINAL) != 0) {
							judged++;
							report(Kind.FINAL, c.name + "#" + key, "METHOD " + s.name + "." + key, "overrides a final method");
						}
						break;
					}
				}
				cursor = s.superName;
			}
		}
	}

	/**
	 * A reference to a class: in scope, present, accessible. {@code handle} reports it as part of a method handle or
	 * dynamic constant. Returns false when it is judged and does not link (so the member behind it is not judged again).
	 */
	private boolean classReference(Info caller, String from, String name, boolean handle) {
		String target = classOf(name);
		if (target == null) return true;
		if (handle && !options.on(Check.INDY)) return true;
		Scope scope = scopeOf(target);
		if (scope == Scope.OUT) {
			outOfScope++;
			return true;
		}
		judged++;
		if (scope == Scope.MISSING) {
			if (options.on(Check.EXISTENCE)) {
				report(handle ? Kind.INDY_MISSING : Kind.MISSING_CLASS, from, "CLASS " + target, "no input defines it");
				return false;
			}
			return true;
		}
		if (options.on(Check.ACCESS) && Boolean.FALSE.equals(classAccessible(caller, target))) {
			report(handle ? Kind.INDY_ACCESS : Kind.CLASS_ACCESS, from, "CLASS " + target,
					"not public, and " + caller.name + " is in another package");
			return false;
		}
		return true;
	}

	/** Every class a descriptor names (a method type the JVM resolves). */
	private void descriptorClasses(Info caller, String from, String descriptor, boolean handle) {
		Type type = Type.getType(descriptor);
		if (type.getSort() == Type.METHOD) {
			for (Type arg : type.getArgumentTypes()) descriptorType(caller, from, arg, handle);
			descriptorType(caller, from, type.getReturnType(), handle);
		} else {
			descriptorType(caller, from, type, handle);
		}
	}

	private void descriptorType(Info caller, String from, Type type, boolean handle) {
		if (type.getSort() == Type.ARRAY) type = type.getElementType();
		if (type.getSort() == Type.OBJECT) classReference(caller, from, type.getInternalName(), handle);
	}

	/**
	 * A field reference: {@code put} for a write, {@code isStatic} for the static form, {@code method} the referencing
	 * method's name (the final-field rule depends on it).
	 */
	private void fieldReference(Info caller, String from, String method, String owner, String name, String desc,
			boolean put, boolean handle) {
		if (owner.startsWith("[")) return;
		if (!classReference(caller, from, owner, handle)) return;
		if (handle && !options.on(Check.INDY)) return;
		if (scopeOf(owner) != Scope.JUDGED) return;
		String target = "FIELD " + owner + "." + name + " " + desc;
		Resolved r = resolveField(owner, name, desc, new HashSet<>());
		if (r == UNKNOWN) {
			unresolved++;
			return;
		}
		judged++;
		if (r == null) {
			if (options.on(Check.EXISTENCE)) report(handle ? Kind.INDY_MISSING : Kind.MISSING_FIELD, from, target, "not declared or inherited");
			return;
		}
		if (options.on(Check.ACCESS)) {
			Boolean ok = memberAccessible(caller, r.declarer, r.access, owner);
			if (ok == null) unresolved++;
			else if (!ok) {
				report(handle ? Kind.INDY_ACCESS : Kind.MEMBER_ACCESS, from, target, describe(r));
				return;
			}
		}
		if (put && options.on(Check.FINAL) && (r.access & Opcodes.ACC_FINAL) != 0) {
			boolean isStatic = (r.access & Opcodes.ACC_STATIC) != 0;
			String initializer = isStatic ? "<clinit>" : "<init>";
			String why = null;
			if (!r.declarer.name.equals(caller.name)) why = "written from another class than " + r.declarer.name;
			else if (handle) why = "written through a method handle";
			else if (caller.version >= Opcodes.V9 && !initializer.equals(method)) why = "written outside " + initializer;
			if (why != null) report(handle ? Kind.INDY_FINAL : Kind.FINAL, from, target, "final; " + why);
		}
	}

	/** A method reference, {@code itf} as the instruction (or handle) says. */
	private void methodReference(Info caller, String from, String owner, String name, String desc, boolean itf, boolean handle) {
		if (owner.startsWith("[")) return; // clone() and Object's methods on an array: always there, always public
		if (!classReference(caller, from, owner, handle)) return;
		if (handle && !options.on(Check.INDY)) return;
		if (scopeOf(owner) != Scope.JUDGED) return;
		String target = "METHOD " + owner + "." + name + desc;
		Resolved r = resolveMethod(owner, name, desc, itf);
		if (r == UNKNOWN) {
			unresolved++;
			return;
		}
		judged++;
		if (r == null) {
			if (options.on(Check.EXISTENCE)) {
				report(handle ? Kind.INDY_MISSING : Kind.MISSING_METHOD, from, target,
						(itf ? "interface method " : "") + "not declared or inherited");
			}
			return;
		}
		if (options.on(Check.ACCESS)) {
			Boolean ok = memberAccessible(caller, r.declarer, r.access, owner);
			if (ok == null) unresolved++;
			else if (!ok) report(handle ? Kind.INDY_ACCESS : Kind.MEMBER_ACCESS, from, target, describe(r));
		}
	}

	private static String describe(Resolved r) {
		int a = r.access;
		String level = (a & Opcodes.ACC_PRIVATE) != 0 ? "private" : (a & Opcodes.ACC_PROTECTED) != 0 ? "protected"
				: (a & Opcodes.ACC_PUBLIC) != 0 ? "public" : "package-private";
		return level + " in " + r.declarer.name;
	}

	/** A method handle constant, judged as the instruction its kind stands for. */
	private void handle(Info caller, String from, Handle h) {
		if (!options.on(Check.INDY)) return;
		switch (h.getTag()) {
			case Opcodes.H_GETFIELD, Opcodes.H_GETSTATIC ->
					fieldReference(caller, from, null, h.getOwner(), h.getName(), h.getDesc(), false, true);
			case Opcodes.H_PUTFIELD, Opcodes.H_PUTSTATIC ->
					fieldReference(caller, from, null, h.getOwner(), h.getName(), h.getDesc(), true, true);
			default -> methodReference(caller, from, h.getOwner(), h.getName(), h.getDesc(), h.isInterface(), true);
		}
	}

	/** A loaded constant: a class, a method type, a handle, a dynamic constant; strings and numbers name nothing. */
	private void constant(Info caller, String from, Object value) {
		if (value instanceof Type type) {
			if (type.getSort() == Type.METHOD) {
				if (options.on(Check.INDY)) descriptorClasses(caller, from, type.getDescriptor(), true);
			} else {
				classReference(caller, from, type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName(), false);
			}
		} else if (value instanceof Handle h) {
			handle(caller, from, h);
		} else if (value instanceof ConstantDynamic condy) {
			if (!options.on(Check.INDY)) return;
			handle(caller, from, condy.getBootstrapMethod());
			descriptorClasses(caller, from, condy.getDescriptor(), true);
			for (int i = 0; i < condy.getBootstrapMethodArgumentCount(); i++) {
				constant(caller, from, condy.getBootstrapMethodArgument(i));
			}
		}
	}

	private final class Scanner extends ClassVisitor {
		private final Info caller;

		Scanner(Info caller) {
			super(Opcodes.ASM9);
			this.caller = caller;
		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
			String from = caller.name + "#" + name + descriptor;
			return new MethodVisitor(Opcodes.ASM9) {
				@Override
				public void visitFieldInsn(int opcode, String owner, String fieldName, String desc) {
					fieldReference(caller, from, name, owner, fieldName, desc,
							opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC, false);
				}

				@Override
				public void visitMethodInsn(int opcode, String owner, String methodName, String desc, boolean itf) {
					methodReference(caller, from, owner, methodName, desc, itf, false);
				}

				@Override
				public void visitTypeInsn(int opcode, String type) {
					classReference(caller, from, type, false);
				}

				@Override
				public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
					classReference(caller, from, descriptor, false);
				}

				@Override
				public void visitLdcInsn(Object value) {
					constant(caller, from, value);
				}

				@Override
				public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
					if (type != null) classReference(caller, from, type, false);
				}

				@Override
				public void visitInvokeDynamicInsn(String indyName, String desc, Handle bootstrap, Object... args) {
					if (!options.on(Check.INDY)) return;
					handle(caller, from, bootstrap);
					descriptorClasses(caller, from, desc, true);
					for (Object arg : args) constant(caller, from, arg);
				}
			};
		}
	}

	// --- command line ----------------------------------------------------------------------------------------------

	/**
	 * Exit 0 links, 1 does not, 2 usage or nothing judged. An input that cannot be read (a damaged jar or class file)
	 * ends it with its exception and no verdict line, which a caller reading the verdict takes as a failure.
	 */
	public static void main(String[] args) throws IOException {
		System.exit(run(args, System.out, System.err));
	}

	/** The command line, returning the exit code: 0 links, 1 does not, 2 usage or nothing judged. */
	public static int run(String[] args, java.io.PrintStream out, java.io.PrintStream err) throws IOException {
		List<Path> inputs = new ArrayList<>();
		Set<Path> judgedInputs = new LinkedHashSet<>();
		List<Path> libraries = new ArrayList<>();
		Options options = Options.all();
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--judge" -> {
					if (++i >= args.length) return usage(err, "--judge needs an input");
					judgedInputs.add(Path.of(args[i]).toAbsolutePath().normalize());
				}
				case "--lib" -> {
					if (++i >= args.length) return usage(err, "--lib needs a jar or directory");
					libraries.add(Path.of(args[i]));
				}
				case "--judge-runtime" -> options = options.withJudgeRuntime();
				default -> {
					if (args[i].startsWith("--")) return usage(err, "unknown option " + args[i]);
					inputs.add(Path.of(args[i]));
				}
			}
		}
		if (inputs.isEmpty()) return usage(err, "no input given");
		for (Path judgedInput : judgedInputs) {
			if (inputs.stream().noneMatch(p -> p.toAbsolutePath().normalize().equals(judgedInput))) {
				return usage(err, "--judge " + judgedInput + " is not one of the inputs");
			}
		}
		LinkCheck check = new LinkCheck(options);
		for (Path input : inputs) {
			boolean judgedInput = judgedInputs.isEmpty() || judgedInputs.contains(input.toAbsolutePath().normalize());
			if (!Files.exists(input)) return usage(err, "no such input: " + input);
			if (judgedInput) check.scan(input);
			else check.scanUnjudged(input);
			out.println("[link-check] " + (judgedInput ? "judged " : "scanned") + " " + input);
		}
		for (Path library : libraries) {
			if (!Files.exists(library)) return usage(err, "no such library: " + library);
			check.library(library);
		}
		if (check.judgedPackages.isEmpty()) return usage(err, "the judged inputs contain no classes; no link check was performed");
		Result result = check.run();
		for (Violation v : result.violations()) out.println(v.line());
		out.println(result.breakdown());
		out.println(result.summary());
		return result.violations().isEmpty() ? 0 : 1;
	}

	private static int usage(java.io.PrintStream err, String why) {
		err.println("usage: LinkCheck [--judge <input>]... [--lib <jar|dir>]... [--judge-runtime] <input>...");
		err.println("       (" + why + ")");
		return 2;
	}

	/** The packages the judged inputs define, for a caller that reports scope. */
	public Set<String> judgedPackages() {
		return Collections.unmodifiableSet(new TreeSet<>(judgedPackages));
	}
}
