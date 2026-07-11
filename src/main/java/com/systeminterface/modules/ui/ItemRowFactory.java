package com.systeminterface.modules.ui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Shared Swing builders for item-icon rows, used by both the Combat section's
 * "Track" grid (what can drop) and the Loot Log / skilling output rows (what
 * did drop). Extracted so {@link CombatSection} and the upcoming SkillingSection
 * (Task 6) don't duplicate this rendering.
 */
public final class ItemRowFactory
{
	private static final Color RECEIVED_BADGE = new Color(0, 180, 0);
	private static final Color LOOT_QTY = new Color(0, 190, 0);

	private ItemRowFactory()
	{
	}

	/** One itemised line for a loot/output ledger: small item icon, name, and an "xN" quantity badge. */
	public static final class LedgerRow
	{
		public final int itemId;
		public final String name;
		public final long qty;

		public LedgerRow(int itemId, String name, long qty)
		{
			this.itemId = itemId;
			this.name = name;
			this.qty = qty;
		}
	}

	/** Builds a single ledger row: icon + name + "xN" quantity, dark-themed. */
	public static JPanel buildLedgerRow(ItemManager itemManager, LedgerRow r)
	{
		JPanel row = new JPanel(new BorderLayout(4, 0));
		row.setBackground(ColorScheme.DARK_GRAY_COLOR);
		row.setBorder(new EmptyBorder(1, 12, 1, 4));

		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(SwingConstants.LEFT);
		if (r.itemId >= 0)
		{
			AsyncBufferedImage img = itemManager.getImage(r.itemId);
			img.onLoaded(() -> SwingUtilities.invokeLater(() ->
			{
				icon.setIcon(new ImageIcon(img));
				row.repaint();
			}));
			icon.setIcon(new ImageIcon(img));
		}
		row.add(icon, BorderLayout.WEST);

		JLabel nameLabel = new JLabel(r.name);
		nameLabel.setForeground(new Color(210, 210, 210));
		nameLabel.setBorder(new EmptyBorder(0, 4, 0, 0));
		row.add(nameLabel, BorderLayout.CENTER);

		JLabel qtyLabel = new JLabel("x" + String.format("%,d", r.qty));
		qtyLabel.setForeground(LOOT_QTY);
		row.add(qtyLabel, BorderLayout.EAST);

		return row;
	}

	/**
	 * Builds a single trackable drop-rate slot for the rarity/Track grid: item icon,
	 * a "received xN" badge (if any), a rarity-coloured border (yellow when tracked),
	 * a rate label, and a click handler that toggles tracking.
	 */
	public static JPanel createItemSlot(ItemManager itemManager, Dimension itemSize, Color tierColor,
		Color selectedBorderColor, String itemName, int itemId, double rate, int received,
		boolean isTracked, boolean rareTable, Runnable onClick)
	{
		JPanel slot = new JPanel(new BorderLayout());
		slot.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		slot.setPreferredSize(itemSize);
		slot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

		if (isTracked)
		{
			slot.setBorder(BorderFactory.createLineBorder(selectedBorderColor, 2));
		}
		else
		{
			slot.setBorder(BorderFactory.createLineBorder(tierColor, 1));
		}

		JLabel iconLabel = new JLabel();
		iconLabel.setHorizontalAlignment(SwingConstants.CENTER);
		iconLabel.setVerticalAlignment(SwingConstants.CENTER);

		if (itemId >= 0)
		{
			AsyncBufferedImage img = itemManager.getImage(itemId);
			img.onLoaded(() -> SwingUtilities.invokeLater(() ->
			{
				iconLabel.setIcon(new ImageIcon(img));
				slot.repaint();
			}));
			iconLabel.setIcon(new ImageIcon(img));
		}
		slot.add(iconLabel, BorderLayout.CENTER);

		if (received > 0)
		{
			JLabel badge = new JLabel("x" + received, SwingConstants.RIGHT);
			badge.setForeground(RECEIVED_BADGE);
			badge.setFont(badge.getFont().deriveFont(Font.BOLD, 9f));
			badge.setBorder(new EmptyBorder(0, 0, 0, 1));
			slot.add(badge, BorderLayout.NORTH);
		}

		long denom = Math.max(1L, Math.round(1.0 / rate));
		String rateStr = denom <= 1 ? "Always" : "1/" + denom;
		JLabel rateLabel = new JLabel(rateStr, SwingConstants.CENTER);
		rateLabel.setForeground(Color.WHITE);
		rateLabel.setFont(rateLabel.getFont().deriveFont(Font.BOLD, 11f));
		rateLabel.setOpaque(true);
		rateLabel.setBackground(new Color(0, 0, 0, 200));
		slot.add(rateLabel, BorderLayout.SOUTH);

		String tip = "<html><b>" + itemName + "</b>"
			+ (rareTable ? " <font color='#999999'>(RDT)</font>" : "") + "<br>" + rateStr;
		if (received > 0) tip += "<br>Received: " + received + "x";
		tip += isTracked ? "<br>Tracked — click to untrack" : "<br>Click to track";
		tip += "</html>";
		slot.setToolTipText(tip);

		slot.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				onClick.run();
			}
		});

		return slot;
	}

	/** A blank filler slot used to pad the last row of a rarity grid to a full width. */
	public static JPanel blankSlot(Dimension itemSize)
	{
		JPanel blank = new JPanel();
		blank.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		blank.setPreferredSize(itemSize);
		return blank;
	}
}
