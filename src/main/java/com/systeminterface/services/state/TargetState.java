package com.systeminterface.services.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Per-target progress snapshot — current KC plus the full list of recorded
 * {@link DropOccurrence}s.
 *
 * <p>Mutable, but only the owning {@link StateTracker} should call the
 * package-private mutators. Public accessors return defensive copies / immutable
 * views so consumers (overlay, future plugin panel) cannot mutate state by
 * accident.
 */
public final class TargetState
{
	private final String name;
	private volatile int currentKc;
	private final List<DropOccurrence> drops;

	/**
	 * Kills observed in <em>this</em> session (since the plugin most recently
	 * started or the profile was switched). Never persisted — Gson skips it
	 * because of {@code transient}. Resets to 0 on every {@link StateTracker#setActiveProfile}.
	 *
	 * <p>Inspired by RuneLite's Loot Tracker, which displays "x N" per source
	 * to show recent activity separately from lifetime totals. Useful for
	 * slayer-task progress, "kills this trip", etc.
	 */
	private transient int sessionKc;

	/** NPC combat level, persisted for bestiary ranking. 0 = unknown. */
	private int combatLevel;

	/** True once the player has attacked/interacted with or killed this target. Persisted. */
	private boolean engaged;

	/** Epoch millis of the last real interaction (engage / kill / loot). Persisted. */
	private long lastSeen;

	/**
	 * Lifetime GE value of <em>all</em> loot this target has dropped, picked up
	 * or not. Persisted. Paired with {@link #keptValue} for the
	 * "Profit: kept / total" display.
	 */
	private long totalDropValue;

	/**
	 * Lifetime GE value of loot actually picked up and kept (drops re-dropped by
	 * the player are subtracted back out; items the player brought never count).
	 * Persisted.
	 */
	private long keptValue;

	/** This-session counterparts of the profit totals. Transient — reset each session. */
	private transient long sessionTotalDropValue;
	private transient long sessionKeptValue;

	/**
	 * Lifetime quantity of each item actually <em>kept</em> from this target (picked up
	 * and not later dropped/eaten), keyed by item id. This is the itemised counterpart
	 * of {@link #keptValue} and powers the Loot Log — it reflects what you saved, not
	 * everything that dropped. Persisted. May be {@code null} for state loaded from an
	 * older save (Gson skips absent fields), so always access via the methods below.
	 */
	private Map<Integer, Integer> keptItems;

	public TargetState(String name)
	{
		this.name = Objects.requireNonNull(name, "name");
		this.currentKc = 0;
		this.drops = Collections.synchronizedList(new ArrayList<>());
		this.sessionKc = 0;
	}

	public String getName()
	{
		return name;
	}

	public int getCurrentKc()
	{
		return currentKc;
	}

	/** Kills observed in this session only. Resets on profile switch / plugin restart. */
	public int getSessionKc()
	{
		return sessionKc;
	}

	/** Defensive snapshot — safe to iterate without holding any lock. */
	public List<DropOccurrence> getDrops()
	{
		synchronized (drops)
		{
			return new ArrayList<>(drops);
		}
	}

	/**
	 * Current dry streak for a specific drop, in kills. Defined as
	 * {@code currentKc − maxKc(dropName)}; if the drop has never been received,
	 * returns the full current KC.
	 */
	public int dryStreakFor(String dropName)
	{
		int lastKc = -1;
		synchronized (drops)
		{
			for (DropOccurrence d : drops)
			{
				if (d.getDropName().equalsIgnoreCase(dropName) && d.getKc() > lastKc)
				{
					lastKc = d.getKc();
				}
			}
		}
		if (lastKc < 0)
		{
			return currentKc;
		}
		return Math.max(0, currentKc - lastKc);
	}

	/** Total number of recorded drops of {@code dropName}. */
	public int countOf(String dropName)
	{
		int count = 0;
		synchronized (drops)
		{
			for (DropOccurrence d : drops)
			{
				if (d.getDropName().equalsIgnoreCase(dropName))
				{
					count++;
				}
			}
		}
		return count;
	}

	public int getCombatLevel()
	{
		return combatLevel;
	}

	/** Lifetime GE value of all loot dropped by this target. */
	public long getTotalDropValue()
	{
		return totalDropValue;
	}

	/** Lifetime GE value of loot kept (picked up, minus loot re-dropped). Never negative. */
	public long getKeptValue()
	{
		return Math.max(0L, keptValue);
	}

	/** This-session GE value of all loot dropped by this target. */
	public long getSessionTotalDropValue()
	{
		return sessionTotalDropValue;
	}

	/** This-session GE value of loot kept. Never negative. */
	public long getSessionKeptValue()
	{
		return Math.max(0L, sessionKeptValue);
	}

	/** Lifetime kept quantity per item id (defensive copy; never null). */
	public Map<Integer, Integer> getKeptItems()
	{
		synchronized (this)
		{
			return keptItems == null ? Collections.emptyMap() : new HashMap<>(keptItems);
		}
	}

	/** True once the player has engaged (attacked/interacted) with or killed this target. */
	public boolean isEngaged()
	{
		return engaged || currentKc > 0;
	}

	/** Epoch millis of the last real interaction, for hiding long-stale targets. */
	public long getLastSeen()
	{
		return lastSeen;
	}

	// ---- package-private mutators (StateTracker only) ----

	void setCurrentKc(int kc)
	{
		this.currentKc = kc;
	}

	void bumpSessionKc()
	{
		this.sessionKc++;
	}

	void resetSessionKc()
	{
		this.sessionKc = 0;
	}

	void setCombatLevel(int level)
	{
		this.combatLevel = level;
	}

	void addTotalDropValue(long value)
	{
		this.totalDropValue += value;
		this.sessionTotalDropValue += value;
	}

	void addKeptValue(long delta)
	{
		this.keptValue += delta;
		this.sessionKeptValue += delta;
	}

	/** Adjust the kept quantity for an item id (positive on pickup, negative on re-drop). Floors at 0. */
	void addKeptItem(int itemId, int delta)
	{
		synchronized (this)
		{
			if (keptItems == null)
			{
				keptItems = new HashMap<>();
			}
			final int updated = keptItems.getOrDefault(itemId, 0) + delta;
			if (updated > 0)
			{
				keptItems.put(itemId, updated);
			}
			else
			{
				keptItems.remove(itemId);
			}
		}
	}

	void setEngaged()
	{
		this.engaged = true;
	}

	void touch()
	{
		this.lastSeen = System.currentTimeMillis();
	}

	/** Zeroes lifetime profit only (per-mob / "reset all-time" buttons). */
	void resetLifetimeProfit()
	{
		this.totalDropValue = 0;
		this.keptValue = 0;
		synchronized (this)
		{
			if (keptItems != null)
			{
				keptItems.clear();
			}
		}
	}

	/** Zeroes this-session profit only ("reset session" button). */
	void resetSessionProfit()
	{
		this.sessionTotalDropValue = 0;
		this.sessionKeptValue = 0;
	}

	void addDrop(DropOccurrence drop)
	{
		drops.add(drop);
	}

}


