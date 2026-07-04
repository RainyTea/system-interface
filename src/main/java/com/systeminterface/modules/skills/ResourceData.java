package com.systeminterface.modules.skills;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

@Slf4j
public final class ResourceData
{
	private final Map<Skill, SkillData> skills;
	// A node (object or NPC id) can map to several resources — a fishing spot yields
	// shrimp, sardine, herring, anchovies all from one NPC id — so these are lists.
	// Trees and rocks resolve to a 1-element list (no behaviour change).
	private final Map<Integer, List<ResourceEntry>> byObjectId;
	private final Map<Integer, List<ResourceEntry>> byNpcId;
	private final Map<Integer, ResourceEntry> byItemId;

	private ResourceData(Map<Skill, SkillData> skills)
	{
		this.skills = skills;
		this.byObjectId = new HashMap<>();
		this.byNpcId = new HashMap<>();
		this.byItemId = new HashMap<>();
		for (SkillData sd : skills.values())
		{
			for (ResourceEntry r : sd.resources)
			{
				for (int objId : r.objectIds)
				{
					byObjectId.computeIfAbsent(objId, k -> new ArrayList<>()).add(r);
				}
				for (int npcId : r.npcIds)
				{
					byNpcId.computeIfAbsent(npcId, k -> new ArrayList<>()).add(r);
				}
				byItemId.put(r.itemId, r);
			}
		}
	}

	public static ResourceData load(Gson gson)
	{
		try (InputStream is = ResourceData.class.getResourceAsStream("ResourceData.json"))
		{
			if (is == null)
			{
				log.debug("ResourceData.json not found on classpath");
				return empty();
			}
			JsonObject root = gson.fromJson(
				new InputStreamReader(is, StandardCharsets.UTF_8), JsonObject.class);

			Map<Skill, SkillData> skills = new HashMap<>();
			for (Map.Entry<String, JsonElement> entry : root.entrySet())
			{
				Skill skill = parseSkill(entry.getKey());
				if (skill == null)
				{
					continue;
				}
				JsonObject skillObj = entry.getValue().getAsJsonObject();
				int petBase = skillObj.get("petBaseChance").getAsInt();

				List<ResourceEntry> resources = new ArrayList<>();
				for (JsonElement resEl : skillObj.getAsJsonArray("resources"))
				{
					JsonObject r = resEl.getAsJsonObject();
					String name = r.get("name").getAsString();
					int levelRequired = r.get("levelRequired").getAsInt();
					double xpPerAction = r.get("xpPerAction").getAsDouble();
					int itemId = r.get("itemId").getAsInt();

					List<Integer> objectIds = new ArrayList<>();
					if (r.has("objectIds"))
					{
						for (JsonElement oid : r.getAsJsonArray("objectIds"))
						{
							objectIds.add(oid.getAsInt());
						}
					}

					// New in Phase 4: NPC-based nodes (fishing spots are NPCs, not objects).
					// Optional — trees/rocks omit it.
					List<Integer> npcIds = new ArrayList<>();
					if (r.has("npcIds"))
					{
						for (JsonElement nid : r.getAsJsonArray("npcIds"))
						{
							npcIds.add(nid.getAsInt());
						}
					}

					List<String> uses = new ArrayList<>();
					if (r.has("uses"))
					{
						for (JsonElement u : r.getAsJsonArray("uses"))
						{
							uses.add(u.getAsString());
						}
					}

					Integer petOverride = !r.has("petBaseChanceOverride") || r.get("petBaseChanceOverride").isJsonNull()
						? null : r.get("petBaseChanceOverride").getAsInt();

					// Optional catch/success rate (e.g. for future Hunter/Thieving), or null.
					Double rate = r.has("rate") && !r.get("rate").isJsonNull()
						? r.get("rate").getAsDouble() : null;

					// Optional gathering method (fishing: net/bait/lure/cage/harpoon/...), or null.
					String method = r.has("method") && !r.get("method").isJsonNull()
						? r.get("method").getAsString() : null;

					// Optional secondary/consumable item ids required to perform the action
					// (fishing: feathers for lure, bait for bait fishing, sandworms, etc.).
					List<Integer> secondaries = new ArrayList<>();
					if (r.has("secondaries"))
					{
						for (JsonElement sid : r.getAsJsonArray("secondaries"))
						{
							secondaries.add(sid.getAsInt());
						}
					}

					resources.add(new ResourceEntry(name, skill, levelRequired, xpPerAction,
						itemId, objectIds, npcIds, uses, petOverride, rate, method, secondaries));
				}
				skills.put(skill, new SkillData(skill, petBase, resources));
			}
			return new ResourceData(skills);
		}
		catch (Exception e)
		{
			log.debug("Failed to load ResourceData.json", e);
			return empty();
		}
	}

	private static ResourceData empty()
	{
		return new ResourceData(Collections.emptyMap());
	}

	private static Skill parseSkill(String name)
	{
		try
		{
			return Skill.valueOf(name.toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	public SkillData getSkillData(Skill skill)
	{
		return skills.get(skill);
	}

	/** All resources offered by a game-object node (e.g. a tree/rock). 1-element for trees/rocks. */
	public List<ResourceEntry> forObjectId(int objectId)
	{
		List<ResourceEntry> list = byObjectId.get(objectId);
		return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
	}

	/** All resources offered by an NPC node (e.g. a fishing spot — may be several fish). */
	public List<ResourceEntry> forNpcId(int npcId)
	{
		List<ResourceEntry> list = byNpcId.get(npcId);
		return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
	}

	/**
	 * Fish offered by a spot for a specific gathering method. Falls back to ALL of the spot's fish
	 * when {@code method} is null or no entry matches it, so single-method spots and unrecognised
	 * methods still show their catch (never hides a spot's fish).
	 */
	public List<ResourceEntry> forNpcIdAndMethod(int npcId, String method)
	{
		List<ResourceEntry> all = forNpcId(npcId);
		if (method == null || all.isEmpty())
		{
			return all;
		}
		List<ResourceEntry> filtered = new ArrayList<>();
		for (ResourceEntry e : all)
		{
			if (method.equals(e.getMethod()))
			{
				filtered.add(e);
			}
		}
		return filtered.isEmpty() ? all : Collections.unmodifiableList(filtered);
	}

	public ResourceEntry forItemId(int itemId)
	{
		return byItemId.get(itemId);
	}

	public static final class SkillData
	{
		private final Skill skill;
		private final int petBaseChance;
		private final List<ResourceEntry> resources;

		SkillData(Skill skill, int petBaseChance, List<ResourceEntry> resources)
		{
			this.skill = skill;
			this.petBaseChance = petBaseChance;
			this.resources = Collections.unmodifiableList(resources);
		}

		public Skill getSkill() { return skill; }
		public int getPetBaseChance() { return petBaseChance; }
		public List<ResourceEntry> getResources() { return resources; }
	}

	public static final class ResourceEntry
	{
		private final String name;
		private final Skill skill;
		private final int levelRequired;
		private final double xpPerAction;
		private final int itemId;
		private final List<Integer> objectIds;
		private final List<Integer> npcIds;
		private final List<String> uses;
		private final Integer petBaseChanceOverride;
		private final Double rate;
		private final String method;
		private final List<Integer> secondaries;

		ResourceEntry(String name, Skill skill, int levelRequired, double xpPerAction,
			int itemId, List<Integer> objectIds, List<Integer> npcIds, List<String> uses,
			Integer petBaseChanceOverride, Double rate, String method, List<Integer> secondaries)
		{
			this.name = name;
			this.skill = skill;
			this.levelRequired = levelRequired;
			this.xpPerAction = xpPerAction;
			this.itemId = itemId;
			this.objectIds = Collections.unmodifiableList(objectIds);
			this.npcIds = Collections.unmodifiableList(npcIds);
			this.uses = Collections.unmodifiableList(uses);
			this.petBaseChanceOverride = petBaseChanceOverride;
			this.rate = rate;
			this.method = method;
			this.secondaries = Collections.unmodifiableList(secondaries);
		}

		public String getName() { return name; }
		public Skill getSkill() { return skill; }
		public int getLevelRequired() { return levelRequired; }
		public double getXpPerAction() { return xpPerAction; }
		public int getItemId() { return itemId; }
		public List<Integer> getObjectIds() { return objectIds; }
		public List<Integer> getNpcIds() { return npcIds; }
		public List<String> getUses() { return uses; }
		public Integer getPetBaseChanceOverride() { return petBaseChanceOverride; }
		/** Optional per-action catch/success rate, or {@code null} if not specified. */
		public Double getRate() { return rate; }
		/** Gathering method (fishing: net/bait/lure/cage/harpoon/bignet/vessel), or {@code null}. */
		public String getMethod() { return method; }
		/** Secondary/consumable item ids required for the action (any one suffices). Empty if none. */
		public List<Integer> getSecondaries() { return secondaries; }

		public int getEffectivePetBaseChance(int skillDefault)
		{
			return petBaseChanceOverride != null ? petBaseChanceOverride : skillDefault;
		}
	}
}
