package net.forbric.loader.impl.forge.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

class ForbricRegistrySyncBoundaryMixinTest {
	@Test
	void sendRegistriesHookDoesNotMutateKnownPacks() throws Exception {
		ForbricRegistrySyncBoundaryMixin mixin = new ForbricRegistrySyncBoundaryMixin();
		Method method = ForbricRegistrySyncBoundaryMixin.class.getDeclaredMethod("forbric$prepareMergedRegistrySync",
				Consumer.class, Set.class, CallbackInfo.class);
		method.setAccessible(true);

		LinkedHashSet<String> knownPacks = new LinkedHashSet<>();
		knownPacks.add("forbric/forge/server_data/physicsmod");
		knownPacks.add("vanilla");

		method.invoke(mixin, (Consumer<Object>) ignored -> {
		}, knownPacks, new CallbackInfo("sendRegistries", false));

		assertEquals(Set.of("forbric/forge/server_data/physicsmod", "vanilla"), knownPacks);
	}
}
