/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.boot;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.*;import org.junit.jupiter.api.parallel.ResourceLock;import org.objectweb.asm.*;import org.objectweb.asm.tree.*;
@ResourceLock("DefinedMethodContracts")
class DefinedDefaultDispatchTest{
 @BeforeEach @AfterEach void reset(){DefinedMethodContracts.resetForTests();}
 static byte[]bytes(Class<?>type)throws Exception{try(var in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){return in.readAllBytes();}}
 static void observe(Class<?>type)throws Exception{DefinedMethodContracts.observe(type.getClassLoader(),type.getName(),bytes(type));}
 static DefinedMethodContracts.MethodContract contract(Class<?>type,String name)throws Exception{ClassNode n=new ClassNode();new ClassReader(bytes(type)).accept(n,0);MethodNode m=n.methods.stream().filter(method->method.name.equals(name)).findFirst().orElseThrow();return new DefinedMethodContracts.MethodContract(type.getName(),m.name,m.desc,DefinedMethodContracts.fingerprint(m));}
 @Test void aPureActualForwarderExecutesOneExactConditionalDefaultButConcreteAndChangedOperandsFail()throws Exception{
  var expected=contract(Branch.class,"evaluate");observe(Branch.class);var receiver=new Forwarder();assertFalse(DefinedMethodContracts.validatesDefaultDispatch(receiver,expected));observe(Forwarder.class);assertTrue(DefinedMethodContracts.validatesDefaultDispatch(receiver,expected));assertFalse(DefinedMethodContracts.validates(receiver,expected));Branch.State.calls=0;assertEquals(3,receiver.evaluate("abc"));assertEquals(1,Branch.State.calls);
  observe(Concrete.class);assertFalse(DefinedMethodContracts.validatesDefaultDispatch(new Concrete(),expected));observe(ChangedInput.class);assertFalse(DefinedMethodContracts.validatesDefaultDispatch(new ChangedInput(),expected));observe(SynchronizedForwarder.class);assertFalse(DefinedMethodContracts.validatesDefaultDispatch(new SynchronizedForwarder(),expected));observe(WrongDefault.class);observe(OtherBranch.class);assertFalse(DefinedMethodContracts.validatesDefaultDispatch(new WrongDefault(),expected));
 }
 @Test void inheritedSymbolicInterfaceTargetsAndThePureCastIdentityAreResolvedPrecisely()throws Exception{
  observe(Branch.class);observe(InheritedForwarder.class);assertTrue(DefinedMethodContracts.validatesDefaultDispatch(new InheritedForwarder(),contract(Branch.class,"evaluate")));
  observe(CastIdentity.class);var expected=contract(CastIdentity.class,"self");var actor=new IdentityReceiver();assertFalse(DefinedMethodContracts.validatesTransparentDispatch(actor,expected));observe(IdentityReceiver.class);assertTrue(DefinedMethodContracts.validatesTransparentDispatch(actor,expected));assertSame(actor,actor.self());observe(ChangedIdentity.class);assertFalse(DefinedMethodContracts.validatesTransparentDispatch(new ChangedIdentity(),expected));
 }
 @Test void finalChangedDefaultOrForwarderAndMissingLoaderWitnessCannotBorrowTheProof()throws Exception{
  var expected=contract(Branch.class,"evaluate");observe(Branch.class);observe(Forwarder.class);assertTrue(DefinedMethodContracts.validatesDefaultDispatch(new Forwarder(),expected));ClassNode changed=new ClassNode();new ClassReader(bytes(Forwarder.class)).accept(changed,0);changed.methods.stream().filter(m->m.name.equals("evaluate")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));ClassWriter writer=new ClassWriter(0);changed.accept(writer);DefinedMethodContracts.observe(Forwarder.class.getClassLoader(),Forwarder.class.getName(),writer.toByteArray());assertFalse(DefinedMethodContracts.validatesDefaultDispatch(new Forwarder(),expected));observe(Forwarder.class);
  changed=new ClassNode();new ClassReader(bytes(Branch.class)).accept(changed,0);changed.methods.stream().filter(m->m.name.equals("evaluate")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));writer=new ClassWriter(0);changed.accept(writer);DefinedMethodContracts.observe(Branch.class.getClassLoader(),Branch.class.getName(),writer.toByteArray());assertFalse(DefinedMethodContracts.validatesDefaultDispatch(new Forwarder(),expected));
 }
 public interface Branch{java.util.concurrent.atomic.AtomicInteger counter=new java.util.concurrent.atomic.AtomicInteger();static final class State{static int calls;}default int evaluate(String value){State.calls++;return value.isEmpty()?0:value.length();} }
 public interface SubBranch extends Branch{}
 public interface OtherBranch{default int evaluate(String value){return 42;}}
 public static class Forwarder implements Branch{public int evaluate(String value){return Branch.super.evaluate(value);}}
 public static class InheritedForwarder implements SubBranch{public int evaluate(String value){return SubBranch.super.evaluate(value);}}
 public static class Concrete implements Branch{public int evaluate(String value){return 41;}}
 public static class ChangedInput implements Branch{public int evaluate(String value){return Branch.super.evaluate(value+"!");}}
 public static class SynchronizedForwarder implements Branch{public synchronized int evaluate(String value){return Branch.super.evaluate(value);}}
 public static class WrongDefault implements Branch,OtherBranch{public int evaluate(String value){return OtherBranch.super.evaluate(value);}}
 public interface CastIdentity{default IdentityReceiver self(){return(IdentityReceiver)this;}}
 public static class IdentityReceiver implements CastIdentity{public IdentityReceiver self(){return this;}}
 public static class ChangedIdentity extends IdentityReceiver{public IdentityReceiver self(){return new IdentityReceiver();}}
}
