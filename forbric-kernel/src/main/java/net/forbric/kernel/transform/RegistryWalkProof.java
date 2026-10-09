/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import java.util.*;
import java.util.function.Function;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;

/**
 * Proves, from data flow and control flow rather than from the compiled shape, that a method walks a platform registry
 * to its end and gives every element the same no-argument interface callback exactly once.
 *
 * <p>The mechanism this serves: the kernel registers Forge-family content in later waves than a Fabric instance does, so
 * a mod's "every element exists now" pass can run before the last wave. The per-element work that pass did is then
 * missing for every later element. The proof below is what makes that work safe to complete later for exactly the late
 * elements, whatever loop the mod wrote: a for-each or a hand-written iterator loop (any compiler's layout, any locals),
 * an index loop over {@code size()}/{@code byId}, or {@code forEach} with a lambda or method reference, directly or on the
 * registry's stream.
 *
 * <p>What is proved, and why each part is needed for the replay to be the mod's own work:
 * <ul>
 * <li>The walk reaches every element: an iterator loop leaves normally only when {@code hasNext()} is false; an index
 *     loop starts at 0, reads the element in every iteration before it steps by exactly 1, and leaves only when the
 *     index reaches the {@code size()} of the registry it reads (not a constant, a parameter, or another registry's
 *     size); {@code forEach} visits every element by contract. No exception handler covers the walk, so a failing
 *     element ends the method.</li>
 * <li>Each element is given each callback exactly once, unconditionally: the callback lies on every path from taking an
 *     element to the end of that iteration (in a consumer, on every path to its return) and on no inner cycle, and the
 *     element flows nowhere else (no condition, no argument, no field read). A conditional or repeated callback is the
 *     mod deciding per element, which a replay cannot reproduce; so is a condition that never reads the element, such
 *     as a static flag, around the callback or around the read itself, because the replay would run where the mod's
 *     walk did not.</li>
 * <li>A normal return of the method means every walk finished, or was skipped only because the element type cannot
 *     carry the callback interface at all; after a walk the method does nothing observable but further walks. The late
 *     replay runs after the method, so work ordered after the callbacks could not keep its order.</li>
 * </ul>
 *
 * <p>What is recognised, by platform facts only; anything else is left exactly as the mod wrote it:
 * <ul>
 * <li>The registry: a {@code net/minecraft/core} registry type read, in the walking method, straight from a static
 *     field of a platform class (Minecraft, NeoForge, MinecraftForge, Fabric), possibly through locals and casts. A
 *     registry the mod cached in a field of its own, or was handed as a parameter, is not recognised.</li>
 * <li>The callback: a call ON THE ELEMENT of a public no-argument member of a public interface (an
 *     {@code invokeinterface} whose receiver is the element, its result discarded), the way a mixin-added contract is
 *     called. The replay invokes exactly that member on each late element and nothing else, so a static helper the
 *     element is passed to ({@code Helper.init(element)}), a method of the element's own class
 *     ({@code invokevirtual}), or any argument beside the receiver is not recognised.</li>
 * <li>The index step: one {@code iinc} of 1 ({@code i++}, {@code ++i}, {@code i += 1}). {@code i = i + 1} compiles to
 *     a load, an add and a store instead, and is not recognised.</li>
 * <li>A walk nested in another (each block's states, inside a walk of the blocks) is not: the outer element flows into
 *     the inner walk, not only into a callback.</li>
 * </ul>
 */
final class RegistryWalkProof {
    private RegistryWalkProof() { }

    /** The static field a walk reads its registry from; the hooks read it again rather than keeping the value. */
    record Field(String owner, String name, String desc) {
        /** "block-state" for {@code BLOCK_STATE_REGISTRY}, "block" for {@code BLOCK}: what a log line calls it. */
        String label() {
            String base = name.endsWith("_REGISTRY") ? name.substring(0, name.length() - "_REGISTRY".length()) : name;
            return base.toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }
    /** One per-element callback. {@code call} is the instruction in the root method, or null when it runs inside a consumer. */
    record Callback(String contract, String member, String desc, MethodInsnNode call) { }
    enum Form { ITERATOR, INDEXED, FOR_EACH }
    /**
     * A proved walk. For ITERATOR the hooks go after {@code start} (the {@code iterator()} call), for INDEXED before it
     * (the index's initial 0), for FOR_EACH {@code start} is the {@code forEach} call whose consumer is wrapped.
     */
    record Walk(Form form, Field registry, AbstractInsnNode start, List<Callback> callbacks) { }
    /** Every walk of a root, and its normal returns, before each of which the batch is committed. */
    record Proof(List<Walk> walks, List<AbstractInsnNode> returns) { }

    static final Set<String> REGISTRY_TYPES = Set.of("Lnet/minecraft/core/IdMapper;", "Lnet/minecraft/core/IdMap;",
            "Lnet/minecraft/core/Registry;", "Lnet/minecraft/core/DefaultedRegistry;", "Lnet/minecraft/core/WritableRegistry;",
            "Lnet/minecraft/core/MappedRegistry;", "Lnet/minecraft/core/DefaultedMappedRegistry;");
    private static final List<String> PLATFORM = List.of("net/minecraft/", "net/neoforged/", "net/minecraftforge/", "net/fabricmc/");
    /** Calls that start a walk; a method naming none of them, or no registry field, is not analysed at all. */
    static final Set<String> WALK_CALLS = Set.of("iterator()Ljava/util/Iterator;", "forEach(Ljava/util/function/Consumer;)V",
            "forEachOrdered(Ljava/util/function/Consumer;)V", "byId(I)Ljava/lang/Object;", "byIdOrThrow(I)Ljava/lang/Object;");
    /** Reads of a registry that change nothing: what may sit between and after walks. */
    private static final Set<String> REGISTRY_QUERIES = Set.of("iterator()Ljava/util/Iterator;", "size()I",
            "byId(I)Ljava/lang/Object;", "byIdOrThrow(I)Ljava/lang/Object;", "stream()Ljava/util/stream/Stream;",
            "spliterator()Ljava/util/Spliterator;");
    private static final String METAFACTORY = "java/lang/invoke/LambdaMetafactory";
    private static final String CONSUMER = ")Ljava/util/function/Consumer;";

    // ---- entry -------------------------------------------------------------------------------------------------------

    static boolean candidate(MethodNode method) {
        if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNCHRONIZED)) != 0
                || method.name.equals("<init>") || method.instructions.size() == 0) return false;
        boolean registry = false, walk = false;
        for (AbstractInsnNode insn : method.instructions) {
            if (registryRead(insn)) registry = true;
            else if (insn instanceof MethodInsnNode call && WALK_CALLS.contains(call.name + call.desc)) walk = true;
            else if (insn.getOpcode() == Opcodes.MONITORENTER) return false;
        }
        return registry && walk;
    }

    /** The proof for {@code method}, or null when any part of it does not hold. */
    static Proof prove(ClassNode owner, MethodNode method, Function<String, ClassNode> declarations) {
        if (!candidate(method)) return null;
        Flow flow = Flow.of(owner.name, method);
        if (flow == null) return null;
        List<Facts> walks = new ArrayList<>();
        for (AbstractInsnNode insn : method.instructions) {
            if (!(insn instanceof MethodInsnNode call) || flow.frame(call) == null) continue;
            Facts facts = switch (call.name + call.desc) {
                case "iterator()Ljava/util/Iterator;" -> iterator(flow, call);
                case "byId(I)Ljava/lang/Object;", "byIdOrThrow(I)Ljava/lang/Object;" -> indexed(flow, call);
                case "forEach(Ljava/util/function/Consumer;)V", "forEachOrdered(Ljava/util/function/Consumer;)V" -> forEach(flow, owner, call);
                default -> null;
            };
            if (facts != null) walks.add(facts);
        }
        if (walks.isEmpty() || !rootHolds(flow, walks, declarations)) return null;
        List<Walk> proved = walks.stream().map(f -> new Walk(f.form, f.registry, f.start, f.callbacks)).toList();
        List<AbstractInsnNode> returns = new ArrayList<>();
        for (int i = 0; i < flow.insns.length; i++) if (flow.frames[i] != null && isReturn(flow.insns[i])) returns.add(flow.insns[i]);
        return returns.isEmpty() ? null : new Proof(proved, returns);
    }

    // ---- the three ways to take each element -------------------------------------------------------------------------

    /** What a walk was proved to be, plus the control-flow facts the method-level checks need. */
    private static final class Facts {
        Form form; Field registry; AbstractInsnNode start;
        List<Callback> callbacks = new ArrayList<>();
        Set<String> elementTypes = new HashSet<>();
        Set<Integer> loop = Set.of();
        /** Edges whose taking proves the walk ended: a loop's exit test failing, or {@code forEach} returning. */
        List<int[]> completions = new ArrayList<>();
        /** Edges that skip the walk because the element type cannot carry the callback interface. */
        List<int[]> guards = new ArrayList<>();
        int exit = -1;
    }

    private static Facts iterator(Flow flow, MethodInsnNode iterator) {
        Field registry = flow.registryOf(flow.operand(iterator, 0));
        if (registry == null) return null;
        MethodInsnNode next = null;
        List<Test> tests = new ArrayList<>();
        for (Use use : flow.uses(iterator)) {
            if (!flow.exactly(use.value, iterator)) return null;
            if (use.insn instanceof MethodInsnNode call && use.index == 0 && call.name.equals("next") && call.desc.equals("()Ljava/lang/Object;")) {
                if (next != null) return null;
                next = call;
            } else if (use.insn instanceof MethodInsnNode call && use.index == 0 && call.name.equals("hasNext") && call.desc.equals("()Z")) {
                Test test = hasNextTest(flow, call);
                if (test == null) return null;
                tests.add(test);
            } else if (!nullCheck(use)) return null;
        }
        if (next == null || tests.isEmpty()) return null;
        Loop loop = Loop.of(flow, tests);
        if (loop == null || !loop.enteredOnlyAfter(flow, flow.index(iterator))) return null;
        Facts facts = new Facts();
        facts.form = Form.ITERATOR; facts.registry = registry; facts.start = iterator;
        if (!perElement(flow, loop, next, facts)) return null;
        facts.loop = loop.nodes; facts.exit = loop.exit; facts.completions.addAll(loop.exits);
        return facts;
    }

    /** A {@code hasNext()} whose answer is used only by one branch: false leaves the walk. */
    private static Test hasNextTest(Flow flow, MethodInsnNode hasNext) {
        List<Use> uses = flow.uses(hasNext);
        if (uses.size() != 1 || !flow.exactly(uses.getFirst().value, hasNext) || !(uses.getFirst().insn instanceof JumpInsnNode jump)) return null;
        int at = flow.index(jump), taken = flow.index(jump.label), fall = at + 1;
        return switch (jump.getOpcode()) {
            case Opcodes.IFEQ -> new Test(at, fall, taken);
            case Opcodes.IFNE -> new Test(at, taken, fall);
            default -> null;
        };
    }

    private static Facts indexed(Flow flow, MethodInsnNode byId) {
        Field registry = flow.registryOf(flow.operand(byId, 0));
        if (registry == null) return null;
        // The index: every value byId reads is either the initial constant 0 or the one increment by 1 of that same variable.
        Set<AbstractInsnNode> index = flow.operand(byId, 1).insns;
        AbstractInsnNode zero = null;
        IincInsnNode step = null;
        for (AbstractInsnNode root : index) {
            if (root.getOpcode() == Opcodes.ICONST_0 && zero == null) zero = root;
            else if (root instanceof IincInsnNode inc && inc.incr == 1 && step == null) step = inc;
            else return null;
        }
        if (zero == null || step == null || !flow.operand(step, 0).insns.equals(index)) return null;
        List<Test> tests = new ArrayList<>();
        for (int i = 0; i < flow.insns.length; i++) {
            if (!(flow.insns[i] instanceof JumpInsnNode jump) || flow.frames[i] == null) continue;
            int opcode = jump.getOpcode();
            if (opcode < Opcodes.IF_ICMPEQ || opcode > Opcodes.IF_ICMPLE) continue;
            SourceValue left = flow.operand(jump, 0), right = flow.operand(jump, 1);
            boolean leftIndex = isIndex(left, zero, step), rightIndex = isIndex(right, zero, step);
            if (!leftIndex && !rightIndex) continue;
            if (leftIndex == rightIndex || !isSize(flow, leftIndex ? right : left, registry)) return null;
            // Which way the branch goes when "index < size" holds decides which successor continues the walk.
            int relation = leftIndex ? opcode : mirror(opcode);
            int at = flow.index(jump), taken = flow.index(jump.label), fall = at + 1;
            if (relation == Opcodes.IF_ICMPLT) tests.add(new Test(at, taken, fall));
            else if (relation == Opcodes.IF_ICMPGE) tests.add(new Test(at, fall, taken));
            else return null;
        }
        if (tests.isEmpty()) return null;
        Loop loop = Loop.of(flow, tests);
        int start = flow.index(zero), inc = flow.index(step), element = flow.index(byId);
        if (loop == null || loop.nodes.contains(start) || !loop.nodes.contains(inc) || !loop.nodes.contains(element)
                || !loop.enteredOnlyAfter(flow, start)) return null;
        // Exactly one step per element, after the element was read and before the exit test reads the index again. And no
        // step in an iteration that did not read the element: a condition around the read itself never sees the element,
        // so nothing per-element would notice that the step skipped one.
        if (loop.inInnerCycle(flow, inc) || loop.reachesIterationEnd(flow, element, inc)
                || loop.reachesWithinIteration(flow, inc, element, -1) || loop.reachesAcrossIterations(flow, inc, element, loop.testNodes())
                || loop.reachesWithinIteration(flow, loop.header, inc, element))
            return null;
        Facts facts = new Facts();
        facts.form = Form.INDEXED; facts.registry = registry; facts.start = zero;
        if (!perElement(flow, loop, byId, facts)) return null;
        facts.loop = loop.nodes; facts.exit = loop.exit; facts.completions.addAll(loop.exits);
        return facts;
    }

    private static boolean isIndex(SourceValue value, AbstractInsnNode zero, IincInsnNode step) {
        return !value.insns.isEmpty() && value.insns.stream().allMatch(root -> root == zero || root == step);
    }

    private static boolean isSize(Flow flow, SourceValue value, Field registry) {
        if (value.insns.isEmpty()) return false;
        for (AbstractInsnNode root : value.insns)
            if (!(root instanceof MethodInsnNode call && call.name.equals("size") && call.desc.equals("()I")
                    && registry.equals(flow.registryOf(flow.operand(call, 0))))) return false;
        return true;
    }

    private static int mirror(int opcode) {
        return switch (opcode) {
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGT; case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGE; case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLE;
            default -> opcode;
        };
    }

    private static Facts forEach(Flow flow, ClassNode owner, MethodInsnNode forEach) {
        Field registry = flow.registryOf(flow.operand(forEach, 0));
        if (registry == null) registry = registryStream(flow, flow.operand(forEach, 0));
        if (registry == null) return null;
        SourceValue consumer = flow.operand(forEach, 1);
        if (consumer.insns.size() != 1 || !(consumer.insns.iterator().next() instanceof InvokeDynamicInsnNode factory)
                || !factory.bsm.getOwner().equals(METAFACTORY) || !factory.bsm.getName().equals("metafactory")
                || !factory.name.equals("accept") || !factory.desc.endsWith(CONSUMER) || factory.bsmArgs.length < 3
                || !(factory.bsmArgs[1] instanceof Handle target) || !(factory.bsmArgs[2] instanceof Type instantiated)) return null;
        List<Use> uses = flow.uses(factory);
        if (uses.size() != 1 || uses.getFirst().insn != forEach) return null;
        Type[] parameters = instantiated.getArgumentTypes();
        if (parameters.length != 1 || parameters[0].getSort() != Type.OBJECT) return null;
        Facts facts = new Facts();
        facts.form = Form.FOR_EACH; facts.registry = registry; facts.start = forEach;
        facts.elementTypes.add(parameters[0].getInternalName());
        int captured = Type.getArgumentTypes(factory.desc).length;
        if (target.getTag() == Opcodes.H_INVOKEINTERFACE && target.getDesc().startsWith("()") && captured == 0) {
            // A method reference to the callback itself: the consumer is that callback and nothing else.
            facts.callbacks.add(new Callback(target.getOwner(), target.getName(), target.getDesc(), null));
        } else if ((target.getTag() == Opcodes.H_INVOKESTATIC || target.getTag() == Opcodes.H_INVOKESPECIAL
                || target.getTag() == Opcodes.H_INVOKEVIRTUAL) && target.getOwner().equals(owner.name)) {
            // A lambda (capturing or not) compiled into this class: the element is its last parameter.
            MethodNode body = owner.methods.stream().filter(m -> m.name.equals(target.getName()) && m.desc.equals(target.getDesc())).findFirst().orElse(null);
            boolean instance = target.getTag() != Opcodes.H_INVOKESTATIC;
            if (body == null || ((body.access & Opcodes.ACC_STATIC) == 0) != instance
                    || Type.getArgumentTypes(body.desc).length + (instance ? 1 : 0) != captured + 1
                    || !consumerBody(owner.name, body, facts)) return null;
        } else return null;
        int at = flow.index(forEach);
        if (at + 1 >= flow.insns.length || flow.frames[at + 1] == null) return null;
        facts.completions.add(new int[] {at, at + 1});
        facts.exit = flow.nextReal(at + 1);
        return facts;
    }

    /** {@code registry.stream()}, or {@code StreamSupport.stream(registry.spliterator(), false)}: sequential, unfiltered. */
    private static Field registryStream(Flow flow, SourceValue stream) {
        if (stream.insns.size() != 1 || !(stream.insns.iterator().next() instanceof MethodInsnNode call)) return null;
        if (call.name.equals("stream") && call.desc.equals("()Ljava/util/stream/Stream;")) return flow.registryOf(flow.operand(call, 0));
        if (call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals("java/util/stream/StreamSupport") && call.name.equals("stream")
                && call.desc.equals("(Ljava/util/Spliterator;Z)Ljava/util/stream/Stream;")) {
            SourceValue parallel = flow.operand(call, 1), source = flow.operand(call, 0);
            if (parallel.insns.size() != 1 || parallel.insns.iterator().next().getOpcode() != Opcodes.ICONST_0) return null;
            if (source.insns.size() != 1 || !(source.insns.iterator().next() instanceof MethodInsnNode spliterator)
                    || !spliterator.name.equals("spliterator") || !spliterator.desc.equals("()Ljava/util/Spliterator;")) return null;
            return flow.registryOf(flow.operand(spliterator, 0));
        }
        return null;
    }

    /**
     * A consumer compiled into the root's class: its last parameter is the element (any before it are what the lambda
     * captured), and every normal return passes each callback once.
     */
    private static boolean consumerBody(String owner, MethodNode body, Facts facts) {
        Type[] parameters = Type.getArgumentTypes(body.desc);
        if ((body.access & (Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || parameters.length == 0
                || parameters[parameters.length - 1].getSort() != Type.OBJECT || !body.tryCatchBlocks.isEmpty()) return false;
        for (AbstractInsnNode insn : body.instructions) if (insn.getOpcode() == Opcodes.MONITORENTER) return false;
        Flow flow = Flow.of(owner, body);
        if (flow == null) return false;
        int slot = (body.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < parameters.length - 1; i++) slot += parameters[i].getSize();
        AbstractInsnNode element = flow.parameter(slot);
        facts.elementTypes.add(parameters[parameters.length - 1].getInternalName());
        List<MethodInsnNode> callbacks = elementUses(flow, element, facts.elementTypes);
        if (callbacks == null || callbacks.isEmpty()) return false;
        for (MethodInsnNode call : callbacks) {
            int at = flow.index(call);
            if (flow.reaches(List.of(at), i -> i == at, Set.of(), true)) return false;
            if (flow.reaches(List.of(0), i -> isReturn(flow.insns[i]), Set.of(at), false)) return false;
            facts.callbacks.add(new Callback(call.owner, call.name, call.desc, null));
        }
        return true;
    }

    /** The element taken by {@code producer} reaches only its callbacks, each once per iteration and after it was taken. */
    private static boolean perElement(Flow flow, Loop loop, AbstractInsnNode producer, Facts facts) {
        int at = flow.index(producer);
        if (!loop.nodes.contains(at) || loop.inInnerCycle(flow, at)) return false;
        List<MethodInsnNode> callbacks = elementUses(flow, producer, facts.elementTypes);
        if (callbacks == null || callbacks.isEmpty()) return false;
        for (MethodInsnNode call : callbacks) {
            int c = flow.index(call);
            if (!loop.nodes.contains(c) || loop.inInnerCycle(flow, c) || loop.reachesIterationEnd(flow, at, c)
                    || loop.reachesWithinIteration(flow, loop.header, c, at)) return false;
            facts.callbacks.add(new Callback(call.owner, call.name, call.desc, call));
        }
        return true;
    }

    /**
     * The interface callbacks the element is given; null when it also flows anywhere else. Casts are recorded as the
     * element's types; a compiler's null check is not a use.
     */
    private static List<MethodInsnNode> elementUses(Flow flow, AbstractInsnNode element, Set<String> types) {
        List<MethodInsnNode> callbacks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Use use : flow.uses(element)) {
            if (!flow.exactly(use.value, element)) return null;
            if (use.insn.getOpcode() == Opcodes.CHECKCAST) { types.add(((TypeInsnNode) use.insn).desc); continue; }
            if (nullCheck(use)) continue;
            if (!(use.insn instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKEINTERFACE || !call.itf
                    || use.index != 0 || !call.desc.startsWith("()") || !flow.uses(call).isEmpty()
                    || !seen.add(call.owner + "#" + call.name)) return null;
            callbacks.add(call);
        }
        for (MethodInsnNode call : callbacks) types.remove(call.owner);
        return callbacks;
    }

    // ---- the root ----------------------------------------------------------------------------------------------------

    private static boolean rootHolds(Flow flow, List<Facts> walks, Function<String, ClassNode> declarations) {
        // Each callback once per registry, and nowhere else in the method: a second walk of the same registry with the same
        // member, or a call of it outside the walks, would be a second callback for some element.
        Set<String> signatures = new HashSet<>(), perRegistry = new HashSet<>();
        Set<MethodInsnNode> callbackInsns = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Facts walk : walks) for (Callback callback : walk.callbacks) {
            signatures.add(callback.contract + "#" + callback.member);
            if (!perRegistry.add(walk.registry + "#" + callback.contract + "#" + callback.member) || !accessible(callback, declarations)) return false;
            if (callback.call != null) callbackInsns.add(callback.call);
        }
        for (AbstractInsnNode insn : flow.insns)
            if (insn instanceof MethodInsnNode call && !callbackInsns.contains(call) && signatures.contains(call.owner + "#" + call.name)) return false;
        for (int i = 0; i < flow.insns.length; i++) if (flow.frames[i] != null && flow.insns[i] instanceof InvokeDynamicInsnNode indy
                && indy.bsmArgs.length > 1 && indy.bsmArgs[1] instanceof Handle handle && signatures.contains(handle.getOwner() + "#" + handle.getName())
                && walks.stream().noneMatch(w -> w.form == Form.FOR_EACH && flow.operand(w.start, 1).insns.contains(indy))) return false;
        // No handler covers a walk or anything after one: an exception from a callback must end the method.
        Set<Integer> after = new HashSet<>();
        for (Facts walk : walks) after.addAll(flow.closure(List.of(flow.index(walk.start)), (from, to) -> true));
        for (TryCatchBlockNode handler : flow.method.tryCatchBlocks)
            for (int i = flow.index(handler.start); i < flow.index(handler.end); i++) if (after.contains(i) && flow.insns[i].getOpcode() >= 0) return false;
        guards(flow, walks);
        for (Facts walk : walks) {
            // Every normal return passes this walk's completion, or its interface guard's "cannot carry it" branch.
            List<int[]> proving = new ArrayList<>(walk.completions);
            proving.addAll(walk.guards);
            if (flow.reachesWithout(List.of(0), i -> isReturn(flow.insns[i]), proving)) return false;
            // After the walk: only further walks and plumbing, and never this walk again.
            List<Integer> resume = new ArrayList<>();
            for (int[] edge : walk.completions) resume.add(edge[1]);
            Set<Integer> later = flow.closure(resume, (from, to) -> true);
            if (later.contains(flow.index(walk.start)) || walk.loop.stream().anyMatch(later::contains)) return false;
            for (int i : later) {
                AbstractInsnNode insn = flow.insns[i];
                if (insn.getOpcode() < 0 || plumbing(flow, insn)) continue;
                final int at = i;
                if (walks.stream().anyMatch(other -> other != walk && (other.loop.contains(at) || other.start == insn
                        || other.form == Form.FOR_EACH && flow.operand(other.start, 1).insns.contains(insn)))) continue;
                return false;
            }
        }
        return true;
    }

    /** {@code if (Contract.class.isAssignableFrom(Element.class))} around a walk whose only contract and element are those. */
    private static void guards(Flow flow, List<Facts> walks) {
        for (int i = 0; i < flow.insns.length; i++) {
            if (flow.frames[i] == null || !(flow.insns[i] instanceof JumpInsnNode jump)
                    || jump.getOpcode() != Opcodes.IFEQ && jump.getOpcode() != Opcodes.IFNE) continue;
            SourceValue tested = flow.operand(jump, 0);
            if (tested.insns.size() != 1 || !(tested.insns.iterator().next() instanceof MethodInsnNode test)
                    || !test.owner.equals("java/lang/Class") || !test.name.equals("isAssignableFrom")) continue;
            String contract = classConstant(flow.operand(test, 0)), element = classConstant(flow.operand(test, 1));
            if (contract == null || element == null) continue;
            int absent = jump.getOpcode() == Opcodes.IFEQ ? flow.index(jump.label) : i + 1;
            for (Facts walk : walks) {
                Set<String> contracts = new HashSet<>();
                for (Callback callback : walk.callbacks) contracts.add(callback.contract);
                if (contracts.equals(Set.of(contract)) && walk.elementTypes.contains(element) && flow.nextReal(absent) == walk.exit)
                    walk.guards.add(new int[] {i, absent});
            }
        }
    }

    private static String classConstant(SourceValue value) {
        return value.insns.size() == 1 && value.insns.iterator().next() instanceof LdcInsnNode ldc && ldc.cst instanceof Type type
                && type.getSort() == Type.OBJECT ? type.getInternalName() : null;
    }

    /** Instructions with no effect anyone else can observe: locals, the stack, constants, branches, registry reads. */
    private static boolean plumbing(Flow flow, AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        return switch (insn.getType()) {
            case AbstractInsnNode.VAR_INSN, AbstractInsnNode.IINC_INSN, AbstractInsnNode.LDC_INSN, AbstractInsnNode.JUMP_INSN,
                    AbstractInsnNode.TABLESWITCH_INSN, AbstractInsnNode.LOOKUPSWITCH_INSN -> true;
            case AbstractInsnNode.INT_INSN -> opcode != Opcodes.NEWARRAY;
            case AbstractInsnNode.TYPE_INSN -> opcode == Opcodes.CHECKCAST || opcode == Opcodes.INSTANCEOF;
            case AbstractInsnNode.FIELD_INSN -> opcode == Opcodes.GETSTATIC || opcode == Opcodes.GETFIELD;
            case AbstractInsnNode.INSN -> !(opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE)
                    && opcode != Opcodes.MONITORENTER && opcode != Opcodes.MONITOREXIT;
            case AbstractInsnNode.METHOD_INSN -> registryQuery(flow, (MethodInsnNode) insn) || nullCheck((MethodInsnNode) insn)
                    || ((MethodInsnNode) insn).owner.equals("java/lang/Class") && ((MethodInsnNode) insn).name.equals("isAssignableFrom");
            default -> false;
        };
    }

    private static boolean registryQuery(Flow flow, MethodInsnNode call) {
        String signature = call.name + call.desc;
        if (REGISTRY_QUERIES.contains(signature)) return flow.registryOf(flow.operand(call, 0)) != null;
        if (signature.equals("hasNext()Z") || signature.equals("next()Ljava/lang/Object;")) {
            Set<AbstractInsnNode> roots = flow.operand(call, 0).insns;
            return !roots.isEmpty() && roots.stream().allMatch(root -> root instanceof MethodInsnNode iterator
                    && iterator.name.equals("iterator") && flow.registryOf(flow.operand(iterator, 0)) != null);
        }
        return call.owner.equals("java/util/stream/StreamSupport") && registryStream(flow, new SourceValue(1, call)) != null;
    }

    private static boolean accessible(Callback callback, Function<String, ClassNode> declarations) {
        ClassNode contract = declarations.apply(callback.contract);
        if (contract == null || (contract.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE)) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE)) return false;
        Deque<ClassNode> pending = new ArrayDeque<>(List.of(contract));
        Set<String> visited = new HashSet<>();
        while (!pending.isEmpty()) {
            ClassNode type = pending.poll();
            if (!visited.add(type.name) || (type.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE)) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE)) continue;
            for (MethodNode member : type.methods)
                if (member.name.equals(callback.member) && member.desc.equals(callback.desc))
                    return (member.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC)) == Opcodes.ACC_PUBLIC;
            for (String parent : type.interfaces) { ClassNode declared = declarations.apply(parent); if (declared != null) pending.add(declared); }
        }
        return false;
    }

    // ---- shared predicates -------------------------------------------------------------------------------------------

    static boolean registryRead(AbstractInsnNode insn) {
        return insn instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETSTATIC && REGISTRY_TYPES.contains(field.desc)
                && PLATFORM.stream().anyMatch(field.owner::startsWith);
    }

    private static boolean isReturn(AbstractInsnNode insn) {
        return insn.getOpcode() >= Opcodes.IRETURN && insn.getOpcode() <= Opcodes.RETURN;
    }

    /** A null check a compiler inserts: it observes only whether the value is null, and throws if it is. */
    private static boolean nullCheck(Use use) {
        return use.insn instanceof MethodInsnNode call && nullCheck(call);
    }

    private static boolean nullCheck(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKESTATIC && (call.owner.equals("java/util/Objects") && call.name.equals("requireNonNull")
                && call.desc.startsWith("(Ljava/lang/Object;") && call.desc.endsWith(")Ljava/lang/Object;")
                || call.owner.equals("kotlin/jvm/internal/Intrinsics") && call.name.startsWith("checkNotNull") && call.desc.endsWith(")V"));
    }

    // ---- loops -------------------------------------------------------------------------------------------------------

    /** A branch that ends the walk: {@code cont} keeps walking, {@code exit} is the walk finished. */
    private record Test(int at, int cont, int exit) { }

    /**
     * The one cycle the exit tests of a walk sit on, entered at one place and left only through those tests. Tests outside
     * it are a compiler's "is it empty" pre-check: they must lead straight into it, and leave to the same place.
     */
    private static final class Loop {
        final Set<Integer> nodes; final int header; final int exit; final List<int[]> exits = new ArrayList<>();
        final Map<Integer, Integer> inLoopExits = new HashMap<>();
        private Loop(Set<Integer> nodes, int header, int exit) { this.nodes = nodes; this.header = header; this.exit = exit; }

        static Loop of(Flow flow, List<Test> tests) {
            int exit = -1;
            Set<Integer> cycle = null;
            for (Test test : tests) {
                int to = flow.nextReal(test.exit);
                if (exit != -1 && exit != to) return null;
                exit = to;
                Set<Integer> scc = flow.cycleThrough(test.at);
                if (scc.isEmpty()) continue;
                if (cycle != null && !cycle.equals(scc)) return null;
                cycle = scc;
            }
            if (cycle == null) return null;
            Integer header = null;
            for (int node : cycle) for (int from : flow.pred.get(node)) if (!cycle.contains(from)) {
                if (header != null && header != node) return null;
                header = node;
            }
            if (header == null) return null;
            Loop loop = new Loop(cycle, header, exit);
            for (Test test : tests) {
                if (cycle.contains(test.exit)) return null;
                if (cycle.contains(test.at)) {
                    if (!cycle.contains(test.cont)) return null;
                    loop.inLoopExits.put(test.at, test.exit);
                } else if (flow.nextReal(test.cont) != flow.nextReal(header)) return null;
                loop.exits.add(new int[] {test.at, test.exit});
            }
            // Left only through an exit test; never returned from.
            for (int node : cycle) {
                if (isReturn(flow.insns[node])) return null;
                for (int to : flow.succ.get(node))
                    if (!cycle.contains(to) && !Objects.equals(loop.inLoopExits.get(node), to)) return null;
            }
            return loop;
        }

        Set<Integer> testNodes() { return inLoopExits.keySet(); }

        /** The walk's start runs once, outside the loop, and every way into the loop and its tests passes it. */
        boolean enteredOnlyAfter(Flow flow, int start) {
            if (nodes.contains(start)) return false;
            Set<Integer> ends = new HashSet<>(nodes);
            for (int[] edge : exits) ends.add(edge[0]);
            return !flow.reaches(List.of(0), ends::contains, Set.of(start), false);
        }

        /** Within one iteration (never back through the header), from {@code from}'s successors, is {@code to} reached? */
        boolean reachesWithinIteration(Flow flow, int from, int to, int avoid) {
            Deque<Integer> pending = new ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            if (from == avoid) return false;
            if (from == header) { if (from == to) return true; pending.add(from); seen.add(from); }
            else for (int next : flow.succ.get(from)) if (next != header && nodes.contains(next) && next != avoid && seen.add(next)) pending.add(next);
            while (!pending.isEmpty()) {
                int node = pending.poll();
                if (node == to) return true;
                for (int next : flow.succ.get(node)) if (next != header && nodes.contains(next) && next != avoid && seen.add(next)) pending.add(next);
            }
            return false;
        }

        /** A path from {@code from} that ends the iteration (back to the header, or out through an exit test) avoiding {@code avoid}. */
        boolean reachesIterationEnd(Flow flow, int from, int avoid) {
            Deque<Integer> pending = new ArrayDeque<>(List.of(from));
            Set<Integer> seen = new HashSet<>(List.of(from));
            while (!pending.isEmpty()) {
                int node = pending.poll();
                for (int next : flow.succ.get(node)) {
                    if (next == header || Objects.equals(inLoopExits.get(node), next)) return true;
                    if (nodes.contains(next) && next != avoid && seen.add(next)) pending.add(next);
                }
            }
            return false;
        }

        /** Can {@code node} run twice in one iteration? */
        boolean inInnerCycle(Flow flow, int node) {
            return node != header && reachesWithinIteration(flow, node, node, -1);
        }

        /** From {@code from}, possibly through the next iteration's header, is {@code to} reached without any of {@code avoid}? */
        boolean reachesAcrossIterations(Flow flow, int from, int to, Set<Integer> avoid) {
            return flow.search(flow.succ.get(from), i -> i == to, avoid, List.of(), nodes);
        }
    }

    // ---- data flow and control flow of one method -------------------------------------------------------------------

    /** One consumption of a value: {@code index} is its operand position, the receiver first. */
    private record Use(AbstractInsnNode insn, int index, SourceValue value) { }

    /**
     * {@link SourceInterpreter} with copies made transparent: a value's {@code insns} are the instructions that produced it,
     * through locals, stack shuffles, casts and {@code requireNonNull}, merged at joins. Parameters and unset locals carry
     * marker roots of their own, so a value that might be stale or uninitialised on some path never looks like an element.
     */
    private static class Roots extends SourceInterpreter {
        static final AbstractInsnNode UNSET = new InsnNode(Opcodes.NOP), CAUGHT = new InsnNode(Opcodes.NOP);
        final Map<Integer, AbstractInsnNode> parameters = new HashMap<>();
        Roots() { super(Opcodes.ASM9); }
        @Override public SourceValue newParameterValue(boolean instance, int local, Type type) {
            return new SourceValue(type.getSize(), parameters.computeIfAbsent(local, ignored -> new InsnNode(Opcodes.NOP)));
        }
        @Override public SourceValue newEmptyValue(int local) { return new SourceValue(1, UNSET); }
        @Override public SourceValue newExceptionValue(TryCatchBlockNode handler, Frame<SourceValue> frame, Type type) { return new SourceValue(1, CAUGHT); }
        @Override public SourceValue copyOperation(AbstractInsnNode insn, SourceValue value) { return new SourceValue(value.getSize(), value.insns); }
        @Override public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue value) {
            return insn.getOpcode() == Opcodes.CHECKCAST ? new SourceValue(1, value.insns) : super.unaryOperation(insn, value);
        }
        @Override public SourceValue naryOperation(AbstractInsnNode insn, List<? extends SourceValue> values) {
            if (insn instanceof MethodInsnNode call && call.owner.equals("java/util/Objects") && nullCheck(call)) return new SourceValue(1, values.getFirst().insns);
            return super.naryOperation(insn, values);
        }
    }

    /** Records what each instruction consumes, by re-executing it on the analysed frame. */
    private static final class Recorder extends Roots {
        final Map<AbstractInsnNode, List<SourceValue>> operands;
        AbstractInsnNode current;
        Recorder(Map<AbstractInsnNode, List<SourceValue>> operands) { this.operands = operands; }
        @Override public SourceValue unaryOperation(AbstractInsnNode insn, SourceValue value) { operands.put(current, List.of(value)); return super.unaryOperation(insn, value); }
        @Override public SourceValue binaryOperation(AbstractInsnNode insn, SourceValue a, SourceValue b) { operands.put(current, List.of(a, b)); return super.binaryOperation(insn, a, b); }
        @Override public SourceValue ternaryOperation(AbstractInsnNode insn, SourceValue a, SourceValue b, SourceValue c) { operands.put(current, List.of(a, b, c)); return super.ternaryOperation(insn, a, b, c); }
        @Override public SourceValue naryOperation(AbstractInsnNode insn, List<? extends SourceValue> values) { operands.put(current, List.copyOf(values)); return super.naryOperation(insn, values); }
        @Override public void returnOperation(AbstractInsnNode insn, SourceValue value, SourceValue expected) { operands.put(current, List.of(value)); }
    }

    private static final class Flow {
        final MethodNode method; final AbstractInsnNode[] insns; final Frame<SourceValue>[] frames;
        final List<Set<Integer>> succ = new ArrayList<>(), pred = new ArrayList<>();
        final Map<AbstractInsnNode, List<SourceValue>> operands = new IdentityHashMap<>();
        final Map<AbstractInsnNode, List<Use>> uses = new IdentityHashMap<>();
        final Map<Integer, AbstractInsnNode> parameters;

        private Flow(MethodNode method, Frame<SourceValue>[] frames, Map<Integer, AbstractInsnNode> parameters) {
            this.method = method; this.insns = method.instructions.toArray(); this.frames = frames; this.parameters = parameters;
        }

        static Flow of(String owner, MethodNode method) {
            int size = method.instructions.size();
            List<Set<Integer>> succ = new ArrayList<>(), pred = new ArrayList<>();
            for (int i = 0; i < size; i++) { succ.add(new LinkedHashSet<>()); pred.add(new LinkedHashSet<>()); }
            Roots roots = new Roots();
            Analyzer<SourceValue> analyzer = new Analyzer<>(roots) {
                @Override protected void newControlFlowEdge(int from, int to) { succ.get(from).add(to); pred.get(to).add(from); }
                @Override protected boolean newControlFlowExceptionEdge(int from, int to) { succ.get(from).add(to); pred.get(to).add(from); return true; }
            };
            Frame<SourceValue>[] frames;
            try { frames = analyzer.analyze(owner, method); } catch (AnalyzerException | RuntimeException unanalysable) { return null; }
            Flow flow = new Flow(method, frames, roots.parameters);
            flow.succ.addAll(succ); flow.pred.addAll(pred);
            Recorder recorder = new Recorder(flow.operands);
            for (int i = 0; i < size; i++) {
                AbstractInsnNode insn = flow.insns[i];
                if (frames[i] == null || insn.getOpcode() < 0) continue;
                recorder.current = insn;
                try { new Frame<>(frames[i]).execute(insn, recorder); } catch (AnalyzerException | RuntimeException unanalysable) { return null; }
            }
            for (var entry : flow.operands.entrySet()) {
                List<SourceValue> values = entry.getValue();
                for (int k = 0; k < values.size(); k++)
                    for (AbstractInsnNode root : values.get(k).insns)
                        flow.uses.computeIfAbsent(root, ignored -> new ArrayList<>()).add(new Use(entry.getKey(), k, values.get(k)));
            }
            return flow;
        }

        int index(AbstractInsnNode insn) { return method.instructions.indexOf(insn); }
        Frame<SourceValue> frame(AbstractInsnNode insn) { return frames[index(insn)]; }
        SourceValue operand(AbstractInsnNode insn, int k) {
            List<SourceValue> values = operands.get(insn);
            return values == null || k >= values.size() ? new SourceValue(1, Roots.UNSET) : values.get(k);
        }
        List<Use> uses(AbstractInsnNode root) { return uses.getOrDefault(root, List.of()); }
        AbstractInsnNode parameter(int local) { return parameters.get(local); }
        boolean exactly(SourceValue value, AbstractInsnNode root) { return value.insns.size() == 1 && value.insns.contains(root); }

        Field registryOf(SourceValue value) {
            Field field = null;
            if (value.insns.isEmpty()) return null;
            for (AbstractInsnNode root : value.insns) {
                if (!registryRead(root)) return null;
                FieldInsnNode read = (FieldInsnNode) root;
                Field this_ = new Field(read.owner, read.name, read.desc);
                if (field != null && !field.equals(this_)) return null;
                field = this_;
            }
            return field;
        }

        int nextReal(int at) {
            while (at < insns.length && insns[at].getOpcode() < 0) at++;
            return at;
        }

        /** Every node on a cycle through {@code node}; empty when none passes it. */
        Set<Integer> cycleThrough(int node) {
            Set<Integer> forward = closure(succ.get(node), (from, to) -> true);
            if (!forward.contains(node)) return Set.of();
            Set<Integer> backward = new HashSet<>();
            Deque<Integer> pending = new ArrayDeque<>(List.of(node));
            backward.add(node);
            while (!pending.isEmpty()) for (int from : pred.get(pending.poll())) if (backward.add(from)) pending.add(from);
            forward.retainAll(backward);
            return forward;
        }

        Set<Integer> closure(Collection<Integer> starts, java.util.function.BiPredicate<Integer, Integer> follow) {
            Set<Integer> seen = new HashSet<>(starts);
            Deque<Integer> pending = new ArrayDeque<>(starts);
            while (!pending.isEmpty()) {
                int node = pending.poll();
                for (int next : succ.get(node)) if (follow.test(node, next) && seen.add(next)) pending.add(next);
            }
            return seen;
        }

        /** From {@code starts} (or their successors), never entering {@code avoid}: is any {@code target} node reached? */
        boolean reaches(Collection<Integer> starts, java.util.function.IntPredicate target, Set<Integer> avoid, boolean fromSuccessors) {
            return search(fromSuccessors ? successors(starts) : starts, target, avoid, List.of(), null);
        }

        /** As {@link #reaches}, never taking one of the {@code cut} edges. */
        boolean reachesWithout(Collection<Integer> starts, java.util.function.IntPredicate target, List<int[]> cut) {
            return search(starts, target, Set.of(), cut, null);
        }

        private List<Integer> successors(Collection<Integer> nodes) {
            List<Integer> result = new ArrayList<>();
            for (int node : nodes) result.addAll(succ.get(node));
            return result;
        }

        /** Breadth-first from {@code starts}, never entering {@code avoid}, never taking a {@code cut} edge, staying inside {@code within}. */
        boolean search(Collection<Integer> starts, java.util.function.IntPredicate target, Set<Integer> avoid, List<int[]> cut,
                Set<Integer> within) {
            Deque<Integer> pending = new ArrayDeque<>();
            Set<Integer> seen = new HashSet<>();
            for (int start : starts) if (!avoid.contains(start) && (within == null || within.contains(start)) && seen.add(start)) pending.add(start);
            while (!pending.isEmpty()) {
                int node = pending.poll();
                if (target.test(node)) return true;
                next: for (int next : succ.get(node)) {
                    if (avoid.contains(next) || within != null && !within.contains(next) || seen.contains(next)) continue;
                    for (int[] edge : cut) if (edge[0] == node && edge[1] == next) continue next;
                    seen.add(next);
                    pending.add(next);
                }
            }
            return false;
        }
    }
}
