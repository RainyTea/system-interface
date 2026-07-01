package com.systeminterface.common.model;

import java.awt.Color;

public enum BestiaryRank
{
	E("E", 1, 20, 1.0, new Color(160, 160, 160), "A weak foe, not worth a glance"),
	D("D", 21, 40, 1.5, new Color(255, 220, 50), "A minor threat at best"),
	C("C", 41, 80, 2.0, new Color(60, 150, 255), "A worthy opponent"),
	B("B", 81, 120, 3.0, new Color(50, 200, 80), "A dangerous adversary"),
	A("A", 121, 160, 4.0, new Color(255, 140, 40), "A formidable enemy"),
	S("S", 161, 200, 5.0, new Color(220, 50, 50), "A fearsome creature"),
	S_PLUS("S+", 201, 300, 6.0, new Color(180, 80, 255), "Death incarnate"),
	SS("SS", 301, Integer.MAX_VALUE, 8.0, new Color(100, 0, 130), "An existence beyond comprehension");

	private final String label;
	private final int minLevel;
	private final int maxLevel;
	private final double weight;
	private final Color color;
	private final String flavor;

	BestiaryRank(String label, int minLevel, int maxLevel, double weight, Color color, String flavor)
	{
		this.label = label;
		this.minLevel = minLevel;
		this.maxLevel = maxLevel;
		this.weight = weight;
		this.color = color;
		this.flavor = flavor;
	}

	public String getLabel() { return label; }
	public double getWeight() { return weight; }
	public Color getColor() { return color; }
	public String getFlavor() { return flavor; }

	public static BestiaryRank fromCombatLevel(int level)
	{
		if (level <= 0) return E;
		for (BestiaryRank r : values())
		{
			if (level >= r.minLevel && level <= r.maxLevel)
			{
				return r;
			}
		}
		return SS;
	}
}
