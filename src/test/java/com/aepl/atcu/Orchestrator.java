package com.aepl.atcu;

import org.openqa.selenium.By;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.support.ui.ExpectedConditions;
import org.openqa.selenium.support.ui.WebDriverWait;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Orchestrator: uses SerialReader + Selenium to perform iterative FOTA until
 * device reaches latest version.
 *
 * Assumptions: - SerialReader has the getProcessorQueue() method returning
 * BlockingQueue<String> - CSV firmware list contains:
 * firmware_id,firmware_version,firmware_file_path
 */
public class Orchestrator {

	private final SerialReader serialReader;
	private final BlockingQueue<String> serialQueue;
	private final WebDriver driver;
	private final WebDriverWait wait;
	private final Path auditCsv;
	private final List<Firmware> firmwareList;

	// simple version pattern (reuse from your reader if you want)
	private static final Pattern VERSION_SIMPLE = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)*");

	public Orchestrator(String serialPort, int baud, String chromeDriverPath, String firmwareCsvPath,
			String auditCsvPath) throws Exception {
		this.serialReader = new SerialReader(serialPort, baud);
		this.serialQueue = null; // will set after start
		System.setProperty("webdriver.chrome.driver", chromeDriverPath);
		this.driver = new ChromeDriver();
		this.wait = new WebDriverWait(driver, java.time.Duration.ofSeconds(30));
		this.auditCsv = Paths.get(auditCsvPath);
		this.firmwareList = readFirmwareCsv(firmwareCsvPath);
		// ensure audit file exists with header
		if (Files.notExists(auditCsv)) {
			Files.write(auditCsv, Collections.singletonList(
					"timestamp,deviceId,firmwareId,firmwareVersion,firmwarePath,beforeVersion,afterVersion,result,jobId"),
					StandardOpenOption.CREATE);
		}
	}

	private List<Firmware> readFirmwareCsv(String csvPath) throws IOException {
		List<Firmware> list = new ArrayList<>();
		List<String> lines = Files.readAllLines(Paths.get(csvPath), StandardCharsets.UTF_8);
		for (String l : lines) {
			String trim = l.trim();
			if (trim.isEmpty() || trim.startsWith("#") || trim.startsWith("firmware_id"))
				continue;
			String[] parts = trim.split(",");
			if (parts.length < 3)
				continue;
			list.add(new Firmware(parts[0].trim(), parts[1].trim(), parts[2].trim()));
		}
		// sort by version ascending if needed
		list.sort(Comparator.comparing(f -> parseVersionKey(f.version)));
		return list;
	}

	private long parseVersionKey(String v) {
		// crude numeric key: 1.2.3 -> 1_02_03 as long; works for simple semantic
		// versions
		String[] s = v.split("\\.");
		long key = 0;
		for (int i = 0; i < Math.min(3, s.length); i++) {
			int n = Integer.parseInt(s[i]);
			key = key * 1000 + n;
		}
		return key;
	}

	public void start(String loginUrl, String user, String pass, String deviceId) throws Exception {
		// start serial reader
		serialReader.start();
		BlockingQueue<String> q = serialReader.getProcessorQueue();

		// start selenium session + login (adapt selectors)
		driver.get(loginUrl);
		// adapt the element ids for your app:
		wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("username"))).sendKeys(user);
		driver.findElement(By.id("password")).sendKeys(pass);
		driver.findElement(By.id("loginButton")).click();
		wait.until(d -> d.getCurrentUrl().contains("dashboard"));

		// iterate firmware list
		for (Firmware fw : firmwareList) {
			// read current known version from stateMap if available
			String beforeVersion = serialReader.getVersionFor("LOGIN", deviceId);
			// trigger FOTA via web UI
			String jobId = triggerFotaViaUi(deviceId, fw);
			// wait for device to report version change or ack
			String afterVersion = waitForVersionFromQueue(q, 120, TimeUnit.SECONDS);
			// decide outcome
			String result;
			if (afterVersion == null) {
				result = "TIMEOUT";
			} else if (isVersionLessOrEqual(afterVersion, fw.version)) {
				// device reports version <= what we targeted -> success or partial?
				result = "REPORTED:" + afterVersion;
			} else {
				result = "REPORTED_HIGHER:" + afterVersion;
			}
			// write audit line
			writeAudit(deviceId, fw, beforeVersion, afterVersion, result, jobId);
			// if device afterVersion >= latest available -> break
			String latest = firmwareList.get(firmwareList.size() - 1).version;
			if (afterVersion != null && !isVersionLess(afterVersion, latest)) {
				System.out.println("Device reached latest version " + afterVersion + ". Stopping.");
				break;
			}
			// else continue to next firmware in list
		}

		shutdown();
	}

	private String waitForVersionFromQueue(BlockingQueue<String> q, long timeout, TimeUnit unit)
			throws InterruptedException {
		long deadline = System.currentTimeMillis() + unit.toMillis(timeout);
		while (System.currentTimeMillis() < deadline) {
			String tsLine = q.poll(2, TimeUnit.SECONDS);
			if (tsLine == null)
				continue;
			// strip leading timestamp like your reader does
			String withoutTs = tsLine.replaceFirst("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{7}\\s+", "");
			// attempt to extract version token
			Matcher m = VERSION_SIMPLE.matcher(withoutTs);
			if (m.find()) {
				String ver = m.group();
				System.out.println("[ORC] Found version on serial: " + ver + " (line=" + withoutTs + ")");
				// capture extra acknowledgement lines if needed (e.g., "ACK" token)
				return ver;
			} else {
				// optionally search for ack or job id in the line; also log
				System.out.println("[ORC] Serial line: " + withoutTs);
			}
		}
		return null;
	}

	private boolean isVersionLess(String a, String b) {
		return isVersionLessOrEqual(a, b) && !a.equals(b);
	}

	private boolean isVersionLessOrEqual(String a, String b) {
		int[] A = parse(a), B = parse(b);
		for (int i = 0; i < Math.max(A.length, B.length); i++) {
			int av = i < A.length ? A[i] : 0;
			int bv = i < B.length ? B[i] : 0;
			if (av < bv)
				return true;
			if (av > bv)
				return false;
		}
		return true; // equal
	}

	private int[] parse(String ver) {
		String[] parts = ver.split("\\.");
		int[] out = new int[parts.length];
		for (int i = 0; i < parts.length; i++)
			out[i] = Integer.parseInt(parts[i]);
		return out;
	}

	private String triggerFotaViaUi(String deviceId, Firmware fw) {
		// Simplified example: adapt to your app
		// Navigate to FOTA page
		driver.get("https://app.example.com/fota");
		wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("deviceId"))).sendKeys(deviceId);
		driver.findElement(By.id("firmwareUpload")).sendKeys(new File(fw.path).getAbsolutePath());
		driver.findElement(By.id("startFota")).click();
		// wait for job id element to appear
		try {
			String jobId = wait.until(ExpectedConditions.visibilityOfElementLocated(By.id("jobId"))).getText();
			System.out.println("[ORC] Submitted FOTA jobId=" + jobId + " for firmware=" + fw.id);
			return jobId;
		} catch (Exception e) {
			System.out.println("[ORC] No jobId returned; continuing without it.");
			return null;
		}
	}

	private void writeAudit(String deviceId, Firmware fw, String before, String after, String result, String jobId) {
		String ts = DateTimeFormatter.ISO_INSTANT.format(Instant.now());
		String line = String.join(",", ts, deviceId, fw.id, fw.version, fw.path, safe(before), safe(after),
				safe(result), safe(jobId));
		try {
			Files.write(auditCsv, Collections.singletonList(line), StandardOpenOption.APPEND);
		} catch (IOException e) {
			System.err.println("Failed to write audit: " + e.getMessage());
		}
	}

	private String safe(String s) {
		return (s == null) ? "" : s.replace(",", ";");
	}

	public void shutdown() {
		try {
			if (serialReader != null)
				serialReader.stop();
		} catch (Exception ignored) {
		}
		try {
			if (driver != null)
				driver.quit();
		} catch (Exception ignored) {
		}
	}

	// small inner model
	private static class Firmware {
		final String id;
		final String version;
		final String path;

		Firmware(String id, String version, String path) {
			this.id = id;
			this.version = version;
			this.path = path;
		}
	}

	// main for quick run (adapt args)
	public static void main(String[] args) throws Exception {
		// args: <serialPort> <baud> <chromeDriverPath> <firmwareCsv> <auditCsv>
		// <loginUrl> <user> <pass> <deviceId>
		Orchestrator o = new Orchestrator(args.length > 0 ? args[0] : "COM21",
				args.length > 1 ? Integer.parseInt(args[1]) : 115200,
				args.length > 2 ? args[2] : "/path/to/chromedriver", args.length > 3 ? args[3] : "fota_list.csv",
				args.length > 4 ? args[4] : "fota_audit.csv");
		String loginUrl = args.length > 5 ? args[5] : "https://app.example.com/login";
		String user = args.length > 6 ? args[6] : "admin";
		String pass = args.length > 7 ? args[7] : "password";
		String deviceId = args.length > 8 ? args[8] : "ATCU1234";
		o.start(loginUrl, user, pass, deviceId);
	}
}
