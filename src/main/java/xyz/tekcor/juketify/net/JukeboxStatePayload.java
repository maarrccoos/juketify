package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxStatePayload(BlockPos pos, String fileName, long offsetMillis) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxStatePayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_state"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxStatePayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxStatePayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxStatePayload::fileName,
					ByteBufCodecs.VAR_LONG, JukeboxStatePayload::offsetMillis,
					JukeboxStatePayload::new);

	public static JukeboxStatePayload stop(BlockPos pos) {
		return new JukeboxStatePayload(pos, "", 0L);
	}

	public boolean isStop() {
		return this.fileName.isEmpty();
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
