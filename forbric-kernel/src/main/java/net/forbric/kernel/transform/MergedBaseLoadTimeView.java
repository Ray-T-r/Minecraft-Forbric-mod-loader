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

package net.forbric.kernel.transform;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.forbric.kernel.util.Caught;

/**
 * A staged game class as the kernel defines it: its bytes through the merged-base repairs
 * ({@link ForbricMergedBaseCompatTransformer}), with the other classes those repairs read looked up in the same jars.
 *
 * <p>For offline readers of the game's calls ({@code HookCallSiteCensus}): a repair can take a hook call away as well as
 * add one — {@code routePlaceItemHookToNeoForge} sends {@code ItemStack.useOn}'s MinecraftForge placement hook to
 * NeoForge's — and a reader of the staged bytes then counts a call that never runs. The repairs need no running game
 * (the transformer writes without computing frames), so they can be replayed here exactly as they run.
 */
public final class MergedBaseLoadTimeView implements BiFunction<String, byte[], byte[]>, AutoCloseable {
	private final List<ZipFile> jars = new ArrayList<>();
	private final ForbricMergedBaseCompatTransformer repairs = new ForbricMergedBaseCompatTransformer(this::resource);

	/** @param jars the merged base first, then whatever else a launch would serve classes from */
	public MergedBaseLoadTimeView(List<Path> jars) throws IOException {
		try {
			for (Path jar : jars) this.jars.add(new ZipFile(jar.toFile()));
		} catch (IOException failed) {
			close();
			throw failed;
		}
	}

	/** {@code internalName}'s bytes as defined, given its staged bytes. */
	@Override
	public byte[] apply(String internalName, byte[] staged) {
		return repairs.transform(internalName.replace('/', '.'), staged, null);
	}

	/** A resource ({@code some/Name.class}) from the jars, first one wins; null when none has it. */
	private byte[] resource(String path) {
		for (ZipFile jar : jars) {
			ZipEntry entry = jar.getEntry(path);
			if (entry == null) continue;
			try (InputStream in = jar.getInputStream(entry)) {
				return in.readAllBytes();
			} catch (IOException unreadable) {
				throw new java.io.UncheckedIOException(unreadable);
			}
		}
		return null;
	}

	@Override
	public void close() {
		for (ZipFile jar : jars) {
			try {
				jar.close();
			} catch (IOException failed) {
				Caught.warn(failed, "[Forbric/LoadTimeView] could not close %s", jar.getName());
			}
		}
		jars.clear();
	}
}
