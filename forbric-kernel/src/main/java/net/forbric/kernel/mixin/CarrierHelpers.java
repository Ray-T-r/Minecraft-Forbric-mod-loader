/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.api.Ecosystem;
import net.forbric.kernel.util.ForbricLog;

/**
 * Calls a vanilla method made itself that the merged base makes in a helper the carrier added — the shipped table
 * {@code carrier-helpers.txt}, which CarrierHelperCensusTest re-derives from the staged jars and pins.
 *
 * <p>A row says: in {@code owner#method}, the reference jar of each listed ecosystem makes {@code member} exactly
 * once; the merged method no longer makes it, and the one place it is made instead is the carrier-added
 * {@code helper}, exactly once, reached from the method in the listed {@code shape}. MixinRetarget moves an
 * injector only along a row, for a mod of a listed ecosystem, and re-checks the row against the live bytes first.
 *
 * <p>{@code SPLIT}: the merged method is nothing but calls to same-shaped helpers the carrier added, each handed the
 * method's own arguments in place — NeoForge turned {@code Hud.extractPlayerHealth} into
 * {@code extractHealthLevel; extractArmorLevel; extractFoodLevel; extractAirLevel} so each can be a HUD layer.
 */
public final class CarrierHelpers {
	/** The shipped table; CarrierHelperCensusTest pins it to the staged artifacts. */
	static final String TABLE = "/net/forbric/kernel/mixin/carrier-helpers.txt";

	/** How the helper is reached from the method the mod named. */
	enum Shape { SPLIT }

	/**
	 * @param owner      the class (internal name)
	 * @param method     the method a mod written against the reference names, {@code name + descriptor}
	 * @param helper     the carrier-added method that makes the call now, {@code name + descriptor}
	 * @param member     the call or field access, as an {@code @At} target: {@code Lowner;name(desc)} or {@code Lowner;name:desc}
	 * @param shapes     how the helper relates to the method
	 * @param ecosystems whose own patched jar makes the call in the method — the mods compiled against that shape
	 */
	record Row(String owner, String method, String helper, String member, Set<Shape> shapes, Set<Ecosystem> ecosystems) {
		String line() {
			return owner + "#" + method + " -> " + helper + " : " + member + " | " + join(shapes) + " | " + join(ecosystems);
		}

		static Row parse(String line) {
			String[] columns = line.split(" \\| ");
			if (columns.length != 3) return null;
			int hash = columns[0].indexOf('#'), arrow = columns[0].indexOf(" -> "), colon = columns[0].indexOf(" : ");
			if (hash <= 0 || arrow < hash || colon < arrow) return null;
			try {
				Set<Shape> shapes = EnumSet.noneOf(Shape.class);
				for (String s : columns[1].trim().split(",")) shapes.add(Shape.valueOf(s.trim()));
				Set<Ecosystem> ecosystems = EnumSet.noneOf(Ecosystem.class);
				for (String e : columns[2].trim().split(",")) ecosystems.add(Ecosystem.valueOf(e.trim()));
				return new Row(columns[0].substring(0, hash), columns[0].substring(hash + 1, arrow),
						columns[0].substring(arrow + 4, colon), columns[0].substring(colon + 3).trim(),
						Set.copyOf(shapes), Set.copyOf(ecosystems));
			} catch (IllegalArgumentException unknown) {
				return null;
			}
		}

		private static String join(Set<? extends Enum<?>> values) {
			return String.join(",", values.stream().sorted().map(Enum::name).toList());
		}
	}

	private static volatile List<Row> rows;

	private CarrierHelpers() {
	}

	static List<Row> rows() {
		List<Row> loaded = rows;
		if (loaded != null) return loaded;
		List<Row> read = new ArrayList<>();
		try (InputStream in = CarrierHelpers.class.getResourceAsStream(TABLE)) {
			if (in != null) {
				for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
					if (line.isBlank() || line.startsWith("#")) continue;
					Row row = Row.parse(line.trim());
					if (row != null) read.add(row);
				}
			}
		} catch (IOException unreadable) {
			ForbricLog.warn("[Forbric/Mixin] could not read %s; no injector follows a call into a carrier's helper", TABLE);
		}
		rows = List.copyOf(read);
		return rows;
	}

	/**
	 * The one row for {@code anchor} (an {@code @At} target as the mod wrote it: owner and descriptor may be left out)
	 * in {@code owner#method} of that shape, whose ecosystems include the mod's; null when there is none, or when a
	 * loosely written anchor would match two.
	 */
	static Row find(String owner, String method, String anchor, Shape shape, Ecosystem ecosystem) {
		if (ecosystem == null) return null;
		MixinFit.Member want = MixinFit.parseMember(anchor);
		if (want == null) return null;
		Row found = null;
		for (Row row : rows()) {
			if (!row.owner().equals(owner) || !row.method().equals(method) || !row.shapes().contains(shape)
					|| !row.ecosystems().contains(ecosystem)) continue;
			MixinFit.Member have = MixinFit.parseMember(row.member());
			if (have == null || !matches(want, have)) continue;
			if (found != null) return null;    // "getX" with no owner or descriptor, two rows: which one is a guess
			found = row;
		}
		return found;
	}

	private static boolean matches(MixinFit.Member want, MixinFit.Member have) {
		return want.name().equals(have.name()) && (want.owner() == null || want.owner().equals(have.owner()))
				&& (want.desc() == null || want.desc().equals(have.desc()));
	}

	/** How many instructions of {@code method} make the call or field access {@code member} names (as {@link MixinFit#containsMember}). */
	static int occurrences(MethodNode method, String member) {
		MixinFit.Member want = MixinFit.parseMember(member);
		if (want == null || method.instructions == null) return -1;
		int count = 0;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn instanceof MethodInsnNode call && matches(want, new MixinFit.Member(call.owner, call.name, call.desc))) count++;
			else if (insn instanceof FieldInsnNode field && matches(want, new MixinFit.Member(field.owner, field.name, field.desc))) count++;
		}
		return count;
	}

	/** The {@code @At} spelling of an instruction's member, as the table writes it. */
	static String member(AbstractInsnNode insn) {
		if (insn instanceof MethodInsnNode call) return "L" + call.owner + ";" + call.name + call.desc;
		if (insn instanceof FieldInsnNode field) return "L" + field.owner + ";" + field.name + ":" + field.desc;
		return null;
	}

	/**
	 * The helper calls of {@code method} when it is a pure dispatcher, null otherwise: nothing but, one after another,
	 * {@code this} (when not static) and every parameter loaded in order, a call to a method of {@code owner} with the
	 * same descriptor and static-ness that has a body there, and at the end a {@code return}. The helpers therefore
	 * take exactly the arguments the method was given, which is what lets a handler that captures them move.
	 */
	static List<MethodInsnNode> dispatchedHelpers(ClassNode owner, MethodNode method) {
		if (method.instructions == null || !Type.getReturnType(method.desc).equals(Type.VOID_TYPE)) return null;
		boolean isStatic = (method.access & Opcodes.ACC_STATIC) != 0;
		Type[] params = Type.getArgumentTypes(method.desc);
		List<AbstractInsnNode> real = new ArrayList<>();
		for (AbstractInsnNode insn : method.instructions) if (insn.getOpcode() >= 0) real.add(insn);
		List<MethodInsnNode> calls = new ArrayList<>();
		int i = 0;
		while (i < real.size()) {
			if (real.get(i).getOpcode() == Opcodes.RETURN) return i == real.size() - 1 && !calls.isEmpty() ? calls : null;
			int slot = 0;
			if (!isStatic) {
				if (!(real.get(i) instanceof VarInsnNode self) || self.getOpcode() != Opcodes.ALOAD || self.var != 0) return null;
				slot = 1;
				i++;
			}
			for (Type param : params) {
				if (i >= real.size() || !(real.get(i) instanceof VarInsnNode load) || load.var != slot
						|| load.getOpcode() != param.getOpcode(Opcodes.ILOAD)) return null;
				slot += param.getSize();
				i++;
			}
			if (i >= real.size() || !(real.get(i) instanceof MethodInsnNode call) || !call.owner.equals(owner.name)
					|| !call.desc.equals(method.desc) || (call.getOpcode() == Opcodes.INVOKESTATIC) != isStatic) return null;
			MethodNode helper = declared(owner, call.name, call.desc);
			if (helper == null || helper == method || helper.instructions == null || helper.instructions.size() == 0) return null;
			calls.add(call);
			i++;
		}
		return null;
	}

	static MethodNode declared(ClassNode owner, String name, String desc) {
		if (owner == null || owner.methods == null) return null;
		for (MethodNode m : owner.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
		return null;
	}
}
