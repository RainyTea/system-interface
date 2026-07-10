package com.systeminterface.services.drops;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Curated exclusion list of <b>conditional drops</b> — items a monster only drops under a condition
 * (e.g. Treasure Trails clue-step keys) that the wiki's {@code dropsline} data carries NO
 * machine-readable flag for (verified 2026-07-06: Man's "Key (medium)" reads {@code Drop type
 * "combat"}, {@code Rarity "Always"}, empty notes). Excluding them at Bucket mapping time restores
 * the old wikitext parser's quest-only filter. Data-fillable: add one lowercase line per QA-found
 * conditional drop. Bundled/user tables bypass the mapper and are never filtered.
 */
public final class ConditionalDrops
{
	private ConditionalDrops()
	{
	}

	private static final Set<String> CONDITIONAL = buildConditional();

	/** Lowercase item names excluded from Bucket-mapped drop tables. Add a line per QA find. */
	private static Set<String> buildConditional()
	{
		final Set<String> s = new HashSet<>();
		s.add("key (medium)"); // Treasure Trails clue-step key (Man, Guard, Wizard, ...)
		s.add("key (elite)");  // Treasure Trails clue-step key (King Black Dragon)
		return Collections.unmodifiableSet(s);
	}

	/** Whether {@code itemName} is a curated conditional drop (case-insensitive; null → false). */
	public static boolean isConditional(String itemName)
	{
		return isConditional(itemName, CONDITIONAL);
	}

	static boolean isConditional(String itemName, Set<String> conditional)
	{
		return itemName != null && conditional.contains(itemName.toLowerCase().trim());
	}
}
