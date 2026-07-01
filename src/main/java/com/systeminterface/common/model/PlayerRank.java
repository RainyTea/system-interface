package com.systeminterface.common.model;

import java.awt.Color;

/**
 * Adventurer ranking for players, themed after MMORPG/manga hunter guild tiers.
 * Based on combat level for now; future versions may factor in boss KC and
 * combat achievements where that data is locally available.
 */
public enum PlayerRank
{
	F("F", 3, 25, new Color(150, 150, 150), "A fresh-faced wanderer"),
	E("E", 26, 50, new Color(255, 220, 50), "Still finding their footing"),
	D("D", 51, 75, new Color(60, 150, 255), "A capable fighter"),
	C("C", 76, 95, new Color(50, 200, 80), "A seasoned combatant"),
	B("B", 96, 110, new Color(255, 140, 40), "A battle-hardened veteran"),
	A("A", 111, 120, new Color(220, 50, 50), "An elite warrior"),
	S("S", 121, 125, new Color(180, 80, 255), "A master of combat"),
	SS("SS", 126, Integer.MAX_VALUE, new Color(100, 0, 130), "A living legend");

	private final String label;
	private final int minLevel;
	private final int maxLevel;
	private final Color color;
	private final String flavor;

	PlayerRank(String label, int minLevel, int maxLevel, Color color, String flavor)
	{
		this.label = label;
		this.minLevel = minLevel;
		this.maxLevel = maxLevel;
		this.color = color;
		this.flavor = flavor;
	}

	public String getLabel() { return label; }
	public Color getColor() { return color; }
	public String getFlavor() { return flavor; }

	public static PlayerRank fromCombatLevel(int level)
	{
		if (level <= 0) return F;
		for (PlayerRank r : values())
		{
			if (level >= r.minLevel && level <= r.maxLevel)
			{
				return r;
			}
		}
		return SS;
	}
}
