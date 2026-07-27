package xyz.tekcor.juketify.client.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

import javax.sound.sampled.AudioFormat;

import net.minecraft.client.sounds.AudioStream;

public class MonoAudioStream implements AudioStream {
	private static final int STEREO_FRAME_BYTES = 4;

	private final AudioStream delegate;
	private final AudioFormat format;
	private final boolean downmix;

	private byte[] leftover = new byte[0];

	public MonoAudioStream(AudioStream delegate) {
		this.delegate = delegate;

		AudioFormat source = delegate.getFormat();
		this.downmix = source.getChannels() == 2 && source.getSampleSizeInBits() == 16;

		if (this.downmix) {
			this.format = new AudioFormat(
					source.getEncoding(),
					source.getSampleRate(),
					16,
					1,
					2,
					source.getFrameRate(),
					source.isBigEndian());
		} else {
			this.format = source;
		}
	}

	@Override
	public AudioFormat getFormat() {
		return this.format;
	}

	@Override
	public ByteBuffer read(int expectedSize) throws IOException {
		if (!this.downmix) {
			return this.delegate.read(expectedSize);
		}

		ByteBuffer source = this.delegate.read(expectedSize * 2);

		if (source == null) {
			return null;
		}

		ByteOrder order = source.order();
		byte[] incoming = new byte[source.remaining()];
		source.get(incoming);

		byte[] combined;

		if (this.leftover.length == 0) {
			combined = incoming;
		} else {
			combined = new byte[this.leftover.length + incoming.length];
			System.arraycopy(this.leftover, 0, combined, 0, this.leftover.length);
			System.arraycopy(incoming, 0, combined, this.leftover.length, incoming.length);
		}

		int usable = combined.length - (combined.length % STEREO_FRAME_BYTES);
		int remainder = combined.length - usable;

		if (remainder > 0) {
			this.leftover = new byte[remainder];
			System.arraycopy(combined, usable, this.leftover, 0, remainder);
		} else {
			this.leftover = new byte[0];
		}

		int frames = usable / STEREO_FRAME_BYTES;
		ByteBuffer out = ByteBuffer.allocateDirect(frames * 2).order(order);

		ShortBuffer stereo = ByteBuffer.wrap(combined, 0, usable).order(order).asShortBuffer();
		ShortBuffer mono = out.asShortBuffer();

		for (int i = 0; i < frames; i++) {
			int left = stereo.get(i * 2);
			int right = stereo.get(i * 2 + 1);
			mono.put(i, (short) ((left + right) / 2));
		}

		out.position(0);
		out.limit(frames * 2);
		return out;
	}

	@Override
	public void close() throws IOException {
		this.delegate.close();
	}
}
