/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.classloading;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.forbric.api.AncestorComposition;

/** Per-loader requirements and final-definition proofs; unknown stateful protocols cannot disappear silently. */
final class RequiredAncestorCompositions {
    static final String RESOURCE="META-INF/forbric/required-ancestor-compositions.tsv";
    private final List<AncestorComposition> proofs=new CopyOnWriteArrayList<>();
    private volatile Map<String,List<AncestorComposition.Requirement>> requirements;
    void register(AncestorComposition proof){proofs.add(Objects.requireNonNull(proof));}
    void verify(String name,byte[] definition,Function<String,byte[]> resources){
        Map<String,List<AncestorComposition.Requirement>> indexed=requirements;
        if(indexed==null)synchronized(this){if(requirements==null)requirements=parse(resources.apply(RESOURCE));indexed=requirements;}
        for(var requirement:indexed.getOrDefault(name.replace('.','/'),List.of())){
            if(proofs.stream().noneMatch(proof->proof.proves(requirement,definition,resources)))
                throw new LinkageError("Unresolved stateful ancestor composition for "+requirement.owner()+": retained "+requirement.retainedSuperclass()+", removed "+requirement.sourceSuperclass()+". No protocol proved the final definition; rebuild the base or register an AncestorComposition.");
        }
    }
    static Map<String,List<AncestorComposition.Requirement>> parse(byte[] bytes){
        if(bytes==null)return Map.of();String[] lines=new String(bytes,StandardCharsets.UTF_8).split("\\R");
        if(lines.length==0||!lines[0].equals("# forbric-required-ancestor-composition-v1"))throw new LinkageError("Invalid ancestor-composition manifest header");
        Map<String,List<AncestorComposition.Requirement>> out=new LinkedHashMap<>();Set<AncestorComposition.Requirement> seen=new HashSet<>();
        for(int i=1;i<lines.length;i++){
            if(lines[i].isBlank())continue;String[] p=lines[i].split("\\t",-1);
            if(p.length!=3||Arrays.stream(p).anyMatch(s->!internalName(s))||p[1].equals(p[2]))throw new LinkageError("Invalid ancestor-composition manifest row "+(i+1));
            var r=new AncestorComposition.Requirement(p[0],p[1],p[2]);if(!seen.add(r))throw new LinkageError("Duplicate ancestor-composition requirement "+r);
            out.computeIfAbsent(p[0],unused->new ArrayList<>()).add(r);
        }
        Map<String,List<AncestorComposition.Requirement>> frozen=new LinkedHashMap<>();out.forEach((owner,list)->frozen.put(owner,List.copyOf(list)));return Map.copyOf(frozen);
    }
    private static boolean internalName(String name){
        return !name.isEmpty()&&!name.startsWith("/")&&!name.endsWith("/")&&!name.contains("//")
            &&name.codePoints().noneMatch(c->c=='.'||c==';'||c=='['||Character.isWhitespace(c)||Character.isISOControl(c));
    }
}
