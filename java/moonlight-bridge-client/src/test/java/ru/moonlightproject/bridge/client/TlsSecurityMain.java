package ru.moonlightproject.bridge.client;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Negative TLS checks against the integration server's localhost-only certificate. */
public final class TlsSecurityMain {
    public static void main(String[] args) throws Exception {
        KeyStore trust = KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(Path.of(System.getProperty("javax.net.ssl.trustStore")))) {
            trust.load(in, "changeit".toCharArray());
        }
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext noIdentity = SSLContext.getInstance("TLS");
        noIdentity.init(new KeyManager[0], factory.getTrustManagers(), null);
        rejected("localhost", noIdentity, "client certificate is required");
        rejected("127.0.0.1", SSLContext.getDefault(), "hostname verification is required");
        System.out.println("mTLS rejects missing client identity and mismatched server hostname");
    }

    private static void rejected(String host, SSLContext context, String message) throws Exception {
        try (var ignored = MoonLightClient.tls(host, 38192, context)) {
            throw new AssertionError(message);
        } catch (IOException expected) { }
    }
}
