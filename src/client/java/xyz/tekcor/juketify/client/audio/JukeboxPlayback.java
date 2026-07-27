package xyz.tekcor.juketify.client.audio;

import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import xyz.tekcor.juketify.client.library.Track;

public final class JukeboxPlayback {
	@Nullable
	private static JukeboxSoundInstance current;
	@Nullable
	private static Track currentTrack;
	@Nullable
	private static BlockPos currentPos;

	private static long startOffsetMillis;
	private static long startedAtMillis;

	private JukeboxPlayback() {
	}

	public static void play(Track track, BlockPos pos) {
		play(track, pos, 0L);
	}

	public static void play(Track track, BlockPos pos, long startOffsetMillis) {
		stop();

		current = new JukeboxSoundInstance(track.path(), pos, startOffsetMillis);
		currentTrack = track;
		currentPos = pos.immutable();
		JukeboxPlayback.startOffsetMillis = Math.max(0L, startOffsetMillis);
		startedAtMillis = System.currentTimeMillis();

		Minecraft.getInstance().getSoundManager().play(current);
	}

	public static void stop() {
		if (current != null) {
			Minecraft.getInstance().getSoundManager().stop(current);
		}

		Minecraft.getInstance().getSoundManager()
				.stop(JukeboxSoundInstance.STREAM_SOUND_ID, SoundSource.RECORDS);

		current = null;
		currentTrack = null;
		currentPos = null;
	}

	public static void stopIfAt(BlockPos pos) {
		if (currentPos != null && currentPos.equals(pos)) {
			stop();
		}
	}

	public static void refreshRange() {
		if (current == null || currentTrack == null || currentPos == null) {
			return;
		}

		Track track = currentTrack;
		BlockPos pos = currentPos;

		play(track, pos, currentOffsetMillis());
	}

	private static long currentOffsetMillis() {
		return startOffsetMillis + (System.currentTimeMillis() - startedAtMillis);
	}

	@Nullable
	public static BlockPos currentPos() {
		return currentPos;
	}

	public static boolean isPlaying() {
		return current != null;
	}

	@Nullable
	public static Track currentTrack() {
		return currentTrack;
	}
}
