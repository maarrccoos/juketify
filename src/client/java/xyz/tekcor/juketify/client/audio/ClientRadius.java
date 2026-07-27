package xyz.tekcor.juketify.client.audio;

import xyz.tekcor.juketify.JuketifyConfig;

public final class ClientRadius {
	private static final float BASE_ATTENUATION_DISTANCE = 16.0F;

	private static volatile int radius = JuketifyConfig.DEFAULT_RADIUS;

	private ClientRadius() {
	}

	public static void set(int blocks) {
		radius = JuketifyConfig.clamp(blocks);
	}

	public static int get() {
		return radius;
	}

	public static float asVolume() {
		return Math.max(1.0F, radius / BASE_ATTENUATION_DISTANCE);
	}
}
