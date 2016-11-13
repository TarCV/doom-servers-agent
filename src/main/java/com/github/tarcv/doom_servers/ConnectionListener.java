package com.github.tarcv.doom_servers;

import com.github.tarcv.doom_servers.messages.Message;
import org.jetbrains.annotations.Nullable;

public interface ConnectionListener {
    @Nullable
    Message onMessage(Message message);
}
