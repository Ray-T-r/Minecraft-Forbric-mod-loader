package net.forbric.kernel.transform;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * The pinned lifecycle seams needed to pair the real Fabric 8.x and NeoForge 26.2 transactions. Original
 * validation and snapshot algorithms remain intact. Missing/drifted seams get no readiness marker, so the runtime
 * refuses a cross-API transfer before modifying either endpoint. Register before Mixin when Fabric transfer is present.
 *
 * <p>NeoForge's engine is the platform's own and is named. fabric-api's is an ordinary mod's implementation, so it is
 * found the way a caller reaches it: from the public {@code Transaction}'s static openers, following the calls until one
 * constructs an implementer of {@code Transaction} ({@link #fabricEngine}). That method is the engine's one way to a new
 * scope; its class is the manager, the constructed class is the transaction. In the transaction, the native close is the
 * private {@code (TransactionContext.Result)V} method that runs the {@code OuterCloseCallback}s. Whatever names a build
 * of fabric-api gives these, they are hooked the same.
 */
public final class TransferTransactionHooks implements ClassTransformer {
	public static final String NEO = "net.neoforged.neoforge.transfer.transaction.Transaction";
	public static final String NEO_MANAGER = "net.neoforged.neoforge.transfer.transaction.TransactionManager";
	/** fabric-api's public transaction contract, from whose static openers its engine is found. */
	public static final String FABRIC_API = "net/fabricmc/fabric/api/transfer/v1/transaction/Transaction";
	public static final String MARKER = "forbric$transferHooks";
	private static final String HOOK = "net/forbric/kernel/runtime/transfer/PairedTransactions";
	private static final String RESULT = "net/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext$Result";
	private static final String OUTER_CALLBACK = "net/fabricmc/fabric/api/transfer/v1/transaction/TransactionContext$OuterCloseCallback";
	private static final String JOURNAL = "net/neoforged/neoforge/transfer/transaction/SnapshotJournal";
	/** How many methods the search from the public openers reads before it gives up. */
	private static final int SEARCH_LIMIT = 64;

	/**
	 * fabric-api's transaction engine as its public openers reach it: the class whose {@code funnel} method constructs
	 * the {@code transaction} class (an implementer of the public {@code Transaction}). Internal names.
	 */
	public record FabricEngine(String manager, String funnelName, String funnelDesc, String transaction) {
		/** The two engine classes, binary names. */
		public List<String> classes() {
			return manager.equals(transaction) ? List.of(binary(manager)) : List.of(binary(manager), binary(transaction));
		}
	}

	private final Function<String, byte[]> classBytes;
	private volatile Optional<FabricEngine> engine;

	/** @param classBytes the game's unmodified class bytes by internal name, or null when absent */
	public TransferTransactionHooks(Function<String, byte[]> classBytes) {
		this.classBytes = classBytes;
	}

	@Override public String name() { return "forbric:paired-transfer-transactions"; }
	@Override public AnchorSet anchors() {
		// fabric-api's engine classes are found, not named, so they cannot be declared here; the runtime refuses every
		// cross-API transfer when either of them came back without the marker (PairedTransactions.checkHooks).
		return AnchorSet.of(new AnchorSet.Anchor(NEO, AnchorSet.Severity.REQUIRED, "NeoForge transfers cannot follow Fabric rollback"),
				new AnchorSet.Anchor(NEO_MANAGER, AnchorSet.Severity.REQUIRED, "NeoForge final notifications cannot wait for both transaction engines"));
	}

	/** fabric-api's engine as this transformer found it, or null when the public API is absent or reaches none. */
	public FabricEngine engine() {
		Optional<FabricEngine> found = engine;
		if (found == null) engine = found = Optional.ofNullable(fabricEngine(classBytes));
		return found.orElse(null);
	}

	/** Whether {@code className} is one of the classes this transformer hooks. */
	public boolean targets(String className) {
		if (NEO.equals(className) || NEO_MANAGER.equals(className)) return true;
		FabricEngine found = engine();
		return found != null && found.classes().contains(className);
	}

	@Override public byte[] transform(String className, byte[] bytes, TransformContext context) {
		if (!targets(className)) return bytes;
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		if (node.methods.stream().anyMatch(m -> MARKER.equals(m.name))) return bytes;
		if (NEO.equals(className)) {
			MethodNode close = node.methods.stream().filter(m -> m.name.equals("close") && m.desc.equals("(Z)V")).findFirst().orElse(null);
			if (close == null || !hookClose(node, close, true, classBytes)) return bytes;
		} else if (NEO_MANAGER.equals(className)) {
			int changed = 0;
			for (MethodNode method : node.methods) if (method.name.equals("processRootCommitQueue")) {
				changed += replaceFinal(method, JOURNAL, "callOnRootCommit", "()V", "neoFinal", "(Ljava/lang/Object;)V");
			}
			MethodNode open = method(node, "open", "(Lnet/neoforged/neoforge/transfer/transaction/TransactionContext;Ljava/lang/Class;)Lnet/neoforged/neoforge/transfer/transaction/Transaction;");
			if (changed != 1 || open == null) return bytes;
			guardOpen(open);
		} else {
			FabricEngine found = engine();
			String internal = className.replace('.', '/');
			if (internal.equals(found.transaction())) {
				MethodNode close = fabricClose(node);
				if (close == null || replaceFinal(close, OUTER_CALLBACK, "afterOuterClose", "(L" + RESULT + ";)V",
						"fabricFinal", "(Ljava/lang/Object;Ljava/lang/Object;)V") != 1 || !hookClose(node, close, false, classBytes)) return bytes;
			}
			if (internal.equals(found.manager())) {
				MethodNode funnel = method(node, found.funnelName(), found.funnelDesc());
				if (funnel == null) return bytes;
				guardOpen(funnel);
			}
		}
		MethodNode marker = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC, MARKER, "()V", null, null);
		marker.visitCode(); marker.visitInsn(Opcodes.RETURN); marker.visitMaxs(0, 0); marker.visitEnd();
		node.methods.add(marker);
		ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		node.accept(writer);
		return writer.toByteArray();
	}

	/**
	 * fabric-api's engine, found from the public {@code Transaction}'s static methods that return a {@code Transaction}:
	 * the calls they make are followed, breadth first, through every class whose bytes are available, until a method
	 * constructs a class implementing {@code Transaction}. Null when the API is absent or no such method is reached.
	 */
	public static FabricEngine fabricEngine(Function<String, byte[]> classBytes) {
		if (classBytes == null) return null;
		Map<String, ClassNode> classes = new HashMap<>();
		ClassNode api = read(classBytes, classes, FABRIC_API);
		if (api == null || (api.access & Opcodes.ACC_INTERFACE) == 0) return null;
		ArrayDeque<String[]> queue = new ArrayDeque<>();
		Set<String> seen = new HashSet<>();
		String opened = ")L" + FABRIC_API + ";";
		for (MethodNode method : api.methods) {
			if ((method.access & Opcodes.ACC_STATIC) != 0 && method.desc.endsWith(opened)) queue.add(new String[] { api.name, method.name, method.desc });
		}
		while (!queue.isEmpty() && seen.size() < SEARCH_LIMIT) {
			String[] next = queue.removeFirst();
			if (!seen.add(next[0] + '.' + next[1] + next[2])) continue;
			ClassNode owner = read(classBytes, classes, next[0]);
			MethodNode method = owner == null ? null : method(owner, next[1], next[2]);
			if (method == null || method.instructions.size() == 0) continue;
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW && implementsApi(classBytes, classes, type.desc, 0)) {
					return new FabricEngine(owner.name, method.name, method.desc, type.desc);
				}
			}
			for (AbstractInsnNode insn : method.instructions) {
				if (insn instanceof MethodInsnNode call && !platform(call.owner)) queue.add(new String[] { call.owner, call.name, call.desc });
			}
		}
		return null;
	}

	/** The transaction's native close: its private {@code (Result)V} instance method that runs the outer close callbacks. */
	private static MethodNode fabricClose(ClassNode node) {
		MethodNode only = null;
		for (MethodNode method : node.methods) {
			if ((method.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != Opcodes.ACC_PRIVATE || !method.desc.equals("(L" + RESULT + ";)V")) continue;
			boolean outer = false;
			for (AbstractInsnNode insn : method.instructions) {
				outer |= insn instanceof MethodInsnNode call && call.owner.equals(OUTER_CALLBACK) && call.name.equals("afterOuterClose");
			}
			if (!outer) continue;
			if (only != null) return null;
			only = method;
		}
		return only;
	}

	/**
	 * Renames the native close and puts a wrapper under its name: the native close's leading validation, then
	 * beforeClose, the native close, afterClose. Refused when the native close does not open with a validation.
	 */
	private static boolean hookClose(ClassNode node, MethodNode close, boolean neo, Function<String, byte[]> classBytes) {
		if ((close.access & Opcodes.ACC_PRIVATE) == 0) return false;
		Map<String, ClassNode> classes = new HashMap<>();
		classes.put(node.name, node);
		List<List<AbstractInsnNode>> guards = leadingGuards(close, call -> {
			ClassNode owner = read(classBytes, classes, call.owner);
			return owner == null ? null : method(owner, call.name, call.desc);
		});
		if (guards.isEmpty()) return false;
		String name = close.name;
		close.name = "forbric$nativeTransferClose";
		node.methods.add(wrapper(node.name, name, close.desc, neo, guards));
		return true;
	}

	/**
	 * The statements a method opens with that only validate: each starts from {@code this}, reads fields and calls methods
	 * on what it reaches, and ends in a call to a validator — a method that returns nothing, can throw, and writes no
	 * field — leaving the stack empty. They take no argument of the method's, so running them once more before the method
	 * checks exactly what the method checks first.
	 */
	static List<List<AbstractInsnNode>> leadingGuards(MethodNode method, Function<MethodInsnNode, MethodNode> callee) {
		List<List<AbstractInsnNode>> guards = new ArrayList<>();
		List<AbstractInsnNode> statement = new ArrayList<>();
		int depth = 0;
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn instanceof LabelNode || insn instanceof LineNumberNode || insn instanceof FrameNode) continue;
			if (insn instanceof VarInsnNode load && load.getOpcode() == Opcodes.ALOAD && load.var == 0) {
				depth++;
			} else if (insn instanceof FieldInsnNode get && get.getOpcode() == Opcodes.GETFIELD && depth > 0
					&& Type.getType(get.desc).getSort() >= Type.ARRAY) {
				// one reference for another
			} else if (insn instanceof MethodInsnNode call && call.getOpcode() != Opcodes.INVOKESTATIC && !call.name.equals("<init>")) {
				Type type = Type.getMethodType(call.desc);
				int popped = 1;
				for (Type argument : type.getArgumentTypes()) {
					if (argument.getSort() < Type.ARRAY) return guards;    // only references reached from this
					popped++;
				}
				if (popped > depth) return guards;
				depth -= popped;
				if (type.getReturnType().getSort() == Type.VOID) {
					if (depth != 0 || !validator(callee.apply(call))) return guards;
					statement.add(insn);
					guards.add(statement);
					statement = new ArrayList<>();
					continue;
				}
				if (type.getReturnType().getSort() < Type.ARRAY) return guards;
				depth++;
			} else {
				return guards;
			}
			statement.add(insn);
		}
		return guards;
	}

	/** A method that can throw and writes no field: what a check before a state change looks like. */
	private static boolean validator(MethodNode method) {
		if (method == null || method.instructions.size() == 0) return false;
		boolean throwsHere = false;
		for (AbstractInsnNode insn : method.instructions) {
			if (insn.getOpcode() == Opcodes.PUTFIELD || insn.getOpcode() == Opcodes.PUTSTATIC) return false;
			throwsHere |= insn.getOpcode() == Opcodes.ATHROW;
		}
		return throwsHere;
	}

	private static void guardOpen(MethodNode method) {
		method.instructions.insert(new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, "beforeOpen", "()V", false));
	}

	private static int replaceFinal(MethodNode method, String owner, String name, String desc, String hook, String hookDesc) {
		int count = 0;
		for (var instruction : method.instructions.toArray()) {
			if (instruction instanceof MethodInsnNode call && call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(desc)) {
				method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, HOOK, hook, hookDesc, false));
				count++;
			}
		}
		return count;
	}

	private static MethodNode wrapper(String owner, String name, String desc, boolean neo, List<List<AbstractInsnNode>> guards) {
		MethodNode method = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_SYNTHETIC, name, desc, null, null);
		MethodVisitor v = method;
		v.visitCode();
		// Validate before beforeClose, so a caller closing the wrong nesting level cannot close its peer by accident.
		Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
		for (List<AbstractInsnNode> guard : guards) for (AbstractInsnNode insn : guard) method.instructions.add(insn.clone(labels));
		v.visitVarInsn(Opcodes.ALOAD, 0);
		v.visitVarInsn(neo ? Opcodes.ILOAD : Opcodes.ALOAD, 1);
		if (neo) { v.visitInsn(Opcodes.ICONST_1); v.visitInsn(Opcodes.IXOR); }
		else v.visitMethodInsn(Opcodes.INVOKEVIRTUAL, RESULT, "wasCommitted", "()Z", false);
		v.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "beforeClose", "(Ljava/lang/Object;Z)V", false);
		Label start = new Label(), end = new Label(), handler = new Label();
		v.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
		v.visitLabel(start);
		v.visitVarInsn(Opcodes.ALOAD, 0);
		v.visitVarInsn(neo ? Opcodes.ILOAD : Opcodes.ALOAD, 1);
		v.visitMethodInsn(Opcodes.INVOKESPECIAL, owner, "forbric$nativeTransferClose", desc, false);
		v.visitLabel(end);
		v.visitVarInsn(Opcodes.ALOAD, 0); v.visitInsn(Opcodes.ACONST_NULL);
		v.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "afterClose", "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false);
		v.visitInsn(Opcodes.RETURN);
		v.visitLabel(handler);
		v.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[]{"java/lang/Throwable"});
		v.visitVarInsn(Opcodes.ASTORE, 2);
		v.visitVarInsn(Opcodes.ALOAD, 0); v.visitVarInsn(Opcodes.ALOAD, 2);
		v.visitMethodInsn(Opcodes.INVOKESTATIC, HOOK, "afterClose", "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false);
		v.visitVarInsn(Opcodes.ALOAD, 2); v.visitInsn(Opcodes.ATHROW);
		v.visitMaxs(3, 3); v.visitEnd();
		return method;
	}

	private static MethodNode method(ClassNode node, String name, String desc) {
		for (MethodNode method : node.methods) if (method.name.equals(name) && method.desc.equals(desc)) return method;
		return null;
	}

	/** Whether {@code name} implements the public {@code Transaction}, through its interfaces or superclasses. */
	private static boolean implementsApi(Function<String, byte[]> classBytes, Map<String, ClassNode> classes, String name, int depth) {
		if (depth > 8 || platform(name)) return false;
		if (name.equals(FABRIC_API)) return depth > 0;
		ClassNode node = read(classBytes, classes, name);
		if (node == null || (node.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0 && depth == 0) return false;
		for (String parent : node.interfaces) if (implementsApi(classBytes, classes, parent, depth + 1)) return true;
		return node.superName != null && implementsApi(classBytes, classes, node.superName, depth + 1);
	}

	private static ClassNode read(Function<String, byte[]> classBytes, Map<String, ClassNode> classes, String name) {
		if (classes.containsKey(name)) return classes.get(name);
		ClassNode node = null;
		try {
			byte[] bytes = classBytes.apply(name);
			if (bytes != null) {
				node = new ClassNode();
				new ClassReader(bytes).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			}
		} catch (RuntimeException | LinkageError unreadable) {
			node = null;
		}
		classes.put(name, node);
		return node;
	}

	private static boolean platform(String name) {
		return name.startsWith("java/") || name.startsWith("javax/") || name.startsWith("jdk/") || name.startsWith("sun/") || name.startsWith("[");
	}

	private static String binary(String internal) {
		return internal.replace('/', '.');
	}
}
