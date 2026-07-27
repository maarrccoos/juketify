package xyz.tekcor.juketify.client;

import java.util.Optional;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.client.player.ClientPlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import xyz.tekcor.juketify.Juketify;
import xyz.tekcor.juketify.client.audio.ClientRadius;
import xyz.tekcor.juketify.client.audio.JukeboxPlayback;
import xyz.tekcor.juketify.client.gui.JukeboxScreen;
import xyz.tekcor.juketify.client.library.MusicLibrary;
import xyz.tekcor.juketify.client.library.Track;
import xyz.tekcor.juketify.client.net.FileTransferClient;
import xyz.tekcor.juketify.client.net.FileUploadClient;
import xyz.tekcor.juketify.net.JukeboxCommandPayload;
import xyz.tekcor.juketify.net.JukeboxFileChunkPayload;
import xyz.tekcor.juketify.net.JukeboxFileRequestPayload;
import xyz.tekcor.juketify.net.JukeboxFileStartPayload;
import xyz.tekcor.juketify.net.JukeboxLibraryPayload;
import xyz.tekcor.juketify.net.JukeboxPreparePayload;
import xyz.tekcor.juketify.net.JukeboxProgressPayload;
import xyz.tekcor.juketify.net.JukeboxQueuePayload;
import xyz.tekcor.juketify.net.JukeboxRadiusPayload;
import xyz.tekcor.juketify.net.JukeboxReadyPayload;
import xyz.tekcor.juketify.net.JukeboxSearchFailedPayload;
import xyz.tekcor.juketify.net.JukeboxStatePayload;
import xyz.tekcor.juketify.net.JukeboxUploadRequestPayload;

public class JuketifyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Juketify.LOGGER.info("Juketify client initialising");

		MusicLibrary.get().rescan();

		registerJukeboxInteraction();
		registerStateReceiver();
		registerFileTransferReceivers();

		ClientTickEvents.END_CLIENT_TICK.register(client -> FileUploadClient.tick());

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> resetClientState());
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> resetClientState());

		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			if (state.is(Blocks.JUKEBOX)) {
				ClientPlayNetworking.send(JukeboxCommandPayload.stop(pos));
			}

			JukeboxPlayback.stopIfAt(pos);
		});
	}

	private static void resetClientState() {
		JukeboxPlayback.stop();
		FileTransferClient.reset();
		FileUploadClient.reset();
		ClientJukebox.reset();
	}

	private static void registerJukeboxInteraction() {
		UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
			if (!level.isClientSide()) {
				return InteractionResult.PASS;
			}

			if (!level.getBlockState(hitResult.getBlockPos()).is(Blocks.JUKEBOX)) {
				return InteractionResult.PASS;
			}

			if (!player.getItemInHand(hand).isEmpty()) {
				return InteractionResult.PASS;
			}

			Minecraft.getInstance().setScreen(new JukeboxScreen(hitResult.getBlockPos()));
			return InteractionResult.SUCCESS;
		});
	}

	private static void registerStateReceiver() {
		ClientPlayNetworking.registerGlobalReceiver(JukeboxStatePayload.TYPE, (payload, context) ->
				context.client().execute(() -> handleState(payload, context.client())));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxPreparePayload.TYPE, (payload, context) ->
				context.client().execute(() -> handlePrepare(payload, context.client())));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxRadiusPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					ClientRadius.set(payload.radius());
					JukeboxPlayback.refreshRange();
				}));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxLibraryPayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientJukebox.setLibrary(payload.fileNames())));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxQueuePayload.TYPE, (payload, context) ->
				context.client().execute(() -> ClientJukebox.setQueue(payload.nowPlaying(), payload.upcoming())));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxProgressPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					ClientJukebox.setProgress(payload.fileName(), payload.percent());

					if (context.client().gui != null && payload.percent() < 100) {
						context.client().gui.setOverlayMessage(
								Component.literal("Juketify: downloading "
												+ ClientJukebox.label(payload.fileName())
												+ " (" + payload.percent() + "%)")
										.withStyle(ChatFormatting.YELLOW),
								false);
					}
				}));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxSearchFailedPayload.TYPE, (payload, context) ->
				context.client().execute(() -> {
					if (context.client().gui != null) {
						context.client().gui.setOverlayMessage(
								Component.literal("Juketify: " + payload.message()).withStyle(ChatFormatting.RED),
								false);
					}
				}));
	}

	private static void registerFileTransferReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(JukeboxUploadRequestPayload.TYPE, (payload, context) ->
				context.client().execute(() -> handleUploadRequest(payload, context.client())));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxFileStartPayload.TYPE, (payload, context) ->
				context.client().execute(() -> FileTransferClient.begin(
						payload.fileName(), payload.pos(), payload.fileSize(), payload.totalChunks())));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxFileChunkPayload.TYPE, (payload, context) ->
				context.client().execute(() -> FileTransferClient.chunk(
						payload.fileName(), payload.index(), payload.data(),
						pos -> ClientPlayNetworking.send(new JukeboxReadyPayload(pos, payload.fileName(), true)))));
	}

	private static void handleUploadRequest(JukeboxUploadRequestPayload payload, Minecraft client) {
		Optional<Track> track = MusicLibrary.get().byFileName(payload.fileName());

		if (track.isEmpty()) {
			return;
		}

		if (client.gui != null) {
			client.gui.setOverlayMessage(
					Component.literal("Juketify: sharing " + track.get().label() + " with the server")
							.withStyle(ChatFormatting.YELLOW),
					false);
		}

		FileUploadClient.offer(payload.pos(), payload.fileName(), track.get().path());
	}

	private static void handlePrepare(JukeboxPreparePayload payload, Minecraft client) {
		JukeboxPlayback.stopIfAt(payload.pos());

		if (MusicLibrary.get().byFileName(payload.fileName()).isPresent()) {
			ClientPlayNetworking.send(new JukeboxReadyPayload(payload.pos(), payload.fileName(), true));
			return;
		}

		if (FileTransferClient.isDownloading(payload.fileName())) {
			return;
		}

		ClientPlayNetworking.send(new JukeboxFileRequestPayload(payload.pos(), payload.fileName()));
	}

	private static void handleState(JukeboxStatePayload payload, Minecraft client) {
		if (payload.isStop()) {
			JukeboxPlayback.stopIfAt(payload.pos());
			return;
		}

		Optional<Track> track = MusicLibrary.get().byFileName(payload.fileName());

		if (track.isPresent()) {
			JukeboxPlayback.play(track.get(), payload.pos(), payload.offsetMillis());
			announce(client, track.get().label());
			return;
		}

		if (FileTransferClient.isDownloading(payload.fileName())) {
			return;
		}

		ClientPlayNetworking.send(new JukeboxFileRequestPayload(payload.pos(), payload.fileName()));
	}

	private static void announce(Minecraft client, String label) {
		if (client.gui == null) {
			return;
		}

		client.gui.setOverlayMessage(
				Component.literal("Now playing: " + label).withStyle(ChatFormatting.GREEN), false);
	}
}
