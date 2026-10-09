package ru.moonlightproject.bridge.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a nested Java or Kotlin enum as a Protobuf enum. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface MoonLightEnumeration { }
