package dev.strataproxy.admin.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

@Command(
        name = "strataproxy-admin",
        mixinStandardHelpOptions = true,
        version = "strataproxy-admin 0.1",
        description = "Admin CLI for a running StrataProxy instance.",
        subcommands = {
                StrataProxyAdminCli.HealthCommand.class,
                StrataProxyAdminCli.ReadyCommand.class,
                StrataProxyAdminCli.OverviewCommand.class,
                StrataProxyAdminCli.SloCommand.class,
                StrataProxyAdminCli.MetricsCommand.class,
                StrataProxyAdminCli.DiagnosticsCommand.class,
                StrataProxyAdminCli.CompressionCommand.class,
                StrataProxyAdminCli.PacketsCommand.class,
                StrataProxyAdminCli.ModPayloadsCommand.class,
                StrataProxyAdminCli.PlayersCommand.class,
                StrataProxyAdminCli.AnomaliesCommand.class,
                StrataProxyAdminCli.BackpressureCommand.class,
                StrataProxyAdminCli.CapturesCommand.class,
                StrataProxyAdminCli.RoutesCommand.class,
                StrataProxyAdminCli.ServersCommand.class
        })
public final class StrataProxyAdminCli implements Callable<Integer> {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Option(names = "--base-url", defaultValue = "http://127.0.0.1:8080", description = "Admin API base URL.")
    private URI baseUrl;

    @Option(names = "--token", description = "Bearer token for protected admin endpoints.")
    private String token;

    @Option(names = "--timeout-ms", defaultValue = "5000", description = "HTTP request timeout in milliseconds.")
    private long timeoutMillis;

    @Option(names = "--trust-store-path", description = "Truststore path for HTTPS Admin API certificates.")
    private Path trustStorePath;

    @Option(names = "--trust-store-password", defaultValue = "", description = "Truststore password.")
    private String trustStorePassword;

    @Option(names = "--trust-store-type", defaultValue = "PKCS12", description = "Truststore type.")
    private String trustStoreType;

    @Option(names = "--key-store-path", description = "Client keystore path for mTLS Admin API authentication.")
    private Path keyStorePath;

    @Option(names = "--key-store-password", defaultValue = "", description = "Client keystore password.")
    private String keyStorePassword;

    @Option(names = "--key-store-type", defaultValue = "PKCS12", description = "Client keystore type.")
    private String keyStoreType;

    private HttpClient client;

    public static void main(String[] args) {
        System.exit(new CommandLine(new StrataProxyAdminCli()).execute(args));
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    Response request(String method, String path, String body) {
        try {
            var builder = HttpRequest.newBuilder(baseUrl.resolve(path))
                    .timeout(Duration.ofMillis(timeoutMillis));
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            if (body == null) {
                builder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.method(method, HttpRequest.BodyPublishers.ofString(body));
            }
            var response = httpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("request interrupted", exception);
        }
    }

    int print(Response response) {
        System.out.print(response.body());
        return response.statusCode() >= 200 && response.statusCode() < 300 ? 0 : 1;
    }

    void client(HttpClient client) {
        this.client = client;
    }

    void baseUrl(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    void token(String token) {
        this.token = token;
    }

    HttpClient httpClient() {
        if (client == null) {
            client = createHttpClient();
        }
        return client;
    }

    private HttpClient createHttpClient() {
        var builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMillis));
        if (trustStorePath != null || keyStorePath != null) {
            builder.sslContext(createSslContext());
        }
        return builder.build();
    }

    private SSLContext createSslContext() {
        try {
            var keyManagers = keyStorePath == null ? null : keyManagers(loadStore(keyStorePath, keyStoreType, keyStorePassword), keyStorePassword);
            var trustManagers = trustStorePath == null ? null : trustManagers(loadStore(trustStorePath, trustStoreType, trustStorePassword));
            var context = SSLContext.getInstance("TLS");
            context.init(keyManagers, trustManagers, null);
            return context;
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("failed to initialize TLS context: " + exception.getMessage(), exception);
        }
    }

    private static KeyStore loadStore(Path path, String type, String password) {
        if (path == null) {
            throw new IllegalArgumentException("store path must not be null");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("store type must not be blank");
        }
        if (Files.notExists(path)) {
            throw new IllegalArgumentException("store path does not exist: " + path);
        }
        try (var input = Files.newInputStream(path)) {
            var store = KeyStore.getInstance(type);
            store.load(input, passwordChars(password));
            return store;
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to read store: " + path, exception);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("failed to load store " + path + ": " + exception.getMessage(), exception);
        }
    }

    private static KeyManager[] keyManagers(KeyStore keyStore, String password) throws GeneralSecurityException {
        var factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore, passwordChars(password));
        return factory.getKeyManagers();
    }

    private static TrustManager[] trustManagers(KeyStore trustStore) throws GeneralSecurityException {
        var factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trustStore);
        return factory.getTrustManagers();
    }

    private static char[] passwordChars(String password) {
        return password == null ? new char[0] : password.toCharArray();
    }

    record Response(int statusCode, String body) {
    }

    @Command(name = "health", description = "Fetch /healthz.")
    static final class HealthCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            return root.print(root.request("GET", "/healthz", null));
        }
    }

    @Command(name = "ready", description = "Fetch /readyz.")
    static final class ReadyCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            return root.print(root.request("GET", "/readyz", null));
        }
    }

    @Command(name = "overview", description = "Show a compact operational summary.")
    static final class OverviewCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            var overview = root.request("GET", "/overview", null);
            if (overview.statusCode() >= 200 && overview.statusCode() < 300) {
                System.out.print(OverviewMetricsView.parseJson(overview.body()).render());
                return 0;
            }
            if (overview.statusCode() != 404 && overview.statusCode() != 405) {
                return root.print(overview);
            }
            var health = root.request("GET", "/healthz", null);
            if (health.statusCode() < 200 || health.statusCode() >= 300) {
                return root.print(health);
            }
            var metrics = root.request("GET", "/metrics", null);
            if (metrics.statusCode() < 200 || metrics.statusCode() >= 300) {
                return root.print(metrics);
            }
            var view = OverviewMetricsView.parse(health.body(), metrics.body());
            System.out.print(view.render());
            return 0;
        }
    }

    @Command(name = "slo", description = "Check operational SLO gates from /overview.")
    static final class SloCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Option(names = "--max-event-loop-delay-ms", defaultValue = "-1", description = "Maximum event loop delay in milliseconds. Negative disables this gate.")
        private double maxEventLoopDelayMillis;

        @Option(names = "--max-active-connections", defaultValue = "-1", description = "Maximum active connections. Negative disables this gate.")
        private long maxActiveConnections;

        @Option(names = "--max-rejected", defaultValue = "-1", description = "Maximum rejected connections. Negative disables this gate.")
        private long maxRejectedConnections;

        @Option(names = "--max-anomalies", defaultValue = "-1", description = "Maximum packet anomalies. Negative disables this gate.")
        private long maxAnomalies;

        @Option(names = "--require-native-transport", description = "Fail unless native transport is active.")
        private boolean requireNativeTransport;

        @Option(names = "--require-ready", description = "Fail unless /readyz reports READY.")
        private boolean requireReady;

        @Override
        public Integer call() {
            var overview = root.request("GET", "/overview", null);
            OverviewMetricsView view;
            if (overview.statusCode() >= 200 && overview.statusCode() < 300) {
                view = OverviewMetricsView.parseJson(overview.body());
            } else if (overview.statusCode() == 404 || overview.statusCode() == 405) {
                var health = root.request("GET", "/healthz", null);
                if (health.statusCode() < 200 || health.statusCode() >= 300) {
                    return root.print(health);
                }
                var metrics = root.request("GET", "/metrics", null);
                if (metrics.statusCode() < 200 || metrics.statusCode() >= 300) {
                    return root.print(metrics);
                }
                view = OverviewMetricsView.parse(health.body(), metrics.body());
            } else {
                return root.print(overview);
            }
            ReadyMetricsView readiness = null;
            if (requireReady) {
                var ready = root.request("GET", "/readyz", null);
                readiness = ReadyMetricsView.parseJson(ready.body(), ready.statusCode());
            }
            var slo = SloView.from(
                    view,
                    readiness,
                    maxEventLoopDelayMillis,
                    maxActiveConnections,
                    maxRejectedConnections,
                    maxAnomalies,
                    requireNativeTransport);
            System.out.print(slo.render());
            return slo.passed() ? 0 : 1;
        }
    }

    @Command(name = "diagnostics", description = "Export one structured diagnostic report JSON document.")
    static final class DiagnosticsCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            return root.print(root.request("GET", "/diagnostic-report", null));
        }
    }

    @Command(name = "metrics", description = "Fetch Prometheus /metrics.")
    static final class MetricsCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            return root.print(root.request("GET", "/metrics", null));
        }
    }

    @Command(name = "compression", description = "Show compression audit summary from /metrics.")
    static final class CompressionCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            var report = root.request("GET", "/compression-report", null);
            if (report.statusCode() >= 200 && report.statusCode() < 300) {
                System.out.print(CompressionMetricsView.parseJson(report.body()).render());
                return 0;
            }
            if (report.statusCode() != 404 && report.statusCode() != 405) {
                return root.print(report);
            }
            var response = root.request("GET", "/metrics", null);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return root.print(response);
            }
            var view = CompressionMetricsView.parse(response.body());
            System.out.print(view.render());
            return 0;
        }
    }

    @Command(name = "packets", description = "Show top packet traffic counters.")
    static final class PacketsCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            var response = root.request("GET", "/packet-traffic", null);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 404 || response.statusCode() == 405) {
                    var metrics = root.request("GET", "/metrics", null);
                    if (metrics.statusCode() < 200 || metrics.statusCode() >= 300) {
                        return root.print(metrics);
                    }
                    System.out.print(PacketTrafficMetricsView.parse(metrics.body()).render());
                    return 0;
                }
                return root.print(response);
            }
            System.out.print(PacketTrafficMetricsView.parseJson(response.body()).render());
            return 0;
        }
    }

    @Command(name = "mod-payloads", description = "Show classified modded custom payload counters.")
    static final class ModPayloadsCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            var response = root.request("GET", "/custom-payloads", null);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return root.print(response);
            }
            System.out.print(CustomPayloadMetricsView.parseJson(response.body()).render());
            return 0;
        }
    }

    @Command(name = "players", description = "Show active player sessions.")
    static final class PlayersCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            var response = root.request("GET", "/player-sessions", null);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return root.print(response);
            }
            System.out.print(PlayerSessionMetricsView.parseJson(response.body()).render());
            return 0;
        }
    }

    @Command(name = "anomalies", description = "Show packet anomaly counters.")
    static final class AnomaliesCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Option(names = "--samples", description = "Print recent packet anomaly samples after the rule summary.")
        private boolean samples;

        @Override
        public Integer call() {
            var response = root.request("GET", "/packet-anomalies", null);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                if (response.statusCode() == 404 || response.statusCode() == 405) {
                    var metrics = root.request("GET", "/metrics", null);
                    if (metrics.statusCode() < 200 || metrics.statusCode() >= 300) {
                        return root.print(metrics);
                    }
                    System.out.print(AnomalyMetricsView.parse(metrics.body()).render());
                    return 0;
                }
                return root.print(response);
            }
            var view = AnomalyMetricsView.parseJson(response.body());
            System.out.print(view.render());
            if (samples) {
                System.out.print(view.renderSamples());
            }
            return 0;
        }
    }

    @Command(name = "backpressure", description = "Show relay write backpressure summary.")
    static final class BackpressureCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            var diagnostics = root.request("GET", "/diagnostic-report", null);
            if (diagnostics.statusCode() >= 200 && diagnostics.statusCode() < 300) {
                System.out.print(BackpressureMetricsView.parseJson(diagnostics.body()).render());
                return 0;
            }
            if (diagnostics.statusCode() != 404 && diagnostics.statusCode() != 405) {
                return root.print(diagnostics);
            }
            var metrics = root.request("GET", "/metrics", null);
            if (metrics.statusCode() < 200 || metrics.statusCode() >= 300) {
                return root.print(metrics);
            }
            System.out.print(BackpressureMetricsView.parse(metrics.body()).render());
            return 0;
        }
    }

    @Command(name = "captures", mixinStandardHelpOptions = true, description = "Manage payload prefix captures.", subcommands = {
            CapturesCommand.StartCommand.class,
            CapturesCommand.GetCommand.class,
            CapturesCommand.StopCommand.class
    })
    static final class CapturesCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            var response = root.request("GET", "/payload-captures", null);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return root.print(response);
            }
            System.out.print(PayloadCaptureMetricsView.parseListJson(response.body()).render());
            return 0;
        }

        @Command(name = "start", description = "Start a bounded payload prefix capture.")
        static final class StartCommand implements Callable<Integer> {
            @ParentCommand
            private CapturesCommand captures;

            @Option(names = "--id", description = "Capture id. Generated by the server when omitted.")
            private String id;

            @Option(names = "--server", required = true, description = "Backend server name to capture.")
            private String server;

            @Option(names = "--direction", defaultValue = "frontend_to_backend", description = "Traffic direction.")
            private String direction;

            @Option(names = "--max-samples", defaultValue = "64", description = "Maximum retained samples.")
            private int maxSamples;

            @Option(names = "--max-bytes", defaultValue = "256", description = "Maximum bytes retained per sample.")
            private int maxBytesPerSample;

            @Option(names = "--duration-ms", defaultValue = "30000", description = "Capture duration in milliseconds.")
            private long durationMillis;

            @Override
            public Integer call() {
                var json = MAPPER.createObjectNode();
                if (id != null && !id.isBlank()) {
                    json.put("id", id);
                }
                json.put("server", server);
                json.put("direction", direction);
                json.put("maxSamples", maxSamples);
                json.put("maxBytesPerSample", maxBytesPerSample);
                json.put("durationMillis", durationMillis);

                var response = captures.root.request("POST", "/payload-captures", json.toString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return captures.root.print(response);
                }
                System.out.print(PayloadCaptureMetricsView.parseCaptureJson(response.body()).render());
                return 0;
            }
        }

        @Command(name = "get", description = "Export one payload prefix capture.")
        static final class GetCommand implements Callable<Integer> {
            @ParentCommand
            private CapturesCommand captures;

            @CommandLine.Parameters(index = "0", description = "Capture id.")
            private String id;

            @Override
            public Integer call() {
                var response = captures.root.request("GET", "/payload-captures/" + id, null);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return captures.root.print(response);
                }
                System.out.print(PayloadCaptureExportView.parseJson(response.body()).render());
                return 0;
            }
        }

        @Command(name = "stop", description = "Stop and remove one payload prefix capture.")
        static final class StopCommand implements Callable<Integer> {
            @ParentCommand
            private CapturesCommand captures;

            @CommandLine.Parameters(index = "0", description = "Capture id.")
            private String id;

            @Override
            public Integer call() {
                return captures.root.print(captures.root.request("DELETE", "/payload-captures/" + id, null));
            }
        }
    }

    @Command(name = "routes", mixinStandardHelpOptions = true, description = "Preview routing decisions.", subcommands = {
            RoutesCommand.PreviewCommand.class
    })
    static final class RoutesCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            CommandLine.usage(this, System.out);
            return 0;
        }

        @Command(name = "preview", description = "Preview where a route request would land.")
        static final class PreviewCommand implements Callable<Integer> {
            @ParentCommand
            private RoutesCommand routes;

            @Option(names = "--route", description = "Requested route, host, server name, tag, or alias.")
            private String route;

            @Option(names = "--protocol-version", required = true, description = "Minecraft protocol version.")
            private int protocolVersion;

            @Option(names = "--remote-address", defaultValue = "127.0.0.1:0", description = "Client remote address used for stable weighted selection.")
            private String remoteAddress;

            @Option(names = "--tag", split = ",", description = "Required tag. Can be repeated or comma-separated.")
            private List<String> tags = List.of();

            @Option(names = "--capability", split = ",", description = "Required capability. Can be repeated or comma-separated.")
            private List<String> capabilities = List.of();

            @Override
            public Integer call() {
                var response = routes.root.request("GET", path(), null);
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return routes.root.print(response);
                }
                var view = RoutePreviewMetricsView.parseJson(response.body());
                System.out.print(view.render());
                return view.selected ? 0 : 1;
            }

            private String path() {
                var params = new ArrayList<String>();
                if (route != null && !route.isBlank()) {
                    params.add("route=" + encode(route));
                }
                params.add("protocolVersion=" + protocolVersion);
                params.add("remoteAddress=" + encode(remoteAddress));
                for (var tag : compact(tags)) {
                    params.add("tag=" + encode(tag));
                }
                for (var capability : compact(capabilities)) {
                    params.add("capability=" + encode(capability));
                }
                return "/routes/preview?" + String.join("&", params);
            }

            private static String encode(String value) {
                return URLEncoder.encode(value, StandardCharsets.UTF_8);
            }

            private static List<String> compact(List<String> values) {
                var result = new ArrayList<String>();
                for (var value : values) {
                    if (value != null && !value.isBlank()) {
                        result.add(value.trim());
                    }
                }
                return List.copyOf(result);
            }
        }
    }

    @Command(name = "servers", mixinStandardHelpOptions = true, description = "Manage registered backend servers.", subcommands = {
            ServersCommand.ListCommand.class,
            ServersCommand.GetCommand.class,
            ServersCommand.RegisterCommand.class,
            ServersCommand.UpdateCommand.class,
            ServersCommand.RemoveCommand.class,
            ServersCommand.DrainCommand.class,
            ServersCommand.UndrainCommand.class,
            ServersCommand.HealthCommand.class,
            ServersCommand.LoadCommand.class
    })
    static final class ServersCommand implements Callable<Integer> {
        @ParentCommand
        private StrataProxyAdminCli root;

        @Override
        public Integer call() {
            return root.print(root.request("GET", "/servers", null));
        }

        @Command(name = "list", description = "List registered backend servers.")
        static final class ListCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @Override
            public Integer call() {
                return servers.root.print(servers.root.request("GET", "/servers", null));
            }
        }

        @Command(name = "get", description = "Get one backend server.")
        static final class GetCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @CommandLine.Parameters(index = "0", description = "Server name.")
            private String name;

            @Override
            public Integer call() {
                return servers.root.print(servers.root.request("GET", "/servers/" + name, null));
            }
        }

        @Command(name = "register", description = "Register or replace a backend server.")
        static final class RegisterCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @Option(names = "--name", required = true)
            private String name;

            @Option(names = "--address", required = true, description = "Host:port backend address.")
            private String address;

            @Option(names = "--tag", split = ",", description = "Server tag. Can be repeated or comma-separated.")
            private List<String> tags = List.of();

            @Option(names = "--capability", split = ",", description = "Server capability. Can be repeated or comma-separated.")
            private List<String> capabilities = List.of();

            @Option(names = "--protocol-range", defaultValue = "any")
            private String protocolRange;

            @Option(names = "--weight", defaultValue = "100")
            private int weight;

            @Option(names = "--soft-capacity", defaultValue = "500")
            private int softCapacity;

            @Option(names = "--hard-capacity", defaultValue = "600")
            private int hardCapacity;

            @Option(names = "--drain")
            private boolean drainMode;

            @Option(names = "--metadata", split = ",", description = "Metadata key=value. Can be repeated or comma-separated.")
            private List<String> metadata = List.of();

            @Override
            public Integer call() {
                var json = MAPPER.createObjectNode();
                json.put("name", name);
                json.put("address", address);
                json.set("tags", MAPPER.valueToTree(compact(tags)));
                json.set("capabilities", MAPPER.valueToTree(compact(capabilities)));
                json.put("protocolRange", protocolRange);
                json.put("weight", weight);
                json.put("softCapacity", softCapacity);
                json.put("hardCapacity", hardCapacity);
                json.put("drainMode", drainMode);
                json.set("metadata", MAPPER.valueToTree(parseMetadata(metadata)));
                return servers.root.print(servers.root.request("POST", "/servers", json.toString()));
            }
        }

        @Command(name = "update", description = "Partially update a registered backend server.")
        static final class UpdateCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @CommandLine.Parameters(index = "0", description = "Server name.")
            private String name;

            @Option(names = "--address", description = "Host:port backend address.")
            private String address;

            @Option(names = "--tag", split = ",", description = "Replace server tags. Can be repeated or comma-separated.")
            private List<String> tags;

            @Option(names = "--capability", split = ",", description = "Replace server capabilities. Can be repeated or comma-separated.")
            private List<String> capabilities;

            @Option(names = "--protocol-range")
            private String protocolRange;

            @Option(names = "--weight")
            private Integer weight;

            @Option(names = "--soft-capacity")
            private Integer softCapacity;

            @Option(names = "--hard-capacity")
            private Integer hardCapacity;

            @Option(names = "--drain-mode", arity = "1", description = "Set descriptor drain mode to true or false.")
            private Boolean drainMode;

            @Option(names = "--metadata", split = ",", description = "Replace metadata with key=value pairs. Can be repeated or comma-separated.")
            private List<String> metadata;

            @Override
            public Integer call() {
                var json = MAPPER.createObjectNode();
                if (address != null) {
                    json.put("address", address);
                }
                if (tags != null) {
                    json.set("tags", MAPPER.valueToTree(compact(tags)));
                }
                if (capabilities != null) {
                    json.set("capabilities", MAPPER.valueToTree(compact(capabilities)));
                }
                if (protocolRange != null) {
                    json.put("protocolRange", protocolRange);
                }
                if (weight != null) {
                    json.put("weight", weight);
                }
                if (softCapacity != null) {
                    json.put("softCapacity", softCapacity);
                }
                if (hardCapacity != null) {
                    json.put("hardCapacity", hardCapacity);
                }
                if (drainMode != null) {
                    json.put("drainMode", drainMode);
                }
                if (metadata != null) {
                    json.set("metadata", MAPPER.valueToTree(parseMetadata(metadata)));
                }
                return servers.root.print(servers.root.request("PATCH", "/servers/" + name, json.toString()));
            }
        }

        @Command(name = "remove", description = "Remove a backend server.")
        static final class RemoveCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @CommandLine.Parameters(index = "0", description = "Server name.")
            private String name;

            @Override
            public Integer call() {
                return servers.root.print(servers.root.request("DELETE", "/servers/" + name, null));
            }
        }

        @Command(name = "drain", description = "Put a backend server into drain mode.")
        static final class DrainCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @CommandLine.Parameters(index = "0", description = "Server name.")
            private String name;

            @Override
            public Integer call() {
                return servers.root.print(servers.root.request("POST", "/servers/" + name + "/drain", "{}"));
            }
        }

        @Command(name = "undrain", description = "Take a backend server out of drain mode.")
        static final class UndrainCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @CommandLine.Parameters(index = "0", description = "Server name.")
            private String name;

            @Override
            public Integer call() {
                return servers.root.print(servers.root.request("POST", "/servers/" + name + "/undrain", "{}"));
            }
        }

        @Command(name = "health", description = "Update backend server health.")
        static final class HealthCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @CommandLine.Parameters(index = "0", description = "Server name.")
            private String name;

            @Option(names = "--status", defaultValue = "UP")
            private String status;

            @Option(names = "--ping-ms", defaultValue = "-1")
            private long backendPingMillis;

            @Option(names = "--failure-rate", defaultValue = "0.0")
            private double recentFailureRate;

            @Option(names = "--reason", defaultValue = "")
            private String reason;

            @Override
            public Integer call() {
                var json = MAPPER.createObjectNode();
                json.put("status", status);
                json.put("backendPingMillis", backendPingMillis);
                json.put("recentFailureRate", recentFailureRate);
                json.put("reason", reason);
                return servers.root.print(servers.root.request("POST", "/servers/" + name + "/health", json.toString()));
            }
        }

        @Command(name = "load", description = "Update backend server load.")
        static final class LoadCommand implements Callable<Integer> {
            @ParentCommand
            private ServersCommand servers;

            @CommandLine.Parameters(index = "0", description = "Server name.")
            private String name;

            @Option(names = "--players", defaultValue = "0")
            private int players;

            @Option(names = "--soft-capacity", defaultValue = "500")
            private int softCapacity;

            @Option(names = "--hard-capacity", defaultValue = "600")
            private int hardCapacity;

            @Option(names = "--in-bps", defaultValue = "0")
            private long inboundBytesPerSecond;

            @Option(names = "--out-bps", defaultValue = "0")
            private long outboundBytesPerSecond;

            @Option(names = "--pps", defaultValue = "0")
            private long packetsPerSecond;

            @Option(names = "--event-loop-delay-ms", defaultValue = "0.0")
            private double eventLoopDelayMillis;

            @Override
            public Integer call() {
                ObjectNode json = MAPPER.createObjectNode();
                json.put("players", players);
                json.put("softCapacity", softCapacity);
                json.put("hardCapacity", hardCapacity);
                json.put("inboundBytesPerSecond", inboundBytesPerSecond);
                json.put("outboundBytesPerSecond", outboundBytesPerSecond);
                json.put("packetsPerSecond", packetsPerSecond);
                json.put("eventLoopDelayMillis", eventLoopDelayMillis);
                return servers.root.print(servers.root.request("POST", "/servers/" + name + "/load", json.toString()));
            }
        }

        private static List<String> compact(List<String> values) {
            var result = new ArrayList<String>();
            for (var value : values) {
                if (value != null && !value.isBlank()) {
                    result.add(value.trim());
                }
            }
            return List.copyOf(result);
        }

        private static Map<String, String> parseMetadata(List<String> values) {
            var result = new java.util.LinkedHashMap<String, String>();
            for (var value : compact(values)) {
                var splitAt = value.indexOf('=');
                if (splitAt <= 0) {
                    throw new IllegalArgumentException("metadata must use key=value: " + value);
                }
                result.put(value.substring(0, splitAt), value.substring(splitAt + 1));
            }
            return Map.copyOf(result);
        }
    }

    private static final class CompressionMetricsView {
        private final CompressionRow global = new CompressionRow("global");
        private final Map<String, CompressionRow> servers = new LinkedHashMap<>();
        private final Map<String, CompressionRow> directions = new LinkedHashMap<>();
        private final List<CompressionDecisionRow> decisions = new ArrayList<>();
        private final List<CompressionRewriteRow> rewrites = new ArrayList<>();
        private long negotiations;

        static CompressionMetricsView parse(String metrics) {
            var view = new CompressionMetricsView();
            for (var line : metrics.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                view.accept(line);
            }
            return view;
        }

        static CompressionMetricsView parseJson(String json) {
            var view = new CompressionMetricsView();
            try {
                var root = MAPPER.readTree(json);
                var rows = root.get("rows");
                if (rows != null && rows.isArray()) {
                    for (var rowNode : rows) {
                        var row = new CompressionRow(text(rowNode, "scope", ""));
                        row.direction = text(rowNode, "direction", "all");
                        row.rawBytes = number(rowNode, "rawBytes");
                        row.compressedBytes = number(rowNode, "compressedBytes");
                        row.savedBytes = number(rowNode, "savedBytes");
                        row.ratio = decimal(rowNode, "ratio", 1.0d);
                        row.samples = number(rowNode, "samples");
                        row.threshold = number(rowNode, "threshold");
                        var negotiations = number(rowNode, "negotiations");
                        if (row.scope.equals("global") && row.direction.equals("all")) {
                            view.global.samples = row.samples;
                            view.global.rawBytes = row.rawBytes;
                            view.global.compressedBytes = row.compressedBytes;
                            view.global.savedBytes = row.savedBytes;
                            view.global.ratio = row.ratio;
                            view.global.threshold = row.threshold;
                            view.negotiations = negotiations;
                        } else if (row.direction.equals("all")) {
                            view.servers.put(row.scope, row);
                        } else {
                            view.directions.put(row.scope + "|" + row.direction, row);
                        }
                    }
                }
                var decisions = root.get("decisions");
                if (decisions != null && decisions.isArray()) {
                    for (var decision : decisions) {
                        view.decisions.add(new CompressionDecisionRow(
                                text(decision, "scope", ""),
                                text(decision, "direction", ""),
                                text(decision, "action", ""),
                                number(decision, "threshold"),
                                number(decision, "count")));
                    }
                }
                var rewrites = root.get("rewrites");
                if (rewrites != null && rewrites.isArray()) {
                    for (var rewrite : rewrites) {
                        view.rewrites.add(new CompressionRewriteRow(
                                text(rewrite, "scope", ""),
                                text(rewrite, "direction", ""),
                                text(rewrite, "outcome", ""),
                                number(rewrite, "count"),
                                number(rewrite, "cpuNanos")));
                    }
                }
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        String render() {
            var output = new StringBuilder();
            output.append("scope direction raw_bytes compressed_bytes saved_bytes ratio samples threshold negotiations\n");
            output.append(global.render(negotiations)).append('\n');
            for (var row : servers.values()) {
                output.append(row.render(0)).append('\n');
            }
            for (var row : directions.values()) {
                output.append(row.render(0)).append('\n');
            }
            output.append("decision_scope direction action threshold count\n");
            decisions.stream()
                    .sorted(Comparator
                            .comparing(CompressionDecisionRow::scope)
                            .thenComparing(CompressionDecisionRow::direction)
                            .thenComparing(CompressionDecisionRow::action)
                            .thenComparingLong(CompressionDecisionRow::threshold))
                    .forEach(row -> output.append(row.render()).append('\n'));
            output.append("rewrite_scope direction outcome count cpu_millis\n");
            rewrites.stream()
                    .sorted(Comparator
                            .comparing(CompressionRewriteRow::scope)
                            .thenComparing(CompressionRewriteRow::direction)
                            .thenComparing(CompressionRewriteRow::outcome))
                    .forEach(row -> output.append(row.render()).append('\n'));
            return output.toString();
        }

        private void accept(String line) {
            var splitAt = line.lastIndexOf(' ');
            if (splitAt <= 0 || splitAt == line.length() - 1) {
                return;
            }
            var nameAndLabels = line.substring(0, splitAt);
            var value = parseDouble(line.substring(splitAt + 1));
            var name = metricName(nameAndLabels);
            var server = labelValue(nameAndLabels, "server");
            var direction = labelValue(nameAndLabels, "direction");
            if (name.equals("strataproxy_compression_decisions_total")) {
                acceptDecision(nameAndLabels, value);
                return;
            }
            if (name.equals("strataproxy_compression_rewrites_total")) {
                acceptRewrite(nameAndLabels, value, 0);
                return;
            }
            if (name.equals("strataproxy_compression_rewrite_cpu_seconds_total")) {
                acceptRewrite(nameAndLabels, 0, Math.round(value * 1_000_000_000L));
                return;
            }
            if (server == null) {
                acceptGlobal(name, value);
            } else if (direction == null) {
                acceptServer(servers.computeIfAbsent(server, CompressionRow::new), name, value);
            } else {
                acceptServer(directionalRow(server, direction), name, value);
            }
        }

        private void acceptGlobal(String name, double value) {
            switch (name) {
                case "strataproxy_compression_samples_total" -> global.samples = (long) value;
                case "strataproxy_compression_raw_bytes_total" -> global.rawBytes = (long) value;
                case "strataproxy_compression_compressed_bytes_total" -> global.compressedBytes = (long) value;
                case "strataproxy_compression_saved_bytes_total" -> global.savedBytes = (long) value;
                case "strataproxy_compression_ratio" -> global.ratio = value;
                case "strataproxy_compression_negotiations_total" -> negotiations = (long) value;
                default -> {
                }
            }
        }

        private void acceptServer(CompressionRow row, String name, double value) {
            switch (name) {
                case "strataproxy_compression_samples_total" -> row.samples = (long) value;
                case "strataproxy_compression_raw_bytes_total" -> row.rawBytes = (long) value;
                case "strataproxy_compression_compressed_bytes_total" -> row.compressedBytes = (long) value;
                case "strataproxy_compression_saved_bytes_total" -> row.savedBytes = (long) value;
                case "strataproxy_compression_ratio" -> row.ratio = value;
                case "strataproxy_server_compression_threshold_bytes" -> row.threshold = (long) value;
                default -> {
                }
            }
        }

        private CompressionRow directionalRow(String server, String direction) {
            return directions.computeIfAbsent(server + "|" + direction, ignored -> {
                var row = new CompressionRow(server);
                row.direction = direction;
                return row;
            });
        }

        private void acceptDecision(String nameAndLabels, double value) {
            var server = labelValue(nameAndLabels, "server");
            var direction = labelValue(nameAndLabels, "direction");
            var action = labelValue(nameAndLabels, "action");
            var threshold = labelValue(nameAndLabels, "threshold");
            if (server == null || direction == null || action == null || threshold == null) {
                return;
            }
            decisions.add(new CompressionDecisionRow(server, direction, action, parseLong(threshold), (long) value));
        }

        private void acceptRewrite(String nameAndLabels, double count, long cpuNanos) {
            var server = labelValue(nameAndLabels, "server");
            var direction = labelValue(nameAndLabels, "direction");
            var outcome = labelValue(nameAndLabels, "outcome");
            if (server == null || direction == null || outcome == null) {
                return;
            }
            var existing = -1;
            for (var i = 0; i < rewrites.size(); i++) {
                var row = rewrites.get(i);
                if (row.scope().equals(server) && row.direction().equals(direction) && row.outcome().equals(outcome)) {
                    existing = i;
                    break;
                }
            }
            if (existing >= 0) {
                var row = rewrites.get(existing);
                rewrites.set(existing, new CompressionRewriteRow(
                        row.scope(),
                        row.direction(),
                        row.outcome(),
                        row.count() + (long) count,
                        row.cpuNanos() + cpuNanos));
            } else {
                rewrites.add(new CompressionRewriteRow(server, direction, outcome, (long) count, cpuNanos));
            }
        }

        private static String metricName(String nameAndLabels) {
            var labelsAt = nameAndLabels.indexOf('{');
            return labelsAt < 0 ? nameAndLabels : nameAndLabels.substring(0, labelsAt);
        }

        private static String labelValue(String nameAndLabels, String label) {
            var labelsAt = nameAndLabels.indexOf('{');
            if (labelsAt < 0 || !nameAndLabels.endsWith("}")) {
                return null;
            }
            var needle = label + "=\"";
            var start = nameAndLabels.indexOf(needle, labelsAt);
            if (start < 0) {
                return null;
            }
            start += needle.length();
            var end = nameAndLabels.indexOf('"', start);
            return end < 0 ? null : nameAndLabels.substring(start, end);
        }

        private static double parseDouble(String value) {
            try {
                return Double.parseDouble(value);
            } catch (NumberFormatException exception) {
                return 0.0d;
            }
        }

        private static long parseLong(String value) {
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException exception) {
                return 0L;
            }
        }

        private static String text(com.fasterxml.jackson.databind.JsonNode node, String field, String fallback) {
            return node.has(field) ? node.get(field).asText() : fallback;
        }

        private static long number(com.fasterxml.jackson.databind.JsonNode node, String field) {
            return node.has(field) ? node.get(field).asLong() : 0L;
        }

        private static double decimal(com.fasterxml.jackson.databind.JsonNode node, String field, double fallback) {
            return node.has(field) ? node.get(field).asDouble() : fallback;
        }
    }

    private static final class OverviewMetricsView {
        private String healthStatus = "unknown";
        private long configuredServers;
        private long activeConnections;
        private long routedConnections;
        private long rejectedConnections;
        private final Map<String, Long> rejectedConnectionsByReason = new LinkedHashMap<>();
        private long failedRoutes;
        private long backendConnectFailures;
        private long frontendToBackendBytes;
        private long backendToFrontendBytes;
        private long compressionNegotiations;
        private long compressionSavedBytes;
        private double compressionRatio = 1.0d;
        private long anomalyCount;
        private double eventLoopDelaySeconds;
        private long directMemoryBytes;
        private String transport = "unknown";
        private String nativeTransport = "false";

        static OverviewMetricsView parse(String healthJson, String metrics) {
            var view = new OverviewMetricsView();
            view.acceptHealth(healthJson);
            for (var line : metrics.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                view.acceptMetric(line);
            }
            return view;
        }

        static OverviewMetricsView parseJson(String json) {
            var view = new OverviewMetricsView();
            try {
                var root = MAPPER.readTree(json);
                view.healthStatus = text(root, "status", "unknown");
                view.configuredServers = number(root, "servers");
                view.activeConnections = number(root, "activeConnections");
                view.routedConnections = number(root, "routedConnections");
                view.rejectedConnections = number(root, "rejectedConnections");
                view.acceptRejectionReasons(root.get("rejectedConnectionsByReason"));
                view.failedRoutes = number(root, "failedRoutes");
                view.backendConnectFailures = number(root, "backendConnectFailures");
                view.frontendToBackendBytes = number(root, "frontendToBackendBytes");
                view.backendToFrontendBytes = number(root, "backendToFrontendBytes");
                view.compressionNegotiations = number(root, "compressionNegotiations");
                view.compressionSavedBytes = number(root, "compressionSavedBytes");
                view.compressionRatio = decimal(root, "compressionRatio", 1.0d);
                view.anomalyCount = number(root, "packetAnomalies");
                view.eventLoopDelaySeconds = decimal(root, "eventLoopDelaySeconds", 0.0d);
                view.directMemoryBytes = number(root, "pooledDirectMemoryBytes");
                view.transport = text(root, "transport", "unknown");
                view.nativeTransport = Boolean.toString(root.has("nativeTransport") && root.get("nativeTransport").asBoolean());
                return view;
            } catch (IOException exception) {
                view.healthStatus = "unparseable";
                return view;
            }
        }

        String render() {
            var output = new StringBuilder()
                    .append("status ").append(healthStatus).append('\n')
                    .append("servers ").append(configuredServers).append('\n')
                    .append("connections_active ").append(activeConnections).append('\n')
                    .append("connections_routed_total ").append(routedConnections).append('\n')
                    .append("connections_rejected_total ").append(rejectedConnections).append('\n');
            rejectedConnectionsByReason.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> output.append("connections_rejected_total{reason=\"")
                            .append(entry.getKey())
                            .append("\"} ")
                            .append(entry.getValue())
                            .append('\n'));
            return output
                    .append("routes_failed_total ").append(failedRoutes).append('\n')
                    .append("backend_connect_failures_total ").append(backendConnectFailures).append('\n')
                    .append("frontend_to_backend_bytes_total ").append(frontendToBackendBytes).append('\n')
                    .append("backend_to_frontend_bytes_total ").append(backendToFrontendBytes).append('\n')
                    .append("compression_negotiations_total ").append(compressionNegotiations).append('\n')
                    .append("compression_saved_bytes_total ").append(compressionSavedBytes).append('\n')
                    .append("compression_ratio ").append(String.format(java.util.Locale.ROOT, "%.4f", compressionRatio)).append('\n')
                    .append("packet_anomalies_total ").append(anomalyCount).append('\n')
                    .append("event_loop_delay_seconds ").append(String.format(java.util.Locale.ROOT, "%.6f", eventLoopDelaySeconds)).append('\n')
                    .append("pooled_direct_memory_bytes ").append(directMemoryBytes).append('\n')
                    .append("transport ").append(transport).append('\n')
                    .append("native_transport ").append(nativeTransport).append('\n')
                    .toString();
        }

        private void acceptHealth(String body) {
            try {
                var json = MAPPER.readTree(body);
                if (json.has("status")) {
                    healthStatus = json.get("status").asText();
                }
                if (json.has("servers")) {
                    configuredServers = json.get("servers").asLong();
                }
            } catch (IOException exception) {
                healthStatus = "unparseable";
            }
        }

        private void acceptMetric(String line) {
            var splitAt = line.lastIndexOf(' ');
            if (splitAt <= 0 || splitAt == line.length() - 1) {
                return;
            }
            var nameAndLabels = line.substring(0, splitAt);
            var name = CompressionMetricsView.metricName(nameAndLabels);
            var value = CompressionMetricsView.parseDouble(line.substring(splitAt + 1));
            var labeled = nameAndLabels.indexOf('{') >= 0;
            if (name.equals("strataproxy_packet_anomalies_total")) {
                anomalyCount += (long) value;
                return;
            }
            switch (name) {
                case "strataproxy_connections_active" -> activeConnections = (long) value;
                case "strataproxy_connections_routed_total" -> routedConnections = (long) value;
                case "strataproxy_connections_rejected_total" -> {
                    if (!labeled) {
                        rejectedConnections = (long) value;
                    } else {
                        var reason = CompressionMetricsView.labelValue(nameAndLabels, "reason");
                        if (reason != null) {
                            rejectedConnectionsByReason.put(reason, (long) value);
                        }
                    }
                }
                case "strataproxy_routes_failed_total" -> failedRoutes = (long) value;
                case "strataproxy_backend_connect_failures_total" -> backendConnectFailures = (long) value;
                case "strataproxy_frontend_to_backend_bytes_total" -> frontendToBackendBytes = (long) value;
                case "strataproxy_backend_to_frontend_bytes_total" -> backendToFrontendBytes = (long) value;
                case "strataproxy_compression_negotiations_total" -> compressionNegotiations = (long) value;
                case "strataproxy_compression_saved_bytes_total" -> {
                    if (!labeled) {
                        compressionSavedBytes = (long) value;
                    }
                }
                case "strataproxy_compression_ratio" -> {
                    if (!labeled) {
                        compressionRatio = value;
                    }
                }
                case "strataproxy_event_loop_delay_seconds" -> eventLoopDelaySeconds = value;
                case "strataproxy_pooled_direct_memory_bytes" -> directMemoryBytes = (long) value;
                case "strataproxy_network_transport_info" -> {
                    var transportLabel = CompressionMetricsView.labelValue(nameAndLabels, "transport");
                    var nativeLabel = CompressionMetricsView.labelValue(nameAndLabels, "native");
                    if (transportLabel != null) {
                        transport = transportLabel;
                    }
                    if (nativeLabel != null) {
                        nativeTransport = nativeLabel;
                    }
                }
                default -> {
                }
            }
        }

        private static String text(com.fasterxml.jackson.databind.JsonNode node, String field, String fallback) {
            return node.has(field) ? node.get(field).asText() : fallback;
        }

        private static long number(com.fasterxml.jackson.databind.JsonNode node, String field) {
            return node.has(field) ? node.get(field).asLong() : 0L;
        }

        private static double decimal(com.fasterxml.jackson.databind.JsonNode node, String field, double fallback) {
            return node.has(field) ? node.get(field).asDouble() : fallback;
        }

        private void acceptRejectionReasons(com.fasterxml.jackson.databind.JsonNode node) {
            if (node == null || !node.isObject()) {
                return;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                rejectedConnectionsByReason.put(entry.getKey(), entry.getValue().asLong());
            }
        }
    }

    private static final class ReadyMetricsView {
        private String status = "unknown";
        private long readyServers;
        private long registeredServers;
        private int httpStatus;

        static ReadyMetricsView parseJson(String json, int httpStatus) {
            var view = new ReadyMetricsView();
            view.httpStatus = httpStatus;
            try {
                var root = MAPPER.readTree(json);
                view.status = text(root, "status", "unknown");
                view.readyServers = number(root, "readyServers");
                view.registeredServers = number(root, "registeredServers");
                return view;
            } catch (IOException exception) {
                view.status = "unparseable";
                return view;
            }
        }

        private static String text(com.fasterxml.jackson.databind.JsonNode node, String field, String fallback) {
            return node.has(field) ? node.get(field).asText() : fallback;
        }

        private static long number(com.fasterxml.jackson.databind.JsonNode node, String field) {
            return node.has(field) ? node.get(field).asLong() : 0L;
        }
    }

    private static final class SloView {
        private final List<SloRow> rows = new ArrayList<>();

        static SloView from(
                OverviewMetricsView overview,
                ReadyMetricsView readiness,
                double maxEventLoopDelayMillis,
                long maxActiveConnections,
                long maxRejectedConnections,
                long maxAnomalies,
                boolean requireNativeTransport) {
            var view = new SloView();
            view.rows.add(new SloRow(
                    "health_status",
                    overview.healthStatus,
                    "UP",
                    "UP".equalsIgnoreCase(overview.healthStatus)));
            if (readiness != null) {
                view.rows.add(new SloRow(
                        "readiness_status",
                        readiness.status + "/" + readiness.readyServers + "/" + readiness.registeredServers + "/" + readiness.httpStatus,
                        "READY/>=1/registered/200",
                        "READY".equalsIgnoreCase(readiness.status)
                                && readiness.readyServers >= 1
                                && readiness.httpStatus == 200));
            }
            if (maxEventLoopDelayMillis >= 0.0d) {
                var actual = overview.eventLoopDelaySeconds * 1000.0d;
                view.rows.add(new SloRow(
                        "event_loop_delay_ms",
                        format(actual),
                        "<=" + format(maxEventLoopDelayMillis),
                        actual <= maxEventLoopDelayMillis));
            }
            if (maxActiveConnections >= 0) {
                view.rows.add(new SloRow(
                        "active_connections",
                        Long.toString(overview.activeConnections),
                        "<=" + maxActiveConnections,
                        overview.activeConnections <= maxActiveConnections));
            }
            if (maxRejectedConnections >= 0) {
                view.rows.add(new SloRow(
                        "rejected_connections",
                        Long.toString(overview.rejectedConnections),
                        "<=" + maxRejectedConnections,
                        overview.rejectedConnections <= maxRejectedConnections));
            }
            if (maxAnomalies >= 0) {
                view.rows.add(new SloRow(
                        "packet_anomalies",
                        Long.toString(overview.anomalyCount),
                        "<=" + maxAnomalies,
                        overview.anomalyCount <= maxAnomalies));
            }
            if (requireNativeTransport) {
                view.rows.add(new SloRow(
                        "native_transport",
                        overview.nativeTransport,
                        "true",
                        Boolean.parseBoolean(overview.nativeTransport)));
            }
            return view;
        }

        boolean passed() {
            return rows.stream().allMatch(SloRow::passed);
        }

        String render() {
            var output = new StringBuilder("gate actual expected status\n");
            for (var row : rows) {
                output.append(row.name()).append(' ')
                        .append(value(row.actual())).append(' ')
                        .append(value(row.expected())).append(' ')
                        .append(row.passed() ? "PASS" : "FAIL")
                        .append('\n');
            }
            return output.toString();
        }

        private static String format(double value) {
            return String.format(java.util.Locale.ROOT, "%.3f", value);
        }

        private static String value(String value) {
            return value == null || value.isBlank() ? "-" : value.replace(' ', '_');
        }
    }

    private record SloRow(String name, String actual, String expected, boolean passed) {
    }

    private static final class AnomalyMetricsView {
        private final Map<String, Long> rules = new LinkedHashMap<>();
        private final List<AnomalySampleRow> samples = new ArrayList<>();

        static AnomalyMetricsView parse(String metrics) {
            var view = new AnomalyMetricsView();
            for (var line : metrics.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                view.accept(line);
            }
            return view;
        }

        static AnomalyMetricsView parseJson(String json) {
            var view = new AnomalyMetricsView();
            try {
                var root = MAPPER.readTree(json);
                var rules = root.get("rules");
                if (rules == null || !rules.isArray()) {
                    return view;
                }
                for (var rule : rules) {
                    var name = rule.has("rule") ? rule.get("rule").asText() : "";
                    if (!name.isBlank()) {
                        view.rules.put(name, rule.has("count") ? rule.get("count").asLong() : 0L);
                    }
                }
                var recentSamples = root.get("recentSamples");
                if (recentSamples != null && recentSamples.isArray()) {
                    for (var sample : recentSamples) {
                        view.samples.add(new AnomalySampleRow(
                                sample.has("sequence") ? sample.get("sequence").asLong() : 0L,
                                sample.has("timestamp") ? sample.get("timestamp").asText() : "",
                                sample.has("rule") ? sample.get("rule").asText() : "",
                                sample.has("server") ? sample.get("server").asText() : "",
                                sample.has("direction") ? sample.get("direction").asText() : "",
                                sample.has("protocolState") ? sample.get("protocolState").asText() : "",
                                sample.has("packetId") ? sample.get("packetId").asInt() : -1,
                                sample.has("rawSize") ? sample.get("rawSize").asLong() : -1,
                                sample.has("compressedSize") ? sample.get("compressedSize").asLong() : -1,
                                sample.has("remoteAddress") ? sample.get("remoteAddress").asText() : "",
                                sample.has("detail") ? sample.get("detail").asText() : ""));
                    }
                }
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        String renderSamples() {
            var output = new StringBuilder("sequence timestamp rule server direction state packet_id raw_size compressed_size remote detail\n");
            samples.stream()
                    .sorted(Comparator.comparingLong(AnomalySampleRow::sequence).reversed())
                    .forEach(sample -> output
                            .append(sample.sequence()).append(' ')
                            .append(value(sample.timestamp())).append(' ')
                            .append(value(sample.rule())).append(' ')
                            .append(value(sample.server())).append(' ')
                            .append(value(sample.direction())).append(' ')
                            .append(value(sample.protocolState())).append(' ')
                            .append(sample.packetId()).append(' ')
                            .append(sample.rawSize()).append(' ')
                            .append(sample.compressedSize()).append(' ')
                            .append(value(sample.remoteAddress())).append(' ')
                            .append(value(sample.detail())).append('\n'));
            return output.toString();
        }

        String render() {
            var output = new StringBuilder("rule count\n");
            rules.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
                            .thenComparing(Map.Entry.comparingByKey()))
                    .forEach(entry -> output
                            .append(entry.getKey())
                            .append(' ')
                            .append(entry.getValue())
                            .append('\n'));
            return output.toString();
        }

        private void accept(String line) {
            var splitAt = line.lastIndexOf(' ');
            if (splitAt <= 0 || splitAt == line.length() - 1) {
                return;
            }
            var nameAndLabels = line.substring(0, splitAt);
            if (!CompressionMetricsView.metricName(nameAndLabels).equals("strataproxy_packet_anomalies_total")) {
                return;
            }
            var rule = CompressionMetricsView.labelValue(nameAndLabels, "rule");
            if (rule == null || rule.isBlank()) {
                return;
            }
            rules.put(rule, (long) CompressionMetricsView.parseDouble(line.substring(splitAt + 1)));
        }

        private static String value(String value) {
            return value == null || value.isBlank() ? "-" : value.replace(' ', '_');
        }
    }

    private record AnomalySampleRow(
            long sequence,
            String timestamp,
            String rule,
            String server,
            String direction,
            String protocolState,
            int packetId,
            long rawSize,
            long compressedSize,
            String remoteAddress,
            String detail) {
    }

    private static final class PacketTrafficMetricsView {
        private final List<PacketTrafficRow> rows = new ArrayList<>();

        static PacketTrafficMetricsView parseJson(String json) {
            var view = new PacketTrafficMetricsView();
            try {
                var root = MAPPER.readTree(json);
                var top = root.get("top");
                if (top == null || !top.isArray()) {
                    return view;
                }
                for (var row : top) {
                    view.rows.add(new PacketTrafficRow(
                            row.has("server") ? row.get("server").asText() : "",
                            row.has("direction") ? row.get("direction").asText() : "",
                            row.has("protocolState") ? row.get("protocolState").asText() : "",
                            row.has("packetId") ? row.get("packetId").asInt() : -1,
                            row.has("packets") ? row.get("packets").asLong() : 0L,
                            row.has("rawBytes") ? row.get("rawBytes").asLong() : 0L,
                            row.has("compressedBytes") ? row.get("compressedBytes").asLong() : 0L));
                }
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        static PacketTrafficMetricsView parse(String metrics) {
            var counters = new LinkedHashMap<String, PacketTrafficRowBuilder>();
            for (var line : metrics.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var splitAt = line.lastIndexOf(' ');
                if (splitAt <= 0 || splitAt == line.length() - 1) {
                    continue;
                }
                var nameAndLabels = line.substring(0, splitAt);
                var name = CompressionMetricsView.metricName(nameAndLabels);
                if (!name.startsWith("strataproxy_packet_traffic_")) {
                    continue;
                }
                var server = CompressionMetricsView.labelValue(nameAndLabels, "server");
                var direction = CompressionMetricsView.labelValue(nameAndLabels, "direction");
                var state = CompressionMetricsView.labelValue(nameAndLabels, "state");
                var packetId = CompressionMetricsView.labelValue(nameAndLabels, "packet_id");
                if (server == null || direction == null || state == null || packetId == null) {
                    continue;
                }
                var key = server + "|" + direction + "|" + state + "|" + packetId;
                var builder = counters.computeIfAbsent(key, ignored -> new PacketTrafficRowBuilder(
                        server,
                        direction,
                        state,
                        (int) CompressionMetricsView.parseLong(packetId)));
                var value = (long) CompressionMetricsView.parseDouble(line.substring(splitAt + 1));
                switch (name) {
                    case "strataproxy_packet_traffic_packets_total" -> builder.packets = value;
                    case "strataproxy_packet_traffic_raw_bytes_total" -> builder.rawBytes = value;
                    case "strataproxy_packet_traffic_compressed_bytes_total" -> builder.compressedBytes = value;
                    default -> {
                    }
                }
            }
            var view = new PacketTrafficMetricsView();
            counters.values().stream()
                    .map(PacketTrafficRowBuilder::build)
                    .sorted(Comparator
                            .comparingLong(PacketTrafficRow::rawBytes)
                            .reversed()
                            .thenComparing(PacketTrafficRow::server)
                            .thenComparing(PacketTrafficRow::direction)
                            .thenComparing(PacketTrafficRow::protocolState)
                            .thenComparingInt(PacketTrafficRow::packetId))
                    .forEach(view.rows::add);
            return view;
        }

        String render() {
            var output = new StringBuilder("server direction state packet_id packets raw_bytes compressed_bytes\n");
            for (var row : rows) {
                output.append(value(row.server())).append(' ')
                        .append(value(row.direction())).append(' ')
                        .append(value(row.protocolState())).append(' ')
                        .append(row.packetId()).append(' ')
                        .append(row.packets()).append(' ')
                        .append(row.rawBytes()).append(' ')
                        .append(row.compressedBytes()).append('\n');
            }
            return output.toString();
        }

        private static String value(String value) {
            return value == null || value.isBlank() ? "-" : value.replace(' ', '_');
        }
    }

    private static final class PacketTrafficRowBuilder {
        private final String server;
        private final String direction;
        private final String protocolState;
        private final int packetId;
        private long packets;
        private long rawBytes;
        private long compressedBytes;

        private PacketTrafficRowBuilder(String server, String direction, String protocolState, int packetId) {
            this.server = server;
            this.direction = direction;
            this.protocolState = protocolState;
            this.packetId = packetId;
        }

        private PacketTrafficRow build() {
            return new PacketTrafficRow(server, direction, protocolState, packetId, packets, rawBytes, compressedBytes);
        }
    }

    private record PacketTrafficRow(
            String server,
            String direction,
            String protocolState,
            int packetId,
            long packets,
            long rawBytes,
            long compressedBytes) {
    }

    private static final class CustomPayloadMetricsView {
        private final List<CustomPayloadRow> rows = new ArrayList<>();

        static CustomPayloadMetricsView parseJson(String json) {
            var view = new CustomPayloadMetricsView();
            try {
                var root = MAPPER.readTree(json);
                var top = root.get("top");
                if (top == null || !top.isArray()) {
                    return view;
                }
                for (var row : top) {
                    view.rows.add(new CustomPayloadRow(
                            row.has("server") ? row.get("server").asText() : "",
                            row.has("direction") ? row.get("direction").asText() : "",
                            row.has("kind") ? row.get("kind").asText() : "",
                            row.has("channel") ? row.get("channel").asText() : "",
                            row.has("packets") ? row.get("packets").asLong() : 0L,
                            row.has("payloadBytes") ? row.get("payloadBytes").asLong() : 0L,
                            row.has("compressedBytes") ? row.get("compressedBytes").asLong() : 0L,
                            row.has("maxPayloadBytes") ? row.get("maxPayloadBytes").asLong() : 0L,
                            row.has("maxCompressedBytes") ? row.get("maxCompressedBytes").asLong() : 0L,
                            row.has("firstSeen") ? row.get("firstSeen").asText() : "",
                            row.has("lastSeen") ? row.get("lastSeen").asText() : ""));
                }
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        String render() {
            var output = new StringBuilder("server direction kind channel packets payload_bytes compressed_bytes max_payload_bytes max_compressed_bytes first_seen last_seen\n");
            for (var row : rows) {
                output.append(value(row.server())).append(' ')
                        .append(value(row.direction())).append(' ')
                        .append(value(row.kind())).append(' ')
                        .append(value(row.channel())).append(' ')
                        .append(row.packets()).append(' ')
                        .append(row.payloadBytes()).append(' ')
                        .append(row.compressedBytes()).append(' ')
                        .append(row.maxPayloadBytes()).append(' ')
                        .append(row.maxCompressedBytes()).append(' ')
                        .append(value(row.firstSeen())).append(' ')
                        .append(value(row.lastSeen())).append('\n');
            }
            return output.toString();
        }

        private static String value(String value) {
            return value == null || value.isBlank() ? "-" : value.replace(' ', '_');
        }
    }

    private record CustomPayloadRow(
            String server,
            String direction,
            String kind,
            String channel,
            long packets,
            long payloadBytes,
            long compressedBytes,
            long maxPayloadBytes,
            long maxCompressedBytes,
            String firstSeen,
            String lastSeen) {
    }

    private static final class PlayerSessionMetricsView {
        private final List<PlayerSessionRow> rows = new ArrayList<>();

        static PlayerSessionMetricsView parseJson(String json) {
            var view = new PlayerSessionMetricsView();
            try {
                var root = MAPPER.readTree(json);
                var players = root.get("players");
                if (players == null || !players.isArray()) {
                    return view;
                }
                for (var player : players) {
                    view.rows.add(new PlayerSessionRow(
                            text(player, "player"),
                            text(player, "server"),
                            text(player, "remoteAddress"),
                            text(player, "connectedAt")));
                }
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        String render() {
            var output = new StringBuilder("player server remote_address connected_at\n");
            for (var row : rows) {
                output.append(value(row.player())).append(' ')
                        .append(value(row.server())).append(' ')
                        .append(value(row.remoteAddress())).append(' ')
                        .append(value(row.connectedAt())).append('\n');
            }
            return output.toString();
        }

        private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
            return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
        }

        private static String value(String value) {
            return value == null || value.isBlank() ? "-" : value.replace(' ', '_');
        }
    }

    private record PlayerSessionRow(String player, String server, String remoteAddress, String connectedAt) {
    }

    private static final class RoutePreviewMetricsView {
        private boolean selected;
        private String server = "";
        private double score;
        private String reason = "";
        private String route = "";
        private int protocolVersion;
        private String remoteAddress = "";
        private final List<RouteCandidateRow> candidates = new ArrayList<>();

        static RoutePreviewMetricsView parseJson(String json) {
            var view = new RoutePreviewMetricsView();
            try {
                var root = MAPPER.readTree(json);
                view.selected = root.has("selected") && root.get("selected").asBoolean();
                view.server = text(root, "server");
                view.score = root.has("score") ? root.get("score").asDouble() : 0.0d;
                view.reason = text(root, "reason");
                view.route = text(root, "route");
                view.protocolVersion = root.has("protocolVersion") ? root.get("protocolVersion").asInt() : 0;
                view.remoteAddress = text(root, "remoteAddress");
                var candidates = root.get("candidates");
                if (candidates != null && candidates.isArray()) {
                    for (var candidate : candidates) {
                        view.candidates.add(new RouteCandidateRow(
                                text(candidate, "server"),
                                candidate.has("eligible") && candidate.get("eligible").asBoolean(),
                                text(candidate, "reason"),
                                candidate.has("effectiveWeight") ? candidate.get("effectiveWeight").asDouble() : 0.0d,
                                candidate.has("selectionKey") ? candidate.get("selectionKey").asDouble() : 0.0d));
                    }
                }
                return view;
            } catch (IOException exception) {
                view.reason = "unparseable";
                return view;
            }
        }

        String render() {
            var output = new StringBuilder("selected server score reason route protocol_version remote_address\n");
            output.append(selected).append(' ')
                    .append(value(server)).append(' ')
                    .append(String.format(java.util.Locale.ROOT, "%.4f", score)).append(' ')
                    .append(value(reason)).append(' ')
                    .append(value(route)).append(' ')
                    .append(protocolVersion).append(' ')
                    .append(value(remoteAddress)).append('\n');
            output.append("candidate eligible reason effective_weight selection_key\n");
            for (var candidate : candidates) {
                output.append(value(candidate.server())).append(' ')
                        .append(candidate.eligible()).append(' ')
                        .append(value(candidate.reason())).append(' ')
                        .append(String.format(java.util.Locale.ROOT, "%.4f", candidate.effectiveWeight())).append(' ')
                        .append(candidate.selectionKey() == Double.POSITIVE_INFINITY
                                ? "inf"
                                : String.format(java.util.Locale.ROOT, "%.6f", candidate.selectionKey()))
                        .append('\n');
            }
            return output.toString();
        }

        private static String text(com.fasterxml.jackson.databind.JsonNode node, String field) {
            return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : "";
        }

        private static String value(String value) {
            return value == null || value.isBlank() ? "-" : value.replace(' ', '_');
        }
    }

    private record RouteCandidateRow(
            String server,
            boolean eligible,
            String reason,
            double effectiveWeight,
            double selectionKey) {
    }

    private static final class PayloadCaptureMetricsView {
        private static final String HEADER = "id server direction max_samples max_bytes_per_sample expires_at sample_count\n";
        private final List<PayloadCaptureRow> rows = new ArrayList<>();

        static PayloadCaptureMetricsView parseListJson(String json) {
            var view = new PayloadCaptureMetricsView();
            try {
                var root = MAPPER.readTree(json);
                var captures = root.get("captures");
                if (captures != null && captures.isArray()) {
                    for (var capture : captures) {
                        view.rows.add(row(capture));
                    }
                }
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        static PayloadCaptureMetricsView parseCaptureJson(String json) {
            var view = new PayloadCaptureMetricsView();
            try {
                view.rows.add(row(MAPPER.readTree(json)));
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        String render() {
            var output = new StringBuilder(HEADER);
            for (var row : rows) {
                output.append(value(row.id())).append(' ')
                        .append(value(row.server())).append(' ')
                        .append(value(row.direction())).append(' ')
                        .append(row.maxSamples()).append(' ')
                        .append(row.maxBytesPerSample()).append(' ')
                        .append(value(row.expiresAt())).append(' ')
                        .append(row.sampleCount()).append('\n');
            }
            return output.toString();
        }

        private static PayloadCaptureRow row(com.fasterxml.jackson.databind.JsonNode node) {
            return new PayloadCaptureRow(
                    text(node, "id", ""),
                    text(node, "server", ""),
                    text(node, "direction", ""),
                    intValue(node, "maxSamples"),
                    intValue(node, "maxBytesPerSample"),
                    text(node, "expiresAt", ""),
                    intValue(node, "sampleCount"));
        }

        private static int intValue(com.fasterxml.jackson.databind.JsonNode node, String field) {
            return node.has(field) ? node.get(field).asInt() : 0;
        }

        private static String text(com.fasterxml.jackson.databind.JsonNode node, String field, String fallback) {
            return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : fallback;
        }

        private static String value(String value) {
            return value == null || value.isBlank() ? "-" : value.replace(' ', '_');
        }
    }

    private static final class PayloadCaptureExportView {
        private PayloadCaptureRow capture = new PayloadCaptureRow("", "", "", 0, 0, "", 0);
        private final List<PayloadCaptureSampleRow> samples = new ArrayList<>();

        static PayloadCaptureExportView parseJson(String json) {
            var view = new PayloadCaptureExportView();
            try {
                var root = MAPPER.readTree(json);
                var capture = root.get("capture");
                if (capture != null && capture.isObject()) {
                    view.capture = PayloadCaptureMetricsView.row(capture);
                }
                var samples = root.get("samples");
                if (samples != null && samples.isArray()) {
                    for (var sample : samples) {
                        view.samples.add(new PayloadCaptureSampleRow(
                                sample.has("sequence") ? sample.get("sequence").asLong() : 0L,
                                text(sample, "captureId", ""),
                                text(sample, "server", ""),
                                text(sample, "direction", ""),
                                sample.has("rawBytes") ? sample.get("rawBytes").asLong() : 0L,
                                sample.has("compressedBytes") ? sample.get("compressedBytes").asLong() : 0L,
                                text(sample, "prefixBase64", ""),
                                text(sample, "player", ""),
                                text(sample, "remoteAddress", ""),
                                text(sample, "timestamp", "")));
                    }
                }
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        String render() {
            var output = new StringBuilder(PayloadCaptureMetricsView.HEADER);
            output.append(PayloadCaptureMetricsView.value(capture.id())).append(' ')
                    .append(PayloadCaptureMetricsView.value(capture.server())).append(' ')
                    .append(PayloadCaptureMetricsView.value(capture.direction())).append(' ')
                    .append(capture.maxSamples()).append(' ')
                    .append(capture.maxBytesPerSample()).append(' ')
                    .append(PayloadCaptureMetricsView.value(capture.expiresAt())).append(' ')
                    .append(capture.sampleCount()).append('\n');
            output.append("sequence capture_id server direction raw_bytes compressed_bytes player remote_address prefix_base64 timestamp\n");
            for (var sample : samples) {
                output.append(sample.sequence()).append(' ')
                        .append(PayloadCaptureMetricsView.value(sample.captureId())).append(' ')
                        .append(PayloadCaptureMetricsView.value(sample.server())).append(' ')
                        .append(PayloadCaptureMetricsView.value(sample.direction())).append(' ')
                        .append(sample.rawBytes()).append(' ')
                        .append(sample.compressedBytes()).append(' ')
                        .append(PayloadCaptureMetricsView.value(sample.player())).append(' ')
                        .append(PayloadCaptureMetricsView.value(sample.remoteAddress())).append(' ')
                        .append(PayloadCaptureMetricsView.value(sample.prefixBase64())).append(' ')
                        .append(PayloadCaptureMetricsView.value(sample.timestamp())).append('\n');
            }
            return output.toString();
        }

        private static String text(com.fasterxml.jackson.databind.JsonNode node, String field, String fallback) {
            return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : fallback;
        }
    }

    private record PayloadCaptureRow(
            String id,
            String server,
            String direction,
            int maxSamples,
            int maxBytesPerSample,
            String expiresAt,
            int sampleCount) {
    }

    private record PayloadCaptureSampleRow(
            long sequence,
            String captureId,
            String server,
            String direction,
            long rawBytes,
            long compressedBytes,
            String prefixBase64,
            String player,
            String remoteAddress,
            String timestamp) {
    }

    private static final class BackpressureMetricsView {
        private final List<BackpressureRow> rows = new ArrayList<>();

        static BackpressureMetricsView parseJson(String json) {
            var view = new BackpressureMetricsView();
            try {
                var root = MAPPER.readTree(json);
                var report = root.get("relayBackpressure");
                if (report == null) {
                    return view;
                }
                var top = report.get("top");
                if (top == null || !top.isArray()) {
                    return view;
                }
                for (var row : top) {
                    view.rows.add(new BackpressureRow(
                            row.has("server") ? row.get("server").asText() : "",
                            row.has("direction") ? row.get("direction").asText() : "",
                            row.has("events") ? row.get("events").asLong() : 0L,
                            row.has("lastBytesBeforeWritable") ? row.get("lastBytesBeforeWritable").asLong() : 0L,
                            row.has("maxBytesBeforeWritable") ? row.get("maxBytesBeforeWritable").asLong() : 0L));
                }
                return view;
            } catch (IOException exception) {
                return view;
            }
        }

        static BackpressureMetricsView parse(String metrics) {
            var counters = new LinkedHashMap<String, BackpressureRowBuilder>();
            for (var line : metrics.split("\\R")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var splitAt = line.lastIndexOf(' ');
                if (splitAt <= 0 || splitAt == line.length() - 1) {
                    continue;
                }
                var nameAndLabels = line.substring(0, splitAt);
                var name = CompressionMetricsView.metricName(nameAndLabels);
                if (!name.startsWith("strataproxy_relay_backpressure_")) {
                    continue;
                }
                var server = CompressionMetricsView.labelValue(nameAndLabels, "server");
                var direction = CompressionMetricsView.labelValue(nameAndLabels, "direction");
                if (server == null || direction == null) {
                    continue;
                }
                var builder = counters.computeIfAbsent(server + "|" + direction, ignored -> new BackpressureRowBuilder(server, direction));
                var value = (long) CompressionMetricsView.parseDouble(line.substring(splitAt + 1));
                switch (name) {
                    case "strataproxy_relay_backpressure_events_total" -> builder.events = value;
                    case "strataproxy_relay_backpressure_last_bytes_before_writable" -> builder.lastBytesBeforeWritable = value;
                    case "strataproxy_relay_backpressure_max_bytes_before_writable" -> builder.maxBytesBeforeWritable = value;
                    default -> {
                    }
                }
            }
            var view = new BackpressureMetricsView();
            counters.values().stream()
                    .map(BackpressureRowBuilder::build)
                    .sorted(Comparator
                            .comparingLong(BackpressureRow::events)
                            .reversed()
                            .thenComparing(BackpressureRow::server)
                            .thenComparing(BackpressureRow::direction))
                    .forEach(view.rows::add);
            return view;
        }

        String render() {
            var output = new StringBuilder("server direction events last_bytes_before_writable max_bytes_before_writable\n");
            for (var row : rows) {
                output.append(value(row.server())).append(' ')
                        .append(value(row.direction())).append(' ')
                        .append(row.events()).append(' ')
                        .append(row.lastBytesBeforeWritable()).append(' ')
                        .append(row.maxBytesBeforeWritable()).append('\n');
            }
            return output.toString();
        }

        private static String value(String value) {
            return value == null || value.isBlank() ? "-" : value.replace(' ', '_');
        }
    }

    private static final class BackpressureRowBuilder {
        private final String server;
        private final String direction;
        private long events;
        private long lastBytesBeforeWritable;
        private long maxBytesBeforeWritable;

        private BackpressureRowBuilder(String server, String direction) {
            this.server = server;
            this.direction = direction;
        }

        private BackpressureRow build() {
            return new BackpressureRow(server, direction, events, lastBytesBeforeWritable, maxBytesBeforeWritable);
        }
    }

    private record BackpressureRow(
            String server,
            String direction,
            long events,
            long lastBytesBeforeWritable,
            long maxBytesBeforeWritable) {
    }

    private static final class CompressionRow {
        private final String scope;
        private String direction = "all";
        private long samples;
        private long rawBytes;
        private long compressedBytes;
        private long savedBytes;
        private double ratio = 1.0d;
        private long threshold = -1;

        private CompressionRow(String scope) {
            this.scope = scope;
        }

        private String render(long negotiations) {
            return scope
                    + ' ' + direction
                    + ' ' + rawBytes
                    + ' ' + compressedBytes
                    + ' ' + savedBytes
                    + ' ' + String.format(java.util.Locale.ROOT, "%.4f", ratio)
                    + ' ' + samples
                    + ' ' + threshold
                    + ' ' + negotiations;
        }
    }

    private record CompressionDecisionRow(String scope, String direction, String action, long threshold, long count) {
        private String render() {
            return scope
                    + ' ' + direction
                    + ' ' + action
                    + ' ' + threshold
                    + ' ' + count;
        }
    }

    private record CompressionRewriteRow(String scope, String direction, String outcome, long count, long cpuNanos) {
        private String render() {
            return scope
                    + ' ' + direction
                    + ' ' + outcome
                    + ' ' + count
                    + ' ' + String.format(java.util.Locale.ROOT, "%.3f", cpuNanos / 1_000_000.0d);
        }
    }
}
