package ov4.socks5server.logging;

import java.io.PrintStream;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public final class StdoutLogger {
	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

	private final String name;
	private final LogLevel threshold;
	private final PrintStream output;

	public StdoutLogger(String name, LogLevel threshold) {
		this(name, threshold, System.out);
	}

	public StdoutLogger(String name, LogLevel threshold, PrintStream output) {
		this.name = Objects.requireNonNull(name, "name");
		this.threshold = Objects.requireNonNull(threshold, "threshold");
		this.output = Objects.requireNonNull(output, "output");
	}

	public void debug(String message) {
		log(LogLevel.DEBUG, message, null);
	}

	public void info(String message) {
		log(LogLevel.INFO, message, null);
	}

	public void warn(String message) {
		log(LogLevel.WARN, message, null);
	}

	public void error(String message) {
		log(LogLevel.ERROR, message, null);
	}

	public void error(String message, Throwable throwable) {
		log(LogLevel.ERROR, message, throwable);
	}

	private void log(LogLevel level, String message, Throwable throwable) {
		if (!threshold.allows(level)) {
			return;
		}

		synchronized (output) {
			output.println(FORMATTER.format(OffsetDateTime.now(ZoneOffset.UTC))
					+ " [" + level + "] [" + name + "] " + message);
			if (throwable != null) {
				throwable.printStackTrace(output);
			}
			output.flush();
		}
	}
}
