package xyz.tekcor.juketify.net;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxQueuePayload(BlockPos pos, String nowPlaying, List<String> upcoming)
		implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxQueuePayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_queue"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxQueuePayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxQueuePayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxQueuePayload::nowPlaying,
					ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), JukeboxQueuePayload::upcoming,
					JukeboxQueuePayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
