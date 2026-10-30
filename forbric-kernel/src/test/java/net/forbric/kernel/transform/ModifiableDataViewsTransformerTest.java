package net.forbric.kernel.transform;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import net.forbric.kernel.TestFixtures;
import net.forbric.kernel.TestFixtures.Fixture;
class ModifiableDataViewsTransformerTest {
    private static final String RUNTIME="net/forbric/kernel/runtime/KernelModifiableDataViews";
    private static final String BIOME="net/minecraft/world/level/biome/Biome",FORGE_BIOME_INFO="Lnet/minecraftforge/common/world/ModifiableBiomeInfo;";
    /**
     * Every published Forge data field ends up a view of NeoForge's live state, whichever shape the merge left it in:
     * never written (the merge kept NeoForge's constructor whole — ReloadableServerResources here), or initialized by
     * MinecraftForge's own statement as a second, independent state that nothing reads (the edit-splicing merge keeps
     * that statement in Biome and Structure). Afterwards the field's only write in each constructor is the view.
     */
    @Test void actualNativeLostDataInitializersAreRestoredAndVerified()throws Exception{
        var resources=resources();var transform=new ModifiableDataViewsTransformer(resources);
        for(String owner:List.of("net/minecraft/world/level/biome/Biome","net/minecraft/world/level/levelgen/structure/Structure","net/minecraft/server/ReloadableServerResources")){
            byte[] raw=resources.apply(owner+".class"),result=transform.transform(owner,raw,null);assertNotSame(raw,result,owner);ClassNode parsed=parse(result);
            int calls=0;for(MethodNode method:parsed.methods){for(AbstractInsnNode i:method.instructions)if(i instanceof MethodInsnNode c&&c.owner.equals(RUNTIME))calls++;if(method.name.equals("<init>"))new Analyzer<>(new BasicVerifier()).analyze(owner,method);}
            assertTrue(calls>0,owner);assertArrayEquals(result,transform.transform(owner,result,null),"constructor state is initialized once");
            for(MethodNode method:parsed.methods){
                for(AbstractInsnNode i:method.instructions){
                    if(!(i instanceof FieldInsnNode f)||f.getOpcode()!=Opcodes.PUTFIELD||!f.owner.equals(owner)||!f.desc.startsWith("Lnet/minecraftforge/"))continue;
                    AbstractInsnNode value=i.getPrevious();while(value.getOpcode()<0)value=value.getPrevious();
                    assertTrue(value instanceof MethodInsnNode c&&c.owner.equals(RUNTIME),owner+"."+f.name+" is written with something other than the view of the live state");
                }
            }
        }
    }
    /** The lost shape: an unproved constructor cannot leave the recognized public source field null. */
    @Test void aChangedConstructorCannotLeaveTheRecognizedPublicSourceFieldNull()throws Exception{
        var resources=resources();ClassNode node=parse(resources.apply(BIOME+".class"));withoutForgeInitializer(node);
        ClassWriter lost=new ClassWriter(0);node.accept(lost);byte[] lostBytes=lost.toByteArray();
        assertEquals(1,forgeWrites(parse(new ModifiableDataViewsTransformer(resources).transform(BIOME,lostBytes,null))),"premise: the unchanged lost shape gets its view");
        MethodNode ctor=node.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();ctor.instructions.insert(new InsnNode(Opcodes.NOP));ClassWriter writer=new ClassWriter(0);node.accept(writer);
        LinkageError error=assertThrows(LinkageError.class,()->new ModifiableDataViewsTransformer(resources).transform(BIOME,writer.toByteArray(),null));assertTrue(error.getMessage().contains("source initializes this field"));
        assertTrue(error.getMessage().contains("constructor inputs or control flow differ"),error.getMessage());
    }
    /**
     * The shape where the merge initializes the Forge field itself: an unproved constructor keeps that state exactly as
     * merged (it is not null, and replacing it unproved could be wrong), and nothing is thrown.
     */
    @Test void aChangedConstructorKeepsTheMergesOwnStateWhenTheMergeInitializesIt()throws Exception{
        var resources=resources();ClassNode node=parse(resources.apply(BIOME+".class"));
        if(forgeWrites(node)==0)withForgeInitializerOf(node);   // a base that loses it: give it Forge's own statement back
        assertEquals(1,forgeWrites(node),"premise: the merged constructor initializes the Forge field itself");
        ClassWriter own=new ClassWriter(0);node.accept(own);byte[] ownBytes=own.toByteArray();byte[] viewed=new ModifiableDataViewsTransformer(resources).transform(BIOME,ownBytes,null);
        assertNotSame(ownBytes,viewed,"premise: unchanged, Forge's own initializer is replaced by the view");
        MethodNode ctor=node.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();ctor.instructions.insert(new InsnNode(Opcodes.NOP));ClassWriter writer=new ClassWriter(0);node.accept(writer);
        byte[] changed=writer.toByteArray();assertSame(changed,new ModifiableDataViewsTransformer(resources).transform(BIOME,changed,null));
    }
    /** A write that is not MinecraftForge's own initializer statement is a live producer: the field is left alone. */
    @Test void aWriterThatIsNotForgesOwnStatementIsLeftAlone()throws Exception{
        var resources=resources();ClassNode node=parse(resources.apply(BIOME+".class"));withoutForgeInitializer(node);
        MethodNode ctor=node.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();
        AbstractInsnNode last=null;for(AbstractInsnNode i:ctor.instructions)if(i.getOpcode()==Opcodes.RETURN)last=i;
        InsnList other=new InsnList();other.add(new VarInsnNode(Opcodes.ALOAD,0));other.add(new InsnNode(Opcodes.ACONST_NULL));other.add(new FieldInsnNode(Opcodes.PUTFIELD,BIOME,"modifiableBiomeInfo",FORGE_BIOME_INFO));
        ctor.instructions.insertBefore(last,other);ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer);
        byte[] bytes=writer.toByteArray();assertSame(bytes,new ModifiableDataViewsTransformer(resources).transform(BIOME,bytes,null));
    }
    private static int forgeWrites(ClassNode node){int n=0;for(MethodNode m:node.methods)for(AbstractInsnNode i:m.instructions)if(i instanceof FieldInsnNode f&&f.getOpcode()==Opcodes.PUTFIELD&&f.desc.equals(FORGE_BIOME_INFO))n++;return n;}
    /** Takes out {@code this.modifiableBiomeInfo = new ModifiableBiomeInfo(...)} (Forge's), as the old merge left it. */
    private static void withoutForgeInitializer(ClassNode node){
        for(MethodNode m:node.methods){
            if(!m.name.equals("<init>"))continue;
            for(AbstractInsnNode i:m.instructions.toArray()){
                if(!(i instanceof FieldInsnNode put)||put.getOpcode()!=Opcodes.PUTFIELD||!put.desc.equals(FORGE_BIOME_INFO))continue;
                AbstractInsnNode start=put;while(!(start instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&("L"+t.desc+";").equals(FORGE_BIOME_INFO)))start=start.getPrevious();
                start=start.getPrevious();while(start.getOpcode()<0)start=start.getPrevious();assertTrue(start instanceof VarInsnNode v&&v.var==0);
                AbstractInsnNode stop=put.getNext();
                for(AbstractInsnNode at=start;at!=stop;){AbstractInsnNode next=at.getNext();if(at.getOpcode()>=0)m.instructions.remove(at);at=next;}
            }
        }
        assertEquals(0,forgeWrites(node));
    }
    /** Gives the constructor MinecraftForge's own initializer statement, copied from Forge's patched Biome. */
    private static void withForgeInitializerOf(ClassNode node)throws Exception{
        Path forge=TestFixtures.forgeMergeInput();TestFixtures.requireFiles(Fixture.STAGED,"MinecraftForge's patched game",forge);
        ClassNode source;try(var zip=new ZipFile(forge.toFile());var in=zip.getInputStream(zip.getEntry(BIOME+".class"))){source=parse(in.readAllBytes());}
        MethodNode from=source.methods.stream().filter(m->m.name.equals("<init>")).findFirst().orElseThrow();
        MethodNode to=node.methods.stream().filter(m->m.name.equals("<init>")&&m.desc.equals(from.desc)).findFirst().orElseThrow();
        List<AbstractInsnNode> run=new ArrayList<>();boolean in=false;
        for(AbstractInsnNode i:from.instructions){if(i.getOpcode()<0)continue;if(i instanceof TypeInsnNode t&&t.getOpcode()==Opcodes.NEW&&("L"+t.desc+";").equals(FORGE_BIOME_INFO)){in=true;run.add(new VarInsnNode(Opcodes.ALOAD,0));}if(in)run.add(i.clone(Map.of()));if(in&&i instanceof FieldInsnNode f&&f.desc.equals(FORGE_BIOME_INFO))break;}
        AbstractInsnNode first=null;for(AbstractInsnNode i:to.instructions)if(i instanceof MethodInsnNode c&&c.name.equals("<init>")&&c.getOpcode()==Opcodes.INVOKESPECIAL){first=c;break;}
        InsnList list=new InsnList();for(AbstractInsnNode i:run)list.add(i);to.instructions.insert(first,list);to.maxStack+=6;
    }
    private static ClassNode parse(byte[] b){ClassNode n=new ClassNode();new ClassReader(b).accept(n,0);return n;}
    private static Function<String,byte[]> resources()throws Exception{
        Path jar=TestFixtures.mergedBase();TestFixtures.requireFiles(Fixture.STAGED,"native source data views",jar);
        Map<String,byte[]> entries=new HashMap<>();try(var zip=new ZipFile(jar.toFile())){var all=zip.entries();while(all.hasMoreElements()){var e=all.nextElement();if(!e.isDirectory())try(var in=zip.getInputStream(e)){entries.put(e.getName(),in.readAllBytes());}}}return entries::get;
    }
}
