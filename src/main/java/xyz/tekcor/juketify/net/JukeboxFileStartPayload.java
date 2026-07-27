package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxFileStartPayload(String fileName, BlockPos pos, int fileSize, int totalChunks)
		implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxFileStartPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_file_start"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxFileStartPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, JukeboxFileStartPayload::fileName,
					BlockPos.STREAM_CODEC, JukeboxFileStartPayload::pos,
					ByteBufCodecs.VAR_INT, JukeboxFileStartPayload::fileSize,
					ByteBufCodecs.VAR_INT, JukeboxFileStartPayload::totalChunks,
					JukeboxFileStartPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
