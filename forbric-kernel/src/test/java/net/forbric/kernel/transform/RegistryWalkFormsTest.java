/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.GateLogContract;
import net.forbric.kernel.interop.RegistryElementCallbacks;

/**
 * The late-registration completion is a property of the WALK, not of how one mod compiled it.
 *
 * <p>A mod initialises per-element state in one pass over a platform registry; the kernel registers Forge-family
 * content after that pass, so the later elements never get it. Every fixture here is that same situation written a
 * different way than Lithium writes it, under names no mod uses: a hand-written iterator loop whose element never
 * reaches a local, in an instance method with parameters and other work around the walk; index loops over
 * {@code size()}/{@code byId}; {@code forEach} with plain and capturing lambdas, on the registry and on its stream; a
 * callback whose result is thrown away; a loop with its test at the bottom and a compiler's null checks; and walks of
 * registries other than the block-state one. Each look-alike below is a walk that does NOT give every element the
 * callback exactly once, and must be left exactly as written.
 */
@ExecutesInjector(RegistryElementCallbackInjector.class)
class RegistryWalkFormsTest {
	@TempDir Path root;

	private static final String WALKER = "zz/thaw/Thaw";
	private static final String FLUID_STATE = "net/minecraft/world/level/material/FluidState", BLOCK = "net/minecraft/world/level/block/Block";

	/** The platform's registry types, two registries other than the block-state one, and a mod contract added by "mixin". */
	private static final Map<String, String> PLATFORM = Map.ofEntries(
			Map.entry("net.minecraft.core.IdMap", "package net.minecraft.core; public interface IdMap<T> extends Iterable<T> {int size(); T byId(int id);}"),
			Map.entry("net.minecraft.core.IdMapper", "package net.minecraft.core; public class IdMapper<T> implements IdMap<T> {"
					+ "private final java.util.List<T> values=new java.util.ArrayList<>(); public void add(T value){values.add(value);}"
					+ "public java.util.Iterator<T> iterator(){return values.iterator();} public int size(){return values.size();}"
					+ "public final T byId(int id){return id>=0&&id<values.size()?values.get(id):null;}}"),
			Map.entry("net.minecraft.core.Registry", "package net.minecraft.core; public interface Registry<T> extends IdMap<T> {"
					+ "default java.util.stream.Stream<T> stream(){return java.util.stream.StreamSupport.stream(spliterator(),false);}}"),
			Map.entry("net.minecraft.core.MappedRegistry", "package net.minecraft.core; public class MappedRegistry<T> implements Registry<T> {"
					+ "private final java.util.List<T> values=new java.util.ArrayList<>(); public void register(T value){values.add(value);}"
					+ "public java.util.Iterator<T> iterator(){return values.iterator();} public int size(){return values.size();}"
					+ "public T byId(int id){return id>=0&&id<values.size()?values.get(id):null;}}"),
			Map.entry("net.minecraft.core.registries.BuiltInRegistries", "package net.minecraft.core.registries; public class BuiltInRegistries {"
					+ "public static final net.minecraft.core.Registry<net.minecraft.world.level.block.Block> BLOCK=new net.minecraft.core.MappedRegistry<>();}"),
			Map.entry("net.minecraft.world.level.material.Fluid", "package net.minecraft.world.level.material; public class Fluid {"
					+ "public static final net.minecraft.core.IdMapper<FluidState> FLUID_STATE_REGISTRY=new net.minecraft.core.IdMapper<>();}"),
			Map.entry("net.minecraft.world.level.material.FluidState", "package net.minecraft.world.level.material; public class FluidState {"
					+ "public int warmed; public long seasons; public boolean fail;"
					+ "public void warm(){warmed++; if(fail)throw new IllegalStateException(\"thaw failed\");} public long season(){return ++seasons;}}"),
			Map.entry("net.minecraft.world.level.block.Block", "package net.minecraft.world.level.block; public class Block {"
					+ "public int warmed; public void warm(){warmed++;}}"),
			// The member is declared on a parent of the interface the walk casts to.
			Map.entry("zz.thaw.Kindled", "package zz.thaw; public interface Kindled {void warm();}"),
			Map.entry("zz.thaw.Warmable", "package zz.thaw; public interface Warmable extends Kindled {}"),
			Map.entry("zz.thaw.Seasoned", "package zz.thaw; public interface Seasoned {long season();}"),
			// What a Kotlin compiler calls for a platform value; a stand-in, so the fixture needs no Kotlin.
			Map.entry("kotlin.jvm.internal.Intrinsics", "package kotlin.jvm.internal; public class Intrinsics {"
					+ "public static void checkNotNullExpressionValue(Object value,String expression){if(value==null)throw new NullPointerException(expression);}}"));

	private static final String IMPORTS = "package zz.thaw; import java.util.*; import net.minecraft.core.*; import net.minecraft.core.registries.BuiltInRegistries;"
			+ "import net.minecraft.world.level.block.Block; import net.minecraft.world.level.material.*;";

	// ---- positives -----------------------------------------------------------------------------------------------

	@Test
	void aHandWrittenIteratorLoopInAnInstanceMethodWithParametersAndOtherWorkIsCompleted() throws Throwable {
		Loaded l = load(compile(IMPORTS + """
				public class Thaw {
				    public static int started, steps;
				    private final String label;
				    public Thaw(String label){this.label=label;}
				    public void warmAll(int passes,long stamp){
				        started+=passes+label.length();               // setup before the walk
				        Iterator<FluidState> it=Fluid.FLUID_STATE_REGISTRY.iterator();
				        while(it.hasNext()){
				            ((Warmable)it.next()).warm();             // the element never reaches a local
				            steps++;                                  // per-iteration work that never sees the element
				        }
				    }
				}"""));
		Object first = l.newFluid(), second = l.newFluid();
		InjectorExecution.invoke(InjectorExecution.construct(l.walker, "x"), "warmAll", 2, 7L);
		Object late = l.newFluid();
		assertEquals(1, RegistryElementCallbacks.complete(l.fluids));
		assertEquals(List.of(1, 1, 1), List.of(warmed(first), warmed(second), warmed(late)));
		assertEquals(0, RegistryElementCallbacks.complete(l.fluids), "a completed element is not given the callback twice");
		assertEquals(2, InjectorExecution.getStatic(l.walker, "steps"), "only the callback is completed, not the walk's other work");
	}

	@Test
	void indexLoopsOverSizeAndByIdAreCompleted() throws Throwable {
		for (String body : List.of(
				"for(int i=0;i<Fluid.FLUID_STATE_REGISTRY.size();i++) ((Warmable)Fluid.FLUID_STATE_REGISTRY.byId(i)).warm();",
				// the registry in a local, the bound read once, the element in a local, the comparison written backwards
				"IdMapper<FluidState> fluids=Fluid.FLUID_STATE_REGISTRY; int n=fluids.size(); for(int i=0;n>i;++i){FluidState f=fluids.byId(i); ((Warmable)f).warm();}")) {
			Loaded l = load(compile(IMPORTS + "public class Thaw {public static void warmAll(){" + body + "}}"));
			Object first = l.newFluid();
			InjectorExecution.invokeStatic(l.walker, "warmAll");
			Object late = l.newFluid();
			assertEquals(1, RegistryElementCallbacks.complete(l.fluids), body);
			assertEquals(1, warmed(first), body);
			assertEquals(1, warmed(late), body);
		}
	}

	@Test
	void forEachWithALambdaOnTheRegistryOrOnItsStreamIsCompleted() throws Throwable {
		record Form(String body, boolean blocks) { }
		for (Form form : List.of(
				new Form("Fluid.FLUID_STATE_REGISTRY.forEach(state->((Warmable)state).warm());", false),
				new Form("int[] seen={0}; Fluid.FLUID_STATE_REGISTRY.forEach(state->{((Warmable)state).warm(); seen[0]++;});", false),
				new Form("java.util.stream.StreamSupport.stream(Fluid.FLUID_STATE_REGISTRY.spliterator(),false).forEachOrdered(s->((Warmable)s).warm());", false),
				new Form("BuiltInRegistries.BLOCK.stream().forEach(block->((Warmable)block).warm());", true))) {
			Loaded l = load(compile(IMPORTS + "public class Thaw {public static void warmAll(){" + form.body + "}}"));
			Object first = form.blocks ? l.newBlock() : l.newFluid();
			InjectorExecution.invokeStatic(l.walker, "warmAll");
			Object late = form.blocks ? l.newBlock() : l.newFluid();
			assertEquals(1, RegistryElementCallbacks.complete(form.blocks ? l.blocks : l.fluids), form.body);
			assertEquals(1, warmed(first), form.body);
			assertEquals(1, warmed(late), form.body);
		}
	}

	@Test
	void aConsumerWalkThatFailsPartWayPublishesNothing() throws Throwable {
		Loaded l = load(compile(IMPORTS + "public class Thaw {public static void warmAll(){Fluid.FLUID_STATE_REGISTRY.forEach(s->((Warmable)s).warm());}}"));
		Object first = l.newFluid(), bad = l.newFluid();
		l.fluidState.getField("fail").setBoolean(bad, true);
		assertThrows(IllegalStateException.class, () -> InjectorExecution.invokeStatic(l.walker, "warmAll"));
		Object late = l.newFluid();
		assertEquals(0, RegistryElementCallbacks.complete(l.fluids), "an aborted walk proves nothing about the elements after it");
		assertEquals(List.of(1, 1, 0), List.of(warmed(first), warmed(bad), warmed(late)));
	}

	@Test
	void aCallbackWhoseResultTheWalkDiscardsIsCompleted() throws Throwable {
		Loaded l = load(compile(IMPORTS + "public class Thaw {public static void warmAll(){for(FluidState s:Fluid.FLUID_STATE_REGISTRY) ((Seasoned)s).season();}}"));
		Object first = l.newFluid();
		InjectorExecution.invokeStatic(l.walker, "warmAll");
		Object late = l.newFluid();
		assertEquals(1, RegistryElementCallbacks.complete(l.fluids));
		assertEquals(1L, l.fluidState.getField("seasons").getLong(first));
		assertEquals(1L, l.fluidState.getField("seasons").getLong(late));
	}

	@Test
	void aLoopWithItsTestAtTheBottomAndACompilersNullChecksIsCompleted() throws Throwable {
		Map<String, byte[]> classes = compile(IMPORTS + "public class Thaw {}");
		classes.put(WALKER, bottomTestedLoop());
		Loaded l = load(classes);
		Object first = l.newFluid();
		InjectorExecution.invokeStatic(l.walker, "warmAll");
		Object late = l.newFluid();
		assertEquals(1, RegistryElementCallbacks.complete(l.fluids));
		assertEquals(1, warmed(first));
		assertEquals(1, warmed(late));

		// Its pre-check is part of the walk: an empty registry still proves the walk ran, so a later element is completed.
		Loaded empty = load(classes);
		InjectorExecution.invokeStatic(empty.walker, "warmAll");
		Object afterEmpty = empty.newFluid();
		assertEquals(1, RegistryElementCallbacks.complete(empty.fluids));
		assertEquals(1, warmed(afterEmpty));
	}

	@Test
	void theKernelsCompletionCoversEveryRegistryAWalkCoveredAndTheLineNamesIt() throws Throwable {
		Map<String, byte[]> classes = compile(IMPORTS + "public class Thaw {public static void warmAll(){"
				+ "for(FluidState s:Fluid.FLUID_STATE_REGISTRY) ((Warmable)s).warm(); BuiltInRegistries.BLOCK.forEach(b->((Warmable)b).warm());}}");
		String[] out = new String[1];
		byte[] raw = classes.get(WALKER);
		byte[] edited = GateLogContract.capture(() -> InjectorExecution.transform(injector(classes), binary(WALKER), raw, EnvType.CLIENT), out);
		assertNotSame(raw, edited);
		assertTrue(out[0].contains("zz.thaw.Thaw.warmAll is a closed walk of the fluid-state registry with 1 per-element callback(s)"), out[0]);
		assertTrue(out[0].contains("zz.thaw.Thaw.warmAll is a closed walk of the block registry with 1 per-element callback(s)"), out[0]);

		Loaded l = load(classes);
		Object fluid = l.newFluid(), block = l.newBlock();
		InjectorExecution.invokeStatic(l.walker, "warmAll");
		Object lateFluid = l.newFluid(), lateBlock = l.newBlock();
		try {
			RegistryElementCallbacks.completeLateRegistrations();
		} catch (RuntimeException | Error otherTestsRegistry) {
			// Registries other tests published share this JVM; a failure there is theirs, and is rethrown only after ours ran.
		}
		assertEquals(List.of(1, 1, 1, 1), List.of(warmed(fluid), warmed(block), warmed(lateFluid), warmed(lateBlock)));
	}

	// ---- look-alikes -------------------------------------------------------------------------------------------------

	@Test
	void walksThatDoNotGiveEveryElementTheCallbackExactlyOnceAreLeftAsWritten() throws Exception {
		for (String body : List.of(
				// stops after the first element
				"for(FluidState s:Fluid.FLUID_STATE_REGISTRY){((Warmable)s).warm(); break;}",
				// takes two elements per iteration and calls back on every second one
				"Iterator<FluidState> it=Fluid.FLUID_STATE_REGISTRY.iterator(); while(it.hasNext()){it.next(); ((Warmable)it.next()).warm();}",
				// starts at 1, steps by 2, runs one past the end, or steps only sometimes
				"for(int i=1;i<Fluid.FLUID_STATE_REGISTRY.size();i++) ((Warmable)Fluid.FLUID_STATE_REGISTRY.byId(i)).warm();",
				"for(int i=0;i<Fluid.FLUID_STATE_REGISTRY.size();i+=2) ((Warmable)Fluid.FLUID_STATE_REGISTRY.byId(i)).warm();",
				"for(int i=0;i<=Fluid.FLUID_STATE_REGISTRY.size();i++) ((Warmable)Fluid.FLUID_STATE_REGISTRY.byId(i)).warm();",
				"for(int i=0;i<Fluid.FLUID_STATE_REGISTRY.size();){((Warmable)Fluid.FLUID_STATE_REGISTRY.byId(i)).warm(); if(Fluid.FLUID_STATE_REGISTRY.size()>3)i++;}",
				// decides per element, or hands the element to something else as well
				"Fluid.FLUID_STATE_REGISTRY.forEach(s->{if(s.warmed==0)((Warmable)s).warm();});",
				"BuiltInRegistries.BLOCK.stream().filter(b->b.warmed==0).forEach(b->((Warmable)b).warm());",
				"List<FluidState> seen=new ArrayList<>(); for(FluidState s:Fluid.FLUID_STATE_REGISTRY){((Warmable)s).warm(); seen.add(s);}",
				// a parallel stream, a walk run twice, a walk whose failure is swallowed
				"java.util.stream.StreamSupport.stream(Fluid.FLUID_STATE_REGISTRY.spliterator(),true).forEach(s->((Warmable)s).warm());",
				"for(int pass=0;pass<2;pass++) for(FluidState s:Fluid.FLUID_STATE_REGISTRY) ((Warmable)s).warm();",
				"try{for(FluidState s:Fluid.FLUID_STATE_REGISTRY) ((Warmable)s).warm();}catch(RuntimeException ignored){}")) {
			Map<String, byte[]> classes = compile(IMPORTS + "public class Thaw {public static void warmAll(){" + body + "}}");
			byte[] raw = classes.get(WALKER);
			assertSame(raw, InjectorExecution.transform(injector(classes), binary(WALKER), raw, EnvType.CLIENT), body);
		}
	}

	@Test
	void aRegistryTheModOwnsIsNotOneTheKernelRegistersLateIntoAndIsLeftAlone() throws Throwable {
		Map<String, byte[]> classes = compile(IMPORTS + "public class Thaw {public static final IdMapper<FluidState> OWN=new IdMapper<>();"
				+ "public static void warmAll(){for(FluidState s:OWN) ((Warmable)s).warm();}}");
		byte[] raw = classes.get(WALKER);
		assertSame(raw, InjectorExecution.transform(injector(classes), binary(WALKER), raw, EnvType.CLIENT));

		ClassLoader loader = InjectorExecution.load(classes);
		Class<?> walker = loader.loadClass(binary(WALKER));
		Object own = InjectorExecution.getStatic(walker, "OWN");
		Object state = InjectorExecution.construct(loader.loadClass(binary(FLUID_STATE)));
		InjectorExecution.invoke(own, "add", state);
		InjectorExecution.invokeStatic(walker, "warmAll");
		Object late = InjectorExecution.construct(loader.loadClass(binary(FLUID_STATE)));
		InjectorExecution.invoke(own, "add", late);
		assertEquals(0, RegistryElementCallbacks.complete(own));
		assertEquals(0, loader.loadClass(binary(FLUID_STATE)).getField("warmed").getInt(late));
	}

	// ---- fixtures ------------------------------------------------------------------------------------------------

	/**
	 * {@code warmAll()} as a compiler that tests at the bottom lays it out: null-check the registry, an "is it empty"
	 * pre-check, then a body whose {@code hasNext()} decides whether to go round again.
	 */
	private static byte[] bottomTestedLoop() {
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
			@Override protected String getCommonSuperClass(String a, String b) { return "java/lang/Object"; }
		};
		writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, WALKER, null, "java/lang/Object", null);
		MethodVisitor m = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "warmAll", "()V", null, null);
		m.visitCode();
		Label body = new Label(), end = new Label();
		m.visitFieldInsn(Opcodes.GETSTATIC, "net/minecraft/world/level/material/Fluid", "FLUID_STATE_REGISTRY", "Lnet/minecraft/core/IdMapper;");
		m.visitInsn(Opcodes.DUP);
		m.visitLdcInsn("FLUID_STATE_REGISTRY");
		m.visitMethodInsn(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNullExpressionValue", "(Ljava/lang/Object;Ljava/lang/String;)V", false);
		m.visitTypeInsn(Opcodes.CHECKCAST, "java/lang/Iterable");
		m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator", "()Ljava/util/Iterator;", true);
		m.visitVarInsn(Opcodes.ASTORE, 0);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
		m.visitJumpInsn(Opcodes.IFEQ, end);
		m.visitLabel(body);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true);
		m.visitTypeInsn(Opcodes.CHECKCAST, FLUID_STATE);
		m.visitVarInsn(Opcodes.ASTORE, 1);
		m.visitVarInsn(Opcodes.ALOAD, 1);
		m.visitTypeInsn(Opcodes.CHECKCAST, "zz/thaw/Warmable");
		m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "zz/thaw/Warmable", "warm", "()V", true);
		m.visitVarInsn(Opcodes.ALOAD, 0);
		m.visitMethodInsn(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true);
		m.visitJumpInsn(Opcodes.IFNE, body);
		m.visitLabel(end);
		m.visitInsn(Opcodes.RETURN);
		m.visitMaxs(0, 0);
		m.visitEnd();
		writer.visitEnd();
		return writer.toByteArray();
	}

	private Map<String, byte[]> compile(String walker) throws Exception {
		Map<String, String> sources = new HashMap<>(PLATFORM);
		sources.put("zz.thaw.Thaw", walker);
		Map<String, byte[]> compiled = InjectorExecution.compile(root.resolve("w" + Math.abs((long) walker.hashCode())), sources);
		// The contracts arrive on the elements the way a mixin adds them: after the walk was compiled against the platform.
		for (String element : List.of(FLUID_STATE, BLOCK)) {
			ClassNode node = new ClassNode();
			new ClassReader(compiled.get(element)).accept(node, 0);
			node.interfaces.add("zz/thaw/Warmable");
			if (element.equals(FLUID_STATE)) node.interfaces.add("zz/thaw/Seasoned");
			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			compiled.put(element, writer.toByteArray());
		}
		return compiled;
	}

	private static RegistryElementCallbackInjector injector(Map<String, byte[]> classes) {
		return new RegistryElementCallbackInjector(name -> {
			byte[] bytes = classes.get(name.replace('.', '/'));
			if (bytes == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(bytes).accept(node, 0);
			return node;
		});
	}

	private record Loaded(Class<?> walker, Class<?> fluidState, Class<?> block, Object fluids, Object blocks) {
		Object newFluid() throws Throwable { Object state = InjectorExecution.construct(fluidState); InjectorExecution.invoke(fluids, "add", state); return state; }
		Object newBlock() throws Throwable { Object b = InjectorExecution.construct(block); InjectorExecution.invoke(blocks, "register", b); return b; }
	}

	private Loaded load(Map<String, byte[]> originals) throws Throwable {
		Map<String, byte[]> classes = new HashMap<>(originals);
		byte[] raw = classes.get(WALKER);
		byte[] edited = InjectorExecution.transform(injector(classes), binary(WALKER), raw, EnvType.CLIENT);
		assertNotSame(raw, edited, "the walk was not recognised");
		classes.put(WALKER, edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader));
		Class<?> fluid = loader.loadClass("net.minecraft.world.level.material.Fluid");
		Class<?> registries = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries");
		return new Loaded(loader.loadClass(binary(WALKER)), loader.loadClass(binary(FLUID_STATE)), loader.loadClass(binary(BLOCK)),
				fluid.getField("FLUID_STATE_REGISTRY").get(null), registries.getField("BLOCK").get(null));
	}

	private static int warmed(Object element) throws ReflectiveOperationException {
		return element.getClass().getField("warmed").getInt(element);
	}

	private static String binary(String internal) { return internal.replace('/', '.'); }
}
