package com.github.tarcv.doom_servers;

/**
 * Creates concrete instances of {@link Connection}
 */
public interface ConnectionFactory {
    Connection connect(ConnectionListener agent, String url, Key key);
}
