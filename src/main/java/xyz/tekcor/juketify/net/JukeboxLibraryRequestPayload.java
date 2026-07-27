package xyz.tekcor.juketify.net;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public record JukeboxLibraryRequestPayload(BlockPos pos) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<JukeboxLibraryRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("juketify", "jukebox_library_request"));

	public static final StreamCodec<RegistryFriendlyByteBuf, JukeboxLibraryRequestPayload> CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, JukeboxLibraryRequestPayload::pos,
					JukeboxLibraryRequestPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
