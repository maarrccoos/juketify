package xyz.tekcor.juketify.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxProgressPayload(String fileName, int percent) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxProgressPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_progress"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxProgressPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8, JukeboxProgressPayload::fileName,
					ByteBufCodecs.VAR_INT, JukeboxProgressPayload::percent,
					JukeboxProgressPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
