package xyz.tekcor.juketify.server;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

import xyz.tekcor.juketify.Juketify;

public final class OggDuration {

	private static final int TAIL_SCAN_BYTES = 65536;
	private static final int HEAD_SCAN_BYTES = 8192;

	private OggDuration() {
	}

	public static long millis(Path file) {
		try {
			long size = Files.size(file);

			if (size < 64L) {
				return 0L;
			}

			try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
				int sampleRate = readSampleRate(raf);

				if (sampleRate <= 0) {
					return 0L;
				}

				long granule = readLastGranulePosition(raf, size);

				if (granule <= 0L) {
					return 0L;
				}

				return granule * 1000L / sampleRate;
			}
		} catch (IOException e) {
			Juketify.LOGGER.warn("Could not read duration of {}", file, e);
			return 0L;
		}
	}

	private static int readSampleRate(RandomAccessFile raf) throws IOException {
		byte[] head = new byte[HEAD_SCAN_BYTES];
		raf.seek(0L);
		int read = raf.read(head);

		for (int i = 0; i + 17 < read; i++) {
			if (head[i] != 0x01
					|| head[i + 1] != 'v' || head[i + 2] != 'o' || head[i + 3] != 'r'
					|| head[i + 4] != 'b' || head[i + 5] != 'i' || head[i + 6] != 's') {
				continue;
			}

			return readIntLE(head, i + 12);
		}

		return -1;
	}

	private static long readLastGranulePosition(RandomAccessFile raf, long size) throws IOException {
		int length = (int) Math.min(TAIL_SCAN_BYTES, size);
		byte[] tail = new byte[length];

		raf.seek(size - length);
		raf.readFully(tail);

		for (int i = length - 14; i >= 0; i--) {
			if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S') {
				return readLongLE(tail, i + 6);
			}
		}

		return -1L;
	}

	private static int readIntLE(byte[] data, int offset) {
		return (data[offset] & 0xFF)
				| ((data[offset + 1] & 0xFF) << 8)
				| ((data[offset + 2] & 0xFF) << 16)
				| ((data[offset + 3] & 0xFF) << 24);
	}

	private static long readLongLE(byte[] data, int offset) {
		long value = 0L;

		for (int i = 7; i >= 0; i--) {
			value = (value << 8) | (data[offset + i] & 0xFFL);
		}

		return value;
	}
}
