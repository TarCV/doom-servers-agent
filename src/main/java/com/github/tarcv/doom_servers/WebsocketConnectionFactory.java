package com.github.tarcv.doom_servers;

/**
 * Creates instances of {@link WebsocketConnection}
 */
public class WebsocketConnectionFactory implements ConnectionFactory {
    @Override
    public Connection connect(ConnectionListener listener, String url, Key key) {
        WebsocketConnection connection = new WebsocketConnection(listener, url, key);
        connection.connectImpl();
        return connection;
    }
}
