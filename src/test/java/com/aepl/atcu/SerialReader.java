package com.aepl.atcu;

import com.fazecast.jSerialComm.*;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SerialReader with: - event-driven serial read (jSerialComm) - full-line
 * assembly (no fragmented prints) - ANSI stripping - aligned console+file
 * logging - parsing of SOFTWARE/VERSION/STATE and login CSV packets - in-memory
 * stateMap (state -> (software -> version)) - terminal input mode (read from
 * stdin and send to device)
 *
 * Usage: java -cp <jar> com.aepl.atcu.SerialReader [PORT_NAME] [BAUD]
 */
public class SerialReader {

	// CONFIG (override via args)
	private static final String PORT_NAME = "COM21";
	private static final int BAUD = 115200;
	private static final String LOG_FILE = "serial-stream.log";

	// Patterns
	private static final Pattern ANSI_ESCAPE = Pattern.compile("\u001B\\[[;\\d]*[A-Za-z]");
	private static final Pattern LEADING_TIMESTAMP = Pattern
			.compile("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{7}\\s+");
	private static final Pattern SOFTWARE_PATTERN = Pattern.compile("(?i)SOFTWARE[:=\\s]+([^\\s,;:\\n]+)");
	private static final Pattern VERSION_PATTERN = Pattern.compile("(?i)VERSION[:=\\s]+([^\\s,;:\\n]+)");
	private static final Pattern STATE_PATTERN = Pattern.compile("(?i)STATE[:=\\s]+([^\\s,;:\\n]+)");
	private static final Pattern VERSION_SIMPLE = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)*");
	private static final Pattern LOG_PARSE = Pattern
			.compile("^(\\S+)\\s*(?:([A-Z]+):\\s*)?(?:\\s*\\[([^\\]]+)\\]\\s*)?(.*)$");

	public BlockingQueue<String> getProcessorQueue() {
		return this.processorQueue;
	}

	// In-memory state map
	private final ConcurrentMap<String, ConcurrentMap<String, String>> stateMap = new ConcurrentHashMap<>();

	private final SerialPort port;
	private final BlockingQueue<String> writerQueue = new LinkedBlockingQueue<>(20000);
	private final BlockingQueue<String> processorQueue = new LinkedBlockingQueue<>(20000);

	private final ExecutorService writerExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "log-writer");
		t.setDaemon(true);
		return t;
	});
	private final ExecutorService processorExecutor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "message-processor");
		t.setDaemon(true);
		return t;
	});

	private final StringBuilder lineBuffer = new StringBuilder();

	public SerialReader(String portName, int baud) {
		port = SerialPort.getCommPort(portName);
		port.setBaudRate(baud);
		port.setNumDataBits(8);
		port.setNumStopBits(SerialPort.ONE_STOP_BIT);
		port.setParity(SerialPort.NO_PARITY);
		port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0);
	}

	public void start() {
		if (!port.openPort()) {
			System.err.println("Failed to open port: " + port.getSystemPortName());
			return;
		}
		System.out.println("Opened " + port.getSystemPortName());

		writerExecutor.submit(this::writeLoop);
		processorExecutor.submit(this::processLoop);

		// Serial data listener
		port.addDataListener(new SerialPortDataListener() {
			@Override
			public int getListeningEvents() {
				return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
			}

			@Override
			public void serialEvent(SerialPortEvent event) {
				if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE)
					return;
				int available = port.bytesAvailable();
				if (available <= 0)
					return;
				byte[] buffer = new byte[available];
				int read = port.readBytes(buffer, buffer.length);
				if (read > 0) {
					String chunk = new String(buffer, 0, read, StandardCharsets.UTF_8);
					handleIncomingChunk(chunk);
				}
			}
		});

		// Terminal input thread: read from stdin and send to serial
		Thread inputThread = new Thread(this::terminalInputLoop, "terminal-input");
		inputThread.setDaemon(false); // keep JVM alive while user interacts
		inputThread.start();

		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			stop();
			System.out.println("Shutdown complete.");
		}));
	}

	public void stop() {
		try {
			port.removeDataListener();
			if (port.isOpen())
				port.closePort();
		} finally {
			writerExecutor.shutdown();
			processorExecutor.shutdown();
			try {
				writerExecutor.awaitTermination(3, TimeUnit.SECONDS);
				processorExecutor.awaitTermination(3, TimeUnit.SECONDS);
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		}
	}

	/**
	 * Read from terminal (System.in) and send to serial port. Commands: - exit /
	 * quit -> stops program - otherwise the line is sent with CRLF appended
	 */
	private void terminalInputLoop() {
		try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8.name())) {
			System.out.println("[INPUT] Type commands to send to device. Type 'exit' or 'quit' to stop.");
			while (true) {
				if (!scanner.hasNextLine()) {
					Thread.sleep(50);
					continue;
				}
				String cmd = scanner.nextLine();
				if (cmd == null)
					continue;
				String trimmed = cmd.trim();
				if (trimmed.equalsIgnoreCase("exit") || trimmed.equalsIgnoreCase("quit")) {
					System.out.println("Exiting...");
					stop();
					// ensure JVM exits
					System.exit(0);
				}
				// Echo the command to console for clarity
				System.out.println("[SEND] " + cmd);
				byte[] bytes = (cmd + "\r\n").getBytes(StandardCharsets.UTF_8);
				try {
					port.writeBytes(bytes, bytes.length);
				} catch (Exception ex) {
					System.err.println("Failed to write to serial port: " + ex.getMessage());
				}
			}
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Assemble chunks into full lines, strip ANSI, timestamp and enqueue.
	 */
	private void handleIncomingChunk(String chunk) {
		if (chunk == null || chunk.isEmpty())
			return;
		synchronized (lineBuffer) {
			chunk = chunk.replace("\r\n", "\n").replace('\r', '\n');
			lineBuffer.append(chunk);
			int newlineIndex;
			while ((newlineIndex = lineBuffer.indexOf("\n")) != -1) {
				String rawLine = lineBuffer.substring(0, newlineIndex);
				lineBuffer.delete(0, newlineIndex + 1);
				String cleaned = stripAnsi(rawLine).trim();
				if (!cleaned.isEmpty()) {
					String timestamped = timestampWith7() + " " + cleaned;
					boolean wOk = writerQueue.offer(timestamped);
					boolean pOk = processorQueue.offer(timestamped);
					if (!wOk || !pOk) {
						System.err.println("Queue full: dropping line -> " + cleaned);
					}
				}
			}
		}
	}

	/**
	 * Writer: format and write to console and file
	 */
	private void writeLoop() {
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(LOG_FILE, true))) {
			while (!Thread.currentThread().isInterrupted()) {
				String raw = writerQueue.take();
				String formatted = formatLogLine(raw);
				System.out.println(formatted);
				bw.write(formatted);
				bw.newLine();
				bw.flush();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch (IOException ioe) {
			ioe.printStackTrace();
		}
	}

	/**
	 * Processor: parse tokens and update state map
	 */
	private void processLoop() {
		try {
			while (!Thread.currentThread().isInterrupted()) {
				String line = processorQueue.take();
				String withoutTs = LEADING_TIMESTAMP.matcher(line).replaceFirst("");
				handleMessage(withoutTs);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * Parse both labeled lines and login CSV packets for software versions.
	 */
	private void handleMessage(String line) {
		if (line == null || line.isEmpty())
			return;

		// 1) labeled SOFTWARE/VERSION/STATE preferred
		String software = extractToken(SOFTWARE_PATTERN, line);
		String version = extractToken(VERSION_PATTERN, line);
		String state = extractToken(STATE_PATTERN, line);

		if (software != null && version != null && state != null) {
			putVersionIntoMap(state.trim(), software.trim(), version.trim());
			return;
		}

		// 2) If labeled SOFTWARE found but VERSION missing, check if SOFTWARE value
		// itself is a version
		if (software != null && version == null) {
			if (VERSION_SIMPLE.matcher(software).find()) {
				putVersionIntoMap(state == null ? "UNKNOWN" : state.trim(), "SOFTWARE", software.trim());
				return;
			}
		}

		// 3) CSV login packet (e.g. "55AA,...,5.2.8,...|")
		if (line.startsWith("55AA") || line.contains("55AA,")) {
			String payload = line;
			int pipeIdx = payload.indexOf('|');
			if (pipeIdx != -1)
				payload = payload.substring(0, pipeIdx);
			String[] parts = payload.split(",");
			String foundVersion = null;
			for (String p : parts) {
				String t = p.trim();
				if (VERSION_SIMPLE.matcher(t).matches()) {
					foundVersion = t;
					break;
				}
			}
			String deviceId = null;
			if (parts.length > 6) {
				String candidate = parts[6].trim();
				if (!candidate.isEmpty() && !VERSION_SIMPLE.matcher(candidate).matches()) {
					deviceId = candidate;
				}
			}
			if (foundVersion != null) {
				String keySoftware = (deviceId != null) ? deviceId : "LOGIN_SOFTWARE";
				putVersionIntoMap("LOGIN", keySoftware, foundVersion);
				return;
			}
		}

		// 4) fallback: any standalone version-like token
		Matcher mVer = VERSION_SIMPLE.matcher(line);
		if (mVer.find()) {
			putVersionIntoMap("UNKNOWN", "SOFTWARE", mVer.group());
		}

		// Example action rules still available
		if (line.contains("ignStatus=1")) {
			System.out.println("[ACTION] ignition status is 1 -> trigger alert or API call");
		} else if (line.matches(".*VEHICLE\\s+.*:.*")) {
			System.out.println("[ACTION] vehicle info line detected");
		}
	}

	private void putVersionIntoMap(String state, String software, String version) {
		stateMap.computeIfAbsent(state, k -> new ConcurrentHashMap<>()).put(software, version);
		System.out.println("[MAP-UPDATE] state=" + state + " software=" + software + " version=" + version);
	}

	// --- utilities ---

	private static String extractToken(Pattern pattern, String input) {
		if (input == null)
			return null;
		Matcher m = pattern.matcher(input);
		if (m.find()) {
			String g = m.group(1);
			return (g != null) ? g.trim() : null;
		}
		return null;
	}

	private static String formatLogLine(String raw) {
		if (raw == null)
			return "";
		Matcher m = LOG_PARSE.matcher(raw);
		if (!m.matches())
			return raw;
		String ts = safeOrEmpty(m.group(1));
		String level = safeOrEmpty(m.group(2));
		String tag = safeOrEmpty(m.group(3));
		String msg = safeOrEmpty(m.group(4));
		String tagOut = tag.isEmpty() ? "" : "[" + tag + "]";
		String tsFmt = padRight(ts, 27);
		String levelFmt = padRight(level, 6);
		String tagFmt = padRight(tagOut, 12);
		return String.format("%s %s %s %s", tsFmt, levelFmt, tagFmt, msg);
	}

	private static String safeOrEmpty(String s) {
		return (s == null) ? "" : s;
	}

	private static String padRight(String s, int width) {
		if (s == null)
			s = "";
		if (s.length() >= width)
			return s.substring(0, width);
		StringBuilder sb = new StringBuilder(s);
		while (sb.length() < width)
			sb.append(' ');
		return sb.toString();
	}

	private static String timestampWith7() {
		LocalDateTime now = LocalDateTime.now();
		int nano = now.getNano();
		int frac7 = nano / 100;
		String fracStr = String.format("%07d", frac7);
		String base = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"));
		return base + "." + fracStr;
	}

	private static String stripAnsi(String input) {
		if (input == null)
			return null;
		return ANSI_ESCAPE.matcher(input).replaceAll("");
	}

	// JSON snapshot (simple)
	private String toJson() {
		StringBuilder sb = new StringBuilder();
		sb.append("{");
		boolean firstState = true;
		for (Map.Entry<String, ConcurrentMap<String, String>> stateEntry : stateMap.entrySet()) {
			if (!firstState)
				sb.append(", ");
			firstState = false;
			sb.append("\"").append(escapeJson(stateEntry.getKey())).append("\": ");
			sb.append("{");
			boolean firstSw = true;
			for (Map.Entry<String, String> swEntry : stateEntry.getValue().entrySet()) {
				if (!firstSw)
					sb.append(", ");
				firstSw = false;
				sb.append("\"").append(escapeJson(swEntry.getKey())).append("\": ");
				sb.append("\"").append(escapeJson(swEntry.getValue())).append("\"");
			}
			sb.append("}");
		}
		sb.append("}");
		return sb.toString();
	}

	private static String escapeJson(String s) {
		if (s == null)
			return "";
		return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
	}

	// Runtime accessors
	public String getVersionFor(String state, String software) {
		ConcurrentMap<String, String> swMap = stateMap.get(state);
		return (swMap == null) ? null : swMap.get(software);
	}

	public Map<String, ConcurrentMap<String, String>> getStateMapSnapshot() {
		return new ConcurrentHashMap<>(stateMap);
	}

	// Main
	public static void main(String[] args) {
		String portName = args.length > 0 ? args[0] : PORT_NAME;
		int baud = args.length > 1 ? Integer.parseInt(args[1]) : BAUD;
		SerialReader reader = new SerialReader(portName, baud);
		reader.start();
		try {
			Thread.currentThread().join();
		} catch (InterruptedException ignored) {
		}
	}
}
