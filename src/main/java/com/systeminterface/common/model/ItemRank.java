package com.systeminterface.common.model;

import java.awt.Color;

/**
 * F→SS rarity ranking for items, by Grand Exchange value. Mirrors the F-SS
 * scale used by {@link PlayerRank} / {@link BestiaryRank} so the whole plugin
 * speaks one ranking language. Shown on the item-hover tooltip.
 */
public enum ItemRank
{
	F("F", 0L, new Color(150, 150, 150)),
	E("E", 1_000L, new Color(190, 190, 190)),
	D("D", 10_000L, new Color(110, 200, 110)),
	C("C", 100_000L, new Color(60, 150, 255)),
	B("B", 1_000_000L, new Color(50, 200, 80)),
	A("A", 10_000_000L, new Color(255, 140, 40)),
	S("S", 100_000_000L, new Color(220, 50, 50)),
	SS("SS", 1_000_000_000L, new Color(180, 80, 255));

	private final String label;
	private final long minValue;
	private final Color color;

	ItemRank(String label, long minValue, Color color)
	{
		this.label = label;
		this.minValue = minValue;
		this.color = color;
	}

	public String getLabel() { return label; }
	public Color getColor() { return color; }

	/** The highest rank whose value threshold the item clears. */
	public static ItemRank fromValue(long value)
	{
		ItemRank result = F;
		for (ItemRank r : values())
		{
			if (value >= r.minValue)
			{
				result = r;
			}
		}
		return result;
	}
}
