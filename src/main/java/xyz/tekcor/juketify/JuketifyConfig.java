package xyz.tekcor.juketify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;

public final class JuketifyConfig {
	public static final int MIN_RADIUS = 16;
	public static final int MAX_RADIUS = 128;
	public static final int DEFAULT_RADIUS = 64;

	public static final int MIN_CHUNKS_PER_TICK = 1;
	public static final int MAX_CHUNKS_PER_TICK = 128;
	public static final int DEFAULT_CHUNKS_PER_TICK = 32;

	public static final int MIN_IN_FLIGHT = 1;
	public static final int MAX_IN_FLIGHT = 32;
	public static final int DEFAULT_IN_FLIGHT = 8;

	private static int radius = DEFAULT_RADIUS;
	private static int chunksPerTick = DEFAULT_CHUNKS_PER_TICK;
	private static int inFlight = DEFAULT_IN_FLIGHT;

	private JuketifyConfig() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("juketify.txt");
	}

	public static int radius() {
		return radius;
	}

	public static int chunksPerTick() {
		return chunksPerTick;
	}

	public static int inFlight() {
		return inFlight;
	}

	public static int clamp(int value) {
		return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, value));
	}

	public static void set(int value) {
		radius = clamp(value);
		save();
	}

	public static void load() {
		Path path = file();

		if (!Files.isRegularFile(path)) {
			save();
			return;
		}

		try {
			for (String line : Files.readAllLines(path)) {
				String trimmed = line.trim();

				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}

				String[] parts = trimmed.split("=", 2);

				if (parts.length != 2) {
					continue;
				}

				String key = parts[0].trim();
				int value = Integer.parseInt(parts[1].trim());

				switch (key) {
					case "radius" -> radius = clamp(value);
					case "chunksPerTick" -> chunksPerTick =
							Math.max(MIN_CHUNKS_PER_TICK, Math.min(MAX_CHUNKS_PER_TICK, value));
					case "inFlight" -> inFlight =
							Math.max(MIN_IN_FLIGHT, Math.min(MAX_IN_FLIGHT, value));
					default -> {
					}
				}
			}
		} catch (IOException | NumberFormatException e) {
			Juketify.LOGGER.warn("Could not read {}, using defaults", path, e);
		}
	}

	private static void save() {
		Path path = file();

		try {
			Files.createDirectories(path.getParent());
			Files.write(path, List.of(
					"# How far a jukebox can be heard, in blocks.",
					"# Allowed range: " + MIN_RADIUS + " to " + MAX_RADIUS + ".",
					"# Change in game with /juketify radius <blocks>",
					"radius=" + radius,
					"",
					"# How fast tracks are sent to players who don't have them yet.",
					"# chunksPerTick is shared across everyone downloading; each chunk is 16 KB.",
					"# 32 works out to a ceiling of about 10 MB/s. Lower it if hosting chokes your upload.",
					"chunksPerTick=" + chunksPerTick,
					"",
					"# How many chunks may be in flight to one player before waiting for the network.",
					"inFlight=" + inFlight));
		} catch (IOException e) {
			Juketify.LOGGER.error("Could not write {}", path, e);
		}
	}
}
