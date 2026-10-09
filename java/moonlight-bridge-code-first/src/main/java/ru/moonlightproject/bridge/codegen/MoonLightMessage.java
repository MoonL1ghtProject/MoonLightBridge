package ru.moonlightproject.bridge.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a record, data-class stub, or class as a Protobuf message declaration. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface MoonLightMessage { }
