/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin.weave;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.fabricmc.api.EnvType;
import net.forbric.api.Ecosystem;

/**
 * What a real Mixin {@code @Shadow @Final @Mutable} does to its target field, and what the source tag view's field proof
 * makes of it, on classes of no game's.
 *
 * <p>Mixin strips the field's {@code final} in the class it defines. A source tag view whose copied code reads that
 * field later than native code does keeps working only where the injector proved nothing in between can write it
 * ({@code finalImmaterial}); without that proof, and for a field that became final, it declines as before.
 */
class MutableShadowTagSourceWeaveTest {
	@TempDir Path work;

	@Test
	void aMutableShadowStripsFinalAndOnlyAProvedFieldShapeAcceptsIt() throws Exception {
		Map<String, String> code = new LinkedHashMap<>();
		code.put("fixture.drift.Point", "package fixture.drift;public class Point{public final double u;public final double w;"
				+ "public Point(double u,double w){this.u=u;this.w=w;}}");
		code.put("fixture.drift.Holder", "package fixture.drift;public class Holder{public final java.util.Map<Object,Object>store="
				+ "new java.util.LinkedHashMap<>();public boolean armed=true;public void step(){}}");
		code.put("fixture.drift.mixin.PointMixin", "package fixture.drift.mixin;import org.spongepowered.asm.mixin.*;"
				+ "@Mixin(fixture.drift.Point.class)public abstract class PointMixin{@Shadow @Final @Mutable public double u;"
				+ "public void drift$overwrite(double value){this.u=value;}}");
		code.put("fixture.drift.Probe", """
				package fixture.drift;import net.forbric.kernel.boot.*;import java.util.*;
				public class Probe{public void run()throws Exception{
				 org.objectweb.asm.tree.ClassNode node=new org.objectweb.asm.tree.ClassNode();
				 try(var in=Holder.class.getResourceAsStream("/fixture/drift/Holder.class")){new org.objectweb.asm.ClassReader(in.readAllBytes()).accept(node,0);}
				 var step=node.methods.stream().filter(m->m.name.equals("step")).findFirst().orElseThrow();
				 var witness=new DefinedMethodContracts.MethodContract(node.name,"step","()V",DefinedMethodContracts.fingerprint(step));
				 int nativeFinal=org.objectweb.asm.Opcodes.ACC_PUBLIC|org.objectweb.asm.Opcodes.ACC_FINAL;
				 register("drift-proved",new KernelTagSourceContracts.FieldShape("fixture/drift/Point","u","D",nativeFinal,true),node.name,witness);
				 register("drift-strict",new KernelTagSourceContracts.FieldShape("fixture/drift/Point","u","D",nativeFinal),node.name,witness);
				 register("drift-hardened",new KernelTagSourceContracts.FieldShape("fixture/drift/Point","w","D",org.objectweb.asm.Opcodes.ACC_PUBLIC,true),node.name,witness);
				 boolean finalNow=java.lang.reflect.Modifier.isFinal(Point.class.getField("u").getModifiers());
				 Holder holder=new Holder();
				 System.out.println("[Drift] uFinal="+finalNow+" proved="+KernelTagSourceContracts.validates(holder,"drift-proved")
				   +" strict="+KernelTagSourceContracts.validates(holder,"drift-strict")+" hardened="+KernelTagSourceContracts.validates(holder,"drift-hardened"));}
				 static void register(String key,KernelTagSourceContracts.FieldShape shape,String owner,DefinedMethodContracts.MethodContract witness){
				  KernelTagSourceContracts.register(key,new KernelTagSourceContracts.Schema(owner,"store","armed","fixture/drift/Tracker","fixture/drift/Tag",witness,List.of(witness),List.of(),Map.of(),List.of(shape),null,null));}}
				""");
		List<Path> files = new ArrayList<>();
		for (var entry : code.entrySet()) {
			Path file = work.resolve("src").resolve(entry.getKey().replace('.', '/') + ".java");
			Files.createDirectories(file.getParent());
			Files.writeString(file, entry.getValue());
			files.add(file);
		}
		Path withMixin = work.resolve("drift.mixins.json"), without = work.resolve("drift-none.mixins.json");
		Files.writeString(withMixin, "{\"required\":true,\"compatibilityLevel\":\"JAVA_21\",\"package\":\"fixture.drift.mixin\",\"mixins\":[\"PointMixin\"]}");
		Files.writeString(without, "{\"required\":true,\"compatibilityLevel\":\"JAVA_21\",\"package\":\"fixture.drift.mixin\",\"mixins\":[]}");
		Path mutated = WeaveHarness.fixture(work, "drift", files, Map.of("drift.mixins.json", withMixin));
		Path plain = WeaveHarness.fixture(work, "drift-none", files, Map.of("drift-none.mixins.json", without));

		var on = WeaveHarness.run(work, "mutable", mutated, "drift.mixins.json", "drift", Ecosystem.FABRIC, EnvType.SERVER,
				"fixture.drift.Probe", "run", Map.of());
		assertTrue(on.printedLine("[Drift] uFinal=false proved=true strict=false hardened=false"), on.describe());
		var control = WeaveHarness.run(work, "plain", plain, "drift-none.mixins.json", "drift", Ecosystem.FABRIC,
				EnvType.SERVER, "fixture.drift.Probe", "run", Map.of());
		assertTrue(control.printedLine("[Drift] uFinal=true proved=true strict=true hardened=false"), control.describe());
	}
}
