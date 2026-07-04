package com.systeminterface.modules.ui;

import com.systeminterface.core.SystemInterfaceConfig;
import com.systeminterface.common.model.ItemAppraisal;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuEntry;
import net.runelite.client.game.ItemEquipmentStats;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStats;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ColorUtil;
import net.runelite.client.util.QuantityFormatter;

/**
 * Adds a tooltip to the hovered item showing its F–SS rarity rank (by GE value),
 * value, and — for equippable items — a compact equipment-stats block.
 *
 * <p>Detects the hovered item from the top menu entry's item id, so it works in
 * the inventory, bank, equipment screen, and on ground items without per-widget
 * special-casing. Toggle via {@link SystemInterfaceConfig#showItemRankHover()}.
 */
@Singleton
public class ItemHoverOverlay extends Overlay
{
	private final Client client;
	private final ItemManager itemManager;
	private final TooltipManager tooltipManager;
	private final SystemInterfaceConfig config;

	@Inject
	public ItemHoverOverlay(Client client, ItemManager itemManager,
		TooltipManager tooltipManager, SystemInterfaceConfig config)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.tooltipManager = tooltipManager;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.showItemRankHover() || client.isMenuOpen())
		{
			return null;
		}

		final MenuEntry[] menu = client.getMenuEntries();
		if (menu.length == 0)
		{
			return null;
		}
		final int itemId = menu[menu.length - 1].getItemId();
		if (itemId < 0)
		{
			return null;
		}

		// Check noted on the original item BEFORE canonicalizing (which strips noted status).
		final ItemComposition origComp = client.getItemDefinition(itemId);
		final boolean noted = origComp != null && origComp.getNote() != -1;
		final int canonical = itemManager.canonicalize(itemId);
		final ItemComposition comp = client.getItemDefinition(canonical);
		final String name = comp != null ? comp.getName() : null;
		if (name == null || name.isEmpty() || "null".equalsIgnoreCase(name))
		{
			return null;
		}

		final int value = itemManager.getItemPrice(canonical);
		final ItemStats stats = itemManager.getItemStats(canonical);
		final ItemEquipmentStats eq = stats != null ? stats.getEquipment() : null;
		final boolean equipable = stats != null && stats.isEquipable();

		// Skip valueless, non-equippable, non-notable items (quest items etc.).
		if (value <= 0 && eq == null && !noted)
		{
			return null;
		}

		tooltipManager.add(new Tooltip(buildText(name, value, eq, equipable, noted)));
		return null;
	}

	/** Soft wrap width (chars) for the description, to keep the tooltip narrow. */
	private static final int WRAP = 30;

	private static String buildText(String name, int value, ItemEquipmentStats eq,
		boolean equipable, boolean noted)
	{
		final int slot = eq != null ? eq.getSlot() : -1;
		final ItemAppraisal appraisal = ItemAppraisal.appraise(name, value, equipable, slot, noted);
		final ItemAppraisal.ItemClass cls = appraisal.getItemClass();

		final StringBuilder sb = new StringBuilder();
		// Name in rank colour, then class · rank.
		sb.append(ColorUtil.wrapWithColorTag(name, appraisal.getRank().getColor()));
		sb.append("</br>").append(cls.getLabel()).append(" · ")
			.append(ColorUtil.wrapWithColorTag("Rank " + appraisal.getRank().getLabel(),
				appraisal.getRank().getColor()));
		if (appraisal.getSkillUse() != null)
		{
			sb.append("</br>Use: ").append(appraisal.getSkillUse());
		}
		// Description, soft-wrapped so the tooltip stays narrow.
		for (String line : wrap(appraisal.getDescription(), WRAP))
		{
			sb.append("</br>").append(ColorUtil.wrapWithColorTag(line, java.awt.Color.LIGHT_GRAY));
		}
		sb.append("</br>Value: ").append(QuantityFormatter.quantityToStackSize(value)).append(" gp");

		// Show only the stats that matter for the item's role: offence for weapons,
		// defence for armour. Tools/runes/resources/ammo get no combat block.
		if (eq != null && cls == ItemAppraisal.ItemClass.WEAPON)
		{
			sb.append("</br>Atk ")
				.append(plus(eq.getAstab())).append('/')
				.append(plus(eq.getAslash())).append('/')
				.append(plus(eq.getAcrush())).append('/')
				.append(plus(eq.getAmagic())).append('/')
				.append(plus(eq.getArange()));
			final List<String> extras = new ArrayList<>();
			if (eq.getStr() != 0) extras.add("Str " + plus(eq.getStr()));
			if (eq.getRstr() != 0) extras.add("Rng " + plus(eq.getRstr()));
			if (eq.getMdmg() != 0) extras.add("Mag " + plusF(eq.getMdmg()) + "%");
			if (!extras.isEmpty())
			{
				sb.append("</br>").append(String.join("  ", extras));
			}
		}
		else if (eq != null && cls == ItemAppraisal.ItemClass.ARMOUR)
		{
			sb.append("</br>Def ")
				.append(plus(eq.getDstab())).append('/')
				.append(plus(eq.getDslash())).append('/')
				.append(plus(eq.getDcrush())).append('/')
				.append(plus(eq.getDmagic())).append('/')
				.append(plus(eq.getDrange()));
			final List<String> extras = new ArrayList<>();
			if (eq.getStr() != 0) extras.add("Str " + plus(eq.getStr()));
			if (eq.getPrayer() != 0) extras.add("Pray " + plus(eq.getPrayer()));
			if (!extras.isEmpty())
			{
				sb.append("</br>").append(String.join("  ", extras));
			}
		}
		return sb.toString();
	}

	/** Greedy word-wrap into lines of at most {@code max} characters. */
	private static List<String> wrap(String text, int max)
	{
		final List<String> lines = new ArrayList<>();
		final StringBuilder line = new StringBuilder();
		for (String word : text.split(" "))
		{
			if (line.length() > 0 && line.length() + 1 + word.length() > max)
			{
				lines.add(line.toString());
				line.setLength(0);
			}
			if (line.length() > 0)
			{
				line.append(' ');
			}
			line.append(word);
		}
		if (line.length() > 0)
		{
			lines.add(line.toString());
		}
		return lines;
	}

	private static String plus(int v)
	{
		return v >= 0 ? "+" + v : Integer.toString(v);
	}

	private static String plusF(double v)
	{
		final String s = v == Math.floor(v) ? Long.toString((long) v) : String.format("%.1f", v);
		return v >= 0 ? "+" + s : s;
	}
}
