package net.forbric.kernel.boot;

import static org.junit.jupiter.api.Assertions.*;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import net.forbric.kernel.boot.DefinedMethodContracts.MethodContract;

/** Renamed interfaces exercise the full default chain, including private identity and final-field witnesses. */
@ResourceLock("defined-method-contracts")
class KernelDefaultPredicatesTest {
	public interface ViewRules {
		private View self() { return (View)this; }
		default boolean choose(Object context) { return self().extract().choose(self(),context); }
	}
	public interface ObjectRules {
		default boolean choose(View view,Object context) { return view.extract() instanceof Primary || view.extract() instanceof Residual; }
	}
	public static class Item implements ObjectRules { }
	public static class Primary extends Item { }
	public static class Residual extends Item { }
	public static class Registered extends Item { }
	public static class OverrideItem extends Item { @Override public boolean choose(View view,Object context) { return false; } }
	public static class View implements ViewRules { protected final Item value; public View(Item value){this.value=value;} public Item extract(){return value;} }
	public static class OverrideView extends View { public OverrideView(Item value){super(value);} @Override public boolean choose(Object context){return false;} }
	public static class OverrideGetter extends View { public OverrideGetter(Item value){super(value);} @Override public Item extract(){return new Item();} }
	private String key;

	@BeforeEach void setup() throws Exception {
		DefinedMethodContracts.resetForTests(); KernelDefaultPredicates.resetForTests();
		for(Class<?>type:List.of(ViewRules.class,ObjectRules.class,View.class)) DefinedMethodContracts.observe(type.getClassLoader(),type.getName(),bytes(type));
		key=KernelDefaultPredicates.register(new KernelDefaultPredicates.Contract(contract(ViewRules.class,"choose"),contract(View.class,"extract"),contract(ObjectRules.class,"choose"),
				List.of(contract(ViewRules.class,"self")),new KernelDefaultPredicates.ImmutableField(View.class.getName(),"value",Type.getDescriptor(Item.class)),Primary.class.getName(),Residual.class.getName()));
	}
	@AfterEach void reset(){DefinedMethodContracts.resetForTests();KernelDefaultPredicates.resetForTests();}

	@Test void aRegisteredOperandChangesOnlyTheFirstAtomAndCallsItsModifierOnce(){
		Registered item=new Registered();View view=new View(item);AtomicInteger calls=new AtomicInteger();
		boolean nativeResult=view.choose(null);assertFalse(nativeResult);
		assertTrue(KernelDefaultPredicates.modifyDefault(nativeResult,view,key,(original,operand)->{calls.incrementAndGet();assertFalse(original);assertSame(item,operand);return true;}));assertEquals(1,calls.get());
	}
	@Test void theResidualOrRemainsTrueEvenWhenTheModifierIgnoresOriginalAndReturnsFalse(){
		View view=new View(new Residual());AtomicInteger calls=new AtomicInteger();
		assertTrue(KernelDefaultPredicates.modifyDefault(view.choose(null),view,key,(original,operand)->{assertFalse(original);calls.incrementAndGet();return false;}));assertEquals(1,calls.get());
	}
	@Test void aPrimaryTrueCanBeReplacedWithFalseWithoutUnioningNativeBackIn(){
		View view=new View(new Primary());assertTrue(view.choose(null));
		assertFalse(KernelDefaultPredicates.modifyDefault(true,view,key,(original,operand)->{assertTrue(original);return false;}));
	}
	@Test void concreteItemAndViewOverridesKeepTheirNativeResultWithoutRunningTheModifier(){
		AtomicInteger calls=new AtomicInteger();for(View view:List.of(new View(new OverrideItem()),new OverrideView(new Primary()),new OverrideGetter(new Primary()))){
			boolean original=view.choose(null);assertFalse(original);assertFalse(KernelDefaultPredicates.modifyDefault(original,view,key,(ignored,operand)->{calls.incrementAndGet();return true;}));
		}assertEquals(0,calls.get());
	}
	@Test void aChangedDefaultOrPrivateIdentityWitnessCannotExecuteTheGuest() throws Exception {
		View view=new View(new Registered());AtomicInteger calls=new AtomicInteger();
		ClassNode changed=parse(bytes(ObjectRules.class));changed.methods.stream().filter(m->m.name.equals("choose")).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
		DefinedMethodContracts.observe(ObjectRules.class.getClassLoader(),ObjectRules.class.getName(),write(changed));
		assertFalse(KernelDefaultPredicates.modifyDefault(false,view,key,(o,v)->{calls.incrementAndGet();return true;}));assertEquals(0,calls.get());
	}
	@Test void changesToThePrivateIdentityOrProjectionGetterCannotExecuteTheGuest()throws Exception{
		View view=new View(new Registered());AtomicInteger calls=new AtomicInteger();
		for(String name:List.of("self","extract")){
			Class<?>type=name.equals("self")?ViewRules.class:View.class;ClassNode changed=parse(bytes(type));changed.methods.stream().filter(m->m.name.equals(name)).findFirst().orElseThrow().instructions.insert(new InsnNode(Opcodes.NOP));
			DefinedMethodContracts.observe(type.getClassLoader(),type.getName(),write(changed));
			assertFalse(KernelDefaultPredicates.modifyDefault(false,view,key,(o,v)->{calls.incrementAndGet();return true;}));
			DefinedMethodContracts.observe(type.getClassLoader(),type.getName(),bytes(type));
		}assertEquals(0,calls.get());
	}
	@Test void aMissingWitnessOrInconsistentNativeResultFailsClosed(){
		View view=new View(new Registered());AtomicInteger calls=new AtomicInteger();
		assertTrue(KernelDefaultPredicates.modifyDefault(true,view,key,(o,v)->{calls.incrementAndGet();return false;}),"an inconsistent native result is not overwritten");
		DefinedMethodContracts.resetForTests();assertFalse(KernelDefaultPredicates.modifyDefault(false,view,key,(o,v)->{calls.incrementAndGet();return true;}));assertEquals(0,calls.get());
	}
	@Test void exceptionsFromThePreservedGuestBodyAreNotSwallowed(){
		View view=new View(new Registered());AssertionError error=new AssertionError("guest failure");
		assertSame(error,assertThrows(AssertionError.class,()->KernelDefaultPredicates.modifyDefault(false,view,key,(o,v)->{throw error;})));
	}
	private static MethodContract contract(Class<?>owner,String name)throws Exception{ClassNode c=parse(bytes(owner));MethodNode m=c.methods.stream().filter(n->n.name.equals(name)).findFirst().orElseThrow();return new MethodContract(owner.getName(),m.name,m.desc,DefinedMethodContracts.fingerprint(m));}
	private static byte[]bytes(Class<?>type)throws Exception{try(InputStream in=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")){return in.readAllBytes();}}
	private static ClassNode parse(byte[]bytes){ClassNode c=new ClassNode();new ClassReader(bytes).accept(c,0);return c;}
	private static byte[]write(ClassNode c){ClassWriter w=new ClassWriter(0);c.accept(w);return w.toByteArray();}
}
