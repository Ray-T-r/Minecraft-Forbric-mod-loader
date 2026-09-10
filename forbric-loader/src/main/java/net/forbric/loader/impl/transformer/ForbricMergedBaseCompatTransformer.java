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

package net.forbric.loader.impl.transformer;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.forbric.loader.impl.util.ForbricLog;

/**
 * Repairs class-local bytecode invariants that can drift when two patched Minecraft bases are merged.
 */
public final class ForbricMergedBaseCompatTransformer implements ClassTransformer {
	@Override
	public String name() {
		return "forbric-merged-base-compat";
	}

	@Override
	public byte[] transform(String className, byte[] classBytes, TransformContext context) {
		if (classBytes == null || classBytes.length == 0) return classBytes;
		try {
			ClassNode node = new ClassNode();
			new ClassReader(classBytes).accept(node, 0);
			boolean changed = repairLambdaBootstrapHandles(node);
			changed |= addBlockStateModelConflictResolvers(node);
			changed |= addMissingForgeFluidTypeBridge(node);
			changed |= addMissingForgeKeyMappingLookupInitializer(node);
			if (!changed) return classBytes;

			ClassWriter writer = new ClassWriter(0);
			node.accept(writer);
			return writer.toByteArray();
		} catch (RuntimeException e) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] could not inspect " + className, e);
			return classBytes;
		}
	}

	private static boolean repairLambdaBootstrapHandles(ClassNode node) {
		Map<String, MethodNode> methods = new HashMap<>();
		for (MethodNode method : node.methods) {
			methods.put(method.name + method.desc, method);
		}

		boolean changed = false;
		for (MethodNode caller : node.methods) {
			for (AbstractInsnNode insn = caller.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (!(insn instanceof InvokeDynamicInsnNode indy) || indy.bsmArgs == null) continue;
				for (int i = 0; i < indy.bsmArgs.length; i++) {
					if (!(indy.bsmArgs[i] instanceof Handle handle)) continue;
					Handle repaired = repairLambdaHandle(node, methods, caller, indy, handle);
					if (repaired == handle) continue;
					indy.bsmArgs[i] = repaired;
					changed = true;
				}
			}
		}
		return changed;
	}

	private static boolean addBlockStateModelConflictResolvers(ClassNode node) {
		if (!"net/minecraft/client/renderer/block/dispatch/BlockStateModel".equals(node.name)) return false;

		boolean changed = false;
		if (!hasMethod(node, "createGeometryKey",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)"
						+ "Ljava/lang/Object;")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "createGeometryKey",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)"
							+ "Ljava/lang/Object;",
					null, null);
			method.instructions.add(new InsnNode(Opcodes.ACONST_NULL));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 5;
			node.methods.add(method);
			changed = true;
		}

		if (!hasMethod(node, "particleMaterial",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;)"
						+ "Lnet/minecraft/client/resources/model/sprite/Material$Baked;")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "particleMaterial",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)"
							+ "Lnet/minecraft/client/resources/model/sprite/Material$Baked;",
					null, null);
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"net/minecraft/client/renderer/block/dispatch/BlockStateModel",
					"particleMaterial",
					"()Lnet/minecraft/client/resources/model/sprite/Material$Baked;",
					true));
			method.instructions.add(new InsnNode(Opcodes.ARETURN));
			method.maxStack = 1;
			method.maxLocals = 4;
			node.methods.add(method);
			changed = true;
		}

		if (!hasMethod(node, "materialFlags",
				"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
						+ "Lnet/minecraft/world/level/block/state/BlockState;)I")) {
			MethodNode method = new MethodNode(Opcodes.ACC_PUBLIC, "materialFlags",
					"(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
							+ "Lnet/minecraft/world/level/block/state/BlockState;)I",
					null, null);
			method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
			method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
					"net/minecraft/client/renderer/block/dispatch/BlockStateModel",
					"materialFlags", "()I", true));
			method.instructions.add(new InsnNode(Opcodes.IRETURN));
			method.maxStack = 1;
			method.maxLocals = 4;
			node.methods.add(method);
			changed = true;
		}

		if (changed) {
			ForbricLog.warn("[Forbric/MergedBaseCompat] added BlockStateModel default-method conflict resolvers");
		}
		return changed;
	}

	private static boolean addMissingForgeFluidTypeBridge(ClassNode node) {
		if ((node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0) return false;
		if (!node.name.startsWith("net/minecraft/world/level/material/")) return false;
		if (!node.interfaces.contains("net/neoforged/neoforge/common/extensions/IFluidExtension")) return false;
		if (hasMethod(node, "getFluidType", "()Lnet/minecraftforge/fluids/FluidType;")) return false;

		MethodNode bridge = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNTHETIC,
				"getFluidType", "()Lnet/minecraftforge/fluids/FluidType;", null, null);
		bridge.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		bridge.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
				"net/forbric/loader/impl/forge/runtime/ForbricForgeRuntimeInterop",
				"forgeFluidType", "(Ljava/lang/Object;)Ljava/lang/Object;", false));
		bridge.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/minecraftforge/fluids/FluidType"));
		bridge.instructions.add(new InsnNode(Opcodes.ARETURN));
		bridge.maxStack = 1;
		bridge.maxLocals = 1;
		node.methods.add(bridge);
		ForbricLog.warn("[Forbric/MergedBaseCompat] added Forge FluidType bridge to %s",
				node.name.replace('/', '.'));
		return true;
	}

	private static boolean addMissingForgeKeyMappingLookupInitializer(ClassNode node) {
		if (!"net/minecraft/client/KeyMapping".equals(node.name)) return false;
		String forgeLookup = "Lnet/minecraftforge/client/settings/KeyMappingLookup;";
		if (!hasField(node, "MAP", forgeLookup) || initializesStaticField(node, "MAP", forgeLookup)) return false;

		MethodNode clinit = findMethod(node, "<clinit>", "()V");
		if (clinit == null) {
			clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
			clinit.instructions.add(new InsnNode(Opcodes.RETURN));
			clinit.maxLocals = 0;
			node.methods.add(clinit);
		}

		boolean inserted = false;
		for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() != Opcodes.RETURN) continue;
			clinit.instructions.insertBefore(insn, new TypeInsnNode(Opcodes.NEW,
					"net/minecraftforge/client/settings/KeyMappingLookup"));
			clinit.instructions.insertBefore(insn, new InsnNode(Opcodes.DUP));
			clinit.instructions.insertBefore(insn, new MethodInsnNode(Opcodes.INVOKESPECIAL,
					"net/minecraftforge/client/settings/KeyMappingLookup", "<init>", "()V", false));
			clinit.instructions.insertBefore(insn, new FieldInsnNode(Opcodes.PUTSTATIC,
					"net/minecraft/client/KeyMapping", "MAP", forgeLookup));
			inserted = true;
		}
		if (!inserted) return false;

		clinit.maxStack = Math.max(clinit.maxStack, 2);
		ForbricLog.warn("[Forbric/MergedBaseCompat] initialized Forge KeyMapping lookup on merged client base");
		return true;
	}

	private static boolean hasMethod(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return true;
		}
		return false;
	}

	private static MethodNode findMethod(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (method.name.equals(name) && method.desc.equals(desc)) return method;
		}
		return null;
	}

	private static boolean hasField(ClassNode node, String name, String desc) {
		for (FieldNode field : node.fields) {
			if (field.name.equals(name) && field.desc.equals(desc)) return true;
		}
		return false;
	}

	private static boolean initializesStaticField(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) {
			if (!method.name.equals("<clinit>") || !method.desc.equals("()V")) continue;
			for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
				if (insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.PUTSTATIC
						&& field.owner.equals(node.name) && field.name.equals(name) && field.desc.equals(desc)) {
					return true;
				}
			}
		}
		return false;
	}

	private static Handle repairLambdaHandle(ClassNode owner, Map<String, MethodNode> methods, MethodNode caller,
			InvokeDynamicInsnNode indy, Handle handle) {
		if (!owner.name.equals(handle.getOwner()) || !handle.getName().startsWith("lambda$")) return handle;

		MethodNode target = methods.get(handle.getName() + handle.getDesc());
		if (target == null) return handle;

		boolean methodStatic = (target.access & Opcodes.ACC_STATIC) != 0;
		boolean handleStatic = handle.getTag() == Opcodes.H_INVOKESTATIC;
		if (methodStatic == handleStatic) return handle;

		if (!methodStatic && handleStatic) {
			if (!capturesOwner(owner, indy.desc)) {
				if ((caller.access & Opcodes.ACC_STATIC) != 0 || !prependThisCapture(owner, caller, indy)) {
					return handle;
				}
			}
			ForbricLog.warn("[Forbric/MergedBaseCompat] repaired lambda bootstrap handle %s.%s%s "
					+ "from static to instance; invokedynamic is now %s",
					owner.name.replace('/', '.'), handle.getName(), handle.getDesc(), indy.desc);
			return new Handle(Opcodes.H_INVOKEVIRTUAL, handle.getOwner(), handle.getName(), handle.getDesc(), false);
		}

		ForbricLog.warn("[Forbric/MergedBaseCompat] repaired lambda bootstrap handle %s.%s%s from tag %d to %d",
				owner.name.replace('/', '.'), handle.getName(), handle.getDesc(), handle.getTag(), Opcodes.H_INVOKESTATIC);
		return new Handle(Opcodes.H_INVOKESTATIC, handle.getOwner(), handle.getName(), handle.getDesc(), false);
	}

	private static boolean capturesOwner(ClassNode owner, String invokedynamicDesc) {
		Type[] args = Type.getArgumentTypes(invokedynamicDesc);
		return args.length > 0 && args[0].getSort() == Type.OBJECT && owner.name.equals(args[0].getInternalName());
	}

	private static boolean prependThisCapture(ClassNode owner, MethodNode caller, InvokeDynamicInsnNode indy) {
		AbstractInsnNode insertionPoint = capturedArgsStart(indy);
		if (insertionPoint == null) return false;
		caller.instructions.insertBefore(insertionPoint, new VarInsnNode(Opcodes.ALOAD, 0));
		indy.desc = prependArgument(Type.getObjectType(owner.name), indy.desc);
		caller.maxStack = Math.max(caller.maxStack, caller.maxStack + 1);
		return true;
	}

	private static AbstractInsnNode capturedArgsStart(InvokeDynamicInsnNode indy) {
		Type[] args = Type.getArgumentTypes(indy.desc);
		if (args.length == 0) return indy;

		AbstractInsnNode cursor = indy.getPrevious();
		AbstractInsnNode first = null;
		for (int i = args.length - 1; i >= 0; i--) {
			cursor = previousReal(cursor);
			if (!isLocalLoadFor(args[i], cursor)) return null;
			first = cursor;
			cursor = cursor.getPrevious();
		}
		return first;
	}

	private static AbstractInsnNode previousReal(AbstractInsnNode cursor) {
		while (cursor != null && cursor.getOpcode() < 0) {
			cursor = cursor.getPrevious();
		}
		return cursor;
	}

	private static boolean isLocalLoadFor(Type type, AbstractInsnNode insn) {
		return insn instanceof VarInsnNode var && var.getOpcode() == loadOpcode(type);
	}

	private static int loadOpcode(Type type) {
		return switch (type.getSort()) {
			case Type.LONG -> Opcodes.LLOAD;
			case Type.FLOAT -> Opcodes.FLOAD;
			case Type.DOUBLE -> Opcodes.DLOAD;
			case Type.ARRAY, Type.OBJECT -> Opcodes.ALOAD;
			default -> Opcodes.ILOAD;
		};
	}

	private static String prependArgument(Type argument, String methodDesc) {
		Type[] oldArgs = Type.getArgumentTypes(methodDesc);
		Type[] newArgs = new Type[oldArgs.length + 1];
		newArgs[0] = argument;
		System.arraycopy(oldArgs, 0, newArgs, 1, oldArgs.length);
		return Type.getMethodDescriptor(Type.getReturnType(methodDesc), newArgs);
	}
}
