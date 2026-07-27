package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxUploadRequestPayload(BlockPos pos, String fileName) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxUploadRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_upload_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxUploadRequestPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxUploadRequestPayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxUploadRequestPayload::fileName,
					JukeboxUploadRequestPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
