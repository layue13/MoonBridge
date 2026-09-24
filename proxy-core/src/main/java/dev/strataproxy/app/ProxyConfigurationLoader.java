package dev.strataproxy.app;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Path;

/** Strict YAML loader; unknown settings are errors during this redesign. */
public final class ProxyConfigurationLoader {
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public ProxyConfiguration load(Path path) throws IOException {
        return mapper.readValue(path.toFile(), ProxyConfiguration.class);
    }
}
