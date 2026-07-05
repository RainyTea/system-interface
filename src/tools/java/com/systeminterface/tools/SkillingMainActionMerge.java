package com.systeminterface.tools;

import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Pure: replace a resource's {@code mainAction} with fresh generated fields; preserve {@code extraRolls}. */
public final class SkillingMainActionMerge
{
	private SkillingMainActionMerge() { }

	/** Keys the generator owns; on a legacy-flat resource these move under mainAction, the rest under extraRolls. */
	private static final List<String> GENERATED_KEYS = Arrays.asList(
		"source", "name", "itemId", "levelRequired", "xpPerAction", "objectIds", "npcIds", "method", "secondaries");

	public static void apply(JsonObject resource, JsonObject freshMainAction)
	{
		if (resource == null || freshMainAction == null) { return; }
		if (!resource.has("mainAction") && !resource.has("extraRolls"))
		{
			migrateFlat(resource);
		}
		resource.add("mainAction", freshMainAction);
		if (!resource.has("extraRolls"))
		{
			resource.add("extraRolls", new JsonObject());
		}
	}

	/** Splits a flat resource into {mainAction:{generated}, extraRolls:{everything else}}. */
	private static void migrateFlat(JsonObject flat)
	{
		final JsonObject extra = new JsonObject();
		for (Map.Entry<String, com.google.gson.JsonElement> e : flat.entrySet())
		{
			if (!GENERATED_KEYS.contains(e.getKey()))
			{
				extra.add(e.getKey(), e.getValue());
			}
		}
		for (String k : GENERATED_KEYS) { flat.remove(k); }
		for (String k : new java.util.ArrayList<>(extra.keySet())) { flat.remove(k); }
		flat.add("extraRolls", extra);
		// mainAction is set by the caller (apply) from freshMainAction.
	}
}
