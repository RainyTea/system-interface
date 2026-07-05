package com.systeminterface.modules.skills;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Skill;

@Slf4j
public final class ResourceData
{
	public enum RewardType { PRIMARY, SECONDARY, CONDITIONAL, PET }

	private final Map<Skill, SkillData> skills;
	// A node (object or NPC id) can map to several resources — a fishing spot yields
	// shrimp, sardine, herring, anchovies all from one NPC id — so these are lists.
	// Trees and rocks resolve to a 1-element list (no behaviour change).
	private final Map<Integer, List<ResourceEntry>> byObjectId;
	private final Map<Integer, List<ResourceEntry>> byNpcId;
	private final Map<Integer, ResourceEntry> byItemId;
	private final Map<Skill, List<RewardEntry>> rewardsBySkill;
	private final Map<Integer, RewardEntry> rewardByItemId;

	private ResourceData(Map<Skill, SkillData> skills, Map<Skill, List<RewardEntry>> rewards)
	{
		this.skills = skills;
		this.byObjectId = new HashMap<>();
		this.byNpcId = new HashMap<>();
		this.byItemId = new HashMap<>();
		this.rewardsBySkill = rewards;
		this.rewardByItemId = new HashMap<>();
		for (List<RewardEntry> list : rewards.values())
		{
			for (RewardEntry re : list)
			{
				// First-declared skill wins if the same item id is a reward under multiple skills
				// (e.g. bird nest under both woodcutting and mining) — relies on `rewards` iterating
				// in JSON declaration order (LinkedHashMap in load()); putIfAbsent guards the invariant
				// even if a caller passes an unordered map.
				rewardByItemId.putIfAbsent(re.getItemId(), re);
			}
		}
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
			return load(new InputStreamReader(is, StandardCharsets.UTF_8), gson);
		}
		catch (Exception e)
		{
			log.debug("Failed to load ResourceData.json", e);
			return empty();
		}
	}

	static ResourceData load(Reader reader, Gson gson)
	{
		try
		{
			JsonObject root = gson.fromJson(reader, JsonObject.class);

			Map<Skill, SkillData> skills = new HashMap<>();
			// LinkedHashMap: preserves JSON declaration order so that when the same reward item id
			// is declared under multiple skills (e.g. bird nest under both woodcutting and mining),
			// rewardByItemId's reverse lookup deterministically resolves to the first-declared skill
			// rather than depending on undefined HashMap iteration order.
			Map<Skill, List<RewardEntry>> rewards = new LinkedHashMap<>();
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
					// Provenance-structured (nested) or legacy-flat: generated fields come from
					// mainAction, curated fields from extraRolls; a flat resource object supplies both.
					JsonObject action = r.has("mainAction") && r.get("mainAction").isJsonObject()
						? r.getAsJsonObject("mainAction") : r;
					JsonObject extra = r.has("extraRolls") && r.get("extraRolls").isJsonObject()
						? r.getAsJsonObject("extraRolls") : r;

					String name = action.get("name").getAsString();
					int levelRequired = action.get("levelRequired").getAsInt();
					double xpPerAction = action.get("xpPerAction").getAsDouble();
					int itemId = action.get("itemId").getAsInt();

					List<Integer> objectIds = intList(action, "objectIds");
					// New in Phase 4: NPC-based nodes (fishing spots are NPCs, not objects).
					// Optional — trees/rocks omit it.
					List<Integer> npcIds = intList(action, "npcIds");
					// Optional gathering method (fishing: net/bait/lure/cage/harpoon/...), or null.
					String method = optString(action, "method");
					// Optional secondary/consumable item ids required to perform the action
					// (fishing: feathers for lure, bait for bait fishing, sandworms, etc.).
					List<Integer> secondaries = intList(action, "secondaries");

					List<String> uses = stringList(extra, "uses");
					Integer petOverride = optInt(extra, "petBaseChanceOverride");
					// Optional catch/success rate (e.g. for future Hunter/Thieving), or null.
					Double rate = optDouble(extra, "rate");

					resources.add(new ResourceEntry(name, skill, levelRequired, xpPerAction,
						itemId, objectIds, npcIds, uses, petOverride, rate, method, secondaries));
				}
				skills.put(skill, new SkillData(skill, petBase, resources));

				List<RewardEntry> rewardList = new ArrayList<>();
				if (skillObj.has("rewards"))
				{
					for (JsonElement rewEl : skillObj.getAsJsonArray("rewards"))
					{
						JsonObject rw = rewEl.getAsJsonObject();
						String rName = rw.get("name").getAsString();
						int rItemId = rw.get("itemId").getAsInt();
						RewardType rType = "conditional".equalsIgnoreCase(rw.get("type").getAsString())
							? RewardType.CONDITIONAL : RewardType.SECONDARY;
						Double rRate = rw.has("rate") && !rw.get("rate").isJsonNull()
							? rw.get("rate").getAsDouble() : null;
						List<Integer> req = new ArrayList<>();
						if (rw.has("requiredItemIds"))
						{
							for (JsonElement rid : rw.getAsJsonArray("requiredItemIds")) { req.add(rid.getAsInt()); }
						}
						rewardList.add(new RewardEntry(rName, rItemId, rType, skill, rRate, req));
					}
				}
				rewards.put(skill, Collections.unmodifiableList(rewardList));
			}
			return new ResourceData(skills, rewards);
		}
		catch (Exception e)
		{
			log.debug("Failed to load ResourceData.json", e);
			return empty();
		}
	}

	private static ResourceData empty()
	{
		return new ResourceData(Collections.emptyMap(), Collections.emptyMap());
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

	/** Optional int array field, or an empty list when absent. */
	private static List<Integer> intList(JsonObject obj, String field)
	{
		List<Integer> out = new ArrayList<>();
		if (obj.has(field) && obj.get(field).isJsonArray())
		{
			for (JsonElement el : obj.getAsJsonArray(field))
			{
				out.add(el.getAsInt());
			}
		}
		return out;
	}

	/** Optional string array field, or an empty list when absent. */
	private static List<String> stringList(JsonObject obj, String field)
	{
		List<String> out = new ArrayList<>();
		if (obj.has(field) && obj.get(field).isJsonArray())
		{
			for (JsonElement el : obj.getAsJsonArray(field))
			{
				out.add(el.getAsString());
			}
		}
		return out;
	}

	/** Optional string scalar field, or {@code null} when absent/JSON null. */
	private static String optString(JsonObject obj, String field)
	{
		return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsString() : null;
	}

	/** Optional int scalar field, or {@code null} when absent/JSON null. */
	private static Integer optInt(JsonObject obj, String field)
	{
		return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsInt() : null;
	}

	/** Optional double scalar field, or {@code null} when absent/JSON null. */
	private static Double optDouble(JsonObject obj, String field)
	{
		return obj.has(field) && !obj.get(field).isJsonNull() ? obj.get(field).getAsDouble() : null;
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

	/**
	 * Resolves a clicked fishing-spot menu option (e.g. "Harpoon", "Cage", "Net", "Big Net", "Fish")
	 * to the data {@code method} value <em>for that specific spot</em>, or null when the option is
	 * not a fishing action, the spot does not offer that option's method, or the npc is not a known
	 * fishing spot. Spot-aware so the ambiguous net-family word maps to {@code net} at a small-net
	 * spot but {@code bignet} at a big-net spot.
	 */
	public String resolveFishingMethod(int npcId, String menuOption)
	{
		if (menuOption == null)
		{
			return null;
		}
		List<ResourceEntry> entries = forNpcId(npcId);
		if (entries.isEmpty())
		{
			return null;
		}
		Set<String> spotMethods = new HashSet<>();
		for (ResourceEntry e : entries)
		{
			if (e.getMethod() != null)
			{
				spotMethods.add(e.getMethod());
			}
		}
		switch (menuOption.toLowerCase().trim())
		{
			case "harpoon":   return spotMethods.contains("harpoon") ? "harpoon" : null;
			case "cage":      return spotMethods.contains("cage") ? "cage" : null;
			case "lure":
			case "fly":       return spotMethods.contains("lure") ? "lure" : null;
			case "bait":      return spotMethods.contains("bait") ? "bait" : null;
			case "fish":      return spotMethods.contains("vessel") ? "vessel" : null;
			case "net":
			case "small net":
			case "big net":
				if (spotMethods.contains("net"))
				{
					return "net";
				}
				if (spotMethods.contains("bignet"))
				{
					return "bignet";
				}
				return null;
			default:          return null;
		}
	}

	/**
	 * Whether a fishing method's catch is shown in the tracking table. Roll-based methods with
	 * unmodelled rates / incomplete catch data (currently {@code bignet}) are deferred to Phase 2
	 * and excluded. Null (unknown) is treated as trackable so the show-all fallback still applies.
	 */
	public static boolean isTrackableMethod(String method)
	{
		return !"bignet".equals(method);
	}

	public ResourceEntry forItemId(int itemId)
	{
		return byItemId.get(itemId);
	}

	/** All curated reward entries (secondary/conditional) declared for {@code skill}. Empty if none. */
	public List<RewardEntry> getRewards(Skill skill)
	{
		List<RewardEntry> list = rewardsBySkill.get(skill);
		return list == null ? Collections.emptyList() : list;
	}

	/**
	 * Reward entries that currently apply for {@code skill}: every {@code SECONDARY}, plus each
	 * {@code CONDITIONAL} whose required held/worn item is present in {@code heldItemIds}. Pure —
	 * the held set is supplied by the caller (client-thread {@code HeldItemCache} in production).
	 */
	public List<RewardEntry> getApplicableRewards(Skill skill, Set<Integer> heldItemIds)
	{
		List<RewardEntry> out = new ArrayList<>();
		for (RewardEntry re : getRewards(skill))
		{
			if (re.getType() == RewardType.SECONDARY)
			{
				out.add(re);
			}
			else if (re.getType() == RewardType.CONDITIONAL && !re.getRequiredItemIds().isEmpty())
			{
				for (int req : re.getRequiredItemIds())
				{
					if (heldItemIds != null && heldItemIds.contains(req)) { out.add(re); break; }
				}
			}
		}
		return out;
	}

	/** Curated reward entry for an item id, or {@code null}. */
	public RewardEntry rewardForItemId(int itemId)
	{
		return rewardByItemId.get(itemId);
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

	public static final class RewardEntry
	{
		private final String name;
		private final int itemId;
		private final RewardType type;
		private final Skill skill;
		private final Double rate;
		private final List<Integer> requiredItemIds;

		RewardEntry(String name, int itemId, RewardType type, Skill skill, Double rate, List<Integer> requiredItemIds)
		{
			this.name = name;
			this.itemId = itemId;
			this.type = type;
			this.skill = skill;
			this.rate = rate;
			this.requiredItemIds = Collections.unmodifiableList(requiredItemIds);
		}

		public String getName() { return name; }
		public int getItemId() { return itemId; }
		public RewardType getType() { return type; }
		public Skill getSkill() { return skill; }
		/** Skill-wide statistical rate (secondary), or {@code null}. */
		public Double getRate() { return rate; }
		/** Held/worn item ids gating a conditional reward (any one suffices). Empty for non-conditional. */
		public List<Integer> getRequiredItemIds() { return requiredItemIds; }
	}
}
