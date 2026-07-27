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
		System.arraycopy(data, 0, transfer.buffer, offset, data.length);
		transfer.received++;

		if (transfer.received < transfer.totalChunks) {
			return;
		}

		ACTIVE.remove(fileName);

		if (save(fileName, transfer.buffer)) {
			onComplete.accept(transfer.pos);
		}
	}

	private static boolean save(String fileName, byte[] bytes) {
		try {
			Path musicDir = MusicLibrary.musicDir();
			Files.createDirectories(musicDir);
			Files.write(musicDir.resolve(fileName), bytes);
			return true;
		} catch (IOException e) {
			Juketify.LOGGER.error("Failed to save downloaded track {}", fileName, e);
			return false;
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
