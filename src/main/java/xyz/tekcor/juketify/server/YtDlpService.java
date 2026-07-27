package xyz.tekcor.juketify.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import xyz.tekcor.juketify.Juketify;

public final class YtDlpService {

	private static final String YT_DLP = "yt-dlp";

	private static final int MIN_DURATION_SECONDS = 20;
	private static final int MAX_DURATION_SECONDS = 15 * 60;
	private static final double MIN_SCORE = 0.5D;

	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
	private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

	private static final List<String> JUNK_MARKERS = List.of(
			"instrumental", "karaoke", "cover", "remix", "sped up", "slowed",
			"reverb", "nightcore", "8d audio", "loop", "1 hour", "lyrics");

	private static final Executor DOWNLOAD_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "juketify-ytdlp");
		thread.setDaemon(true);
		return thread;
	});

	private YtDlpService() {
	}

	public record Result(String videoId, String title, String artist) {

		public String label() {
			return this.artist.isEmpty() ? this.title : this.artist + " - " + this.title;
		}
	}

	private record Candidate(String videoId, String title, String artist, int durationSeconds) {
	}

	public static CompletableFuture<Result> searchBest(String query) {
		return CompletableFuture.supplyAsync(() -> {
			List<Candidate> candidates = searchYouTubeMusic(query);

			if (candidates.isEmpty()) {
				candidates = searchYouTube(query);
			}

			Result best = pickBest(query, candidates);

			if (best == null) {
				throw new IllegalStateException("No good match for \"" + query + "\"");
			}

			return best;
		}, DOWNLOAD_EXECUTOR);
	}

	private static List<Candidate> searchYouTubeMusic(String query) {
		String url = "https://music.youtube.com/search?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

		List<String> cmd = List.of(
				YT_DLP,
				url,
				"--skip-download",
				"--no-warnings",
				"--playlist-items", "1-5",
				"--print", "%(id)s\t%(title)s\t%(duration)s\t%(artist)s");

		return parseCandidates(runLines(cmd, 45));
	}

	private static List<Candidate> searchYouTube(String query) {
		List<String> cmd = List.of(
				YT_DLP,
				"ytsearch5:" + query,
				"--skip-download",
				"--no-playlist",
				"--no-warnings",
				"--print", "%(id)s\t%(title)s\t%(duration)s\t%(channel)s");

		return parseCandidates(runLines(cmd, 45));
	}

	private static List<Candidate> parseCandidates(List<String> lines) {
		List<Candidate> candidates = new ArrayList<>();

		for (String line : lines) {
			String[] parts = line.split("\t", -1);

			if (parts.length < 4) {
				continue;
			}

			String videoId = parts[0].trim();

			if (videoId.length() != 11) {
				continue;
			}

			int duration;
			try {
				duration = (int) Double.parseDouble(parts[2].trim());
			} catch (NumberFormatException e) {
				continue;
			}

			if (duration < MIN_DURATION_SECONDS || duration > MAX_DURATION_SECONDS) {
				continue;
			}

			String artist = parts[3].trim();

			if (artist.equals("NA")) {
				artist = "";
			}

			candidates.add(new Candidate(videoId, parts[1].trim(), artist, duration));
		}

		return candidates;
	}

	private static Result pickBest(String query, List<Candidate> candidates) {
		String normalisedQuery = normalise(query);
		Set<String> queryTokens = tokens(normalisedQuery);

		if (queryTokens.isEmpty()) {
			return null;
		}

		Candidate best = null;
		double bestScore = 0.0D;

		for (Candidate candidate : candidates) {
			double score = score(normalisedQuery, queryTokens, candidate);

			if (score > bestScore) {
				bestScore = score;
				best = candidate;
			}
		}

		if (best == null || bestScore < MIN_SCORE) {
			return null;
		}

		return new Result(best.videoId(), best.title(), best.artist());
	}

	private static double score(String normalisedQuery, Set<String> queryTokens, Candidate candidate) {
		String haystack = normalise(candidate.artist() + " " + candidate.title());
		Set<String> haystackTokens = tokens(haystack);

		int matched = 0;

		for (String token : queryTokens) {
			if (haystackTokens.contains(token)) {
				matched++;
			}
		}

		double score = matched / (double) queryTokens.size();

		if (haystack.contains(normalisedQuery)) {
			score += 0.25D;
		}

		if (!candidate.artist().isEmpty()) {
			score += 0.1D;
		}

		String rawTitle = candidate.title().toLowerCase(Locale.ROOT);

		for (String marker : JUNK_MARKERS) {
			if (rawTitle.contains(marker) && !normalisedQuery.contains(normalise(marker))) {
				score -= 0.35D;
				break;
			}
		}

		return score;
	}

	private static Set<String> tokens(String normalised) {
		Set<String> result = new LinkedHashSet<>();

		for (String token : normalised.split(" ")) {
			if (!token.isBlank()) {
				result.add(token);
			}
		}

		return result;
	}

	private static String normalise(String raw) {
		String s = raw.toLowerCase(Locale.ROOT);
		s = Normalizer.normalize(s, Normalizer.Form.NFD);
		s = DIACRITICS.matcher(s).replaceAll("");
		s = NON_ALNUM.matcher(s).replaceAll(" ");
		return s.trim().replaceAll("\\s+", " ");
	}

	public static CompletableFuture<String> ensureDownloaded(Result result, Path musicDir) {
		String baseName = sanitizeFileName(result.label());
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
					"-o", musicDir.resolve(baseName + ".%(ext)s").toString());

			runLines(cmd, 180);

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

	private static List<String> runLines(List<String> cmd, int timeoutSeconds) {
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
				Juketify.LOGGER.warn("yt-dlp exited {}: {}", process.exitValue(), String.join(" | ", lines));
				return List.of();
			}

			return lines;
		} catch (IOException e) {
			throw new IllegalStateException("Failed to run yt-dlp: " + cmd, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted running yt-dlp: " + cmd, e);
		}
	}
}
