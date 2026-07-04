package com.systeminterface.services.state;

import com.systeminterface.common.model.BestiaryRank;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Owns the in-memory map of {@link TargetState} and its on-disk persistence.
 *
 * <p>See the {@code com.systeminterface.state} package javadoc for the
 * full data-source design. This first-pass implementation only handles the
 * <b>live</b> path: in-process mutations + JSON round-trip. Backfill from
 * external sources is handled by {@link BackfillService}.
 *
 * <h2>Threading</h2>
 * Mutations happen on the RuneLite client thread (where game events are
 * delivered). Disk writes run on the injected {@link ScheduledExecutorService}
 * and never touch the client thread. The in-memory collections are
 * concurrency-safe so reads from the overlay's render path are lock-free.
 */
@Slf4j
@Singleton
public final class StateTracker
{
	private static final int SCHEMA_VERSION = 1;
	private static final Path STATE_DIR = RuneLite.RUNELITE_DIR.toPath().resolve("system-interface");

	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final SessionTotals sessionTotals;

	/** Indexed by target name (NPC.getName() / chat-parsed name). */
	private final Map<String, TargetState> targets = new ConcurrentHashMap<>();

	/**
	 * Targets we've observed a chat KC message for in this session. For these
	 * NPCs the chat line is authoritative — we MUST NOT also increment KC
	 * from {@code NpcLootReceived}, or we'd double-count.
	 *
	 * <p>Session-scoped on purpose: it's safe to re-discover. On the first
	 * kill of a session for a chat-tracked NPC, if loot fires before chat
	 * we increment by 1 (matches the value chat is about to set), then chat
	 * adds the NPC to this set and subsequent kills are chat-only.
	 */
	private final java.util.Set<String> chatTrackedTargets = ConcurrentHashMap.newKeySet();

	/** Monotonically increments on every mutation — overlay caches off this. */
	private final AtomicLong generation = new AtomicLong(0);

	/** Currently-active player profile, or {@code null} if logged out. */
	private volatile String activeRsn;

	@Inject
	public StateTracker(Gson gson, ScheduledExecutorService executor, SessionTotals sessionTotals)
	{
		this.gson = gson;
		this.executor = executor;
		this.sessionTotals = sessionTotals;
	}

	// ---------------------------------------------------------------------
	// Profile lifecycle
	// ---------------------------------------------------------------------

	/**
	 * Swap to a new player profile. Flushes the previous profile's state
	 * synchronously to avoid losing data on a fast logout-relog, then loads
	 * the new profile's JSON if it exists.
	 *
	 * @param rsn new active RSN, or {@code null} on logout
	 */
	public synchronized void setActiveProfile(String rsn)
	{
		if (java.util.Objects.equals(rsn, activeRsn))
		{
			return;
		}
		// Flush whatever we have for the outgoing profile before clearing.
		if (activeRsn != null)
		{
			writeToDisk(activeRsn);
		}

		activeRsn = rsn;
		targets.clear();
		chatTrackedTargets.clear();
		generation.incrementAndGet();

		if (rsn != null)
		{
			loadFromDisk(rsn);
		}
		else
		{
			ProfileListener pl = profileListener;
			if (pl != null)
			{
				pl.onProfileCleared();
			}
		}
	}

	public String getActiveProfile()
	{
		return activeRsn;
	}

	public long getGeneration()
	{
		return generation.get();
	}

	/**
	 * Signal that an externally-tracked source changed (e.g. a wiki-fetched
	 * loot table just arrived) and the overlay should rebuild on its next render.
	 * Internal state mutations already do this — only call from outside the
	 * tracker when the displayable state for the current target may have changed.
	 */
	public void bumpGeneration()
	{
		generation.incrementAndGet();
	}

	// ---------------------------------------------------------------------
	// Read API
	// ---------------------------------------------------------------------

	public TargetState get(String targetName)
	{
		if (targetName == null)
		{
			return null;
		}
		return targets.get(targetName);
	}

	public TargetState getOrCreate(String targetName)
	{
		return targets.computeIfAbsent(targetName, TargetState::new);
	}

	// ---------------------------------------------------------------------
	// Mutation API
	// ---------------------------------------------------------------------

	/**
	 * Apply a fresh kill-count value from any source. Internal value is taken
	 * as {@code max(current, observed)} so a slower source never regresses a
	 * faster one.
	 */
	public void observeKillCount(String targetName, int observedKc, KcSource source)
	{
		if (targetName == null || observedKc < 0)
		{
			return;
		}
		// Lock in chat-tracking the moment we ever see a chat KC for this NPC.
		if (source == KcSource.CHAT_MESSAGE)
		{
			chatTrackedTargets.add(targetName);
		}
		TargetState t = getOrCreate(targetName);
		if (observedKc <= t.getCurrentKc())
		{
			return; // no progress — silently drop
		}
		int prev = t.getCurrentKc();
		t.setCurrentKc(observedKc);
		generation.incrementAndGet();
		log.debug("KC '{}' {} -> {} via {}", targetName, prev, observedKc, source);
		scheduleFlush();
	}

	/**
	 * Increment the kill count for {@code targetName} by 1, but ONLY if this
	 * target hasn't been observed via chat in this session. For chat-tracked
	 * NPCs (Vorkath, Zulrah, GWD bosses, etc.) the chat line is authoritative
	 * and we let it set the value to avoid double-counting.
	 *
	 * <p>This is how we keep KC alive for the long tail of NPCs that don't
	 * print a {@code "Your X kill count is: N"} message — regular slayer
	 * monsters, low-level mobs, anything without a server-side KC counter.
	 * Fired from {@code NpcLootReceived}, which is a reliable per-kill proxy
	 * (one loot event per kill for normal NPCs).
	 */
	public void recordKillIfNotChatTracked(String targetName)
	{
		if (targetName == null || chatTrackedTargets.contains(targetName))
		{
			return;
		}
		TargetState t = getOrCreate(targetName);
		int next = t.getCurrentKc() + 1;
		t.setCurrentKc(next);
		generation.incrementAndGet();
		log.debug("KC '{}' incremented to {} via loot event", targetName, next);
		scheduleFlush();
	}

	/**
	 * Bump the session kill counter for {@code targetName} by 1. Called from
	 * every {@code NpcLootReceived} regardless of whether the target is
	 * chat-tracked — session KC is a transient "kills observed since plugin
	 * load" counter and isn't persisted, so double-counting with the chat
	 * lifetime path is not a concern.
	 *
	 * <p>Mirrors the {@code x N} display in RuneLite's Loot Tracker, but as
	 * a counter we maintain ourselves (Loot Tracker doesn't persist its
	 * session count — it's runtime-only in their UI).
	 */
	public void incrementSessionKill(String targetName)
	{
		if (targetName == null)
		{
			return;
		}
		TargetState t = getOrCreate(targetName);
		t.bumpSessionKc();
		generation.incrementAndGet();
		// Intentionally NOT scheduling a flush — session KC is transient (Gson
		// skips it via `transient`).
	}

	/**
	 * Record a specific drop being received at a known kill count.
	 */
	public void recordDrop(String targetName, String dropName, int kc, KcSource source)
	{
		if (targetName == null || dropName == null)
		{
			return;
		}
		TargetState t = getOrCreate(targetName);
		// Defensive: if the source supplied a stale KC but we know better, prefer the higher.
		int effectiveKc = Math.max(kc, t.getCurrentKc());
		DropOccurrence drop = DropOccurrence.now(targetName, dropName, effectiveKc, source);
		t.addDrop(drop);
		generation.incrementAndGet();
		log.debug("Drop '{}/{}' @ kc={} via {}", targetName, dropName, effectiveKc, source);
		scheduleFlush();
	}

	// ---------------------------------------------------------------------
	// Profitability (kept vs total dropped value)
	// ---------------------------------------------------------------------

	/** Add to the lifetime total-dropped value for {@code target}. */
	public void recordDropValue(String target, long value)
	{
		if (target == null || value <= 0)
		{
			return;
		}
		TargetState t = getOrCreate(target);
		t.addTotalDropValue(value);
		t.touch();
		generation.incrementAndGet();
		scheduleFlush();
	}

	/**
	 * Adjust the lifetime kept value for {@code target}. Positive on a loot
	 * pickup, negative when the player re-drops previously-kept loot.
	 */
	public void recordKeptDelta(String target, long delta)
	{
		if (target == null || delta == 0)
		{
			return;
		}
		getOrCreate(target).addKeptValue(delta);
		generation.incrementAndGet();
		scheduleFlush();
		sessionTotals.addReward(delta, java.time.LocalDate.now());
	}

	/**
	 * Adjust the lifetime kept <em>quantity</em> of {@code itemId} for {@code target} —
	 * the itemised counterpart of {@link #recordKeptDelta}. Positive on pickup, negative
	 * when the player re-drops previously-kept loot. Powers the Loot Log.
	 */
	public void recordKeptItem(String target, int itemId, int delta)
	{
		if (target == null || delta == 0 || itemId < 0)
		{
			return;
		}
		getOrCreate(target).addKeptItem(itemId, delta);
		generation.incrementAndGet();
		scheduleFlush();
	}

	/**
	 * Mark a target as engaged (attacked/interacted with) and bump its last-seen
	 * time. Drives the side panel's "engage to reveal loot" gate.
	 */
	public void markEngaged(String targetName)
	{
		if (targetName == null)
		{
			return;
		}
		TargetState t = getOrCreate(targetName);
		boolean changed = !t.isEngaged();
		t.setEngaged();
		t.touch();
		generation.incrementAndGet();
		if (changed)
		{
			scheduleFlush();
		}
	}

	/** Sums profit across all targets into session + all-time kept/total. */
	public ProfitSummary accountProfit()
	{
		long sk = 0, st = 0, ak = 0, at = 0;
		for (TargetState t : targets.values())
		{
			sk += t.getSessionKeptValue();
			st += t.getSessionTotalDropValue();
			ak += t.getKeptValue();
			at += t.getTotalDropValue();
		}
		return new ProfitSummary(sk, st, ak, at);
	}

	/**
	 * Targets with lifetime profit data, seen within {@code maxAgeMillis}, newest
	 * first. Used for the per-mob profit list (stale mobs are hidden).
	 */
	public java.util.List<TargetState> getRecentProfitTargets(long maxAgeMillis)
	{
		long cutoff = System.currentTimeMillis() - maxAgeMillis;
		java.util.List<TargetState> result = new java.util.ArrayList<>();
		for (TargetState t : targets.values())
		{
			if (t.getTotalDropValue() > 0 && t.getLastSeen() >= cutoff)
			{
				result.add(t);
			}
		}
		result.sort((a, b) -> Long.compare(b.getLastSeen(), a.getLastSeen()));
		return result;
	}

	/** Zeroes lifetime profit for every target (keeps the current session). */
	public void resetAllTimeProfit()
	{
		for (TargetState t : targets.values())
		{
			t.resetLifetimeProfit();
		}
		generation.incrementAndGet();
		scheduleFlush();
	}

	/** Zeroes this-session profit for every target. */
	public void resetSessionProfit()
	{
		for (TargetState t : targets.values())
		{
			t.resetSessionProfit();
		}
		generation.incrementAndGet();
	}

	/** Zeroes lifetime profit for a single target. */
	public void resetProfit(String targetName)
	{
		TargetState t = get(targetName);
		if (t != null)
		{
			t.resetLifetimeProfit();
			generation.incrementAndGet();
			scheduleFlush();
		}
	}

	/** Immutable snapshot of account-wide profit. */
	public static final class ProfitSummary
	{
		public final long sessionKept;
		public final long sessionTotal;
		public final long allTimeKept;
		public final long allTimeTotal;

		ProfitSummary(long sessionKept, long sessionTotal, long allTimeKept, long allTimeTotal)
		{
			this.sessionKept = sessionKept;
			this.sessionTotal = sessionTotal;
			this.allTimeKept = allTimeKept;
			this.allTimeTotal = allTimeTotal;
		}
	}

	// ---------------------------------------------------------------------
	// Combat level + Bestiary
	// ---------------------------------------------------------------------

	public void setCombatLevel(String targetName, int combatLevel)
	{
		if (targetName == null || combatLevel <= 0) return;
		TargetState t = getOrCreate(targetName);
		if (t.getCombatLevel() != combatLevel)
		{
			t.setCombatLevel(combatLevel);
			scheduleFlush();
		}
	}

	/**
	 * Compute account-wide luck as a weighted average of per-target z-scores.
	 * Each target's z-score is weighted by its {@link BestiaryRank}.
	 * Only targets with KC > 0 and a known rarest drop rate contribute.
	 *
	 * @param lootTables used to look up the rarest drop rate per target
	 * @return weighted average z-score, or {@code Double.NaN} if no data
	 */
	public double computeAccountLuck(com.systeminterface.services.drops.LootTables lootTables)
	{
		double weightedSum = 0;
		double totalWeight = 0;

		for (TargetState t : targets.values())
		{
			if (t.getCurrentKc() <= 0) continue;

			com.systeminterface.services.drops.DropTable table = lootTables.forTarget(t.getName());
			if (table == null) continue;

			// Find the rarest drop rate for this target
			double rarestRate = Double.MAX_VALUE;
			String rarestName = null;
			for (com.systeminterface.services.drops.DropTable.Entry e : table.getDrops())
			{
				if (e.getName() == null || e.getRate() <= 0.0 || e.getRate() >= 1.0) continue;
				if (e.getName().equalsIgnoreCase("Nothing")) continue;
				if (e.getRate() < rarestRate)
				{
					rarestRate = e.getRate();
					rarestName = e.getName();
				}
			}
			if (rarestName == null) continue;

			int drops = t.countOf(rarestName);
			double expected = com.systeminterface.common.probability.Probability.expectedDrops(rarestRate, t.getCurrentKc());
			double sd = com.systeminterface.common.probability.Probability.stdDev(rarestRate, t.getCurrentKc());
			if (sd <= 0) continue;

			double z = (drops - expected) / sd;
			BestiaryRank rank = BestiaryRank.fromCombatLevel(t.getCombatLevel());
			weightedSum += z * rank.getWeight();
			totalWeight += rank.getWeight();
		}

		return totalWeight > 0 ? weightedSum / totalWeight : Double.NaN;
	}

	/**
	 * Returns all targets with KC > 0, sorted by bestiary rank (highest first).
	 */
	public java.util.Set<String> knownTargetNames()
	{
		return new java.util.HashSet<>(targets.keySet());
	}

	public java.util.List<TargetState> getTrackedTargets()
	{
		java.util.List<TargetState> result = new java.util.ArrayList<>();
		for (TargetState t : targets.values())
		{
			if (t.getCurrentKc() > 0) result.add(t);
		}
		result.sort((a, b) -> Integer.compare(b.getCombatLevel(), a.getCombatLevel()));
		return result;
	}

	// ---------------------------------------------------------------------
	// Persistence
	// ---------------------------------------------------------------------

	/** Synchronously flush state for the active profile (use on shutDown). */
	public synchronized void flushSync()
	{
		if (activeRsn != null)
		{
			writeToDisk(activeRsn);
		}
	}

	private void scheduleFlush()
	{
		final String rsn = activeRsn;
		if (rsn == null)
		{
			return;
		}
		executor.submit(() -> writeToDisk(rsn));
	}

	private synchronized void writeToDisk(String rsn)
	{
		try
		{
			Files.createDirectories(STATE_DIR);
			Path target = STATE_DIR.resolve(sanitize(rsn) + ".json");
			PersistedState ps = new PersistedState();
			ps.version = SCHEMA_VERSION;
			ps.rsn = rsn;
			ps.targets = new HashMap<>(targets);
			ps.sessionDay = sessionTotals.persistDay();
			ps.sessionRewards = sessionTotals.persistRewards();
			ProfileListener pl = profileListener;
			if (pl != null)
			{
				pl.onProfileSaving(ps);
			}
			String json = gson.toJson(ps);
			Files.write(target, json.getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException e)
		{
			log.debug("Failed to write state for '{}'", rsn, e);
		}
	}

	private synchronized void loadFromDisk(String rsn)
	{
		Path target = STATE_DIR.resolve(sanitize(rsn) + ".json");
		if (!Files.exists(target))
		{
			log.debug("No persisted state for '{}' — starting fresh", rsn);
			return;
		}
		try
		{
			String json = new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
			PersistedState ps = gson.fromJson(json, PersistedState.class);
			if (ps != null && ps.targets != null)
			{
				targets.putAll(ps.targets);
				generation.incrementAndGet();
				log.debug("Loaded state for '{}': {} target(s)", rsn, ps.targets.size());
			}
			if (ps != null)
			{
				sessionTotals.loadFrom(ps.sessionDay, ps.sessionRewards);
				ProfileListener pl = profileListener;
				if (pl != null)
				{
					pl.onProfileLoaded(ps);
				}
			}
		}
		catch (IOException | JsonSyntaxException e)
		{
			log.debug("Failed to load state for '{}' — starting fresh", rsn, e);
		}
	}

	private static String sanitize(String rsn)
	{
		// Keep only characters that are safe in filenames across Windows/macOS/Linux.
		return rsn.replaceAll("[^A-Za-z0-9_-]", "_");
	}

	// ---------------------------------------------------------------------
	// Source taxonomy
	// ---------------------------------------------------------------------

	/**
	 * Sources of kill-count and drop data, in roughly increasing real-time-ness.
	 * Used both for the merge ("take max" for KC, "prefer most accurate" for
	 * drops) and for debug logging / "(est.)" UI hints.
	 */
	public enum KcSource
	{
		// ---- Persisted / cached ----
		/** Loaded from our own {@code .runelite/system-interface/<rsn>.json}. */
		PERSISTED_STATE,

		// ---- Local historical sources (the "don't start from zero" set) ----
		/** Parsed from Loot Tracker logs at {@code .runelite/loots/}. */
		LOOT_TRACKER_LOG,
		/** Parsed from screenshot filenames at
		 *  {@code .runelite/screenshots/<rsn>/Boss Kills/<Boss>(<KC>).png}. */
		BOSS_KILL_SCREENSHOT,
		/** Recovered by date-matching a Collection Log screenshot against the
		 *  nearest Boss Kill screenshot for the same NPC. */
		COLLECTION_LOG_SCREENSHOT,

		// ---- Other RuneLite plugins' state ----
		/** Read from RuneLite's bundled {@code chatcommands} plugin config. */
		CHATCOMMANDS_CONFIG,

		// ---- Remote APIs ----
		/** Fetched from {@code secure.runescape.com} hiscores (first-party Jagex). */
		HISCORES,
		/** Estimated from Wise Old Man snapshot history. <b>3rd-party, opt-in,
		 *  IP-disclosure warning required.</b> UI must mark these as
		 *  {@code "KC ~N (est.)"}. */
		WISE_OLD_MAN_ESTIMATE,

		// ---- Live game ----
		/** Parsed from an in-game "Your X kill count is: N" GAMEMESSAGE. */
		CHAT_MESSAGE,
		/** From {@link net.runelite.client.events.NpcLootReceived} combined with
		 *  the currently-known KC at the moment of the event. */
		LIVE_DROP_EVENT
	}

	/** Listener for profile change events — lets SkillTracker load/save skill data. */
	public interface ProfileListener
	{
		void onProfileLoaded(PersistedState state);
		void onProfileSaving(PersistedState state);
		void onProfileCleared();
	}

	private volatile ProfileListener profileListener;

	public void setProfileListener(ProfileListener listener)
	{
		this.profileListener = listener;
	}

	public static final class PersistedState
	{
		public int version;
		public String rsn;
		public Map<String, TargetState> targets;
		public Map<String, com.systeminterface.modules.skills.SkillTracker.SkillPersistence> skills;
		public String sessionDay;
		public long sessionRewards;
	}
}

