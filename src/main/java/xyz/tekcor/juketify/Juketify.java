package xyz.tekcor.juketify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
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
import xyz.tekcor.juketify.net.JukeboxPreparePayload;
import xyz.tekcor.juketify.net.JukeboxRadiusPayload;
import xyz.tekcor.juketify.net.JukeboxReadyPayload;
import xyz.tekcor.juketify.net.JukeboxSearchFailedPayload;
import xyz.tekcor.juketify.net.JukeboxSearchOnlinePayload;
import xyz.tekcor.juketify.net.JukeboxStatePayload;
import xyz.tekcor.juketify.server.FileTransferServer;
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
	private static final long PREPARE_TIMEOUT_MILLIS = 120L * 1000L;
	private static final int FILE_CACHE_SIZE = 3;

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

	private static final class Pending {
		private final String fileName;
		private final long deadlineMillis;
		private final Set<UUID> waitingOn = new HashSet<>();

		private Pending(String fileName) {
			this.fileName = fileName;
			this.deadlineMillis = System.currentTimeMillis() + PREPARE_TIMEOUT_MILLIS;
		}

		private boolean expired() {
			return System.currentTimeMillis() > this.deadlineMillis;
		}
	}

	private static final Map<JukeboxKey, Playing> ACTIVE = new ConcurrentHashMap<>();
	private static final Map<JukeboxKey, Pending> PENDING = new ConcurrentHashMap<>();

	private static final Map<String, byte[]> FILE_CACHE =
			new LinkedHashMap<>(FILE_CACHE_SIZE + 1, 0.75F, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
					return size() > FILE_CACHE_SIZE;
				}
			};

	private static int tickCounter;

	@Override
	public void onInitialize() {
		LOGGER.info("Juketify initialising");

		PayloadTypeRegistry.serverboundPlay().register(JukeboxCommandPayload.TYPE, JukeboxCommandPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxSearchOnlinePayload.TYPE, JukeboxSearchOnlinePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxFileRequestPayload.TYPE, JukeboxFileRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxReadyPayload.TYPE, JukeboxReadyPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxStatePayload.TYPE, JukeboxStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxSearchFailedPayload.TYPE, JukeboxSearchFailedPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxFileStartPayload.TYPE, JukeboxFileStartPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxFileChunkPayload.TYPE, JukeboxFileChunkPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxPreparePayload.TYPE, JukeboxPreparePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxRadiusPayload.TYPE, JukeboxRadiusPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(JukeboxCommandPayload.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();

			context.server().execute(() -> {
				if (payload.isStop()) {
					stopPlayback(sender, payload.pos());
				} else {
					beginPrepare(sender, payload.pos(), payload.fileName());
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(JukeboxSearchOnlinePayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleOnlineSearch(context.player(), payload)));

		ServerPlayNetworking.registerGlobalReceiver(JukeboxFileRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleFileRequest(context.player(), payload)));

		ServerPlayNetworking.registerGlobalReceiver(JukeboxReadyPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleReady(context.player(), payload)));

		JuketifyConfig.load();
		HEARING_RANGE = JuketifyConfig.radius();

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

		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				server.execute(() -> {
					UUID id = handler.getPlayer().getUUID();
					FileTransferServer.cancelFor(id);

					for (Pending pending : PENDING.values()) {
						pending.waitingOn.remove(id);
					}
				}));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickCounter++;

			FileTransferServer.tick(server.getPlayerList().getPlayers());

			if (!PENDING.isEmpty()) {
				checkPending(server);
			}

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
				.thenCompose(result -> YtDlpService.ensureDownloaded(result, musicDir))
				.whenComplete((fileName, error) -> server.execute(() -> {
					if (error != null) {
						LOGGER.error("Juketify online search failed for \"{}\"", payload.query(), error);
						ServerPlayNetworking.send(sender, new JukeboxSearchFailedPayload(
								payload.pos(), "couldn't find \"" + payload.query() + "\""));
						return;
					}

					beginPrepare(sender, payload.pos(), fileName);
				}));
	}

	private static void beginPrepare(ServerPlayer sender, BlockPos pos, String fileName) {
		ServerLevel level = sender.level();

		if (!sender.blockPosition().closerThan(pos, HEARING_RANGE)) {
			return;
		}

		JukeboxKey key = new JukeboxKey(level.dimension(), pos.immutable());

		ACTIVE.remove(key);

		Pending pending = new Pending(fileName);
		PENDING.put(key, pending);

		JukeboxPreparePayload prepare = new JukeboxPreparePayload(pos, fileName);

		for (ServerPlayer player : PlayerLookup.around(level, pos, HEARING_RANGE)) {
			pending.waitingOn.add(player.getUUID());
			ServerPlayNetworking.send(player, prepare);
		}

		if (pending.waitingOn.isEmpty()) {
			PENDING.remove(key);
		}
	}

	private static void handleReady(ServerPlayer player, JukeboxReadyPayload payload) {
		JukeboxKey key = new JukeboxKey(player.level().dimension(), payload.pos().immutable());
		Pending pending = PENDING.get(key);

		if (pending == null) {
			catchUp(player, key, payload);
			return;
		}

		if (!pending.fileName.equals(payload.fileName())) {
			return;
		}

		pending.waitingOn.remove(player.getUUID());

		if (pending.waitingOn.isEmpty()) {
			PENDING.remove(key);
			startPlayback(player.level(), key, pending.fileName);
		}
	}

	private static void catchUp(ServerPlayer player, JukeboxKey key, JukeboxReadyPayload payload) {
		Playing playing = ACTIVE.get(key);

		if (playing == null || !playing.fileName.equals(payload.fileName()) || !payload.available()) {
			return;
		}

		if (!player.blockPosition().closerThan(key.pos(), HEARING_RANGE)) {
			return;
		}

		playing.heard.add(player.getUUID());
		ServerPlayNetworking.send(player,
				new JukeboxStatePayload(key.pos(), playing.fileName, playing.elapsedMillis()));
	}

	private static void checkPending(MinecraftServer server) {
		Iterator<Map.Entry<JukeboxKey, Pending>> iterator = PENDING.entrySet().iterator();

		while (iterator.hasNext()) {
			Map.Entry<JukeboxKey, Pending> entry = iterator.next();

			if (!entry.getValue().expired()) {
				continue;
			}

			JukeboxKey key = entry.getKey();
			iterator.remove();

			ServerLevel level = server.getLevel(key.level());

			if (level != null) {
				LOGGER.warn("Starting {} without {} player(s) still preparing",
						entry.getValue().fileName, entry.getValue().waitingOn.size());
				startPlayback(level, key, entry.getValue().fileName);
			}
		}
	}

	private static void startPlayback(ServerLevel level, JukeboxKey key, String fileName) {
		Playing playing = new Playing(fileName);
		ACTIVE.put(key, playing);

		JukeboxStatePayload state = new JukeboxStatePayload(key.pos(), fileName, 0L);

		for (ServerPlayer player : PlayerLookup.around(level, key.pos(), HEARING_RANGE)) {
			playing.heard.add(player.getUUID());
			ServerPlayNetworking.send(player, state);
		}
	}

	private static void stopPlayback(ServerPlayer sender, BlockPos pos) {
		ServerLevel level = sender.level();
		JukeboxKey key = new JukeboxKey(level.dimension(), pos.immutable());

		ACTIVE.remove(key);
		PENDING.remove(key);

		JukeboxStatePayload state = JukeboxStatePayload.stop(pos);

		for (ServerPlayer player : PlayerLookup.around(level, pos, HEARING_RANGE)) {
			ServerPlayNetworking.send(player, state);
		}
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

	private static void handleFileRequest(ServerPlayer requester, JukeboxFileRequestPayload payload) {
		String fileName = payload.fileName();

		if (fileName.isEmpty() || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
			return;
		}

		Path musicDir = musicDir();
		Path file = musicDir.resolve(fileName).normalize();

		if (!file.startsWith(musicDir) || !Files.isRegularFile(file)) {
			markUnavailable(requester, payload.pos(), fileName);
			return;
		}

		byte[] cached;

		synchronized (FILE_CACHE) {
			cached = FILE_CACHE.get(fileName);
		}

		if (cached != null) {
			FileTransferServer.enqueue(requester, payload.pos(), fileName, cached);
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
				markUnavailable(requester, payload.pos(), fileName);
				return;
			}

			synchronized (FILE_CACHE) {
				FILE_CACHE.put(fileName, bytes);
			}

			FileTransferServer.enqueue(requester, payload.pos(), fileName, bytes);
		}));
	}

	private static void markUnavailable(ServerPlayer player, BlockPos pos, String fileName) {
		LOGGER.warn("{} asked for {} but the server does not have it", player.getGameProfile().name(), fileName);

		ServerPlayNetworking.send(player, new JukeboxSearchFailedPayload(pos, "the server doesn't have that track"));
		handleReady(player, new JukeboxReadyPayload(pos, fileName, false));
	}
}
