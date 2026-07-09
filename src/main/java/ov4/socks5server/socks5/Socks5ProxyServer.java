package ov4.socks5server.socks5;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import ov4.socks5server.config.ProxyConfig;
import ov4.socks5server.logging.StdoutLogger;

public final class Socks5ProxyServer {
	private final ProxyConfig config;
	private final StdoutLogger logger;
	private final ExecutorService executor;
	private final AtomicBoolean running = new AtomicBoolean(false);

	private ServerSocket serverSocket;
	private Thread acceptThread;

	public Socks5ProxyServer(ProxyConfig config, StdoutLogger logger) {
		this.config = config;
		this.logger = logger;
		this.executor = Executors.newCachedThreadPool(new DaemonThreadFactory("socks5-server-worker-"));
	}

	public void start() throws IOException {
		if (!running.compareAndSet(false, true)) {
			return;
		}

		ServerSocket socket = new ServerSocket();
		try {
			socket.bind(config.listenSocketAddress());
			serverSocket = socket;
		} catch (IOException exception) {
			running.set(false);
			try {
				socket.close();
			} catch (IOException ignored) {
			}
			throw exception;
		}

		acceptThread = new Thread(this::acceptLoop, "socks5-server-accept");
		acceptThread.setDaemon(true);
		acceptThread.start();

		logger.info("SOCKS5 server listening on "
				+ socket.getInetAddress().getHostAddress() + ":" + socket.getLocalPort());
	}

	public void stop() {
		if (!running.compareAndSet(true, false)) {
			return;
		}

		closeServerSocket();
		executor.shutdownNow();
		logger.info("SOCKS5 server stopped");
	}

	public int getBoundPort() {
		ServerSocket socket = serverSocket;
		return socket == null ? -1 : socket.getLocalPort();
	}

	private void acceptLoop() {
		while (running.get()) {
			try {
				Socket clientSocket = serverSocket.accept();
				executor.execute(() -> handleClient(clientSocket));
			} catch (SocketException exception) {
				if (running.get()) {
					logger.error("SOCKS5 accept loop failed: " + exception.getMessage(), exception);
				}
			} catch (IOException exception) {
				if (running.get()) {
					logger.error("SOCKS5 accept loop failed: " + exception.getMessage(), exception);
				}
			}
		}
	}

	private void handleClient(Socket clientSocket) {
		try (Socket socket = clientSocket) {
			logger.debug("Accepted SOCKS5 client " + socket.getRemoteSocketAddress());
			new Socks5Session(socket, config, logger, executor).handle();
		} catch (IOException exception) {
			logger.debug("SOCKS5 client closed: " + exception.getMessage());
		}
	}

	private void closeServerSocket() {
		ServerSocket socket = serverSocket;
		if (socket == null) {
			return;
		}

		try {
			socket.close();
		} catch (IOException exception) {
			logger.debug("Failed to close SOCKS5 server socket: " + exception.getMessage());
		}
	}

	private static final class DaemonThreadFactory implements ThreadFactory {
		private final String prefix;
		private final AtomicInteger nextId = new AtomicInteger(1);

		private DaemonThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, prefix + nextId.getAndIncrement());
			thread.setDaemon(true);
			return thread;
		}
	}
}
