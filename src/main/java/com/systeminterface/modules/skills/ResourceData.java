package com.systeminterface.modules.skills;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private final Map<Integer, List<ResourceEntry>> byItemId;

	private ResourceData(Map<Skill, SkillData> skills)
	{
		this.skills = skills;
		this.byObjectId = new HashMap<>();
		this.byNpcId = new HashMap<>();
		this.byItemId = new HashMap<>();
		for (Skill skill : Skill.values())
		{
			final SkillData sd = skills.get(skill);
			if (sd == null)
			{
				continue;
			}
			for (ResourceEntry r : sd.resources)
			{
				if (!r.rewardOnly)
				{
					for (int objId : r.objectIds)
					{
						byObjectId.computeIfAbsent(objId, k -> new ArrayList<>()).add(r);
					}
					for (int npcId : r.npcIds)
					{
						byNpcId.computeIfAbsent(npcId, k -> new ArrayList<>()).add(r);
					}
				}
				for (int itemId : r.itemIds)
				{
					byItemId.computeIfAbsent(itemId, k -> new ArrayList<>()).add(r);
				}
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

			Map<Skill, SkillData> skills = new EnumMap<>(Skill.class);
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
					List<Integer> itemIds = new ArrayList<>();
					itemIds.add(itemId);
					if (r.has("itemIds"))
					{
						for (JsonElement iid : r.getAsJsonArray("itemIds"))
						{
							final int alias = iid.getAsInt();
							if (!itemIds.contains(alias))
							{
								itemIds.add(alias);
							}
						}
					}

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

					List<String> actions = new ArrayList<>();
					if (r.has("actions"))
					{
						for (JsonElement action : r.getAsJsonArray("actions"))
						{
							actions.add(action.getAsString());
						}
					}

					List<String> sourceNames = new ArrayList<>();
					if (r.has("sourceNames"))
					{
						for (JsonElement source : r.getAsJsonArray("sourceNames"))
						{
							sourceNames.add(source.getAsString());
						}
					}

					List<Integer> requiredItemsAny = new ArrayList<>();
					if (r.has("requiredItemsAny"))
					{
						for (JsonElement required : r.getAsJsonArray("requiredItemsAny"))
						{
							requiredItemsAny.add(required.getAsInt());
						}
					}

					boolean rewardOnly = r.has("rewardOnly") && r.get("rewardOnly").getAsBoolean();

					Integer petOverride = !r.has("petBaseChanceOverride") || r.get("petBaseChanceOverride").isJsonNull()
						? null : r.get("petBaseChanceOverride").getAsInt();

					// Optional catch/success rate (e.g. for future Hunter/Thieving), or null.
					Double rate = r.has("rate") && !r.get("rate").isJsonNull()
						? r.get("rate").getAsDouble() : null;

					// Optional gathering method (fishing: net/bait/lure/cage/harpoon/...), or null.
					String method = r.has("method") && !r.get("method").isJsonNull()
						? r.get("method").getAsString() : null;

					// Optional OSRS Wiki page title for confident object-examine lookup.
					// Generic scenery names such as "Tree" or "Rocks" intentionally omit
					// this and stay observed-examine only until reviewed.
					String wikiPage = r.has("wikiPage") && !r.get("wikiPage").isJsonNull()
						? r.get("wikiPage").getAsString() : null;

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
						itemId, itemIds, objectIds, npcIds, uses, actions, sourceNames,
						requiredItemsAny, rewardOnly, petOverride, rate, method, secondaries, wikiPage));
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
	 * Best-effort fallback for resource object menu targets when RuneLite's clicked
	 * object id is a transformed/unlisted variant. Exact object ids remain preferred.
	 */
	public List<ResourceEntry> forObjectName(String objectName)
	{
		final String target = normalizeObjectName(objectName);
		if (target.isEmpty())
		{
			return Collections.emptyList();
		}
		final List<ResourceEntry> matches = new ArrayList<>();
		ResourceEntry plainLogs = null;
		for (SkillData sd : skills.values())
		{
			for (ResourceEntry entry : sd.resources)
			{
				if (entry.rewardOnly || entry.objectIds.isEmpty())
				{
					continue;
				}
				final String key = resourceObjectKey(entry.name);
				if ("tree".equals(key))
				{
					plainLogs = entry;
					continue;
				}
				if (!key.isEmpty() && (target.equals(key) || target.contains(key) || key.contains(target)))
				{
					matches.add(entry);
				}
			}
		}
		if (matches.isEmpty() && plainLogs != null && isPlainLogsTarget(target))
		{
			matches.add(plainLogs);
		}
		return matches.isEmpty() ? Collections.emptyList() : Collections.unmodifiableList(matches);
	}

	private static String normalizeObjectName(String value)
	{
		if (value == null)
		{
			return "";
		}
		return value.replaceAll("<[^>]*>", "")
			.replace('\u00A0', ' ')
			.replaceAll("\\s*\\((level|combat).*", "")
			.replaceAll("[^A-Za-z0-9 ]", " ")
			.trim()
			.toLowerCase()
			.replaceAll("\\s+", " ");
	}

	private static String resourceObjectKey(String resourceName)
	{
		String key = normalizeObjectName(resourceName);
		if ("logs".equals(key))
		{
			return "tree";
		}
		for (String suffix : new String[] {" logs", " ore", " cap"})
		{
			if (key.endsWith(suffix))
			{
				return key.substring(0, key.length() - suffix.length()).trim();
			}
		}
		return key;
	}

	private static boolean isPlainLogsTarget(String target)
	{
		return "tree".equals(target)
			|| "dead tree".equals(target)
			|| "evergreen".equals(target)
			|| "jungle tree".equals(target)
			|| "light tree".equals(target)
			|| "snowy tree".equals(target);
	}

	public ResourceEntry forItemId(int itemId)
	{
		List<ResourceEntry> list = byItemId.get(itemId);
		return list == null || list.isEmpty() ? null : list.get(0);
	}

	public ResourceEntry forItemId(int itemId, Skill skill)
	{
		if (skill == null)
		{
			return null;
		}
		for (ResourceEntry entry : forItemIdAll(itemId))
		{
			if (entry.getSkill() == skill)
			{
				return entry;
			}
		}
		return null;
	}

	public List<ResourceEntry> forItemIdAll(int itemId)
	{
		List<ResourceEntry> list = byItemId.get(itemId);
		return list == null ? Collections.emptyList() : Collections.unmodifiableList(list);
	}

	public List<ResourceEntry> forSourceAction(Skill skill, String sourceName, String action)
	{
		SkillData sd = skills.get(skill);
		if (sd == null || sourceName == null || action == null)
		{
			return Collections.emptyList();
		}
		List<ResourceEntry> matches = new ArrayList<>();
		for (ResourceEntry entry : sd.resources)
		{
			if (entry.matchesSource(sourceName) && entry.matchesAction(action))
			{
				matches.add(entry);
			}
		}
		return Collections.unmodifiableList(matches);
	}

	public List<ResourceEntry> skillWideStatisticalRewards(Skill skill)
	{
		SkillData sd = skills.get(skill);
		if (sd == null)
		{
			return Collections.emptyList();
		}
		List<ResourceEntry> matches = new ArrayList<>();
		for (ResourceEntry entry : sd.resources)
		{
			if (entry.isStatisticalReward() && entry.actions.isEmpty()
				&& entry.sourceNames.isEmpty() && entry.objectIds.isEmpty())
			{
				matches.add(entry);
			}
		}
		return Collections.unmodifiableList(matches);
	}

	public List<ResourceEntry> statisticalRewardsForResource(ResourceEntry resource)
	{
		if (resource == null)
		{
			return Collections.emptyList();
		}
		SkillData sd = skills.get(resource.getSkill());
		if (sd == null)
		{
			return Collections.emptyList();
		}
		List<ResourceEntry> matches = new ArrayList<>();
		for (ResourceEntry entry : sd.resources)
		{
			if (!entry.isStatisticalReward() || !entry.actions.isEmpty() || !entry.sourceNames.isEmpty())
			{
				continue;
			}
			if (entry.objectIds.isEmpty() || overlaps(entry.objectIds, resource.objectIds))
			{
				matches.add(entry);
			}
		}
		return Collections.unmodifiableList(matches);
	}

	private static boolean overlaps(List<Integer> a, List<Integer> b)
	{
		for (int value : a)
		{
			if (b.contains(value))
			{
				return true;
			}
		}
		return false;
	}

	public boolean itemMatchesSource(Skill skill, String sourceName, int itemId)
	{
		return itemMatchesSource(skill, sourceName, null, itemId);
	}

	public boolean itemMatchesSource(Skill skill, String sourceName, String action, int itemId)
	{
		if (skill == null || sourceName == null || sourceName.trim().isEmpty())
		{
			return forItemId(itemId, skill) != null;
		}
		final String normalizedAction = normalizeAction(action);
		for (ResourceEntry entry : forItemIdAll(itemId))
		{
			if (entry.getSkill() != skill)
			{
				continue;
			}
			if (entry.matchesSource(sourceName))
			{
				return true;
			}
			if (!normalizedAction.isEmpty() && entry.matchesAction(normalizedAction))
			{
				if (entry.sourceNames.isEmpty() || isGenericPickpocketPouch(entry, normalizedAction))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean isGenericPickpocketPouch(ResourceEntry entry, String normalizedAction)
	{
		return "Pickpocket".equalsIgnoreCase(normalizedAction)
			&& "Coin pouch".equalsIgnoreCase(entry.getName());
	}

	public Set<Integer> allResourceItemIds(Skill skill)
	{
		SkillData sd = skills.get(skill);
		if (sd == null)
		{
			return Collections.emptySet();
		}
		Set<Integer> ids = new HashSet<>();
		for (ResourceEntry r : sd.resources)
		{
			ids.addAll(r.itemIds);
		}
		return ids;
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
		private final List<Integer> itemIds;
		private final List<Integer> objectIds;
		private final List<Integer> npcIds;
		private final List<String> uses;
		private final List<String> actions;
		private final List<String> sourceNames;
		private final List<Integer> requiredItemsAny;
		private final boolean rewardOnly;
		private final Integer petBaseChanceOverride;
		private final Double rate;
		private final String method;
		private final List<Integer> secondaries;
		private final String wikiPage;

		ResourceEntry(String name, Skill skill, int levelRequired, double xpPerAction,
			int itemId, List<Integer> itemIds, List<Integer> objectIds, List<Integer> npcIds,
			List<String> uses, List<String> actions, List<String> sourceNames,
			List<Integer> requiredItemsAny, boolean rewardOnly,
			Integer petBaseChanceOverride, Double rate, String method, List<Integer> secondaries,
			String wikiPage)
		{
			this.name = name;
			this.skill = skill;
			this.levelRequired = levelRequired;
			this.xpPerAction = xpPerAction;
			this.itemId = itemId;
			this.itemIds = Collections.unmodifiableList(itemIds);
			this.objectIds = Collections.unmodifiableList(objectIds);
			this.npcIds = Collections.unmodifiableList(npcIds);
			this.uses = Collections.unmodifiableList(uses);
			this.actions = Collections.unmodifiableList(actions);
			this.sourceNames = Collections.unmodifiableList(sourceNames);
			this.requiredItemsAny = Collections.unmodifiableList(requiredItemsAny);
			this.rewardOnly = rewardOnly;
			this.petBaseChanceOverride = petBaseChanceOverride;
			this.rate = rate;
			this.method = method;
			this.secondaries = Collections.unmodifiableList(secondaries);
			this.wikiPage = wikiPage == null || wikiPage.trim().isEmpty() ? null : wikiPage.trim();
		}

		public String getName() { return name; }
		public Skill getSkill() { return skill; }
		public int getLevelRequired() { return levelRequired; }
		public double getXpPerAction() { return xpPerAction; }
		public int getItemId() { return itemId; }
		public List<Integer> getItemIds() { return itemIds; }
		public List<Integer> getObjectIds() { return objectIds; }
		public List<Integer> getNpcIds() { return npcIds; }
		public List<String> getUses() { return uses; }
		public List<String> getActions() { return actions; }
		public List<String> getSourceNames() { return sourceNames; }
		public List<Integer> getRequiredItemsAny() { return requiredItemsAny; }
		public boolean isRewardOnly() { return rewardOnly; }
		public Integer getPetBaseChanceOverride() { return petBaseChanceOverride; }
		/** Optional per-action catch/success rate, or {@code null} if not specified. */
		public Double getRate() { return rate; }
		/** Gathering method (fishing: net/bait/lure/cage/harpoon/bignet/vessel), or {@code null}. */
		public String getMethod() { return method; }
		/** Secondary/consumable item ids required for the action (any one suffices). Empty if none. */
		public List<Integer> getSecondaries() { return secondaries; }
		/** Confident OSRS Wiki scenery/resource page title, or {@code null} for ambiguous objects. */
		public String getWikiPage() { return wikiPage; }

		public int getEffectivePetBaseChance(int skillDefault)
		{
			return petBaseChanceOverride != null ? petBaseChanceOverride : skillDefault;
		}

		public boolean isStatisticalReward()
		{
			return rate != null && rate > 0.0 && rate < 1.0;
		}

		public long rateDenominator()
		{
			return isStatisticalReward() ? Math.max(1L, Math.round(1.0 / rate)) : 0L;
		}

		public long countIn(Map<Integer, Long> counts)
		{
			if (counts == null || counts.isEmpty())
			{
				return 0L;
			}
			long total = 0L;
			for (int id : itemIds)
			{
				total += counts.getOrDefault(id, 0L);
			}
			return total;
		}

		private boolean matchesAction(String action)
		{
			final String normalized = normalizeAction(action);
			for (String candidate : actions)
			{
				if (normalizeAction(candidate).equalsIgnoreCase(normalized))
				{
					return true;
				}
			}
			return false;
		}

		private boolean matchesSource(String sourceName)
		{
			for (String candidate : sourceNames)
			{
				if (candidate.equalsIgnoreCase(sourceName))
				{
					return true;
				}
			}
			return false;
		}

	}

	private static String normalizeAction(String action)
	{
		return action == null ? "" : action.trim().replace(' ', '-');
	}
}
