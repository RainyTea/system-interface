package com.systeminterface.services.lookup;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses object examine text from OSRS Wiki scenery/resource pages.
 *
 * The parser only returns text when the requested object id appears in the
 * page's infobox id fields. That keeps generic names such as "Tree" or "Rocks"
 * from becoming broad, unsafe fallbacks.
 */
final class WikiObjectExamineParser
{
	private static final Pattern FIELD_PATTERN = Pattern.compile(
		"(?m)^\\|\\s*([A-Za-z0-9 _-]+)\\s*=\\s*(.*?)\\s*$");
	private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
	private static final Pattern LINK_PATTERN = Pattern.compile("\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]");

	private WikiObjectExamineParser()
	{
	}

	static String parseExamineForObject(String wikitext, int objectId)
	{
		if (wikitext == null || wikitext.isEmpty() || objectId < 0)
		{
			return null;
		}
		final Map<String, String> fields = fields(wikitext);
		String fallbackExamine = null;
		for (Map.Entry<String, String> entry : fields.entrySet())
		{
			final String key = entry.getKey();
			if (!key.startsWith("id") || !containsObjectId(entry.getValue(), objectId))
			{
				continue;
			}
			final String suffix = key.substring(2);
			String examine = fields.get("examine" + suffix);
			if (examine == null)
			{
				examine = fields.get("examine");
			}
			if (examine != null)
			{
				return cleanWikiText(examine);
			}
			fallbackExamine = fields.get("examine");
		}
		return cleanWikiText(fallbackExamine);
	}

	static String parseExamineForPageName(String wikitext, String objectName, String pageTitle)
	{
		if (wikitext == null || wikitext.isEmpty())
		{
			return null;
		}
		final Map<String, String> fields = fields(wikitext);
		if (!sameNormalized(objectName, fields.get("name")) && !sameNormalized(objectName, pageTitle))
		{
			return null;
		}
		final String generic = cleanWikiText(fields.get("examine"));
		if (generic != null)
		{
			return generic;
		}
		return firstVersionedExamine(fields);
	}

	private static Map<String, String> fields(String wikitext)
	{
		final Map<String, String> out = new HashMap<>();
		final Matcher matcher = FIELD_PATTERN.matcher(wikitext);
		while (matcher.find())
		{
			final String key = matcher.group(1).trim().toLowerCase(Locale.ROOT).replace(" ", "");
			final String value = matcher.group(2).trim();
			if (!key.isEmpty() && !value.isEmpty())
			{
				out.put(key, value);
			}
		}
		return out;
	}

	private static String firstVersionedExamine(Map<String, String> fields)
	{
		final List<String> keys = new ArrayList<>();
		for (String key : fields.keySet())
		{
			if (key.startsWith("examine") && key.length() > "examine".length())
			{
				keys.add(key);
			}
		}
		keys.sort(Comparator.comparingInt(WikiObjectExamineParser::fieldSuffixNumber));
		for (String key : keys)
		{
			final String examine = cleanWikiText(fields.get(key));
			if (examine != null)
			{
				return examine;
			}
		}
		return null;
	}

	private static int fieldSuffixNumber(String key)
	{
		try
		{
			return Integer.parseInt(key.substring("examine".length()));
		}
		catch (NumberFormatException e)
		{
			return Integer.MAX_VALUE;
		}
	}

	private static boolean sameNormalized(String a, String b)
	{
		final String cleanA = normalizeName(a);
		final String cleanB = normalizeName(b);
		return !cleanA.isEmpty() && cleanA.equals(cleanB);
	}

	private static String normalizeName(String value)
	{
		return value == null ? "" : value.replace('_', ' ')
			.trim()
			.toLowerCase(Locale.ROOT)
			.replaceAll("\\s+", " ");
	}

	private static boolean containsObjectId(String text, int objectId)
	{
		final Matcher matcher = NUMBER_PATTERN.matcher(text == null ? "" : text);
		while (matcher.find())
		{
			try
			{
				if (Integer.parseInt(matcher.group()) == objectId)
				{
					return true;
				}
			}
			catch (NumberFormatException ignored)
			{
				// Regex is numeric, but keep this defensive for very large values.
			}
		}
		return false;
	}

	private static String cleanWikiText(String text)
	{
		if (text == null)
		{
			return null;
		}
		String clean = text.trim();
		if (clean.isEmpty())
		{
			return null;
		}
		clean = clean.replaceAll("<ref[^>]*>.*?</ref>", "");
		clean = clean.replaceAll("<ref[^/]*/>", "");
		clean = clean.replaceAll("'''?", "");
		clean = replaceLinks(clean);
		clean = clean.replaceAll("\\{\\{[^}]*}}", "").trim();
		clean = clean.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&#039;", "'");
		return ObjectExamineService.cleanExamine(clean);
	}

	private static String replaceLinks(String text)
	{
		final Matcher matcher = LINK_PATTERN.matcher(text);
		final StringBuffer out = new StringBuffer();
		while (matcher.find())
		{
			final String label = matcher.group(2) == null ? matcher.group(1) : matcher.group(2);
			matcher.appendReplacement(out, Matcher.quoteReplacement(label));
		}
		matcher.appendTail(out);
		return out.toString();
	}
}
