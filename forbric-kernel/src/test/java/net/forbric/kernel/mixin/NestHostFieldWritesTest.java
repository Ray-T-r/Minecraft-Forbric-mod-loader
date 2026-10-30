package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
import org.junit.jupiter.api.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

class NestHostFieldWritesTest {
	@Test void aPrivateMemberFieldWrittenByHostOrSiblingIsNotAnOrphan() {
		String host="fixture/Host",member=host+"$Member",writer=host+"$Writer";
		ClassNode m=node(member);m.nestHostClass=host;m.fields.add(new FieldNode(Opcodes.ACC_PRIVATE,"live","Z",null,null));
		ClassNode h=node(host);h.nestMembers=new ArrayList<>(List.of(member,writer));ClassNode w=node(writer);w.nestHostClass=host;
		MethodNode method=new MethodNode(Opcodes.ACC_STATIC,"write","(L"+member+";)V",null,null);method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new InsnNode(Opcodes.ICONST_1));method.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD,member,"live","Z"));method.instructions.add(new InsnNode(Opcodes.RETURN));method.maxStack=2;method.maxLocals=1;w.methods.add(method);
		Map<String,byte[]> classes=new HashMap<>();for(ClassNode n:List.of(h,m,w))classes.put(n.name+".class",bytes(n));byte[] mixin=shadow(member,"live","Z");
		assertEquals(MixinFit.Verdict.FIT,MixinFit.evaluate(mixin,classes::get,n->true).verdict());
		ClassNode child=node(member+"$Child");child.superName=member;classes.put(child.name+".class",bytes(child));assertEquals(MixinFit.Verdict.FIT,MixinFit.evaluate(shadow(child.name,"live","Z"),classes::get,n->true).verdict(),"inherited private field uses its declaring class nest");
		h.nestMembers=List.of(member);h.methods.add(method);classes.put(host+".class",bytes(h));classes.remove(writer+".class");assertEquals(MixinFit.Verdict.FIT,MixinFit.evaluate(mixin,classes::get,n->true).verdict());
		h.methods.clear();classes.put(host+".class",bytes(h));assertEquals(MixinFit.Verdict.HAZARD,MixinFit.evaluate(mixin,classes::get,n->true).verdict());
		FieldInsnNode assignment=(FieldInsnNode)method.instructions.get(2);assignment.owner=writer;h.methods.add(method);classes.put(host+".class",bytes(h));assertEquals(MixinFit.Verdict.HAZARD,MixinFit.evaluate(mixin,classes::get,n->true).verdict(),"same-name sibling field must not count as a write to this member");
	}
	@Test void actualC2meVolatileAndSynchronizedGuardsAreNotSuppressed() throws Exception {
		Path base=TestFixtures.stagedBase();TestFixtures.require(Fixture.STAGED,Files.exists(base),base+" absent");
		for(String version:List.of("alpha","beta")) {
			Path mod=Path.of("build/compat-inputs/c2me-barrel-20261001/"+version+"-worldgen.jar");TestFixtures.require(Fixture.THIRD_PARTY,Files.exists(mod),mod+" absent");
			try(ZipFile game=new ZipFile(base.toFile());ZipFile c2me=new ZipFile(mod.toFile())) {
				java.util.function.Function<String,byte[]> resolver=n->{try{ZipEntry e=game.getEntry(n);return e==null?null:game.getInputStream(e).readAllBytes();}catch(Exception ex){throw new RuntimeException(ex);}};
				for(String name:List.of("MixinNetherFortressGeneratorStart","MixinOceanMonumentGeneratorPieceSetting")) {
					byte[] raw=c2me.getInputStream(c2me.getEntry("com/ishland/c2me/fixes/worldgen/threading_issues/mixin/threading/"+name+".class")).readAllBytes();var fit=MixinFit.evaluate(raw,resolver,n->true);assertNotEquals(MixinFit.Verdict.HAZARD,fit.verdict(),fit.reason());
				}
			}
		}
	}
	private static ClassNode node(String name){ClassNode n=new ClassNode();n.version=Opcodes.V17;n.access=Opcodes.ACC_PUBLIC;n.name=name;n.superName="java/lang/Object";return n;}
	private static byte[] bytes(ClassNode node){ClassWriter w=new ClassWriter(0);node.accept(w);return w.toByteArray();}
	private static byte[] shadow(String target,String name,String desc){ClassNode n=node("fixture/Mixin");AnnotationNode a=new AnnotationNode("Lorg/spongepowered/asm/mixin/Mixin;");a.values=new ArrayList<>(List.of("value",List.of(Type.getObjectType(target))));n.invisibleAnnotations=new ArrayList<>(List.of(a));FieldNode f=new FieldNode(0,name,desc,null,null);f.visibleAnnotations=new ArrayList<>(List.of(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;")));n.fields.add(f);return bytes(n);}
}
