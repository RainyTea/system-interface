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

	@ConfigItem(
		position = 0,
		keyName = "showSystemPanel",
		name = "Show System Panel",
		description = "Show the System Panel overlay when interacting with a tracked target"
	)
	default boolean showSystemPanel()
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

	@ConfigItem(
		position = 6,
		keyName = "showSkillingOverlay",
		name = "Skilling overlay",
		description = "Show the Skilling overlay during resource gathering."
	)
	default boolean showSkillingOverlay()
	{
		return true;
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

	// Hidden config keys — managed by the side panel, not shown in plugin settings.

	@ConfigItem(keyName = "trackedItem", hidden = true, name = "", description = "")
	default String trackedItem()
	{
		return "";
	}
}
