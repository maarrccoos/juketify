package xyz.tekcor.juketify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.mojang.brigadier.arguments.IntegerArgumentType;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.tekcor.juketify.net.JukeboxCommandPayload;
import xyz.tekcor.juketify.net.JukeboxFileChunkPayload;
import xyz.tekcor.juketify.net.JukeboxFileRequestPayload;
import xyz.tekcor.juketify.net.JukeboxFileStartPayload;
import xyz.tekcor.juketify.net.JukeboxRadiusPayload;
import xyz.tekcor.juketify.net.JukeboxSearchFailedPayload;
import xyz.tekcor.juketify.net.JukeboxSearchOnlinePayload;
import xyz.tekcor.juketify.net.JukeboxStatePayload;
import xyz.tekcor.juketify.server.YtDlpService;

public class Juketify implements ModInitializer {
	public static final String MOD_ID = "juketify";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	private static volatile double HEARING_RANGE = JuketifyConfig.DEFAULT_RADIUS;

	private static final Executor DISK_EXECUTOR = Executors.newFixedThreadPool(2, r -> {
		Thread thread = new Thread(r, "juketify-disk");
		thread.setDaemon(true);
		return thread;
	});

	private static final int RANGE_CHECK_INTERVAL_TICKS = 20;
	private static final long MAX_TRACK_LIFETIME_MILLIS = 20L * 60L * 1000L;

	private record JukeboxKey(ResourceKey<Level> level, BlockPos pos) {
	}

	private static final class Playing {
		private final String fileName;
		private final long startedAtMillis;
		private final Set<UUID> heard = new HashSet<>();

		private Playing(String fileName) {
			this.fileName = fileName;
			this.startedAtMillis = System.currentTimeMillis();
		}

		private long elapsedMillis() {
			return System.currentTimeMillis() - this.startedAtMillis;
		}
	}

	private static final Map<JukeboxKey, Playing> ACTIVE = new ConcurrentHashMap<>();

	private static int tickCounter;

	@Override
	public void onInitialize() {
		LOGGER.info("Juketify initialising");

		PayloadTypeRegistry.serverboundPlay().register(JukeboxCommandPayload.TYPE, JukeboxCommandPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxSearchOnlinePayload.TYPE, JukeboxSearchOnlinePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxFileRequestPayload.TYPE, JukeboxFileRequestPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxStatePayload.TYPE, JukeboxStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxSearchFailedPayload.TYPE, JukeboxSearchFailedPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxFileStartPayload.TYPE, JukeboxFileStartPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxFileChunkPayload.TYPE, JukeboxFileChunkPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(JukeboxCommandPayload.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();

			context.server().execute(() ->
					relayState(sender, payload.pos(), payload.isStop() ? null : payload.fileName()));
		});

		ServerPlayNetworking.registerGlobalReceiver(JukeboxSearchOnlinePayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleOnlineSearch(context.player(), payload)));

		ServerPlayNetworking.registerGlobalReceiver(JukeboxFileRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleFileRequest(context.player(), payload)));

		JuketifyConfig.load();
		HEARING_RANGE = JuketifyConfig.radius();

		PayloadTypeRegistry.clientboundPlay().register(JukeboxRadiusPayload.TYPE, JukeboxRadiusPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
				dispatcher.register(Commands.literal("juketify")
						.then(Commands.literal("radius")
								.executes(context -> {
									context.getSource().sendSuccess(() -> Component.literal(
											"Juketify hearing radius is " + JuketifyConfig.radius() + " blocks"), false);
									return 1;
								})
								.then(Commands.argument("blocks", IntegerArgumentType.integer(
												JuketifyConfig.MIN_RADIUS, JuketifyConfig.MAX_RADIUS))
										.requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
										.executes(context -> setRadius(
												context.getSource(),
												IntegerArgumentType.getInteger(context, "blocks")))))));

		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				server.execute(() -> {
					ServerPlayNetworking.send(handler.getPlayer(), new JukeboxRadiusPayload(JuketifyConfig.radius()));
					sendCurrentState(handler.getPlayer());
				}));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickCounter++;

			if (tickCounter % RANGE_CHECK_INTERVAL_TICKS == 0) {
				updateListeners(server.getPlayerList().getPlayers());
			}
		});
	}

	private static int setRadius(CommandSourceStack source, int blocks) {
		JuketifyConfig.set(blocks);
		HEARING_RANGE = JuketifyConfig.radius();

		JukeboxRadiusPayload payload = new JukeboxRadiusPayload(JuketifyConfig.radius());

		for (ServerPlayer player : source.getServer().getPlayerList().getPlayers()) {
			ServerPlayNetworking.send(player, payload);
		}

		source.sendSuccess(() -> Component.literal(
				"Juketify hearing radius set to " + JuketifyConfig.radius() + " blocks"), true);

		return 1;
	}

	private static void sendCurrentState(ServerPlayer player) {
		ResourceKey<Level> dimension = player.level().dimension();

		for (Map.Entry<JukeboxKey, Playing> entry : ACTIVE.entrySet()) {
			JukeboxKey key = entry.getKey();

			if (!key.level().equals(dimension) || !player.blockPosition().closerThan(key.pos(), HEARING_RANGE)) {
				continue;
			}

			Playing playing = entry.getValue();
			playing.heard.add(player.getUUID());
			ServerPlayNetworking.send(player,
					new JukeboxStatePayload(key.pos(), playing.fileName, playing.elapsedMillis()));
		}
	}

	private static void updateListeners(Iterable<ServerPlayer> players) {
		if (ACTIVE.isEmpty()) {
			return;
		}

		ACTIVE.entrySet().removeIf(e -> e.getValue().elapsedMillis() > MAX_TRACK_LIFETIME_MILLIS);

		Map<ResourceKey<Level>, List<ServerPlayer>> byDimension = new HashMap<>();

		for (ServerPlayer player : players) {
			byDimension.computeIfAbsent(player.level().dimension(), k -> new ArrayList<>()).add(player);
		}

		for (Map.Entry<JukeboxKey, Playing> entry : ACTIVE.entrySet()) {
			JukeboxKey key = entry.getKey();
			Playing playing = entry.getValue();
			List<ServerPlayer> candidates = byDimension.get(key.level());

			if (candidates == null) {
				continue;
			}

			Set<UUID> inRange = new HashSet<>();

			for (ServerPlayer player : candidates) {
				if (!player.blockPosition().closerThan(key.pos(), HEARING_RANGE)) {
					continue;
				}

				inRange.add(player.getUUID());

				if (playing.heard.add(player.getUUID())) {
					ServerPlayNetworking.send(player,
							new JukeboxStatePayload(key.pos(), playing.fileName, playing.elapsedMillis()));
				}
			}

			playing.heard.retainAll(inRange);
		}
	}

	private static Path musicDir() {
		return FabricLoader.getInstance().getGameDir().resolve("music");
	}

	private static void handleOnlineSearch(ServerPlayer sender, JukeboxSearchOnlinePayload payload) {
		if (!sender.blockPosition().closerThan(payload.pos(), HEARING_RANGE)) {
			return;
		}

		MinecraftServer server = sender.level().getServer();
		Path musicDir = musicDir();

		YtDlpService.searchBest(payload.query())
				.thenCompose(result -> YtDlpService.ensureDownloaded(result.videoId(), musicDir)
						.thenApply(fileName -> result))
				.whenComplete((result, error) -> server.execute(() -> {
					if (error != null) {
						LOGGER.error("Juketify online search failed for \"{}\"", payload.query(), error);
						ServerPlayNetworking.send(sender, new JukeboxSearchFailedPayload(
								payload.pos(), "couldn't find or download \"" + payload.query() + "\""));
						return;
					}

					relayState(sender, payload.pos(), result.videoId() + ".ogg");
				}));
	}

	private static void relayState(ServerPlayer sender, BlockPos pos, String fileName) {
		ServerLevel level = sender.level();

		if (!sender.blockPosition().closerThan(pos, HEARING_RANGE)) {
			return;
		}

		JukeboxKey key = new JukeboxKey(level.dimension(), pos.immutable());
		JukeboxStatePayload state;

		if (fileName == null) {
			ACTIVE.remove(key);
			state = JukeboxStatePayload.stop(pos);
		} else {
			Playing playing = new Playing(fileName);
			ACTIVE.put(key, playing);
			state = new JukeboxStatePayload(pos, fileName, 0L);
		}

		Playing playing = ACTIVE.get(key);

		for (ServerPlayer player : PlayerLookup.around(level, pos, HEARING_RANGE)) {
			if (playing != null) {
				playing.heard.add(player.getUUID());
			}

			ServerPlayNetworking.send(player, state);
		}
	}

	private static void handleFileRequest(ServerPlayer requester, JukeboxFileRequestPayload payload) {
		String fileName = payload.fileName();

		if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
			return;
		}

		Path musicDir = musicDir();
		Path file = musicDir.resolve(fileName).normalize();

		if (!file.startsWith(musicDir) || !Files.isRegularFile(file)) {
			return;
		}

		MinecraftServer server = requester.level().getServer();

		CompletableFuture.supplyAsync(() -> {
			try {
				return Files.readAllBytes(file);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}, DISK_EXECUTOR).whenComplete((bytes, error) -> server.execute(() -> {
			if (error != null) {
				LOGGER.error("Failed to read {} for a file transfer", file, error);
				return;
			}

			sendFile(requester, payload.pos(), fileName, bytes);
		}));
	}

	private static void sendFile(ServerPlayer requester, BlockPos pos, String fileName, byte[] bytes) {
		int chunkSize = JukeboxFileChunkPayload.CHUNK_SIZE;
		int totalChunks = Math.max(1, (bytes.length + chunkSize - 1) / chunkSize);

		ServerPlayNetworking.send(requester, new JukeboxFileStartPayload(fileName, pos, bytes.length, totalChunks));

		for (int i = 0; i < totalChunks; i++) {
			int offset = i * chunkSize;
			int length = Math.min(chunkSize, bytes.length - offset);
			byte[] chunk = Arrays.copyOfRange(bytes, offset, offset + length);
			ServerPlayNetworking.send(requester, new JukeboxFileChunkPayload(fileName, i, chunk));
		}
	}
}
