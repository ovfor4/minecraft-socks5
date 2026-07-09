package ov4.socks5server.logging;

import java.util.Locale;

public enum LogLevel {
	DEBUG(10),
	INFO(20),
	WARN(30),
	ERROR(40),
	OFF(50);

	private final int priority;

	LogLevel(int priority) {
		this.priority = priority;
	}

	public boolean allows(LogLevel level) {
		return level.priority >= priority && this != OFF;
	}

	public static LogLevel parse(String value) {
		return LogLevel.valueOf(value.trim().toUpperCase(Locale.ROOT));
	}
}
