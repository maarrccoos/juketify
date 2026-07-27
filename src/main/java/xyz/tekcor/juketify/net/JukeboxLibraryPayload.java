package xyz.tekcor.juketify.net;

import java.util.List;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxLibraryPayload(List<String> fileNames) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxLibraryPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_library"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxLibraryPayload> CODEC =
			StreamCodec.composite(
					ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), JukeboxLibraryPayload::fileNames,
					JukeboxLibraryPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
