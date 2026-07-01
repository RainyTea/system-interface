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

	enum SkillingTrackingDisplay
	{
		SIDE_PANEL,
		TARGET_STATUS_OVERLAY
	}

	@ConfigSection(
		position = 100,
		name = "Appraise",
		description = "Right-click Appraise and item Appraise presentation."
	)
	String APPRAISE = "appraise";

	@ConfigSection(
		position = 200,
		name = "Overlay",
		description = "In-game overlays shown while interacting with targets or resources."
	)
	String OVERLAY = "overlay";

	@ConfigSection(
		position = 300,
		name = "Side panel",
		description = "Persistent session tracking shown in the RuneLite side panel."
	)
	String SIDE_PANEL = "sidePanel";

	@ConfigSection(
		position = 400,
		name = "Tracking",
		description = "Progress tracking display choices."
	)
	String TRACKING = "tracking";

	@ConfigSection(
		position = 500,
		name = "Data",
		description = "External data and local cache behavior."
	)
	String DATA = "data";

	@ConfigSection(
		position = 900,
		name = "Debug / Diagnostics",
		description = "Developer diagnostics. Leave these off for normal play."
	)
	String DEBUG = "debug";

	@ConfigItem(
		position = 0,
		keyName = "showSystemPanel",
		name = "Show System Panel",
		description = "Show the System Panel overlay when interacting with a tracked target.",
		section = OVERLAY
	)
	default boolean showSystemPanel()
	{
		return true;
	}

	@ConfigItem(
		position = 1,
		keyName = "compactOverlay",
		name = "Compact overlay",
		description = "Use shorter labels and a narrower panel.",
		section = OVERLAY
	)
	default boolean compactOverlay()
	{
		return false;
	}

	// Applies to both the System Panel and the Skilling overlay.
	// NOTE (pre-hub): min is 0 so we can keep overlays pinned during development. For a
	// hub release, raise the minimum to about 5s; a true "never hide" is poor UX in prod.
	@ConfigItem(
		position = 2,
		keyName = "hideAfterSeconds",
		name = "Auto-hide after (seconds)",
		description = "How long the System Panel and Skilling overlay stay visible after you stop. "
			+ "Set to 0 to keep them visible indefinitely until you switch target or skill.",
		section = OVERLAY
	)
	@Range(min = 0, max = 600)
	default int hideAfterSeconds()
	{
		return 20;
	}

	@ConfigItem(
		position = 3,
		keyName = "enableWikiLookup",
		name = "Fetch OSRS Wiki data",
		description = "On first interaction with supported unknown NPCs or resource objects, fetch OSRS Wiki data and cache it locally. "
			+ "Bundled, observed, and user-supplied data take priority. Cached results are reused with no network round-trip.",
		section = DATA
	)
	default boolean enableWikiLookup()
	{
		return true;
	}

	@ConfigItem(
		position = 4,
		keyName = "showItemRankHover",
		name = "Item rank on hover",
		description = "Show an F-SS rarity rank, GE value, and equipment stats when hovering an item.",
		section = APPRAISE
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
			+ "When disabled, Examine is fully restored.",
		section = APPRAISE
	)
	default boolean replaceExamine()
	{
		return false;
	}

	@ConfigItem(
		position = 6,
		keyName = "showSkillingOverlay",
		name = "Skilling overlay",
		description = "Show the Skilling overlay during resource gathering.",
		section = OVERLAY
	)
	default boolean showSkillingOverlay()
	{
		return true;
	}

	@ConfigItem(
		position = 7,
		keyName = "skillingTrackingDisplay",
		name = "Skilling tracking display",
		description = "Choose where action-specific skilling reward progress appears.",
		section = TRACKING
	)
	default SkillingTrackingDisplay skillingTrackingDisplay()
	{
		return SkillingTrackingDisplay.SIDE_PANEL;
	}

	@ConfigItem(
		position = 8,
		keyName = "rememberNpcAction",
		name = "Remember NPC action",
		description = "Remember the last selected action for compatible pickpocket-capable NPCs and prioritize it in future menus when that action is present.",
		section = TRACKING
	)
	default boolean rememberNpcAction()
	{
		return false;
	}

	@ConfigItem(
		position = 9,
		keyName = "showLiveSkillingStatusInSidePanel",
		name = "Show live skilling status in side panel",
		description = "Show the current skilling status block above side-panel skilling tracking rows.",
		section = SIDE_PANEL
	)
	default boolean showLiveSkillingStatusInSidePanel()
	{
		return false;
	}

	@ConfigItem(
		position = 101,
		keyName = "showCombatStats",
		name = "Weapon & armour stats",
		description = "In the item Appraise window, show the equipment stat block - attack and "
			+ "defence bonuses, strength, prayer and attack speed - for weapons and armour.",
		section = APPRAISE
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
		section = APPRAISE
	)
	default boolean showValuableLoot()
	{
		return true;
	}

	@ConfigItem(
		position = 103,
		keyName = "showLootLog",
		name = "Session Summary section",
		description = "Show the side-panel Session Summary with reward totals.",
		section = SIDE_PANEL
	)
	default boolean showLootLog()
	{
		return true;
	}

	@ConfigItem(
		position = 901,
		keyName = "debugEquipmentMenuEntries",
		name = "Debug equipment Appraise menus",
		description = "Diagnostic developer logging for equipment/widget Examine menu entries. Leave off unless validating Appraise coverage.",
		section = DEBUG
	)
	default boolean debugEquipmentMenuEntries()
	{
		return false;
	}

	// Hidden config keys - managed by the side panel, not shown in plugin settings.

	@ConfigItem(keyName = "trackedItem", hidden = true, name = "", description = "")
	default String trackedItem()
	{
		return "";
	}

	@ConfigItem(keyName = "trackedSkillingItem", hidden = true, name = "", description = "")
	default String trackedSkillingItem()
	{
		return "";
	}
}
