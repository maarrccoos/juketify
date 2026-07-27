package xyz.tekcor.juketify.server;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import xyz.tekcor.juketify.Juketify;
import xyz.tekcor.juketify.net.JukeboxFileChunkPayload;
import xyz.tekcor.juketify.net.JukeboxFileStartPayload;

public final class FileTransferServer {

	private static final int MAX_IN_FLIGHT_CHUNKS = 2;
	private static final int GLOBAL_CHUNKS_PER_TICK = 4;
	private static final int STALL_LIMIT_TICKS = 100;

	private static final Map<UUID, Deque<Transfer>> QUEUES = new HashMap<>();

	private FileTransferServer() {
	}

	public static void enqueue(ServerPlayer player, BlockPos pos, String fileName, byte[] data) {
		int chunkSize = JukeboxFileChunkPayload.CHUNK_SIZE;
		int totalChunks = Math.max(1, (data.length + chunkSize - 1) / chunkSize);

		Deque<Transfer> queue = QUEUES.computeIfAbsent(player.getUUID(), id -> new ArrayDeque<>());

		for (Transfer queued : queue) {
			if (queued.fileName.equals(fileName)) {
				return;
			}
		}

		queue.add(new Transfer(pos, fileName, data, totalChunks));
	}

	public static void cancelFor(UUID playerId) {
		QUEUES.remove(playerId);
	}

	public static void tick(Iterable<ServerPlayer> players) {
		if (QUEUES.isEmpty()) {
			return;
		}

		List<ServerPlayer> online = new ArrayList<>();

		for (ServerPlayer player : players) {
			if (QUEUES.containsKey(player.getUUID())) {
				online.add(player);
			}
		}

		QUEUES.keySet().removeIf(id -> online.stream().noneMatch(p -> p.getUUID().equals(id)));

		int budget = GLOBAL_CHUNKS_PER_TICK;

		for (ServerPlayer player : online) {
			if (budget <= 0) {
				return;
			}

			Deque<Transfer> queue = QUEUES.get(player.getUUID());

			if (queue == null || queue.isEmpty()) {
				continue;
			}

			Transfer transfer = queue.peek();

			if (!transfer.started) {
				transfer.started = true;
				player.connection.send(new ClientboundCustomPayloadPacket(new JukeboxFileStartPayload(
						transfer.fileName, transfer.pos, transfer.data.length, transfer.totalChunks)));
			}

			int sent = 0;

			while (budget > 0 && transfer.inFlight.get() < MAX_IN_FLIGHT_CHUNKS
					&& transfer.nextChunk < transfer.totalChunks) {
				sendChunk(player, transfer);
				budget--;
				sent++;
			}

			if (sent > 0) {
				transfer.stalledTicks = 0;
			} else if (transfer.inFlight.get() > 0) {
				transfer.stalledTicks++;

				if (transfer.stalledTicks > STALL_LIMIT_TICKS) {
					Juketify.LOGGER.warn("Transfer of {} stalled, forcing it along", transfer.fileName);
					transfer.inFlight.set(0);
					transfer.stalledTicks = 0;
				}
			}

			if (transfer.nextChunk >= transfer.totalChunks && transfer.inFlight.get() == 0) {
				queue.poll();

				if (queue.isEmpty()) {
					QUEUES.remove(player.getUUID());
				}
			}
		}
	}

	private static void sendChunk(ServerPlayer player, Transfer transfer) {
		int chunkSize = JukeboxFileChunkPayload.CHUNK_SIZE;
		int index = transfer.nextChunk;
		int offset = index * chunkSize;
		int length = Math.min(chunkSize, transfer.data.length - offset);

		byte[] chunk = new byte[length];
		System.arraycopy(transfer.data, offset, chunk, 0, length);

		transfer.nextChunk++;
		transfer.inFlight.incrementAndGet();

		player.connection.send(
				new ClientboundCustomPayloadPacket(new JukeboxFileChunkPayload(transfer.fileName, index, chunk)),
				future -> {
					transfer.inFlight.decrementAndGet();

					if (!future.isSuccess()) {
						Juketify.LOGGER.warn("Chunk {} of {} failed to send", index, transfer.fileName, future.cause());
					}
				});
	}

	public static final class Transfer {
		final BlockPos pos;
		final String fileName;
		final byte[] data;
		final int totalChunks;
		final AtomicInteger inFlight = new AtomicInteger();
		boolean started;
		int nextChunk;
		int stalledTicks;

		Transfer(BlockPos pos, String fileName, byte[] data, int totalChunks) {
			this.pos = pos;
			this.fileName = fileName;
			this.data = data;
			this.totalChunks = totalChunks;
		}

		public BlockPos pos() {
			return this.pos;
		}

		public String fileName() {
			return this.fileName;
		}
	}
}
