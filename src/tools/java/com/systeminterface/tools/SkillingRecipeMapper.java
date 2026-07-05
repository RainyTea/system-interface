package com.systeminterface.tools;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Pure mapping of a wiki {@code recipe} {@code production_json} to the generated skilling fields. */
public final class SkillingRecipeMapper
{
	private SkillingRecipeMapper() { }

	public static final class GenFields
	{
		public final String name;
		public final int level;
		public final double xp;
		public final List<String> secondaryNames;
		public final String facilityName;
		public final String method;

		GenFields(String name, int level, double xp, List<String> secondaryNames, String facilityName, String method)
		{
			this.name = name;
			this.level = level;
			this.xp = xp;
			this.secondaryNames = secondaryNames;
			this.facilityName = facilityName;
			this.method = method;
		}
	}

	public static GenFields mapGathering(String recipeSkill, JsonObject productionJson)
	{
		if (productionJson == null) { return null; }
		final JsonObject skillEntry = skillEntry(productionJson, recipeSkill);
		if (skillEntry == null) { return null; }
		final String facility = stripLinks(str(productionJson, "facilities"));
		if (facility == null || facility.isEmpty() || "N/A".equalsIgnoreCase(facility)) { return null; }
		final int level = parseIntSafe(str(skillEntry, "level"));
		final double xp = parseDoubleSafe(str(skillEntry, "experience"));
		final List<String> secondaries = new ArrayList<>();
		if (productionJson.has("materials") && productionJson.get("materials").isJsonArray())
		{
			for (JsonElement m : productionJson.getAsJsonArray("materials"))
			{
				if (m.isJsonObject())
				{
					final String n = str(m.getAsJsonObject(), "name");
					if (n != null && !n.isEmpty()) { secondaries.add(n); }
				}
			}
		}
		final String method = methodFor(str(productionJson, "tools"), str(productionJson, "facilities"));
		return new GenFields(stripLinks(str(productionJson, "name")), level, xp, secondaries, facility, method);
	}

	public static String methodFor(String toolsField, String facilityField)
	{
		final String t = (toolsField == null ? "" : toolsField).toLowerCase();
		final String f = (facilityField == null ? "" : facilityField).toLowerCase();
		if (!f.contains("fishing spot")) { return null; }
		if (t.contains("harpoon")) { return "harpoon"; }
		if (t.contains("lobster pot")) { return "cage"; }
		if (t.contains("karambwan vessel")) { return "vessel"; }
		if (t.contains("fly fishing rod")) { return "lure"; }
		if (t.contains("fishing rod")) { return "bait"; }
		if (t.contains("big fishing net")) { return null; } // bignet excluded by decision
		if (t.contains("net")) { return "net"; }
		return null;
	}

	private static JsonObject skillEntry(JsonObject pj, String skill)
	{
		if (!pj.has("skills") || !pj.get("skills").isJsonArray()) { return null; }
		for (JsonElement e : pj.getAsJsonArray("skills"))
		{
			if (e.isJsonObject() && skill.equalsIgnoreCase(str(e.getAsJsonObject(), "name")))
			{
				return e.getAsJsonObject();
			}
		}
		return null;
	}

	private static String str(JsonObject o, String key)
	{
		return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
	}

	private static String stripLinks(String s)
	{
		if (s == null) { return null; }
		String out = s.replaceAll("\\[\\[[^\\]|]*\\|", "").replace("[[", "").replace("]]", "");
		return out.replaceAll("<[^>]*>", "").trim();
	}

	private static int parseIntSafe(String s)
	{
		try { return s == null ? 0 : Integer.parseInt(s.trim()); }
		catch (NumberFormatException e) { return 0; }
	}

	private static double parseDoubleSafe(String s)
	{
		try { return s == null ? 0.0 : Double.parseDouble(s.trim()); }
		catch (NumberFormatException e) { return 0.0; }
	}
}
