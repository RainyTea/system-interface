package com.systeminterface.services.drops;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stateless parser that converts OSRS Wiki wikitext and rendered HTML into
 * {@link DropTable} instances. Extracted from {@link LootTables} so the
 * registry/HTTP concerns stay separate from the parsing logic.
 *
 * <p>All public methods are static and side-effect-free — safe to call from
 * any thread and trivially testable without mocks.
 */
public final class WikiDropTableParser
{
	private WikiDropTableParser()
	{
	}

	/**
	 * Bumped whenever the wikitext parser improves. Cached wiki tables carrying an
	 * older schema are transparently re-fetched by {@link LootTables#forTarget},
	 * so parser fixes reach users without them having to delete the cache.
	 */
	public static final int CURRENT_SCHEMA = 6;

	// ---------------------------------------------------------------------
	// Regex constants
	// ---------------------------------------------------------------------

	/**
	 * Matches a drop row's image alt text in the rendered page HTML.
	 * Captures forbid {@code < > "} and line breaks so a match can't leak
	 * out of the {@code alt="..."} attribute.
	 */
	private static final Pattern DROP_ALT_PATTERN = Pattern.compile(
		"drops ([^<>\"\\r\\n]+?) with rarity ([^<>\"\\r\\n]+?) in quantity");

	private static final Pattern HITPOINTS_PATTERN = Pattern.compile(
		"\\|\\s*hitpoints\\d*\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern COMBAT_LEVEL_PATTERN = Pattern.compile(
		"\\|\\s*combat\\d*\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern MAX_HIT_PATTERN = Pattern.compile(
		"\\|\\s*max hit\\d*\\s*=\\s*([^\\|\\n]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern ATTACK_STYLE_PATTERN = Pattern.compile(
		"\\|\\s*attack style\\d*\\s*=\\s*([^\\|\\n]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern WEAKNESS_PATTERN = Pattern.compile(
		"\\|\\s*weakness\\d*\\s*=\\s*([^\\|\\n]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern EXAMINE_PATTERN = Pattern.compile(
		"\\|\\s*examine\\d*\\s*=\\s*([^\\|\\n]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern IMAGE_PATTERN = Pattern.compile(
		"\\|\\s*image\\d*\\s*=\\s*\\[\\[\\s*[Ff]ile:\\s*([^\\]|\\n]+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern AGGRESSIVE_PATTERN = Pattern.compile(
		"\\|\\s*aggressive\\d*\\s*=\\s*(yes|no)", Pattern.CASE_INSENSITIVE);
	private static final Pattern SLAYER_LVL_PATTERN = Pattern.compile(
		"\\|\\s*slaylvl\\d*\\s*=\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
	private static final Pattern MEMBERS_PATTERN = Pattern.compile(
		"\\|\\s*members\\d*\\s*=\\s*(yes|no)", Pattern.CASE_INSENSITIVE);
	private static final Pattern QUEST_ONLY_PATTERN = Pattern.compile(
		"(?i)\\bonly\\b[^|]{0,80}\\b(?:during|while)\\b");

	// ---------------------------------------------------------------------
	// Public API
	// ---------------------------------------------------------------------

	/**
	 * Parses every {@code {{DropsLine|...}}} template out of {@code wikitext}
	 * and returns a {@link DropTable} for {@code targetName} containing every
	 * entry with a numerically-parseable rarity. Entries with named rarities
	 * (Common / Uncommon / Rare) are skipped. Visible for testing.
	 */
	static DropTable parseDropsFromWikitext(String targetName, String wikitext)
	{
		if (wikitext == null || wikitext.isEmpty())
		{
			return null;
		}
		final DropTable table = newTable(targetName);
		parseInfobox(table, wikitext);
		for (String params : extractDropsLineParams(wikitext))
		{
			DropTable.Entry entry = parseDropsLineParams(params);
			if (entry != null)
			{
				table.drops.add(entry);
			}
		}
		removeQuestOnlyDrops(table, wikitext);
		return table.drops.isEmpty() ? null : table;
	}

	/**
	 * Builds a table from the wiki page: infobox stats from {@code wikitext}, and
	 * the complete drop list scraped from the rendered {@code html} (which has all
	 * transcluded sub-tables already expanded). Falls back to inline
	 * {@code {{DropsLine}}} parsing when no HTML is available or it yields nothing.
	 */
	static DropTable parseWikiPage(String targetName, String wikitext, String html)
	{
		if (wikitext == null || wikitext.isEmpty())
		{
			return null;
		}
		final DropTable table = newTable(targetName);
		parseInfobox(table, wikitext);

		final List<DropTable.Entry> htmlDrops = parseDropsFromHtml(html);
		if (!htmlDrops.isEmpty())
		{
			table.drops.addAll(htmlDrops);
		}
		else
		{
			for (String params : extractDropsLineParams(wikitext))
			{
				DropTable.Entry entry = parseDropsLineParams(params);
				if (entry != null)
				{
					table.drops.add(entry);
				}
			}
		}
		removeQuestOnlyDrops(table, wikitext);
		return table.drops.isEmpty() ? null : table;
	}

	/**
	 * Scrapes every drop row from the rendered drops-table HTML. Each row's image
	 * carries an alt of the form {@code "...drops <NAME> with rarity <RARITY> in
	 * quantity <QTY>"}. Visible for testing.
	 */
	static List<DropTable.Entry> parseDropsFromHtml(String html)
	{
		final List<DropTable.Entry> out = new ArrayList<>();
		if (html == null || html.isEmpty())
		{
			return out;
		}
		final Matcher m = DROP_ALT_PATTERN.matcher(html);
		while (m.find())
		{
			final String name = htmlUnescape(m.group(1).trim());
			final String rarity = htmlUnescape(m.group(2).trim())
				.replaceAll("^\\s*\\d+\\s*(?:\\u00d7|&#215;|&times;|x|X)\\s*", "");
			final double rate = parseRarity(rarity);
			if (rate > 0.0 && !name.isEmpty())
			{
				final DropTable.Entry e = new DropTable.Entry();
				e.name = name;
				e.rate = rate;
				out.add(e);
			}
		}
		return out;
	}

	/**
	 * Extracts the inner parameter string of every {@code {{DropsLine|...}}} /
	 * {@code {{DropsLineClue|...}}} template via brace-matching. Tolerates
	 * nested templates. Visible for testing.
	 */
	static List<String> extractDropsLineParams(String wikitext)
	{
		final List<String> out = new ArrayList<>();
		if (wikitext == null)
		{
			return out;
		}
		final int len = wikitext.length();
		int i = 0;
		while (i < len)
		{
			final int open = wikitext.indexOf("{{", i);
			if (open < 0)
			{
				break;
			}
			int depth = 0;
			int j = open;
			int close = -1;
			while (j < len - 1)
			{
				if (wikitext.charAt(j) == '{' && wikitext.charAt(j + 1) == '{')
				{
					depth++;
					j += 2;
				}
				else if (wikitext.charAt(j) == '}' && wikitext.charAt(j + 1) == '}')
				{
					depth--;
					j += 2;
					if (depth == 0)
					{
						close = j;
						break;
					}
				}
				else
				{
					j++;
				}
			}
			if (close < 0)
			{
				break;
			}
			final String block = wikitext.substring(open + 2, close - 2);
			final int bar = block.indexOf('|');
			if (bar > 0)
			{
				final String tpl = block.substring(0, bar).trim();
				if (tpl.equals("DropsLine") || tpl.equals("DropsLineClue"))
				{
					out.add(block.substring(bar + 1));
				}
			}
			i = open + 2;
		}
		return out;
	}

	/**
	 * Parses the inner {@code key=value|key=value} body of a
	 * {@code {{DropsLine|...}}} template. Returns null when name or rarity is
	 * missing/unparseable. Visible for testing.
	 */
	static DropTable.Entry parseDropsLineParams(String paramsBlock)
	{
		final Map<String, String> kv = parseDropsLineKv(paramsBlock);
		final String name = dropNameFromKv(kv);
		final String rarityStr = kv.get("rarity");
		if (name == null || name.isEmpty() || rarityStr == null || rarityStr.isEmpty())
		{
			return null;
		}
		final double rate = parseRarity(rarityStr);
		if (rate <= 0.0)
		{
			return null;
		}
		final DropTable.Entry e = new DropTable.Entry();
		e.name = name;
		e.rate = rate;
		return e;
	}

	/**
	 * Converts a wiki rarity string to a decimal probability in {@code (0, 1]},
	 * or {@code -1} when the string cannot be interpreted numerically. Visible for testing.
	 */
	static double parseRarity(String raw)
	{
		if (raw == null)
		{
			return -1.0;
		}
		String s = raw.trim();
		if (s.isEmpty() || "Never".equalsIgnoreCase(s))
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
		final Matcher mult = Pattern.compile("^(\\d+)\\s*[\\u00d7x]\\s*(.+)$").matcher(s);
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

	/**
	 * Item names flagged as obtainable only during/while on a quest or event.
	 * Names are lowercased. Visible for testing.
	 */
	static java.util.Set<String> extractQuestOnlyDropNames(String wikitext)
	{
		final java.util.Set<String> out = new java.util.HashSet<>();
		if (wikitext == null)
		{
			return out;
		}
		for (String block : extractDropsLineParams(wikitext))
		{
			if (!QUEST_ONLY_PATTERN.matcher(block).find())
			{
				continue;
			}
			final String name = dropNameFromKv(parseDropsLineKv(block));
			if (name != null && !name.isEmpty())
			{
				out.add(name.toLowerCase());
			}
		}
		return out;
	}

	// ---------------------------------------------------------------------
	// Internal helpers
	// ---------------------------------------------------------------------

	private static DropTable newTable(String targetName)
	{
		final DropTable table = new DropTable();
		table.target = targetName;
		table.schema = CURRENT_SCHEMA;
		table.drops = new ArrayList<>();
		return table;
	}

	private static void parseInfobox(DropTable table, String wikitext)
	{
		final Matcher hpMatcher = HITPOINTS_PATTERN.matcher(wikitext);
		if (hpMatcher.find())
		{
			try { table.maxHp = Integer.parseInt(hpMatcher.group(1)); }
			catch (NumberFormatException ignored) { }
		}
		final Matcher clMatcher = COMBAT_LEVEL_PATTERN.matcher(wikitext);
		if (clMatcher.find())
		{
			try { table.combatLevel = Integer.parseInt(clMatcher.group(1)); }
			catch (NumberFormatException ignored) { }
		}
		table.maxHit = extractField(MAX_HIT_PATTERN, wikitext);
		table.attackStyle = extractField(ATTACK_STYLE_PATTERN, wikitext);
		table.weakness = extractField(WEAKNESS_PATTERN, wikitext);
		table.examine = extractField(EXAMINE_PATTERN, wikitext);
		final Matcher imgMatcher = IMAGE_PATTERN.matcher(wikitext);
		if (imgMatcher.find())
		{
			table.imageFile = imgMatcher.group(1).trim();
		}
		final Matcher aggMatcher = AGGRESSIVE_PATTERN.matcher(wikitext);
		if (aggMatcher.find())
		{
			table.aggressive = "yes".equalsIgnoreCase(aggMatcher.group(1));
		}
		final Matcher slayMatcher = SLAYER_LVL_PATTERN.matcher(wikitext);
		if (slayMatcher.find())
		{
			try { table.slayerLevel = Integer.parseInt(slayMatcher.group(1)); }
			catch (NumberFormatException ignored) { }
		}
		final Matcher memMatcher = MEMBERS_PATTERN.matcher(wikitext);
		if (memMatcher.find())
		{
			table.members = "yes".equalsIgnoreCase(memMatcher.group(1));
		}
	}

	private static String extractField(Pattern pattern, String text)
	{
		Matcher m = pattern.matcher(text);
		if (m.find())
		{
			String val = m.group(1).trim();
			val = val.replaceAll("\\[\\[([^\\]|]+)(\\|[^\\]]*)?]]", "$1");
			val = val.replaceAll("\\{\\{[^}]*}}", "").trim();
			return val.isEmpty() ? null : val;
		}
		return null;
	}

	private static Map<String, String> parseDropsLineKv(String paramsBlock)
	{
		final Map<String, String> kv = new HashMap<>();
		for (String pair : paramsBlock.split("\\|"))
		{
			final int eq = pair.indexOf('=');
			if (eq <= 0)
			{
				continue;
			}
			kv.put(pair.substring(0, eq).trim().toLowerCase(), pair.substring(eq + 1).trim());
		}
		return kv;
	}

	private static String dropNameFromKv(Map<String, String> kv)
	{
		String name = kv.get("name");
		if ((name == null || name.isEmpty()) && kv.containsKey("type"))
		{
			final String type = kv.get("type").trim();
			if (!type.isEmpty())
			{
				name = "Clue scroll (" + type.toLowerCase() + ")";
			}
		}
		return name;
	}

	private static void removeQuestOnlyDrops(DropTable table, String wikitext)
	{
		final java.util.Set<String> questOnly = extractQuestOnlyDropNames(wikitext);
		if (!questOnly.isEmpty())
		{
			table.drops.removeIf(e -> e.getName() != null && questOnly.contains(e.getName().toLowerCase()));
		}
	}

	private static String htmlUnescape(String s)
	{
		return s.replace("&amp;", "&").replace("&#39;", "'").replace("&#039;", "'")
			.replace("&quot;", "\"").replace("&lt;", "<").replace("&gt;", ">");
	}
}
