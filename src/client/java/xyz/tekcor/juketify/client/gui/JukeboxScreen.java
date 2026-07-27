package xyz.tekcor.juketify.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import xyz.tekcor.juketify.client.audio.JukeboxPlayback;
import xyz.tekcor.juketify.client.library.MusicLibrary;
import xyz.tekcor.juketify.client.library.Track;
import xyz.tekcor.juketify.net.JukeboxCommandPayload;
import xyz.tekcor.juketify.net.JukeboxSearchOnlinePayload;

public class JukeboxScreen extends Screen {
	private static final int PANEL_BG = 0xC0101010;
	private static final int PANEL_BORDER = 0xFF9A9A9A;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;
	private static final int TEXT_OK = 0xFF55FF55;

	private static final int MAX_RESULTS_SHOWN = 5;

	private final BlockPos jukeboxPos;

	private EditBox search;
	private Component status = Component.empty();
	private int statusColor = TEXT;
	private final List<Track> results = new ArrayList<>();

	public JukeboxScreen(BlockPos jukeboxPos) {
		super(Component.literal("Juketify"));
		this.jukeboxPos = jukeboxPos;
	}

	@Override
	protected void init() {
		int centreX = this.width / 2;

		this.search = new EditBox(this.font, centreX - 110, 54, 220, 20, Component.literal("Search"));
		this.search.setMaxLength(128);
		this.search.setHint(Component.literal("song name...").withStyle(ChatFormatting.DARK_GRAY));
		this.addRenderableWidget(this.search);
		this.setInitialFocus(this.search);

		this.addRenderableWidget(Button.builder(Component.literal("Stop"), b -> stopPlayback())
				.bounds(centreX - 110, 160, 105, 20)
				.build());

		this.addRenderableWidget(Button.builder(Component.literal("Rescan"), b -> rescan())
				.bounds(centreX + 5, 160, 105, 20)
				.build());

		Track playing = JukeboxPlayback.currentTrack();

		if (playing != null) {
			this.status = Component.literal("Now playing: " + playing.label());
			this.statusColor = TEXT_OK;
		} else {
			this.status = Component.literal("Search for a song and hit Enter.");
			this.statusColor = TEXT_DIM;
		}
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			submit();
			return true;
		}

		return super.keyPressed(event);
	}

	private void stopPlayback() {
		ClientPlayNetworking.send(JukeboxCommandPayload.stop(this.jukeboxPos));

		this.results.clear();
		this.status = Component.literal("Stopped.");
		this.statusColor = TEXT_DIM;
	}

	private void rescan() {
		MusicLibrary.get().rescan();

		this.results.clear();
		this.status = Component.literal("Rescanned: " + MusicLibrary.get().size() + " track(s) found.");
		this.statusColor = TEXT_DIM;
	}

	private void submit() {
		String query = this.search.getValue().trim();

		this.results.clear();

		if (query.isEmpty()) {
			this.status = Component.literal("Type something to search for.");
			this.statusColor = TEXT_DIM;
			return;
		}

		List<Track> found = MusicLibrary.get().search(query);

		if (found.isEmpty()) {
			ClientPlayNetworking.send(new JukeboxSearchOnlinePayload(this.jukeboxPos, query));

			this.status = Component.literal("Searching online for \"" + query + "\"...");
			this.statusColor = TEXT_DIM;
			return;
		}

		this.results.addAll(found.subList(0, Math.min(found.size(), MAX_RESULTS_SHOWN)));

		Track best = found.getFirst();

		ClientPlayNetworking.send(new JukeboxCommandPayload(this.jukeboxPos, best.fileName()));

		this.status = Component.literal("Getting everyone ready: " + best.label());
		this.statusColor = TEXT_DIM;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int centreX = this.width / 2;

		graphics.fill(centreX - 130, 20, centreX + 130, 190, PANEL_BG);
		graphics.outline(centreX - 130, 20, 260, 170, PANEL_BORDER);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		graphics.text(this.font, this.title, centreX - this.font.width(this.title) / 2, 30, TEXT);

		Component hint = Component.literal(MusicLibrary.get().size() + " track(s) indexed");
		graphics.text(this.font, hint, centreX - this.font.width(hint) / 2, 42, TEXT_DIM);

		if (!this.status.getString().isEmpty()) {
			graphics.text(this.font, this.status, centreX - this.font.width(this.status) / 2, 82, this.statusColor);
		}

		int y = 98;
		for (Track track : this.results) {
			Component line = Component.literal(track.label());
			graphics.text(this.font, line, centreX - this.font.width(line) / 2, y, TEXT_DIM);
			y += 10;
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
