/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;

/**
 * The boot-time scan of the installed mods reads their calls into uncalled merged-base methods by the census's own rules,
 * with shipped rows, synthetic classes of unrelated names and no staged jar: an override's super call of itself is no
 * call; an {@code @Invoker} binds only the method of its name and descriptor in the classes its {@code @Mixin} targets; a
 * static method is reached only by a static call naming its class or a subclass — and a mixin's call of its own class
 * is made in the class it targets.
 */
@ResourceLock("system-properties")
class MergedBaseGuestScanTest {
	private static final String LIVING = "net/minecraft/world/entity/LivingEntity";
	private static final String VEC3 = "Lnet/minecraft/world/phys/Vec3;";
	private static final String TRAVEL_IN_FLUID = "(" + VEC3 + ")V";
	private static final String GAME_MODE = "net/minecraft/server/level/ServerPlayerGameMode";
	private static final String REMOVE = "(Lnet/minecraft/core/BlockPos;Z)Z";
	private static final String LOADER = "net/minecraft/resources/RegistryDataLoader";
	private static final String LOAD = "(Lnet/minecraft/resources/RegistryDataLoader$LoaderFactory;Ljava/util/List;Ljava/util/List;"
			+ "Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";
	private static final String WIRE = "net/minecraft/world/level/block/RedStoneWireBlock";
	private static final String CONNECTS = "(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z";
	private static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
	private static final String INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

	@AfterEach
	void reset() {
		MergedBaseUncalledMethods.forgetGuests();
		MixinStubRebind.forget();
	}

	/** The row's owner as the merged game has it, reduced to the method: nothing in it calls the method. */
	private static ClassNode owner(String name, String method, String desc, int access) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.name = name;
		node.superName = "java/lang/Object";
		node.access = Opcodes.ACC_PUBLIC;
		MethodNode m = new MethodNode(access, method, desc, null, null);
		m.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
		m.instructions.add(new InsnNode(Opcodes.ATHROW));
		node.methods.add(m);
		return node;
	}

	private static String neverRuns(ClassNode owner, Ecosystem ecosystem) {
		return MergedBaseUncalledMethods.neverRuns(owner, owner.methods.getFirst(), ecosystem);
	}

	private static byte[] bytes(ClassNode node) {
		ClassWriter writer = new ClassWriter(0);
		node.accept(writer);
		return writer.toByteArray();
	}

	/** A class of {@code name} extending {@code superName} with one method whose body is {@code call} (operands loaded as nulls). */
	private static ClassNode caller(String name, String superName, String method, String desc, int opcode, String owner, String callName, String callDesc) {
		ClassNode node = new ClassNode();
		node.version = Opcodes.V21;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT;
		node.name = name;
		node.superName = superName;
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, method, desc, null, null);
		if (opcode != Opcodes.INVOKESTATIC) m.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		for (Type ignored : Type.getArgumentTypes(callDesc)) m.instructions.add(new InsnNode(ignored.getSize() == 2 ? Opcodes.LCONST_0 : ignored.getSort() == Type.OBJECT ? Opcodes.ACONST_NULL : Opcodes.ICONST_0));
		m.instructions.add(new MethodInsnNode(opcode, owner, callName, callDesc, opcode == Opcodes.INVOKEINTERFACE));
		Type returned = Type.getReturnType(callDesc);
		if (returned.getSize() > 0) m.instructions.add(new InsnNode(returned.getSize() == 2 ? Opcodes.POP2 : Opcodes.POP));
		m.instructions.add(new InsnNode(Opcodes.RETURN));
		m.maxStack = 8;
		m.maxLocals = 4;
		node.methods.add(m);
		return node;
	}

	private static void mixin(ClassNode node, String... targets) {
		AnnotationNode type = new AnnotationNode(MIXIN);
		List<Object> classes = new ArrayList<>();
		for (String target : targets) classes.add(Type.getObjectType(target));
		type.values = new ArrayList<>(List.of("value", classes));
		node.invisibleAnnotations = new ArrayList<>(List.of(type));
	}

	/** {@code @Mixin(target) interface Accessor { @Invoker(value?) <method><desc>; }} */
	private static byte[] invoker(String target, String method, String desc, String value) {
		ClassNode accessor = new ClassNode();
		accessor.version = Opcodes.V21;
		accessor.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		accessor.name = "org/quillmere/mixin/Accessor" + Integer.toHexString((target + method + desc).hashCode());
		accessor.superName = "java/lang/Object";
		mixin(accessor, target);
		MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, method, desc, null, null);
		AnnotationNode named = new AnnotationNode(INVOKER);
		if (value != null) named.values = new ArrayList<>(List.of("value", value));
		m.visibleAnnotations = new ArrayList<>(List.of(named));
		accessor.methods.add(m);
		return bytes(accessor);
	}

	@Test
	void anOverridesSuperCallOfItselfIsNoCallButACallOfItIs() {
		ClassNode living = owner(LIVING, "travelInFluid", TRAVEL_IN_FLUID, Opcodes.ACC_PROTECTED);
		assertNotNull(neverRuns(living, Ecosystem.FABRIC), "the shipped row: nothing in the merged game calls it");

		// A mod's entity overriding it and calling super: dead itself, as the census's own override is.
		MergedBaseUncalledMethods.noteGuest(bytes(caller("org/tidewrack/entity/Swimmer", LIVING, "travelInFluid", TRAVEL_IN_FLUID,
				Opcodes.INVOKESPECIAL, LIVING, "travelInFluid", TRAVEL_IN_FLUID)));
		assertNotNull(neverRuns(living, Ecosystem.FABRIC), "a super call of itself runs only when the override runs");
		assertFalse(MergedBaseUncalledMethods.calledByGuest("travelInFluid" + TRAVEL_IN_FLUID));

		// Control: a mod that calls it.
		MergedBaseUncalledMethods.noteGuest(bytes(caller("org/tidewrack/entity/Pusher", "java/lang/Object", "push", "()V",
				Opcodes.INVOKEVIRTUAL, LIVING, "travelInFluid", TRAVEL_IN_FLUID)));
		assertNull(neverRuns(living, Ecosystem.FABRIC), "a mod's call of the method makes it run");
	}

	@Test
	void anInvokerBindsOnlyItsOwnTargetsMethodOfItsNameAndDescriptor() {
		ClassNode mode = owner(GAME_MODE, "removeBlock", REMOVE, Opcodes.ACC_PRIVATE);
		ClassNode loader = owner(LOADER, "load", LOAD, Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC);
		assertNotNull(neverRuns(mode, Ecosystem.FORGE));
		assertNotNull(neverRuns(loader, Ecosystem.FABRIC));

		// Accessors of OTHER classes whose invokers spell the rows' names: they bind their own targets' methods.
		MergedBaseUncalledMethods.noteGuest(invoker("net/minecraft/world/level/block/piston/PistonBaseBlock", "invokeRemoveBlock", REMOVE, null));
		MergedBaseUncalledMethods.noteGuest(invoker("net/minecraft/server/level/ServerChunkCache", "quillmere$load", LOAD, "load"));
		assertNotNull(neverRuns(mode, Ecosystem.FORGE), "an invoker on PistonBaseBlock does not call ServerPlayerGameMode's method");
		assertNotNull(neverRuns(loader, Ecosystem.FABRIC), "an invoker on ServerChunkCache does not call RegistryDataLoader's method");

		// An invoker on the row's own class with another descriptor binds the other overload.
		MergedBaseUncalledMethods.noteGuest(invoker(GAME_MODE, "callRemoveBlock",
				"(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;ZLnet/minecraft/world/item/ItemStack;)Z", null));
		assertNotNull(neverRuns(mode, Ecosystem.FORGE), "another descriptor is another method");

		// Control: invokers of the rows' own classes, by derived name and by value.
		MergedBaseUncalledMethods.noteGuest(invoker(GAME_MODE, "callRemoveBlock", REMOVE, null));
		MergedBaseUncalledMethods.noteGuest(invoker(LOADER, "quillmere$load", LOAD, "load"));
		assertNull(neverRuns(mode, Ecosystem.FORGE), "an invoker of its own class calls it");
		assertNull(neverRuns(loader, Ecosystem.FABRIC), "an invoker naming it by value calls it");
	}

	@Test
	void aMixinsCallOfItsOwnClassIsMadeInTheClassItTargets() {
		ClassNode mode = owner(GAME_MODE, "removeBlock", REMOVE, Opcodes.ACC_PRIVATE);
		// A mixin of ServerPlayerGameMode calling its @Shadow private removeBlock: the call names the mixin class itself.
		ClassNode shadowing = caller("org/quillmere/mixin/GameModeMixin", "java/lang/Object", "quillmere$break", "()V",
				Opcodes.INVOKESPECIAL, "org/quillmere/mixin/GameModeMixin", "removeBlock", REMOVE);
		mixin(shadowing, GAME_MODE);
		MergedBaseUncalledMethods.noteGuest(bytes(shadowing));
		assertNull(neverRuns(mode, Ecosystem.FORGE), "Mixin makes that call in ServerPlayerGameMode");
	}

	@Test
	void aStaticMethodIsReachedOnlyByAStaticCallOfItsClassOrASubclass() {
		ClassNode wire = owner(WIRE, "shouldConnectTo", CONNECTS, Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
		assertNotNull(neverRuns(wire, Ecosystem.FABRIC), "the shipped row");
		Map<String, String> supers = Map.of("org/quillmere/block/Cable", WIRE, WIRE, "java/lang/Object",
				"org/quillmere/block/Wires", "java/lang/Object", "java/lang/Object", "");
		MergedBaseUncalledMethods.superclasses(name -> {
			String up = supers.get(name);
			return up == null || up.isEmpty() ? null : up;
		});

		// TripWireBlock's instance method of the same name and descriptor is another method…
		MergedBaseUncalledMethods.noteGuest(bytes(caller("org/quillmere/block/Hook", "java/lang/Object", "hook", "()V",
				Opcodes.INVOKEVIRTUAL, "net/minecraft/world/level/block/TripWireBlock", "shouldConnectTo", CONNECTS)));
		// …and so is a mod's own static helper of that name and descriptor.
		MergedBaseUncalledMethods.noteGuest(bytes(caller("org/quillmere/block/Router", "java/lang/Object", "route", "()V",
				Opcodes.INVOKESTATIC, "org/quillmere/block/Wires", "shouldConnectTo", CONNECTS)));
		assertNotNull(neverRuns(wire, Ecosystem.FABRIC), "neither reaches RedStoneWireBlock's static method");

		// Control: a static call through a subclass, and one naming the class.
		MergedBaseUncalledMethods.noteGuest(bytes(caller("org/quillmere/block/Cable", WIRE, "link", "()V",
				Opcodes.INVOKESTATIC, "org/quillmere/block/Cable", "shouldConnectTo", CONNECTS)));
		assertNull(neverRuns(wire, Ecosystem.FABRIC), "Cable extends RedStoneWireBlock and does not declare it again");
		MergedBaseUncalledMethods.forgetGuests();
		MergedBaseUncalledMethods.noteGuest(bytes(caller("org/quillmere/block/Direct", "java/lang/Object", "link", "()V",
				Opcodes.INVOKESTATIC, WIRE, "shouldConnectTo", CONNECTS)));
		assertNull(neverRuns(wire, Ecosystem.FABRIC));
	}

	@Test
	void aStaticCallTheKernelCannotFollowCountsAsReachingIt() {
		ClassNode wire = owner(WIRE, "shouldConnectTo", CONNECTS, Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC);
		// No way to read super classes (a test, or a class no jar has): the call may reach it, so it counts.
		MergedBaseUncalledMethods.noteGuest(bytes(caller("org/quillmere/block/Router", "java/lang/Object", "route", "()V",
				Opcodes.INVOKESTATIC, "org/quillmere/block/Wires", "shouldConnectTo", CONNECTS)));
		assertNull(neverRuns(wire, Ecosystem.FABRIC));
	}
}
