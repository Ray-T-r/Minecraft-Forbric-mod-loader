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

package net.forbric.kernel.boot;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * The events a guest listens for in code, which a scan of class-level {@code @EventBusSubscriber} classes cannot see.
 *
 * <h2>Why the annotation scan was not enough</h2>
 *
 * <p>{@link KernelEventSubscribers} reads which events a mod waits for from its {@code @EventBusSubscriber} classes, and
 * {@link ForgeBusSubscriptions} adds what each MinecraftForge mod's own bus group has seen. MinecraftForge 26.2's EventBus 7
 * keeps every game event on a static bus of its own, shared by every mod: {@code RenderNameTagEvent.BUS.addListener(this::on)}
 * registers a listener that neither of them sees — not an annotation, and not on the mod's own group. The local corpus has
 * real mods doing exactly that for events the merged game never posts (a voice-chat mod's name-tag listener; a mod listening
 * for item tosses, client sounds and player rendering), and the audit said nothing about either.
 *
 * <p>So the guest's bytecode is read for the shapes that register a listener in code:
 * <ul>
 *   <li>an {@code addListener} call on a bus whose receiver is an event's static {@code BUS} field (traced through locals),
 *       or what that event's static {@code getBus(...)} returns — the event is that class;</li>
 *   <li>an {@code addListener} call on either family's bus whose receiver cannot be traced, but whose listener is a lambda or
 *       a method reference: its instantiated type's parameter is the event;</li>
 *   <li>a class handed to a bus's {@code register} as a class literal, or registering itself: its {@code @SubscribeEvent}
 *       methods are listeners, class-level {@code @EventBusSubscriber} or not.</li>
 * </ul>
 * A shape this cannot trace (a bus kept in a field and read elsewhere, a listener built by a factory method) is not seen;
 * what is seen is a registration the guest's own code makes.
 */
final class GuestListeners {

	/**
	 * MinecraftForge's EventBus 7 bus types live here; NeoForge's bus is {@code IEventBus}; and MinecraftForge's own game bus
	 * field, {@code MinecraftForge.EVENT_BUS}, is declared as its {@code EventBusMigrationHelper}, which takes the same
	 * {@code register} and {@code addListener} calls for mods still written against the old bus.
	 */
	private static final String FORGE_BUSES = "net/minecraftforge/eventbus/api/bus/";
	private static final String NEO_BUS = "net/neoforged/bus/api/IEventBus";
	private static final String FORGE_GAME_BUS = "net/minecraftforge/common/EventBusMigrationHelper";
	private static final String ONLY_IN_SUFFIX = "/api/distmarker/OnlyIn;";

	private GuestListeners() {
	}

	/** What one class's code registers. */
	record Found(Set<String> events, Set<String> registeredClasses, boolean clientOnly) {
	}

	/**
	 * Whether {@code reader}'s constant pool names a bus type of either family: every shape above calls a method on one, so
	 * a class that names none is not parsed.
	 */
	static boolean namesABus(ClassReader reader) {
		char[] buffer = new char[reader.getMaxStringLength()];
		for (int item = 1; item < reader.getItemCount(); item++) {
			int offset = reader.getItem(item);
			if (offset == 0 || reader.readByte(offset - 1) != CONSTANT_CLASS) continue;
			String name = reader.readUTF8(offset, buffer);
			if (name != null && isBus(name)) return true;
		}
		return false;
	}

	/** The constant pool tag of a CONSTANT_Class entry (JVMS 4.4.1). */
	private static final int CONSTANT_CLASS = 7;

	/** What a bus call's argument is, when it is one of its method's parameters: resolved at the method's callers. */
	private enum Role { RECEIVER, LISTENER, REGISTERED }

	/** A parameter of a method in this class that reaches a bus call in the role given. */
	private record Need(String method, int parameter, Role role) {
	}

	/**
	 * The listeners {@code bytes}' methods register in code, and the classes they hand a bus to register. An argument of
	 * the bus call that is the method's own parameter (a helper such as {@code register(EventHandler handler)}) is followed
	 * one level, to the calls of that method in the same class.
	 */
	static Found scan(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
		Set<String> events = new TreeSet<>(), registered = new TreeSet<>();
		java.util.List<Need> needs = new java.util.ArrayList<>();
		for (MethodNode m : node.methods) {
			if (m.instructions == null || !callsABus(m)) continue;
			Frame<SourceValue>[] frames = frames(node.name, m);
			if (frames == null) continue;
			for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || !isBus(call.owner)) continue;
				Frame<SourceValue> frame = frames[m.instructions.indexOf(insn)];
				if (frame == null) continue;
				Type[] args = Type.getArgumentTypes(call.desc);
				if (call.name.equals("addListener")) {
					if (frame.getStackSize() < args.length + 1) continue;
					AbstractInsnNode receiver = origin(m, frames, frame.getStack(frame.getStackSize() - args.length - 1), new HashSet<>());
					AbstractInsnNode listener = args.length == 0 ? null
							: origin(m, frames, frame.getStack(frame.getStackSize() - 1), new HashSet<>());
					String event = eventOfReceiver(receiver);
					if (event == null) event = eventOfListener(listener);
					if (event != null) {
						events.add(event);
						continue;
					}
					int asReceiver = parameter(m, frames, receiver), asListener = parameter(m, frames, listener);
					if (asReceiver >= 0) needs.add(new Need(m.name + m.desc, asReceiver, Role.RECEIVER));
					if (asListener >= 0) needs.add(new Need(m.name + m.desc, asListener, Role.LISTENER));
				} else if (call.name.equals("register")) {
					for (int i = 0; i < args.length; i++) {
						AbstractInsnNode arg = origin(m, frames, frame.getStack(frame.getStackSize() - args.length + i), new HashSet<>());
						String type = registeredType(m, node.name, arg);
						if (type != null) registered.add(type);
						else if (parameter(m, frames, arg) >= 0) needs.add(new Need(m.name + m.desc, parameter(m, frames, arg), Role.REGISTERED));
					}
				}
			}
		}
		if (!needs.isEmpty()) resolveAtCallers(node, needs, events, registered);
		return new Found(events, registered, clientOnly(node.visibleAnnotations) || clientOnly(node.invisibleAnnotations));
	}

	/** Each need resolved at every call in {@code node} of the method it names: what that call hands the parameter. */
	private static void resolveAtCallers(ClassNode node, java.util.List<Need> needs, Set<String> events, Set<String> registered) {
		for (MethodNode caller : node.methods) {
			if (caller.instructions == null) continue;
			Frame<SourceValue>[] frames = null;
			for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof MethodInsnNode call) || !call.owner.equals(node.name)) continue;
				for (Need need : needs) {
					if (!need.method().equals(call.name + call.desc)) continue;
					if (frames == null) frames = frames(node.name, caller);
					if (frames == null) return;
					Frame<SourceValue> frame = frames[caller.instructions.indexOf(insn)];
					int argCount = Type.getArgumentTypes(call.desc).length;
					if (frame == null || frame.getStackSize() < argCount) continue;
					AbstractInsnNode arg = origin(caller, frames, frame.getStack(frame.getStackSize() - argCount + need.parameter()), new HashSet<>());
					switch (need.role()) {
						case RECEIVER -> add(events, eventOfReceiver(arg));
						case LISTENER -> add(events, eventOfListener(arg));
						case REGISTERED -> add(registered, registeredType(caller, node.name, arg));
					}
				}
			}
		}
	}

	private static void add(Set<String> into, String value) {
		if (value != null) into.add(value);
	}

	private static Frame<SourceValue>[] frames(String owner, MethodNode m) {
		try {
			return new Analyzer<>(new SourceInterpreter()).analyze(owner, m);
		} catch (AnalyzerException unanalyzable) {
			net.forbric.kernel.util.Caught.expected(unanalyzable);
			return null;
		}
	}

	/** The index of the method parameter {@code source} loads unchanged, or -1 ({@code this} is not a parameter). */
	private static int parameter(MethodNode m, Frame<SourceValue>[] frames, AbstractInsnNode source) {
		if (!(source instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD) return -1;
		Frame<SourceValue> frame = frames[m.instructions.indexOf(source)];
		if (frame == null || !frame.getLocal(load.var).insns.isEmpty()) return -1;
		int slot = (m.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
		Type[] params = Type.getArgumentTypes(m.desc);
		for (int i = 0; i < params.length; i++) {
			if (slot == load.var) return i;
			slot += params[i].getSize();
		}
		return -1;
	}

	/**
	 * Whether {@code event}'s own static bus (MinecraftForge's EventBus 7 {@code BUS} field) has a listener now, asked
	 * through {@code loader}; null when the event has no such bus or it cannot be asked.
	 */
	static Boolean listenersOnBus(ClassLoader loader, String event) {
		try {
			Class<?> type = Class.forName(event.replace('/', '.'), false, loader);
			java.lang.reflect.Field bus = type.getField("BUS");
			if (!java.lang.reflect.Modifier.isStatic(bus.getModifiers()) || !bus.getType().getName().startsWith(FORGE_BUSES.replace('/', '.'))) {
				return null;
			}
			Object value = bus.get(null);
			return value == null ? null : (Boolean) bus.getType().getMethod("hasListeners").invoke(value);
		} catch (ClassNotFoundException | NoSuchFieldException | NoSuchMethodException noBus) {
			net.forbric.kernel.util.Caught.expected(noBus);
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError unaskable) {
			net.forbric.kernel.util.Caught.warn(unaskable, "[Forbric/DeadEvents] could not ask %s's bus for its listeners; a mod "
					+ "registering for it in code is judged as found", event);
			return null;
		}
	}

	/** Whether a class carries a family's {@code @OnlyIn(Dist.CLIENT)}: a dedicated server never loads it. */
	private static boolean clientOnly(List<AnnotationNode> annotations) {
		if (annotations == null) return false;
		for (AnnotationNode a : annotations) {
			if (!a.desc.endsWith(ONLY_IN_SUFFIX) || a.values == null) continue;
			for (int i = 0; i + 1 < a.values.size(); i += 2) {
				if ("value".equals(a.values.get(i)) && a.values.get(i + 1) instanceof String[] e && e.length == 2 && "CLIENT".equals(e[1])) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isBus(String owner) {
		return owner.startsWith(FORGE_BUSES) || owner.equals(NEO_BUS) || owner.equals(FORGE_GAME_BUS);
	}

	private static boolean callsABus(MethodNode m) {
		for (AbstractInsnNode insn = m.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof MethodInsnNode call && isBus(call.owner)
					&& (call.name.equals("addListener") || call.name.equals("register"))) return true;
		}
		return false;
	}

	/**
	 * The event whose bus {@code source} produced: an event's static {@code BUS} field, or what its static {@code getBus}
	 * returns.
	 */
	private static String eventOfReceiver(AbstractInsnNode source) {
		if (source instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC && isBusType(Type.getType(f.desc))) return f.owner;
		if (source instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKESTATIC && call.name.equals("getBus")
				&& isBusType(Type.getReturnType(call.desc))) return call.owner;
		return null;
	}

	private static boolean isBusType(Type type) {
		return type.getSort() == Type.OBJECT && isBus(type.getInternalName());
	}

	/** The event a lambda or method-reference listener {@code source} takes: its instantiated method type's first parameter. */
	private static String eventOfListener(AbstractInsnNode source) {
		if (!(source instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null || indy.bsmArgs.length < 3) return null;
		if (!(indy.bsm.getOwner().equals("java/lang/invoke/LambdaMetafactory")) || !(indy.bsmArgs[2] instanceof Type instantiated)) return null;
		Type[] params = instantiated.getArgumentTypes();
		return params.length >= 1 && params[0].getSort() == Type.OBJECT ? params[0].getInternalName() : null;
	}

	/** The class a {@code register} argument {@code source} names: a class literal, a construction, or {@code this}. */
	private static String registeredType(MethodNode m, String owner, AbstractInsnNode source) {
		if (source instanceof LdcInsnNode ldc && ldc.cst instanceof Type t && t.getSort() == Type.OBJECT) return t.getInternalName();
		if (source instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0
				&& (m.access & Opcodes.ACC_STATIC) == 0) return owner;
		if (source instanceof org.objectweb.asm.tree.TypeInsnNode t && t.getOpcode() == Opcodes.NEW) return t.desc;
		return null;
	}

	/**
	 * The instruction that produced {@code value}, followed back through copies, stores, loads and casts; null when it has
	 * more than one producer or none.
	 */
	private static AbstractInsnNode origin(MethodNode m, Frame<SourceValue>[] frames, SourceValue value, Set<AbstractInsnNode> seen) {
		if (value == null || value.insns.size() != 1) return null;
		AbstractInsnNode source = value.insns.iterator().next();
		if (!seen.add(source)) return null;
		Frame<SourceValue> frame = frames[m.instructions.indexOf(source)];
		if (frame == null) return source;
		int opcode = source.getOpcode();
		if (opcode == Opcodes.DUP || opcode == Opcodes.ASTORE || opcode == Opcodes.CHECKCAST) {
			return frame.getStackSize() < 1 ? null : origin(m, frames, frame.getStack(frame.getStackSize() - 1), seen);
		}
		if (source instanceof VarInsnNode load && opcode == Opcodes.ALOAD) {
			SourceValue local = frame.getLocal(load.var);
			// A parameter (no store in the method) is the method's input, not something it produced: {@code this}, for one.
			if (local.insns.isEmpty()) return source;
			return origin(m, frames, local, seen);
		}
		return source;
	}
}
