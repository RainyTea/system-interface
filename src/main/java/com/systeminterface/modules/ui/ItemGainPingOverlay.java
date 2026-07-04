package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.services.lookup.ItemNameCache;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;

/**
 * Transient item-gain ping channel (spec §4): an accumulating {@code + item xN} toast stack near
 * the chatbox for both combat loot and skilling gathers. Names resolve via {@link ItemNameCache}
 * (universal, no dependency on curated data); notable gains render tinted. Renders on the client
 * thread; the shared {@link ItemGainPings} model is mutated by client-thread gain callbacks.
 */
@Singleton
public final class ItemGainPingOverlay extends OverlayPanel
{
	private static final Color OSRS_BG = new Color(45, 40, 31, 220);
	private static final Color COMMON = new Color(120, 200, 255);
	private static final Color NOTABLE = new Color(255, 200, 50);

	private final Client client;
	private final SystemInterfaceConfig config;
	private final ItemNameCache itemNameCache;
	private final ItemGainPings pings;

	@Inject
	ItemGainPingOverlay(Client client, SystemInterfaceConfig config, ItemNameCache itemNameCache, ItemGainPings pings)
	{
		this.client = client;
		this.config = config;
		this.itemNameCache = itemNameCache;
		this.pings = pings;
		setPosition(OverlayPosition.DETACHED);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showItemGainPings())
		{
			return null;
		}
		if (getPreferredLocation() == null)
		{
			// Bottom-left, above the chatbox by default; user-draggable thereafter.
			final int h = client.getViewportHeight();
			setPreferredLocation(new java.awt.Point(8, h > 0 ? Math.max(0, h - 180) : 300));
		}
		final List<ItemGainPings.Ping> visible = pings.visible(System.currentTimeMillis());
		if (visible.isEmpty())
		{
			return null;
		}
		panelComponent.setBackgroundColor(OSRS_BG);
		for (ItemGainPings.Ping p : visible)
		{
			panelComponent.getChildren().add(LineComponent.builder()
				.left("+ " + itemNameCache.name(p.itemId))
				.leftColor(p.notable ? NOTABLE : COMMON)
				.right("x" + String.format("%,d", p.qty))
				.rightColor(p.notable ? NOTABLE : COMMON)
				.build());
		}
		return super.render(graphics);
	}
}
