package com.github.tarcv.doom_servers;

import javax.websocket.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@ClientEndpoint
public class Agent {
    private static final Object waitLock = new Object();
    private static boolean needReconnect = false;
    private static Properties properties = loadProperties();

    private static State state = State.NOT_CONNECTED;

    public static void main(String[] args) throws IOException {
        String trustStorePassword = properties.getProperty("trustStore.password", "changeit");

        // Accept only certs stored in agentstore.jks
        System.setProperty("javax.net.ssl.trustStore", "agentstore.jks");
        System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);

        Session session = null;
        try {
            boolean interrupted = false;
            while (!interrupted) {
                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                session = container.connectToServer(Agent.class, URI.create("wss://doom-servers:8443/gs-guide-websocket"));
                synchronized (waitLock) {
                    needReconnect = false;
                    while (!needReconnect) {
                        try {
                            waitLock.wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                            break;
                        }
                    }
                    if (needReconnect) {
                        Thread.sleep(5000);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (session != null) {
                try {
                    session.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
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

    @OnOpen
    public void onOpen(Session session) throws IOException {
        System.out.println("Connected");

        String key = properties.getProperty("agent.key");
        if (key == null) {
            throw new RuntimeException("Agent key is not set in the configuration file");
        }
        Hello helloMessage = new Hello(key);
        OutputStream sendStream = session.getBasicRemote().getSendStream();
        Mapper.writeValue(sendStream, helloMessage);

        state = State.AUTHENTIFICATING;
    }

    @OnMessage
    public void onMessage(String message) {
        System.out.println(message);
    }

    @OnError
    public void onError(Throwable error) {
        error.printStackTrace();
    }

    @OnClose
    public void onClose() {
        System.out.println("Disconnected");
        synchronized (waitLock) {
            needReconnect = true;
            waitLock.notify();
        }
    }

    private static void waitForInterrupt() {
        synchronized (waitLock) {
            boolean interrupted = false;
            while (!interrupted) {
                try {
                    waitLock.wait();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
        }
    }

    enum State {
        NOT_CONNECTED,
        AUTHENTIFICATING,
        LISTENING
    }
}