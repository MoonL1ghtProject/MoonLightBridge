package ru.moonlightproject.bridge.gradle;

import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

public abstract class MoonLightBridgeExtension {
    public abstract DirectoryProperty getProtoDirectory();
    public abstract RegularFileProperty getSchemaLock();
    public abstract DirectoryProperty getGeneratedSources();
    public abstract DirectoryProperty getGeneratedProtoSources();
    public abstract RegularFileProperty getDescriptorFile();
    public abstract Property<String> getProtocExecutable();
    public abstract Property<String> getCodegenExecutable();
}
