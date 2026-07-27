package xyz.tekcor.juketify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

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
import xyz.tekcor.juketify.net.JukeboxLibraryPayload;
import xyz.tekcor.juketify.net.JukeboxLibraryRequestPayload;
import xyz.tekcor.juketify.net.JukeboxPreparePayload;
import xyz.tekcor.juketify.net.JukeboxProgressPayload;
import xyz.tekcor.juketify.net.JukeboxQueueAddPayload;
import xyz.tekcor.juketify.net.JukeboxQueuePayload;
import xyz.tekcor.juketify.net.JukeboxRadiusPayload;
import xyz.tekcor.juketify.net.JukeboxReadyPayload;
import xyz.tekcor.juketify.net.JukeboxSearchFailedPayload;
import xyz.tekcor.juketify.net.JukeboxSearchOnlinePayload;
import xyz.tekcor.juketify.net.JukeboxSkipPayload;
import xyz.tekcor.juketify.net.JukeboxStatePayload;
import xyz.tekcor.juketify.net.JukeboxUploadChunkPayload;
import xyz.tekcor.juketify.net.JukeboxUploadRequestPayload;
import xyz.tekcor.juketify.net.JukeboxUploadStartPayload;
import xyz.tekcor.juketify.server.FileTransferServer;
import xyz.tekcor.juketify.server.FileUploadServer;
import xyz.tekcor.juketify.server.OggDuration;
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
	private static final long PREPARE_TIMEOUT_MILLIS = 20L * 1000L;
	private static final long TRACK_END_GRACE_MILLIS = 400L;
	private static final int MAX_QUEUE_SIZE = 32;
	private static final int FILE_CACHE_SIZE = 3;

	private record JukeboxKey(ResourceKey<Level> level, BlockPos pos) {
	}

	private static final class Jukebox {
		private String nowPlaying;
		private long startedAtMillis;
		private long durationMillis;
		private final Deque<String> queue = new ArrayDeque<>();
		private final Set<UUID> heard = new HashSet<>();

		private long elapsedMillis() {
			return System.currentTimeMillis() - this.startedAtMillis;
		}

		private boolean finished() {
			return this.durationMillis > 0L
					&& this.elapsedMillis() > this.durationMillis + TRACK_END_GRACE_MILLIS;
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

	private static final Map<JukeboxKey, Jukebox> JUKEBOXES = new ConcurrentHashMap<>();
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

		registerPayloads();
		registerReceivers();

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
					FileUploadServer.cancelFor(id);

					for (Pending pending : PENDING.values()) {
						pending.waitingOn.remove(id);
					}

					for (Jukebox jukebox : JUKEBOXES.values()) {
						jukebox.heard.remove(id);
					}
				}));

		ServerTickEvents.END_SERVER_TICK.register(server -> {
			tickCounter++;

			FileTransferServer.tick(server.getPlayerList().getPlayers());

			if (!PENDING.isEmpty()) {
				checkPending(server);
			}

			if (!JUKEBOXES.isEmpty()) {
				checkFinished(server);
			}

			if (tickCounter % RANGE_CHECK_INTERVAL_TICKS == 0) {
				updateListeners(server.getPlayerList().getPlayers());
			}
		});
	}

	private static void registerPayloads() {
		PayloadTypeRegistry.serverboundPlay().register(JukeboxCommandPayload.TYPE, JukeboxCommandPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxSearchOnlinePayload.TYPE, JukeboxSearchOnlinePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxFileRequestPayload.TYPE, JukeboxFileRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxReadyPayload.TYPE, JukeboxReadyPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxLibraryRequestPayload.TYPE, JukeboxLibraryRequestPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxQueueAddPayload.TYPE, JukeboxQueueAddPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxSkipPayload.TYPE, JukeboxSkipPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxUploadStartPayload.TYPE, JukeboxUploadStartPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(JukeboxUploadChunkPayload.TYPE, JukeboxUploadChunkPayload.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(JukeboxStatePayload.TYPE, JukeboxStatePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxSearchFailedPayload.TYPE, JukeboxSearchFailedPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxFileStartPayload.TYPE, JukeboxFileStartPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxFileChunkPayload.TYPE, JukeboxFileChunkPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxPreparePayload.TYPE, JukeboxPreparePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxRadiusPayload.TYPE, JukeboxRadiusPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxLibraryPayload.TYPE, JukeboxLibraryPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxQueuePayload.TYPE, JukeboxQueuePayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxProgressPayload.TYPE, JukeboxProgressPayload.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(JukeboxUploadRequestPayload.TYPE, JukeboxUploadRequestPayload.CODEC);
	}

	private static void registerReceivers() {
		ServerPlayNetworking.registerGlobalReceiver(JukeboxCommandPayload.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();

			context.server().execute(() -> {
				if (!inRange(sender, payload.pos())) {
					return;
				}

				if (payload.isStop()) {
					stopPlayback(sender.level(), key(sender, payload.pos()));
				} else {
					enqueueTrack(sender.level(), key(sender, payload.pos()), payload.fileName(), sender);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(JukeboxSearchOnlinePayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleOnlineSearch(context.player(), payload)));

		ServerPlayNetworking.registerGlobalReceiver(JukeboxFileRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleFileRequest(context.player(), payload)));

		ServerPlayNetworking.registerGlobalReceiver(JukeboxReadyPayload.TYPE, (payload, context) ->
				context.server().execute(() -> handleReady(context.player(), payload)));

		ServerPlayNetworking.registerGlobalReceiver(JukeboxLibraryRequestPayload.TYPE, (payload, context) ->
				context.server().execute(() -> sendLibrary(context.player())));

		ServerPlayNetworking.registerGlobalReceiver(JukeboxQueueAddPayload.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();

			context.server().execute(() -> {
				if (inRange(sender, payload.pos())) {
					enqueueTrack(sender.level(), key(sender, payload.pos()), payload.fileName(), sender);
				}
			});
		});

		ServerPlayNetworking.registerGlobalReceiver(JukeboxUploadStartPayload.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();

			context.server().execute(() -> FileUploadServer.begin(
					sender, payload.pos(), payload.fileName(), payload.fileSize(), payload.totalChunks()));
		});

		ServerPlayNetworking.registerGlobalReceiver(JukeboxUploadChunkPayload.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();

			context.server().execute(() -> FileUploadServer.chunk(
					sender, musicDir(), payload.fileName(), payload.index(), payload.data(),
					(pos, fileName) -> {
						synchronized (FILE_CACHE) {
							FILE_CACHE.remove(fileName);
						}

						enqueueTrack(sender.level(), key(sender, pos), fileName, null);
					}));
		});

		ServerPlayNetworking.registerGlobalReceiver(JukeboxSkipPayload.TYPE, (payload, context) -> {
			ServerPlayer sender = context.player();

			context.server().execute(() -> {
				if (inRange(sender, payload.pos())) {
					advance(sender.level(), key(sender, payload.pos()));
				}
			});
		});
	}

	private static JukeboxKey key(ServerPlayer player, BlockPos pos) {
		return new JukeboxKey(player.level().dimension(), pos.immutable());
	}

	private static boolean inRange(ServerPlayer player, BlockPos pos) {
		return player.blockPosition().closerThan(pos, HEARING_RANGE);
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

	private static void sendLibrary(ServerPlayer player) {
		CompletableFuture.supplyAsync(() -> {
			Path root = musicDir();
			List<String> names = new ArrayList<>();

			try {
				Files.createDirectories(root);

				try (Stream<Path> walk = Files.walk(root)) {
					walk.filter(Files::isRegularFile)
							.filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".ogg"))
							.sorted()
							.forEach(p -> names.add(p.getFileName().toString()));
				}
			} catch (IOException e) {
				LOGGER.error("Could not list {}", root, e);
			}

			return names;
		}, DISK_EXECUTOR).whenComplete((names, error) -> {
			if (error != null || names == null) {
				return;
			}

			player.level().getServer().execute(() ->
					ServerPlayNetworking.send(player, new JukeboxLibraryPayload(names)));
		});
	}

	private static void handleOnlineSearch(ServerPlayer sender, JukeboxSearchOnlinePayload payload) {
		if (!inRange(sender, payload.pos())) {
			return;
		}

		MinecraftServer server = sender.level().getServer();
		Path musicDir = musicDir();
		JukeboxKey key = key(sender, payload.pos());
		ServerLevel level = sender.level();

		YtDlpService.searchBest(payload.query())
				.thenCompose(result -> YtDlpService.ensureDownloaded(result, musicDir))
				.whenComplete((fileName, error) -> server.execute(() -> {
					if (error != null) {
						LOGGER.error("Juketify online search failed for \"{}\"", payload.query(), error);
						ServerPlayNetworking.send(sender, new JukeboxSearchFailedPayload(
								payload.pos(), "couldn't find \"" + payload.query() + "\""));
						return;
					}

					enqueueTrack(level, key, fileName, null);
				}));
	}

	private static boolean serverHasFile(String fileName) {
		if (!FileUploadServer.isValidName(fileName)) {
			return false;
		}

		Path file = musicDir().resolve(fileName).normalize();
		return file.startsWith(musicDir()) && Files.isRegularFile(file);
	}

	private static void enqueueTrack(ServerLevel level, JukeboxKey key, String fileName, ServerPlayer origin) {
		if (!serverHasFile(fileName)) {
			requestUpload(key, fileName, origin);
			return;
		}

		Jukebox jukebox = JUKEBOXES.get(key);

		if (PENDING.containsKey(key)) {
			if (jukebox != null && jukebox.queue.size() < MAX_QUEUE_SIZE) {
				jukebox.queue.add(fileName);
			}

			return;
		}

		if (jukebox == null || jukebox.nowPlaying == null) {
			beginPrepare(level, key, fileName);
			return;
		}

		if (jukebox.queue.size() >= MAX_QUEUE_SIZE) {
			return;
		}

		jukebox.queue.add(fileName);
		broadcastQueue(level, key, jukebox);
	}

	private static void requestUpload(JukeboxKey key, String fileName, ServerPlayer origin) {
		if (origin == null || !ServerPlayNetworking.canSend(origin, JukeboxUploadRequestPayload.TYPE)) {
			return;
		}

		LOGGER.info("Asking {} to share {}", origin.getGameProfile().name(), fileName);
		ServerPlayNetworking.send(origin, new JukeboxUploadRequestPayload(key.pos(), fileName));
	}

	private static void advance(ServerLevel level, JukeboxKey key) {
		Jukebox jukebox = JUKEBOXES.get(key);

		if (jukebox == null) {
			return;
		}

		String next;

		while ((next = jukebox.queue.poll()) != null && !serverHasFile(next)) {
			LOGGER.warn("Skipping {}, the server no longer has it", next);
		}

		if (next == null) {
			stopPlayback(level, key);
			return;
		}

		beginPrepare(level, key, next);
	}

	private static void beginPrepare(ServerLevel level, JukeboxKey key, String fileName) {
		Jukebox jukebox = JUKEBOXES.computeIfAbsent(key, k -> new Jukebox());
		jukebox.nowPlaying = null;
		jukebox.heard.clear();

		Pending pending = new Pending(fileName);
		PENDING.put(key, pending);

		JukeboxPreparePayload prepare = new JukeboxPreparePayload(key.pos(), fileName);

		for (ServerPlayer player : PlayerLookup.around(level, key.pos(), HEARING_RANGE)) {
			if (!ServerPlayNetworking.canSend(player, JukeboxPreparePayload.TYPE)) {
				continue;
			}

			pending.waitingOn.add(player.getUUID());
			ServerPlayNetworking.send(player, prepare);
		}

		if (pending.waitingOn.isEmpty()) {
			PENDING.remove(key);
			startPlayback(level, key, fileName);
		}
	}

	private static void handleReady(ServerPlayer player, JukeboxReadyPayload payload) {
		JukeboxKey key = key(player, payload.pos());
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
		Jukebox jukebox = JUKEBOXES.get(key);

		if (jukebox == null || jukebox.nowPlaying == null
				|| !jukebox.nowPlaying.equals(payload.fileName()) || !payload.available()) {
			return;
		}

		if (!inRange(player, key.pos())) {
			return;
		}

		jukebox.heard.add(player.getUUID());
		ServerPlayNetworking.send(player,
				new JukeboxStatePayload(key.pos(), jukebox.nowPlaying, jukebox.elapsedMillis()));
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

	private static void checkFinished(MinecraftServer server) {
		for (Map.Entry<JukeboxKey, Jukebox> entry : JUKEBOXES.entrySet()) {
			Jukebox jukebox = entry.getValue();

			if (jukebox.nowPlaying == null || !jukebox.finished()) {
				continue;
			}

			JukeboxKey key = entry.getKey();
			ServerLevel level = server.getLevel(key.level());

			if (level != null) {
				advance(level, key);
			}
		}
	}

	private static void startPlayback(ServerLevel level, JukeboxKey key, String fileName) {
		Jukebox jukebox = JUKEBOXES.computeIfAbsent(key, k -> new Jukebox());

		jukebox.nowPlaying = fileName;
		jukebox.startedAtMillis = System.currentTimeMillis();
		jukebox.durationMillis = durationOf(fileName);
		jukebox.heard.clear();

		JukeboxStatePayload state = new JukeboxStatePayload(key.pos(), fileName, 0L);

		for (ServerPlayer player : PlayerLookup.around(level, key.pos(), HEARING_RANGE)) {
			jukebox.heard.add(player.getUUID());
			ServerPlayNetworking.send(player, state);
		}

		broadcastQueue(level, key, jukebox);
	}

	private static long durationOf(String fileName) {
		Path file = musicDir().resolve(fileName).normalize();

		if (!file.startsWith(musicDir()) || !Files.isRegularFile(file)) {
			return 0L;
		}

		return OggDuration.millis(file);
	}

	private static void broadcastQueue(ServerLevel level, JukeboxKey key, Jukebox jukebox) {
		JukeboxQueuePayload payload = new JukeboxQueuePayload(
				key.pos(),
				jukebox.nowPlaying == null ? "" : jukebox.nowPlaying,
				List.copyOf(jukebox.queue));

		for (ServerPlayer player : PlayerLookup.around(level, key.pos(), HEARING_RANGE)) {
			ServerPlayNetworking.send(player, payload);
		}
	}

	private static void stopPlayback(ServerLevel level, JukeboxKey key) {
		Jukebox jukebox = JUKEBOXES.remove(key);
		PENDING.remove(key);

		JukeboxStatePayload state = JukeboxStatePayload.stop(key.pos());
		JukeboxQueuePayload queue = new JukeboxQueuePayload(key.pos(), "", List.of());

		for (ServerPlayer player : PlayerLookup.around(level, key.pos(), HEARING_RANGE)) {
			ServerPlayNetworking.send(player, state);
			ServerPlayNetworking.send(player, queue);
		}

		if (jukebox != null) {
			jukebox.queue.clear();
		}
	}

	private static void sendCurrentState(ServerPlayer player) {
		ResourceKey<Level> dimension = player.level().dimension();

		for (Map.Entry<JukeboxKey, Jukebox> entry : JUKEBOXES.entrySet()) {
			JukeboxKey key = entry.getKey();
			Jukebox jukebox = entry.getValue();

			if (!key.level().equals(dimension) || jukebox.nowPlaying == null
					|| !inRange(player, key.pos())) {
				continue;
			}

			if (!jukebox.heard.add(player.getUUID())) {
				continue;
			}

			ServerPlayNetworking.send(player,
					new JukeboxStatePayload(key.pos(), jukebox.nowPlaying, jukebox.elapsedMillis()));
			ServerPlayNetworking.send(player, new JukeboxQueuePayload(
					key.pos(), jukebox.nowPlaying, List.copyOf(jukebox.queue)));
		}
	}

	private static void updateListeners(Iterable<ServerPlayer> players) {
		if (JUKEBOXES.isEmpty()) {
			return;
		}

		JUKEBOXES.entrySet().removeIf(e -> e.getValue().nowPlaying != null
				&& e.getValue().durationMillis <= 0L
				&& e.getValue().elapsedMillis() > MAX_TRACK_LIFETIME_MILLIS);

		Map<ResourceKey<Level>, List<ServerPlayer>> byDimension = new HashMap<>();

		for (ServerPlayer player : players) {
			byDimension.computeIfAbsent(player.level().dimension(), k -> new ArrayList<>()).add(player);
		}

		for (Map.Entry<JukeboxKey, Jukebox> entry : JUKEBOXES.entrySet()) {
			JukeboxKey key = entry.getKey();
			Jukebox jukebox = entry.getValue();

			if (jukebox.nowPlaying == null) {
				continue;
			}

			List<ServerPlayer> candidates = byDimension.get(key.level());

			if (candidates == null) {
				continue;
			}

			Set<UUID> inRange = new HashSet<>();

			for (ServerPlayer player : candidates) {
				if (!inRange(player, key.pos())) {
					continue;
				}

				inRange.add(player.getUUID());

				if (jukebox.heard.add(player.getUUID())) {
					ServerPlayNetworking.send(player,
							new JukeboxStatePayload(key.pos(), jukebox.nowPlaying, jukebox.elapsedMillis()));
					ServerPlayNetworking.send(player, new JukeboxQueuePayload(
							key.pos(), jukebox.nowPlaying, List.copyOf(jukebox.queue)));
				}
			}

			jukebox.heard.retainAll(inRange);
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
