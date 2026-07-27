package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxReadyPayload(BlockPos pos, String fileName, boolean available) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxReadyPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_ready"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxReadyPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxReadyPayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxReadyPayload::fileName,
					ByteBufCodecs.BOOL, JukeboxReadyPayload::available,
					JukeboxReadyPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
