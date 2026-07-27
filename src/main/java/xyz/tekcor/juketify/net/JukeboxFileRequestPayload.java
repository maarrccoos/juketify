package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxFileRequestPayload(BlockPos pos, String fileName) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxFileRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_file_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxFileRequestPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxFileRequestPayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxFileRequestPayload::fileName,
					JukeboxFileRequestPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
