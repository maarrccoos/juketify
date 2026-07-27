package xyz.tekcor.juketify.client.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import net.fabricmc.loader.api.FabricLoader;
import xyz.tekcor.juketify.Juketify;

public final class MusicLibrary {

	private static final List<String> EXTENSIONS = List.of(".ogg");

	private static final Pattern BRACKETED = Pattern.compile("[(\\[][^)\\]]*[)\\]]");
	private static final Pattern FEATURING = Pattern.compile("\\b(feat|ft|featuring|with)\\b.*");
	private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
	private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

	private static final MusicLibrary INSTANCE = new MusicLibrary();

	private final List<Track> tracks = new ArrayList<>();
	private final List<String> keys = new ArrayList<>();

	private MusicLibrary() {
	}

	public static MusicLibrary get() {
		return INSTANCE;
	}

	public static Path musicDir() {
		return FabricLoader.getInstance().getGameDir().resolve("music");
	}

	public List<Track> tracks() {
		return List.copyOf(tracks);
	}

	public int size() {
		return tracks.size();
	}

	public synchronized void rescan() {
		tracks.clear();
		keys.clear();

		Path root = musicDir();
		try {
			Files.createDirectories(root);
		} catch (IOException e) {
			Juketify.LOGGER.error("Could not create music folder {}", root, e);
			return;
		}

		try (Stream<Path> walk = Files.walk(root)) {
			walk.filter(Files::isRegularFile)
					.filter(MusicLibrary::hasSupportedExtension)
					.sorted()
					.forEach(this::index);
		} catch (IOException e) {
			Juketify.LOGGER.error("Failed scanning music folder {}", root, e);
		}

		Juketify.LOGGER.info("Indexed {} track(s) from {}", tracks.size(), root);
	}

	private static boolean hasSupportedExtension(Path path) {
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return EXTENSIONS.stream().anyMatch(name::endsWith);
	}

	private void index(Path path) {
		Track track = read(path);
		tracks.add(track);

		keys.add(normalize(track.artist() + " " + track.title() + " " + track.fileName()));
	}

	public synchronized void addFile(Path path) {
		if (!hasSupportedExtension(path)) {
			return;
		}

		String fileName = path.getFileName().toString();

		for (Track existing : tracks) {
			if (existing.fileName().equals(fileName)) {
				return;
			}
		}

		index(path);
	}

	private static Track read(Path path) {
		String name = stripExtension(path.getFileName().toString()).trim();

		int dash = name.indexOf(" - ");
		if (dash > 0) {
			String artist = name.substring(0, dash).trim();
			String title = name.substring(dash + 3).trim();

			if (!artist.isEmpty() && !title.isEmpty()) {
				return new Track(path, title, artist, "", 0L);
			}
		}

		return new Track(path, name, "", "", 0L);
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	static String normalize(String raw) {
		String s = raw.toLowerCase(Locale.ROOT);
		s = Normalizer.normalize(s, Normalizer.Form.NFD);
		s = DIACRITICS.matcher(s).replaceAll("");
		s = BRACKETED.matcher(s).replaceAll(" ");
		s = FEATURING.matcher(s).replaceAll(" ");
		s = NON_ALNUM.matcher(s).replaceAll(" ");
		return s.trim().replaceAll("\\s+", " ");
	}

	public synchronized List<Track> search(String query) {
		String q = normalize(query);
		if (q.isEmpty()) {
			return List.of();
		}

		List<Scored> hits = new ArrayList<>();
		for (int i = 0; i < tracks.size(); i++) {
			int score = score(keys.get(i), q);
			if (score > 0) {
				hits.add(new Scored(tracks.get(i), score));
			}
		}

		hits.sort(Comparator.comparingInt(Scored::score).reversed()
				.thenComparing(s -> s.track().label()));

		return hits.stream().map(Scored::track).toList();
	}

	public synchronized Optional<Track> byFileName(String fileName) {
		return tracks.stream().filter(t -> t.fileName().equals(fileName)).findFirst();
	}

	public Optional<Track> best(String query) {
		List<Track> results = search(query);
		return results.isEmpty() ? Optional.empty() : Optional.of(results.getFirst());
	}

	private static int score(String key, String query) {
		if (key.equals(query)) {
			return 1000;
		}

		if (key.startsWith(query)) {
			return 800;
		}

		if (key.contains(query)) {
			return 600;
		}

		String[] words = query.split(" ");
		int matched = 0;
		for (String word : words) {
			if (key.contains(word)) {
				matched++;
			}
		}

		if (matched == words.length) {
			return 400;
		}

		return matched == 0 ? 0 : (100 * matched) / words.length;
	}

	private record Scored(Track track, int score) {
	}
}
