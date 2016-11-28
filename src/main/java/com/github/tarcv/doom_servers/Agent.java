package com.github.tarcv.doom_servers;

import com.github.tarcv.doom_servers.messages.*;
import org.jetbrains.annotations.Nullable;

import javax.websocket.ClientEndpoint;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeoutException;

@ClientEndpoint
public class Agent implements ConnectionListener {
    private final WebsocketConnectionFactory connectionFactory;
    private final Path executable;
    private final Path workDir;
    private Server server = null;
    private String key;

    public static void main(String[] args) throws IOException {
        Properties properties = loadProperties();
        setupTrustStore(properties);

        new Agent(properties, new WebsocketConnectionFactory()).run();
    }

    private Agent(Properties properties, WebsocketConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
        this.key = properties.getProperty("agent.key");

        String engine = properties.getProperty("engine");
        Path executable = Paths.get(getEngineProperty(properties, engine, "executable", engine)).toAbsolutePath();
        Path workDir = Paths.get(getEngineProperty(properties, engine, "workdir", ".")).toAbsolutePath();

        if (!workDir.toFile().isDirectory()) {
            throw new ConfigurationException(workDir.toAbsolutePath() + " is not a directory");
        }
        if (!executable.toFile().canExecute()) {
            throw new ConfigurationException(executable.toAbsolutePath() + " cannot be executed by the current user");
        }

        this.executable = executable;
        this.workDir = workDir;
    }

    private void run() {
        Key key = getKey();
        Connection connection = connectionFactory.connect(this, "wss://doom-servers:8443/gs-guide-websocket", key);
    }

    private Key getKey() {
        if (key == null) {
            throw new RuntimeException("Agent key is not set in the configuration file");
        }
        return new Key(key);
    }

    private static void setupTrustStore(Properties properties) {
        String trustStorePassword = properties.getProperty("trustStore.password", "changeit");

        // Accept only certs stored in agentstore.jks
        System.setProperty("javax.net.ssl.trustStore", "agentstore.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
    }

    private static Properties loadProperties() {
        Path propertiesPath = Paths.get("agent.properties");
        Properties properties = new Properties();
        try {
            if (propertiesPath.toFile().exists()) {
                try (BufferedReader reader = Files.newBufferedReader(propertiesPath)) {
                    properties.load(reader);
                }
            }
        } catch (IOException e) {
            System.err.println("Failed to load configuration: " + e.toString());
        }
        return properties;
    }

    @Override
    public Message onMessage(Message message) throws TimeoutException, InterruptedException {
        if (message instanceof RunServer) {
            try {
                ServerConfiguration configuration = ((RunServer)message).getConfiguration();
                Server newServer = new Server(this.executable, this.workDir, configuration);
                newServer.run();
                this.server = newServer;
                return new ServerStarted(null);
            } catch (Exception e) {
                return new ServerStarted(e);
            }
        } else if (message instanceof ConsoleCommand) {
            List<String> command = ((ConsoleCommand) message).getCommand();
            List<String> lines = server.executeConsole(command);
            return new ConsoleResult(lines);
        } else {
            return null;
        }
    }

    private static String getEngineProperty(Properties properties, String engine, String key, @Nullable String defaultValue) {
        return properties.getProperty("engine." + engine + "." + key, defaultValue);
    }
}