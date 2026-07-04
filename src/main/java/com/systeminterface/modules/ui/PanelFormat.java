package com.systeminterface.modules.ui;

/**
 * Shared GP abbreviation formatting for side-panel Swing components (K/M/B suffixes).
 * Extracted from {@link SystemInterfacePanel} so new panels (Session Summary, etc.)
 * don't duplicate the logic.
 */
public final class PanelFormat
{
	private PanelFormat()
	{
	}

	public static String gp(long v)
	{
		if (v >= 1_000_000_000L) return gpTrim(v / 1_000_000_000.0) + "B";
		if (v >= 1_000_000L) return gpTrim(v / 1_000_000.0) + "M";
		if (v >= 1_000L) return gpTrim(v / 1_000.0) + "K";
		return Long.toString(v);
	}

	private static String gpTrim(double d)
	{
		return d == Math.floor(d) ? Long.toString((long) d) : String.format("%.1f", d);
	}
}
