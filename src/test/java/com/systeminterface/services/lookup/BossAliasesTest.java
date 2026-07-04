package com.systeminterface.services.lookup;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Unit tests for {@link BossAliases} — the {@code !analyze} shorthand resolver.
 */
public class BossAliasesTest
{
	@Test
	public void resolvesShortAliases()
	{
		assertEquals("Cerberus", BossAliases.canonicalize("cerb"));
		assertEquals("Kalphite Queen", BossAliases.canonicalize("kq"));
		assertEquals("Corporeal Beast", BossAliases.canonicalize("corp"));
		assertEquals("General Graardor", BossAliases.canonicalize("bandos"));
		assertEquals("King Black Dragon", BossAliases.canonicalize("kbd"));
	}

	@Test
	public void isCaseAndWhitespaceInsensitive()
	{
		assertEquals("Corporeal Beast", BossAliases.canonicalize("  CORP "));
		assertEquals("Phosani's Nightmare", BossAliases.canonicalize("phosani   nightmare"));
	}

	@Test
	public void fullCanonicalNameMapsToItself()
	{
		assertEquals("Vorkath", BossAliases.canonicalize("Vorkath"));
		assertEquals("Vet'ion", BossAliases.canonicalize("vet'ion"));
	}

	@Test
	public void awakenedVariantsFoldToBaseBoss()
	{
		assertEquals("Duke Sucellus", BossAliases.canonicalize("duke awakened"));
		assertEquals("Vardorvis", BossAliases.canonicalize("vard awakened"));
		assertEquals("Whisperer", BossAliases.canonicalize("wisp awakened"));
	}

	@Test
	public void unknownInputReturnsNull()
	{
		assertNull(BossAliases.canonicalize("not a boss"));
		assertNull(BossAliases.canonicalize(""));
		assertNull(BossAliases.canonicalize(null));
	}
}
