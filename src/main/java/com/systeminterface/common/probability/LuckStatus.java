package com.systeminterface.common.probability;

import java.awt.Color;

/**
 * Maps a z-score (actual drops vs expected) to a named luck tier with color.
 * Thresholds: Spooned (z>1.5), Lucky (z>0.5), Average, Unlucky (z<-0.5), Cursed (z<-1.5).
 */
public enum LuckStatus
{
	SPOONED("Spooned", new Color(0, 220, 0)),
	LUCKY("Lucky", new Color(100, 220, 100)),
	AVERAGE("Average", new Color(200, 200, 200)),
	UNLUCKY("Unlucky", new Color(220, 160, 0)),
	CURSED("Cursed", new Color(220, 0, 0));

	private final String label;
	private final Color color;

	LuckStatus(String label, Color color)
	{
		this.label = label;
		this.color = color;
	}

	public String getLabel()
	{
		return label;
	}

	public Color getColor()
	{
		return color;
	}

	public static LuckStatus fromZScore(double z)
	{
		if (z > 1.5) return SPOONED;
		if (z > 0.5) return LUCKY;
		if (z >= -0.5) return AVERAGE;
		if (z >= -1.5) return UNLUCKY;
		return CURSED;
	}
}
