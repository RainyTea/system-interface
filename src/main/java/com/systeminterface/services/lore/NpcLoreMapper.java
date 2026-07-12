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
		final String location = stripWikitext(row.str("location"));
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
	 */
	public static String stripWikitext(String s)
	{
		if (s == null)
		{
			return null;
		}
		String out = s.replaceAll("\\[\\[([^\\]|]*)\\|([^\\]]*)\\]\\]", "$2");
		out = out.replaceAll("\\[\\[([^\\]]*)\\]\\]", "$1");
		out = out.replaceAll("<[^>]*>", "");
		out = out.replace("'''", "").replace("&bull;", "•"); // bullet survives as a split marker
		out = out.replaceAll("\\s+", " ").trim();
		return out.isEmpty() ? null : out;
	}

	/** Splits the {@code quest} field's bullet list into clean quest names. Never null. */
	static List<String> questList(String questField)
	{
		final List<String> quests = new ArrayList<>();
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
