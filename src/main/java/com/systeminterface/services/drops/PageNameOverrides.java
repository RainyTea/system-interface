package com.systeminterface.services.drops;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Resolves an in-game / canonical target name to the exact OSRS-Wiki {@code page_name} used by Bucket
 * {@code where('page_name', …)}. The base case is identity (the name IS the page). The curated
 * {@link #OVERRIDES} map — data-fillable, one line per entry — handles the recurring edge case where a
 * monster's wiki page carries a disambiguating {@code (…)} suffix (location / game-mode / variant)
 * while the in-game name does not. Add entries as in-game QA surfaces mismatches.
 */
public final class PageNameOverrides
{
	private PageNameOverrides()
	{
	}

	private static final Map<String, String> OVERRIDES = buildOverrides();

	/** Curated name → wiki page_name overrides. Starts empty; add a line when QA finds a mismatch. */
	private static Map<String, String> buildOverrides()
	{
		final Map<String, String> m = new HashMap<>();
		// Example (when found): m.put("<in-game name>", "<Wiki page (variant)>");
		return Collections.unmodifiableMap(m);
	}

	/** The wiki page_name for {@code name}, or {@code name} unchanged when no override exists. */
	public static String resolve(String name)
	{
		return resolve(name, OVERRIDES);
	}

	static String resolve(String name, Map<String, String> overrides)
	{
		if (name == null)
		{
			return null;
		}
		return overrides.getOrDefault(name, name);
	}
}
