package xyz.tekcor.juketify.client.net;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import xyz.tekcor.juketify.Juketify;
import xyz.tekcor.juketify.client.library.MusicLibrary;
import xyz.tekcor.juketify.net.JukeboxFileChunkPayload;

public final class FileTransferClient {

	private static final Map<String, Transfer> ACTIVE = new HashMap<>();

	private FileTransferClient() {
	}

	public static void begin(String fileName, BlockPos pos, int fileSize, int totalChunks) {
		ACTIVE.put(fileName, new Transfer(pos, new byte[fileSize], totalChunks));
	}

	public static void chunk(String fileName, int index, byte[] data, Consumer<BlockPos> onComplete) {
		Transfer transfer = ACTIVE.get(fileName);

		if (transfer == null) {
			return;
		}

		int offset = index * JukeboxFileChunkPayload.CHUNK_SIZE;

		if (offset < 0 || offset + data.length > transfer.buffer.length) {
			return;
		}

		System.arraycopy(data, 0, transfer.buffer, offset, data.length);
		transfer.received++;

		if (transfer.received < transfer.totalChunks) {
			return;
		}

		ACTIVE.remove(fileName);

		Path saved = save(fileName, transfer.buffer);

		if (saved != null) {
			MusicLibrary.get().addFile(saved);
			onComplete.accept(transfer.pos);
		}
	}

	public static boolean isDownloading(String fileName) {
		return ACTIVE.containsKey(fileName);
	}

	private static Path save(String fileName, byte[] bytes) {
		try {
			Path musicDir = MusicLibrary.musicDir();
			Files.createDirectories(musicDir);

			Path target = musicDir.resolve(fileName);
			Files.write(target, bytes);

			return target;
		} catch (IOException e) {
			Juketify.LOGGER.error("Failed to save downloaded track {}", fileName, e);
			return null;
		}
	}

	private static final class Transfer {
		final BlockPos pos;
		final byte[] buffer;
		final int totalChunks;
		int received;

		Transfer(BlockPos pos, byte[] buffer, int totalChunks) {
			this.pos = pos;
			this.buffer = buffer;
			this.totalChunks = totalChunks;
		}
	}
}
