package ru.moonlightproject.bridge.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks an interface as an RPC service declaration. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface MoonLightService { }
