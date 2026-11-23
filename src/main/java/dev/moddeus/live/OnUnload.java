package dev.moddeus.live;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Static no-arg method run (on the server thread) when its class file is deleted (mc remove). The class stays loaded
 * until restart, so undo what it registered here: turn off its listeners' guard flag, remove its commands, etc.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface OnUnload {}
