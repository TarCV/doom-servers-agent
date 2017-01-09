package com.github.tarcv.doom_servers;

import com.github.tarcv.doom_servers.messages.Authenticated;
import com.github.tarcv.doom_servers.messages.Hello;
import com.github.tarcv.doom_servers.messages.Mapper;
import com.github.tarcv.doom_servers.messages.Message;
import com.github.tarcv.doom_servers.messages.Error;

import javax.websocket.*;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;

import static com.github.tarcv.doom_servers.WebsocketConnection.State.*;

/**
 * Represents a connection between doom-server and client. Automatically reconnects
 */
public class WebsocketConnection implements Connection {
    private final Object waitLock = new Object();
    private final URI url;
    private final Key key;
    private State state = NOT_CONNECTED;
    private ConnectionListener listener = null;
    private Session session = null;

    WebsocketConnection(ConnectionListener listener, String url, Key key) {
        this.listener = listener;
        this.url = URI.create(url);
        this.key = key;
    }

    void connectImpl() {
        try {
            boolean interrupted = false;
            while (!interrupted) {
                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                session = container.connectToServer(new WebsocketEndpoint(), url);
                synchronized (waitLock) {
                    boolean needReconnect = false;
                    while (!needReconnect && !interrupted) {
                        waitLock.wait();
                        needReconnect = RECONNECTING == state;
                        interrupted = DISCONNECTED == state;
                    }
                    if (needReconnect) {
                        Thread.sleep(5000);
                    }
                }
            }
            System.out.println("Interrupted by server");
        } catch (InterruptedException e) {
            System.out.println("Interrupted by signal");
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

    @Override
    public void send(Message message) throws IOException {
        send(session, message);
    }

    private static void send(Session session, Message message) throws IOException {
        OutputStream sendStream = session.getBasicRemote().getSendStream();
        Mapper.writeValue(sendStream, message);
    }

    enum State {
        NOT_CONNECTED,
        RECONNECTING,
        AUTHENTIFICATING,
        DISCONNECTED, LISTENING
    }

    @ClientEndpoint
    public class WebsocketEndpoint {
        @OnOpen
        public void onOpen(Session session) throws IOException {
            synchronized (waitLock) {
                System.out.println("Connected");

                Hello helloMessage = new Hello(key.getToken());
                send(session, helloMessage);

                assert NOT_CONNECTED == state || RECONNECTING == state;
                state = State.AUTHENTIFICATING;
            }
        }

        @OnMessage
        public void onMessage(String message, Session session) {
            synchronized (waitLock) {
                if (NOT_CONNECTED == state) {
                    logUnexpectedMessage(message);
                    return;
                }

                try {
                    Message decodedMessage = Mapper.readValue(message, Message.class);
                    if (AUTHENTIFICATING == state) {
                        if (!(decodedMessage instanceof Authenticated)) {
                            logUnexpectedMessage(message);
                            return;
                        }
                        if (((Authenticated) decodedMessage).isSuccessful()) {
                            state = LISTENING;
                        } else {
                            state = DISCONNECTED;
                        }
                    } else if (LISTENING == state && listener != null) {
                        Message response;
                        try {
                            response = listener.onMessage(decodedMessage);
                        } catch (Throwable e) {
                            response = new Error(e);
                        }
                        if (response != null) {
                            send(session, response);
                        }
                    } else {
                        logUnexpectedMessage(message);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        @OnError
        public void onError(Throwable error) {
            error.printStackTrace();
        }

        @OnClose
        public void onClose() {
            synchronized (waitLock) {
                System.out.println("Disconnected");
                if (!DISCONNECTED.equals(state)) {
                    state = RECONNECTING;
                }
                waitLock.notify();
            }
        }
    }

    private void logUnexpectedMessage(String message) {
        System.err.println("Unexpected message: " + message);
    }
}
