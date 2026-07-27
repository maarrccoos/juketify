package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxQueueAddPayload(BlockPos pos, String fileName) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxQueueAddPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_queue_add"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxQueueAddPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxQueueAddPayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxQueueAddPayload::fileName,
					JukeboxQueueAddPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
