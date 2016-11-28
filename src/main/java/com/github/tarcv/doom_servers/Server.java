package com.github.tarcv.doom_servers;

import org.jetbrains.annotations.Nullable;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.StartedProcess;
import org.zeroturnaround.exec.stream.ExecuteStreamHandler;
import org.zeroturnaround.exec.stream.PumpStreamHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static java.nio.file.StandardOpenOption.*;

/**
 * Created by TarCV on 13.11.2016.
 */
public class Server {
    private final ServerConfiguration configuration;
    private final File executable;
    private final File workDir;
    private PrintWriter processInputSource;

    @Nullable
    private volatile OutputHandler outputLineHandler = null;

    private List<Thread> handlingThreads = new ArrayList<>();
    private StartedProcess serverProcess;

    public Server(Path executable, Path workDir, ServerConfiguration configuration) {
        this.executable = executable.toFile();
        this.workDir = workDir.toFile();
        this.configuration = configuration;
    }

    public void run() throws IOException, TimeoutException, InterruptedException {
        List<String> commandParts = new ArrayList<>();
        commandParts.add(executable.toString());
        commandParts.addAll(configuration.getCommandline());

        createFiles(workDir, configuration.getConfigs());

        ExecuteStreamHandler executeStreamHandler = prepareStreamHandler();
        assert this.processInputSource != null;

        ServerInitingWaiter serverInitingWaiter = new ServerInitingWaiter(processInputSource);
        outputLineHandler = serverInitingWaiter;

        serverProcess = new ProcessExecutor()
                .command(commandParts)
                .directory(workDir)
                .streams(executeStreamHandler)
                .destroyOnExit()
                .start();

        serverInitingWaiter.await();
    }

    private ExecuteStreamHandler prepareStreamHandler() throws IOException {
        InputStream processInputStream = createProcessInputStream();

        OutputStream processOutputSink = setupLineHandler("STDOUT handler", scanner -> {
            try {
                onOutputLine(scanner.nextLine());
            } catch (IOException e) {
                e.printStackTrace();
            }
        });
        OutputStream processErrorSink = setupLineHandler("STDERR handler", scanner -> onErrorLine(scanner.nextLine()));

        return new FlushingPumpStreamHandler(processOutputSink, processErrorSink, processInputStream);
    }

    private InputStream createProcessInputStream() throws IOException {
        PipedOutputStream inputSourceStream = new PipedOutputStream();
        processInputSource = new PrintWriter(inputSourceStream, true);
        return new PipedInputStream(inputSourceStream);
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

    public void onOutputLine(String line) throws IOException {
        if (outputLineHandler != null) {
            OutputHandler tmp = outputLineHandler;
            assert tmp != null;
            tmp.onOutputLine(line);

        }
        System.out.println("Output:" + line);
    }

    public void onErrorLine(String line) {
        System.err.println("Error:" + line);
    }

    private OutputStream setupLineHandler(String threadName, Consumer<Scanner> consumer) {
        PipedOutputStream outputStream = new PipedOutputStream();
        Runnable runnable = () -> {
            try {
                PipedInputStream is = new PipedInputStream(outputStream);
                Scanner scanner = new Scanner(is);
                while (scanner.hasNextLine()) {
                    consumer.accept(scanner);
                }
            } catch (IOException e) {
                // TODO: better handle this fatal error
                throw new RuntimeException(e);
            }
        };
        Thread thread = new Thread(runnable, threadName);
        thread.setDaemon(true);
        thread.start();
        handlingThreads.add(thread);
        return outputStream;
    }

    public List<String> executeConsole(List<String> command) throws InterruptedException, TimeoutException {
        // It is assumed that command contains a game-specific command to output 'DoomConsoleResultEnd' right after the command result
        for (String subcommand : command) {
            processInputSource.println(subcommand);
        }

        try {
            ConsoleResultWaiter consoleResultWaiter = new ConsoleResultWaiter();
            outputLineHandler = consoleResultWaiter;
            return consoleResultWaiter.await();
        } finally {
            outputLineHandler = null;
        }
    }

    /**
     * Original {@link PumpStreamHandler} doesn't flush 'input stream' OutputStream when needed.
     * So it cannot be used to send some command to a running executable.<br />
     * This Handler fixes that <br />
     * TODO: Check if this handler is needed with ZT-Exec<br/>
     *
     * Also it closed Server.processInputSource in {@link #stop()}. Which is required to correctly shutdown streams.
     */
    private class FlushingPumpStreamHandler extends PumpStreamHandler {
        private static final int BUFFER_SIZE = 1024;
        private final InputStream processInputStream;
        Thread inputThread;

        public FlushingPumpStreamHandler(OutputStream processOutputSink, OutputStream processErrorSink, InputStream processInputStream) {
            super(processOutputSink, processErrorSink, new ByteArrayInputStream(new byte[]{0}));
            this.processInputStream = processInputStream;
        }

        @Override
        public void setProcessInputStream(OutputStream os) {
            Runnable pumpingRunnable = () -> {
                byte[] buffer = new byte[BUFFER_SIZE];
                int readBytes;
                try {
                    while ((readBytes = processInputStream.read(buffer)) > 0) {
                        os.write(buffer, 0, readBytes);
                        if (processInputStream.available() == 0) {
                            os.flush();
                        }
                    }
                    os.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            };
            Thread pumpingThread = new Thread(pumpingRunnable);
            pumpingThread.setDaemon(true);
            inputThread = pumpingThread;
        }

        @Override
        public void start() {
            super.start();
            inputThread.start();
        }

        @Override
        public void stop() {
            processInputSource.close(); //otherwise super.stop() will hang

            super.stop();

            try {
                inputThread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static class ServerInitingWaiter implements OutputHandler {
        private final CountDownLatch initNotifier = new CountDownLatch(1);
        private final Object lock = new Object();
        private final PrintWriter processInputSource;

        public ServerInitingWaiter(PrintWriter processInputSource) {
            this.processInputSource = processInputSource;
        }

        @Override
        public void onOutputLine(String line) {
            synchronized (lock) {
                if (line.contains("DoomServerReady")) {
                    processInputSource.println("echo DoomConsoleReady");
                } else if (line.contains("DoomConsoleReady")) {
                    initNotifier.countDown();
                }
            }
        }

        void await() throws InterruptedException, TimeoutException {
            if (!initNotifier.await(60, TimeUnit.SECONDS)) {
                throw new TimeoutException("Timed out waiting for server to start");
            }
        }
    }

    private static class ConsoleResultWaiter implements OutputHandler {
        private final CountDownLatch consoleResultNotifier = new CountDownLatch(1);
        private final List<String> consoleResultBuffer = new ArrayList<>();
        private final Object lock = new Object();

        @Override
        public void onOutputLine(String line) {
            synchronized (lock) {
                // chat messages will be filtered out by server not agent
                if (line.contains("DoomConsoleResultEnd")) {
                    onResultEnd();
                    return;
                }

                if (consoleResultNotifier.getCount() == 0) {
                    throw new IllegalStateException("Got line after finished waiting for console result");
                }
                consoleResultBuffer.add(line);
            }
        }

        void onResultEnd() {
            synchronized (lock) {
                consoleResultNotifier.countDown();
            }
        }

        List<String> await() throws InterruptedException, TimeoutException {
            if (!consoleResultNotifier.await(30, TimeUnit.SECONDS)) {
                throw new TimeoutException("Timed out waiting for console result");
            }
            return consoleResultBuffer;
        }

    }
}
