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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.objectweb.asm.Opcodes.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

import net.forbric.kernel.linkcheck.LinkCheck.Check;
import net.forbric.kernel.linkcheck.LinkCheck.Kind;

/**
 * The link check on synthetic classes, each case also loaded and run in a real JVM: what the check flags must be what
 * the JVM refuses, with the error the JVM throws for it, and what it passes must run.
 *
 * <p>Every flagged case needs a set of checks; switching any one of them off must lose exactly the cases that need it and
 * no other ({@link #eachCheckIsTheOnlyOneThatSeesItsCases}), which is the red control of each check.
 */
class LinkCheckTest {
	private static final String LOOKUP = "Ljava/lang/invoke/MethodHandles$Lookup;";
	private static final Handle METAFACTORY = new Handle(H_INVOKESTATIC, Type.getInternalName(LambdaMetafactory.class),
			"metafactory", MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
					MethodType.class, java.lang.invoke.MethodHandle.class, MethodType.class).toMethodDescriptorString(), false);

	// --- the providers every case links against -----------------------------------------------------------------

	/** The classes of package {@code p}: what the cases' callers in {@code q} (and {@code p}) refer to. */
	static Map<String, byte[]> providers() {
		Map<String, byte[]> out = new LinkedHashMap<>();
		out.put("p/A", cls("p/A", ACC_PUBLIC, null, cw -> {
			ctor(cw, "java/lang/Object", ACC_PUBLIC);
			field(cw, ACC_PRIVATE | ACC_STATIC, "hidden", "I");
			field(cw, ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "CONST", "I");
			method(cw, ACC_STATIC, "<clinit>", "()V", mv -> {
				mv.visitInsn(ICONST_1);
				mv.visitFieldInsn(PUTSTATIC, "p/A", "CONST", "I");
			});
			method(cw, ACC_PUBLIC | ACC_STATIC, "pub", "()V", mv -> { });
			method(cw, ACC_PRIVATE | ACC_STATIC, "secret", "()V", mv -> { });
			method(cw, ACC_STATIC, "pkg", "()V", mv -> { });
			method(cw, ACC_PROTECTED | ACC_STATIC, "prot", "()V", mv -> { });
			method(cw, ACC_PUBLIC, "inst", "()V", mv -> { });
			field(cw, ACC_PUBLIC, "f", "I");
		}));
		out.put("p/Abs", cls("p/Abs", ACC_PUBLIC | ACC_ABSTRACT, null, cw -> ctor(cw, "java/lang/Object", ACC_PUBLIC)));
		// A class a nest member names as its host, which does not list it back: the member is its own host (JDK 15+).
		out.put("p/Lonely", cls("p/Lonely", ACC_PUBLIC, null, cw -> {
			ctor(cw, "java/lang/Object", ACC_PUBLIC);
			method(cw, ACC_PRIVATE | ACC_STATIC, "secret", "()V", mv -> { });
		}));
		out.put("p/Hidden", cls("p/Hidden", 0, null, cw -> {
			ctor(cw, "java/lang/Object", ACC_PUBLIC);
			method(cw, ACC_PUBLIC | ACC_STATIC, "m", "()V", mv -> { });
		}));
		out.put("p/Fin", cls("p/Fin", ACC_PUBLIC | ACC_FINAL, null, cw -> ctor(cw, "java/lang/Object", ACC_PUBLIC)));
		out.put("p/Base", cls("p/Base", ACC_PUBLIC, null, cw -> {
			ctor(cw, "java/lang/Object", ACC_PUBLIC);
			method(cw, ACC_PUBLIC | ACC_FINAL, "locked", "()V", mv -> { });
			method(cw, ACC_PROTECTED, "prot", "()V", mv -> { });
			method(cw, ACC_PROTECTED | ACC_STATIC, "sprot", "()V", mv -> { });
		}));
		out.put("p/Other", cls("p/Other", ACC_PUBLIC, "p/Base", cw -> ctor(cw, "p/Base", ACC_PUBLIC)));
		out.put("p/I", cls("p/I", ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, null, cw -> {
			method(cw, ACC_PUBLIC | ACC_STATIC, "s", "()V", mv -> { });
			method(cw, ACC_PRIVATE, "p", "()V", mv -> { });
			// A default method calling the interface's own private method, as javac 11+ emits it.
			method(cw, ACC_PUBLIC, "d", "()V", mv -> {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitMethodInsn(INVOKEINTERFACE, "p/I", "p", "()V", true);
			});
		}));
		out.put("p/J", cls("p/J", ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT, null, cw -> { }, "p/I"));
		out.put("p/Impl", cls("p/Impl", ACC_PUBLIC, null, cw -> ctor(cw, "java/lang/Object", ACC_PUBLIC), "p/J"));
		// Hud's shape on the genuine base: the vanilla twin of a method that is public on the merged base, private here.
		out.put("p/Hud", cls("p/Hud", ACC_PUBLIC, null, cw -> {
			ctor(cw, "java/lang/Object", ACC_PUBLIC);
			method(cw, ACC_PRIVATE, "extract", "()V", mv -> { });
		}));
		// Nestmates: Outer's private member, called from its nest member as javac 11+ calls it (no accessor).
		out.put("p/Outer", cls("p/Outer", ACC_PUBLIC, null, cw -> {
			cw.visitNestMember("p/Outer$Inner");
			ctor(cw, "java/lang/Object", ACC_PUBLIC);
			method(cw, ACC_PRIVATE | ACC_STATIC, "secret", "()V", mv -> { });
		}));
		out.put("p/F", cls("p/F", ACC_PUBLIC, null, cw -> {
			field(cw, ACC_FINAL, "x", "I");
			method(cw, ACC_PUBLIC, "<init>", "()V", mv -> {
				mv.visitVarInsn(ALOAD, 0);
				mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
				mv.visitVarInsn(ALOAD, 0);
				mv.visitInsn(ICONST_0);
				mv.visitFieldInsn(PUTFIELD, "p/F", "x", "I");
			});
		}));
		return out;
	}

	// --- the cases ---------------------------------------------------------------------------------------------------

	/**
	 * One case: its classes (beside the providers), the class whose static {@code run()} the JVM calls, the violation the
	 * check must report (null: none), the checks that violation needs, and the error the JVM throws (null: none).
	 */
	record Case(String id, Map<String, byte[]> classes, String caller, Kind kind, Set<Check> needs,
			Class<? extends Throwable> jvm, boolean judgeRuntime) {
	}

	static Case red(String id, Kind kind, Set<Check> needs, Class<? extends Throwable> jvm, Consumer<MethodVisitor> body) {
		return new Case(id, Map.of("q/Caller", caller("q/Caller", "java/lang/Object", body)), "q/Caller", kind, needs, jvm, false);
	}

	static Case green(String id, Consumer<MethodVisitor> body) {
		return new Case(id, Map.of("q/Caller", caller("q/Caller", "java/lang/Object", body)), "q/Caller", null, Set.of(), null, false);
	}

	static List<Case> redCases() {
		List<Case> cases = new ArrayList<>();
		Set<Check> existence = EnumSet.of(Check.EXISTENCE), access = EnumSet.of(Check.ACCESS), fin = EnumSet.of(Check.FINAL);
		cases.add(red("missing method", Kind.MISSING_METHOD, existence, NoSuchMethodError.class,
				mv -> mv.visitMethodInsn(INVOKESTATIC, "p/A", "gone", "()V", false)));
		cases.add(red("missing field", Kind.MISSING_FIELD, existence, NoSuchFieldError.class, mv -> {
			mv.visitFieldInsn(GETSTATIC, "p/A", "gone", "I");
			mv.visitInsn(POP);
		}));
		cases.add(red("missing class in a package the inputs own", Kind.MISSING_CLASS, existence, NoClassDefFoundError.class, mv -> {
			mv.visitLdcInsn(Type.getObjectType("p/Gone"));
			mv.visitInsn(POP);
		}));
		cases.add(red("missing interface method", Kind.MISSING_METHOD, existence, NoSuchMethodError.class, mv -> {
			mv.visitInsn(ACONST_NULL);
			mv.visitMethodInsn(INVOKEINTERFACE, "p/I", "gone", "()V", true);
		}));
		cases.add(red("private method of another class", Kind.MEMBER_ACCESS, access, IllegalAccessError.class,
				mv -> mv.visitMethodInsn(INVOKESTATIC, "p/A", "secret", "()V", false)));
		cases.add(red("package-private method from another package", Kind.MEMBER_ACCESS, access, IllegalAccessError.class,
				mv -> mv.visitMethodInsn(INVOKESTATIC, "p/A", "pkg", "()V", false)));
		cases.add(red("protected method from an unrelated class", Kind.MEMBER_ACCESS, access, IllegalAccessError.class,
				mv -> mv.visitMethodInsn(INVOKESTATIC, "p/A", "prot", "()V", false)));
		cases.add(red("private field of another class", Kind.MEMBER_ACCESS, access, IllegalAccessError.class, mv -> {
			mv.visitFieldInsn(GETSTATIC, "p/A", "hidden", "I");
			mv.visitInsn(POP);
		}));
		cases.add(red("package-private class from another package", Kind.CLASS_ACCESS, access, IllegalAccessError.class, mv -> {
			mv.visitLdcInsn(Type.getObjectType("p/Hidden"));
			mv.visitInsn(POP);
		}));
		cases.add(red("Hud-shaped: an instance method public on one base, private on this one", Kind.MEMBER_ACCESS, access,
				IllegalAccessError.class, mv -> {
					mv.visitInsn(ACONST_NULL);
					mv.visitMethodInsn(INVOKEVIRTUAL, "p/Hud", "extract", "()V", false);
				}));
		cases.add(new Case("protected instance method through an unrelated receiver",
				Map.of("q/Sub2", caller("q/Sub2", "p/Base", mv -> {
					mv.visitTypeInsn(NEW, "p/Other");
					mv.visitInsn(DUP);
					mv.visitMethodInsn(INVOKESPECIAL, "p/Other", "<init>", "()V", false);
					mv.visitMethodInsn(INVOKEVIRTUAL, "p/Other", "prot", "()V", false);
				})), "q/Sub2", Kind.MEMBER_ACCESS, access, IllegalAccessError.class, false));
		// No constructors on these two: one calling the inaccessible or final superclass's would be a second reference.
		cases.add(new Case("superclass in another package and not public", Map.of("q/SubHidden", cls("q/SubHidden",
				ACC_PUBLIC, "p/Hidden", cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> { }))), "q/SubHidden",
				Kind.CLASS_ACCESS, access,
				IllegalAccessError.class, false));
		cases.add(new Case("private access from a class whose nest host does not list it", Map.of("p/Lonely$Inner",
				cls("p/Lonely$Inner", ACC_PUBLIC, null, cw -> {
					cw.visitNestHost("p/Lonely");
					ctor(cw, "java/lang/Object", ACC_PUBLIC);
					method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> mv.visitMethodInsn(INVOKESTATIC, "p/Lonely", "secret", "()V", false));
				})), "p/Lonely$Inner", Kind.MEMBER_ACCESS, access, IllegalAccessError.class, false));
		// A member or class that changed kind between two builds: IncompatibleClassChangeError, or InstantiationError.
		Set<Check> kind = EnumSet.of(Check.KIND);
		cases.add(red("static read of an instance field", Kind.INCOMPATIBLE, kind, IncompatibleClassChangeError.class, mv -> {
			mv.visitFieldInsn(GETSTATIC, "p/A", "f", "I");
			mv.visitInsn(POP);
		}));
		cases.add(red("instance read of a static field", Kind.INCOMPATIBLE, kind, IncompatibleClassChangeError.class, mv -> {
			mv.visitInsn(ACONST_NULL);
			mv.visitFieldInsn(GETFIELD, "p/A", "CONST", "I");
			mv.visitInsn(POP);
		}));
		cases.add(red("static call of an instance method", Kind.INCOMPATIBLE, kind, IncompatibleClassChangeError.class,
				mv -> mv.visitMethodInsn(INVOKESTATIC, "p/A", "inst", "()V", false)));
		cases.add(red("instance call of a static method", Kind.INCOMPATIBLE, kind, IncompatibleClassChangeError.class, mv -> {
			mv.visitInsn(ACONST_NULL);
			mv.visitMethodInsn(INVOKEVIRTUAL, "p/A", "pub", "()V", false);
		}));
		cases.add(red("class method reference whose owner is an interface", Kind.INCOMPATIBLE, kind,
				IncompatibleClassChangeError.class, mv -> mv.visitMethodInsn(INVOKESTATIC, "p/I", "s", "()V", false)));
		cases.add(red("interface method reference whose owner is a class", Kind.INCOMPATIBLE, kind,
				IncompatibleClassChangeError.class, mv -> {
					mv.visitInsn(ACONST_NULL);
					mv.visitMethodInsn(INVOKEINTERFACE, "p/A", "inst", "()V", true);
				}));
		cases.add(red("new of an abstract class", Kind.INCOMPATIBLE, kind, InstantiationError.class, mv -> {
			mv.visitTypeInsn(NEW, "p/Abs");
			mv.visitInsn(DUP);
			mv.visitMethodInsn(INVOKESPECIAL, "p/Abs", "<init>", "()V", false);
			mv.visitInsn(POP);
		}));
		cases.add(new Case("superclass that is an interface", Map.of("q/SubItf", cls("q/SubItf", ACC_PUBLIC, "p/I",
				cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> { }))), "q/SubItf", Kind.INCOMPATIBLE, kind,
				IncompatibleClassChangeError.class, false));
		cases.add(new Case("implements a class", Map.of("q/ImplCls", cls("q/ImplCls", ACC_PUBLIC, null,
				cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> { }), "p/A")), "q/ImplCls", Kind.INCOMPATIBLE,
				kind, IncompatibleClassChangeError.class, false));
		cases.add(red("write to another class's final field", Kind.FINAL, fin, IllegalAccessError.class, mv -> {
			mv.visitInsn(ICONST_2);
			mv.visitFieldInsn(PUTSTATIC, "p/A", "CONST", "I");
		}));
		// The same write from the writer's own initializer: only the declaring-class rule sees it (the method is <clinit>).
		cases.add(new Case("write to another class's final field from the writer's initializer", Map.of("q/Init", cls("q/Init",
				ACC_PUBLIC, null, cw -> {
					method(cw, ACC_STATIC, "<clinit>", "()V", mv -> {
						mv.visitInsn(ICONST_2);
						mv.visitFieldInsn(PUTSTATIC, "p/A", "CONST", "I");
					});
					method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> { });
				})), "q/Init", Kind.FINAL, fin, IllegalAccessError.class, false));
		cases.add(new Case("extends a final class", Map.of("q/SubFin", cls("q/SubFin", ACC_PUBLIC, "p/Fin",
				cw -> method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> { }))), "q/SubFin",
				Kind.FINAL, fin, IncompatibleClassChangeError.class, false));
		cases.add(new Case("overrides a final method", Map.of("q/SubBase", cls("q/SubBase", ACC_PUBLIC, "p/Base", cw -> {
			ctor(cw, "p/Base", ACC_PUBLIC);
			method(cw, ACC_PUBLIC, "locked", "()V", mv -> { });
			method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> { });
		})), "q/SubBase", Kind.FINAL, fin, IncompatibleClassChangeError.class, false));
		cases.add(new Case("own final field written outside the initializer", Map.of("p/G", cls("p/G", ACC_PUBLIC, null, cw -> {
			field(cw, ACC_FINAL, "x", "I");
			ctor(cw, "java/lang/Object", ACC_PUBLIC);
			method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> {
				mv.visitTypeInsn(NEW, "p/G");
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKESPECIAL, "p/G", "<init>", "()V", false);
				mv.visitInsn(ICONST_1);
				mv.visitFieldInsn(PUTFIELD, "p/G", "x", "I");
			});
		})), "p/G", Kind.FINAL, fin, IllegalAccessError.class, false));
		Set<Check> indyExistence = EnumSet.of(Check.INDY, Check.EXISTENCE), indyAccess = EnumSet.of(Check.INDY, Check.ACCESS);
		cases.add(red("invokedynamic whose bootstrap method is missing", Kind.INDY_MISSING, indyExistence, NoSuchMethodError.class,
				mv -> mv.visitInvokeDynamicInsn("x", "()V", new Handle(H_INVOKESTATIC, "p/A", "gone",
						"(" + LOOKUP + "Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;", false))));
		cases.add(red("lambda whose implementation is another class's private method", Kind.INDY_ACCESS, indyAccess,
				IllegalAccessError.class, mv -> {
					mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", METAFACTORY, Type.getMethodType("()V"),
							new Handle(H_INVOKESTATIC, "p/A", "secret", "()V", false), Type.getMethodType("()V"));
					mv.visitInsn(POP);
				}));
		cases.add(red("method handle constant to a missing field", Kind.INDY_MISSING, indyExistence, NoSuchFieldError.class, mv -> {
			mv.visitLdcInsn(new Handle(H_GETSTATIC, "p/A", "gone", "I", false));
			mv.visitInsn(POP);
		}));
		cases.add(red("method handle constant calling an instance method as static", Kind.INDY_INCOMPATIBLE,
				EnumSet.of(Check.INDY, Check.KIND), IncompatibleClassChangeError.class, mv -> {
					mv.visitLdcInsn(new Handle(H_INVOKESTATIC, "p/A", "inst", "()V", false));
					mv.visitInsn(POP);
				}));
		cases.add(red("method handle constant writing a final field", Kind.INDY_FINAL, EnumSet.of(Check.INDY, Check.FINAL),
				IllegalAccessError.class, mv -> {
					mv.visitLdcInsn(new Handle(H_PUTSTATIC, "p/A", "CONST", "I", false));
					mv.visitInsn(POP);
				}));
		return cases;
	}

	/** The false-positive controls: each links and runs in the JVM, and the check must say nothing. */
	static List<Case> greenCases() {
		List<Case> cases = new ArrayList<>();
		cases.add(new Case("nestmate private access (Java 11)", Map.of("p/Outer$Inner", cls("p/Outer$Inner", ACC_PUBLIC, null, cw -> {
			cw.visitNestHost("p/Outer");
			ctor(cw, "java/lang/Object", ACC_PUBLIC);
			method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> mv.visitMethodInsn(INVOKESTATIC, "p/Outer", "secret", "()V", false));
		})), "p/Outer$Inner", null, Set.of(), null, false));
		cases.add(new Case("protected through a subclass receiver", Map.of("q/Sub", cls("q/Sub", ACC_PUBLIC, "p/Base", cw -> {
			ctor(cw, "p/Base", ACC_PUBLIC);
			method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", mv -> {
				mv.visitTypeInsn(NEW, "q/Sub");
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKESPECIAL, "q/Sub", "<init>", "()V", false);
				mv.visitInsn(DUP);
				mv.visitMethodInsn(INVOKEVIRTUAL, "q/Sub", "prot", "()V", false);
				mv.visitMethodInsn(INVOKEVIRTUAL, "p/Base", "prot", "()V", false);
				mv.visitMethodInsn(INVOKESTATIC, "p/Base", "sprot", "()V", false);
			});
		})), "q/Sub", null, Set.of(), null, false));
		// Judged with the JDK in scope: invokeExact's descriptor is the call site's, not MethodHandle's.
		cases.add(new Case("signature-polymorphic MethodHandle.invoke and invokeExact", Map.of("q/Caller", caller("q/Caller",
				"java/lang/Object", mv -> {
					mv.visitLdcInsn(new Handle(H_INVOKESTATIC, "p/A", "pub", "()V", false));
					mv.visitInsn(DUP);
					mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invokeExact", "()V", false);
					mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/invoke/MethodHandle", "invoke", "()V", false);
				})), "q/Caller", null, Set.of(), null, true));
		cases.add(green("static and private interface methods, default methods through superinterfaces", mv -> {
			mv.visitMethodInsn(INVOKESTATIC, "p/I", "s", "()V", true);
			mv.visitTypeInsn(NEW, "p/Impl");
			mv.visitInsn(DUP);
			mv.visitMethodInsn(INVOKESPECIAL, "p/Impl", "<init>", "()V", false);
			mv.visitInsn(DUP);
			mv.visitMethodInsn(INVOKEINTERFACE, "p/J", "d", "()V", true);
			mv.visitMethodInsn(INVOKEVIRTUAL, "p/Impl", "d", "()V", false);
		}));
		cases.add(green("array clone()", mv -> {
			mv.visitInsn(ICONST_1);
			mv.visitIntInsn(NEWARRAY, T_INT);
			mv.visitMethodInsn(INVOKEVIRTUAL, "[I", "clone", "()Ljava/lang/Object;", false);
			mv.visitInsn(POP);
			mv.visitInsn(ICONST_1);
			mv.visitTypeInsn(ANEWARRAY, "p/A");
			mv.visitMethodInsn(INVOKEVIRTUAL, "[Lp/A;", "clone", "()Ljava/lang/Object;", false);
			mv.visitInsn(POP);
		}));
		cases.add(green("public members and the lambdas javac writes", mv -> {
			mv.visitMethodInsn(INVOKESTATIC, "p/A", "pub", "()V", false);
			mv.visitFieldInsn(GETSTATIC, "p/A", "CONST", "I");
			mv.visitInsn(POP);
			mv.visitInvokeDynamicInsn("run", "()Ljava/lang/Runnable;", METAFACTORY, Type.getMethodType("()V"),
					new Handle(H_INVOKESTATIC, "p/A", "pub", "()V", false), Type.getMethodType("()V"));
			mv.visitMethodInsn(INVOKEINTERFACE, "java/lang/Runnable", "run", "()V", true);
		}));
		return cases;
	}

	// --- the tests ---------------------------------------------------------------------------------------------------

	@Test void theProvidersAloneLink() {
		LinkCheck.Result result = check(providers(), Map.of(), LinkCheck.Options.all().withJudgeRuntime());
		assertEquals(List.of(), result.violations());
		assertTrue(result.judged() > 0, "nothing was judged");
	}

	@Test void eachFlaggedCaseIsReportedAloneAndTheJvmRefusesItTheSameWay() {
		for (Case c : redCases()) {
			LinkCheck.Result result = check(providers(), c.classes(), LinkCheck.Options.all());
			assertEquals(1, result.violations().size(), c.id() + ": " + result.violations());
			assertEquals(c.kind(), result.violations().getFirst().kind(), c.id() + ": " + result.violations());
			Throwable thrown = runInJvm(c);
			assertNotNull(thrown, c.id() + ": the JVM ran what the check flags");
			assertTrue(chainHas(thrown, c.jvm()), c.id() + ": the JVM threw " + chain(thrown) + ", not " + c.jvm().getName());
			// In the test report, as the record of what the JVM said for each case.
			System.out.println(c.id() + ": " + result.violations().getFirst().line() + "  ||  JVM: " + chain(thrown));
		}
	}

	@Test void eachFalsePositiveControlLinksAndRuns() {
		for (Case c : greenCases()) {
			LinkCheck.Options options = c.judgeRuntime() ? LinkCheck.Options.all().withJudgeRuntime() : LinkCheck.Options.all();
			LinkCheck.Result result = check(providers(), c.classes(), options);
			assertEquals(List.of(), result.violations(), c.id());
			assertEquals(0, result.unresolved(), c.id() + ": something was left unjudged");
			assertNull(runInJvm(c), c.id() + ": the JVM refused a case the check passes");
		}
	}

	/**
	 * The red control of every check, and its specificity: with one check switched off, exactly the cases that need it go
	 * unreported, and every other case is still reported as before.
	 */
	@Test void eachCheckIsTheOnlyOneThatSeesItsCases() {
		for (Check off : Check.values()) {
			int lost = 0;
			for (Case c : redCases()) {
				LinkCheck.Result result = check(providers(), c.classes(), LinkCheck.Options.all().without(off));
				if (c.needs().contains(off)) {
					assertEquals(List.of(), result.violations(), "with " + off + " off, " + c.id() + " is still reported");
					lost++;
				} else {
					assertEquals(1, result.violations().size(), "with " + off + " off, " + c.id() + ": " + result.violations());
					assertEquals(c.kind(), result.violations().getFirst().kind(), c.id());
				}
			}
			assertTrue(lost > 0, off + " guards no case");
		}
	}

	/**
	 * A host as the game defined it can list a nest member its scanned bytes do not (Mixin adds a helper it synthesises to
	 * its target's NestMembers; GameLinkCheck takes that from a definitions capture): {@link LinkCheck#nestMember} records
	 * it, and the JVM agrees once the host it defines lists the member. A member the host does not list stays refused.
	 */
	@Test void aNestMemberTheDefinedHostListsIsANestmate() {
		Case lonely = redCases().stream().filter(c -> c.id().startsWith("private access from a class whose nest host"))
				.findFirst().orElseThrow();
		assertEquals(1, nestCheck(lonely, "p/Someone$Else").violations().size(), "a member the host does not list");
		assertEquals(List.of(), nestCheck(lonely, "p/Lonely$Inner").violations());
		Map<String, byte[]> defined = new LinkedHashMap<>(lonely.classes());
		defined.put("p/Lonely", withNestMember(providers().get("p/Lonely"), "p/Lonely$Inner"));
		assertNull(runInJvm(new Case(lonely.id(), defined, lonely.caller(), null, Set.of(), null, false)),
				"the JVM refused a nest member its defined host lists");
	}

	private static LinkCheck.Result nestCheck(Case c, String member) {
		LinkCheck check = new LinkCheck();
		c.classes().values().forEach(b -> check.scanClass(b, true));
		providers().values().forEach(b -> check.scanClass(b, true));
		return check.nestMember("p/Lonely", member).run();
	}

	private static byte[] withNestMember(byte[] host, String member) {
		ClassWriter cw = new ClassWriter(0);
		new ClassReader(host).accept(new ClassVisitor(ASM9, cw) {
			@Override
			public void visitEnd() {
				super.visitNestMember(member);
				super.visitEnd();
			}
		}, 0);
		return cw.toByteArray();
	}

	/**
	 * Both directions: the game base calling a carrier member that is gone, and the carrier calling a base member that is
	 * gone. Each is judged in the run that judges the jar its owner belongs to, and only there.
	 */
	@Test void referencesAreJudgedInTheRunThatJudgesTheirOwner() {
		Map<String, byte[]> game = Map.of("g/Game", caller("g/Game", "java/lang/Object",
				mv -> mv.visitMethodInsn(INVOKESTATIC, "c/Carrier", "gone", "()V", false)));
		Map<String, byte[]> carrier = Map.of("c/Carrier", caller("c/Carrier", "java/lang/Object",
				mv -> mv.visitMethodInsn(INVOKESTATIC, "g/Game", "gone", "()V", false)));
		List<LinkCheck.Violation> intoGame = check(game, carrier, true, false).violations();
		List<LinkCheck.Violation> intoCarrier = check(game, carrier, false, true).violations();
		List<LinkCheck.Violation> both = check(game, carrier, true, true).violations();
		assertEquals(1, intoGame.size(), intoGame.toString());
		assertTrue(intoGame.getFirst().line().contains("c/Carrier#run()V  ->  METHOD g/Game.gone()V"), intoGame.toString());
		assertEquals(1, intoCarrier.size(), intoCarrier.toString());
		assertTrue(intoCarrier.getFirst().line().contains("g/Game#run()V  ->  METHOD c/Carrier.gone()V"), intoCarrier.toString());
		assertEquals(2, both.size(), both.toString());
	}

	/** A reference into a package no input defines (a library this check was not given) is out of scope, not dangling. */
	@Test void aClassFromAPackageNoInputDefinesIsOutOfScope() {
		Map<String, byte[]> caller = Map.of("q/Caller", caller("q/Caller", "java/lang/Object", mv -> {
			mv.visitMethodInsn(INVOKESTATIC, "lib/Absent", "call", "()V", false);
		}));
		LinkCheck.Result result = check(providers(), caller, LinkCheck.Options.all());
		assertEquals(List.of(), result.violations());
		assertTrue(result.outOfScope() > 0);
	}

	/** The command line the installer runs: its verdict line counts every kind, and its exit code follows it. */
	@Test void theCommandLineCountsEveryViolationInItsVerdict(@TempDir Path dir) throws IOException {
		Map<String, byte[]> classes = new LinkedHashMap<>(providers());
		classes.put("q/Caller", caller("q/Caller", "java/lang/Object", mv -> {
			mv.visitMethodInsn(INVOKESTATIC, "p/A", "secret", "()V", false);
			mv.visitMethodInsn(INVOKESTATIC, "p/A", "gone", "()V", false);
			mv.visitMethodInsn(INVOKESTATIC, "p/A", "inst", "()V", false);
		}));
		Path jar = jar(dir.resolve("all.jar"), classes);
		String[] out = new String[1];
		int code = capture(out, new String[] { jar.toString() });
		assertEquals(1, code, out[0]);
		assertTrue(out[0].contains("[MEMBER_ACCESS] q/Caller#run()V  ->  METHOD p/A.secret()V  (private in p/A)"), out[0]);
		assertTrue(out[0].contains("[MISSING_METHOD] q/Caller#run()V  ->  METHOD p/A.gone()V"), out[0]);
		String verdict = out[0].lines().filter(l -> l.contains("dangling references:")).reduce((a, b) -> b).orElseThrow();
		assertTrue(out[0].contains("[INCOMPATIBLE] q/Caller#run()V  ->  METHOD p/A.inst()V"), out[0]);
		assertTrue(out[0].contains("[link-check] missing 1, inaccessible 1, final 0, incompatible 1,"), out[0]);
		assertTrue(verdict.matches("\\[link-check\\] loaded \\d+ classes .*; dangling references: 3"), verdict);

		Path clean = jar(dir.resolve("clean.jar"), providers());
		code = capture(out, new String[] { "--judge", clean.toString(), clean.toString() });
		assertEquals(0, code, out[0]);
		assertTrue(out[0].lines().anyMatch(l -> l.matches("\\[link-check\\] loaded \\d+ classes .*; dangling references: 0")), out[0]);

		Path empty = jar(dir.resolve("empty.jar"), Map.of());
		code = capture(out, new String[] { "--judge", empty.toString(), empty.toString(), clean.toString() });
		assertEquals(2, code, out[0]);
		assertTrue(out[0].contains("no classes"), out[0]);
	}

	/**
	 * The checker's own classes need ASM's core and the JDK and nothing else of the kernel: the installer carries them as a
	 * standalone jar and runs them before it has any of Forbric's jars.
	 */
	@Test void theCheckerNeedsOnlyAsmAndTheJdk() throws IOException {
		Path classes = Path.of(LinkCheck.class.getProtectionDomain().getCodeSource().getLocation().getPath())
				.resolve("net/forbric/kernel/linkcheck");
		List<String> foreign = new ArrayList<>();
		int read = 0;
		try (var files = Files.list(classes)) {
			for (Path file : files.filter(f -> f.toString().endsWith(".class")).toList()) {
				read++;
				for (String named : referencedClasses(Files.readAllBytes(file))) {
					if (!named.startsWith("java/") && !named.startsWith("org/objectweb/asm/")
							&& !named.matches("net/forbric/kernel/linkcheck/[^/]+")) foreign.add(file.getFileName() + " -> " + named);
				}
			}
		}
		assertTrue(read > 0, "no checker classes under " + classes);
		assertEquals(List.of(), foreign);
	}

	// --- helpers -----------------------------------------------------------------------------------------------------

	private static LinkCheck.Result check(Map<String, byte[]> providers, Map<String, byte[]> callers, LinkCheck.Options options) {
		LinkCheck check = new LinkCheck(options);
		callers.values().forEach(b -> check.scanClass(b, true));
		providers.values().forEach(b -> check.scanClass(b, true));
		return check.run();
	}

	private static LinkCheck.Result check(Map<String, byte[]> game, Map<String, byte[]> carrier, boolean judgeGame,
			boolean judgeCarrier) {
		LinkCheck check = new LinkCheck();
		game.values().forEach(b -> check.scanClass(b, judgeGame));
		carrier.values().forEach(b -> check.scanClass(b, judgeCarrier));
		return check.run();
	}

	/** Loads the case's classes and the providers in one loader and calls the caller's static {@code run()}. */
	static Throwable runInJvm(Case c) {
		Map<String, byte[]> all = new LinkedHashMap<>(providers());
		all.putAll(c.classes());
		ClassLoader loader = new ClassLoader("linkcheck-case", ClassLoader.getPlatformClassLoader()) {
			@Override
			protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] bytes = all.get(name.replace('.', '/'));
				if (bytes == null) throw new ClassNotFoundException(name);
				return defineClass(name, bytes, 0, bytes.length);
			}
		};
		try {
			Class.forName(c.caller().replace('/', '.'), true, loader).getMethod("run").invoke(null);
			return null;
		} catch (InvocationTargetException thrown) {
			return thrown.getCause();
		} catch (Throwable thrown) {
			return thrown;
		}
	}

	private static boolean chainHas(Throwable thrown, Class<? extends Throwable> type) {
		for (Throwable t = thrown; t != null; t = t.getCause()) if (type.isInstance(t)) return true;
		return false;
	}

	private static String chain(Throwable thrown) {
		StringBuilder sb = new StringBuilder();
		for (Throwable t = thrown; t != null; t = t.getCause()) sb.append(sb.isEmpty() ? "" : " <- ").append(t);
		return sb.toString();
	}

	static byte[] cls(String name, int access, String superName, Consumer<ClassWriter> members, String... interfaces) {
		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cw.visit(V21, access | ((access & ACC_INTERFACE) == 0 ? ACC_SUPER : 0), name, null,
				superName == null ? "java/lang/Object" : superName, interfaces);
		members.accept(cw);
		cw.visitEnd();
		return cw.toByteArray();
	}

	/** A public class with a public constructor and a {@code public static void run()} whose body is {@code body}. */
	static byte[] caller(String name, String superName, Consumer<MethodVisitor> body) {
		return cls(name, ACC_PUBLIC, superName, cw -> {
			ctor(cw, superName, ACC_PUBLIC);
			method(cw, ACC_PUBLIC | ACC_STATIC, "run", "()V", body);
		});
	}

	static void ctor(ClassWriter cw, String superName, int access) {
		method(cw, access, "<init>", "()V", mv -> {
			mv.visitVarInsn(ALOAD, 0);
			mv.visitMethodInsn(INVOKESPECIAL, superName, "<init>", "()V", false);
		});
	}

	static void field(ClassWriter cw, int access, String name, String desc) {
		FieldVisitor fv = cw.visitField(access, name, desc, null, null);
		fv.visitEnd();
	}

	/** A void method: {@code body}, then {@code return}. */
	static void method(ClassWriter cw, int access, String name, String desc, Consumer<MethodVisitor> body) {
		MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
		mv.visitCode();
		body.accept(mv);
		mv.visitInsn(RETURN);
		mv.visitMaxs(0, 0);
		mv.visitEnd();
	}

	static Path jar(Path file, Map<String, byte[]> classes) throws IOException {
		try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(file))) {
			for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
				out.putNextEntry(new JarEntry(entry.getKey() + ".class"));
				out.write(entry.getValue());
				out.closeEntry();
			}
		}
		return file;
	}

	/** Runs the command line with its output and errors captured into {@code out[0]}. */
	private static int capture(String[] out, String[] args) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		PrintStream both = new PrintStream(buffer, true, StandardCharsets.UTF_8);
		int code = LinkCheck.run(args, both, both);
		out[0] = buffer.toString(StandardCharsets.UTF_8);
		return code;
	}

	/** Every class a class file names in its constant pool. */
	private static Set<String> referencedClasses(byte[] bytes) {
		Set<String> out = new java.util.TreeSet<>();
		ClassReader reader = new ClassReader(bytes);
		char[] buffer = new char[reader.getMaxStringLength()];
		for (int i = 1; i < reader.getItemCount(); i++) {
			int offset = reader.getItem(i);
			if (offset > 0 && bytes[offset - 1] == 7) { // CONSTANT_Class
				String name = reader.readUTF8(offset, buffer);
				if (name.startsWith("[")) {
					Type element = Type.getType(name).getElementType();
					if (element.getSort() == Type.OBJECT) out.add(element.getInternalName());
				} else {
					out.add(name);
				}
			}
		}
		return out;
	}
}
