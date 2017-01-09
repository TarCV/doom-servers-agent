package com.github.tarcv.doom_servers;

import com.github.tarcv.doom_servers.messages.Message;

import java.io.IOException;

/**
 * Created by TarCV on 12.11.2016.
 */
public interface Connection {
    void send(Message message) throws IOException;
}
