package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Runs the re-hosted handler: NeoForge's own veto decides first, then the guest's original handler on the one effect
 * decides, asking its question exactly once with the entity, the effect's key and the effect itself. Every shape the proof
 * admits is executed — a snapshot loop, a {@code removeIf} that never calls the original, kotlinc's shape with two host
 * maps, and a stream whose predicate captures more than the entity.
 */
class PerEffectClearVetoExecutionTest {
	private static final String LIVING = PerEffectClearVetoTest.LIVING, HOLDER = PerEffectClearVetoTest.HOLDER,
			EFFECT = PerEffectClearVetoTest.EFFECT, OP = PerEffectClearVetoTest.OP, GUARDS = PerEffectClearVetoTest.GUARDS;

	@Test void aSnapshotLoopDecidesEachEffectOnItsOwn() throws Exception {
		run("org/example/effects/mixin/SnapshotVetoMixin", "keepGuarded", mixin -> mixin.methods.add(PerEffectClearVetoTest.snapshotLoop("keepGuarded", false, false)));
	}

	@Test void aRemoveIfDecidesEachEffectOnItsOwn() throws Exception {
		run("org/example/effects/mixin/PruneVetoMixin", "pruneEffects", PerEffectClearVetoTest::addRemoveIf);
	}

	@Test void kotlincsShapeDecidesEachEffectOnItsOwn() throws Exception {
		run("org/example/effects/mixin/KotlinVetoMixin", "vetoClear", mixin -> mixin.methods.add(PerEffectClearVetoTest.kotlinShaped()));
	}

	@Test void aStreamWithAnExtraCaptureDecidesEachEffectOnItsOwn() throws Exception {
		run("org/example/effects/mixin/StrictVetoMixin", "strictClear", PerEffectClearVetoTest::addStreamWithExtraCapture);
	}

	private static void run(String name, String handler, Consumer<ClassNode> shape) throws Exception {
		ClassNode mixin = PerEffectClearVetoTest.mixin(name);
		mixin.superName = LIVING;   // as woven: the handler runs on the entity itself
		mixin.access = Opcodes.ACC_PUBLIC;
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LIVING, "<init>", "()V", false));
		init.instructions.add(new InsnNode(Opcodes.RETURN));
		mixin.methods.add(init);
		shape.accept(mixin);
		ClassNode living = StagedFabricMixinFixture.living(false);
		assertEquals(1, FabricEntityMixinAnchors.adapt(mixin, n -> living));
		mixin.visibleAnnotations = null;
		mixin.invisibleAnnotations = null;
		for (MethodNode m : mixin.methods) {
			m.visibleAnnotations = null;
			m.invisibleAnnotations = null;
		}

		Loader loader = new Loader();
		Class<?> holderClass = loader.define(plain(HOLDER, "java/lang/Object"));
		Class<?> entityClass = loader.define(plain(LIVING, "java/lang/Object"));
		Class<?> effectClass = loader.define(effect());
		Class<?> operationClass = loader.define(operation());
		Class<?> guards = loader.define(guards());
		loader.define(intrinsics());
		Class<?> provider = loader.define(mixin);
		Object entity = provider.getConstructor().newInstance();
		Object holder = holderClass.getConstructor().newInstance();
		Object effect = effectClass.getConstructor(holderClass).newInstance(holder);
		boolean[] nativeKeeps = {false};
		int[] nativeAsked = {0};
		Object operation = Proxy.newProxyInstance(loader, new Class[] {operationClass}, (proxy, method, args) -> {
			Object[] arguments = (Object[]) args[0];
			assertSame(entity, arguments[0]);
			assertSame(effect, arguments[1]);
			nativeAsked[0]++;
			return nativeKeeps[0];
		});
		Method bridge = provider.getDeclaredMethod("forbric$clearVeto$" + handler, entityClass, effectClass, operationClass);
		bridge.setAccessible(true);

		guards.getField("answer").setBoolean(null, true);
		assertEquals(true, bridge.invoke(entity, entity, effect, operation), "the guest keeps it");
		assertEquals(1, guards.getField("asked").getInt(null), "asked once, for this effect");
		assertSame(entity, guards.getField("lastEntity").get(null));
		assertSame(holder, guards.getField("lastHolder").get(null));
		assertSame(effect, guards.getField("lastEffect").get(null));

		guards.getField("answer").setBoolean(null, false);
		assertEquals(false, bridge.invoke(entity, entity, effect, operation), "the guest lets it go");
		assertEquals(2, guards.getField("asked").getInt(null));

		nativeKeeps[0] = true;
		assertEquals(true, bridge.invoke(entity, entity, effect, operation), "NeoForge keeping it keeps it");
		assertEquals(2, guards.getField("asked").getInt(null), "and the guest is not asked");
		assertEquals(3, nativeAsked[0]);
	}

	private static ClassNode plain(String name, String superName) {
		ClassNode n = new ClassNode();
		n.version = Opcodes.V21;
		n.access = Opcodes.ACC_PUBLIC;
		n.name = name;
		n.superName = superName;
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
		init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, superName, "<init>", "()V", false));
		init.instructions.add(new InsnNode(Opcodes.RETURN));
		n.methods.add(init);
		return n;
	}

	private static ClassNode effect() {
		ClassNode n = plain(EFFECT, "java/lang/Object");
		n.methods.clear();
		n.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "holder", "L" + HOLDER + ";", null, null));
		MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(L" + HOLDER + ";)V", null, null);
		PerEffectClearVetoTest.add(init, new VarInsnNode(Opcodes.ALOAD, 0), new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false),
				new VarInsnNode(Opcodes.ALOAD, 0), new VarInsnNode(Opcodes.ALOAD, 1), new FieldInsnNode(Opcodes.PUTFIELD, EFFECT, "holder", "L" + HOLDER + ";"),
				new InsnNode(Opcodes.RETURN));
		n.methods.add(init);
		MethodNode get = new MethodNode(Opcodes.ACC_PUBLIC, "getEffect", "()L" + HOLDER + ";", null, null);
		PerEffectClearVetoTest.add(get, new VarInsnNode(Opcodes.ALOAD, 0), new FieldInsnNode(Opcodes.GETFIELD, EFFECT, "holder", "L" + HOLDER + ";"),
				new InsnNode(Opcodes.ARETURN));
		n.methods.add(get);
		return n;
	}

	private static ClassNode operation() {
		ClassNode n = plain(OP, "java/lang/Object");
		n.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		n.methods.clear();
		n.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", null, null));
		return n;
	}

	/** The mod's own question: records what it was asked and answers {@code answer}. */
	private static ClassNode guards() {
		ClassNode n = plain(GUARDS, "java/lang/Object");
		for (String[] field : new String[][] {{"answer", "Z"}, {"STRICT", "Z"}, {"asked", "I"}, {"lastEntity", "Ljava/lang/Object;"},
				{"lastHolder", "Ljava/lang/Object;"}, {"lastEffect", "Ljava/lang/Object;"}})
			n.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, field[0], field[1], null, null));
		MethodNode keep = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "keep", PerEffectClearVetoTest.KEEP, null, null);
		PerEffectClearVetoTest.add(keep, new FieldInsnNode(Opcodes.GETSTATIC, GUARDS, "asked", "I"), new InsnNode(Opcodes.ICONST_1), new InsnNode(Opcodes.IADD),
				new FieldInsnNode(Opcodes.PUTSTATIC, GUARDS, "asked", "I"),
				new VarInsnNode(Opcodes.ALOAD, 0), new FieldInsnNode(Opcodes.PUTSTATIC, GUARDS, "lastEntity", "Ljava/lang/Object;"),
				new VarInsnNode(Opcodes.ALOAD, 1), new FieldInsnNode(Opcodes.PUTSTATIC, GUARDS, "lastHolder", "Ljava/lang/Object;"),
				new VarInsnNode(Opcodes.ALOAD, 2), new FieldInsnNode(Opcodes.PUTSTATIC, GUARDS, "lastEffect", "Ljava/lang/Object;"),
				new FieldInsnNode(Opcodes.GETSTATIC, GUARDS, "answer", "Z"), new InsnNode(Opcodes.IRETURN));
		n.methods.add(keep);
		return n;
	}

	private static ClassNode intrinsics() {
		ClassNode n = plain("kotlin/jvm/internal/Intrinsics", "java/lang/Object");
		// As kotlinc's runtime does: a null parameter throws.
		MethodNode check = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "checkNotNullParameter", "(Ljava/lang/Object;Ljava/lang/String;)V", null, null);
		LabelNode fine = new LabelNode();
		PerEffectClearVetoTest.add(check, new VarInsnNode(Opcodes.ALOAD, 0), new JumpInsnNode(Opcodes.IFNONNULL, fine),
				new TypeInsnNode(Opcodes.NEW, "java/lang/NullPointerException"), new InsnNode(Opcodes.DUP), new VarInsnNode(Opcodes.ALOAD, 1),
				new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/NullPointerException", "<init>", "(Ljava/lang/String;)V", false),
				new InsnNode(Opcodes.ATHROW), fine, new InsnNode(Opcodes.RETURN));
		n.methods.add(check);
		return n;
	}

	private static final class Loader extends ClassLoader {
		Loader() {
			super(PerEffectClearVetoExecutionTest.class.getClassLoader());
		}

		Class<?> define(ClassNode node) {
			ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
				@Override protected String getCommonSuperClass(String a, String b) {
					return "java/lang/Object";
				}
			};
			List<MethodNode> methods = new ArrayList<>(node.methods);
			for (MethodNode m : methods) for (AbstractInsnNode i : m.instructions.toArray()) if (i instanceof FrameNode) m.instructions.remove(i);
			node.accept(writer);
			byte[] bytes = writer.toByteArray();
			return defineClass(node.name.replace('/', '.'), bytes, 0, bytes.length);
		}
	}
}
