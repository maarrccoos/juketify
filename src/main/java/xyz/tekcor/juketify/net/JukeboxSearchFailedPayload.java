package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxSearchFailedPayload(BlockPos pos, String message) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxSearchFailedPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_search_failed"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxSearchFailedPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxSearchFailedPayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxSearchFailedPayload::message,
					JukeboxSearchFailedPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
