package com.aepl.atcu;

import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.*;
import java.util.Properties;

/**
 * Launcher: reads config.properties (from current dir or classpath) and starts
 * Orchestrator.
 *
 * Usage: - Put config.properties next to the jar and run: java -jar
 * target/fota.jar
 *
 * - Or run from IDE: run this Launcher main class.
 */
public class Launcher {

	private static final String CONFIG_FILE = "config.properties";

	public static void main(String[] args) {
		Properties p = new Properties();

		// 1) Try working directory
		Path cwdConfig = Paths.get(System.getProperty("user.dir")).resolve(CONFIG_FILE);
		try (InputStream in = Files.exists(cwdConfig) ? new FileInputStream(cwdConfig.toFile())
				: Launcher.class.getResourceAsStream("/" + CONFIG_FILE)) {
			if (in != null) {
				p.load(in);
				System.out.println(
						"Loaded config from: " + (Files.exists(cwdConfig) ? cwdConfig.toAbsolutePath() : "classpath"));
			} else {
				System.out.println(
						"No config.properties found in working dir or classpath — using defaults or env vars.");
			}
		} catch (Exception e) {
			System.err.println("Failed to load config.properties: " + e.getMessage());
		}

		// 2) Read values with defaults or env var overrides
		String serialPort = get(p, "serial.port", "COM21");
		int baud = Integer.parseInt(get(p, "serial.baud", "115200"));
		String chromeDriver = get(p, "chromedriver.path", "/usr/bin/chromedriver");
		String firmwareCsv = get(p, "firmware.csv", "fota_list.csv");
		String auditCsv = get(p, "audit.csv", "fota_audit.csv");
		String loginUrl = get(p, "login.url", "https://app.example.com/login");
		String user = get(p, "login.user", "admin");
		String pass = get(p, "login.pass", "password");
		String deviceId = get(p, "device.id", "ATCU1234");

		// optional timeouts (not used directly here but available)
		String serialWait = get(p, "serial.wait.seconds", "120");
		String seleniumWait = get(p, "selenium.wait.seconds", "30");

		try {
			// instantiate Orchestrator using these values
			Orchestrator orch = new Orchestrator(serialPort, baud, chromeDriver, firmwareCsv, auditCsv);

			// start will perform login & orchestrate; pass login details and device id
			orch.start(loginUrl, user, pass, deviceId);
		} catch (Exception e) {
			System.err.println("Fatal error starting orchestrator: " + e.getMessage());
			e.printStackTrace();
			System.exit(2);
		}
	}

	private static String get(Properties p, String key, String def) {
		// priority: properties file -> environment variable -> default
		String v = p.getProperty(key);
		if (v != null && !v.trim().isEmpty())
			return v.trim();
		String envKey = key.toUpperCase().replace('.', '_');
		String env = System.getenv(envKey);
		if (env != null && !env.trim().isEmpty())
			return env.trim();
		return def;
	}
}
