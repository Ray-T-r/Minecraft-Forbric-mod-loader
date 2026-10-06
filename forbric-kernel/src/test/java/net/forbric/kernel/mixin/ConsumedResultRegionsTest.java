package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/** Compiled operand expressions, branches and induction loops: no unordered dependency bag can authorize a move. */
class ConsumedResultRegionsTest {
	@TempDir Path work;
	@Test void subtractionAndAdditionKeepTheirOperandOrder()throws Exception{
		assertFalse(compare("int index=a-b;","int index=b-a;"));assertFalse(compare("int index=a+b;","int index=b+a;"));
		assertTrue(compare("int index=a-b;","int index=a-b;"));
	}
	@Test void aPhiKeepsTheValueAssignedOnEachControlEdge()throws Exception{
		assertFalse(compare("int index=flag?a:b;","int index=flag?b:a;"));
		assertTrue(compare("int index=flag?a:b;","int index=flag?a:b;"));
		assertFalse(compare("int index=flag?a:b;","int index=!flag?a:b;"));
	}
	@Test void aCountedLoopNeedsTheSameInitialStepAndOrderedExit()throws Exception{
		assertTrue(loop("for(int index=0;index<3;index++)","for(int index=0;index<3;index++)"));
		assertFalse(loop("for(int index=0;index<3;index++)","for(int index=1;index<3;index++)"));
		assertFalse(loop("for(int index=0;index<3;index++)","for(int index=0;index<3;index+=2)"));
		assertFalse(loop("for(int index=0;index<3;index++)","for(int index=0;index<4;index++)"));
	}
	@Test void unknownConditionalStepsAndRepeatedEffectfulInputsRemainUnproved()throws Exception{
		assertFalse(check("for(int index=0;index<3;index++){sink(old.size(),index);}","for(int index=0;index<3;index++){if(flag)index++;sink(live.size(),index);}"));
		assertFalse(compare("int index=effect(a)-effect(b);","int index=effect(a)-effect(b);"),"two same-member effectful reads are not interchangeable SSA origins");
	}
	@Test void aBranchAlsoPreservesOperandsCarriedOnTheStackAcrossTheResultCall(){
		MethodNode before=stackBranch(false),after=stackBranch(true);
		assertFalse(ConsumedResultRegions.same("probe/Host",before,call(before),after,call(after)),"an unchanged sink does not excuse changed branch inputs evaluated before the result");
	}
	private static MethodNode stackBranch(boolean changed){
		MethodNode method=new MethodNode(Opcodes.ACC_STATIC,"consume","(Ljava/lang/Object;II)V",null,null);LabelNode done=new LabelNode();
		method.instructions.add(new VarInsnNode(Opcodes.ILOAD,changed?2:1));method.instructions.add(new VarInsnNode(Opcodes.ILOAD,changed?1:2));method.instructions.add(new InsnNode(Opcodes.ISUB));method.instructions.add(new VarInsnNode(Opcodes.ALOAD,0));method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST,changed?"java/util/Map":"java/util/List"));method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,changed?"java/util/Map":"java/util/List","size","()I",true));method.instructions.add(new InsnNode(Opcodes.SWAP));method.instructions.add(new JumpInsnNode(Opcodes.IFEQ,done));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"probe/Effect","touch","()V",false));method.instructions.add(done);method.instructions.add(new InsnNode(Opcodes.ICONST_0));method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,"probe/Sink","accept","(II)V",false));method.instructions.add(new InsnNode(Opcodes.RETURN));method.maxLocals=3;method.maxStack=2;return method;
	}
	private boolean compare(String original,String current)throws Exception{return check(original+"sink(old.size(),index);",current+"sink(live.size(),index);");}
	private boolean loop(String original,String current)throws Exception{return check(original+"{sink(old.size(),index);}",current+"{sink(live.size(),index);}");}
	private boolean check(String original,String current)throws Exception{
		String arguments="java.util.List<?> old,java.util.Map<?,?> live,int a,int b,boolean flag";
		Path file=work.resolve("Probe.java"),classes=Files.createDirectories(work.resolve("classes"));
		Files.writeString(file,"public class Probe {static void before("+arguments+"){ "+original+"} static void after("+arguments+"){"+current+"} static void sink(int size,int index){} static int effect(int value){return value;} }");
		assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","--release","21","-d",classes.toString(),file.toString()));
		ClassNode owner=new ClassNode();new ClassReader(Files.readAllBytes(classes.resolve("Probe.class"))).accept(owner,0);
		MethodNode a=owner.methods.stream().filter(m->m.name.equals("before")).findFirst().orElseThrow(),b=owner.methods.stream().filter(m->m.name.equals("after")).findFirst().orElseThrow();
		return ConsumedResultRegions.same(owner.name,a,call(a),b,call(b));
	}
	private static MethodInsnNode call(MethodNode method){for(var instruction:method.instructions)if(instruction instanceof MethodInsnNode call&&call.name.equals("size"))return call;throw new AssertionError("result call absent");}
}
