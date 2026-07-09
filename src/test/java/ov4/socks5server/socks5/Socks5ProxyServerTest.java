package ov4.socks5server.socks5;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import ov4.socks5server.config.ProxyConfig;
import ov4.socks5server.logging.LogLevel;
import ov4.socks5server.logging.StdoutLogger;

class Socks5ProxyServerTest {
	private Socks5ProxyServer proxyServer;

	@AfterEach
	void stopProxy() {
		if (proxyServer != null) {
			proxyServer.stop();
		}
	}

	@Test
	void connectWithPasswordRelaysTcp() throws Exception {
		try (EchoServer echoServer = EchoServer.start()) {
			startProxy("user", "pass");

			try (Socket clientSocket = new Socket(InetAddress.getLoopbackAddress(), proxyServer.getBoundPort())) {
				InputStream inputStream = clientSocket.getInputStream();
				OutputStream outputStream = clientSocket.getOutputStream();

				assertAuthenticated(inputStream, outputStream, "user", "pass", true);
				sendConnectRequest(outputStream, echoServer.port());
				assertEquals(0x00, readReply(inputStream));

				byte[] payload = "hello through socks".getBytes(StandardCharsets.UTF_8);
				outputStream.write(payload);
				outputStream.flush();

				assertArrayEquals(payload, inputStream.readNBytes(payload.length));
			}
		}
	}

	@Test
	void rejectsClientsWithoutUsernamePasswordMethod() throws Exception {
		startProxy("user", "pass");

		try (Socket clientSocket = new Socket(InetAddress.getLoopbackAddress(), proxyServer.getBoundPort())) {
			InputStream inputStream = clientSocket.getInputStream();
			OutputStream outputStream = clientSocket.getOutputStream();

			outputStream.write(new byte[] {0x05, 0x01, 0x00});
			outputStream.flush();

			assertArrayEquals(new byte[] {0x05, (byte) 0xff}, inputStream.readNBytes(2));
		}
	}

	@Test
	void rejectsWrongPassword() throws Exception {
		startProxy("user", "pass");

		try (Socket clientSocket = new Socket(InetAddress.getLoopbackAddress(), proxyServer.getBoundPort())) {
			assertAuthenticated(clientSocket.getInputStream(), clientSocket.getOutputStream(), "user", "wrong", false);
		}
	}

	@Test
	void rejectsUnsupportedCommand() throws Exception {
		startProxy("user", "pass");

		try (Socket clientSocket = new Socket(InetAddress.getLoopbackAddress(), proxyServer.getBoundPort())) {
			InputStream inputStream = clientSocket.getInputStream();
			OutputStream outputStream = clientSocket.getOutputStream();

			assertAuthenticated(inputStream, outputStream, "user", "pass", true);
			outputStream.write(new byte[] {0x05, 0x02, 0x00, 0x01, 127, 0, 0, 1, 0, 80});
			outputStream.flush();

			assertEquals(0x07, readReply(inputStream));
		}
	}

	private void startProxy(String username, String password) throws IOException {
		ProxyConfig config = new ProxyConfig(
				InetAddress.getLoopbackAddress(),
				0,
				LogLevel.OFF,
				username,
				password);
		proxyServer = new Socks5ProxyServer(config, silentLogger());
		proxyServer.start();
	}

	private void assertAuthenticated(
			InputStream inputStream,
			OutputStream outputStream,
			String username,
			String password,
			boolean expectedSuccess
	) throws IOException {
		outputStream.write(new byte[] {0x05, 0x01, 0x02});
		outputStream.flush();
		assertArrayEquals(new byte[] {0x05, 0x02}, inputStream.readNBytes(2));

		byte[] usernameBytes = username.getBytes(StandardCharsets.UTF_8);
		byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);
		outputStream.write(0x01);
		outputStream.write(usernameBytes.length);
		outputStream.write(usernameBytes);
		outputStream.write(passwordBytes.length);
		outputStream.write(passwordBytes);
		outputStream.flush();

		assertArrayEquals(
				new byte[] {0x01, (byte) (expectedSuccess ? 0x00 : 0x01)},
				inputStream.readNBytes(2));
	}

	private void sendConnectRequest(OutputStream outputStream, int port) throws IOException {
		outputStream.write(new byte[] {
				0x05,
				0x01,
				0x00,
				0x01,
				127,
				0,
				0,
				1,
				(byte) ((port >>> 8) & 0xff),
				(byte) (port & 0xff)
		});
		outputStream.flush();
	}

	private int readReply(InputStream inputStream) throws IOException {
		byte[] header = inputStream.readNBytes(4);
		assertEquals(0x05, header[0] & 0xff);
		int addressLength = switch (header[3] & 0xff) {
			case 0x01 -> 4;
			case 0x03 -> inputStream.read();
			case 0x04 -> 16;
			default -> throw new IOException("Unsupported reply address type: " + (header[3] & 0xff));
		};
		inputStream.readNBytes(addressLength + 2);
		return header[1] & 0xff;
	}

	private StdoutLogger silentLogger() {
		return new StdoutLogger("test", LogLevel.OFF, new PrintStream(OutputStream.nullOutputStream()));
	}

	private static final class EchoServer implements AutoCloseable {
		private final ServerSocket serverSocket;
		private final CompletableFuture<Void> future;

		private EchoServer(ServerSocket serverSocket, CompletableFuture<Void> future) {
			this.serverSocket = serverSocket;
			this.future = future;
		}

		private static EchoServer start() throws IOException {
			ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
			CompletableFuture<Void> future = CompletableFuture.runAsync(() -> echoOnce(serverSocket));
			return new EchoServer(serverSocket, future);
		}

		private int port() {
			return serverSocket.getLocalPort();
		}

		@Override
		public void close() throws Exception {
			serverSocket.close();
			future.get(5, TimeUnit.SECONDS);
		}

		private static void echoOnce(ServerSocket serverSocket) {
			try (Socket socket = serverSocket.accept()) {
				socket.getInputStream().transferTo(socket.getOutputStream());
			} catch (IOException ignored) {
			}
		}
	}
}
