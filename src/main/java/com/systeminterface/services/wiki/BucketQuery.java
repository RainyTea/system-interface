package com.systeminterface.services.wiki;

import java.util.ArrayList;
import java.util.List;

/** Builds an OSRS-Wiki Bucket query string: {@code bucket('x').select(...).where(...).limit(N).run()}. */
public final class BucketQuery
{
	private final String bucket;
	private final List<String> selects = new ArrayList<>();
	private final List<String[]> wheres = new ArrayList<>();
	private Integer limit;

	public BucketQuery(String bucket)
	{
		this.bucket = bucket;
	}

	public BucketQuery select(String... fields)
	{
		for (String f : fields)
		{
			selects.add(f);
		}
		return this;
	}

	public BucketQuery where(String field, String value)
	{
		wheres.add(new String[]{field, value});
		return this;
	}

	public BucketQuery limit(int n)
	{
		this.limit = n;
		return this;
	}

	public String toQueryString()
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("bucket('").append(esc(bucket)).append("')");
		if (!selects.isEmpty())
		{
			sb.append(".select(");
			for (int i = 0; i < selects.size(); i++)
			{
				if (i > 0)
				{
					sb.append(',');
				}
				sb.append('\'').append(esc(selects.get(i))).append('\'');
			}
			sb.append(')');
		}
		for (String[] w : wheres)
		{
			sb.append(".where('").append(esc(w[0])).append("','").append(esc(w[1])).append("')");
		}
		if (limit != null)
		{
			sb.append(".limit(").append(limit).append(')');
		}
		sb.append(".run()");
		return sb.toString();
	}

	/** Escapes for the single-quoted query mini-language: backslash first, then apostrophe. */
	private static String esc(String s)
	{
		return s == null ? "" : s.replace("\\", "\\\\").replace("'", "\\'");
	}
}
