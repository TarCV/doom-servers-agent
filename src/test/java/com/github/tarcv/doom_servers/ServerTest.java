package com.github.tarcv.doom_servers;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Created on 19.11.2016.
 */
public class ServerTest {
    @Test
    public void startServer() throws IOException {
        Path executable = Paths.get("debugCmdArgs.cmd");
        Path workdir = Paths.get("debugWorkDir");
        List<String> commandLine = Arrays.asList(
                "argument1",
                "+exec echo DoomServerReady"
        );

        Map<String, List<String>> configs = Collections.singletonMap("server.cfg", Arrays.asList("parameter1 1"));
        ServerConfiguration configuration = new ServerConfiguration(commandLine, configs);
        Server server = new Server(executable, workdir, configuration, consoleBlockingSink);
        server.run();
    }
}
