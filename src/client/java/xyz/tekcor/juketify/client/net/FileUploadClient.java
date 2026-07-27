package xyz.tekcor.juketify.client.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import xyz.tekcor.juketify.Juketify;
import xyz.tekcor.juketify.net.JukeboxUploadChunkPayload;
import xyz.tekcor.juketify.net.JukeboxUploadStartPayload;

public final class FileUploadClient {

	private static final int CHUNKS_PER_TICK = 4;
	public static final int MAX_UPLOAD_BYTES = 32 * 1024 * 1024;

	private static final Deque<Upload> QUEUE = new ArrayDeque<>();

	private FileUploadClient() {
	}

	public static void offer(BlockPos pos, String fileName, Path file) {
		for (Upload queued : QUEUE) {
			if (queued.fileName.equals(fileName)) {
				return;
			}
		}

		byte[] data;

		try {
			long size = Files.size(file);

			if (size > MAX_UPLOAD_BYTES) {
				Juketify.LOGGER.warn("{} is too big to share ({} MB)", fileName, size / 1024 / 1024);
				return;
			}

			data = Files.readAllBytes(file);
		} catch (IOException e) {
			Juketify.LOGGER.error("Could not read {} to share it", file, e);
			return;
		}

		int totalChunks = Math.max(1, (data.length + JukeboxUploadChunkPayload.CHUNK_SIZE - 1)
				/ JukeboxUploadChunkPayload.CHUNK_SIZE);

		ClientPlayNetworking.send(new JukeboxUploadStartPayload(pos, fileName, data.length, totalChunks));
		QUEUE.add(new Upload(fileName, data, totalChunks));
	}

	public static void reset() {
		QUEUE.clear();
	}

	public static void tick() {
		if (QUEUE.isEmpty()) {
			return;
		}

		Upload upload = QUEUE.peek();

		for (int i = 0; i < CHUNKS_PER_TICK && upload.nextChunk < upload.totalChunks; i++) {
			int index = upload.nextChunk;
			int offset = index * JukeboxUploadChunkPayload.CHUNK_SIZE;
			int length = Math.min(JukeboxUploadChunkPayload.CHUNK_SIZE, upload.data.length - offset);

			byte[] chunk = new byte[length];
			System.arraycopy(upload.data, offset, chunk, 0, length);

			ClientPlayNetworking.send(new JukeboxUploadChunkPayload(upload.fileName, index, chunk));
			upload.nextChunk++;
		}

		if (upload.nextChunk >= upload.totalChunks) {
			QUEUE.poll();
		}
	}

	private static final class Upload {
		final String fileName;
		final byte[] data;
		final int totalChunks;
		int nextChunk;

		Upload(String fileName, byte[] data, int totalChunks) {
			this.fileName = fileName;
			this.data = data;
			this.totalChunks = totalChunks;
		}
	}
}
