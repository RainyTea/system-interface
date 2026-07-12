package com.systeminterface.services.lore;

import com.systeminterface.services.wiki.BucketRow;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure mapping of an OSRS-Wiki {@code infobox_npc} Bucket row onto {@link NpcLore}, including
 * light wikitext stripping ({@code [[A|B]]} → B, {@code [[A]]} → A, bullet/span removal). No
 * HTTP, no game state.
 */
public final class NpcLoreMapper
{
	private NpcLoreMapper()
	{
	}

	/** Maps one infobox_npc row, or null for a null row. */
	public static NpcLore map(BucketRow row)
	{
		if (row == null)
		{
			return null;
		}
		final String examine = row.str("examine");
		final String location = truncateAtParen(stripWikitext(row.str("location")));
		final List<String> quests = questList(row.str("quest"));
		String image = row.str("image");
		if (image != null && image.startsWith("File:"))
		{
			image = image.substring("File:".length());
		}
		return new NpcLore(examine, location, quests, image);
	}

	/** Exact page_name match preferred (Cook → "Cook" over "Cook (Lumbridge)"); else first row. */
	public static BucketRow pickBest(List<BucketRow> rows, String npcName)
	{
		if (rows == null || rows.isEmpty())
		{
			return null;
		}
		for (BucketRow r : rows)
		{
			if (r != null && npcName != null && npcName.equalsIgnoreCase(r.str("page_name")))
			{
				return r;
			}
		}
		return rows.get(0);
	}

	/**
	 * Strips wiki links and markup: {@code [[A|B]]} → B, {@code [[A]]} → A, HTML tags,
	 * {@code '''} bold marks and {@code &bull;} entities removed, whitespace collapsed.
	 * Also drops leaked MediaWiki {@code UNIQ--...-QINU} strip markers and treats newline-{@code *}
	 * bullets the same as {@code &bull;} spans, both becoming the {@code •} split marker.
	 */
	public static String stripWikitext(String s)
	{
		if (s == null)
		{
			return null;
		}
		String out = s.replace("&amp;", "&"); // FIRST: un-double-encode so later entity decodes see the real entity
		// MediaWiki strip markers are wrapped in DEL (0x7f) control chars, which String.trim()
		// does not remove — drop all ASCII control chars so no invisible residue survives.
		out = out.replaceAll("[\\x00-\\x1f\\x7f]", " ");
		// MediaWiki strip markers (<nowiki> leftovers) leak through the bucket raw — drop them.
		out = out.replaceAll("'?\"?`?UNIQ--[a-zA-Z]+-[0-9A-Fa-f]+-QINU`?\"?'?", "");
		out = out.replaceAll("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]", "$2");
		out = out.replaceAll("\\[\\[([^\\]]*)\\]\\]", "$1");
		out = out.replaceAll("<[^>]*>", "");
		// Both bullet forms the wiki emits (&bull; spans and newline-* lists) become the split marker.
		out = out.replace("'''", "").replace("&bull;", "•").replace("*", "•");
		out = out.replace("&nbsp;", " ").replace("&quot;", "\"")
			.replace("&#39;", "'").replace("&#91;", "[").replace("&#93;", "]");
		out = out.replaceAll("\\s+", " ").trim();
		return out.isEmpty() ? null : out;
	}

	/** Drops a trailing parenthetical annotation ("(2nd floor... bank)") — secondary detail
	 *  and the escape hatch for floor-numbering template junk the wiki emits raw. */
	private static String truncateAtParen(String s)
	{
		if (s == null)
		{
			return null;
		}
		final int p = s.indexOf('(');
		final String out = (p >= 0 ? s.substring(0, p) : s).trim();
		return out.isEmpty() ? null : out;
	}

	/**
	 * Quest names from the raw {@code quest} field. Wiki quest lists always link their entries,
	 * so links are extracted directly — immune to the bullet-format zoo seen in the wild
	 * (&bull; spans, newline-* lists, HTML <ul><li>). Plain-text fields (no links) fall back to
	 * the bullet-split pipeline. Never null.
	 */
	static List<String> questList(String questField)
	{
		final List<String> quests = new ArrayList<>();
		if (questField == null)
		{
			return quests;
		}
		final java.util.regex.Matcher m = java.util.regex.Pattern
			.compile("\\[\\[([^\\]|]*)(?:\\|([^\\]]*))?\\]\\]").matcher(questField);
		while (m.find())
		{
			final String name = (m.group(2) != null ? m.group(2) : m.group(1)).trim();
			if (!name.isEmpty())
			{
				quests.add(name);
			}
		}
		if (!quests.isEmpty())
		{
			return quests;
		}
		final String stripped = stripWikitext(questField);
		if (stripped == null)
		{
			return quests;
		}
		for (String part : stripped.split("•"))
		{
			final String q = part.trim();
			if (!q.isEmpty())
			{
				quests.add(q);
			}
		}
		return quests;
	}
}
