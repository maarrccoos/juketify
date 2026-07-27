package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxSkipPayload(BlockPos pos) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxSkipPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_skip"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxSkipPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxSkipPayload::pos,
					JukeboxSkipPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
