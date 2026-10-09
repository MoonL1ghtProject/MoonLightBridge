package ru.moonlightproject.bridge.codegen;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Declares one Java or Kotlin-owned MoonLightBridge contract. */
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface MoonLightContract {
    /** Canonical Protobuf package.
     * @return package written to the generated schema
     */
    String protoPackage();
    /** Package used by generated JVM bindings.
     * @return package for generated JVM message classes
     */
    String javaPackage();
}
