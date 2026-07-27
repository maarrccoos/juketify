package xyz.tekcor.juketify.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxFileChunkPayload(String fileName, int index, byte[] data) implements CustomPacketPayload {

	public static final int CHUNK_SIZE = 16384;

	public static final CustomPacketPayload.Type<JukeboxFileChunkPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_file_chunk"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxFileChunkPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, JukeboxFileChunkPayload::fileName,
					ByteBufCodecs.VAR_INT, JukeboxFileChunkPayload::index,
					ByteBufCodecs.byteArray(CHUNK_SIZE), JukeboxFileChunkPayload::data,
					JukeboxFileChunkPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
