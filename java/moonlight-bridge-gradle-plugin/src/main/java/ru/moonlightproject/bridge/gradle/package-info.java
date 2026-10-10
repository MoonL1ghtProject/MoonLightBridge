/**
 * Gradle plugin API for code-first and handwritten Protobuf compilation, typed client generation,
 * and schema compatibility locks.
 *
 * <p>Annotated Java/Kotlin contracts and {@code src/main/proto} are independently optional and may
 * be combined. Every input is normalized to a standard Protobuf descriptor before the plugin
 * generates messages, clients, policies, and readable telemetry operation names.</p>
 */
package ru.moonlightproject.bridge.gradle;
