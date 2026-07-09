package ov4.socks5server.config;

import java.net.InetAddress;
import java.net.InetSocketAddress;

import ov4.socks5server.logging.LogLevel;

public record ProxyConfig(
		InetAddress listenAddress,
		int port,
		LogLevel logLevel,
		String username,
		String password
) {
	public InetSocketAddress listenSocketAddress() {
		return new InetSocketAddress(listenAddress, port);
	}
}
