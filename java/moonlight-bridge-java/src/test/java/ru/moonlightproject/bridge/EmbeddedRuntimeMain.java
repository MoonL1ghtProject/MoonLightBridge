package ru.moonlightproject.bridge;

import ru.moonlightproject.bridge.client.MoonLightTelemetry;

public final class EmbeddedRuntimeMain {
    private EmbeddedRuntimeMain() { }

    public static void main(String[] args) {
        Thread thread = Thread.currentThread();
        ClassLoader previousLoader = thread.getContextClassLoader();
        thread.setContextClassLoader(ClassLoader.getPlatformClassLoader());
        MoonLightTelemetry automatic;
        try {
            automatic = MoonLightTelemetry.automatic();
        } finally {
            thread.setContextClassLoader(previousLoader);
        }
        if (automatic == MoonLightTelemetry.disabled()) {
            throw new AssertionError("bundled runtime implementation was not discovered");
        }
        System.out.println("MoonLightBridge embedded runtime discovered successfully");
    }
}
