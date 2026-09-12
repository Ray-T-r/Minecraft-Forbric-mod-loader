/*
 * Copyright 2026 The Forbric Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.forbric.api;

import java.util.List;

import net.forbric.kernel.util.ForbricLog;

/**
 * What MinecraftForge's {@code LoadingModList} is built from — held by the kernel, read straight out of the
 * lazy holder's class initializer.
 *
 * <h2>The wall this replaces</h2>
 *
 * <p>MinecraftForge builds its loading list in a lazy holder, and the holder reads a field the genuine loader
 * filled on its way past:
 *
 * <pre>{@code
 * static {};                                    // LoadingModListImpl$1LazyInit
 *    0: new           LoadingModListImpl
 *    3: dup
 *    4: getstatic     LoadingModListImpl.temp : ModSorter$State;
 *    7: invokevirtual ModSorter$State.files:()Ljava/util/List;      // NPE when temp is null
 *   10: getstatic     LoadingModListImpl.temp : ModSorter$State;
 *   13: invokevirtual ModSorter$State.mods:()Ljava/util/List;
 *   16: invokespecial LoadingModListImpl."<init>":(List;List;)V
 *   19: putstatic     INSTANCE
 * }</pre>
 *
 * <p>The kernel runs no genuine loader, so nothing fills {@code temp} on its own. It is seeded instead — but the
 * seed happens in the mod-loading window, and a class initializer is a ONE-SHOT: that method has an empty
 * exception table, so the first caller to arrive before the seed gets an NPE, and JVMS 5.5 then makes
 * {@code $1LazyInit} permanently erroneous. Every later {@code LoadingModList.getMods()} /
 * {@code getModFiles()} / {@code getModFileById()} is a {@code NoClassDefFoundError} for the rest of the run, in
 * a process where the list itself is perfectly computable. One early reader costs the whole MinecraftForge
 * ecosystem its mod list — including the multiplayer handshake, which then announces {@code mods=[]}.
 *
 * <p>Worse, the damage is invisible from the seeder. {@code LoadingModListImpl}'s own initializer only fetches a
 * logger and always succeeds, so it is the NESTED holder that goes erroneous: seeding afterwards still resolves
 * the class, still writes {@code temp}, and still logs that it seeded N mods — while every read fails. The
 * kernel could not tell a healthy instance from a poisoned one.
 *
 * <p>{@code ForgeLoadingListHolderInjector} therefore rewrites that initializer to take its two lists from here
 * instead of from {@code temp}, which makes the holder's correctness independent of WHEN it is first touched.
 *
 * <h2>Three states, not two</h2>
 *
 * <p>The obvious fix — inject {@code if (temp == null) temp = new State(List.of(), List.of())} — trades a loud
 * crash for a silent empty list, which is worse: {@code INSTANCE} is {@code final} and written once, so the
 * empty list is frozen for the run, and the real list can never replace it. So this holder distinguishes
 * "the answer is empty" from "there is no answer yet":
 *
 * <ul>
 *   <li>published with mods — the real list.</li>
 *   <li>published empty — this instance genuinely has no MinecraftForge-family mods. Logged once, at INFO.</li>
 *   <li>not published — {@link IllegalStateException}, naming the caller's stack. The holder still goes
 *       erroneous and the run still dies, exactly as it does today, but it says which code reached
 *       {@code LoadingModList} before the kernel had an answer instead of leaving a bare NPE on a field name.</li>
 * </ul>
 *
 * <p>Empty appears only where empty is the truth. "Not yet known" is always loud.
 *
 * <h2>Why {@code net.forbric.api} and not {@code net.forbric.kernel.runtime}</h2>
 *
 * <p>This package is {@code ALWAYS_PARENT}, so it is already loaded by the time any game class is defined. The
 * injected {@code invokestatic} can therefore run in the middle of Mixin's {@code prepareConfigs} — where the
 * holder is most likely to be touched first — without defining a single new game-side class and without
 * re-entering {@code select()}. The two return types are {@code java.util.List}, a JDK type, so the rewritten
 * initializer names nothing that is not already loaded. {@code ForeignModPresenceInjector} crosses from game
 * bytecode into {@link ModPresence} for the same reason.
 */
public final class ForgeLoadingList {
	/**
	 * Both lists in ONE field, so a reader can never see the files of one publish and the mods of another, and so
	 * "published" is a single volatile read rather than two that can disagree.
	 *
	 * @param modFiles MinecraftForge {@code ModFileInfo}s, held as {@code Object}s — this package is boot-side and
	 *                 cannot name a game-side type
	 * @param modInfos MinecraftForge {@code ModInfo}s, likewise
	 */
	private record Lists(List<?> modFiles, List<?> modInfos) {
	}

	private static volatile Lists published;

	private ForgeLoadingList() {
	}

	/**
	 * Hands the kernel's answer to the holder. Idempotent by FIRST WRITER: a later publish is ignored and logged,
	 * because {@code INSTANCE} may already have been built from the first one and re-publishing would put the
	 * holder and this field out of step without anyone noticing.
	 *
	 * @param modFiles the {@code ModFileInfo}s, in load order
	 * @param modInfos the {@code ModInfo}s, in load order
	 */
	public static synchronized void publish(List<?> modFiles, List<?> modInfos) {
		if (modFiles == null || modInfos == null) {
			throw new IllegalArgumentException("ForgeLoadingList.publish: neither list may be null — an absent "
					+ "answer must stay absent, so that reading it is loud rather than silently empty");
		}
		if (published != null) {
			ForbricLog.debug("[Forbric/ForgeList] already published (%d file(s), %d mod(s)) — ignoring a second "
					+ "publish of %d/%d; the holder may already have been built from the first",
					published.modFiles().size(), published.modInfos().size(), modFiles.size(), modInfos.size());
			return;
		}
		published = new Lists(List.copyOf(modFiles), List.copyOf(modInfos));
		// Deliberately says nothing about WHEN this happened. Whether the publish beat Mixin is the caller's fact,
		// not this method's, and a gate that reads its own timing guarantee out of a line written by the layer that
		// cannot know it is a gate that stays green through the regression it exists to catch.
		if (modInfos.isEmpty()) {
			ForbricLog.info("[Forbric/ForgeList] this instance has no MinecraftForge-family mods — publishing an "
					+ "EMPTY LoadingModList. That is the answer, not a failure: its handshake will truthfully say "
					+ "mods=[]. A list that is merely unknown is never published, and reading one throws.");
		} else {
			ForbricLog.info("[Forbric/ForgeList] MinecraftForge's LoadingModList is now %d file(s), %d mod(s). Its "
					+ "lazy holder reads this instead of LoadingModListImpl.temp, so whoever touches LoadingModList "
					+ "first — a mixin plugin during prepareConfigs, ServerStatusPing, a coremod — gets the real "
					+ "list instead of poisoning the holder for the whole run.",
					modFiles.size(), modInfos.size());
		}
	}

	/**
	 * Forgets the published answer. For tests, which need to exercise all three states in one JVM.
	 *
	 * <p>Deliberately not public and deliberately not called anywhere in production: first-writer-wins is a
	 * correctness property here, because {@code INSTANCE} may already have been built from the first publish and
	 * a second one would put the holder and this field out of step with nothing to notice it.
	 */
	static synchronized void reset() {
		published = null;
	}

	/** Whether an answer exists. Readers that must not throw ask this first. */
	public static boolean isPublished() {
		return published != null;
	}

	/** How many mods were published, or {@code -1} if nothing has been. Diagnostics and the read-back check. */
	public static int publishedModCount() {
		Lists lists = published;
		return lists == null ? -1 : lists.modInfos().size();
	}

	/**
	 * The {@code ModFileInfo}s. Called from the rewritten {@code $1LazyInit.<clinit>} — the descriptor
	 * {@code ()Ljava/util/List;} is part of the injected bytecode and must not change.
	 */
	public static List<?> modFiles() {
		return require().modFiles();
	}

	/**
	 * The {@code ModInfo}s. Called from the rewritten {@code $1LazyInit.<clinit>} — see {@link #modFiles()} for
	 * why the descriptor is frozen.
	 */
	public static List<?> modInfos() {
		return require().modInfos();
	}

	private static Lists require() {
		Lists lists = published;
		if (lists != null) return lists;
		throw new IllegalStateException("ForgeLoadingList: MinecraftForge's LoadingModList was read before the "
				+ "kernel published one. The holder that read it is a one-shot class initializer, so it is now "
				+ "permanently erroneous and every later LoadingModList call will fail too — this exception is "
				+ "where that started. The publish happens in KernelBoot before Mixin is installed; a caller "
				+ "earlier than that either ran during mod discovery or was reached from a static initializer "
				+ "the kernel seeds later. The stack below names it.");
	}
}
