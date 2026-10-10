/* Copyright 2026 The Forbric Project. Licensed under the Apache License, Version 2.0. */
package net.forbric.kernel.mixin;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

import net.forbric.api.Ecosystem;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * fabric-api's two shared-result groups (the furnace's HEAD producer, the crafting recipe's closed WrapOperation
 * producer), renamed to an unrelated mixin and written the other ways Mixin reads the same thing. The transport finds the
 * redirect's host, its producer's host and both points as Mixin binds them: a selector owner-qualified, dotted, spaced or
 * doubled is the same host; a producer is on the consumer's host when it binds that method, however either is spelled; a
 * bare name binds the first method of that name; a point without its owner or descriptor names the call it selects in
 * the method the mod was written for. A look-alike written for another method, another owner or another overload, or a
 * short point that selects a second call there, is left byte for byte as it was.
 */
class MixinSharedResultTransportSpellingTest {
	private static final String FURNACE_MIXIN = "net/fabricmc/fabric/mixin/item/AbstractFurnaceBlockEntityMixin";
	private static final String FURNACE = "net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity";
	private static final String CRAFTING_MIXIN = "net/fabricmc/fabric/mixin/item/CraftingRecipeMixin";
	private static final String CRAFTING = "net/minecraft/world/item/crafting/CraftingRecipe";

	/** A group: the renamed mixin, the merged target and the class the mod was compiled against; how many methods it adds. */
	private record Group(String id, ClassNode mixin, ClassNode current, ClassNode reference, int expected, String producer, String host) {
		int adapt() {
			return MixinSharedResultTransport.adapt(mixin, n -> n.equals(current.name) ? current : null, n -> n.equals(reference.name) ? reference : null);
		}
	}

	private static Group furnace() throws Exception {
		return group("furnace HEAD producer", FURNACE_MIXIN, FURNACE, 1, TransportSpellings.INJECT, "consumeFuel");
	}
	private static Group crafting() throws Exception {
		return group("crafting closed producer", CRAFTING_MIXIN, CRAFTING, 2, TransportSpellings.WRAP, "defaultCraftingReminder");
	}
	private static Group group(String id, String mixin, String owner, int expected, String producer, String host) throws Exception {
		return new Group(id, MixinCallbackSelectorSpellingTest.unrelatedNames(TransportSpellings.fabricMixin("fabric-item-api-v1", mixin)),
				TransportSpellings.merged(owner), TransportSpellings.nativeClass(Ecosystem.FABRIC, owner), expected, producer, host);
	}
	private static List<Group> groups() throws Exception { return List.of(furnace(), crafting()); }

	@Test void theRenamedGroupsTransportAsShipped() throws Exception {
		for (Group group : groups()) assertEquals(group.expected(), group.adapt(), group.id() + ": premise, renamed and written as released");
	}

	/**
	 * The consumer's selector, the producer's, or both, written another way. Before, the host was found by comparing
	 * the selector with {@code name}/{@code name + desc} and the producer by comparing the two selector lists as
	 * strings: none of these was moved.
	 */
	@Test void eitherSelectorWrittenAnotherWayBindsTheSameHost() throws Exception {
		for (Map.Entry<String, BiFunction<String, String, List<String>>> spelling : PointRespelling.SELECTORS.entrySet()) {
			for (String which : List.of("consumer", "producer", "both")) {
				for (Group group : groups()) {
					Predicate<MethodNode> chosen = switch (which) {
						case "consumer" -> TransportSpellings.injector(TransportSpellings.REDIRECT);
						case "producer" -> TransportSpellings.injector(group.producer());
						default -> TransportSpellings.injector(TransportSpellings.REDIRECT).or(TransportSpellings.injector(group.producer()));
					};
					assertEquals(which.equals("both") ? 2 : 1, PointRespelling.selectors(group.mixin(), chosen, spelling.getValue()), "premise");
					assertEquals(group.expected(), group.adapt(), group.id() + ", " + which + " as " + spelling.getKey());
				}
			}
		}
	}

	/** A bare name with an overload declared after the host binds the host (before: two matches, refused). */
	@Test void aBareNameBindsTheFirstMethodOfThatName() throws Exception {
		Group furnace = furnace();
		TransportSpellings.overload(furnace.current(), "consumeFuel", "(I)V", Opcodes.ACC_PROTECTED, false);
		TransportSpellings.overload(furnace.reference(), "consumeFuel", "(I)V", Opcodes.ACC_PROTECTED, false);
		assertEquals(1, furnace.adapt(), "the furnace's bare consumeFuel binds the method declared first");
		Group crafting = crafting();
		TransportSpellings.overload(crafting.current(), "defaultCraftingReminder", "(I)Ljava/lang/Object;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false);
		TransportSpellings.overload(crafting.reference(), "defaultCraftingReminder", "(I)Ljava/lang/Object;", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, false);
		assertEquals(2, crafting.adapt());
	}

	/** Each point written without whitespace, with a dotted owner, or — where the written method decides — without owner or descriptor. */
	@Test void eachPointWrittenAnotherWayNamesTheSameCall() throws Exception {
		for (PointRespelling.Form form : PointRespelling.allForms()) {
			for (Group group : groups()) {
				int changed = PointRespelling.points(group.mixin(), PointRespelling::any, form, m -> PointRespelling.bound(m, group.reference()));
				assertTrue(changed >= 1, group.id() + ": premise, " + form.id() + " applies to the redirect's point");
				assertEquals(group.expected(), group.adapt(), group.id() + ", points " + form.id());
			}
		}
	}

	// ---- look-alikes: left alone ---------------------------------------------------------------------------------------

	@Test void aBareNameWhoseFirstMethodIsAnotherOverloadIsLeftAlone() throws Exception {
		for (boolean inMerged : List.of(true, false)) {
			Group furnace = furnace();
			if (inMerged) TransportSpellings.overload(furnace.current(), "consumeFuel", "(I)V", Opcodes.ACC_PROTECTED, true);
			TransportSpellings.overload(furnace.reference(), "consumeFuel", "(I)V", Opcodes.ACC_PROTECTED, true);
			assertUnchanged(furnace, inMerged ? "the bare name binds the overload declared first, in both classes"
					: "the mod's own class binds the other overload: it was not written for the merged host");
		}
	}

	@Test void aConsumerWrittenForAnotherMethodOrOwnerIsLeftAlone() throws Exception {
		for (Group group : groups()) {
			MethodNode other = TransportSpellings.another(group.current(), group.host(), (group.current().methods.stream()
					.filter(m -> m.name.equals(group.host())).findFirst().orElseThrow().access & Opcodes.ACC_STATIC) != 0);
			TransportSpellings.select(TransportSpellings.handler(group.mixin(), TransportSpellings.REDIRECT), "L" + group.current().name + ";" + other.name + other.desc);
			assertUnchanged(group, "a consumer on " + other.name);
		}
		for (Group group : groups()) {
			TransportSpellings.select(TransportSpellings.handler(group.mixin(), TransportSpellings.REDIRECT), "Lorg/example/elsewhere/Other;" + group.host());
			assertUnchanged(group, "a selector naming another owner");
		}
	}

	/** The producer bound to another method of the class: it sets no shared value in the consumer's host. */
	@Test void aProducerOnAnotherMethodIsNotTheConsumersProducer() throws Exception {
		for (Group group : groups()) {
			MethodNode producer = TransportSpellings.handler(group.mixin(), group.producer());
			MethodNode other = TransportSpellings.another(group.current(), group.host(), (group.current().methods.stream()
					.filter(m -> m.name.equals(group.host())).findFirst().orElseThrow().access & Opcodes.ACC_STATIC) != 0);
			TransportSpellings.select(producer, other.name + other.desc);
			assertUnchanged(group, "the producer written for " + other.name);
		}
	}

	/** A point without owner or descriptor that also selects another call in the written method names no one call. */
	@Test void aShortPointThatAlsoSelectsAnotherCallIsLeftAlone() throws Exception {
		for (boolean otherOwner : List.of(true, false)) {
			PointRespelling.Form form = PointRespelling.READ_IN_THE_WRITTEN_METHOD.get(otherOwner ? 0 : 1);
			for (Group group : groups()) {
				Predicate<MethodNode> redirect = TransportSpellings.injector(TransportSpellings.REDIRECT);
				assertTrue(PointRespelling.crowd(PointRespelling.copy(group.mixin()), redirect, group.reference(), otherOwner) >= 1, "premise");
				TransportSpellings.respell(TransportSpellings.handler(group.mixin(), TransportSpellings.REDIRECT), form);
				assertUnchanged(group, "the redirect's point " + form.id() + " beside a call of the same name " + (otherOwner ? "on another owner" : "of another descriptor"));
			}
		}
	}

	private static void assertUnchanged(Group group, String why) {
		byte[] before = TransportSpellings.bytes(group.mixin());
		assertEquals(0, group.adapt(), group.id() + ": " + why);
		assertArrayEquals(before, TransportSpellings.bytes(group.mixin()), group.id() + ": " + why + " — the mixin is not touched");
	}
}
