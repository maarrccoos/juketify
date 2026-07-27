package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxCommandPayload(BlockPos pos, String fileName) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxCommandPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_command"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxCommandPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxCommandPayload::pos,
					ByteBufCodecs.STRING_UTF8, JukeboxCommandPayload::fileName,
					JukeboxCommandPayload::new);

	public static JukeboxCommandPayload stop(BlockPos pos) {
		return new JukeboxCommandPayload(pos, "");
	}

	public boolean isStop() {
		return this.fileName.isEmpty();
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
