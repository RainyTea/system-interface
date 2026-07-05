package com.systeminterface.services.wiki;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** One Bucket result row. Some columns are JSON arrays even for single values; accessors take the first element. */
public final class BucketRow
{
	private final JsonObject obj;

	public BucketRow(JsonObject obj)
	{
		this.obj = obj;
	}

	/** The field's string value (first element if the field is an array), or null. */
	public String str(String field)
	{
		final JsonElement el = first(field);
		return el == null || el.isJsonNull() ? null : el.getAsString();
	}

	public Integer intOrNull(String field)
	{
		final JsonElement el = first(field);
		if (el == null || el.isJsonNull())
		{
			return null;
		}
		try
		{
			return el.getAsInt();
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	public boolean bool(String field, boolean def)
	{
		final JsonElement el = first(field);
		if (el == null || el.isJsonNull())
		{
			return def;
		}
		try
		{
			return el.getAsBoolean();
		}
		catch (RuntimeException e)
		{
			return def;
		}
	}

	private JsonElement first(String field)
	{
		if (obj == null || !obj.has(field))
		{
			return null;
		}
		final JsonElement el = obj.get(field);
		if (el.isJsonArray())
		{
			final JsonArray arr = el.getAsJsonArray();
			return arr.size() == 0 ? null : arr.get(0);
		}
		return el;
	}
}
