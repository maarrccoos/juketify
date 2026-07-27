package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxPreparePayload(BlockPos pos, String fileName) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxPreparePayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_prepare"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxPreparePayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxPreparePayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxPreparePayload::fileName,
					JukeboxPreparePayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
