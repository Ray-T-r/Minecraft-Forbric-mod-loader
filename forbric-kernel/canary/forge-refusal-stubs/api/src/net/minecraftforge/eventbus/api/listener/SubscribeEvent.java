package net.minecraftforge.eventbus.api.listener;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-only: EventBus 7's listener annotation, with its retention and target. Never shipped. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface SubscribeEvent {
	byte priority() default 0;

	boolean alwaysCancelling() default false;
}
