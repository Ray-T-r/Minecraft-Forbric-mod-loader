/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.ArrayList;
import java.util.List;

import net.forbric.kernel.util.ForbricLog;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * A Fabric mod's fuels go into the fuel values the merged game builds.
 *
 * <p>fabric-content-registries fires {@code FuelValueEvents.BUILD}/{@code EXCLUSIONS} from a wrap in vanilla's
 * {@code FuelValues.vanillaBurnTimes}; the merged server (and a client on a NeoForge connection) builds its fuels in
 * NeoForge's {@code DataMapHooks.populateFuelValues} instead and never calls it, so a Fabric mod's fuel could not go in a
 * furnace. Right before that method's one {@code Builder.build()}, the builder goes through {@code KernelFabricFuel.apply}
 * with the method's own registries and feature flags. Applied only when the method builds one builder from its two
 * parameters and builds it once. {@code -Dforbric.fabricFuel=off} leaves it as shipped.
 *
 * <p>The other half is the mixins on {@code vanillaBurnTimes}' RETURN, torrential's Angling Table among them: right
 * after that {@code build()}, the table goes through {@code KernelFabricFuel.throughVanillaReturnHooks}, which calls
 * {@code vanillaBurnTimes(Provider, FeatureFlagSet)} — the call native Fabric and MinecraftForge servers make, which
 * forwards to {@code (Provider, FeatureFlagSet, int)} — with it pending; and the merge-added body
 * {@code vanillaBurnTimes(Builder, int)} starts by returning the pending table when there is one. The short-circuit
 * sits in that body, never in the forwarding overload: MixinStubRebind recognises the overload as the carrier's stub
 * only while it is a pure forward, and moves Fabric injectors into the body by that recognition. It jumps to the
 * body's single {@code areturn} rather than adding a return of its own, so a RETURN or TAIL injector there — whatever
 * its ordinal — runs on both paths. {@code -Dforbric.fabricFuel.returnHooks=off} leaves both methods without it.
 *
 * <p>The third half is the client's second table. A client on a NeoForge connection builds vanilla's table — where the
 * guest's own wrap fires the fuel events and every return hook runs — and right after it NeoForge's from the synced data
 * map, which it keeps; going through {@code populateFuelValues} there fired every listener and every return hook a
 * second time per native build. So the native build reports what it did and the data-map table built from the same
 * registries gets that instead ({@code KernelFabricFuel}'s class comment has the rule): the native point in vanilla's
 * body — its {@code Builder.remove(TagKey)} right before {@code build()}, the call fabric-content-registries wraps — is
 * bracketed by {@code openNativePoint}/{@code closeNativePoint}, the body's built table goes through
 * {@code nativeBuilt}, every public mutator of {@code FuelValues.Builder} reports its call ({@code recordCall}) and its
 * registries constructor the registries ({@code builderMade}); after weaving, the two-argument overload reports entering
 * and the table it returns ({@link #afterMixin}). All of it is keyed on the platform classes' own shapes, not on any
 * mod. {@code -Dforbric.fabricFuel.carry=off} leaves vanilla's body, the builder and the overload without it, and the
 * data-map table is handed everything again.
 */
public final class FabricFuelValuesInjector implements ClassTransformer {
	public static final String PROPERTY = "forbric.fabricFuel";
	/** Mirrors {@code KernelFabricFuel.RETURN_HOOKS}, which the game side reads; the boot side cannot name it. */
	public static final String RETURN_HOOKS_PROPERTY = "forbric.fabricFuel.returnHooks";
	/** Mirrors {@code KernelFabricFuel.CARRY}. */
	public static final String CARRY_PROPERTY = "forbric.fabricFuel.carry";
	static final String BUILDER_CLASS = "net.minecraft.world.level.block.entity.FuelValues$Builder";
	static final String TAG_KEY = "net/minecraft/tags/TagKey";
	static final String PROVIDER = "net/minecraft/core/HolderLookup$Provider";
	/** The two-argument overload native clients and servers call; it forwards to the three-argument one. */
	static final String OUTER_DESC = "(L" + PROVIDER + ";Lnet/minecraft/world/flag/FeatureFlagSet;)L"
			+ "net/minecraft/world/level/block/entity/FuelValues;";
	static final String HOOKS = "net.neoforged.neoforge.common.DataMapHooks";
	static final String FUEL_VALUES_CLASS = "net.minecraft.world.level.block.entity.FuelValues";
	static final String FUEL_VALUES = "net/minecraft/world/level/block/entity/FuelValues";
	static final String BUILDER = "net/minecraft/world/level/block/entity/FuelValues$Builder";
	static final String DESC = "(Lnet/minecraft/core/RegistryAccess;Lnet/minecraft/world/flag/FeatureFlagSet;)Lnet/minecraft/world/level/block/entity/FuelValues;";
	/** The merge-added body the forwarding {@code vanillaBurnTimes(Provider, FeatureFlagSet, int)} calls. */
	static final String BODY_DESC = "(L" + BUILDER + ";I)L" + FUEL_VALUES + ";";
	static final String KERNEL_FUEL = "net/forbric/kernel/runtime/KernelFabricFuel";
	static final String THROUGH_DESC = "(L" + FUEL_VALUES + ";Lnet/minecraft/core/HolderLookup$Provider;"
			+ "Lnet/minecraft/world/flag/FeatureFlagSet;)L" + FUEL_VALUES + ";";

	/** What {@link #repair} inserted into populateFuelValues. */
	static final int APPLIED = 1, RETURN_HOOKED = 2;

	static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	static boolean returnHooksEnabled() {
		return enabled() && !"off".equalsIgnoreCase(System.getProperty(RETURN_HOOKS_PROPERTY, "on"));
	}

	static boolean carryEnabled() {
		return enabled() && !"off".equalsIgnoreCase(System.getProperty(CARRY_PROPERTY, "on"));
	}

	@Override public String name() { return "forbric-fabric-fuel"; }

	@Override public AnchorSet anchors() {
		if (!enabled()) return AnchorSet.scanned("Fabric fuels left out of NeoForge's fuel values with -D" + PROPERTY + "=off");
		List<AnchorSet.Anchor> anchors = new ArrayList<>(List.of(new AnchorSet.Anchor(HOOKS, AnchorSet.Severity.REQUIRED,
				"a Fabric mod's fuels cannot go in a furnace")));
		if (returnHooksEnabled() || carryEnabled()) {
			anchors.add(new AnchorSet.Anchor(FUEL_VALUES_CLASS, AnchorSet.Severity.REQUIRED,
					"a mod's mixin on vanillaBurnTimes' return (torrential's Angling Table fuel) never reaches the fuel "
							+ "values the merged server uses, or runs twice per native build on a client"));
		}
		if (carryEnabled()) {
			anchors.add(new AnchorSet.Anchor(BUILDER_CLASS, AnchorSet.Severity.REQUIRED,
					"a client's data-map fuel table fires a Fabric mod's fuel events and return hooks a second time per "
							+ "native build"));
		}
		return AnchorSet.of(anchors.toArray(new AnchorSet.Anchor[0]));
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!enabled() || bytes == null || bytes.length == 0) return bytes;
		if (FUEL_VALUES_CLASS.equals(className)) return bracketTheNativePoint(shortCircuitTheBody(bytes));
		if (BUILDER_CLASS.equals(className)) return reportTheBuildersCalls(bytes);
		if (!HOOKS.equals(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		int inserted = repair(node);
		if (inserted == 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		if ((inserted & APPLIED) != 0) {
			ForbricLog.info("[Forbric/Fuel] DataMapHooks.populateFuelValues runs fabric-content-registries' fuel events before it "
					+ "builds — the merged game builds its fuels there and never reached Fabric's hook in vanillaBurnTimes");
		}
		if ((inserted & RETURN_HOOKED) != 0) {
			ForbricLog.info("[Forbric/Fuel] DataMapHooks.populateFuelValues hands the table it built through "
					+ "FuelValues.vanillaBurnTimes' return — a mod's mixin there (torrential's Angling Table) now reaches "
					+ "the fuels the merged server uses");
		}
		return writer.toByteArray();
	}

	/** What was inserted into populateFuelValues, as {@link #APPLIED} / {@link #RETURN_HOOKED} bits; 0 for nothing. */
	static int repair(ClassNode hooks) {
		for (MethodNode method : hooks.methods) {
			if (!method.name.equals("populateFuelValues") || !method.desc.equals(DESC) || (method.access & Opcodes.ACC_STATIC) == 0) continue;
			MethodInsnNode build = null;
			int builds = 0, news = 0;
			boolean applied = false, hooked = false;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && type.desc.equals(BUILDER)) news++;
				if (insn instanceof MethodInsnNode call) {
					if (call.owner.equals(KERNEL_FUEL) && call.name.equals("apply")) applied = true;
					if (call.owner.equals(KERNEL_FUEL) && call.name.equals("throughVanillaReturnHooks")) hooked = true;
					if (call.owner.equals(BUILDER) && call.name.equals("build")) { build = call; builds++; }
				}
			}
			if (news != 1 || builds != 1) return 0;
			int inserted = 0;
			if (!applied) {
				InsnList apply = new InsnList();
				apply.add(new VarInsnNode(Opcodes.ALOAD, 0));
				apply.add(new VarInsnNode(Opcodes.ALOAD, 1));
				apply.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "apply",
						"(L" + BUILDER + ";Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;)L" + BUILDER + ";", false));
				method.instructions.insertBefore(build, apply);
				inserted |= APPLIED;
			}
			if (!hooked && returnHooksEnabled()) {
				// [table] -> [table, registries, features] -> [table'] : the built table, then what the hooks make of it.
				InsnList through = new InsnList();
				through.add(new VarInsnNode(Opcodes.ALOAD, 0));
				through.add(new VarInsnNode(Opcodes.ALOAD, 1));
				through.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "throughVanillaReturnHooks", THROUGH_DESC, false));
				method.instructions.insert(build, through);
				inserted |= RETURN_HOOKED;
			}
			return inserted;
		}
		return 0;
	}

	/**
	 * Puts {@code KernelFabricFuel.takePending()} at the head of {@code vanillaBurnTimes(Builder, int)}: a pending table
	 * goes straight to the body's own {@code areturn}, everything else runs the body as shipped.
	 *
	 * <p>Only while the forwarding overload still calls this body, and the body still ends in its one {@code areturn}
	 * after building its builder; anything else is left alone, and the anchor audit names it. The frame at the join is
	 * written expanded, so the class is read with its frames expanded too.
	 */
	static byte[] shortCircuitTheBody(byte[] bytes) {
		if (!returnHooksEnabled()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.EXPAND_FRAMES);
		MethodNode body = concreteEntryBody(node);
		boolean fromEntry = body != null;
		boolean forwarded = false;
		for (MethodNode method : node.methods) {
			if (body == null && method.name.equals("vanillaBurnTimes") && method.desc.equals(BODY_DESC)
					&& (method.access & Opcodes.ACC_STATIC) != 0) body = method;
			if (method.name.equals("vanillaBurnTimes") && !method.desc.equals(BODY_DESC)) {
				for (AbstractInsnNode insn : method.instructions) {
					if (insn instanceof MethodInsnNode call && call.owner.equals(FUEL_VALUES)
							&& call.name.equals("vanillaBurnTimes") && call.desc.equals(BODY_DESC)) forwarded = true;
				}
			}
		}
		if (body == null || !forwarded && !fromEntry) return bytes;
		AbstractInsnNode exit = null;
		for (AbstractInsnNode insn : body.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(KERNEL_FUEL) && call.name.equals("takePending")) return bytes;   // already in
			if (insn.getOpcode() == Opcodes.ARETURN) {
				if (exit != null) return bytes;
				exit = insn;
			}
			if (insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN && insn.getOpcode() != Opcodes.ARETURN) return bytes;
		}
		AbstractInsnNode beforeExit = exit == null ? null : realPrevious(exit);
		if (beforeExit instanceof MethodInsnNode reported && reported.owner.equals(KERNEL_FUEL) && reported.name.equals("nativeBuilt")) {
			beforeExit = realPrevious(reported);   // the carry's report sits between the build and the return
		}
		if (!(beforeExit instanceof MethodInsnNode built) || !built.owner.equals(BUILDER) || !built.name.equals("build")) return bytes;

		LabelNode join = new LabelNode();
		InsnList head = new InsnList();
		head.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "takePending", "()L" + FUEL_VALUES + ";", false));
		head.add(new InsnNode(Opcodes.DUP));
		head.add(new JumpInsnNode(Opcodes.IFNONNULL, join));
		head.add(new InsnNode(Opcodes.POP));
		body.instructions.insert(head);
		InsnList tail = new InsnList();
		tail.add(join);
		java.util.List<Object> locals = new java.util.ArrayList<>();
		for (org.objectweb.asm.Type argument : org.objectweb.asm.Type.getArgumentTypes(body.desc)) {
			locals.add(switch (argument.getSort()) {
				case org.objectweb.asm.Type.FLOAT -> Opcodes.FLOAT;
				case org.objectweb.asm.Type.LONG -> Opcodes.LONG;
				case org.objectweb.asm.Type.DOUBLE -> Opcodes.DOUBLE;
				case org.objectweb.asm.Type.OBJECT -> argument.getInternalName();
				case org.objectweb.asm.Type.ARRAY -> argument.getDescriptor();
				default -> Opcodes.INTEGER;
			});
		}
		tail.add(new FrameNode(Opcodes.F_NEW, locals.size(), locals.toArray(), 1, new Object[] { FUEL_VALUES }));
		body.instructions.insertBefore(exit, tail);

		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fuel] FuelValues.%s%s returns the fuel table populateFuelValues "
				+ "hands it instead of rebuilding vanilla's, so the return hooks of both vanillaBurnTimes run on the table "
				+ "the merged server uses", body.name, body.desc);
		return writer.toByteArray();
	}

	/**
	 * Brackets the native point in vanilla's body: {@code openNativePoint(builder, argument, member)} right before its one
	 * {@code Builder.remove(TagKey)} that is followed straight by {@code build()}, {@code closeNativePoint} on what that
	 * call returns, and {@code nativeBuilt} on what {@code build()} returns — before the body's return, so before any
	 * return hook Mixin puts there, and before the short-circuit's join, so a pending table never goes through it.
	 *
	 * <p>The remove call itself stays exactly as shipped, so a guest's {@code @At(INVOKE)} on it — the wrap that fires the
	 * fuel events — matches it as before; the bracket only duplicates its receiver and argument ahead of it. Every such
	 * site is bracketed: where a native facade was restored ({@code NativeConstructorFacadeRestorer}) the class holds
	 * vanilla's body twice — the facade, where the guests bind, and the merge-added closure — and which of them a guest's
	 * wrap lands in is Mixin's business; each window lives for one call of the method it is in.
	 */
	static byte[] bracketTheNativePoint(byte[] bytes) {
		if (!carryEnabled()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		String removeDesc = "(L" + TAG_KEY + ";)L" + BUILDER + ";";
		List<MethodNode> sites = new ArrayList<>();
		List<MethodInsnNode[]> calls = new ArrayList<>();
		for (MethodNode method : node.methods) {
			if ((method.access & Opcodes.ACC_STATIC) == 0) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(KERNEL_FUEL) && call.name.equals("openNativePoint")) return bytes;
				if (!(insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEVIRTUAL || !call.owner.equals(BUILDER)
						|| !call.name.equals("remove") || !call.desc.equals(removeDesc)) continue;
				AbstractInsnNode next = realNext(call);
				if (next instanceof MethodInsnNode built && built.getOpcode() == Opcodes.INVOKEVIRTUAL && built.owner.equals(BUILDER)
						&& built.name.equals("build") && built.desc.equals("()L" + FUEL_VALUES + ";")) {
					sites.add(method);
					calls.add(new MethodInsnNode[] { call, built });
				}
			}
		}
		if (sites.isEmpty()) return bytes;
		List<String> where = new ArrayList<>();
		for (int i = 0; i < sites.size(); i++) {
			MethodNode site = sites.get(i);
			MethodInsnNode remove = calls.get(i)[0], build = calls.get(i)[1];
			InsnList open = new InsnList();
			open.add(new InsnNode(Opcodes.DUP2));
			open.add(new org.objectweb.asm.tree.LdcInsnNode(remove.name + remove.desc));
			open.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "openNativePoint",
					"(L" + BUILDER + ";Ljava/lang/Object;Ljava/lang/String;)V", false));
			site.instructions.insertBefore(remove, open);
			site.instructions.insert(remove, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "closeNativePoint",
					"(L" + BUILDER + ";)L" + BUILDER + ";", false));
			site.instructions.insert(build, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "nativeBuilt",
					"(L" + FUEL_VALUES + ";)L" + FUEL_VALUES + ";", false));
			where.add(site.name + site.desc);
		}
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		ForbricLog.info("[Forbric/Fuel] FuelValues reports the call at its native point (Builder.remove before build()) and the "
				+ "table it builds in %s, so a client's data-map fuel table gets that build's fuel events and return hooks instead "
				+ "of a second run", where);
		return writer.toByteArray();
	}

	/**
	 * Every public mutator of {@code FuelValues.Builder} — an instance method returning the builder — reports its call
	 * ({@code recordCall(this, name + descriptor, arguments)}) at its head, and the constructor that takes the registries
	 * reports them ({@code builderMade(this, registries)}) where it returns.
	 */
	static byte[] reportTheBuildersCalls(byte[] bytes) {
		if (!carryEnabled()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (!node.name.equals(BUILDER)) return bytes;
		int mutators = 0, constructors = 0;
		for (MethodNode method : node.methods) {
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && call.owner.equals(KERNEL_FUEL)) return bytes;   // already in
			}
		}
		for (MethodNode method : node.methods) {
			if (method.name.equals("<init>") && method.desc.startsWith("(L" + PROVIDER + ";")) {
				for (AbstractInsnNode insn : method.instructions.toArray()) {
					if (insn.getOpcode() != Opcodes.RETURN) continue;
					InsnList made = new InsnList();
					made.add(new VarInsnNode(Opcodes.ALOAD, 0));
					made.add(new VarInsnNode(Opcodes.ALOAD, 1));
					made.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "builderMade", "(L" + BUILDER + ";Ljava/lang/Object;)V", false));
					method.instructions.insertBefore(insn, made);
				}
				constructors++;
				continue;
			}
			if ((method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC | Opcodes.ACC_BRIDGE)) != Opcodes.ACC_PUBLIC
					|| method.name.startsWith("<") || !org.objectweb.asm.Type.getReturnType(method.desc).getDescriptor().equals("L" + BUILDER + ";")) continue;
			org.objectweb.asm.Type[] arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);
			InsnList report = new InsnList();
			report.add(new VarInsnNode(Opcodes.ALOAD, 0));
			report.add(new org.objectweb.asm.tree.LdcInsnNode(method.name + method.desc));
			report.add(new org.objectweb.asm.tree.LdcInsnNode(arguments.length));
			report.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
			int slot = 1;
			for (int i = 0; i < arguments.length; i++) {
				report.add(new InsnNode(Opcodes.DUP));
				report.add(new org.objectweb.asm.tree.LdcInsnNode(i));
				report.add(new VarInsnNode(arguments[i].getOpcode(Opcodes.ILOAD), slot));
				box(report, arguments[i]);
				report.add(new InsnNode(Opcodes.AASTORE));
				slot += arguments[i].getSize();
			}
			report.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "recordCall",
					"(L" + BUILDER + ";Ljava/lang/String;[Ljava/lang/Object;)V", false));
			method.instructions.insert(report);
			mutators++;
		}
		if (constructors == 0 || mutators == 0) return bytes;
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * After weaving: the two-argument {@code vanillaBurnTimes} — what native clients and servers call — reports entering
	 * at its head and the table it hands its caller right before each of its returns, so after every return hook Mixin put
	 * in any of the three overloads. Called on the woven bytes of every class; edits only that method of
	 * {@code FuelValues}.
	 */
	public static byte[] afterMixin(String internalName, byte[] bytes) {
		if (bytes == null || !FUEL_VALUES.equals(internalName) || !carryEnabled()) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		MethodNode outer = node.methods.stream().filter(m -> m.name.equals("vanillaBurnTimes") && m.desc.equals(OUTER_DESC)
				&& (m.access & Opcodes.ACC_STATIC) != 0).findFirst().orElse(null);
		if (outer == null) return bytes;
		for (AbstractInsnNode insn : outer.instructions) {
			if (insn instanceof MethodInsnNode call && call.owner.equals(KERNEL_FUEL) && call.name.equals("outerEntered")) return bytes;
		}
		int returns = 0;
		for (AbstractInsnNode insn : outer.instructions.toArray()) {
			if (insn.getOpcode() != Opcodes.ARETURN) continue;
			outer.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "outerReturned",
					"(L" + FUEL_VALUES + ";)L" + FUEL_VALUES + ";", false));
			returns++;
		}
		if (returns == 0) return bytes;
		outer.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, KERNEL_FUEL, "outerEntered", "()V", false));
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	private static void box(InsnList list, org.objectweb.asm.Type type) {
		String owner = switch (type.getSort()) {
			case org.objectweb.asm.Type.BOOLEAN -> "java/lang/Boolean";
			case org.objectweb.asm.Type.BYTE -> "java/lang/Byte";
			case org.objectweb.asm.Type.CHAR -> "java/lang/Character";
			case org.objectweb.asm.Type.SHORT -> "java/lang/Short";
			case org.objectweb.asm.Type.INT -> "java/lang/Integer";
			case org.objectweb.asm.Type.FLOAT -> "java/lang/Float";
			case org.objectweb.asm.Type.LONG -> "java/lang/Long";
			case org.objectweb.asm.Type.DOUBLE -> "java/lang/Double";
			default -> null;
		};
		if (owner != null) list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, "valueOf", "(" + type.getDescriptor() + ")L" + owner + ";", false));
	}

	private static AbstractInsnNode realNext(AbstractInsnNode insn) {
		AbstractInsnNode next = insn.getNext();
		while (next != null && next.getOpcode() < 0) next = next.getNext();
		return next;
	}

	/** Follow the actual native entry through its own unambiguous forwarding chain; a restored facade may
	 * now own the concrete build rather than the constructor closure it used to call. */
	private static MethodNode concreteEntryBody(ClassNode owner) {
		String entry = "(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/world/flag/FeatureFlagSet;)L" + FUEL_VALUES + ";";
		MethodNode method = owner.methods.stream().filter(m -> m.name.equals("vanillaBurnTimes") && m.desc.equals(entry)
				&& (m.access & Opcodes.ACC_STATIC) != 0).findFirst().orElse(null);
		java.util.Set<MethodNode> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		while (method != null && seen.add(method)) {
			int builds = 0, returns = 0;
			java.util.List<MethodInsnNode> delegates = new java.util.ArrayList<>();
			for (AbstractInsnNode instruction : method.instructions) {
				if (instruction.getOpcode() == Opcodes.ARETURN) returns++;
				if (instruction instanceof MethodInsnNode call) {
					if (call.owner.equals(BUILDER) && call.name.equals("build") && call.desc.equals("()L" + FUEL_VALUES + ";")) builds++;
					if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals(owner.name) && call.name.equals(method.name)
							&& org.objectweb.asm.Type.getReturnType(call.desc).equals(org.objectweb.asm.Type.getObjectType(owner.name))) delegates.add(call);
				}
			}
			if (builds == 1 && returns == 1) return method;
			if (builds != 0 || delegates.size() != 1) return null;
			MethodInsnNode edge = delegates.getFirst();
			method = owner.methods.stream().filter(m -> m.name.equals(edge.name) && m.desc.equals(edge.desc)
					&& (m.access & Opcodes.ACC_STATIC) != 0).findFirst().orElse(null);
		}
		return null;
	}

	private static AbstractInsnNode realPrevious(AbstractInsnNode insn) {
		AbstractInsnNode previous = insn.getPrevious();
		while (previous != null && previous.getOpcode() < 0) previous = previous.getPrevious();
		return previous;
	}
}
