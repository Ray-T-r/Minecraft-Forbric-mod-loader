/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import net.fabricmc.api.EnvType;
import net.forbric.kernel.interop.RegistryElementCallbacks;

/**
 * The checks of {@link RegistryWalkProof} that nothing else backs up: in each look-alike below the ELEMENT flows exactly
 * as it does in a proved walk (taken once, cast, handed to one public no-argument interface member, nowhere else), so
 * the element data-flow check passes it, and only the one check named on each test can tell it is not a complete walk.
 *
 * <p>A late completion gives the callback to every element the walk did not record. A walk the proof wrongly accepted
 * would therefore hand the callback to elements the mod deliberately skipped, not only to late ones.
 *
 * <p>Every look-alike has a twin that differs only in that one fact (the same flag read beside the callback instead of
 * guarding it, a return after the callback instead of before it, the walked registry's own {@code size()} as the
 * bound) and IS completed, so a look-alike cannot be left alone for some reason other than the one it is there for.
 * Each look-alike was checked to be instrumented once its check is removed from the proof. Names, registries and
 * the contract are ones the other walk tests do not use: two built-in registries (one of them a defaulted one), a
 * contract added to both elements as a mixin would add it.
 */
@ExecutesInjector(RegistryElementCallbackInjector.class)
class RegistryWalkLoadBearingChecksTest {
	@TempDir Path root;

	private static final String WALKER = "yy/kiln/Kiln";
	private static final String ITEM = "net/minecraft/world/item/Item", EFFECT = "net/minecraft/world/effect/MobEffect";

	private static final Map<String, String> PLATFORM = Map.ofEntries(
			Map.entry("net.minecraft.core.IdMap", "package net.minecraft.core; public interface IdMap<T> extends Iterable<T> {int size(); T byId(int id);}"),
			Map.entry("net.minecraft.core.Registry", "package net.minecraft.core; public interface Registry<T> extends IdMap<T> {"
					+ "default java.util.stream.Stream<T> stream(){return java.util.stream.StreamSupport.stream(spliterator(),false);}}"),
			Map.entry("net.minecraft.core.DefaultedRegistry", "package net.minecraft.core; public interface DefaultedRegistry<T> extends Registry<T> {}"),
			Map.entry("net.minecraft.core.MappedRegistry", "package net.minecraft.core; public class MappedRegistry<T> implements Registry<T> {"
					+ "private final java.util.List<T> values=new java.util.ArrayList<>(); public void register(T value){values.add(value);}"
					+ "public java.util.Iterator<T> iterator(){return values.iterator();} public int size(){return values.size();}"
					+ "public T byId(int id){return id>=0&&id<values.size()?values.get(id):null;}}"),
			Map.entry("net.minecraft.core.DefaultedMappedRegistry", "package net.minecraft.core;"
					+ "public class DefaultedMappedRegistry<T> extends MappedRegistry<T> implements DefaultedRegistry<T> {}"),
			Map.entry("net.minecraft.core.registries.BuiltInRegistries", "package net.minecraft.core.registries; public class BuiltInRegistries {"
					+ "public static final net.minecraft.core.DefaultedRegistry<net.minecraft.world.item.Item> ITEM=new net.minecraft.core.DefaultedMappedRegistry<>();"
					+ "public static final net.minecraft.core.Registry<net.minecraft.world.effect.MobEffect> MOB_EFFECT=new net.minecraft.core.MappedRegistry<>();}"),
			Map.entry("net.minecraft.world.item.Item", "package net.minecraft.world.item; public class Item {public int fired; public void fire(){fired++;}}"),
			Map.entry("net.minecraft.world.effect.MobEffect", "package net.minecraft.world.effect; public class MobEffect {public int fired; public void fire(){fired++;}}"),
			Map.entry("yy.kiln.Fireable", "package yy.kiln; public interface Fireable {void fire();}"));

	private static final String IMPORTS = "package yy.kiln; import java.util.*; import net.minecraft.core.*;"
			+ "import net.minecraft.core.registries.BuiltInRegistries; import net.minecraft.world.item.Item;";
	/** A switch the walk reads that says nothing about any element; {@code sparks} is work that never sees one. */
	private static final String FIELDS = "public static boolean lit=true; public static int sparks;";

	// ---- a condition that does not read the element ------------------------------------------------------------------

	/**
	 * Loops: the callback is on every path from taking an element to the end of that iteration
	 * ({@code reachesIterationEnd} in {@code perElement}). The condition reads a static flag, never the element.
	 */
	@Test
	void aLoopWhoseCallbackOnlyAStaticFlagGuardsIsLeftAsWritten() throws Throwable {
		assertLeftAsWritten(walker("public static void fireAll(){" + body("""
				for(Item item:BuiltInRegistries.ITEM){ if(lit) ((Fireable)item).fire(); }""") + "}"));
		// Written by hand, the element in a local and the flag tested the other way round, skipping with continue.
		assertLeftAsWritten(walker("public static void fireAll(){" + body("""
				Iterator<Item> it=BuiltInRegistries.ITEM.iterator();
				while(it.hasNext()){ Item next=it.next(); if(!lit) continue; ((Fireable)next).fire(); }""") + "}"));
		// An index loop that reads every element, but calls back on it only while the flag holds.
		assertLeftAsWritten(walker("public static void fireAll(){" + body("""
				for(int i=0;i<BuiltInRegistries.ITEM.size();i++){ Item item=BuiltInRegistries.ITEM.byId(i); if(lit) ((Fireable)item).fire(); }""") + "}"));

		// Twins: the same flag read in the same place, deciding only work that never sees the element.
		assertCompletesTheLateElement(walker("public static void fireAll(){" + body("""
				for(Item item:BuiltInRegistries.ITEM){ if(lit) sparks++; ((Fireable)item).fire(); }""") + "}"));
		assertCompletesTheLateElement(walker("public static void fireAll(){" + body("""
				Iterator<Item> it=BuiltInRegistries.ITEM.iterator();
				while(it.hasNext()){ Item next=it.next(); if(!lit) sparks++; ((Fireable)next).fire(); }""") + "}"));
		assertCompletesTheLateElement(walker("public static void fireAll(){" + body("""
				for(int i=0;i<BuiltInRegistries.ITEM.size();i++){ Item item=BuiltInRegistries.ITEM.byId(i); if(lit) sparks++; ((Fireable)item).fire(); }""") + "}"));
	}

	/**
	 * Consumers: every normal return of the lambda body passes the callback ({@code consumerBody}). Whether the flag
	 * guards the call or returns before it, and whether it is a static field or a value the lambda captured.
	 */
	@Test
	void aConsumerThatCanReturnBeforeItsCallbackOnAStaticFlagIsLeftAsWritten() throws Throwable {
		assertLeftAsWritten(walker("public static void fireAll(){" + body("""
				BuiltInRegistries.ITEM.forEach(item->{ if(lit) ((Fireable)item).fire(); });""") + "}"));
		assertLeftAsWritten(walker("public static void fireAll(){" + body("""
				BuiltInRegistries.ITEM.forEach(item->{ if(lit) return; ((Fireable)item).fire(); });""") + "}"));
		assertLeftAsWritten(walker("public static void fireAll(){" + body("""
				boolean[] on={lit}; BuiltInRegistries.ITEM.stream().forEach(item->{ if(!on[0]) return; ((Fireable)item).fire(); });""") + "}"));

		// Twins: the flag beside the callback, or a return that comes only after it.
		assertCompletesTheLateElement(walker("public static void fireAll(){" + body("""
				BuiltInRegistries.ITEM.forEach(item->{ if(lit) sparks++; ((Fireable)item).fire(); });""") + "}"));
		assertCompletesTheLateElement(walker("public static void fireAll(){" + body("""
				BuiltInRegistries.ITEM.forEach(item->{ ((Fireable)item).fire(); if(lit) return; sparks++; });""") + "}"));
		assertCompletesTheLateElement(walker("public static void fireAll(){" + body("""
				boolean[] on={lit}; BuiltInRegistries.ITEM.stream().forEach(item->{ ((Fireable)item).fire(); if(!on[0]) return; sparks++; });""") + "}"));
	}

	/**
	 * Index loops: no step of the index is taken in an iteration that did not read the element. Here the condition is
	 * around the read itself, so the element never flows into it and the per-element check has nothing to see; a step
	 * past an unread index skips that element as surely as a skipped callback does.
	 */
	@Test
	void anIndexLoopThatCanStepPastAnElementItNeverReadIsLeftAsWritten() throws Throwable {
		for (String loop : List.of(
				"for(int i=0;i<BuiltInRegistries.ITEM.size();i++) if(lit) ((Fireable)BuiltInRegistries.ITEM.byId(i)).fire();",
				"for(int i=0;i<BuiltInRegistries.ITEM.size();i++){ if((i&1)!=0) continue; ((Fireable)BuiltInRegistries.ITEM.byId(i)).fire(); }",
				"int i=0; while(i<BuiltInRegistries.ITEM.size()){ if(lit){ Item item=BuiltInRegistries.ITEM.byId(i); ((Fireable)item).fire(); } i++; }"))
			assertLeftAsWritten(walker("public static void fireAll(){" + loop + "}"));

		// Twins: the same conditions deciding only work that never sees the element; every step reads one.
		assertCompletesTheLateElement(walker("public static void fireAll(){"
				+ "for(int i=0;i<BuiltInRegistries.ITEM.size();i++){ if(lit) sparks++; ((Fireable)BuiltInRegistries.ITEM.byId(i)).fire(); }}"));
		assertCompletesTheLateElement(walker("public static void fireAll(){"
				+ "int i=0; while(i<BuiltInRegistries.ITEM.size()){ Item item=BuiltInRegistries.ITEM.byId(i); if((i&1)!=0) sparks++; ((Fireable)item).fire(); i++; }}"));
	}

	// ---- a bound that is not the walked registry's size --------------------------------------------------------------

	/**
	 * Index loops: the bound is the walked registry's own {@code size()} on every path ({@code isSize}). A constant, a
	 * count the caller passed in, or another registry's size all stop somewhere unrelated to the registry's end.
	 */
	@Test
	void anIndexLoopBoundedByAnythingButTheWalkedRegistrysSizeIsLeftAsWritten() throws Throwable {
		for (String loop : List.of(
				"for(int i=0;i<16;i++) ((Fireable)BuiltInRegistries.ITEM.byId(i)).fire();",
				"for(int i=0;i<BuiltInRegistries.MOB_EFFECT.size();i++) ((Fireable)BuiltInRegistries.ITEM.byId(i)).fire();",
				// another registry's size read once into a local, the walked registry in a local, the comparison backwards
				"int n=BuiltInRegistries.MOB_EFFECT.size(); DefaultedRegistry<Item> items=BuiltInRegistries.ITEM;"
						+ "for(int i=0;n>i;i++){Item item=items.byId(i); ((Fireable)item).fire();}",
				// the right registry's size on one path only
				"int n=lit?BuiltInRegistries.ITEM.size():8; for(int i=0;i<n;i++) ((Fireable)BuiltInRegistries.ITEM.byId(i)).fire();"))
			assertLeftAsWritten(walker("public static void fireAll(){" + loop + "}"));
		assertLeftAsWritten(walker("public static void fireAll(int count){for(int i=0;i<count;i++) ((Fireable)BuiltInRegistries.ITEM.byId(i)).fire();}"));

		// Twins: the same loops bounded by the walked registry's own size, read in the test or once into a local.
		assertCompletesTheLateElement(walker("public static void fireAll(){"
				+ "for(int i=0;i<BuiltInRegistries.ITEM.size();i++) ((Fireable)BuiltInRegistries.ITEM.byId(i)).fire();}"));
		assertCompletesTheLateElement(walker("public static void fireAll(){int n=BuiltInRegistries.ITEM.size(); DefaultedRegistry<Item> items=BuiltInRegistries.ITEM;"
				+ "for(int i=0;n>i;i++){Item item=items.byId(i); ((Fireable)item).fire();}}"));
	}

	// ---- fixtures ------------------------------------------------------------------------------------------------

	private static String walker(String method) {
		return IMPORTS + "public class Kiln {" + FIELDS + method + "}";
	}

	private static String body(String text) {
		return text.replace('\n', ' ');
	}

	private void assertLeftAsWritten(String source) throws Exception {
		Map<String, byte[]> classes = compile(source);
		byte[] raw = classes.get(WALKER);
		assertSame(raw, InjectorExecution.transform(injector(classes), binary(WALKER), raw, EnvType.CLIENT), source);
	}

	/** Recognised, and after a run over one element, the one registered later gets the callback once; nothing else does. */
	private void assertCompletesTheLateElement(String source) throws Throwable {
		Map<String, byte[]> classes = compile(source);
		byte[] raw = classes.get(WALKER);
		byte[] edited = InjectorExecution.transform(injector(classes), binary(WALKER), raw, EnvType.CLIENT);
		assertNotSame(raw, edited, "the twin was not recognised: " + source);
		classes.put(WALKER, edited);
		ClassLoader loader = InjectorExecution.load(classes);
		assertEquals("", InjectorExecution.verify(edited, loader), source);
		Class<?> walker = loader.loadClass(binary(WALKER)), item = loader.loadClass(binary(ITEM));
		Object items = loader.loadClass("net.minecraft.core.registries.BuiltInRegistries").getField("ITEM").get(null);
		Object first = InjectorExecution.construct(item);
		InjectorExecution.invoke(items, "register", first);
		InjectorExecution.invokeStatic(walker, "fireAll");
		Object late = InjectorExecution.construct(item);
		InjectorExecution.invoke(items, "register", late);
		assertEquals(1, RegistryElementCallbacks.complete(items), source);
		assertEquals(List.of(1, 1), List.of(fired(first), fired(late)), source);
		assertEquals(0, RegistryElementCallbacks.complete(items), source);
	}

	private Map<String, byte[]> compile(String walker) throws Exception {
		Map<String, String> sources = new HashMap<>(PLATFORM);
		sources.put("yy.kiln.Kiln", walker);
		Map<String, byte[]> compiled = InjectorExecution.compile(root.resolve("k" + Math.abs((long) walker.hashCode())), sources);
		// The contract arrives on the elements the way a mixin adds it: after the walk was compiled against the platform.
		for (String element : List.of(ITEM, EFFECT)) {
			ClassNode node = new ClassNode();
			new ClassReader(compiled.get(element)).accept(node, 0);
			node.interfaces.add("yy/kiln/Fireable");
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

	private static int fired(Object element) throws ReflectiveOperationException {
		return element.getClass().getField("fired").getInt(element);
	}

	private static String binary(String internal) { return internal.replace('/', '.'); }
}
