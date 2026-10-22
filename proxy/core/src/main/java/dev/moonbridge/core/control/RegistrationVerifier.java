package dev.moonbridge.core.control;

import dev.moonbridge.app.ProxyConfiguration;
import dev.moonbridge.core.backend.BackendId;
import dev.moonbridge.core.backend.BackendOwner;
import dev.moonbridge.core.backend.BackendRegistration;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import static dev.moonbridge.core.control.ControlFailures.*;
import static dev.moonbridge.core.control.ControlMessages.*;
import static dev.moonbridge.core.control.ControlProtocol.*;

/** Authenticates a backend's registration frame against the configured clients. */
final class RegistrationVerifier {
    record Registration(String instanceId, String name, String generation, URI address,
                        java.util.Set<String> allowedNamespaces, java.util.Set<String> allowedReceiveNamespaces) { }

    private final ProxyConfiguration.BackendChannel configuration;

    RegistrationVerifier(ProxyConfiguration.BackendChannel configuration) { this.configuration = configuration; }

    Registration parse(DataInputStream input, byte[] nonce) throws Exception {
        String instanceId = readString(input, 64);
        String name = readString(input, 128);
        String address = readString(input, 1024);
        String generation = readString(input, 64);
        String keyId = readString(input, 64);
        byte[] signature = input.readNBytes(32);
        if (signature.length != 32) throw new EOFException();
        requireEmpty(input);
        UUID.fromString(generation);
        ProxyConfiguration.Client client = configuration.clients().get(instanceId);
        if (client == null || !client.backendName().equals(name) || !client.keyId().equals(keyId))
            throw new IOException("backend identity rejected");
        URI uri = URI.create(address);
        new BackendRegistration(new BackendId(name), new BackendOwner("backend-control:" + instanceId, 0), uri);
        if (!client.allowedHosts().contains(uri.getHost())) throw new IOException("advertised backend host rejected");
        ByteArrayOutputStream canonical = new ByteArrayOutputStream();
        canonical.write(nonce);
        DataOutputStream signed = new DataOutputStream(canonical);
        writeString(signed, instanceId);
        writeString(signed, name);
        writeString(signed, address);
        writeString(signed, generation);
        writeString(signed, keyId);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(client.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        if (!MessageDigest.isEqual(signature, mac.doFinal(canonical.toByteArray())))
            throw new IOException("backend signature rejected");
        return new Registration(instanceId, name, generation, uri, client.allowedNamespaces(),
                client.allowedReceiveNamespaces());
    }
}
