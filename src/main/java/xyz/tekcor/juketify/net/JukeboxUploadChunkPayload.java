package xyz.tekcor.juketify.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxUploadChunkPayload(String fileName, int index, byte[] data) implements CustomPacketPayload {

	public static final int CHUNK_SIZE = 16384;

	public static final CustomPacketPayload.Type<JukeboxUploadChunkPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_upload_chunk"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxUploadChunkPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, JukeboxUploadChunkPayload::fileName,
					ByteBufCodecs.VAR_INT, JukeboxUploadChunkPayload::index,
					ByteBufCodecs.byteArray(CHUNK_SIZE), JukeboxUploadChunkPayload::data,
					JukeboxUploadChunkPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
