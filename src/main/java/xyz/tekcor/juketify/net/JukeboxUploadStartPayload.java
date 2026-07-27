package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxUploadStartPayload(BlockPos pos, String fileName, int fileSize, int totalChunks)
		implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxUploadStartPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_upload_start"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxUploadStartPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxUploadStartPayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxUploadStartPayload::fileName,
					ByteBufCodecs.VAR_INT, JukeboxUploadStartPayload::fileSize,
					ByteBufCodecs.VAR_INT, JukeboxUploadStartPayload::totalChunks,
					JukeboxUploadStartPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
