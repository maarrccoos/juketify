package xyz.tekcor.juketify.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxRadiusPayload(int radius) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxRadiusPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_radius"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxRadiusPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, JukeboxRadiusPayload::radius,
					JukeboxRadiusPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
