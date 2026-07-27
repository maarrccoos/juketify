package xyz.tekcor.juketify.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import xyz.tekcor.juketify.Juketify;
import xyz.tekcor.juketify.net.JukeboxUploadChunkPayload;

public final class FileUploadServer {

	public static final int MAX_UPLOAD_BYTES = 32 * 1024 * 1024;

	private static final Map<UUID, Upload> ACTIVE = new HashMap<>();

	private FileUploadServer() {
	}

	public static boolean isValidName(String fileName) {
		if (fileName.isEmpty() || fileName.length() > 200) {
			return false;
		}

		if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
			return false;
		}

		return fileName.toLowerCase(Locale.ROOT).endsWith(".ogg");
	}

	public static void begin(ServerPlayer player, BlockPos pos, String fileName, int fileSize, int totalChunks) {
		if (!isValidName(fileName)) {
			Juketify.LOGGER.warn("{} tried to upload a bad file name: {}",
					player.getGameProfile().name(), fileName);
			return;
		}

		if (fileSize <= 0 || fileSize > MAX_UPLOAD_BYTES || totalChunks <= 0) {
			Juketify.LOGGER.warn("{} tried to upload {} with a bad size ({} bytes)",
					player.getGameProfile().name(), fileName, fileSize);
			return;
		}

		int expectedChunks = Math.max(1, (fileSize + JukeboxUploadChunkPayload.CHUNK_SIZE - 1)
				/ JukeboxUploadChunkPayload.CHUNK_SIZE);

		if (totalChunks != expectedChunks) {
			return;
		}

		Juketify.LOGGER.info("Receiving {} ({} KB) from {}",
				fileName, fileSize / 1024, player.getGameProfile().name());

		ACTIVE.put(player.getUUID(), new Upload(pos, fileName, new byte[fileSize], totalChunks));
	}

	public static void chunk(ServerPlayer player, Path musicDir, String fileName, int index, byte[] data,
			BiConsumer<BlockPos, String> onComplete) {

		Upload upload = ACTIVE.get(player.getUUID());

		if (upload == null || !upload.fileName.equals(fileName)) {
			return;
		}

		int offset = index * JukeboxUploadChunkPayload.CHUNK_SIZE;

		if (index < 0 || index >= upload.totalChunks
				|| offset < 0 || offset + data.length > upload.buffer.length) {
			ACTIVE.remove(player.getUUID());
			return;
		}

		System.arraycopy(data, 0, upload.buffer, offset, data.length);
		upload.received++;

		if (upload.received < upload.totalChunks) {
			return;
		}

		ACTIVE.remove(player.getUUID());

		Path target = musicDir.resolve(upload.fileName).normalize();

		if (!target.startsWith(musicDir)) {
			return;
		}

		try {
			Files.createDirectories(musicDir);
			Files.write(target, upload.buffer);
			Juketify.LOGGER.info("Saved {} shared by {}", upload.fileName, player.getGameProfile().name());
		} catch (IOException e) {
			Juketify.LOGGER.error("Could not save uploaded track {}", target, e);
			return;
		}

		onComplete.accept(upload.pos, upload.fileName);
	}

	public static void cancelFor(UUID playerId) {
		ACTIVE.remove(playerId);
	}

	private static final class Upload {
		final BlockPos pos;
		final String fileName;
		final byte[] buffer;
		final int totalChunks;
		int received;

		Upload(BlockPos pos, String fileName, byte[] buffer, int totalChunks) {
			this.pos = pos;
			this.fileName = fileName;
			this.buffer = buffer;
			this.totalChunks = totalChunks;
		}
	}
}
