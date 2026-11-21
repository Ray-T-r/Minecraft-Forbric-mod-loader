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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import net.forbric.kernel.ExpectCaught;
import net.forbric.kernel.util.Caught;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * What the frame recomputer says about a class when the inference needs a type no class file here gives.
 *
 * <p>A join of two branches is inferred by walking both types' superclass chains. When one chain reaches a type whose
 * class file is not there (a library of another operating system, an optional dependency), the join cannot be computed.
 * The recomputer then lets {@code java/lang/Object} stand in for that one join: Object is above every answer the join
 * could have had, so the frames inferred with it are never narrower than the true ones. They can show that nothing needs
 * repair (every declared frame that was compared accepts even Object: it is declared as Object, as an interface, under
 * the same name, or not as a reference at all), and then the class is handed back without a word. They cannot show that
 * something does need repair, and they are never written out: where a declared frame does not accept the stand-in, or a
 * method could not be compared at all, the class is handed back and the report stays.
 *
 * <p>Every shape here is made with ASM under invented names, and each one runs under two unrelated sets of names: the
 * rule looks at shapes, so a shape must be judged the same whatever it is called. The resolver is the map of the world's
 * types and then the platform class loader; it never falls back to the test class path. A test that must be quiet carries
 * no {@link ExpectCaught}: one failure caught by the kernel and {@code CaughtErrorsExtension} fails it. A test that must
 * still report says what the report mentions, and checks that exactly one was made. Beside the recomputer's answer each
 * shape is put to the real verifier, in a loader that has exactly the same types: every quiet shape here links, and every
 * reported one fails to. (A report can still be more than the verifier needs, where a hierarchy loops, runs past the
 * walk's bound, or a method could not be compared, under a frame declared as an interface. Those are known and are not
 * held here as something that must stay.)
 */
class MergedBaseFrameUnreadTypeTest {
	private static final String OBJ = "java/lang/Object";
	/** What the report made after a stand-in says, and the report of anything else that went wrong does not. */
	private static final String STOOD_IN = "java/lang/Object in place of the merges";

	// ---------------------------------------------------------------------------------------------------------------
	// Must be quiet: the class comes back as it went in, and nothing is caught
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * The shape this was found on: a static factory on an interface, one branch per implementation, all joining a frame
	 * declared as the interface itself, and one implementation extends a type that is not there. The resolver does not
	 * serve the interface either; the recomputer knows it from the definition it was handed.
	 */
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void anInterfaceThatJoinsItsOwnImplementationsIsQuiet(String p) throws Exception {
		World world = new World(p);
		byte[] self = world.self();
		assertNull(world.resource(p + "Self.class"), "the resolver must not serve the interface being transformed");
		assertQuiet(world, self);
	}

	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aFrameDeclaredAsAnInterfaceIsQuiet(String p) throws Exception {
		World world = new World(p);
		String speaker = p + "Speaker";
		assertQuiet(world, pick(p + "Pick", speaker, speaker, false, p + "A", p + "A2", p + "B"));
		assertQuiet(world, pick(p + "PickBA", speaker, speaker, false, p + "B", p + "A"));
	}

	/** Whether the Object-typed value comes first or last, the conclusion is the same one. */
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aFrameDeclaredAsObjectIsQuiet(String p) throws Exception {
		World world = new World(p);
		assertQuiet(world, pick(p + "PickO", OBJ, OBJ, false, p + "A", p + "B"));
		assertQuiet(world, pick(p + "PickOAB", OBJ, OBJ, false, OBJ, p + "A", p + "B"));
		assertQuiet(world, pick(p + "PickABO", OBJ, OBJ, false, p + "A", p + "B", OBJ));
	}

	/** Both types are stored into one local that the join frame does not declare: nothing is compared there. */
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aLocalNoFrameDeclaresIsQuiet(String p) throws Exception {
		World world = new World(p);
		assertQuiet(world, dead(p + "Dead", p + "A", p + "B"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void arraysOfAnInterfaceOrOfObjectAreQuiet(String p) throws Exception {
		World world = new World(p);
		String speakers = "[L" + p + "Speaker;", objects = "[L" + OBJ + ";";
		assertQuiet(world, pick(p + "PickArr", speakers, speakers, true, p + "A", p + "B"));
		assertNull(world.resource(p + "gone/Impl.class"));
		assertQuiet(world, pick(p + "ArrI", speakers, speakers, true, p + "A", p + "gone/Impl"));
		assertQuiet(world, pick(p + "ArrO", objects, objects, true, p + "A", p + "gone/Impl"));
	}

	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aSourceWithNoClassFileAtAllIsQuiet(String p) throws Exception {
		World world = new World(p);
		String speaker = p + "Speaker";
		for (String gone : List.of("gone/Impl", "gone/One", "gone/Two")) assertNull(world.resource(p + gone + ".class"));
		assertQuiet(world, pick(p + "PickGone", speaker, speaker, false, p + "A", p + "gone/Impl"));
		assertQuiet(world, pick(p + "PickGG", speaker, speaker, false, p + "gone/One", p + "gone/Two"));
	}

	/** Declared and inferred under one name: the hierarchy is not asked, so a type that is not there is not missed. */
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aDeclaredTypeWithNoClassFileIsNotAskedForWhenTheInferenceNamesItToo(String p) throws Exception {
		World world = new World(p);
		assertNull(world.resource(p + "gone/Local.class"));
		assertQuiet(world, sameName(p + "Same", p + "gone/Local"));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Must still report: the class comes back as it went in, with exactly one report
	// ---------------------------------------------------------------------------------------------------------------

	@ExpectCaught(STOOD_IN)
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aFrameDeclaredAsAClassStillReports(String p) throws Exception {
		World world = new World(p);
		String root = p + "Root";
		byte[] reader = pick(p + "PickR", root, root, false, p + "RA", p + "RB");
		assertReports(world, reader, STOOD_IN, root, p + "gone/Base");
		assertThrows(NoClassDefFoundError.class, () -> link(world, reader));
	}

	@ExpectCaught(STOOD_IN)
	@ParameterizedTest
	@CsvSource({"shape/,RB,gone/Base", "shape/,gone/Impl,gone/Impl", "other/names/deep/,RB,gone/Base", "other/names/deep/,gone/Impl,gone/Impl"})
	void anArrayOfADeclaredClassStillReports(String p, String second, String unread) throws Exception {
		World world = new World(p);
		String roots = "[L" + p + "Root;";
		assertNull(world.resource(p + unread + ".class"));
		byte[] reader = pick(p + "PickRArr", roots, roots, true, p + "RA", p + second);
		assertReports(world, reader, STOOD_IN, roots, p + unread);
		assertThrows(NoClassDefFoundError.class, () -> link(world, reader));
	}

	@ExpectCaught(STOOD_IN)
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void twoSourcesWithNoClassFileStillReportIntoAClass(String p) throws Exception {
		World world = new World(p);
		String root = p + "Root";
		byte[] reader = pick(p + "PickGGR", root, root, false, p + "gone/One", p + "gone/Two");
		assertReports(world, reader, STOOD_IN, root, p + "gone/");
		assertThrows(NoClassDefFoundError.class, () -> link(world, reader));
	}

	/**
	 * Two exception-table entries for one handler, whose frame names a class above both catch types, and one catch type
	 * is not there: the shape most of the real classes that still report have.
	 */
	@ExpectCaught(STOOD_IN)
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aHandlerOfTwoCatchTypesStillReportsWhenOneHasNoClassFile(String p) throws Exception {
		World world = new World(p);
		byte[] reader = multiCatch(p + "Catch", "java/lang/Exception", "java/lang/IllegalStateException", p + "gone/Ex");
		assertReports(world, reader, STOOD_IN, "java/lang/Exception", p + "gone/Ex");
		assertThrows(NoClassDefFoundError.class, () -> link(world, reader));
	}

	/**
	 * One method's frame really disagrees with the hierarchy, another's join needed a stand-in. The frames inferred over
	 * a stand-in are never written out, so the class is not repaired; it is reported, and the wrong frame is named.
	 */
	@ExpectCaught(STOOD_IN)
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aWrongFrameBesideAnUnreadMergeIsReportedAndNothingIsRewritten(String p) throws Exception {
		World world = new World(p);
		byte[] reader = both(p + "FixDead", p + "Old", p + "First", p + "Second", p + "A", p + "B");
		assertReports(world, reader, STOOD_IN, p + "Old", p + "gone/Base");
		assertThrows(VerifyError.class, () -> link(world, reader));
	}

	/**
	 * A method that carries unreachable code is rewritten by the frame computation and cannot be compared frame by
	 * frame, so its declared frames prove nothing either way. With a stand-in in the same class that is not a proof that
	 * nothing needs repair. Two readable sources beside the unreadable one: A2 is a Root, so whether the declared frame
	 * holds turns on the type that is not there, and the verifier fails on exactly that; A is not a Root, and the
	 * verifier rejects the frame outright. Neither may pass in silence.
	 */
	@ExpectCaught("methods whose frames could not be compared: [pick")
	@ParameterizedTest
	@CsvSource({"shape/,A2", "shape/,A", "other/names/deep/,A2", "other/names/deep/,A"})
	void anUnreadMergeBesideAMethodThatCouldNotBeComparedStillReports(String p, String readable) throws Exception {
		World world = new World(p);
		byte[] reader = host(p + "Host", p + "Root", p + readable, p + "RB");
		assertReports(world, reader, STOOD_IN, p + "gone/Base", "methods whose frames could not be compared: [pick");
		Class<? extends LinkageError> verdict = readable.equals("A2") ? NoClassDefFoundError.class : VerifyError.class;
		assertThrows(verdict, () -> link(world, reader));
	}

	/**
	 * The declared type itself is not there: that is the report of a judgment that could not be made at all, not the
	 * stand-in's. Which absent type it names depends on what was asked first, so only "one of them" is held.
	 */
	@ExpectCaught("cannot prove a frame repair for")
	@ParameterizedTest
	@CsvSource({"shape/,RA", "shape/,B", "other/names/deep/,RA", "other/names/deep/,B"})
	void aDeclaredTypeWithNoClassFileStillReports(String p, String second) throws Exception {
		World world = new World(p);
		assertNull(world.resource(p + "gone/Decl.class"));
		byte[] reader = pick(p + "PickD", p + "gone/Decl", OBJ, false, p + "A", p + second);
		assertFalse(assertReports(world, reader, p + "gone/").contains(STOOD_IN), "the declared type's own absence is not a stand-in's report");
		assertThrows(NoClassDefFoundError.class, () -> link(world, reader));
	}

	/** No join at all: one source on every edge, its chain cannot be walked, and the frame declares another class. */
	@ExpectCaught("cannot prove a frame repair for")
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aSingleUnreadableSourceStillReportsIntoAClass(String p) throws Exception {
		World world = new World(p);
		byte[] reader = single(p + "Single", p + "Root", p + "RB");
		assertFalse(assertReports(world, reader, p + "gone/Base").contains(STOOD_IN), "there was no join to stand in for");
		assertThrows(NoClassDefFoundError.class, () -> link(world, reader));
	}

	/** A hierarchy that loops is not a missing class file, and nothing stands in for it. */
	@ExpectCaught("Cyclic hierarchy")
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aLoopingSourceStillReportsIntoAClass(String p) throws Exception {
		World world = new World(p);
		world.types.put(p + "CycA", plain(p + "CycA", p + "CycB"));
		world.types.put(p + "CycB", plain(p + "CycB", p + "CycA"));
		String root = p + "Root";
		byte[] reader = pick(p + "PickCycR", root, root, false, p + "RA", p + "CycA");
		assertFalse(assertReports(world, reader, "Cyclic hierarchy").contains(STOOD_IN), "a loop must not be taken for an absent class file");
		assertThrows(ClassCircularityError.class, () -> link(world, reader));
	}

	/** Nor is a chain longer than the walk is willing to follow. */
	@ExpectCaught("Hierarchy exceeds proof bound")
	@ParameterizedTest
	@ValueSource(strings = {"shape/", "other/names/deep/"})
	void aChainPastTheProofBoundStillReportsIntoAClass(String p) throws Exception {
		World world = new World(p);
		for (int depth = 0; depth < 70; depth++) world.types.put(p + "D" + depth, plain(p + "D" + depth, depth == 69 ? OBJ : p + "D" + (depth + 1)));
		String root = p + "Root";
		byte[] reader = pick(p + "PickDeepR", root, root, false, p + "RA", p + "D0");
		assertFalse(assertReports(world, reader, "Hierarchy exceeds proof bound").contains(STOOD_IN), "a long chain must not be taken for an absent class file");
		assertThrows(VerifyError.class, () -> link(world, reader));
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Repairs, order, the contract of the public join, and judgments running at once
	// ---------------------------------------------------------------------------------------------------------------

	@Test
	void aRepairIsStillMadeWhateverWasJudgedBefore() throws Exception {
		World world = new World("order/");
		byte[] quiet = world.quiet("Pick"), wrong = world.wrong();
		MergedBaseFrameRecomputer quietFirst = world.recomputer();
		assertSame(quiet, judge(quietFirst, quiet));
		byte[] after = judge(quietFirst, wrong);
		assertNotSame(wrong, after, "a class whose frame disagrees with the hierarchy is recomputed after a quiet judgment");
		MergedBaseFrameRecomputer wrongFirst = world.recomputer();
		byte[] before = judge(wrongFirst, wrong);
		assertNotSame(wrong, before);
		assertSame(quiet, judge(wrongFirst, quiet));
		assertArrayEquals(before, after, "the repair is the same bytes whatever the instance judged first");
		assertThrows(VerifyError.class, () -> link(world, wrong));
		Class<?> repaired = link(world, after);
		assertEquals("order.First", repaired.getMethod("pick", int.class).invoke(null, 0).getClass().getName());
		assertEquals("order.Second", repaired.getMethod("pick", int.class).invoke(null, 1).getClass().getName());
	}

	/** Absence is not a fact about the hierarchy: the same instance asks again, and answers as a fresh one does. */
	@ExpectCaught(STOOD_IN)
	@Test
	void absenceIsNotRemembered() throws Exception {
		World world = new World("order/");
		String root = "order/Root";
		byte[] reader = pick("order/PickR", root, root, false, "order/RA", "order/RB");
		MergedBaseFrameRecomputer one = world.recomputer();
		long before = Caught.errorCount();
		assertSame(reader, judge(one, reader));
		assertEquals(before + 1, Caught.errorCount(), "the first judgment reports: " + Caught.failureSites());
		world.types.put("order/gone/Base", plain("order/gone/Base", root));   // the type arrives: a jar joined the loader
		assertSame(reader, judge(one, reader), "RA and RB now join in Root, which is what the frame declares");
		assertEquals(before + 1, Caught.errorCount(), "the second judgment has nothing to report: " + Caught.failureSites());
		assertSame(reader, judge(world.recomputer(), reader), "and a fresh instance says the same");
		assertEquals(before + 1, Caught.errorCount());
		link(world, reader);
	}

	@ExpectCaught(STOOD_IN)
	@Test
	void aTypeThatArrivesLetsTheRepairHappen() throws Exception {
		World world = new World("order/");
		byte[] reader = pick("order/PickOld", "order/Old", OBJ, false, "order/B", "order/A");
		MergedBaseFrameRecomputer one = world.recomputer();
		assertReports(one, reader, STOOD_IN, "order/Old", "order/gone/Base");
		world.types.put("order/gone/Base", plain("order/gone/Base", "order/Current"));
		long before = Caught.errorCount();
		byte[] repaired = judge(one, reader);
		assertNotSame(reader, repaired, "with the type there the join is known, and the frame that disagrees with it is recomputed");
		assertEquals(before, Caught.errorCount(), "the repair itself reports nothing: " + Caught.failureSites());
		assertThrows(VerifyError.class, () -> link(world, reader));
		link(world, repaired);
	}

	/** A quiet judgment leaves nothing behind for the callers that write the join into bytes: they are still refused. */
	@Test
	void aQuietJudgmentTeachesTheInstanceNothing() throws Exception {
		World world = new World("order/");
		byte[] quiet = world.quiet("Pick");
		MergedBaseFrameRecomputer one = world.recomputer();
		assertSame(quiet, judge(one, quiet));
		assertThrows(TypeNotPresentException.class, () -> one.commonSuperClass("order/B", "order/A"));
		assertThrows(TypeNotPresentException.class, () -> one.commonSuperClass("order/B", "order/A2"));
		world.types.put("order/gone/Base", plain("order/gone/Base", "order/Root"));
		assertEquals(OBJ, one.commonSuperClass("order/B", "order/A"));
		assertEquals("order/Root", one.commonSuperClass("order/B", "order/A2"));
	}

	/**
	 * The public join is what the callers that write frames into bytes ask. A stand-in in bytes they emit would turn a
	 * class that runs into one that fails verification, so for them a join that cannot be computed stays an exception.
	 */
	@Test
	void anUndecidableJoinStillThrowsForCallersThatWriteIt() {
		World world = new World("order/");
		assertNull(world.resource("order/gone/Impl.class"));
		MergedBaseFrameRecomputer fresh = world.recomputer();
		assertThrows(TypeNotPresentException.class, () -> fresh.commonSuperClass("order/B", "order/A"));
		assertThrows(TypeNotPresentException.class, () -> fresh.commonSuperClass("order/A", "order/B"));
		assertThrows(TypeNotPresentException.class, () -> fresh.commonSuperClass("order/A", "order/gone/Impl"));
		assertThrows(TypeNotPresentException.class, () -> fresh.commonSuperClass("order/gone/Impl", "order/A"));
	}

	/**
	 * Three classes, two fresh instances, opposite orders: each class gets the same answer. This covers judgments that
	 * rest on class headers read from resources. It does not cover the header an instance keeps of a class it has itself
	 * transformed: a class with no resource is known to later judgments only once it has been through the instance, and
	 * that dependence on order is as it was.
	 */
	@Test
	void classesWithClassFilesAreJudgedTheSameInEitherOrder() throws Exception {
		World world = new World("order/");
		List<byte[]> classes = List.of(world.self(), world.quiet("Pick"), world.wrong());
		MergedBaseFrameRecomputer forward = world.recomputer(), backward = world.recomputer();
		byte[][] there = new byte[3][], back = new byte[3][];
		for (int i = 0; i < 3; i++) there[i] = judge(forward, classes.get(i));
		for (int i = 2; i >= 0; i--) back[i] = judge(backward, classes.get(i));
		assertSame(classes.get(0), there[0]);
		assertSame(classes.get(0), back[0]);
		assertSame(classes.get(1), there[1]);
		assertSame(classes.get(1), back[1]);
		assertNotSame(classes.get(2), there[2]);
		assertArrayEquals(there[2], back[2]);
	}

	/**
	 * One instance judges on several threads at once, as the class loader calls it. What one judgment could not read
	 * belongs to that judgment: were it shared, a quiet shape on one thread would make a repair on another decline.
	 */
	@Test
	void judgmentsRunningAtOnceDoNotShareWhatOneCouldNotRead() throws Exception {
		World world = new World("conc/");
		byte[] expected = judge(world.recomputer(), world.wrong());
		assertFalse(Arrays.equals(world.wrong(), expected), "the single-threaded repair rewrites the class");
		MergedBaseFrameRecomputer one = world.recomputer();
		long before = Caught.errorCount();
		ExecutorService pool = Executors.newFixedThreadPool(8);
		Map<String, Integer> tally = new TreeMap<>();
		try {
			List<Future<String>> all = new ArrayList<>();
			for (int i = 0; i < 4000; i++) {
				int n = i;
				all.add(pool.submit(() -> {
					if (n % 2 == 0) {
						byte[] in = world.quiet("Quiet" + n);
						return one.transform("conc.Quiet", in, null) == in ? "ok" : "quiet shape rewritten";
					}
					byte[] in = world.wrong(), out = one.transform("conc.Fix", in, null);
					return out == in ? "repair declined" : Arrays.equals(out, expected) ? "ok" : "repair gave other bytes";
				}));
			}
			for (Future<String> each : all) tally.merge(each.get(), 1, Integer::sum);
		} finally {
			pool.shutdownNow();
		}
		assertEquals(Map.of("ok", 4000), tally);
		assertEquals(before, Caught.errorCount(), "nothing is caught: " + Caught.failureSites());
	}

	// ---------------------------------------------------------------------------------------------------------------
	// The world of types, the judgments, the real verifier
	// ---------------------------------------------------------------------------------------------------------------

	/**
	 * The types under one prefix. {@code gone/...} names have no class file: {@code B} (which implements the interface)
	 * and {@code RB} extend {@code gone/Base}, so their chains cannot be walked. {@code Self} is the interface of
	 * {@link #self()} and is not in the map on purpose.
	 */
	private static final class World {
		final String p;
		final Map<String, byte[]> types = new ConcurrentHashMap<>();

		World(String p) {
			this.p = p;
			String speaker = p + "Speaker", root = p + "Root", gone = p + "gone/Base", self = p + "Self";
			types.put(speaker, iface(speaker));
			types.put(root, plain(root, OBJ));
			types.put(p + "Old", plain(p + "Old", OBJ));
			types.put(p + "Current", plain(p + "Current", OBJ));
			types.put(p + "A", plain(p + "A", OBJ, speaker));
			types.put(p + "A2", plain(p + "A2", root, speaker));
			types.put(p + "B", plain(p + "B", gone, speaker));
			types.put(p + "RA", plain(p + "RA", root));
			types.put(p + "RB", plain(p + "RB", gone));
			types.put(p + "First", plain(p + "First", p + "Current"));
			types.put(p + "Second", plain(p + "Second", p + "Current"));
			types.put(p + "SA", plain(p + "SA", OBJ, self));
			types.put(p + "SA2", plain(p + "SA2", root, self));
			types.put(p + "SB", plain(p + "SB", gone, self));
			// Without this every test below would pass on a world where nothing is unreadable. Two plain classes, one of
			// whose chains cannot be walked: a join that really cannot be computed, not a join with Object.
			assertNull(resource(gone + ".class"));
			assertThrows(TypeNotPresentException.class, () -> recomputer().commonSuperClass(p + "B", p + "A"),
					"the premise of every shape here: B's chain reaches a type no class file gives");
		}

		/** The world's types, then the JDK's; never the test class path. */
		byte[] resource(String path) {
			byte[] known = types.get(path.substring(0, path.length() - ".class".length()));
			if (known != null) return known;
			try (InputStream in = ClassLoader.getPlatformClassLoader().getResourceAsStream(path)) {
				return in == null ? null : in.readAllBytes();
			} catch (IOException unreadable) {
				return null;
			}
		}

		MergedBaseFrameRecomputer recomputer() {
			return new MergedBaseFrameRecomputer(this::resource);
		}

		/** An interface whose own static method joins three of its implementations into a frame declared as itself. */
		byte[] self() {
			String self = p + "Self";
			ClassWriter writer = new ClassWriter(0);
			writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, self, null, OBJ, null);
			pickMethod(writer, self, self, false, p + "SA", p + "SA2", p + "SB");
			writer.visitEnd();
			return writer.toByteArray();
		}

		/** A plain class whose join of A, A2 and B is declared as the interface all three implement. */
		byte[] quiet(String name) {
			return pick(p + name, p + "Speaker", p + "Speaker", false, p + "A", p + "A2", p + "B");
		}

		/** Every type readable, and the declared frame wrong: First and Second join in Current, not in Old. */
		byte[] wrong() {
			return pick(p + "Fix", p + "Old", OBJ, false, p + "First", p + "Second");
		}
	}

	private static byte[] judge(MergedBaseFrameRecomputer recomputer, byte[] input) {
		return recomputer.transform(new ClassReader(input).getClassName().replace('/', '.'), input, null);
	}

	/** A fresh instance hands the class back, catches nothing, and the real verifier links those bytes. */
	private static void assertQuiet(World world, byte[] input) throws Exception {
		long before = Caught.errorCount();
		assertSame(input, judge(world.recomputer(), input), "the class comes back as it went in");
		assertEquals(before, Caught.errorCount(), "nothing is caught: " + Caught.failureSites());
		link(world, input);
	}

	private static String assertReports(World world, byte[] input, String... mentions) {
		return assertReports(world.recomputer(), input, mentions);
	}

	/** The class comes back as it went in with exactly one report, which mentions all of {@code mentions}; its text. */
	private static String assertReports(MergedBaseFrameRecomputer recomputer, byte[] input, String... mentions) {
		long before = Caught.errorCount();
		assertSame(input, judge(recomputer, input), "a class that is reported is never rewritten");
		List<Caught.Site> sites = Caught.failureSites();
		assertEquals(before + 1, Caught.errorCount(), "exactly one report: " + sites);
		assertEquals(1, sites.size(), "one site: " + sites);
		String said = sites.get(0).toString();
		for (String mention : mentions) assertTrue(said.contains(mention), "the report names " + mention + ": " + said);
		return said;
	}

	/** The real verifier: the class defined in a loader that has exactly the world's types, and linked. */
	private static Class<?> link(World world, byte[] bytes) throws Exception {
		Map<String, byte[]> definitions = new HashMap<>(world.types);
		String owner = new ClassReader(bytes).getClassName();
		definitions.put(owner, bytes);
		ClassLoader loader = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
			@Override protected Class<?> findClass(String name) throws ClassNotFoundException {
				byte[] definition = definitions.get(name.replace('.', '/'));
				if (definition == null) throw new ClassNotFoundException(name);
				return defineClass(name, definition, 0, definition.length);
			}
		};
		Class<?> linked = Class.forName(owner.replace('/', '.'), false, loader);
		linked.getDeclaredMethods();
		return linked;
	}

	// ---------------------------------------------------------------------------------------------------------------
	// Class builders
	// ---------------------------------------------------------------------------------------------------------------

	private static byte[] iface(String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, name, null, OBJ, null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static byte[] plain(String owner, String parent, String... interfaces) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, parent, interfaces);
		MethodVisitor init = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.visitCode();
		init.visitVarInsn(Opcodes.ALOAD, 0);
		init.visitMethodInsn(Opcodes.INVOKESPECIAL, parent, "<init>", "()V", false);
		init.visitInsn(Opcodes.RETURN);
		init.visitMaxs(1, 1);
		init.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A new instance of {@code type}, or a one-element array of it. */
	private static void value(MethodVisitor method, String type, boolean array) {
		if (array) {
			method.visitInsn(Opcodes.ICONST_1);
			method.visitTypeInsn(Opcodes.ANEWARRAY, type);
		} else {
			method.visitTypeInsn(Opcodes.NEW, type);
			method.visitInsn(Opcodes.DUP);
			method.visitMethodInsn(Opcodes.INVOKESPECIAL, type, "<init>", "()V", false);
		}
	}

	/**
	 * {@code static R pick(int)}: one branch per source type, all joining a frame whose one stack slot is declared as
	 * {@code declared}. Both {@code declared} and {@code returned} are internal names (an array's is its descriptor).
	 */
	private static byte[] pick(String owner, String declared, String returned, boolean array, String... sources) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, OBJ, null);
		pickMethod(writer, declared, returned, array, sources);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void pickMethod(ClassWriter writer, String declared, String returned, boolean array, String... sources) {
		String descriptor = "(I)" + (returned.startsWith("[") ? returned : "L" + returned + ";");
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", descriptor, null, null);
		method.visitCode();
		Label join = new Label();
		for (int i = 0; i < sources.length; i++) {
			boolean last = i == sources.length - 1;
			Label next = new Label();
			if (!last) {
				method.visitVarInsn(Opcodes.ILOAD, 0);
				method.visitIntInsn(Opcodes.BIPUSH, i);
				method.visitJumpInsn(Opcodes.IF_ICMPNE, next);
			}
			value(method, sources[i], array);
			if (!last) {
				method.visitJumpInsn(Opcodes.GOTO, join);
				method.visitLabel(next);
				method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
			}
		}
		method.visitLabel(join);
		method.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {declared});
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(2, 1);
		method.visitEnd();
	}

	/** {@code static Object dead(int)}: two types stored into one local that the join frame does not declare. */
	private static byte[] dead(String owner, String a, String b) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, OBJ, null);
		deadMethod(writer, a, b);
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static void deadMethod(ClassWriter writer, String a, String b) {
		// Local 1 is a String the frames declare, so that the class has a reference frame at all; local 2 is the dead one.
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "dead", "(I)Ljava/lang/Object;", null, null);
		method.visitCode();
		Label second = new Label(), join = new Label();
		method.visitLdcInsn("x");
		method.visitVarInsn(Opcodes.ASTORE, 1);
		method.visitVarInsn(Opcodes.ILOAD, 0);
		method.visitJumpInsn(Opcodes.IFEQ, second);
		value(method, b, false);
		method.visitVarInsn(Opcodes.ASTORE, 2);
		method.visitJumpInsn(Opcodes.GOTO, join);
		method.visitLabel(second);
		method.visitFrame(Opcodes.F_APPEND, 1, new Object[] {"java/lang/String"}, 0, null);
		value(method, a, false);
		method.visitVarInsn(Opcodes.ASTORE, 2);
		method.visitLabel(join);
		method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
		method.visitVarInsn(Opcodes.ALOAD, 1);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(2, 3);
		method.visitEnd();
	}

	/** A frame that really disagrees with the hierarchy (pick) beside a dead-local join of an unreadable type (dead). */
	private static byte[] both(String owner, String declared, String first, String second, String a, String b) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, OBJ, null);
		pickMethod(writer, declared, OBJ, false, first, second);
		deadMethod(writer, a, b);
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** {@code static Object single(int)}: one source type on every edge into a frame that declares the local as another class. */
	private static byte[] single(String owner, String declared, String source) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, OBJ, null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "single", "(I)Ljava/lang/Object;", null, null);
		method.visitCode();
		Label after = new Label();
		value(method, source, false);
		method.visitVarInsn(Opcodes.ASTORE, 1);
		method.visitVarInsn(Opcodes.ILOAD, 0);
		method.visitJumpInsn(Opcodes.IFEQ, after);
		method.visitInsn(Opcodes.NOP);
		method.visitLabel(after);
		method.visitFrame(Opcodes.F_APPEND, 1, new Object[] {declared}, 0, null);
		method.visitVarInsn(Opcodes.ALOAD, 1);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(2, 2);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** Two exception-table entries, one handler: its frame's stack slot is declared as {@code declared}. */
	private static byte[] multiCatch(String owner, String declared, String readable, String absent) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, OBJ, null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(I)Ljava/lang/Object;", null, null);
		method.visitCode();
		Label start = new Label(), end = new Label(), handler = new Label();
		method.visitTryCatchBlock(start, end, handler, readable);
		method.visitTryCatchBlock(start, end, handler, absent);
		method.visitLabel(start);
		method.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/System", "nanoTime", "()J", false);
		method.visitInsn(Opcodes.POP2);
		method.visitLabel(end);
		method.visitInsn(Opcodes.ACONST_NULL);
		method.visitInsn(Opcodes.ARETURN);
		method.visitLabel(handler);
		method.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {declared});
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(2, 1);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/** A local whose type has no class file, declared by the frame under the very name the inference gives it. */
	private static byte[] sameName(String owner, String gone) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, OBJ, null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(I)Ljava/lang/Object;", null, null);
		method.visitCode();
		Label after = new Label();
		method.visitInsn(Opcodes.ACONST_NULL);
		method.visitTypeInsn(Opcodes.CHECKCAST, gone);
		method.visitVarInsn(Opcodes.ASTORE, 1);
		method.visitVarInsn(Opcodes.ILOAD, 0);
		method.visitJumpInsn(Opcodes.IFEQ, after);
		method.visitInsn(Opcodes.NOP);
		method.visitLabel(after);
		method.visitFrame(Opcodes.F_APPEND, 1, new Object[] {gone}, 0, null);
		method.visitVarInsn(Opcodes.ALOAD, 1);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(1, 2);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	/**
	 * {@code static D pick(boolean)}: the join of {@code a} and {@code b} declared as {@code declared}, and after the
	 * return a framed tail nothing reaches. The frame computation rewrites such a tail, so the method's instructions no
	 * longer line up with the original's and none of its frames is compared.
	 */
	private static byte[] host(String owner, String declared, String a, String b) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, owner, null, OBJ, null);
		MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pick", "(Z)L" + declared + ";", null, null);
		method.visitCode();
		Label other = new Label(), join = new Label();
		method.visitVarInsn(Opcodes.ILOAD, 0);
		method.visitJumpInsn(Opcodes.IFEQ, other);
		value(method, a, false);
		method.visitJumpInsn(Opcodes.GOTO, join);
		method.visitLabel(other);
		method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
		value(method, b, false);
		method.visitLabel(join);
		method.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {declared});
		method.visitInsn(Opcodes.ARETURN);
		method.visitLabel(new Label());
		method.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
		method.visitInsn(Opcodes.ACONST_NULL);
		method.visitInsn(Opcodes.ARETURN);
		method.visitMaxs(2, 1);
		method.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}
}
