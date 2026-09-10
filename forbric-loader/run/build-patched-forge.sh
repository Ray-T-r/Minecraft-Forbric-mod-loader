#!/usr/bin/env bash
# Produce the traditional-MinecraftForge-patched, Mojmap-named Minecraft 26.2 game jar that Forbric loads under
# Knot (the NFRT analog for traditional Forge — Forge uses BinaryPatcher + MCPConfig, NOT NeoFormRuntime).
# The result (Forge-injected vanilla, e.g. Level implements net.minecraftforge.common.extensions.IForgeLevel) is
# runtime-supplied like the user's MC jar + the Forge runtime — never committed into the Apache-2.0 loader source.
#
# Pipeline (all verified end-to-end):
#   1. BUNDLER_EXTRACT the Mojang server jar; take the client jar from the user's MC install.
#   2. mergetool --ann API  (REQUIRED: Forge's binpatches target the @OnlyIn-annotated merge output).
#   3. binarypatcher --apply joined.lzma  (joined.lzma + ats extracted from the Forge -userdev jar).
#   4. overlay the patched classes onto the clean merge + strip the Mojang jar signature (re-serialized classes
#      would fail SHA verification otherwise).
#   5. apply Forge's access transformers (ats/accesstransformer.cfg) via Forge's own AccessTransformerEngine
#      (handles InnerClasses-attribute widening correctly — a naive ASM applier writes invalid class files).
#   6. inject a concrete covariant self() into every MC class that directly implements a Forge extension interface
#      exposing a PUBLIC self() (IForgeLivingEntity/LivingEntity) — the interface default does not resolve on
#      subclasses under Forbric's flat Knot classloader (ServerPlayer AbstractMethodError on a world tick).
#
# Usage: [FORGE_VERSION=26.2-65.0.1] [MC_VER=26.2] ./build-patched-forge.sh
set -euo pipefail

FORGE_VERSION="${FORGE_VERSION:-26.2-65.0.1}"
MC_VER="${MC_VER:-26.2}"
HERE="$(cd "$(dirname "$0")" && pwd)"
MC="${MC_DIR:-$HOME/Library/Application Support/minecraft}"
WORK="$HERE/forge-patched/work"
OUT="$HERE/forge-patched/patched-mc-forge-$MC_VER.jar"
export JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home 2>/dev/null)}"
J="$JAVA_HOME/bin/java"; JC="$JAVA_HOME/bin/javac"
FORGEMVN=https://maven.minecraftforge.net
CENTRAL=https://repo1.maven.org/maven2

if [ -f "$OUT" ]; then echo "[patched] up-to-date: $OUT"; exit 0; fi
mkdir -p "$WORK/dl" "$WORK/at"

get() { local url="$1" out="$2"; [ -f "$out" ] && return 0
  local code; code=$(curl -sS -L -o "$out" -w "%{http_code}" "$url")
  [ "$code" = "200" ] && [ -s "$out" ] || { echo "  FAIL($code) $url" >&2; rm -f "$out"; return 1; }; }

echo "[patched] fetching tools + Forge userdev"
get "$FORGEMVN/net/minecraftforge/mergetool/1.2.5/mergetool-1.2.5-fatjar.jar"          "$WORK/dl/mergetool.jar"
get "$FORGEMVN/net/minecraftforge/installertools/1.3.2/installertools-1.3.2-fatjar.jar" "$WORK/dl/installertools.jar"
get "$FORGEMVN/net/minecraftforge/binarypatcher/1.3.0/binarypatcher-1.3.0-fatjar.jar"  "$WORK/dl/binarypatcher.jar"
get "$FORGEMVN/net/minecraftforge/accesstransformers/8.2.2/accesstransformers-8.2.2.jar" "$WORK/dl/accesstransformers.jar"
for a in asm asm-tree asm-commons asm-util asm-analysis; do
  get "$CENTRAL/org/ow2/asm/$a/9.9.1/$a-9.9.1.jar" "$WORK/dl/$a.jar"; done
get "$CENTRAL/org/apache/logging/log4j/log4j-api/2.24.3/log4j-api-2.24.3.jar"   "$WORK/dl/log4j-api.jar"
get "$CENTRAL/org/apache/logging/log4j/log4j-core/2.24.3/log4j-core-2.24.3.jar" "$WORK/dl/log4j-core.jar"
get "$FORGEMVN/net/minecraftforge/forge/$FORGE_VERSION/forge-$FORGE_VERSION-userdev.jar" "$WORK/dl/forge-userdev.jar"
get "$FORGEMVN/net/minecraftforge/forge/$FORGE_VERSION/forge-$FORGE_VERSION-universal.jar" "$WORK/dl/forge-universal.jar"  # IForge* interfaces for the self() scan

CLIENT="$MC/versions/$MC_VER/$MC_VER.jar"
[ -f "$CLIENT" ] || { echo "client jar not found: $CLIENT" >&2; exit 2; }
SERVER_URL="$(python3 -c "import json,os;d=json.load(open(os.path.join('$MC','versions','$MC_VER','$MC_VER.json')));print(d['downloads']['server']['url'])")"
get "$SERVER_URL" "$WORK/dl/server.jar"

echo "[patched] extract server + merge (--ann API) + extract patches"
"$J" -jar "$WORK/dl/installertools.jar" --task BUNDLER_EXTRACT --input "$WORK/dl/server.jar" --output "$WORK/server-main.jar" --jar-only
"$J" -jar "$WORK/dl/mergetool.jar" --merge --client "$CLIENT" --server "$WORK/server-main.jar" \
  --output "$WORK/clean.jar" --keep-data --keep-meta --ann API
unzip -o -q "$WORK/dl/forge-userdev.jar" joined.lzma ats/accesstransformer.cfg -d "$WORK"

echo "[patched] binarypatcher --apply joined.lzma"
"$J" -jar "$WORK/dl/binarypatcher.jar" --clean "$WORK/clean.jar" --output "$WORK/patched-subset.jar" --apply "$WORK/joined.lzma" >/dev/null

echo "[patched] overlay patched classes onto clean + strip signatures"
CLEAN="$WORK/clean.jar" PATCHED="$WORK/patched-subset.jar" FULL="$WORK/patched-full.jar" python3 - <<'PY'
import os, zipfile
clean, patched, full = os.environ["CLEAN"], os.environ["PATCHED"], os.environ["FULL"]
pe = {}
with zipfile.ZipFile(patched) as z:
    for n in z.namelist():
        if not n.endswith('/'): pe[n] = z.read(n)
def is_sig(n):
    u=n.upper(); return u.startswith('META-INF/') and u.rsplit('.',1)[-1] in ('SF','RSA','DSA','EC')
with zipfile.ZipFile(clean) as zin, zipfile.ZipFile(full,'w',zipfile.ZIP_DEFLATED) as zout:
    seen=set()
    for info in zin.infolist():
        n=info.filename
        if n.endswith('/') or is_sig(n): continue
        if n=='META-INF/MANIFEST.MF':
            raw=zin.read(n).decode('utf-8','replace'); main=raw.split('\r\n\r\n',1)[0].split('\n\n',1)[0]
            zout.writestr(n, main.rstrip()+'\r\n'); seen.add(n); continue
        zout.writestr(n, pe.get(n) or zin.read(n)); seen.add(n)
    for n,data in pe.items():
        if n not in seen: zout.writestr(n, data)
print("  overlay done")
PY

echo "[patched] apply access transformers (Forge AccessTransformerEngine)"
cat > "$WORK/at/ApplyATForge.java" <<'JAVA'
import net.minecraftforge.accesstransformer.AccessTransformerEngine;
import net.minecraftforge.accesstransformer.IdentityNameHandler;
import org.objectweb.asm.*; import org.objectweb.asm.tree.ClassNode;
import java.io.BufferedOutputStream; import java.nio.file.*; import java.util.Enumeration; import java.util.zip.*;
public class ApplyATForge {
  public static void main(String[] a) throws Exception {
    AccessTransformerEngine eng = AccessTransformerEngine.INSTANCE;
    eng.acceptNaming(new IdentityNameHandler()); eng.addResource(Path.of(a[0]), "forge_at");
    try (ZipFile zf=new ZipFile(a[1]); ZipOutputStream zos=new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(a[2]))))) {
      Enumeration<? extends ZipEntry> e=zf.entries();
      while (e.hasMoreElements()) { ZipEntry ze=e.nextElement(); if (ze.isDirectory()) continue;
        String n=ze.getName(); byte[] d=zf.getInputStream(ze).readAllBytes();
        if (n.endsWith(".class")) { Type t=Type.getObjectType(n.substring(0,n.length()-6));
          if (eng.handlesClass(t)) { ClassNode cn=new ClassNode(); new ClassReader(d).accept(cn,0); eng.transform(cn,t);
            ClassWriter cw=new ClassWriter(0); cn.accept(cw); d=cw.toByteArray(); } }
        zos.putNextEntry(new ZipEntry(n)); zos.write(d); zos.closeEntry(); } }
    System.out.println("  ATs applied");
  }
}
JAVA
ATCP="$WORK/dl/accesstransformers.jar:$WORK/dl/asm.jar:$WORK/dl/asm-tree.jar:$WORK/dl/asm-commons.jar:$WORK/dl/asm-util.jar:$WORK/dl/asm-analysis.jar:$WORK/dl/log4j-api.jar:$WORK/dl/log4j-core.jar"
"$JC" -d "$WORK/at" -cp "$ATCP" "$WORK/at/ApplyATForge.java"
"$J" -cp "$WORK/at:$ATCP" ApplyATForge "$WORK/ats/accesstransformer.cfg" "$WORK/patched-full.jar" "$WORK/patched-at.jar" 2>/dev/null

echo "[patched] inject concrete covariant self() (Forge interface default-dispatch gap)"
cat > "$WORK/at/InjectSelf.java" <<'JAVA'
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
import java.io.BufferedOutputStream; import java.nio.file.*; import java.util.*; import java.util.zip.*;
public class InjectSelf {
  public static void main(String[] a) throws Exception { // a: <forge-jar> <in-jar> <out-jar>
    Map<String,String> iface = new LinkedHashMap<>();    // IForge* interface internal name -> public self() desc
    try (ZipFile zf = new ZipFile(a[0])) {
      Enumeration<? extends ZipEntry> e = zf.entries();
      while (e.hasMoreElements()) { ZipEntry ze = e.nextElement(); String n = ze.getName();
        if (ze.isDirectory() || !n.endsWith(".class") || !n.startsWith("net/minecraftforge/common/extensions/IForge")) continue;
        ClassNode cn = new ClassNode(); new ClassReader(zf.getInputStream(ze).readAllBytes()).accept(cn, 0);
        for (MethodNode mn : cn.methods) { if (!mn.name.equals("self")) continue;
          if ((mn.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != 0) continue; // only dispatchable self()
          iface.put(cn.name, mn.desc); break; } } }
    int injected = 0;
    try (ZipFile zf = new ZipFile(a[1]);
         ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(a[2]))))) {
      Enumeration<? extends ZipEntry> e = zf.entries();
      while (e.hasMoreElements()) { ZipEntry ze = e.nextElement(); if (ze.isDirectory()) continue;
        String n = ze.getName(); byte[] d = zf.getInputStream(ze).readAllBytes();
        if (n.endsWith(".class")) {
          ClassNode cn = new ClassNode(); new ClassReader(d).accept(cn, 0);
          String desc = null; for (String i : cn.interfaces) { if (iface.containsKey(i)) { desc = iface.get(i); break; } }
          boolean present = false;
          if (desc != null) for (MethodNode mn : cn.methods) if (mn.name.equals("self") && mn.desc.equals(desc)) { present = true; break; }
          if (desc != null && !present) {
            MethodNode mn = new MethodNode(Opcodes.ASM9, Opcodes.ACC_PUBLIC, "self", desc, null, null);
            mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); mn.instructions.add(new InsnNode(Opcodes.ARETURN));
            mn.maxStack = 1; mn.maxLocals = 1; cn.methods.add(mn);
            ClassWriter cw = new ClassWriter(0); cn.accept(cw); d = cw.toByteArray();
            injected++; System.out.println("  injected self()" + desc + " into " + n.substring(0, n.length() - 6)); } }
        zos.putNextEntry(new ZipEntry(n)); zos.write(d); zos.closeEntry(); } }
    System.out.println("  self() injected into " + injected + " class(es)");
  }
}
JAVA
"$JC" -d "$WORK/at" -cp "$ATCP" "$WORK/at/InjectSelf.java"
"$J" -cp "$WORK/at:$ATCP" InjectSelf "$WORK/dl/forge-universal.jar" "$WORK/patched-at.jar" "$OUT"

echo "[patched] wrote $OUT ($(du -h "$OUT" | cut -f1))"
