package dev.strataproxy.bukkit;

import dev.strataproxy.backend.api.BackendAgentApi;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bukkit-side adapter for the restricted StrataProxy backend-agent protocol. */
public final class StrataProxyBackendAgentPlugin extends JavaPlugin {
    private BackendAgentClient client;
    private BukkitBackendMessageApi messaging;
    private int heartbeatTask = -1;
    private boolean unregisterOnDisable;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            client = BackendAgentClient.from(getConfig());
        } catch (IllegalArgumentException exception) {
            getLogger().severe("Invalid StrataProxy backend-agent configuration: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        messaging = new BukkitBackendMessageApi(this);
        getServer().getServicesManager().register(BackendAgentApi.class, messaging, this, ServicePriority.Normal);
        unregisterOnDisable = getConfig().getBoolean("unregisterOnDisable", true);
        runAsync("register");
        long heartbeatTicks = Math.max(20L, getConfig().getLong("heartbeatSeconds", 10L) * 20L);
        heartbeatTask = getServer().getScheduler().scheduleAsyncRepeatingTask(this, new Runnable() {
            @Override
            public void run() {
                runAsync("heartbeat");
            }
        }, heartbeatTicks, heartbeatTicks);
    }

    @Override
    public void onDisable() {
        if (heartbeatTask >= 0) {
            getServer().getScheduler().cancelTask(heartbeatTask);
        }
        if (messaging != null) {
            getServer().getServicesManager().unregisterAll(this);
            messaging.close();
        }
        if (client != null && unregisterOnDisable) {
            runAsync("unregister");
        }
    }

    private void runAsync(final String operation) {
        getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                try {
                    String response = client.call(operation);
                    if (!response.contains("\"success\":true")) {
                        getLogger().warning("StrataProxy backend-agent " + operation + " failed: " + response);
                    }
                } catch (Exception exception) {
                    getLogger().warning("StrataProxy backend-agent " + operation + " failed: " + exception.getMessage());
                }
            }
        });
    }

    private static final class BackendAgentClient {
        private final String proxyHost;
        private final int proxyPort;
        private final String secret;
        private final String backendJson;
        private final String backendName;
        private final String instanceId = UUID.randomUUID().toString();

        private BackendAgentClient(String proxyHost, int proxyPort, String secret, String backendJson, String backendName) {
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.secret = secret;
            this.backendJson = backendJson;
            this.backendName = backendName;
        }

        static BackendAgentClient from(org.bukkit.configuration.file.FileConfiguration config) {
            String proxyHost = required(config.getString("proxy.host"), "proxy.host");
            int proxyPort = config.getInt("proxy.port", 25578);
            String secret = required(config.getString("proxy.sharedSecret"), "proxy.sharedSecret");
            if (secret.length() < 32) throw new IllegalArgumentException("proxy.sharedSecret must be at least 32 characters");
            String name = required(config.getString("backend.name"), "backend.name");
            String[] address = required(config.getString("backend.address"), "backend.address").split(":", 2);
            if (address.length != 2) throw new IllegalArgumentException("backend.address must be host:port");
            int backendPort = Integer.parseInt(address[1]);
            if (proxyPort < 1 || proxyPort > 65535 || backendPort < 1 || backendPort > 65535) throw new IllegalArgumentException("ports must be between 1 and 65535");
            ConfigurationSection metadata = config.getConfigurationSection("backend.metadata");
            StringBuilder json = new StringBuilder();
            json.append('{').append(field("name", name)).append(',').append(field("host", address[0])).append(',')
                    .append("\"port\":").append(backendPort).append(',')
                    .append(array("tags", config.getStringList("backend.tags"))).append(',')
                    .append(array("capabilities", config.getStringList("backend.capabilities"))).append(',')
                    .append(field("protocolName", config.getString("backend.protocolName", "any"))).append(',')
                    .append("\"minProtocol\":").append(config.getInt("backend.minProtocol", 0)).append(',')
                    .append("\"maxProtocol\":").append(config.getInt("backend.maxProtocol", Integer.MAX_VALUE)).append(',')
                    .append("\"weight\":").append(config.getInt("backend.weight", 100)).append(',')
                    .append("\"softCapacity\":").append(config.getInt("backend.softCapacity", 0)).append(',')
                    .append("\"hardCapacity\":").append(config.getInt("backend.hardCapacity", 0)).append(',')
                    .append("\"drainMode\":").append(config.getBoolean("backend.drainMode", false)).append(',')
                    .append("\"persistent\":").append(config.getBoolean("backend.persistent", false)).append(',')
                    .append(map("metadata", metadata));
            json.append('}');
            return new BackendAgentClient(proxyHost, proxyPort, secret, json.toString(), name);
        }

        String call(String operation) throws Exception {
            String payload = operation.equals("register") ? backendJsonWithOperation(operation) : "{\"operation\":\"" + operation + "\",\"name\":\"" + escape(backendName) + "\",\"instanceId\":\"" + instanceId + "\"}";
            String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            long timestamp = System.currentTimeMillis();
            String nonce = UUID.randomUUID().toString().replace("-", "");
            String signature = hex(hmac(backendName + "\n" + timestamp + "\n" + nonce + "\n" + encoded));
            String envelope = "{\"agentId\":\"" + escape(backendName) + "\",\"timestamp\":" + timestamp + ",\"nonce\":\"" + nonce + "\",\"payload\":\"" + encoded + "\",\"signature\":\"" + signature + "\"}";
            Socket socket = new Socket(proxyHost, proxyPort);
            try {
                socket.setSoTimeout(5000);
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out.write(envelope); out.newLine(); out.flush();
                String response = in.readLine();
                if (response == null) throw new IllegalStateException("proxy closed the registration connection");
                return response;
            } finally {
                socket.close();
            }
        }

        private String backendJsonWithOperation(String operation) {
            return "{\"operation\":\"" + operation + "\",\"instanceId\":\"" + instanceId + "\"," + backendJson.substring(1);
        }

        private byte[] hmac(String input) throws Exception {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        }

        private static String required(String value, String key) {
            if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(key + " must not be blank");
            return value.trim();
        }

        private static String field(String key, String value) { return "\"" + key + "\":\"" + escape(value) + "\""; }
        private static String array(String key, List<String> values) {
            List<String> escaped = new ArrayList<String>();
            for (String value : values) escaped.add("\"" + escape(value) + "\"");
            return "\"" + key + "\":[" + join(escaped) + "]";
        }
        private static String map(String key, ConfigurationSection values) {
            List<String> entries = new ArrayList<String>();
            if (values != null) for (String name : values.getKeys(false)) entries.add(field(name, String.valueOf(values.get(name))));
            return "\"" + key + "\":{" + join(entries) + "}";
        }
        private static String join(List<String> values) { return values.isEmpty() ? "" : joinValues(values); }
        private static String joinValues(List<String> values) { StringBuilder out = new StringBuilder(); for (String value : values) { if (out.length() > 0) out.append(','); out.append(value); } return out.toString(); }
        private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
        private static String hex(byte[] bytes) { StringBuilder out = new StringBuilder(); for (byte value : bytes) out.append(String.format("%02x", value & 0xff)); return out.toString(); }
    }
}
