package ov4.socks5server.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ov4.socks5server.logging.LogLevel;
import ov4.socks5server.logging.StdoutLogger;

class ConfigLoaderTest {
	@TempDir
	private Path tempDir;

	@Test
	void createsDefaultConfigWhenMissing() throws Exception {
		Path configPath = tempDir.resolve(ConfigLoader.CONFIG_FILE_NAME);

		ProxyConfig config = ConfigLoader.loadOrCreate(configPath, silentLogger());

		assertTrue(Files.exists(configPath));
		assertEquals("127.0.0.1", config.listenAddress().getHostAddress());
		assertEquals(1080, config.port());
		assertEquals(LogLevel.INFO, config.logLevel());
		assertEquals("minecraft", config.username());
		assertFalse(config.password().isBlank());
	}

	@Test
	void parsesLogLevelCaseInsensitively() throws Exception {
		Properties properties = validProperties();
		properties.setProperty("log.level", "debug");

		ProxyConfig config = ConfigLoader.parse(properties);

		assertEquals(LogLevel.DEBUG, config.logLevel());
	}

	@Test
	void rejectsInvalidPort() {
		Properties properties = validProperties();
		properties.setProperty("listen.port", "0");

		ConfigException exception = assertThrows(ConfigException.class, () -> ConfigLoader.parse(properties));

		assertTrue(exception.getMessage().contains("listen.port"));
	}

	@Test
	void rejectsEmptyPassword() {
		Properties properties = validProperties();
		properties.setProperty("auth.password", " ");

		ConfigException exception = assertThrows(ConfigException.class, () -> ConfigLoader.parse(properties));

		assertTrue(exception.getMessage().contains("auth.password"));
	}

	private Properties validProperties() {
		Properties properties = new Properties();
		properties.setProperty("listen.address", "127.0.0.1");
		properties.setProperty("listen.port", "1080");
		properties.setProperty("log.level", "INFO");
		properties.setProperty("auth.username", "minecraft");
		properties.setProperty("auth.password", "secret");
		return properties;
	}

	private StdoutLogger silentLogger() {
		return new StdoutLogger("test", LogLevel.OFF, new PrintStream(OutputStream.nullOutputStream()));
	}
}
