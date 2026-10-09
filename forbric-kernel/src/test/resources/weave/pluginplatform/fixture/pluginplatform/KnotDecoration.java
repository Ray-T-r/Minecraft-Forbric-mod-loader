package fixture.pluginplatform;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;

/**
 * Decorates Knot's weaver the way a Fabric mod does: from the thread's context loader to its {@code delegate}, to that
 * object's {@code mixinTransformer}, which is wrapped and written back. The wrapper lets the weaver do its work and then
 * turns the {@code "plain"} constant of {@link Stamp} into {@code "decorated"}, so the probe can tell whether every class
 * after this one went through it.
 */
final class KnotDecoration {
	private Object delegate;
	private Field weaverField;

	String install() {
		try {
			ClassLoader game = Thread.currentThread().getContextClassLoader();
			Field delegateField = game.getClass().getDeclaredField("delegate");
			delegateField.setAccessible(true);
			delegate = delegateField.get(game);
			weaverField = delegate.getClass().getDeclaredField("mixinTransformer");
			weaverField.setAccessible(true);
			IMixinTransformer weaver = (IMixinTransformer) weaverField.get(delegate);
			weaverField.set(delegate, decorate(weaver));
			return weaver == null ? "empty" : "wrapped";
		} catch (ReflectiveOperationException unreachable) {
			return "unreachable:" + unreachable;
		}
	}

	private static IMixinTransformer decorate(IMixinTransformer weaver) {
		return (IMixinTransformer) Proxy.newProxyInstance(KnotDecoration.class.getClassLoader(),
				new Class<?>[] {IMixinTransformer.class}, (proxy, method, args) -> {
					Object result;
					try {
						result = method.invoke(weaver, args);
					} catch (InvocationTargetException thrown) {
						throw thrown.getCause();
					}
					if (method.getName().equals("transformClassBytes") && "fixture.pluginplatform.Stamp".equals(args[0])
							&& result instanceof byte[] bytes) {
						return restamp(bytes);
					}
					return result;
				});
	}

	private static byte[] restamp(byte[] bytes) {
		ClassWriter writer = new ClassWriter(0);
		new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, descriptor, signature, exceptions)) {
					@Override
					public void visitLdcInsn(Object value) {
						super.visitLdcInsn("plain".equals(value) ? "decorated" : value);
					}
				};
			}
		}, 0);
		return writer.toByteArray();
	}
}
