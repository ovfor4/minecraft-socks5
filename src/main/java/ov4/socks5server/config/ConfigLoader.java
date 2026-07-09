package ov4.socks5server.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Properties;

import ov4.socks5server.logging.LogLevel;
import ov4.socks5server.logging.StdoutLogger;

public final class ConfigLoader {
	public static final String CONFIG_FILE_NAME = "socks5-server.properties";

	private static final String LISTEN_ADDRESS = "listen.address";
	private static final String LISTEN_PORT = "listen.port";
	private static final String LOG_LEVEL = "log.level";
	private static final String AUTH_USERNAME = "auth.username";
	private static final String AUTH_PASSWORD = "auth.password";
	private static final SecureRandom RANDOM = new SecureRandom();

	private ConfigLoader() {
	}

	public static ProxyConfig loadOrCreate(Path path, StdoutLogger logger) throws IOException, ConfigException {
		if (Files.notExists(path)) {
			createDefaultConfig(path);
			logger.info("Created default SOCKS5 config at " + path);
		}

		Properties properties = new Properties();
		try (InputStream inputStream = Files.newInputStream(path)) {
			properties.load(inputStream);
		}

		return parse(properties);
	}

	static ProxyConfig parse(Properties properties) throws ConfigException {
		InetAddress listenAddress = parseListenAddress(required(properties, LISTEN_ADDRESS));
		int port = parsePort(required(properties, LISTEN_PORT));
		LogLevel logLevel = parseLogLevel(required(properties, LOG_LEVEL));
		String username = validateCredential(AUTH_USERNAME, required(properties, AUTH_USERNAME));
		String password = validateCredential(AUTH_PASSWORD, required(properties, AUTH_PASSWORD));

		return new ProxyConfig(listenAddress, port, logLevel, username, password);
	}

	private static void createDefaultConfig(Path path) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		Properties properties = new Properties();
		properties.setProperty(LISTEN_ADDRESS, "127.0.0.1");
		properties.setProperty(LISTEN_PORT, "1080");
		properties.setProperty(LOG_LEVEL, "INFO");
		properties.setProperty(AUTH_USERNAME, "minecraft");
		properties.setProperty(AUTH_PASSWORD, randomPassword());

		Path temporaryPath = path.resolveSibling(path.getFileName() + ".tmp");
		Files.deleteIfExists(temporaryPath);
		try (OutputStream outputStream = Files.newOutputStream(temporaryPath)) {
			properties.store(outputStream, "SOCKS5 Server configuration");
		}

		try {
			Files.move(temporaryPath, path, StandardCopyOption.ATOMIC_MOVE);
		} catch (AtomicMoveNotSupportedException ignored) {
			Files.move(temporaryPath, path, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private static String required(Properties properties, String key) throws ConfigException {
		String value = properties.getProperty(key);
		if (value == null) {
			throw new ConfigException("Missing config key: " + key);
		}
		return value.trim();
	}

	private static InetAddress parseListenAddress(String value) throws ConfigException {
		if (value.isEmpty()) {
			throw new ConfigException("listen.address must not be empty");
		}

		try {
			return InetAddress.getByName(value);
		} catch (UnknownHostException exception) {
			throw new ConfigException("Invalid listen.address: " + value, exception);
		}
	}

	private static int parsePort(String value) throws ConfigException {
		try {
			int port = Integer.parseInt(value);
			if (port < 1 || port > 65535) {
				throw new ConfigException("listen.port must be between 1 and 65535");
			}
			return port;
		} catch (NumberFormatException exception) {
			throw new ConfigException("listen.port must be a number", exception);
		}
	}

	private static LogLevel parseLogLevel(String value) throws ConfigException {
		try {
			return LogLevel.parse(value);
		} catch (IllegalArgumentException exception) {
			throw new ConfigException("Invalid log.level: " + value, exception);
		}
	}

	private static String validateCredential(String key, String value) throws ConfigException {
		if (value.isEmpty()) {
			throw new ConfigException(key + " must not be empty");
		}
		if (value.getBytes(StandardCharsets.UTF_8).length > 255) {
			throw new ConfigException(key + " must be 255 UTF-8 bytes or less");
		}
		return value;
	}

	private static String randomPassword() {
		byte[] bytes = new byte[32];
		RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
