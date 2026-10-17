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

package net.forbric.kernel.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;


import net.forbric.kernel.util.ForbricLog;
import net.minecraft.network.PacketEncoder;

/**
 * Gives NeoForge's packet splitter the Fabric packet context every other encode runs inside.
 *
 * <p>Fabric's networking binds a {@code PacketContext} as a {@code ScopedValue} around
 * {@code PacketEncoder.encode}, and a Fabric codec reads it with {@code PacketContext.get()} — Polymer's
 * ingredient codec does, on every recipe it writes. NeoForge's {@code GenericPacketSplitter} is a second encoder
 * EARLIER in the same pipeline: it encodes each packet itself to measure it, outside that scope, where
 * {@code get()} answers null.
 *
 * <p>On the 97-jar pack that was the last step of joining a world. Every previous repair got the client further
 * along, and it ended here: {@code update_recipes} failed to encode on an NPE inside Polymer, thrown from inside
 * NeoForge's splitter, reported to the player as "Internal Exception" and a disconnect. Neither ecosystem is
 * wrong on its own — Fabric binds the context where Fabric encodes, NeoForge encodes somewhere Fabric does not
 * know about — which is exactly the shape of defect that only exists here.
 *
 * <p>The context is not invented. It is the one the connection's own {@code PacketEncoder} already holds, found
 * by walking the pipeline this splitter is installed in, so a codec reading the connection or the registry access
 * out of it gets the real ones. With no Fabric networking present, or no encoder yet in the pipeline, the body
 * runs exactly as before.
 */
public final class KernelPacketContext {
	static final String PROPERTY = "forbric.splitterPacketContext";
	private static final String CONTEXT = "net.fabricmc.fabric.api.networking.v1.context.PacketContext";
	/** The body the transformer moved aside, resolved once per splitter class. */
	private static final ClassValue<Method> BODY = new ClassValue<>() {
		@Override
		protected Method computeValue(Class<?> type) {
			// By name and arity: netty is not on this source set's compile path, so the parameter types cannot be
			// spelled here — and there is exactly one method with this name, which the kernel itself put there.
			for (Method method : type.getDeclaredMethods()) {
				if (!BODY_NAME.equals(method.getName()) || method.getParameterCount() != 3) continue;
				method.setAccessible(true);
				return method;
			}
			return null;
		}
	};
	/** The name {@code SplitterPacketContextInjector} moves the original encode to. */
	public static final String BODY_NAME = "encode$forbriccontext";

	private static volatile Object scopedValue;
	private static volatile Field encoderContext;
	private static volatile boolean resolved;
	private static volatile boolean announced;
	private static volatile boolean warned;

	private KernelPacketContext() {
	}

	public static boolean enabled() {
		return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on"));
	}

	/**
	 * Runs the splitter's own encode, inside the connection's Fabric packet context when there is one.
	 *
	 * @param splitter the {@code GenericPacketSplitter} whose body was moved aside
	 */
	public static void encodeInFabricContext(Object splitter, Object ctx, Object packet, Object out)
			throws Exception {
		Method body = BODY.get(splitter.getClass());
		if (body == null) return;

		Object context = enabled() ? contextOf(ctx) : null;
		if (context == null) {
			invoke(body, splitter, ctx, packet, out);
			return;
		}
		if (!announced) {
			announced = true;
			ForbricLog.info("[Forbric/Net] NeoForge's packet splitter now encodes inside the connection's Fabric "
					+ "packet context — it is a second encoder earlier in the same pipeline, and a Fabric codec "
					+ "reading PacketContext.get() there got null");
		}
		Throwable[] thrown = new Throwable[1];
		scope(context, () -> {
			try {
				invoke(body, splitter, ctx, packet, out);
			} catch (Throwable t) {
				thrown[0] = t;
			}
		});
		if (thrown[0] instanceof Exception e) throw e;
		if (thrown[0] != null) throw (Error) thrown[0];
	}

	private static void invoke(Method body, Object splitter, Object ctx, Object packet, Object out) throws Exception {
		try {
			body.invoke(splitter, ctx, packet, out);
		} catch (java.lang.reflect.InvocationTargetException wrapped) {
			Throwable cause = wrapped.getCause() != null ? wrapped.getCause() : wrapped;
			if (cause instanceof Exception e) throw e;
			throw (Error) cause;
		}
	}

	/** {@code ScopedValue.where(<the scoped value PacketContext.get() reads>, context).run(body)}, reflectively. */
	private static void scope(Object context, Runnable body) {
		try {
			Object value = scopedValue;
			Class<?> scoped = Class.forName("java.lang.ScopedValue");
			Object carrier = scoped.getMethod("where", scoped, Object.class).invoke(null, value, context);
			carrier.getClass().getMethod("run", Runnable.class).invoke(carrier, body);
		} catch (ReflectiveOperationException | RuntimeException e) {
			warnOnce("could not bind the Fabric packet context around NeoForge's splitter", e);
			body.run();
		}
	}

	/** The {@code PacketContext} the connection's own {@code PacketEncoder} holds, or null. */
	private static Object contextOf(Object ctx) {
		if (!resolve(ctx)) return null;
		try {
			// Through the INTERFACE, not the concrete class: netty's AbstractChannelHandlerContext is
			// package-private, so a method looked up on it is public on a class this cannot access.
			Class<?> handlerContext = Class.forName("io.netty.channel.ChannelHandlerContext", false,
					ctx.getClass().getClassLoader());
			Object pipeline = handlerContext.getMethod("pipeline").invoke(ctx);
			for (Object item : (Iterable<?>) pipeline) {
				if (!(item instanceof java.util.Map.Entry<?, ?> entry)) continue;
				if (!(entry.getValue() instanceof PacketEncoder<?> encoder)) continue;
				return encoderContext.get(encoder);
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			warnOnce("could not read the connection's Fabric packet context", e);
		}
		return null;
	}

	/**
	 * Resolves, once, the two things the bind needs: the scoped value fabric-api's public {@code PacketContext.get()}
	 * answers from — the {@code ScopedValue} static that method reads, found in its own bytecode, whatever the class that
	 * holds it is called — and the encoder's field of the public {@code PacketContext} type. Absent means no Fabric
	 * networking here, which is not a failure.
	 */
	private static boolean resolve(Object ctx) {
		if (resolved) return scopedValue != null && encoderContext != null;
		synchronized (KernelPacketContext.class) {
			if (resolved) return scopedValue != null && encoderContext != null;
			resolved = true;
			try {
				ClassLoader loader = ctx.getClass().getClassLoader();
				Field value = scopedValueReadBy(loader, CONTEXT, "get");
				if (value == null) throw new NoSuchFieldException("no ScopedValue read by " + CONTEXT + ".get()");
				value.setAccessible(true);
				Class<?> context = Class.forName(CONTEXT, false, loader);
				for (Field field : PacketEncoder.class.getDeclaredFields()) {
					if (!context.isAssignableFrom(field.getType())) continue;
					field.setAccessible(true);
					encoderContext = field;
					break;
				}
				if (encoderContext != null) scopedValue = value.get(null);
			} catch (ReflectiveOperationException | RuntimeException absent) {
				ForbricLog.debug("[Forbric/Net] no Fabric packet context on this instance — NeoForge's splitter "
						+ "encodes exactly as it always did");
			}
		}
		return scopedValue != null && encoderContext != null;
	}

	/**
	 * The static field holding a {@code ScopedValue} that the public no-argument static {@code method} of {@code owner}
	 * reads first, read off that method's own bytecode and judged by the value it holds; null when there is none.
	 */
	static Field scopedValueReadBy(ClassLoader loader, String owner, String method) throws ReflectiveOperationException {
		byte[] bytes;
		try (java.io.InputStream in = loader.getResourceAsStream(owner.replace('.', '/') + ".class")) {
			if (in == null) return null;
			bytes = in.readAllBytes();
		} catch (java.io.IOException unreadable) {
			return null;
		}
		org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
		new org.objectweb.asm.ClassReader(bytes).accept(node, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
		for (org.objectweb.asm.tree.MethodNode candidate : node.methods) {
			if (!candidate.name.equals(method) || !candidate.desc.startsWith("()")
					|| (candidate.access & org.objectweb.asm.Opcodes.ACC_STATIC) == 0) continue;
			for (org.objectweb.asm.tree.AbstractInsnNode insn : candidate.instructions) {
				if (!(insn instanceof org.objectweb.asm.tree.FieldInsnNode read) || read.getOpcode() != org.objectweb.asm.Opcodes.GETSTATIC
						|| read.desc.startsWith("[") || read.desc.length() == 1) continue;
				Field field = Class.forName(read.owner.replace('/', '.'), true, loader).getDeclaredField(read.name);
				field.setAccessible(true);
				if (Class.forName("java.lang.ScopedValue").isInstance(field.get(null))) return field;
			}
		}
		return null;
	}

	private static void warnOnce(String what, Throwable t) {
		if (warned) return;
		warned = true;
		ForbricLog.warn("[Forbric/Net] " + what + " — a Fabric codec that reads it will see null there, as before "
				+ "this repair existed", t);
	}
}
