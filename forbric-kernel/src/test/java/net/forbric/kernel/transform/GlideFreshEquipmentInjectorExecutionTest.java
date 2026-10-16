/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.transform;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import net.fabricmc.api.EnvType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

/**
 * {@link GlideFreshEquipmentInjector}'s output, run on a stand-in entity shaped like the merged one: its canGlide(true)
 * answers from a cached attribute refreshed by tick(), canGlide(false) from the equipment. Woven, an entity that has just
 * put a glider on (or taken it off) is answered from the equipment until the tick applies it, and from the attribute
 * after — so a gliding source other than equipment still counts. The native reference reads the equipment only.
 */
@ExecutesInjector(GlideFreshEquipmentInjector.class)
class GlideFreshEquipmentInjectorExecutionTest {
	private static final String ENTITY = GlideFreshEquipmentInjector.TARGET;
	private static final Map<String, String> MERGED = Map.of(
			"net.minecraft.world.item.ItemStack", """
					package net.minecraft.world.item;
					public final class ItemStack {
						public static final ItemStack EMPTY = new ItemStack(false);
						public final boolean glider;
						public ItemStack(boolean glider) { this.glider = glider; }
						public static boolean matches(ItemStack a, ItemStack b) { return a == b; }
					}
					""",
			"net.minecraft.world.entity.EquipmentSlot", """
					package net.minecraft.world.entity;
					public enum EquipmentSlot { HEAD, CHEST, LEGS, FEET; public static final java.util.List<EquipmentSlot> VALUES = java.util.List.of(values()); }
					""",
			ENTITY, """
					package net.minecraft.world.entity;
					import java.util.*;
					import net.minecraft.world.item.ItemStack;
					public class LivingEntity {
						private final Map<EquipmentSlot, ItemStack> lastEquipmentItems = new EnumMap<>(EquipmentSlot.class);
						private final Map<EquipmentSlot, ItemStack> worn = new EnumMap<>(EquipmentSlot.class);
						private double glidingFromEquipment, glidingFromElsewhere;
						public LivingEntity() { for (EquipmentSlot s : EquipmentSlot.VALUES) { lastEquipmentItems.put(s, ItemStack.EMPTY); worn.put(s, ItemStack.EMPTY); } }
						public ItemStack getItemBySlot(EquipmentSlot slot) { return worn.get(slot); }
						public void setItemSlot(EquipmentSlot slot, ItemStack stack) { worn.put(slot, stack); }
						public boolean equipmentHasChanged(ItemStack before, ItemStack now) { return !ItemStack.matches(now, before); }
						public void setGlidingFromElsewhere(boolean on) { glidingFromElsewhere = on ? 1 : 0; }
						double getAttributeValue() { return Math.max(glidingFromEquipment, glidingFromElsewhere); }
						/** The tick applies the equipment to the attribute, handing the last-applied map over as the merged body does. */
						public void tick() { collectEquipmentChanges(lastEquipmentItems); }
						protected void collectEquipmentChanges(Map<EquipmentSlot, ItemStack> applied) {
							glidingFromEquipment = 0;
							for (EquipmentSlot s : EquipmentSlot.VALUES) {
								ItemStack before = applied.get(s), now = getItemBySlot(s);
								if (equipmentHasChanged(before, now)) applied.put(s, now);
								if (now.glider) glidingFromEquipment = 1;
							}
						}
						public boolean canGlide() { return canGlide(true); }
						protected boolean canGlide(boolean useAttribute) {
							double attribute = getAttributeValue();
							if (useAttribute) return attribute > 0;
							for (EquipmentSlot s : EquipmentSlot.VALUES) if (getItemBySlot(s).glider) return true;
							return false;
						}
					}
					""");
	private static final String NATIVE = """
			package net.minecraft.world.entity;
			import net.minecraft.world.item.ItemStack;
			public class LivingEntity {
				public ItemStack getItemBySlot(EquipmentSlot slot) { return ItemStack.EMPTY; }
				public boolean canGlide() { for (EquipmentSlot s : EquipmentSlot.VALUES) if (getItemBySlot(s).glider) return true; return false; }
			}
			""";

	@Test void anEntityThatHasJustPutOnAGliderIsAnsweredFromItsEquipment(@TempDir Path work) throws Throwable {
		Map<String, byte[]> merged = InjectorExecution.compile(work.resolve("merged"), MERGED);
		Map<String, String> nativeSources = new HashMap<>(MERGED);
		nativeSources.put(ENTITY, NATIVE);
		ClassNode reference = new ClassNode();
		new ClassReader(InjectorExecution.compile(work.resolve("native"), nativeSources).get(ENTITY.replace('.', '/'))).accept(reference, 0);
		Map<String, byte[]> classes = new HashMap<>(merged);
		String internal = ENTITY.replace('.', '/');
		classes.put(internal, InjectorExecution.transform(new GlideFreshEquipmentInjector((e, o) -> o.equals(internal) ? reference : null),
				ENTITY, merged.get(internal), EnvType.SERVER));
		ClassLoader woven = InjectorExecution.load(classes), shipped = InjectorExecution.load(merged);
		assertEquals("", InjectorExecution.verify(classes.get(internal), woven));

		assertEquals(java.util.List.of(true, true, false, false, true),
				sequence(woven), "woven: the equipment answers until the tick has applied it, the attribute after");
		assertEquals(java.util.List.of(false, true, true, false, true),
				sequence(shipped), "premise: as shipped, the cached attribute lags one tick behind the equipment");
	}

	/** equip → ask; tick → ask; unequip → ask; tick → ask; a gliding source other than equipment, no equipment change → ask. */
	private static java.util.List<Boolean> sequence(ClassLoader loader) throws Throwable {
		Class<?> type = loader.loadClass(ENTITY), slot = loader.loadClass("net.minecraft.world.entity.EquipmentSlot"),
				stack = loader.loadClass("net.minecraft.world.item.ItemStack");
		Object entity = type.getConstructor().newInstance();
		Object chest = slot.getField("CHEST").get(null), glider = stack.getConstructor(boolean.class).newInstance(true),
				empty = stack.getField("EMPTY").get(null);
		var set = type.getMethod("setItemSlot", slot, stack);
		var ask = type.getMethod("canGlide");
		var tick = type.getMethod("tick");
		java.util.List<Boolean> answers = new java.util.ArrayList<>();
		set.invoke(entity, chest, glider);
		answers.add((Boolean) ask.invoke(entity));
		tick.invoke(entity);
		answers.add((Boolean) ask.invoke(entity));
		set.invoke(entity, chest, empty);
		answers.add((Boolean) ask.invoke(entity));
		tick.invoke(entity);
		answers.add((Boolean) ask.invoke(entity));
		type.getMethod("setGlidingFromElsewhere", boolean.class).invoke(entity, true);
		answers.add((Boolean) ask.invoke(entity));
		return answers;
	}

	@Test void withoutANativeReferenceThatAsksTheEquipmentNothingChanges(@TempDir Path work) throws Exception {
		Map<String, byte[]> merged = InjectorExecution.compile(work, MERGED);
		byte[] entity = merged.get(ENTITY.replace('.', '/'));
		assertSame(entity, InjectorExecution.transform(new GlideFreshEquipmentInjector((e, o) -> null), ENTITY, entity, EnvType.SERVER));
	}
}
