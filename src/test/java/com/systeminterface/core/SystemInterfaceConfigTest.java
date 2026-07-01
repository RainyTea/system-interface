package com.systeminterface.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SystemInterfaceConfigTest
{
	private final SystemInterfaceConfig config = new SystemInterfaceConfig()
	{
	};

	@Test
	public void defaults_keepDiagnosticsOffAndCoreSurfacesEnabled()
	{
		assertTrue(config.showSystemPanel());
		assertTrue(config.showSkillingOverlay());
		assertTrue(config.showLootLog());
		assertTrue(config.enableWikiLookup());
		assertTrue(config.showItemRankHover());
		assertTrue(config.showCombatStats());
		assertTrue(config.showValuableLoot());

		assertFalse(config.compactOverlay());
		assertFalse(config.replaceExamine());
		assertFalse(config.showLiveSkillingStatusInSidePanel());
		assertFalse(config.debugEquipmentMenuEntries());
	}

	@Test
	public void defaults_keepSkillingTrackingInSidePanel()
	{
		assertEquals(SystemInterfaceConfig.SkillingTrackingDisplay.SIDE_PANEL,
			config.skillingTrackingDisplay());
	}
}
