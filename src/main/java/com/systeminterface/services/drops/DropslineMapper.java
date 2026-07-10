package com.systeminterface.services.drops;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.systeminterface.services.wiki.BucketRow;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure mapping of OSRS-Wiki Bucket rows onto {@link DropTable}: {@code dropsline} rows → drop
 * {@link DropTable.Entry}, and an {@code infobox_monster} row → the table's monster fields. No HTTP,
 * no game state — trivially unit-testable, safe on any thread.
 */
@Slf4j
public final class DropslineMapper
{
	private DropslineMapper()
	{
	}

	/** Bumped when the Bucket producer changes; older cached tables auto-refetch (see {@link LootTables}). */
	public static final int CURRENT_SCHEMA = 8;

	private static final Pattern MULTI_ROLL = Pattern.compile("^(\\d+)\\s*[\\u00d7x]\\s*(.+)$");

	public static DropTable newTable(String target)
	{
		final DropTable table = new DropTable();
		table.target = target;
		table.schema = CURRENT_SCHEMA;
		table.drops = new ArrayList<>();
		return table;
	}

	/**
	 * Maps one {@code dropsline} row to a drop {@link DropTable.Entry}, parsing its {@code drop_json}.
	 * Returns null when the rarity isn't numerically interpretable (e.g. "Varies") — rateless rows are skipped.
	 */
	public static DropTable.Entry mapDrop(BucketRow row, Gson gson)
	{
		if (row == null)
		{
			return null;
		}
		final String name = row.str("item_name");
		final String dropJson = row.str("drop_json");
		if (name == null || name.isEmpty() || dropJson == null)
		{
			return null;
		}
		final JsonObject dj;
		try
		{
			dj = gson.fromJson(dropJson, JsonObject.class);
		}
		catch (RuntimeException e)
		{
			log.debug("Bad drop_json for '{}'", name, e);
			return null;
		}
		if (dj == null)
		{
			return null;
		}
		// Non-combat rows (e.g. pickpocket loot arrives as Drop type "thieving") don't belong in the
		// combat drop table. Missing/empty type is treated as combat — never over-filter.
		final String dropType = str(dj, "Drop type");
		if (dropType != null && !dropType.isEmpty() && !"combat".equalsIgnoreCase(dropType))
		{
			// Logged so QA can spot a genuine combat drop wrongly skipped if the wiki ever
			// introduces a new combat-flavored type value (watch-item from the final review).
			log.debug("Skipping non-combat drop '{}' (Drop type '{}')", name, dropType);
			return null;
		}
		// Conditional drops (clue-step keys etc.) carry no machine-readable flag — curated exclusion
		// restores the old wikitext parser's quest-only filter.
		if (ConditionalDrops.isConditional(name))
		{
			return null;
		}
		final double rate = parseRarity(str(dj, "Rarity"));
		if (rate <= 0.0)
		{
			return null;
		}
		final DropTable.Entry e = new DropTable.Entry();
		e.name = name;
		e.rate = rate;
		e.quantityLow = intVal(dj, "Quantity Low");
		e.quantityHigh = intVal(dj, "Quantity High");
		e.rolls = intVal(dj, "Rolls");
		e.dropValue = intVal(dj, "Drop Value");
		e.region = str(dj, "League region");
		return e;
	}

	/** Populates the table's monster fields from an {@code infobox_monster} row (first variant row). */
	public static void applyMonster(DropTable table, BucketRow row)
	{
		if (table == null || row == null)
		{
			return;
		}
		final Integer cl = row.intOrNull("combat_level");
		if (cl != null)
		{
			table.combatLevel = cl;
		}
		final Integer hp = row.intOrNull("hitpoints");
		if (hp != null)
		{
			table.maxHp = hp;
		}
		table.members = row.bool("is_members_only", true);
		table.examine = row.str("examine");
		table.maxHit = row.str("max_hit");
		table.attackStyle = row.str("attack_style");
		table.weakness = row.str("elemental_weakness");
		final Integer sl = row.intOrNull("slayer_level");
		if (sl != null)
		{
			table.slayerLevel = sl;
		}
		table.imageFile = row.str("image");
	}

	/**
	 * Converts a Bucket rarity string to a decimal probability in {@code (0, 1]}, or {@code -1} when
	 * not numerically interpretable ("Varies", named ranks, blank). "Always" → 1.0. Tolerates comma
	 * thousands, a leading {@code ~}, and a {@code N × 1/D} multi-roll prefix.
	 */
	public static double parseRarity(String raw)
	{
		if (raw == null)
		{
			return -1.0;
		}
		String s = raw.trim();
		if (s.isEmpty() || "Never".equalsIgnoreCase(s) || "Varies".equalsIgnoreCase(s))
		{
			return -1.0;
		}
		if ("Always".equalsIgnoreCase(s))
		{
			return 1.0;
		}
		if (s.startsWith("~"))
		{
			s = s.substring(1).trim();
		}
		double multiplier = 1.0;
		final Matcher mult = MULTI_ROLL.matcher(s);
		if (mult.matches())
		{
			try
			{
				multiplier = Double.parseDouble(mult.group(1));
				s = mult.group(2).trim();
			}
			catch (NumberFormatException ignored)
			{
			}
		}
		final int slash = s.indexOf('/');
		if (slash <= 0)
		{
			return -1.0;
		}
		try
		{
			final double num = Double.parseDouble(s.substring(0, slash).replace(",", "").trim());
			final double den = Double.parseDouble(s.substring(slash + 1).replace(",", "").trim());
			if (den <= 0.0)
			{
				return -1.0;
			}
			final double rate = multiplier * (num / den);
			return rate > 0.0 && rate <= 1.0 ? rate : -1.0;
		}
		catch (NumberFormatException e)
		{
			return -1.0;
		}
	}

	private static String str(JsonObject o, String key)
	{
		return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static int intVal(JsonObject o, String key)
	{
		if (!o.has(key) || o.get(key).isJsonNull())
		{
			return 0;
		}
		try
		{
			return o.get(key).getAsInt();
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
