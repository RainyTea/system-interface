package com.systeminterface.services.drops;

import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PageNameOverridesTest
{
	@Test
	public void overriddenName_resolvesToPage()
	{
		Map<String, String> overrides = new HashMap<>();
		overrides.put("Some Boss", "Some Boss (variant)");
		assertEquals("Some Boss (variant)", PageNameOverrides.resolve("Some Boss", overrides));
	}

	@Test
	public void unmappedName_resolvesToItself()
	{
		assertEquals("General Graardor", PageNameOverrides.resolve("General Graardor", new HashMap<>()));
	}

	@Test
	public void productionResolve_defaultsToIdentity()
	{
		// The curated map ships empty; every name resolves to itself until an override is added.
		assertEquals("General Graardor", PageNameOverrides.resolve("General Graardor"));
	}

	@Test
	public void nullName_isNull()
	{
		assertEquals(null, PageNameOverrides.resolve(null));
	}
}
