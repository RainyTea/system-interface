package com.systeminterface.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemInterfacePluginMenuTest
{
	@Test
	public void widgetItemExamineEntry_acceptsEquipmentAndWidgetItemPaths()
	{
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.CC_OP.getId(), "Examine", 4151));
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.CC_OP_LOW_PRIORITY.getId(), "Examine", 4151));
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.WIDGET_FIRST_OPTION.getId(), "Examine", 4151));
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.WIDGET_FIFTH_OPTION.getId(), "Examine", 4151));
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.EXAMINE_ITEM.getId(), "Examine", 4151));
	}

	@Test
	public void widgetItemExamineEntry_acceptsEquipmentWidgetItemFallback()
	{
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.CC_OP.getId(),
			"Examine",
			-1,
			4151,
			componentId(InterfaceID.EQUIPMENT_SIDE, 12)));
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.WIDGET_TYPE_1.getId(),
			"Examine",
			-1,
			4151,
			componentId(InterfaceID.WORNITEMS, 68)));
	}

	@Test
	public void widgetItemExamineEntry_acceptsResolvedWornEquipmentId()
	{
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.CC_OP_LOW_PRIORITY.getId(),
			"Examine",
			-1,
			-1,
			componentId(InterfaceID.EQUIPMENT, 10),
			11665));
		assertTrue(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.CC_OP_LOW_PRIORITY.getId(),
			"Examine",
			-1,
			-1,
			componentId(InterfaceID.WORNITEMS, 23),
			4131));
	}

	@Test
	public void widgetItemExamineEntry_rejectsNonExamineAndMissingItems()
	{
		assertFalse(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.WIDGET_FIRST_OPTION.getId(), "Remove", 4151));
		assertFalse(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.WIDGET_FIRST_OPTION.getId(), "Wear", 4151));
		assertFalse(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.WIDGET_FIRST_OPTION.getId(), "Examine", -1));
		assertFalse(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.EXAMINE_OBJECT.getId(), "Examine", 4151));
	}

	@Test
	public void widgetItemExamineEntry_rejectsUnknownWidgetWithoutMenuEntryItemId()
	{
		assertFalse(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.CC_OP.getId(),
			"Examine",
			-1,
			4151,
			componentId(InterfaceID.BANKMAIN, 12)));
		assertFalse(SystemInterfacePlugin.isWidgetItemExamineEntry(
			MenuAction.WIDGET_TYPE_1.getId(),
			"Remove",
			-1,
			4151,
			componentId(InterfaceID.EQUIPMENT_SIDE, 12)));
	}

	@Test
	public void widgetItemExamineEntry_identifiesEquipmentSurfaces()
	{
		assertTrue(SystemInterfacePlugin.isEquipmentWidget(componentId(InterfaceID.EQUIPMENT, 1)));
		assertTrue(SystemInterfacePlugin.isEquipmentWidget(componentId(InterfaceID.EQUIPMENT_SIDE, 12)));
		assertTrue(SystemInterfacePlugin.isEquipmentWidget(componentId(InterfaceID.WORNITEMS, 68)));
		assertFalse(SystemInterfacePlugin.isEquipmentWidget(componentId(InterfaceID.BANKMAIN, 12)));

		assertEquals("equipment-tab", SystemInterfacePlugin.equipmentSurface(componentId(InterfaceID.EQUIPMENT, 1)));
		assertEquals("equipment-side-tab", SystemInterfacePlugin.equipmentSurface(componentId(InterfaceID.EQUIPMENT_SIDE, 12)));
		assertEquals("view-equipment-stats", SystemInterfacePlugin.equipmentSurface(componentId(InterfaceID.WORNITEMS, 68)));
		assertEquals("unknown", SystemInterfacePlugin.equipmentSurface(componentId(InterfaceID.BANKMAIN, 12)));
	}

	@Test
	public void wornSlotForWidgetId_mapsEquipmentTabAndStatsSlots()
	{
		assertEquals(0, SystemInterfacePlugin.wornSlotForWidgetId(componentId(InterfaceID.EQUIPMENT, 10)));
		assertEquals(3, SystemInterfacePlugin.wornSlotForWidgetId(componentId(InterfaceID.EQUIPMENT, 13)));
		assertEquals(10, SystemInterfacePlugin.wornSlotForWidgetId(componentId(InterfaceID.EQUIPMENT, 18)));
		assertEquals(12, SystemInterfacePlugin.wornSlotForWidgetId(componentId(InterfaceID.EQUIPMENT, 19)));
		assertEquals(10, SystemInterfacePlugin.wornSlotForWidgetId(componentId(InterfaceID.WORNITEMS, 23)));
		assertEquals(12, SystemInterfacePlugin.wornSlotForWidgetId(componentId(InterfaceID.WORNITEMS, 24)));
		assertEquals(-1, SystemInterfacePlugin.wornSlotForWidgetId(componentId(InterfaceID.WORNITEMS, 44)));
		assertEquals(-1, SystemInterfacePlugin.wornSlotForWidgetId(componentId(InterfaceID.BANKMAIN, 12)));
	}

	@Test
	public void equipmentWidgetExamineEntry_requiresExamineAndLowPriorityAction()
	{
		assertTrue(SystemInterfacePlugin.isEquipmentWidgetExamineEntry(
			MenuAction.CC_OP_LOW_PRIORITY.getId(), "Examine", componentId(InterfaceID.EQUIPMENT, 10)));
		assertFalse(SystemInterfacePlugin.isEquipmentWidgetExamineEntry(
			MenuAction.CC_OP.getId(), "Remove", componentId(InterfaceID.EQUIPMENT, 10)));
		assertFalse(SystemInterfacePlugin.isEquipmentWidgetExamineEntry(
			MenuAction.CC_OP_LOW_PRIORITY.getId(), "Examine", componentId(InterfaceID.BANKMAIN, 10)));
	}

	@Test
	public void resolveMenuItemId_prefersMenuEntryItemId()
	{
		assertEquals(4151, SystemInterfacePlugin.resolveMenuItemId(4151, 11840));
		assertEquals(11840, SystemInterfacePlugin.resolveMenuItemId(-1, 11840));
		assertEquals(-1, SystemInterfacePlugin.resolveMenuItemId(-1, -1));
		assertEquals(-1, SystemInterfacePlugin.resolveMenuItemId(0, 0));
	}

	@Test
	public void equipmentMenuAudit_predicateIsNarrowButUseful()
	{
		assertTrue(SystemInterfacePlugin.shouldLogEquipmentMenuEntry("Examine", "", -1, -1, -1));
		assertTrue(SystemInterfacePlugin.shouldLogEquipmentMenuEntry("Remove", "", -1, 4151,
			componentId(InterfaceID.EQUIPMENT_SIDE, 12)));
		assertTrue(SystemInterfacePlugin.shouldLogEquipmentMenuEntry("Inspect", "Worn equipment", -1, -1, -1));
		assertFalse(SystemInterfacePlugin.shouldLogEquipmentMenuEntry("Walk here", "", 0, -1, -1));
		assertFalse(SystemInterfacePlugin.shouldLogEquipmentMenuEntry("Walk here", "", -1, -1, -1));
	}

	@Test
	public void objectExamineReplacement_requiresKnownObservedText()
	{
		assertFalse(SystemInterfacePlugin.shouldReplaceObjectExamine(true, false));
		assertFalse(SystemInterfacePlugin.shouldReplaceObjectExamine(false, true));
		assertTrue(SystemInterfacePlugin.shouldReplaceObjectExamine(true, true));

		assertTrue(SystemInterfacePlugin.isObjectExamineAction(MenuAction.EXAMINE_OBJECT));
		assertTrue(SystemInterfacePlugin.isObjectExamineAction(MenuAction.EXAMINE_WORLD_ENTITY));
		assertFalse(SystemInterfacePlugin.isObjectExamineAction(MenuAction.GAME_OBJECT_FIRST_OPTION));
	}

	@Test
	public void objectExamineCapture_acceptsNativeAndRuneliteObjectExamineClicks()
	{
		assertTrue(SystemInterfacePlugin.shouldTrackObjectExamineClick(
			MenuAction.EXAMINE_OBJECT, "Examine", 1276, 55, 41));
		assertTrue(SystemInterfacePlugin.shouldTrackObjectExamineClick(
			MenuAction.EXAMINE_WORLD_ENTITY, "Examine", 1276, 55, 41));
		assertTrue(SystemInterfacePlugin.shouldTrackObjectExamineClick(
			MenuAction.RUNELITE, "Examine", 1276, 55, 41));

		assertFalse(SystemInterfacePlugin.shouldTrackObjectExamineClick(
			MenuAction.RUNELITE, "Appraise", 1276, 55, 41));
		assertFalse(SystemInterfacePlugin.shouldTrackObjectExamineClick(
			MenuAction.RUNELITE, "Examine", -1, 55, 41));
		assertFalse(SystemInterfacePlugin.shouldTrackObjectExamineClick(
			MenuAction.RUNELITE, "Examine", 1276, 0, 0));
	}

	@Test
	public void systemPopupText_resolvesThievingEffectMessages()
	{
		assertEquals("System: Dodgy necklace crumbled to dust.",
			SystemInterfacePlugin.systemPopupTextForMessage(
				"Your dodgy necklace protects you. It then crumbles to dust."));
		assertEquals("System: Shadow Veil faded.",
			SystemInterfacePlugin.systemPopupTextForMessage("Your Shadow Veil has faded away."));
		assertEquals("System: Gloves of silence degraded.",
			SystemInterfacePlugin.systemPopupTextForMessage("Your gloves of silence are going to fall apart!"));
		assertEquals("System: Coin pouch limit reached.",
			SystemInterfacePlugin.systemPopupTextForMessage(
				"You need to empty your coin pouches before you can continue pickpocketing."));
		assertEquals(null, SystemInterfacePlugin.systemPopupTextForMessage("You pick the warrior's pocket."));
	}

	@Test
	public void rememberNpcAction_storesOnlyAfterPickpocketSourceKnown()
	{
		Map<String, String> remembered = new HashMap<>();
		HashSet<String> pickpocketSources = new HashSet<>();

		assertFalse(SystemInterfacePlugin.rememberNpcAction(remembered, pickpocketSources, "Warrior", "Attack"));
		assertTrue(SystemInterfacePlugin.rememberNpcAction(remembered, pickpocketSources, "Warrior", "Pickpocket"));
		assertEquals("Pickpocket", remembered.get("Warrior"));
		assertTrue(SystemInterfacePlugin.rememberNpcAction(remembered, pickpocketSources, "Warrior", "Talk-to"));
		assertEquals("Talk-to", remembered.get("Warrior"));
	}

	@Test
	public void rememberNpcAction_prioritizesRememberedOptionWhenPresent()
	{
		assertEquals(Arrays.asList("Talk-to", "Attack", "Pickpocket"),
			SystemInterfacePlugin.prioritizedOptionsForTest(
				Arrays.asList("Pickpocket", "Talk-to", "Attack"), "Pickpocket"));
		assertEquals(Arrays.asList("Pickpocket", "Talk-to", "Attack"),
			SystemInterfacePlugin.prioritizedOptionsForTest(
				Arrays.asList("Pickpocket", "Talk-to", "Attack"), "Trade"));
	}

	private static int componentId(int group, int child)
	{
		return (group << 16) | child;
	}
}
