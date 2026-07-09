package ov4.socks5server;

import java.io.IOException;
import java.nio.file.Path;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import ov4.socks5server.config.ConfigException;
import ov4.socks5server.config.ConfigLoader;
import ov4.socks5server.config.ProxyConfig;
import ov4.socks5server.logging.LogLevel;
import ov4.socks5server.logging.StdoutLogger;
import ov4.socks5server.socks5.Socks5ProxyServer;

public final class Socks5ServerMod implements ModInitializer {
	public static final String MOD_ID = "socks5-server";

	private static Socks5ProxyServer server;

	@Override
	public void onInitialize() {
		StdoutLogger bootstrapLogger = new StdoutLogger(MOD_ID, LogLevel.INFO);
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve(ConfigLoader.CONFIG_FILE_NAME);

		try {
			ProxyConfig config = ConfigLoader.loadOrCreate(configPath, bootstrapLogger);
			StdoutLogger logger = new StdoutLogger(MOD_ID, config.logLevel());
			Socks5ProxyServer proxyServer = new Socks5ProxyServer(config, logger);
			proxyServer.start();
			server = proxyServer;

			Runtime.getRuntime().addShutdownHook(new Thread(proxyServer::stop, MOD_ID + "-shutdown"));
		} catch (ConfigException | IOException exception) {
			bootstrapLogger.error("SOCKS5 server did not start: " + exception.getMessage(), exception);
		}
	}
}
