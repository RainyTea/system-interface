package com.systeminterface.core;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(SystemInterfaceConfig.GROUP)
public interface SystemInterfaceConfig extends Config
{
	String GROUP = "system-interface";

	@ConfigSection(
		position = 100,
		name = "Features",
		description = "Show or hide individual System Interface features. Everything is on by default."
	)
	String FEATURES = "features";

	@ConfigSection(
		position = 200,
		name = "Active overlay rows",
		description = "Show or hide individual rows of the Active overlay's skilling view."
	)
	String ACTIVE_ROWS = "activeRows";

	@ConfigItem(
		position = 0,
		keyName = "showActiveOverlay",
		name = "Show Active overlay",
		description = "Show the Active overlay (your current combat target or skilling source) on the game screen."
	)
	default boolean showActiveOverlay()
	{
		return true;
	}

	@ConfigItem(
		position = 1,
		keyName = "compactOverlay",
		name = "Compact overlay",
		description = "Use shorter labels and a narrower panel."
	)
	default boolean compactOverlay()
	{
		return false;
	}

	// Applies to both the System Panel and the Skilling overlay.
	// NOTE (pre-hub): min is 0 so we can keep overlays pinned during development. For a
	// hub release, raise the minimum to ~5s — a true "never hide" is poor UX in prod.
	@ConfigItem(
		position = 2,
		keyName = "hideAfterSeconds",
		name = "Auto-hide after (seconds)",
		description = "How long the System Panel and Skilling overlay stay visible after you stop. "
			+ "Set to 0 to keep them visible indefinitely until you switch target or skill."
	)
	@Range(min = 0, max = 600)
	default int hideAfterSeconds()
	{
		return 20;
	}

	@ConfigItem(
		position = 3,
		keyName = "enableWikiLookup",
		name = "Fetch drop tables from OSRS Wiki",
		description = "On first interaction with an unknown NPC, fetch its drop table from oldschool.runescape.wiki and cache it locally. "
			+ "Bundled and user-supplied tables always take priority. Cached results are reused on subsequent kills with no network round-trip."
	)
	default boolean enableWikiLookup()
	{
		return true;
	}

	@ConfigItem(
		position = 4,
		keyName = "showItemRankHover",
		name = "Item rank on hover",
		description = "Show an F–SS rarity rank, GE value, and equipment stats when hovering an item."
	)
	default boolean showItemRankHover()
	{
		return true;
	}

	@ConfigItem(
		position = 5,
		keyName = "replaceExamine",
		name = "Replace Examine with Appraise",
		description = "Replaces the right-click Examine option with System Interface Appraise on NPCs and items. "
			+ "When disabled, Examine is fully restored."
	)
	default boolean replaceExamine()
	{
		return false;
	}

	// --- Feature toggles (section: Features). All default on. ---

	@ConfigItem(
		position = 101,
		keyName = "showCombatStats",
		name = "Weapon & armour stats",
		description = "In the item Appraise window, show the equipment stat block — attack and "
			+ "defence bonuses, strength, prayer and attack speed — for weapons and armour.",
		section = FEATURES
	)
	default boolean showCombatStats()
	{
		return true;
	}

	@ConfigItem(
		position = 102,
		keyName = "showValuableLoot",
		name = "Valuable loot in Appraise",
		description = "Show the top valuable drops in the NPC Appraise window.",
		section = FEATURES
	)
	default boolean showValuableLoot()
	{
		return true;
	}

	@ConfigItem(
		position = 103,
		keyName = "showLootLog",
		name = "Loot log panel section",
		description = "Show the Loot Log (profitability + itemised loot ledger) section in the side panel.",
		section = FEATURES
	)
	default boolean showLootLog()
	{
		return true;
	}

	@ConfigItem(
		position = 104,
		keyName = "showItemGainPings",
		name = "Item-gain pings",
		description = "Show a brief accumulating toast near the chatbox for each item you gain (loot and gathers).",
		section = FEATURES
	)
	default boolean showItemGainPings()
	{
		return true;
	}

	@ConfigItem(
		position = 105,
		keyName = "pingNotableValueThreshold",
		name = "Notable combat ping value (GP)",
		description = "Combat loot at or above this GP unit value gets a distinct, longer-lived ping. 0 disables the value rule for combat.",
		section = FEATURES
	)
	@Range(min = 0, max = 100_000_000)
	default int pingNotableValueThreshold()
	{
		return 20_000;
	}

	@ConfigItem(
		position = 106,
		keyName = "pingSkillingValueThreshold",
		name = "Notable skilling ping value (GP)",
		description = "Gathered resources at or above this GP unit value get a distinct, longer-lived ping. 0 disables the value rule for skilling.",
		section = FEATURES
	)
	@Range(min = 0, max = 100_000_000)
	default int pingSkillingValueThreshold()
	{
		return 1_000;
	}

	// --- Active overlay skilling row toggles (section: Active overlay rows). ---

	@ConfigItem(position = 201, keyName = "showActivitySource", name = "Activity → source", description = "Show the current activity and gathering source.", section = ACTIVE_ROWS)
	default boolean showActivitySource() { return true; }

	@ConfigItem(position = 202, keyName = "showLevelRow", name = "Level", description = "Show the current skill level.", section = ACTIVE_ROWS)
	default boolean showLevelRow() { return true; }

	@ConfigItem(position = 203, keyName = "showXpHrRow", name = "XP / hr", description = "Show XP per hour.", section = ACTIVE_ROWS)
	default boolean showXpHrRow() { return true; }

	@ConfigItem(position = 204, keyName = "showXpGainedRow", name = "XP gained", description = "Show session XP for the active skill.", section = ACTIVE_ROWS)
	default boolean showXpGainedRow() { return true; }

	@ConfigItem(position = 205, keyName = "showPetOddsRow", name = "Pet odds", description = "Show pet odds at your level (skills with a pet).", section = ACTIVE_ROWS)
	default boolean showPetOddsRow() { return true; }

	@ConfigItem(position = 206, keyName = "showTimeToLevel", name = "Time to next level", description = "Show the estimated time to the next level.", section = ACTIVE_ROWS)
	default boolean showTimeToLevel() { return true; }

	@ConfigItem(position = 207, keyName = "showXpBar", name = "XP progress bar", description = "Show a thin XP progress bar toward the next level.", section = ACTIVE_ROWS)
	default boolean showXpBar() { return true; }

	@ConfigItem(position = 208, keyName = "showActionsRow", name = "Actions", description = "Show the session action count.", section = ACTIVE_ROWS)
	default boolean showActionsRow() { return false; }

	@ConfigItem(position = 209, keyName = "showGpHrRow", name = "GP / hr", description = "Show the value rate of gathered output.", section = ACTIVE_ROWS)
	default boolean showGpHrRow() { return false; }

	// Hidden config keys — managed by the side panel, not shown in plugin settings.

	@ConfigItem(keyName = "trackedItem", hidden = true, name = "", description = "")
	default String trackedItem()
	{
		return "";
	}
}
