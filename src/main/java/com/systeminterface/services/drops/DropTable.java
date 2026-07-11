package com.systeminterface.services.drops;

import java.util.Collections;
import java.util.List;

/**
 * The loot table for a single target (NPC or activity).
 *
 * <p>Loaded from JSON resources shipped inside the plugin jar at
 * {@code /com/systeminterface/drops/<target>.json}, and (later) from user
 * overrides under {@code .runelite/system-interface/loot-tables/}.
 *
 * <p>Expected JSON shape:
 * <pre>
 * {
 *   "target": "Vorkath",
 *   "drops": [
 *     { "name": "Skeletal visage", "rate": 0.0002 },
 *     { "name": "Jar of decay",    "rate": 0.000333 }
 *   ]
 * }
 * </pre>
 *
 * <p>{@code rate} is the per-kill probability as a decimal in {@code [0, 1]}
 * (i.e. {@code 1/5000 → 0.0002}). This keeps the {@link com.systeminterface.common.probability.Probability}
 * engine's signatures clean — no rate-format translation needed at call sites.
 *
 * <p>Gson populates these fields directly via field-name matching. Fields are
 * package-private + non-final to keep Gson happy without custom adapters.
 */
public final class DropTable
{
	String target;
	/** Parser schema version this table was produced with; lets stale wiki caches auto-refetch. */
	int schema;
	/** Where this table came from ("wiki", "wiki-cache", "user", "bundled"). Not persisted. */
	transient String origin;
	int maxHp;
	int combatLevel;
	String maxHit;
	String attackStyle;
	String weakness;
	String examine;
	String imageFile;
	boolean aggressive;
	int slayerLevel;
	/**
	 * Whether this monster is members-only (P2P). Nullable so that tables parsed
	 * before this field existed (older wiki caches) read back as {@code null} and
	 * are treated conservatively as members — see {@link #isMembers()}.
	 */
	Boolean members;
	List<Entry> drops;

	public String getTarget()
	{
		return target;
	}

	public int getSchema()
	{
		return schema;
	}

	public String getOrigin()
	{
		return origin;
	}

	public int getMaxHp()
	{
		return maxHp;
	}

	public int getCombatLevel()
	{
		return combatLevel;
	}

	public String getMaxHit() { return maxHit; }
	public String getAttackStyle() { return attackStyle; }
	public String getWeakness() { return weakness; }
	public String getExamine() { return examine; }
	/** Wiki image filename from the Infobox Monster, e.g. {@code "Vorkath.png"}, or null. */
	public String getImageFile() { return imageFile; }
	public boolean isAggressive() { return aggressive; }
	public int getSlayerLevel() { return slayerLevel; }

	/**
	 * @return {@code true} if this monster is members-only (P2P). Unknown
	 *         membership (e.g. an older cached table without the field) is treated
	 *         as members, so we never wrongly hide drops from a P2P-only monster.
	 */
	public boolean isMembers() { return members == null || members; }

	public List<Entry> getDrops()
	{
		return drops == null ? Collections.emptyList() : Collections.unmodifiableList(drops);
	}

	/** A single drop within a {@link DropTable}. */
	public static final class Entry
	{
		String name;
		double rate;
		int quantityLow;
		int quantityHigh;
		int rolls;
		int dropValue;
		String region;
		boolean rareDropTable;

		public String getName()
		{
			return name;
		}

		public double getRate()
		{
			return rate;
		}

		public int getQuantityLow()
		{
			return quantityLow;
		}

		public int getQuantityHigh()
		{
			return quantityHigh;
		}

		public int getRolls()
		{
			return rolls;
		}

		public int getDropValue()
		{
			return dropValue;
		}

		/** League/region restriction from the wiki, or null. */
		public String getRegion()
		{
			return region;
		}

		/** True when the wiki flags this row as coming from a shared rare-drop table. */
		public boolean isRareDropTable()
		{
			return rareDropTable;
		}
	}
}


