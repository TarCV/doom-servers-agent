package com.github.tarcv.doom_servers;

import org.apache.commons.exec.*;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.*;

/**
 * Created by TarCV on 13.11.2016.
 */
public class Server {
    private final ServerConfiguration configuration;
    private final File executable;
    private final File workDir;
    private ProcessDestroyer processDestroyer;

    public Server(Path executable, Path workDir, ServerConfiguration configuration) {
        this.executable = executable.toFile();
        this.workDir = workDir.toFile();
        this.configuration = configuration;
    }

    public void run() throws IOException {
        CommandLine commandLine = new CommandLine(executable);
        final boolean handleQuoting = true;
        commandLine.addArguments(configuration.getCommandline().toArray(new String[0]), handleQuoting);

        createFiles(workDir, configuration.getConfigs());

        Executor exec = new DefaultExecutor();
        processDestroyer = new ShutdownHookProcessDestroyer();
        ExecuteStreamHandler executeStreamHandler = new PumpStreamHandler();
        exec.setWorkingDirectory(workDir);
        exec.setProcessDestroyer(processDestroyer);
        exec.setStreamHandler(executeStreamHandler);
        exec.execute(commandLine);
    }

    private void createFiles(File workDir, Map<String, List<String>> configs) {
        final Path basePath = workDir.toPath();

        configs.forEach((name, lines) -> {
            Path subPath = Paths.get(name);
            if (subPath.isAbsolute()) {
                throw new RuntimeException(subPath.toString() + " is an absolute path. Discarding request as invalid");
            }

            Path configPath = basePath.resolve(subPath);
            if (!isChild(basePath, configPath)) {
                throw new RuntimeException(subPath.toString() + " is an dangerous path. Discarding request as invalid");
            }

            try {
                Files.createDirectories(configPath.getParent());
                Files.write(configPath, lines, StandardCharsets.UTF_8,
                        CREATE, TRUNCATE_EXISTING, WRITE);
            } catch (IOException e) {
                throw new RuntimeException("Failed to write " + configPath, e);
            }
        });
    }

    private static boolean isChild(Path parent, Path supposedChild) {
        return supposedChild.toAbsolutePath().startsWith(parent.toAbsolutePath());
    }

    public void onOutputLine(String line) {

    }

    public void onErrorLine(String line) {

    }

    private Thread setupLineHandler(InputStream is, Consumer<Scanner> consumer) {
        Runnable runnable = () -> {
            Scanner scanner = new Scanner(is);
            while (scanner.hasNextLine()) {
                consumer.accept(scanner);
            }
        };
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    }

    private class ScanningStreamHandler implements ExecuteStreamHandler {
        Thread errorThread;
        Thread outputThread;

        @Override
        public void setProcessInputStream(OutputStream os) throws IOException {
            try {
                os.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void setProcessErrorStream(InputStream is) throws IOException {
            errorThread = setupLineHandler(is, scanner -> onErrorLine(scanner.nextLine()));
        }

        @Override
        public void setProcessOutputStream(InputStream is) throws IOException {
            outputThread = setupLineHandler(is, scanner -> onOutputLine(scanner.nextLine()));
        }

        @Override
        public void start() throws IOException {
            if (outputThread != null) {
                outputThread.start();
            }
            if (errorThread != null) {
                errorThread.start();
            }
        }

        @Override
        public void stop() throws IOException {
            if (outputThread != null) {
                outputThread.interrupt();
            }
            if (errorThread != null) {
                errorThread.interrupt();
            }
        }
    }
}
