package xyz.tekcor.juketify.client.library;

import java.nio.file.Path;

public record Track(Path path, String title, String artist, String album, long durationMs) {

	public String label() {
		return artist.isEmpty() ? title : artist + " - " + title;
	}

	public String fileName() {
		return path.getFileName().toString();
	}
}
