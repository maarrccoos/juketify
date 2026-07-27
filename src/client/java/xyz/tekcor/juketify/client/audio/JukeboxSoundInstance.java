package xyz.tekcor.juketify.client.audio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.sound.sampled.AudioFormat;

import net.fabricmc.fabric.api.client.sound.v1.FabricSoundInstance;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.sounds.AudioStream;
import net.minecraft.client.sounds.JOrbisAudioStream;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import xyz.tekcor.juketify.Juketify;

public class JukeboxSoundInstance extends AbstractTickableSoundInstance {

	public static final Identifier STREAM_SOUND_ID =
			Identifier.fromNamespaceAndPath("juketify", "stream");

	private static final SoundEvent STREAM_EVENT =
			SoundEvent.createVariableRangeEvent(STREAM_SOUND_ID);

	private final Path file;
	private final long startOffsetMillis;

	public JukeboxSoundInstance(Path file, BlockPos pos) {
		this(file, pos, 0L);
	}

	public JukeboxSoundInstance(Path file, BlockPos pos, long startOffsetMillis) {
		super(STREAM_EVENT, SoundSource.RECORDS, RandomSource.create());

		this.file = file;
		this.startOffsetMillis = Math.max(0L, startOffsetMillis);

		this.x = pos.getX() + 0.5D;
		this.y = pos.getY() + 0.5D;
		this.z = pos.getZ() + 0.5D;

		this.looping = false;
		this.delay = 0;
		this.volume = ClientRadius.asVolume();
		this.pitch = 1.0F;
		this.attenuation = Attenuation.LINEAR;
	}

	public Path file() {
		return this.file;
	}

	public void halt() {
		this.stop();
	}

	@Override
	public CompletableFuture<AudioStream> getAudioStream(
			SoundBufferLibrary library, Identifier id, boolean repeatInstantly) {

		return CompletableFuture.supplyAsync(() -> {
			try {
				InputStream in = new BufferedInputStream(Files.newInputStream(this.file));
				AudioStream stream = new MonoAudioStream(new JOrbisAudioStream(in));

				if (this.startOffsetMillis > 0L) {
					skipAhead(stream, this.startOffsetMillis);
				}

				return stream;
			} catch (IOException e) {
				Juketify.LOGGER.error("Failed to open audio file {}", this.file, e);
				throw new CompletionException(e);
			}
		}, Util.nonCriticalIoPool());
	}

	private static void skipAhead(AudioStream stream, long offsetMillis) throws IOException {
		AudioFormat format = stream.getFormat();
		long targetBytes = (long) (offsetMillis / 1000.0D * format.getFrameRate() * format.getFrameSize());
		long discarded = 0L;

		while (discarded < targetBytes) {
			int want = (int) Math.min(16384L, targetBytes - discarded);
			ByteBuffer chunk = stream.read(want);

			if (chunk == null || !chunk.hasRemaining()) {
				break;
			}

			discarded += chunk.remaining();
		}
	}

	@Override
	public void tick() {

	}
}
