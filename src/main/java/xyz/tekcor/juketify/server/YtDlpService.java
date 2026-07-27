package xyz.tekcor.juketify.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class YtDlpService {

	private static final String YT_DLP = "yt-dlp";

	private static final Executor DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "juketify-ytdlp");
		thread.setDaemon(true);
		return thread;
	});

	private YtDlpService() {
	}

	public record Result(String videoId, String title) {
	}

	public static CompletableFuture<Result> searchBest(String query) {
		return CompletableFuture.supplyAsync(() -> {
			List<String> cmd = List.of(
					YT_DLP,
					"ytsearch1:" + query,
					"--skip-download",
					"--no-playlist",
					"--no-warnings",
					"--print", "%(id)s\t%(title)s"
			);

			String line = runAndReadLastLine(cmd, 30);
			int tab = line.indexOf('\t');
			if (tab < 0) {
				throw new IllegalStateException("Unexpected yt-dlp output: " + line);
			}

			return new Result(line.substring(0, tab), line.substring(tab + 1));
		}, DOWNLOAD_EXECUTOR);
	}

	public static CompletableFuture<String> ensureDownloaded(Result result, Path musicDir) {
		String baseName = sanitizeFileName(result.title());
		String fileName = baseName + ".ogg";
		Path target = musicDir.resolve(fileName);

		if (Files.exists(target)) {
			return CompletableFuture.completedFuture(fileName);
		}

		return CompletableFuture.supplyAsync(() -> {
			try {
				Files.createDirectories(musicDir);
			} catch (IOException e) {
				throw new IllegalStateException("Could not create music folder " + musicDir, e);
			}

			List<String> cmd = List.of(
					YT_DLP,
					"https://www.youtube.com/watch?v=" + result.videoId(),
					"-x", "--audio-format", "vorbis",
					"--no-playlist",
					"--no-warnings",
					"-o", musicDir.resolve(baseName + ".%(ext)s").toString()
			);

			runAndReadLastLine(cmd, 120);

			if (!Files.exists(target)) {
				throw new IllegalStateException("yt-dlp finished but " + target + " is missing");
			}

			return fileName;
		}, DOWNLOAD_EXECUTOR);
	}

	private static String sanitizeFileName(String rawTitle) {
		String cleaned = rawTitle.replaceAll("[\\\\/:*?\"<>|]", "").trim();
		cleaned = cleaned.replaceAll("\\s+", " ");

		if (cleaned.length() > 150) {
			cleaned = cleaned.substring(0, 150).trim();
		}

		return cleaned.isEmpty() ? "track" : cleaned;
	}

	private static String runAndReadLastLine(List<String> cmd, int timeoutSeconds) {
		try {
			ProcessBuilder pb = new ProcessBuilder(cmd);
			pb.redirectErrorStream(true);
			Process process = pb.start();

			List<String> lines = new ArrayList<>();
			try (BufferedReader reader =
					new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (!line.isBlank()) {
						lines.add(line);
					}
				}
			}

			boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				throw new IllegalStateException("yt-dlp timed out: " + cmd);
			}
			if (process.exitValue() != 0) {
				throw new IllegalStateException("yt-dlp failed: " + String.join(" | ", lines));
			}
			if (lines.isEmpty()) {
				throw new IllegalStateException("yt-dlp produced no output: " + cmd);
			}

			return lines.get(lines.size() - 1);
		} catch (IOException e) {
			throw new IllegalStateException("Failed to run yt-dlp: " + cmd, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted running yt-dlp: " + cmd, e);
		}
	}
}
