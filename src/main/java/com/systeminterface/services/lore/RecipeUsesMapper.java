package com.systeminterface.services.lore;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.systeminterface.services.wiki.BucketRow;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Pure mapping of OSRS-Wiki {@code recipe} Bucket rows onto {@link UseEntry}: parses the row's
 * {@code production_json} (a JSON string, like dropsline's {@code drop_json}). No HTTP, no game
 * state — trivially unit-testable, safe on any thread.
 */
@Slf4j
public final class RecipeUsesMapper
{
	private RecipeUsesMapper()
	{
	}

	/** Maps one recipe row, or null when the row/production_json is absent or malformed. */
	public static UseEntry mapUse(BucketRow row, Gson gson)
	{
		if (row == null)
		{
			return null;
		}
		final String pageName = row.str("page_name");
		final String prodJson = row.str("production_json");
		if (prodJson == null)
		{
			return null;
		}
		final JsonObject pj;
		try
		{
			pj = gson.fromJson(prodJson, JsonObject.class);
		}
		catch (RuntimeException e)
		{
			log.debug("Bad production_json for '{}'", pageName, e);
			return null;
		}
		if (pj == null)
		{
			return null;
		}
		String output = null;
		if (pj.has("output") && pj.get("output").isJsonObject())
		{
			final JsonObject out = pj.getAsJsonObject("output");
			output = strVal(out, "name");
		}
		if (output == null || output.isEmpty())
		{
			output = pageName;
		}
		if (output == null || output.isEmpty())
		{
			return null;
		}
		String facility = strVal(pj, "facilities");
		if (facility == null)
		{
			facility = strVal(pj, "tools");
		}
		String skill = null;
		Integer level = null;
		Integer xp = null;
		if (pj.has("skills") && pj.get("skills").isJsonArray())
		{
			final JsonArray skills = pj.getAsJsonArray("skills");
			if (skills.size() > 0 && skills.get(0).isJsonObject())
			{
				final JsonObject s = skills.get(0).getAsJsonObject();
				skill = strVal(s, "name");
				level = intVal(s, "level");
				xp = intVal(s, "experience");
			}
		}
		return new UseEntry(output, facility, skill, level, xp);
	}

	/**
	 * The single most-direct use: lowest required level wins; entries without a skill rank after
	 * every skilled entry; ties keep the first-listed. Null for no uses.
	 */
	public static UseEntry bestUse(List<UseEntry> uses)
	{
		if (uses == null || uses.isEmpty())
		{
			return null;
		}
		UseEntry best = null;
		for (UseEntry u : uses)
		{
			if (u == null)
			{
				continue;
			}
			if (best == null)
			{
				best = u;
				continue;
			}
			final boolean uSkilled = u.getLevel() != null;
			final boolean bestSkilled = best.getLevel() != null;
			if (uSkilled && (!bestSkilled || u.getLevel() < best.getLevel()))
			{
				best = u;
			}
		}
		return best;
	}

	private static String strVal(JsonObject o, String key)
	{
		if (!o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonPrimitive())
		{
			return null;
		}
		final String v = o.get(key).getAsString();
		return v.isEmpty() ? null : v;
	}

	private static Integer intVal(JsonObject o, String key)
	{
		final String v = strVal(o, key);
		if (v == null)
		{
			return null;
		}
		try
		{
			return (int) Double.parseDouble(v.replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
