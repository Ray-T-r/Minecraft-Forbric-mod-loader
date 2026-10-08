/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import java.util.*;
import net.forbric.api.Ecosystem;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** A call may follow an extracted hook only when it is still the hook's last effect, with the same inputs. */
public final class MergedBaseAbsorbedCalls {
    public record Absorbed(String owner, String method, String member, String hook, Set<Ecosystem> ecosystems, String because) { }
    public static final String PROPERTY = "forbric.mixinAbsorbedCall";
    private MergedBaseAbsorbedCalls() { }
    static boolean enabled() { return !"off".equalsIgnoreCase(System.getProperty(PROPERTY, "on")); }

    static Absorbed find(String owner, String signature, String anchor, Ecosystem family) {
        if (!enabled() || family == null) return null;
        ClassNode source = MergedBaseCalleeSwaps.source(family, owner), current = MergedBaseCalleeSwaps.current(owner);
        MethodNode original = NativeCallChanges.method(source, signature), live = NativeCallChanges.method(current, signature);
        MixinFit.Member wanted = MixinFit.parseMember(anchor);
        if (original == null || live == null || wanted == null || MixinFit.containsMember(live, anchor)
                || !CarrierHelpers.edges(original, anchor).contains(CarrierHelpers.Shape.TAIL)) return null;
        List<NativeCallChanges.Site> old = NativeCallChanges.sites(source, original).stream().filter(s -> matches(s.call(), wanted)).toList();
        if (old.size() != 1) return null;
        Absorbed found = null;
        for (NativeCallChanges.Site site : NativeCallChanges.sites(current, live)) {
            MethodInsnNode call = site.call();
            if (call.getOpcode() != Opcodes.INVOKESTATIC || !CarrierHelpers.edges(live, NativeCallChanges.member(call)).contains(CarrierHelpers.Shape.TAIL)) continue;
            ClassNode helper = MergedBaseCalleeSwaps.current(call.owner);
            MethodNode body = NativeCallChanges.method(helper, call.name + call.desc);
            if (body == null || (body.access & Opcodes.ACC_STATIC) == 0 || !body.tryCatchBlocks.isEmpty()
                    || !CarrierHelpers.edges(body, anchor).contains(CarrierHelpers.Shape.TAIL)) continue;
            List<NativeCallChanges.Site> inside = NativeCallChanges.sites(helper, body).stream().filter(s -> matches(s.call(), wanted)).toList();
            if (inside.size() != 1 || !inside.getFirst().guards().isEmpty()) continue;
            Map<String, NativeCallChanges.Expr> parameters = new HashMap<>();
            Type[] args = Type.getArgumentTypes(call.desc); int slot = 0;
            for (int i = 0; i < args.length; i++) { parameters.put(Integer.toString(slot), site.operands().get(i)); slot += args[i].getSize(); }
            List<NativeCallChanges.Expr> projected = inside.getFirst().operands().stream().map(e -> substitute(e, parameters)).toList();
            if (!projected.equals(old.getFirst().operands()) || !Objects.equals(old.getFirst().guards(), site.guards())) continue;
            Absorbed candidate = new Absorbed(owner, signature, NativeCallChanges.member(old.getFirst().call()), NativeCallChanges.member(call), Set.of(family),
                    "the source's unique final call remains the hook's unconditional final effect, with every original operand preserved");
            if (found != null && !found.equals(candidate)) return null;
            found = candidate;
        }
        return found;
    }
    private static NativeCallChanges.Expr substitute(NativeCallChanges.Expr expr, Map<String, NativeCallChanges.Expr> parameters) {
        if (expr.kind().equals("parameter")) return parameters.getOrDefault(expr.symbol(), new NativeCallChanges.Expr("unmapped", expr.symbol()));
        return new NativeCallChanges.Expr(expr.kind(), expr.symbol(), expr.inputs().stream().map(e -> substitute(e, parameters)).toList());
    }
    private static boolean matches(MethodInsnNode call, MixinFit.Member member) {
        return call.name.equals(member.name()) && (member.owner() == null || call.owner.equals(member.owner())) && (member.desc() == null || call.desc.equals(member.desc()));
    }
}
