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

package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import net.forbric.kernel.classloading.LoaderProbePolicy;
import net.forbric.kernel.util.ByteScan;
import net.forbric.kernel.util.ForbricLog;

/**
 * Fabric Loader's environment stripping, for the classes Fabric Loader would have stripped: members annotated
 * {@code @Environment} for the other physical side are removed, and so are interfaces an
 * {@code @EnvironmentInterface} declares for the other side.
 *
 * <h2>Why it is needed</h2>
 *
 * <p>Knot runs {@code FabricTransformer} over every class it loads that is not Minecraft's own, before Mixin, and on a
 * dedicated server that deletes every {@code @Environment(CLIENT)} member. Mods rely on it without saying so: the
 * member is the one place a client-only type may be named, and its callers are guarded by an environment check.
 * Nothing did this here, so the member stayed, and the JVM verifies a class's methods whether or not they run.
 *
 * <p>CreativeCore paid for it on every Forbric dedicated server. {@code CreativeCore.<clinit>} constructs
 * {@code CreativeFabricLoader}, whose private {@code @Environment(CLIENT) registerHudElement} hands a
 * {@code CreativeHudElement} to {@code HudElementRegistry.addLast(Identifier, HudElement)}. Verifying that call has
 * to load {@code HudElement} to learn it is an interface; {@code HudElement} is in {@code fabric-rendering-v1}, which
 * is {@code environment: client} and never loads on a server. So linking {@code CreativeFabricLoader} threw
 * {@code NoClassDefFoundError}, CreativeCore's main entrypoint failed, its network channel, menu type, config sync
 * and commands never registered, and {@code CreativeCore.<clinit>} stayed poisoned for everything built on it
 * (AmbientSounds, LittleTiles, ItemPhysic). The same jar on native Fabric 0.19.5 reaches "Done" cleanly.
 *
 * <h2>The rules, taken from Fabric Loader 0.19.5 ({@code EnvironmentStrippingData}, {@code ClassStripper})</h2>
 *
 * <ul>
 *   <li>A field or method (constructors included) whose {@code @Environment} names another side is removed, matched
 *       by name plus descriptor.</li>
 *   <li>An {@code @EnvironmentInterface} for another side, alone or inside {@code @EnvironmentInterfaces}, removes
 *       its {@code itf} from the class's interfaces.</li>
 *   <li>When fields are removed, the class's own {@code PUTSTATIC} to one of them in {@code <clinit>} becomes a
 *       {@code POP}/{@code POP2}, and its own {@code PUTFIELD} in a constructor becomes {@code POP}/{@code POP2} plus
 *       {@code POP}. No other code is touched: lambdas and synthetics carry no annotation and stay, as on Fabric.</li>
 *   <li>Visible and invisible annotations count alike ({@code @Environment} is {@code CLASS}-retained, so it is
 *       always the invisible table in practice). The class is rewritten with {@code ClassWriter(reader, 0)}, so
 *       every untouched method is copied verbatim and no frame is recomputed.</li>
 * </ul>
 *
 * <h2>Which classes: only those from a jar arbitrated to Fabric</h2>
 *
 * <p>Fabric's own scope is "everything Knot loads except Minecraft". In one process holding three ecosystems that
 * translates to the jars {@code MultiLoaderArbiter} gave to Fabric. The merged base, the runtime carriers, the MC
 * libraries and the kernel's own jars are Minecraft's side of that line (Fabric strips Minecraft only in a
 * development environment). A jar arbitrated to MinecraftForge or NeoForge is left alone even when it also ships a
 * {@code fabric.mod.json} and carries the annotations -- tcdcommons and betterstats carry 85 such classes -- because
 * neither of those loaders strips anything, so that is what the mod was tested against.
 *
 * <p>The family is asked of the jar the class loader WILL read the class from, not of the class it has defined:
 * Mixin inspects a class through the same chain before it is defined, and a mixin class is never defined at all. The
 * answer has to be the same on both paths, or Mixin would merge a client-only handler that the defined class does not
 * have, and the other way round.
 *
 * <h2>A whole class for the other side</h2>
 *
 * <p>Fabric refuses to load it ("Cannot load class X in environment type SERVER"). Forbric has always loaded such
 * classes, so by default the class is passed on unchanged and reported once. That is a difference from Fabric, not a
 * guarantee the class works: CreativeCore's {@code CreativeHudElement} (whole-class CLIENT) implements
 * {@code HudElement}, which exists on no server. The report fires the first time the chain sees the class, which may
 * be Mixin inspecting a hierarchy rather than the game defining it. {@code -Dforbric.envStrip=strict} refuses it as
 * Fabric does.
 *
 * <p>{@code -Dforbric.envStrip=off}, or Fabric's own {@code -Dfabric.disableEnvironmentStrip}, turns all of it off.
 *
 * <p>Classes served from a superseded (rescue) jar are not stripped: that jar lost arbitration, so it has no family.
 *
 * <p>A jar a Fabric mod puts on the classpath at runtime through Fabric's own launcher
 * ({@code FabricLauncherBase.getLauncher().addToClassPath}) is Fabric's too: Knot strips what it loads from it, so
 * {@code KernelFabricLauncher} records it for this lookup ({@code -Dforbric.envStrip.runtimeJars=off} leaves it out).
 * CustomSkinLoader's Fabric bootstrap is the one caller in the sweep packs; the common jar it adds carries no
 * annotation today. What is NOT covered is a plain library -- a jar with no loader manifest, nested in a Fabric mod
 * or not. It has no family, so its classes load as the jar has them, where Knot would strip them. Across the sweep
 * pack, the popular pack and the merged pack no such jar carries the annotation.
 */
public final class EnvironmentStripTransformer implements ClassTransformer {

	/** What the switch selects. */
	public enum Mode {
		/** Nothing is stripped; every class loads as it is in its jar, as before this transformer existed. */
		OFF,
		/** Members and interfaces are stripped; a whole class for the other side is loaded unchanged and reported. */
		ON,
		/** As {@link #ON}, but a whole class for the other side is refused with Fabric's own message. */
		STRICT
	}

	/** {@code -Dforbric.envStrip=off|on|strict}; {@code on} by default. */
	public static final String SWITCH = "forbric.envStrip";

	/** Fabric Loader's own switch, honoured as an alias for {@code off} with Fabric's reading of it. */
	public static final String FABRIC_SWITCH = "fabric.disableEnvironmentStrip";

	static final String ENVIRONMENT = "Lnet/fabricmc/api/Environment;";
	static final String ENVIRONMENT_INTERFACE = "Lnet/fabricmc/api/EnvironmentInterface;";
	static final String ENVIRONMENT_INTERFACES = "Lnet/fabricmc/api/EnvironmentInterfaces;";

	/**
	 * All three descriptors contain this, so a class without it provably carries none of them. Across every jar of the
	 * 107-mod sweep pack, nested ones included, 314 classes carry one; the game loads tens of thousands.
	 */
	private static final byte[] NEEDLE = ByteScan.needle("net/fabricmc/api/Environment");

	/**
	 * The packages {@code MinecraftGameProvider.getBuiltinTransforms} treats as the game, which Fabric strips only in a
	 * development environment. A mod jar that ships a class under one of them gets the same treatment it gets there.
	 */
	private static final String[] GAME_PACKAGES = {
			"net.minecraft.", "com.mojang.minecraft.", "com.mojang.rubydung.", "com.mojang.blaze3d.",
			"com.mojang.renderpearl.", "com.mojang.math.", "com.mojang.realmsclient."
	};

	private final Function<String, LoaderProbePolicy.Family> familyOf;
	private final Mode mode;

	/** Classes already reported, each once: the chain runs twice for a class Mixin looked at before it was defined. */
	private final Set<String> reported = ConcurrentHashMap.newKeySet();
	private final Set<String> wholeClassReported = ConcurrentHashMap.newKeySet();
	private final AtomicBoolean announced = new AtomicBoolean();

	/**
	 * @param familyOf the loader family of the jar a binary class name will be read from, or {@code null} when that
	 *                 jar is unowned (merged base, carrier, library, a jar declaring no loader) or absent --
	 *                 {@code ForbricClassLoader::familyOfResource}
	 * @param mode     {@link Mode#OFF} makes this a no-op
	 */
	public EnvironmentStripTransformer(Function<String, LoaderProbePolicy.Family> familyOf, Mode mode) {
		if (familyOf == null) throw new NullPointerException("familyOf");
		if (mode == null) throw new NullPointerException("mode");
		this.familyOf = familyOf;
		this.mode = mode;
	}

	/**
	 * The transformer the system properties ask for, or {@code null} when they switch it off (said once, so a log
	 * shows why a client-only member survived on a server).
	 */
	public static EnvironmentStripTransformer configured(Function<String, LoaderProbePolicy.Family> familyOf) {
		Mode mode = configuredMode();
		if (mode == Mode.OFF) {
			ForbricLog.warn("[Forbric/EnvStrip] off (-D%s=off or -D%s) — Fabric mods keep the members they mark "
					+ "@Environment for the other side, which Fabric Loader would remove", SWITCH, FABRIC_SWITCH);
			return null;
		}
		return new EnvironmentStripTransformer(familyOf, mode);
	}

	/** The mode the system properties select. Fabric's alias wins over everything: it only ever means off. */
	public static Mode configuredMode() {
		// Fabric's SystemProperties.isSet: present and not "false". "-Dfabric.disableEnvironmentStrip" alone is set.
		String fabric = System.getProperty(FABRIC_SWITCH);
		if (fabric != null && !"false".equalsIgnoreCase(fabric)) return Mode.OFF;

		String value = System.getProperty(SWITCH, "on").trim();
		if ("off".equalsIgnoreCase(value)) return Mode.OFF;
		if ("strict".equalsIgnoreCase(value)) return Mode.STRICT;
		if (!"on".equalsIgnoreCase(value)) {
			ForbricLog.warn("[Forbric/EnvStrip] -D%s=%s is not one of off|on|strict; using on", SWITCH, value);
		}
		return Mode.ON;
	}

	public Mode mode() {
		return mode;
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (mode == Mode.OFF || classBytes == null) return classBytes;
		if (!context.isDevelopment() && fabricTreatsAsGame(className)) return classBytes;
		// Cheapest question first: it answers "no" for all but a few hundred classes without a lookup or a parse.
		if (!ByteScan.contains(classBytes, NEEDLE)) return classBytes;
		if (familyOf.apply(className) != LoaderProbePolicy.Family.FABRIC) return classBytes;

		String side = context.getEnvType().name();
		Plan plan = Plan.read(classBytes, side);

		if (plan.wholeClass != null) {
			String message = "Cannot load class " + className + " in environment type " + side;
			if (mode == Mode.STRICT) throw new IllegalStateException(message);
			if (wholeClassReported.add(className)) {
				// Not "it works": the types a client-only class names are usually just as absent here.
				ForbricLog.warn("[Forbric/EnvStrip] %s is marked @Environment(%s) but this is the %s; Forbric loads "
						+ "it unchanged instead of refusing it as Fabric Loader does (-D%s=strict refuses it)",
						className, plan.wholeClass, side, SWITCH);
			}
			return classBytes;
		}
		if (plan.isEmpty()) return classBytes;

		byte[] stripped = plan.applyTo(classBytes);
		report(className, side, plan);
		return stripped;
	}

	/** {@code MinecraftGameProvider.getBuiltinTransforms}' own test for "this is the game", default package included. */
	static boolean fabricTreatsAsGame(String className) {
		for (String prefix : GAME_PACKAGES) {
			if (className.startsWith(prefix)) return true;
		}
		return className.indexOf('.') < 0;
	}

	/** The classes a whole-class mismatch has been reported for (tests). */
	Set<String> wholeClassMismatches() {
		return Collections.unmodifiableSet(wholeClassReported);
	}

	/**
	 * One line per class, at INFO, naming what went: a client-only member Fabric removes and a same-side caller still
	 * reaches fails with {@code NoSuchMethodError} naming it, and this is where that name leads.
	 */
	private void report(String className, String side, Plan plan) {
		if (!reported.add(className)) return;
		if (announced.compareAndSet(false, true)) {
			ForbricLog.info("[Forbric/EnvStrip] on the %s, removing what Fabric mods mark @Environment for the other "
					+ "side, as Fabric Loader does (-D%s=off keeps it)", side, SWITCH);
		}
		ForbricLog.info("[Forbric/EnvStrip] %s: removed %s", className, String.join(", ", plan.described));
	}

	@Override
	public String name() {
		return "environment-strip";
	}

	@Override
	public AnchorSet anchors() {
		return AnchorSet.scanned("strips @Environment members from guest Fabric classes, which depend on which mods "
				+ "are installed");
	}

	/** What one class loses on this side: the reading pass of Fabric's {@code EnvironmentStrippingData}. */
	static final class Plan {
		/** The side the class as a whole is marked for, when that is not this one; {@code null} otherwise. */
		String wholeClass;
		final Set<String> interfaces = new HashSet<>();
		/** {@code name + descriptor}, Fabric's key. */
		final Set<String> fields = new HashSet<>();
		final Set<String> methods = new HashSet<>();
		/** The same, spelled for the log line. */
		final Set<String> described = new TreeSet<>();

		boolean isEmpty() {
			return interfaces.isEmpty() && fields.isEmpty() && methods.isEmpty();
		}

		static Plan read(byte[] classBytes, String side) {
			Plan plan = new Plan();
			new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
				/** What the class really implements; an interface it does not list is nothing to remove. */
				private Set<String> implemented = Set.of();

				@Override
				public void visit(int version, int access, String name, String signature, String superName,
						String[] itfs) {
					if (itfs != null) implemented = new HashSet<>(Arrays.asList(itfs));
				}

				@Override
				public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
					if (ENVIRONMENT.equals(descriptor)) return environment(side, value -> plan.wholeClass = value);
					if (ENVIRONMENT_INTERFACE.equals(descriptor)) return environmentInterface(side, plan, implemented);
					if (ENVIRONMENT_INTERFACES.equals(descriptor)) {
						return new AnnotationVisitor(Opcodes.ASM9) {
							@Override
							public AnnotationVisitor visitArray(String name) {
								if (!"value".equals(name)) return null;
								return new AnnotationVisitor(Opcodes.ASM9) {
									@Override
									public AnnotationVisitor visitAnnotation(String unnamed, String nested) {
										return environmentInterface(side, plan, implemented);
									}
								};
							}
						};
					}
					return null;
				}

				@Override
				public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
					return new FieldVisitor(Opcodes.ASM9) {
						@Override
						public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
							return ENVIRONMENT.equals(annotation) ? environment(side, value -> {
								plan.fields.add(name + descriptor);
								plan.described.add("field " + name + ":" + descriptor);
							}) : null;
						}
					};
				}

				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
						String[] exceptions) {
					return new MethodVisitor(Opcodes.ASM9) {
						@Override
						public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
							return ENVIRONMENT.equals(annotation) ? environment(side, value -> {
								plan.methods.add(name + descriptor);
								plan.described.add("method " + name + descriptor);
							}) : null;
						}
					};
				}
			}, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
			return plan;
		}

		/**
		 * {@code @Environment(value)}: any value that is not this side is a mismatch, exactly Fabric's test. The
		 * mismatching value is handed on so a report can name it.
		 */
		private static AnnotationVisitor environment(String side, Consumer<String> onMismatch) {
			return new AnnotationVisitor(Opcodes.ASM9) {
				@Override
				public void visitEnum(String name, String descriptor, String value) {
					if ("value".equals(name) && !side.equals(value)) onMismatch.accept(value);
				}
			};
		}

		/**
		 * {@code @EnvironmentInterface(value, itf)}: on a mismatch, {@code itf} leaves the interface list -- when it is
		 * in the list. Fabric rewrites the class either way and the result is the same list; counting an absent one
		 * would rewrite the class for nothing and report "removed interface X" for an X that was never there, the
		 * second time the chain sees its own output included.
		 */
		private static AnnotationVisitor environmentInterface(String side, Plan plan, Set<String> implemented) {
			return new AnnotationVisitor(Opcodes.ASM9) {
				private boolean mismatch;
				private Type itf;

				@Override
				public void visitEnum(String name, String descriptor, String value) {
					if ("value".equals(name) && !side.equals(value)) mismatch = true;
				}

				@Override
				public void visit(String name, Object value) {
					if ("itf".equals(name) && value instanceof Type type) itf = type;
				}

				@Override
				public void visitEnd() {
					if (!mismatch || itf == null || !implemented.contains(itf.getInternalName())) return;
					plan.interfaces.add(itf.getInternalName());
					plan.described.add("interface " + itf.getInternalName());
				}
			};
		}

		/** The writing pass: Fabric's {@code ClassStripper}, over a writer that copies everything it is not told to drop. */
		byte[] applyTo(byte[] classBytes) {
			ClassReader reader = new ClassReader(classBytes);
			ClassWriter writer = new ClassWriter(reader, 0);
			reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
				private String owner;

				@Override
				public void visit(int version, int access, String name, String signature, String superName,
						String[] itfs) {
					owner = name;
					if (itfs != null && !interfaces.isEmpty()) {
						List<String> kept = new ArrayList<>(itfs.length);
						for (String itf : itfs) if (!interfaces.contains(itf)) kept.add(itf);
						itfs = kept.toArray(new String[0]);
					}
					super.visit(version, access, name, signature, superName, itfs);
				}

				@Override
				public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
					return fields.contains(name + descriptor) ? null
							: super.visitField(access, name, descriptor, signature, value);
				}

				@Override
				public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
						String[] exceptions) {
					if (methods.contains(name + descriptor)) return null;
					MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions);
					if (fields.isEmpty()) return next;

					// The initializer of a removed field would otherwise store into a field that no longer exists.
					int store;
					if ("<clinit>".equals(name)) store = Opcodes.PUTSTATIC;
					else if ("<init>".equals(name)) store = Opcodes.PUTFIELD;
					else return next;

					return new MethodVisitor(Opcodes.ASM9, next) {
						@Override
						public void visitFieldInsn(int opcode, String fieldOwner, String fieldName, String fieldDesc) {
							if (opcode != store || !owner.equals(fieldOwner) || !fields.contains(fieldName + fieldDesc)) {
								super.visitFieldInsn(opcode, fieldOwner, fieldName, fieldDesc);
								return;
							}
							super.visitInsn(Type.getType(fieldDesc).getSize() == 2 ? Opcodes.POP2 : Opcodes.POP);
							if (opcode == Opcodes.PUTFIELD) super.visitInsn(Opcodes.POP); // the receiver
						}
					};
				}
			}, 0);
			return writer.toByteArray();
		}
	}
}
