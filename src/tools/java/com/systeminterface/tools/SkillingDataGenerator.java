package com.systeminterface.tools;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.systeminterface.services.wiki.BucketClient;
import com.systeminterface.services.wiki.BucketQuery;
import com.systeminterface.services.wiki.BucketRow;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;

/**
 * Dev-only tool: refreshes each resource's {@code mainAction} in the bundled ResourceData.json from
 * the OSRS Wiki Bucket API, never touching {@code extraRolls} / skill-level {@code petBaseChance} /
 * {@code rewards}. Run via {@code ./gradlew generateResourceData}. NOT shipped (lives in {@code tools}).
 */
public final class SkillingDataGenerator
{
	private static final Path OUT = Paths.get(
		"src/main/resources/com/systeminterface/modules/skills/ResourceData.json");
	private static final String[] SKILLS = { "Woodcutting", "Mining", "Fishing" };

	private final BucketClient bucket = new BucketClient(new OkHttpClient(), new Gson());
	private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	public static void main(String[] args) throws Exception { new SkillingDataGenerator().run(); }

	private void run() throws Exception
	{
		final JsonObject root = gson.fromJson(
			new String(Files.readAllBytes(OUT), StandardCharsets.UTF_8), JsonObject.class);
		final List<String> notes = new ArrayList<>();
		int refreshed = 0;

		for (String skill : SKILLS)
		{
			final String key = skill.toLowerCase();
			if (!root.has(key)) { notes.add("SKIP: no '" + key + "' block"); continue; }
			final JsonArray resources = root.getAsJsonObject(key).getAsJsonArray("resources");

			for (BucketRow row : query(new BucketQuery("recipe")
				.select("page_name", "uses_skill", "production_json").where("uses_skill", skill).limit(500)))
			{
				final String pjStr = row.str("production_json");
				if (pjStr == null) { continue; }
				final SkillingRecipeMapper.GenFields g =
					SkillingRecipeMapper.mapGathering(skill, gson.fromJson(pjStr, JsonObject.class));
				if (g == null) { continue; }

				final String outputName = row.str("page_name");
				final int itemId = resolveItemId(outputName);
				if (itemId <= 0) { notes.add("UNRESOLVED item id: " + outputName); continue; }

				// Match the existing resource FIRST so curated ids can be unioned in — the wiki
				// under-resolves generic facilities (e.g. "Logs" → [[Tree]] misses most tree variants),
				// so wiki-resolved ids must never replace curated ones, only extend them.
				final JsonObject existing = findByItemId(resources, itemId);

				final JsonObject mainAction = new JsonObject();
				mainAction.addProperty("source", "recipe");
				mainAction.addProperty("name", outputName);
				mainAction.addProperty("itemId", itemId);
				mainAction.addProperty("levelRequired", g.level);
				mainAction.addProperty("xpPerAction", g.xp);
				addIds(mainAction, "objectIds",
					unionIds(existingIds(existing, "objectIds"), resolveNodeIds(g.facilityName, false)));
				addIds(mainAction, "npcIds",
					unionIds(existingIds(existing, "npcIds"), resolveNodeIds(g.facilityName, true)));
				if (g.method != null) { mainAction.addProperty("method", g.method); }
				addIds(mainAction, "secondaries",
					unionIds(existingIds(existing, "secondaries"), resolveSecondaryIds(g.secondaryNames)));

				if (existing != null)
				{
					SkillingMainActionMerge.apply(existing, mainAction);
				}
				else
				{
					final JsonObject added = new JsonObject();
					added.add("mainAction", mainAction);
					added.add("extraRolls", new JsonObject());
					resources.add(added);
					notes.add("ADDED: " + outputName + " (" + itemId + ")");
				}
				refreshed++;
			}
		}

		Files.write(OUT, gson.toJson(root).getBytes(StandardCharsets.UTF_8));
		System.out.println("Refreshed " + refreshed + " resource(s).");
		notes.forEach(System.out::println);
		System.out.println("Review the diff; curate new extraRolls/rewards; then commit.");
	}

	private List<BucketRow> query(BucketQuery q)
	{
		final AtomicReference<List<BucketRow>> ref = new AtomicReference<>(new ArrayList<>());
		final CountDownLatch latch = new CountDownLatch(1);
		bucket.query(q, rows -> { ref.set(rows); latch.countDown(); }, latch::countDown);
		try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
		return ref.get();
	}

	private int resolveItemId(String itemName)
	{
		if (itemName == null) { return -1; }
		for (BucketRow r : query(new BucketQuery("item_id").select("page_name", "id")
			.where("page_name", itemName).limit(1)))
		{
			final List<Integer> ids = r.ints("id");
			if (!ids.isEmpty()) { return ids.get(0); }
		}
		return -1;
	}

	private List<Integer> resolveNodeIds(String facilityName, boolean npc)
	{
		final List<Integer> out = new ArrayList<>();
		if (facilityName == null) { return out; }
		final boolean isFishingSpot = facilityName.toLowerCase().contains("fishing spot");
		if (npc != isFishingSpot) { return out; }
		for (BucketRow r : query(new BucketQuery(npc ? "npc_id" : "object_id").select("page_name", "id")
			.where("page_name", facilityName).limit(50)))
		{
			out.addAll(r.ints("id"));
		}
		return new ArrayList<>(new java.util.LinkedHashSet<>(out));
	}

	private List<Integer> resolveSecondaryIds(List<String> names)
	{
		final List<Integer> out = new ArrayList<>();
		for (String n : names) { final int id = resolveItemId(n); if (id > 0) { out.add(id); } }
		return out;
	}

	private static JsonObject findByItemId(JsonArray resources, int itemId)
	{
		for (int i = 0; i < resources.size(); i++)
		{
			final JsonObject o = resources.get(i).getAsJsonObject();
			final JsonObject action = o.has("mainAction") ? o.getAsJsonObject("mainAction") : o;
			if (action.has("itemId") && action.get("itemId").getAsInt() == itemId) { return o; }
		}
		return null;
	}

	private static void addIds(JsonObject o, String key, List<Integer> ids)
	{
		if (ids.isEmpty()) { return; }
		final JsonArray arr = new JsonArray();
		ids.forEach(arr::add);
		o.add(key, arr);
	}

	/** Ids already on the matched resource (nested {@code mainAction} or legacy-flat), or empty. */
	static List<Integer> existingIds(JsonObject resource, String key)
	{
		final List<Integer> out = new ArrayList<>();
		if (resource == null) { return out; }
		final JsonObject action = resource.has("mainAction") ? resource.getAsJsonObject("mainAction") : resource;
		if (action.has(key) && action.get(key).isJsonArray())
		{
			for (com.google.gson.JsonElement e : action.getAsJsonArray(key))
			{
				try { out.add(e.getAsInt()); } catch (RuntimeException ignored) { }
			}
		}
		return out;
	}

	/** Union preserving curated data: existing ids first (order kept), new resolved ids appended, deduped. */
	static List<Integer> unionIds(List<Integer> existing, List<Integer> resolved)
	{
		final java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>(existing);
		set.addAll(resolved);
		return new ArrayList<>(set);
	}
}
