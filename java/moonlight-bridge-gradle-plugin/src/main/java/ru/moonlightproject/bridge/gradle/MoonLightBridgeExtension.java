package ru.moonlightproject.bridge.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/** Configurable paths and executable names used by the MoonLightBridge Gradle plugin. */
public abstract class MoonLightBridgeExtension {
    /** Creates the Gradle-managed extension. */
    public MoonLightBridgeExtension() { }

    /** Returns the directory containing source {@code .proto} files.
     * @return configurable source directory
     */
    public abstract DirectoryProperty getProtoDirectory();
    /** Returns the schema compatibility lock file.
     * @return configurable lock file
     */
    public abstract RegularFileProperty getSchemaLock();
    /** Returns the directory for generated MoonLightBridge Java clients.
     * @return configurable generated-client directory
     */
    public abstract DirectoryProperty getGeneratedSources();
    /** Returns the directory for generated Protobuf Java messages.
     * @return configurable generated-Protobuf directory
     */
    public abstract DirectoryProperty getGeneratedProtoSources();
    /** Returns the generated Protobuf descriptor-set file.
     * @return configurable descriptor file
     */
    public abstract RegularFileProperty getDescriptorFile();
    /** Returns the {@code protoc} executable or command name.
     * @return configurable executable name
     */
    public abstract Property<String> getProtocExecutable();
    /** Returns the {@code moonlight-bridge-codegen} executable or command name.
     * @return configurable executable name
     */
    public abstract Property<String> getCodegenExecutable();
}
