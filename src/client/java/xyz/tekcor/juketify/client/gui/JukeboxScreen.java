package xyz.tekcor.juketify.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import com.mojang.blaze3d.platform.InputConstants;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import xyz.tekcor.juketify.client.ClientJukebox;
import xyz.tekcor.juketify.client.library.MusicLibrary;
import xyz.tekcor.juketify.client.library.Track;
import xyz.tekcor.juketify.net.JukeboxCommandPayload;
import xyz.tekcor.juketify.net.JukeboxLibraryRequestPayload;
import xyz.tekcor.juketify.net.JukeboxQueueAddPayload;
import xyz.tekcor.juketify.net.JukeboxSearchOnlinePayload;
import xyz.tekcor.juketify.net.JukeboxSkipPayload;

public class JukeboxScreen extends Screen {
	private static final int PANEL_BG = 0xC0101010;
	private static final int PANEL_BORDER = 0xFF9A9A9A;
	private static final int ROW_HOVER = 0x40FFFFFF;
	private static final int SCROLL_TRACK = 0x40FFFFFF;
	private static final int SCROLL_THUMB = 0xFFAAAAAA;
	private static final int TEXT = 0xFFFFFFFF;
	private static final int TEXT_DIM = 0xFFA0A0A0;
	private static final int TEXT_OK = 0xFF55FF55;
	private static final int TEXT_WARN = 0xFFFFAA00;

	private static final int PANEL_HALF_WIDTH = 140;
	private static final int LIST_TOP = 70;
	private static final int ROW_HEIGHT = 13;
	private static final int VISIBLE_ROWS = 6;

	private final BlockPos jukeboxPos;

	private EditBox search;
	private Component status = Component.empty();
	private int statusColor = TEXT_DIM;
	private final List<String> shown = new ArrayList<>();
	private int scroll;
	private int seenVersion = -1;

	public JukeboxScreen(BlockPos jukeboxPos) {
		super(Component.literal("Juketify"));
		this.jukeboxPos = jukeboxPos;
	}

	@Override
	protected void init() {
		int centreX = this.width / 2;

		ClientPlayNetworking.send(new JukeboxLibraryRequestPayload(this.jukeboxPos));

		this.search = new EditBox(this.font, centreX - 120, 46, 240, 18, Component.literal("Search"));
		this.search.setMaxLength(128);
		this.search.setHint(Component.literal("search or type to add...").withStyle(ChatFormatting.DARK_GRAY));
		this.search.setResponder(value -> {
			this.scroll = 0;
			refreshList();
		});
		this.addRenderableWidget(this.search);
		this.setInitialFocus(this.search);

		this.addRenderableWidget(Button.builder(Component.literal("Stop"), b -> stopPlayback())
				.bounds(centreX - 130, 188, 84, 20)
				.build());

		this.addRenderableWidget(Button.builder(Component.literal("Skip"), b -> skip())
				.bounds(centreX - 42, 188, 84, 20)
				.build());

		this.addRenderableWidget(Button.builder(Component.literal("Rescan"), b -> rescan())
				.bounds(centreX + 46, 188, 84, 20)
				.build());

		this.status = Component.literal("Click a track to play it, or queue it if something's on.");
		refreshList();
	}

	private void refreshList() {
		this.seenVersion = ClientJukebox.version();
		this.shown.clear();

		String query = this.search == null ? "" : this.search.getValue().trim();

		Set<String> source = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
		source.addAll(ClientJukebox.library());

		for (Track track : MusicLibrary.get().tracks()) {
			source.add(track.fileName());
		}

		for (String fileName : source) {
			if (query.isEmpty() || ClientJukebox.matches(fileName, query)) {
				this.shown.add(fileName);
			}
		}

		this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
	}

	private int maxScroll() {
		return Math.max(0, this.shown.size() - VISIBLE_ROWS);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER) {
			submit();
			return true;
		}

		return super.keyPressed(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (mouseY >= LIST_TOP && mouseY < LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT) {
			this.scroll = Math.max(0, Math.min(maxScroll(), this.scroll - (int) Math.signum(scrollY)));
			return true;
		}

		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		int index = rowAt(event.x(), event.y());

		if (index >= 0) {
			play(this.shown.get(index));
			return true;
		}

		return super.mouseClicked(event, doubleClick);
	}

	private int rowAt(double mouseX, double mouseY) {
		int centreX = this.width / 2;

		if (mouseX < centreX - 130 || mouseX > centreX + 124) {
			return -1;
		}

		if (mouseY < LIST_TOP || mouseY >= LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT) {
			return -1;
		}

		int row = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT) + this.scroll;
		return row >= 0 && row < this.shown.size() ? row : -1;
	}

	private void play(String fileName) {
		ClientPlayNetworking.send(new JukeboxQueueAddPayload(this.jukeboxPos, fileName));

		this.status = Component.literal("Added: " + ClientJukebox.label(fileName));
		this.statusColor = TEXT_OK;
	}

	private void stopPlayback() {
		ClientPlayNetworking.send(JukeboxCommandPayload.stop(this.jukeboxPos));

		this.status = Component.literal("Stopped.");
		this.statusColor = TEXT_DIM;
	}

	private void skip() {
		ClientPlayNetworking.send(new JukeboxSkipPayload(this.jukeboxPos));

		this.status = Component.literal("Skipped.");
		this.statusColor = TEXT_DIM;
	}

	private void rescan() {
		MusicLibrary.get().rescan();
		ClientPlayNetworking.send(new JukeboxLibraryRequestPayload(this.jukeboxPos));
		refreshList();

		this.status = Component.literal("Rescanned.");
		this.statusColor = TEXT_DIM;
	}

	private void submit() {
		String query = this.search.getValue().trim();

		if (query.isEmpty()) {
			return;
		}

		if (!this.shown.isEmpty()) {
			play(this.shown.getFirst());
			return;
		}

		ClientPlayNetworking.send(new JukeboxSearchOnlinePayload(this.jukeboxPos, query));

		this.status = Component.literal("Searching online for \"" + query + "\"...");
		this.statusColor = TEXT_WARN;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		int centreX = this.width / 2;

		if (this.seenVersion != ClientJukebox.version()) {
			refreshList();
		}

		graphics.fill(centreX - PANEL_HALF_WIDTH, 20, centreX + PANEL_HALF_WIDTH, 212, PANEL_BG);
		graphics.outline(centreX - PANEL_HALF_WIDTH, 20, PANEL_HALF_WIDTH * 2, 192, PANEL_BORDER);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		graphics.text(this.font, this.title, centreX - this.font.width(this.title) / 2, 28, TEXT);

		renderList(graphics, centreX, mouseX, mouseY);
		renderFooter(graphics, centreX);
	}

	private void renderList(GuiGraphicsExtractor graphics, int centreX, int mouseX, int mouseY) {
		if (this.shown.isEmpty()) {
			Component empty = Component.literal("No tracks. Type a name and press Enter to fetch one.");
			graphics.text(this.font, empty, centreX - this.font.width(empty) / 2, LIST_TOP + 24, TEXT_DIM);
			return;
		}

		int hovered = rowAt(mouseX, mouseY);
		int end = Math.min(this.shown.size(), this.scroll + VISIBLE_ROWS);

		for (int i = this.scroll; i < end; i++) {
			int y = LIST_TOP + (i - this.scroll) * ROW_HEIGHT;
			String fileName = this.shown.get(i);

			if (i == hovered) {
				graphics.fill(centreX - 130, y, centreX + 124, y + ROW_HEIGHT, ROW_HOVER);
			}

			boolean playing = fileName.equals(ClientJukebox.nowPlaying());
			Component line = Component.literal(trim(ClientJukebox.label(fileName), 246));

			graphics.text(this.font, line, centreX - 126, y + 3, playing ? TEXT_OK : TEXT);
		}

		renderScrollbar(graphics, centreX);
	}

	private void renderScrollbar(GuiGraphicsExtractor graphics, int centreX) {
		if (maxScroll() <= 0) {
			return;
		}

		int trackTop = LIST_TOP;
		int trackHeight = VISIBLE_ROWS * ROW_HEIGHT;
		int x = centreX + 126;

		graphics.fill(x, trackTop, x + 3, trackTop + trackHeight, SCROLL_TRACK);

		int thumbHeight = Math.max(12, trackHeight * VISIBLE_ROWS / this.shown.size());
		int thumbY = trackTop + (trackHeight - thumbHeight) * this.scroll / maxScroll();

		graphics.fill(x, thumbY, x + 3, thumbY + thumbHeight, SCROLL_THUMB);
	}

	private void renderFooter(GuiGraphicsExtractor graphics, int centreX) {
		String playing = ClientJukebox.nowPlaying();
		List<String> queue = ClientJukebox.queue();
		String downloading = ClientJukebox.downloading();

		int y = LIST_TOP + VISIBLE_ROWS * ROW_HEIGHT + 6;

		if (!playing.isEmpty()) {
			graphics.text(this.font,
					Component.literal(trim("Playing: " + ClientJukebox.label(playing), 260)),
					centreX - 130, y, TEXT_OK);
		} else {
			graphics.text(this.font, Component.literal("Nothing playing"), centreX - 130, y, TEXT_DIM);
		}

		y += 11;

		if (!queue.isEmpty()) {
			String next = ClientJukebox.label(queue.getFirst());
			String extra = queue.size() > 1 ? "  (+" + (queue.size() - 1) + " more)" : "";
			graphics.text(this.font,
					Component.literal(trim("Next: " + next + extra, 260)),
					centreX - 130, y, TEXT_DIM);
		}

		y += 11;

		if (!downloading.isEmpty()) {
			graphics.text(this.font,
					Component.literal(trim("Downloading " + ClientJukebox.label(downloading)
							+ " " + ClientJukebox.downloadPercent() + "%", 260)),
					centreX - 130, y, TEXT_WARN);
		} else if (!this.status.getString().isEmpty()) {
			graphics.text(this.font, this.status, centreX - 130, y, this.statusColor);
		}
	}

	private String trim(String text, int maxWidth) {
		if (this.font.width(text) <= maxWidth) {
			return text;
		}

		String result = text;

		while (result.length() > 1 && this.font.width(result + "...") > maxWidth) {
			result = result.substring(0, result.length() - 1);
		}

		return result + "...";
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
