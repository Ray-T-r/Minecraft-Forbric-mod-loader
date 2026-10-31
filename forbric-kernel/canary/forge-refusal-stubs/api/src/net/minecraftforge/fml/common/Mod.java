package net.minecraftforge.fml.common;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import net.minecraftforge.api.distmarker.Dist;

/**
 * Compile-only: stands in for MinecraftForge's {@code @Mod}, with its retention, target and element defaults, so a
 * fixture's entry class carries the same {@code Lnet/minecraftforge/fml/common/Mod;} annotation a real build does.
 * Never shipped.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Mod {
	String value();

	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.TYPE)
	@interface EventBusSubscriber {
		Dist[] value() default {Dist.CLIENT, Dist.DEDICATED_SERVER};

		String modid() default "";

		Bus bus() default Bus.BOTH;

		enum Bus {
			BOTH
		}
	}
}
