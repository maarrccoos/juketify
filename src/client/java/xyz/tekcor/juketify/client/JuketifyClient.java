package xyz.tekcor.juketify.client;

import java.util.Optional;

import net.fabricmc.api.ClientModInitializer;
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
import xyz.tekcor.juketify.net.JukeboxCommandPayload;
import xyz.tekcor.juketify.net.JukeboxFileChunkPayload;
import xyz.tekcor.juketify.net.JukeboxFileRequestPayload;
import xyz.tekcor.juketify.net.JukeboxFileStartPayload;
import xyz.tekcor.juketify.net.JukeboxPreparePayload;
import xyz.tekcor.juketify.net.JukeboxRadiusPayload;
import xyz.tekcor.juketify.net.JukeboxReadyPayload;
import xyz.tekcor.juketify.net.JukeboxSearchFailedPayload;
import xyz.tekcor.juketify.net.JukeboxStatePayload;

public class JuketifyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		Juketify.LOGGER.info("Juketify client initialising");

		MusicLibrary.get().rescan();

		registerJukeboxInteraction();
		registerStateReceiver();
		registerFileTransferReceivers();

		ClientPlayerBlockBreakEvents.AFTER.register((level, player, pos, state) -> {
			if (state.is(Blocks.JUKEBOX)) {
				ClientPlayNetworking.send(JukeboxCommandPayload.stop(pos));
			}

			JukeboxPlayback.stopIfAt(pos);
		});
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
		ClientPlayNetworking.registerGlobalReceiver(JukeboxFileStartPayload.TYPE, (payload, context) ->
				context.client().execute(() -> FileTransferClient.begin(
						payload.fileName(), payload.pos(), payload.fileSize(), payload.totalChunks())));

		ClientPlayNetworking.registerGlobalReceiver(JukeboxFileChunkPayload.TYPE, (payload, context) ->
				context.client().execute(() -> FileTransferClient.chunk(
						payload.fileName(), payload.index(), payload.data(),
						pos -> ClientPlayNetworking.send(new JukeboxReadyPayload(pos, payload.fileName(), true)))));
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

		if (client.gui != null) {
			client.gui.setOverlayMessage(
					Component.literal("Juketify: downloading \"" + payload.fileName() + "\"...")
							.withStyle(ChatFormatting.YELLOW),
					false);
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
			return;
		}

		if (FileTransferClient.isDownloading(payload.fileName())) {
			return;
		}

		ClientPlayNetworking.send(new JukeboxFileRequestPayload(payload.pos(), payload.fileName()));
	}
}
