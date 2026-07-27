package xyz.tekcor.juketify.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClientJukebox {
	private static final List<String> LIBRARY = new ArrayList<>();
	private static final List<String> QUEUE = new ArrayList<>();

	private static String nowPlaying = "";
	private static String downloading = "";
	private static int downloadPercent;

	private ClientJukebox() {
	}

	public static synchronized void setLibrary(List<String> fileNames) {
		LIBRARY.clear();
		LIBRARY.addAll(fileNames);
	}

	public static synchronized List<String> library() {
		return List.copyOf(LIBRARY);
	}

	public static synchronized void setQueue(String playing, List<String> upcoming) {
		nowPlaying = playing;
		QUEUE.clear();
		QUEUE.addAll(upcoming);
	}

	public static synchronized List<String> queue() {
		return List.copyOf(QUEUE);
	}

	public static synchronized String nowPlaying() {
		return nowPlaying;
	}

	public static synchronized void setProgress(String fileName, int percent) {
		downloading = percent >= 100 ? "" : fileName;
		downloadPercent = percent;
	}

	public static synchronized String downloading() {
		return downloading;
	}

	public static synchronized int downloadPercent() {
		return downloadPercent;
	}

	public static String label(String fileName) {
		if (fileName == null || fileName.isEmpty()) {
			return "";
		}

		String name = fileName;
		int dot = name.lastIndexOf('.');

		if (dot > 0) {
			name = name.substring(0, dot);
		}

		return name;
	}

	public static boolean matches(String fileName, String query) {
		return label(fileName).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT));
	}
}
