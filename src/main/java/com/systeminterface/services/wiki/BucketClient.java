package com.systeminterface.services.wiki;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Generic OSRS-Wiki Bucket query client ({@code api.php?action=bucket}). Runs queries on the OkHttp
 * pool (never the client thread) and parses the {@code {"bucket":[...]}} / {@code {"error":...}}
 * envelope into typed {@link BucketRow}s. Reusable across data slices (drops, skilling, appraise).
 */
@Slf4j
@Singleton
public final class BucketClient
{
	private static final String API_BASE = "https://oldschool.runescape.wiki/api.php";

	private final OkHttpClient http;
	private final Gson gson;

	@Inject
	public BucketClient(OkHttpClient http, Gson gson)
	{
		this.http = http;
		this.gson = gson;
	}

	/** Runs {@code query} asynchronously; delivers parsed rows to {@code onResult}, or invokes {@code onError}. */
	public void query(BucketQuery query, Consumer<List<BucketRow>> onResult, Runnable onError)
	{
		final HttpUrl base = HttpUrl.parse(API_BASE);
		if (base == null)
		{
			onError.run();
			return;
		}
		final HttpUrl url = base.newBuilder()
			.addQueryParameter("action", "bucket")
			.addQueryParameter("format", "json")
			.addQueryParameter("query", query.toQueryString())
			.build();
		final Request req = new Request.Builder().url(url).header("Accept", "application/json").build();
		http.newCall(req).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("Bucket query failed: {}", url, e);
				onError.run();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (ResponseBody body = response.body())
				{
					if (!response.isSuccessful() || body == null)
					{
						onError.run();
						return;
					}
					final BucketResult result = parse(body.string(), gson);
					if (result.ok)
					{
						onResult.accept(result.rows);
					}
					else
					{
						log.debug("Bucket error for {}: {}", url, result.error);
						onError.run();
					}
				}
				catch (IOException e)
				{
					log.debug("Bucket read error", e);
					onError.run();
				}
			}
		});
	}

	/** Parses the Bucket envelope. Pure — unit-tested. */
	static BucketResult parse(String json, Gson gson)
	{
		try
		{
			final JsonObject root = gson.fromJson(json, JsonObject.class);
			if (root == null)
			{
				return BucketResult.error("null response");
			}
			if (root.has("error") && !root.get("error").isJsonNull())
			{
				return BucketResult.error(root.get("error").getAsString());
			}
			final List<BucketRow> rows = new ArrayList<>();
			if (root.has("bucket") && root.get("bucket").isJsonArray())
			{
				for (JsonElement el : root.getAsJsonArray("bucket"))
				{
					if (el.isJsonObject())
					{
						rows.add(new BucketRow(el.getAsJsonObject()));
					}
				}
			}
			return BucketResult.ok(rows);
		}
		catch (RuntimeException e)
		{
			return BucketResult.error("parse failure: " + e.getMessage());
		}
	}

	static final class BucketResult
	{
		final boolean ok;
		final List<BucketRow> rows;
		final String error;

		private BucketResult(boolean ok, List<BucketRow> rows, String error)
		{
			this.ok = ok;
			this.rows = rows;
			this.error = error;
		}

		static BucketResult ok(List<BucketRow> rows)
		{
			return new BucketResult(true, rows, null);
		}

		static BucketResult error(String e)
		{
			return new BucketResult(false, Collections.emptyList(), e);
		}
	}
}
