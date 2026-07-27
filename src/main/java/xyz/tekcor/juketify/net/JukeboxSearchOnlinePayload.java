package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxSearchOnlinePayload(BlockPos pos, String query) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxSearchOnlinePayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_search_online"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxSearchOnlinePayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxSearchOnlinePayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxSearchOnlinePayload::query,
					JukeboxSearchOnlinePayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
