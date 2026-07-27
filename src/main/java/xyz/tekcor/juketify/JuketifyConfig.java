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

	private static int radius = DEFAULT_RADIUS;

	private JuketifyConfig() {
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("juketify.txt");
	}

	public static int radius() {
		return radius;
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

				if (parts.length == 2 && parts[0].trim().equals("radius")) {
					radius = clamp(Integer.parseInt(parts[1].trim()));
				}
			}
		} catch (IOException | NumberFormatException e) {
			Juketify.LOGGER.warn("Could not read {}, using radius {}", path, radius, e);
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
					"radius=" + radius));
		} catch (IOException e) {
			Juketify.LOGGER.error("Could not write {}", path, e);
		}
	}
}
