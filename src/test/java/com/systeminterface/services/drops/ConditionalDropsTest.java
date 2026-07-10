package com.systeminterface.services.drops;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConditionalDropsTest
{
	@Test
	public void seededClueKeys_matchCaseInsensitively()
	{
		assertTrue(ConditionalDrops.isConditional("Key (medium)"));
		assertTrue(ConditionalDrops.isConditional("KEY (ELITE)"));
		assertTrue(ConditionalDrops.isConditional("  key (medium) "));
	}

	@Test
	public void unlistedItems_pass()
	{
		assertFalse(ConditionalDrops.isConditional("Bones"));
		assertFalse(ConditionalDrops.isConditional("Tanzanite fang"));
		assertFalse(ConditionalDrops.isConditional(null));
	}

	@Test
	public void injectedSet_provesMechanism()
	{
		Set<String> set = new HashSet<>(Collections.singletonList("synthetic item"));
		assertTrue(ConditionalDrops.isConditional("Synthetic Item", set));
		assertFalse(ConditionalDrops.isConditional("Key (medium)", set));
	}
}
