package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.fabricmc.api.EnvType;

/**
 * The removed NeoForge name-tag attribute is recognised by where its value goes, not by the one way Corpse wrote it.
 *
 * <p>Every class here is a different, unnamed mod that hides a name tag through a route Corpse does not take: an
 * entity's own {@code getAttribute}, another entity's, a holder or a zero kept in a local, {@code requireNonNull}, two
 * lookups meeting at one setter, and Kotlin's {@code ?.} and {@code !!} (assembled, since no Kotlin compiler runs
 * here). Each one dies with {@code NoSuchFieldError} as built and hides the tag once repaired. The look-alikes read
 * the distance, keep or return the instance, set something other than a constant zero, or read a same-named field
 * that still exists; each of those is left byte-for-byte alone.
 */
@ExecutesInjector(ZeroNameTagMigrationInjector.class)
class ZeroNameTagMigrationDataflowTest {
	private static final String INSTANCE = "net/minecraft/world/entity/ai/attributes/AttributeInstance";
	private static final String MAP = "net/minecraft/world/entity/ai/attributes/AttributeMap";
	private static final String HOLDER = ZeroNameTagMigrationInjector.HOLDER;
	private static final String LOOKUP = "(" + HOLDER + ")L" + INSTANCE + ";";

	private static Map<String, String> platform() {
		Map<String, String> sources = new LinkedHashMap<>();
		sources.put("net.minecraft.core.Holder", "package net.minecraft.core; public interface Holder<T> {}");
		sources.put(ZeroNameTagMigrationInjector.NATIVE.replace('/', '.'), """
				package net.neoforged.neoforge.common;
				public class NeoForgeMod { public static net.minecraft.core.Holder<Object> NAMETAG_DISTANCE; }""");
		sources.put("net.minecraftforge.common.ForgeMod", """
				package net.minecraftforge.common;
				public class ForgeMod { public static final net.minecraft.core.Holder<Object> NAMETAG_DISTANCE = new net.minecraft.core.Holder<>() {}; }""");
		sources.put(ZeroNameTagMigrationInjector.ATTRIBUTES.replace('/', '.'), """
				package net.minecraft.world.entity.ai.attributes;
				public class Attributes { public static final net.minecraft.core.Holder<Object> NAME_TAG_DISTANCE = new net.minecraft.core.Holder<>() {}; }""");
		sources.put("net.minecraft.world.entity.ai.attributes.AttributeInstance", """
				package net.minecraft.world.entity.ai.attributes;
				public class AttributeInstance {
					public double value = 64;
					public void setBaseValue(double value) { this.value = value; }
					public double getValue() { return value; }
				}""");
		sources.put("net.minecraft.world.entity.ai.attributes.AttributeMap", """
				package net.minecraft.world.entity.ai.attributes;
				public class AttributeMap {
					public final java.util.Map<Object, AttributeInstance> instances = new java.util.HashMap<>();
					public AttributeInstance getInstance(net.minecraft.core.Holder<?> key) {
						return instances.computeIfAbsent(java.util.Objects.requireNonNull(key), ignored -> new AttributeInstance());
					}
					public double getValue(net.minecraft.core.Holder<?> key) { return getInstance(key).getValue(); }
				}""");
		sources.put("net.minecraft.world.entity.LivingEntity", """
				package net.minecraft.world.entity;
				import net.minecraft.world.entity.ai.attributes.*;
				public class LivingEntity {
					private final AttributeMap attributes = new AttributeMap();
					public AttributeMap getAttributes() { return attributes; }
					public AttributeInstance getAttribute(net.minecraft.core.Holder<?> key) { return attributes.getInstance(key); }
					public double getAttributeValue(net.minecraft.core.Holder<?> key) { return attributes.getValue(key); }
				}""");
		sources.put("kotlin.jvm.internal.Intrinsics", """
				package kotlin.jvm.internal;
				public class Intrinsics {
					public static void checkNotNull(Object value) { if (value == null) throw new NullPointerException(); }
					public static void checkNotNullExpressionValue(Object value, String expression) { if (value == null) throw new NullPointerException(expression); }
				}""");
		return sources;
	}

	/** Compiles the platform and one mod class, then removes NAMETAG_DISTANCE the way NeoForge 26.2.0.30 did. */
	private static Map<String, byte[]> build(Path work, String mod, String source) throws Exception {
		Map<String, String> sources = platform();
		sources.put(mod.replace('/', '.'), source);
		Map<String, byte[]> classes = new HashMap<>(InjectorExecution.compile(work, sources));
		ClassNode neoForge = parse(classes.get(ZeroNameTagMigrationInjector.NATIVE));
		neoForge.fields.clear();
		classes.put(ZeroNameTagMigrationInjector.NATIVE, write(neoForge, 0));
		return classes;
	}

	/** A mod class written the way kotlinc writes it: a javac shell whose {@code conceal()} body is replaced. */
	private static Map<String, byte[]> kotlin(Path work, String mod, Consumer<InsnList> body) throws Exception {
		String simple = mod.substring(mod.lastIndexOf('/') + 1), pkg = mod.substring(0, mod.lastIndexOf('/')).replace('/', '.');
		Map<String, byte[]> classes = build(work, mod, "package " + pkg + "; public class " + simple
				+ " extends net.minecraft.world.entity.LivingEntity { public void conceal() {} }");
		ClassNode node = parse(classes.get(mod));
		MethodNode conceal = node.methods.stream().filter(m -> m.name.equals("conceal")).findFirst().orElseThrow();
		conceal.instructions.clear();
		conceal.tryCatchBlocks.clear();
		conceal.localVariables = null;
		body.accept(conceal.instructions);
		classes.put(mod, write(node, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS));
		return classes;
	}

	private static ClassNode parse(byte[] bytes) {
		ClassNode node = new ClassNode();
		new ClassReader(bytes).accept(node, 0);
		return node;
	}

	private static byte[] write(ClassNode node, int flags) {
		ClassWriter writer = new ClassWriter(flags) {
			@Override protected String getCommonSuperClass(String first, String second) { return "java/lang/Object"; }
		};
		node.accept(writer);
		return writer.toByteArray();
	}

	private static ZeroNameTagMigrationInjector injector(Map<String, byte[]> classes) {
		return new ZeroNameTagMigrationInjector(name -> classes.containsKey(name) ? parse(classes.get(name)) : null);
	}

	private static FieldInsnNode legacyRead() {
		return new FieldInsnNode(Opcodes.GETSTATIC, ZeroNameTagMigrationInjector.NATIVE, "NAMETAG_DISTANCE", HOLDER);
	}

	/**
	 * The mod as built throws NoSuchFieldError; repaired, it verifies, runs, and leaves the vanilla name-tag attribute
	 * at zero. Only the legacy reads changed, each to the vanilla holder.
	 */
	private static void assertRepairedHidesTheTag(Map<String, byte[]> classes, String mod, Object... flags) throws Throwable {
		String binary = mod.replace('/', '.');
		byte[] original = classes.get(mod);
		Object broken = InjectorExecution.construct(InjectorExecution.load(classes).loadClass(binary));
		assertThrows(NoSuchFieldError.class, () -> InjectorExecution.invoke(broken, "conceal", flags));

		byte[] repaired = InjectorExecution.transform(injector(classes), binary, original, EnvType.CLIENT);
		assertNotSame(original, repaired, mod + " is the same zero-distance use, written differently");
		assertOnlyLegacyReadsMoved(original, repaired);

		Map<String, byte[]> after = new HashMap<>(classes);
		after.put(mod, repaired);
		ClassLoader loader = InjectorExecution.load(after);
		assertEquals("", InjectorExecution.verify(repaired, loader));
		Object entity = InjectorExecution.construct(loader.loadClass(binary));
		InjectorExecution.invoke(entity, "conceal", flags);
		Object attributes = InjectorExecution.invoke(entity, "getAttributes");
		Map<?, ?> instances = (Map<?, ?>) attributes.getClass().getField("instances").get(attributes);
		Object vanilla = InjectorExecution.getStatic(loader.loadClass(ZeroNameTagMigrationInjector.ATTRIBUTES.replace('/', '.')), "NAME_TAG_DISTANCE");
		assertEquals(1, instances.size(), "only the name-tag attribute was looked up: " + instances);
		Object tag = instances.get(vanilla);
		assertNotNull(tag, "the lookup was not given the vanilla name-tag holder");
		assertEquals(0d, tag.getClass().getField("value").getDouble(tag));
		assertSame(repaired, InjectorExecution.transform(injector(after), binary, repaired, EnvType.CLIENT), "a second pass is a no-op");
	}

	private static void assertOnlyLegacyReadsMoved(byte[] original, byte[] repaired) {
		ClassNode before = parse(original), after = parse(repaired);
		assertEquals(before.methods.size(), after.methods.size());
		int moved = 0, legacy = 0;
		for (int m = 0; m < before.methods.size(); m++) {
			AbstractInsnNode[] a = before.methods.get(m).instructions.toArray(), b = after.methods.get(m).instructions.toArray();
			assertEquals(a.length, b.length);
			for (int i = 0; i < a.length; i++) {
				assertEquals(a[i].getOpcode(), b[i].getOpcode());
				if (a[i] instanceof FieldInsnNode x && b[i] instanceof FieldInsnNode y) {
					boolean old = x.owner.equals(ZeroNameTagMigrationInjector.NATIVE) && x.name.equals("NAMETAG_DISTANCE");
					if (old) legacy++;
					if (old && y.owner.equals(ZeroNameTagMigrationInjector.ATTRIBUTES) && y.name.equals("NAME_TAG_DISTANCE") && y.desc.equals(x.desc)) moved++;
					else assertEquals(x.owner + "." + x.name + x.desc, y.owner + "." + y.name + y.desc);
				}
			}
		}
		assertTrue(legacy > 0);
		assertEquals(legacy, moved, "every legacy read moves to the vanilla holder");
	}

	private static void assertLeftAlone(Map<String, byte[]> classes, String mod, String why) {
		byte[] original = classes.get(mod);
		assertSame(original, InjectorExecution.transform(injector(classes), mod.replace('/', '.'), original, EnvType.CLIENT), why);
	}

	// ---- the same problem, written differently: each is repaired ----

	@Test void theEntitysOwnGetAttributeCalledThroughASubclass(@TempDir Path work) throws Throwable {
		String mod = "display/cases/Mannequin";
		assertRepairedHidesTheTag(build(work, mod, """
				package display.cases;
				public class Mannequin extends net.minecraft.world.entity.LivingEntity {
					public void conceal() { getAttribute(net.neoforged.neoforge.common.NeoForgeMod.NAMETAG_DISTANCE).setBaseValue(0); }
				}"""), mod);
	}

	@Test void anotherEntitysGetAttributeWithANullCheck(@TempDir Path work) throws Throwable {
		String mod = "display/cases/Puppeteer";
		assertRepairedHidesTheTag(build(work, mod, """
				package display.cases;
				import net.minecraft.world.entity.LivingEntity;
				public class Puppeteer extends LivingEntity {
					public void conceal() { hide(this); }
					static void hide(LivingEntity other) {
						var tag = other.getAttribute(net.neoforged.neoforge.common.NeoForgeMod.NAMETAG_DISTANCE);
						if (tag != null) { tag.setBaseValue(0.0); }
					}
				}"""), mod);
	}

	@Test void theHolderAndTheZeroKeptInLocals(@TempDir Path work) throws Throwable {
		String mod = "display/cases/Effigy";
		assertRepairedHidesTheTag(build(work, mod, """
				package display.cases;
				import net.minecraft.core.Holder;
				import net.minecraft.world.entity.ai.attributes.AttributeInstance;
				public class Effigy extends net.minecraft.world.entity.LivingEntity {
					public void conceal() {
						Holder<Object> key = net.neoforged.neoforge.common.NeoForgeMod.NAMETAG_DISTANCE;
						double none = 0;
						AttributeInstance tag = getAttributes().getInstance(key);
						if (tag == null) return;
						tag.setBaseValue(none);
					}
				}"""), mod);
	}

	@Test void requireNonNullAroundTheLookup(@TempDir Path work) throws Throwable {
		String mod = "display/cases/Scarecrow";
		assertRepairedHidesTheTag(build(work, mod, """
				package display.cases;
				public class Scarecrow extends net.minecraft.world.entity.LivingEntity {
					public void conceal() {
						java.util.Objects.requireNonNull(getAttribute(net.neoforged.neoforge.common.NeoForgeMod.NAMETAG_DISTANCE), "tag").setBaseValue(0f);
					}
				}"""), mod);
	}

	@Test void twoReadsThroughTwoLookupsMeetAtOneSetter(@TempDir Path work) throws Throwable {
		String mod = "display/cases/Waxwork";
		Map<String, byte[]> classes = build(work, mod, """
				package display.cases;
				import net.neoforged.neoforge.common.NeoForgeMod;
				public class Waxwork extends net.minecraft.world.entity.LivingEntity {
					public void conceal(boolean viaMap) {
						(viaMap ? getAttributes().getInstance(NeoForgeMod.NAMETAG_DISTANCE) : getAttribute(NeoForgeMod.NAMETAG_DISTANCE)).setBaseValue(0);
					}
				}""");
		assertRepairedHidesTheTag(classes, mod, true);
		assertRepairedHidesTheTag(classes, mod, false);
	}

	@Test void kotlinSafeCallSetterDupsAndPopsTheInstance(@TempDir Path work) throws Throwable {
		// attributes.getInstance(NeoForgeMod.NAMETAG_DISTANCE)?.baseValue = 0.0
		String mod = "display/cases/Marionette";
		assertRepairedHidesTheTag(kotlin(work, mod, code -> {
			LabelNode absent = new LabelNode(), done = new LabelNode();
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mod, "getAttributes", "()L" + MAP + ";"));
			code.add(legacyRead());
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MAP, "getInstance", LOOKUP));
			code.add(new InsnNode(Opcodes.DUP));
			code.add(new JumpInsnNode(Opcodes.IFNULL, absent));
			code.add(new LdcInsnNode(0.0d));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, INSTANCE, "setBaseValue", "(D)V"));
			code.add(new JumpInsnNode(Opcodes.GOTO, done));
			code.add(absent);
			code.add(new InsnNode(Opcodes.POP));
			code.add(done);
			code.add(new InsnNode(Opcodes.RETURN));
		}), mod);
	}

	@Test void kotlinNotNullAssertionAndAWidenedZero(@TempDir Path work) throws Throwable {
		// getAttribute(NeoForgeMod.NAMETAG_DISTANCE)!!.baseValue = 0.toDouble()
		String mod = "display/cases/Automaton";
		assertRepairedHidesTheTag(kotlin(work, mod, code -> {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(legacyRead());
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mod, "getAttribute", LOOKUP));
			code.add(new InsnNode(Opcodes.DUP));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNull", "(Ljava/lang/Object;)V"));
			code.add(new InsnNode(Opcodes.ICONST_0));
			code.add(new InsnNode(Opcodes.I2D));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, INSTANCE, "setBaseValue", "(D)V"));
			code.add(new InsnNode(Opcodes.RETURN));
		}), mod);
	}

	@Test void kotlinCheckedPlatformValueKeptInALocal(@TempDir Path work) throws Throwable {
		// val tag: AttributeInstance = attributes.getInstance(NeoForgeMod.NAMETAG_DISTANCE); tag.baseValue = 0.0
		String mod = "display/cases/Golem";
		assertRepairedHidesTheTag(kotlin(work, mod, code -> {
			code.add(new VarInsnNode(Opcodes.ALOAD, 0));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, mod, "getAttributes", "()L" + MAP + ";"));
			code.add(legacyRead());
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MAP, "getInstance", LOOKUP));
			code.add(new InsnNode(Opcodes.DUP));
			code.add(new LdcInsnNode("getInstance(...)"));
			code.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "kotlin/jvm/internal/Intrinsics", "checkNotNullExpressionValue",
					"(Ljava/lang/Object;Ljava/lang/String;)V"));
			code.add(new VarInsnNode(Opcodes.ASTORE, 1));
			code.add(new VarInsnNode(Opcodes.ALOAD, 1));
			code.add(new InsnNode(Opcodes.DCONST_0));
			code.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, INSTANCE, "setBaseValue", "(D)V"));
			code.add(new InsnNode(Opcodes.RETURN));
		}), mod);
	}

	// ---- look-alikes that are not a proved zero-distance use: each is left alone ----

	private static String entity(String simple, String members) {
		return "package display.decoys; import net.neoforged.neoforge.common.NeoForgeMod; import net.minecraft.world.entity.ai.attributes.AttributeInstance; "
				+ "public class " + simple + " extends net.minecraft.world.entity.LivingEntity { " + members + " }";
	}

	@Test void aNonzeroDistanceThroughTheEntity(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Herald";
		assertLeftAlone(build(work, mod, entity("Herald", "public void conceal() { getAttribute(NeoForgeMod.NAMETAG_DISTANCE).setBaseValue(32); }")),
				mod, "a nonzero distance means different things to NeoForge and vanilla");
	}

	@Test void aZeroOnlyOnSomePaths(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Crier";
		assertLeftAlone(build(work, mod, entity("Crier",
				"public void conceal(boolean crouching) { double range = 0; if (crouching) range = 8; getAttribute(NeoForgeMod.NAMETAG_DISTANCE).setBaseValue(range); }")),
				mod, "the distance is zero on one path and eight on the other");
	}

	@Test void aDistanceThatIsNotAConstant(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Beacon";
		assertLeftAlone(build(work, mod, entity("Beacon",
				"double range; public void conceal() { getAttribute(NeoForgeMod.NAMETAG_DISTANCE).setBaseValue(range); }")),
				mod, "a field's value is not a proved zero");
	}

	@Test void theZeroedInstanceAlsoKeptInAField(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Keeper";
		assertLeftAlone(build(work, mod, entity("Keeper",
				"AttributeInstance kept; public void conceal() { AttributeInstance tag = getAttribute(NeoForgeMod.NAMETAG_DISTANCE); tag.setBaseValue(0); kept = tag; }")),
				mod, "the instance escapes to a field where anything may use it later");
	}

	@Test void theZeroedInstanceAlsoReturned(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Lender";
		assertLeftAlone(build(work, mod, entity("Lender",
				"public AttributeInstance conceal() { AttributeInstance tag = getAttribute(NeoForgeMod.NAMETAG_DISTANCE); if (tag != null) tag.setBaseValue(0); return tag; }")),
				mod, "the caller may do anything with the returned instance");
	}

	@Test void theDistanceReadBackAfterZeroing(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Echo";
		assertLeftAlone(build(work, mod, entity("Echo",
				"public double conceal() { AttributeInstance tag = getAttribute(NeoForgeMod.NAMETAG_DISTANCE); tag.setBaseValue(0); return tag.getValue(); }")),
				mod, "the instance's value is read, not only suppressed");
	}

	@Test void theHolderReadsTheDistanceDirectly(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Gauge";
		assertLeftAlone(build(work, mod, entity("Gauge", "public double conceal() { return getAttributeValue(NeoForgeMod.NAMETAG_DISTANCE); }")),
				mod, "(Holder)D reads the distance; it is not a lookup of the instance");
	}

	@Test void theHolderAlsoReadsTheDistance(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Dial";
		assertLeftAlone(build(work, mod, entity("Dial",
				"public double conceal() { var key = NeoForgeMod.NAMETAG_DISTANCE; getAttribute(key).setBaseValue(0); return getAttributeValue(key); }")),
				mod, "the same read also feeds a value read, not only a zeroed lookup");
	}

	@Test void theHolderCachedInAStaticField(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Archive";
		assertLeftAlone(build(work, mod, entity("Archive",
				"static final net.minecraft.core.Holder<Object> KEY = NeoForgeMod.NAMETAG_DISTANCE; public void conceal() { getAttribute(KEY).setBaseValue(0); }")),
				mod, "a holder stored in a field may be used anywhere");
		String also = "display/decoys/Ledger";
		assertLeftAlone(build(work, also, entity("Ledger",
				"static net.minecraft.core.Holder<Object> key; public void conceal() { var read = NeoForgeMod.NAMETAG_DISTANCE; key = read; getAttribute(read).setBaseValue(0); }")),
				also, "the zeroed holder also escapes to a static field");
	}

	@Test void aLookupOnlyNullChecked(@TempDir Path work) throws Exception {
		String mod = "display/decoys/Census";
		assertLeftAlone(build(work, mod, entity("Census", "public boolean conceal() { return getAttribute(NeoForgeMod.NAMETAG_DISTANCE) != null; }")),
				mod, "nothing proves this read suppresses the tag");
	}

	@Test void aSameNamedFieldThatStillExists(@TempDir Path work) throws Throwable {
		String mod = "display/decoys/Banner";
		Map<String, byte[]> classes = build(work, mod, entity("Banner",
				"public void conceal() { getAttribute(net.minecraftforge.common.ForgeMod.NAMETAG_DISTANCE).setBaseValue(0); }"));
		assertLeftAlone(classes, mod, "MinecraftForge still declares its NAMETAG_DISTANCE");
		Object banner = InjectorExecution.construct(InjectorExecution.load(classes).loadClass("display.decoys.Banner"));
		assertDoesNotThrow(() -> InjectorExecution.invoke(banner, "conceal"));
	}
}
