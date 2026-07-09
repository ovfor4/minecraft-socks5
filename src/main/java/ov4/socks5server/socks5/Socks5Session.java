package ov4.socks5server.socks5;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import ov4.socks5server.config.ProxyConfig;
import ov4.socks5server.logging.StdoutLogger;

final class Socks5Session {
	private static final int VERSION = 0x05;
	private static final int NO_ACCEPTABLE_METHODS = 0xff;
	private static final int USERNAME_PASSWORD = 0x02;
	private static final int AUTH_VERSION = 0x01;
	private static final int AUTH_SUCCESS = 0x00;
	private static final int AUTH_FAILURE = 0x01;
	private static final int COMMAND_CONNECT = 0x01;
	private static final int REPLY_SUCCEEDED = 0x00;
	private static final int REPLY_GENERAL_FAILURE = 0x01;
	private static final int REPLY_CONNECTION_NOT_ALLOWED = 0x02;
	private static final int REPLY_NETWORK_UNREACHABLE = 0x03;
	private static final int REPLY_HOST_UNREACHABLE = 0x04;
	private static final int REPLY_CONNECTION_REFUSED = 0x05;
	private static final int REPLY_TTL_EXPIRED = 0x06;
	private static final int REPLY_COMMAND_NOT_SUPPORTED = 0x07;
	private static final int REPLY_ADDRESS_TYPE_NOT_SUPPORTED = 0x08;
	private static final int ATYP_IPV4 = 0x01;
	private static final int ATYP_DOMAIN = 0x03;
	private static final int ATYP_IPV6 = 0x04;
	private static final int HANDSHAKE_TIMEOUT_MILLIS = 30_000;
	private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
	private static final int BUFFER_SIZE = 16 * 1024;

	private final Socket clientSocket;
	private final ProxyConfig config;
	private final StdoutLogger logger;
	private final Executor executor;

	Socks5Session(Socket clientSocket, ProxyConfig config, StdoutLogger logger, Executor executor) {
		this.clientSocket = clientSocket;
		this.config = config;
		this.logger = logger;
		this.executor = executor;
	}

	void handle() throws IOException {
		clientSocket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);

		InputStream inputStream = new BufferedInputStream(clientSocket.getInputStream());
		OutputStream outputStream = new BufferedOutputStream(clientSocket.getOutputStream());

		if (!negotiate(inputStream, outputStream) || !authenticate(inputStream, outputStream)) {
			return;
		}

		Request request;
		try {
			request = readRequest(inputStream);
		} catch (ProtocolException exception) {
			sendReply(outputStream, exception.replyCode(), null, 0);
			return;
		}

		if (request.command() != COMMAND_CONNECT) {
			sendReply(outputStream, REPLY_COMMAND_NOT_SUPPORTED, null, 0);
			return;
		}

		connectAndRelay(request, inputStream, outputStream);
	}

	private boolean negotiate(InputStream inputStream, OutputStream outputStream) throws IOException {
		int version = readUnsignedByte(inputStream);
		if (version != VERSION) {
			writeAndFlush(outputStream, VERSION, NO_ACCEPTABLE_METHODS);
			return false;
		}

		int methodCount = readUnsignedByte(inputStream);
		boolean supportsUsernamePassword = false;
		for (int i = 0; i < methodCount; i++) {
			if (readUnsignedByte(inputStream) == USERNAME_PASSWORD) {
				supportsUsernamePassword = true;
			}
		}

		if (!supportsUsernamePassword) {
			writeAndFlush(outputStream, VERSION, NO_ACCEPTABLE_METHODS);
			return false;
		}

		writeAndFlush(outputStream, VERSION, USERNAME_PASSWORD);
		return true;
	}

	private boolean authenticate(InputStream inputStream, OutputStream outputStream) throws IOException {
		int version = readUnsignedByte(inputStream);
		if (version != AUTH_VERSION) {
			writeAndFlush(outputStream, AUTH_VERSION, AUTH_FAILURE);
			return false;
		}

		byte[] username = readSizedBytes(inputStream);
		byte[] password = readSizedBytes(inputStream);
		boolean accepted = MessageDigest.isEqual(username, config.username().getBytes(StandardCharsets.UTF_8))
				&& MessageDigest.isEqual(password, config.password().getBytes(StandardCharsets.UTF_8));

		writeAndFlush(outputStream, AUTH_VERSION, accepted ? AUTH_SUCCESS : AUTH_FAILURE);
		if (!accepted) {
			logger.warn("Rejected SOCKS5 authentication from " + clientSocket.getRemoteSocketAddress());
		}
		return accepted;
	}

	private Request readRequest(InputStream inputStream) throws IOException, ProtocolException {
		int version = readUnsignedByte(inputStream);
		if (version != VERSION) {
			throw new ProtocolException(REPLY_GENERAL_FAILURE);
		}

		int command = readUnsignedByte(inputStream);
		int reserved = readUnsignedByte(inputStream);
		if (reserved != 0) {
			throw new ProtocolException(REPLY_GENERAL_FAILURE);
		}

		int addressType = readUnsignedByte(inputStream);
		String host = readHost(inputStream, addressType);
		int port = (readUnsignedByte(inputStream) << 8) | readUnsignedByte(inputStream);
		return new Request(command, host, port);
	}

	private String readHost(InputStream inputStream, int addressType) throws IOException, ProtocolException {
		if (addressType == ATYP_IPV4) {
			byte[] address = readFully(inputStream, 4);
			return InetAddress.getByAddress(address).getHostAddress();
		}
		if (addressType == ATYP_DOMAIN) {
			int length = readUnsignedByte(inputStream);
			if (length == 0) {
				throw new ProtocolException(REPLY_ADDRESS_TYPE_NOT_SUPPORTED);
			}
			return new String(readFully(inputStream, length), StandardCharsets.UTF_8);
		}
		if (addressType == ATYP_IPV6) {
			byte[] address = readFully(inputStream, 16);
			return InetAddress.getByAddress(address).getHostAddress();
		}

		throw new ProtocolException(REPLY_ADDRESS_TYPE_NOT_SUPPORTED);
	}

	private void connectAndRelay(Request request, InputStream clientInput, OutputStream clientOutput) throws IOException {
		try (Socket remoteSocket = new Socket()) {
			remoteSocket.connect(new InetSocketAddress(request.host(), request.port()), CONNECT_TIMEOUT_MILLIS);
			clientSocket.setSoTimeout(0);
			sendReply(clientOutput, REPLY_SUCCEEDED, remoteSocket.getLocalAddress(), remoteSocket.getLocalPort());
			logger.debug("SOCKS5 CONNECT " + request.host() + ":" + request.port());
			relay(clientInput, clientOutput, remoteSocket);
		} catch (IOException exception) {
			sendReply(clientOutput, mapConnectFailure(exception), null, 0);
			logger.debug("SOCKS5 CONNECT failed for " + request.host() + ":" + request.port()
					+ ": " + exception.getMessage());
		}
	}

	private int mapConnectFailure(IOException exception) {
		if (exception instanceof ConnectException) {
			return REPLY_CONNECTION_REFUSED;
		}
		if (exception instanceof SocketTimeoutException) {
			return REPLY_TTL_EXPIRED;
		}
		String message = exception.getMessage();
		if (message != null && message.toLowerCase().contains("network is unreachable")) {
			return REPLY_NETWORK_UNREACHABLE;
		}
		if (message != null && message.toLowerCase().contains("permission")) {
			return REPLY_CONNECTION_NOT_ALLOWED;
		}
		return REPLY_HOST_UNREACHABLE;
	}

	private void relay(InputStream clientInput, OutputStream clientOutput, Socket remoteSocket) throws IOException {
		InputStream remoteInput = remoteSocket.getInputStream();
		OutputStream remoteOutput = remoteSocket.getOutputStream();
		CompletableFuture<Void> clientToRemote = CompletableFuture.runAsync(
				() -> copyQuietly(clientInput, remoteOutput), executor);
		CompletableFuture<Void> remoteToClient = CompletableFuture.runAsync(
				() -> copyQuietly(remoteInput, clientOutput), executor);

		CompletableFuture.anyOf(clientToRemote, remoteToClient).join();
		closeQuietly(remoteSocket);
	}

	private void copyQuietly(InputStream inputStream, OutputStream outputStream) {
		byte[] buffer = new byte[BUFFER_SIZE];
		try {
			int count;
			while ((count = inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, count);
				outputStream.flush();
			}
		} catch (IOException ignored) {
		}
	}

	private void sendReply(OutputStream outputStream, int replyCode, InetAddress bindAddress, int bindPort)
			throws IOException {
		byte[] addressBytes = bindAddress == null
				? new byte[] {0, 0, 0, 0}
				: bindAddress.getAddress();
		int addressType = addressBytes.length == 16 ? ATYP_IPV6 : ATYP_IPV4;

		outputStream.write(VERSION);
		outputStream.write(replyCode);
		outputStream.write(0x00);
		outputStream.write(addressType);
		outputStream.write(addressBytes);
		outputStream.write((bindPort >>> 8) & 0xff);
		outputStream.write(bindPort & 0xff);
		outputStream.flush();
	}

	private byte[] readSizedBytes(InputStream inputStream) throws IOException {
		int length = readUnsignedByte(inputStream);
		return readFully(inputStream, length);
	}

	private byte[] readFully(InputStream inputStream, int length) throws IOException {
		byte[] bytes = inputStream.readNBytes(length);
		if (bytes.length != length) {
			throw new EOFException("Unexpected end of SOCKS5 packet");
		}
		return bytes;
	}

	private int readUnsignedByte(InputStream inputStream) throws IOException {
		int value = inputStream.read();
		if (value < 0) {
			throw new EOFException("Unexpected end of SOCKS5 packet");
		}
		return value;
	}

	private void writeAndFlush(OutputStream outputStream, int... values) throws IOException {
		for (int value : values) {
			outputStream.write(value);
		}
		outputStream.flush();
	}

	private void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}

	private record Request(int command, String host, int port) {
	}

	private static final class ProtocolException extends Exception {
		private final int replyCode;

		private ProtocolException(int replyCode) {
			this.replyCode = replyCode;
		}

		private int replyCode() {
			return replyCode;
		}
	}
}
