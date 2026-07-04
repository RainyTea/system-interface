package com.systeminterface.services.drops;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the static parsing helpers on {@link WikiDropTableParser}. Pure
 * functions, no network — safe to run in CI.
 */
public class LootTablesTest
{
	private static final double EPS = 1e-9;

	// ---------------------------------------------------------------------
	// parseRarity
	// ---------------------------------------------------------------------

	@Test
	public void parseRarity_always_isOne()
	{
		assertEquals(1.0, WikiDropTableParser.parseRarity("Always"), EPS);
		assertEquals(1.0, WikiDropTableParser.parseRarity("always"), EPS);
	}

	@Test
	public void parseRarity_simpleFraction()
	{
		assertEquals(1.0 / 128.0, WikiDropTableParser.parseRarity("1/128"), EPS);
		assertEquals(1.0 / 5000.0, WikiDropTableParser.parseRarity("1/5000"), EPS);
	}

	@Test
	public void parseRarity_decimalDenominator_wikiPreMultiplied()
	{
		// Man's herb-table entry on the wiki is "1/22.3" (already pre-multiplied
		// by the herb-table access rate). Must parse without rounding errors.
		assertEquals(1.0 / 22.3, WikiDropTableParser.parseRarity("1/22.3"), EPS);
	}

	@Test
	public void parseRarity_commaThousands()
	{
		// "1/1,048" — uncommon-seed-table rarities use thousands separators.
		assertEquals(1.0 / 1048.0, WikiDropTableParser.parseRarity("1/1,048"), EPS);
	}

	@Test
	public void parseRarity_tildePrefix_isStripped()
	{
		assertEquals(1.0 / 64.0, WikiDropTableParser.parseRarity("~1/64"), EPS);
	}

	@Test
	public void parseRarity_multiRollPrefix()
	{
		// "2 × 1/512" → effective 2/512 = 1/256. Accepts both × (U+00D7) and 'x'.
		assertEquals(2.0 / 512.0, WikiDropTableParser.parseRarity("2 \u00d7 1/512"), EPS);
		assertEquals(3.0 / 1000.0, WikiDropTableParser.parseRarity("3 x 1/1000"), EPS);
	}

	@Test
	public void parseRarity_namedRanks_returnNegative()
	{
		// Pre-DRP wiki pages still use named buckets. We can't compute math
		// on these, so they must round-trip to -1 and be skipped at the call site.
		assertTrue(WikiDropTableParser.parseRarity("Common") < 0);
		assertTrue(WikiDropTableParser.parseRarity("Uncommon") < 0);
		assertTrue(WikiDropTableParser.parseRarity("Rare") < 0);
		assertTrue(WikiDropTableParser.parseRarity("Very rare") < 0);
	}

	@Test
	public void parseRarity_neverAndNullAndEmpty_returnNegative()
	{
		assertTrue(WikiDropTableParser.parseRarity("Never") < 0);
		assertTrue(WikiDropTableParser.parseRarity(null) < 0);
		assertTrue(WikiDropTableParser.parseRarity("") < 0);
		assertTrue(WikiDropTableParser.parseRarity("   ") < 0);
	}

	@Test
	public void parseRarity_outOfRange_returnNegative()
	{
		// A fraction > 1 isn't a probability — bail rather than silently truncate.
		assertTrue(WikiDropTableParser.parseRarity("5/4") < 0);
	}

	// ---------------------------------------------------------------------
	// parseDropsLineParams
	// ---------------------------------------------------------------------

	@Test
	public void parseDropsLineParams_minimal()
	{
		DropTable.Entry e = WikiDropTableParser.parseDropsLineParams("name=Bones|rarity=Always");
		assertNotNull(e);
		assertEquals("Bones", e.getName());
		assertEquals(1.0, e.getRate(), EPS);
	}

	@Test
	public void parseDropsLineParams_extraFieldsIgnored()
	{
		// Real DropsLine templates have many extra fields (quantity, smw, gemw, namenotes...).
		// We only need name + rarity; everything else is silently dropped.
		String params = "name=Skeletal visage|quantity=1|rarity=1/5000|smw=yes|namenotes=";
		DropTable.Entry e = WikiDropTableParser.parseDropsLineParams(params);
		assertNotNull(e);
		assertEquals("Skeletal visage", e.getName());
		assertEquals(1.0 / 5000.0, e.getRate(), EPS);
	}

	@Test
	public void parseDropsLineParams_namedRarity_returnsNull()
	{
		DropTable.Entry e = WikiDropTableParser.parseDropsLineParams("name=Mystery item|rarity=Common");
		assertNull(e);
	}

	@Test
	public void parseDropsLineParams_missingName_returnsNull()
	{
		DropTable.Entry e = WikiDropTableParser.parseDropsLineParams("rarity=1/64");
		assertNull(e);
	}

	// ---------------------------------------------------------------------
	// extractDropsLineParams — brace-matching (tolerates nested templates)
	// ---------------------------------------------------------------------

	@Test
	public void extractDropsLineParams_simpleAndClue()
	{
		String wt = "{{DropsLine|name=Bones|rarity=1/1}}\n"
			+ "{{DropsLineClue|type=easy|rarity=1/128}}";
		java.util.List<String> params = WikiDropTableParser.extractDropsLineParams(wt);
		assertEquals(2, params.size());
		assertEquals("name=Bones|rarity=1/1", params.get(0));
		assertEquals("type=easy|rarity=1/128", params.get(1));
	}

	@Test
	public void extractDropsLineParams_recoversNestedTemplate()
	{
		// A DropsLine whose params contain a nested {{GEP|...}} template — the old
		// [^{}]+ regex dropped this entirely. Brace-matching must still recover it.
		String wt = "{{DropsLine|name=Dragon claws|quantity=1|rarity=1/5000|gemw={{GEP|Dragon claws}}}}";
		java.util.List<String> params = WikiDropTableParser.extractDropsLineParams(wt);
		assertEquals(1, params.size());
		DropTable.Entry e = WikiDropTableParser.parseDropsLineParams(params.get(0));
		assertNotNull(e);
		assertEquals("Dragon claws", e.getName());
		assertEquals(1.0 / 5000.0, e.getRate(), EPS);
	}

	@Test
	public void extractDropsLineParams_ignoresNonDropsTemplates()
	{
		String wt = "{{DropsTableHead}}{{DropsLine|name=Coins|rarity=1/2}}{{DropsTableBottom}}";
		java.util.List<String> params = WikiDropTableParser.extractDropsLineParams(wt);
		assertEquals(1, params.size());
		assertEquals("name=Coins|rarity=1/2", params.get(0));
	}

	// ---------------------------------------------------------------------
	// parseDropsFromHtml — scrapes rendered drop rows (incl. transcluded tables)
	// ---------------------------------------------------------------------

	@Test
	public void parseDropsFromHtml_singleAndMultiRoll()
	{
		// Two rendered rows: a single-roll tertiary drop and a multi-roll ("2 ×") drop.
		String html = "<img alt=\"Skeletal visage.png: Vorkath drops Skeletal visage with rarity 1/5000 in quantity 1\">"
			+ "<img alt=\"Rune longsword.png: Vorkath drops Rune longsword with rarity 2 × 5/150 in quantity 2-3\">";
		java.util.List<DropTable.Entry> drops = WikiDropTableParser.parseDropsFromHtml(html);
		assertEquals(2, drops.size());
		assertEquals("Skeletal visage", drops.get(0).getName());
		assertEquals(1.0 / 5000.0, drops.get(0).getRate(), EPS);
		// Multiplier stripped → per-roll 5/150 = 1/30.
		assertEquals("Rune longsword", drops.get(1).getName());
		assertEquals(5.0 / 150.0, drops.get(1).getRate(), EPS);
	}

	@Test
	public void parseDropsFromHtml_alwaysAndEntityName()
	{
		String html = "<img alt=\"x.png: A drops Superior dragon bones with rarity Always in quantity 2\">"
			+ "<img alt=\"y.png: A drops Vet&#39;ion jr. with rarity 1/2000 in quantity 1\">";
		java.util.List<DropTable.Entry> drops = WikiDropTableParser.parseDropsFromHtml(html);
		assertEquals(2, drops.size());
		assertEquals("Superior dragon bones", drops.get(0).getName());
		assertEquals(1.0, drops.get(0).getRate(), EPS);
		assertEquals("Vet'ion jr.", drops.get(1).getName());
	}

	@Test
	public void parseDropsLineParams_missingRarity_returnsNull()
	{
		DropTable.Entry e = WikiDropTableParser.parseDropsLineParams("name=Bones");
		assertNull(e);
	}

	@Test
	public void parseDropsFromHtml_doesNotLeakAcrossTableMarkup()
	{
		// Reproduces the Goblin bug: a stray "drops" token in the drop-table opening
		// markup precedes the header HTML and the first row's alt. The name capture
		// must NOT span the markup (which contains <, >, " and newlines) — only the
		// genuine alt-text row should be extracted.
		String html =
			"<table class=\"item-drops\" data-rowdrops autosort=4,a\" style=\"text-align:center\">\n"
				+ "<tbody><tr>\n<th class=\"item-col\">Item</th>\n<th>Quantity</th>\n<th>Rarity</th>\n"
				+ "<th>Price</th>\n<th>High Alch</th></tr>\n"
				+ "<tr><td><img alt=\"Bones.png: Goblin drops Bones with rarity 1/1 in quantity 1\"></td></tr>";
		java.util.List<DropTable.Entry> drops = WikiDropTableParser.parseDropsFromHtml(html);
		assertEquals(1, drops.size());
		assertEquals("Bones", drops.get(0).getName());
		assertEquals(1.0, drops.get(0).getRate(), EPS);
	}

	// ---------------------------------------------------------------------
	// parseDropsFromWikitext — full wikitext blob → table
	// ---------------------------------------------------------------------

	@Test
	public void parseDropsFromWikitext_extractsAllDropsLines()
	{
		// Synthetic wikitext mimicking the OSRS Wiki's structure (sections + DropsLine templates).
		final String wikitext =
			"== Drops ==\n"
				+ "=== 100% ===\n"
				+ "{{DropsLine|name=Bones|quantity=1|rarity=Always}}\n"
				+ "=== Weapons and armour ===\n"
				+ "{{DropsLine|name=Bronze med helm|quantity=1|rarity=2/128}}\n"
				+ "{{DropsLine|name=Iron dagger|quantity=1|rarity=1/128}}\n"
				+ "=== Herbs ===\n"
				+ "{{DropsLine|name=Grimy guam leaf|quantity=1|rarity=1/22.3}}\n"
				+ "{{DropsLine|name=Grimy ranarr weed|quantity=1|rarity=1/64.8}}\n";

		DropTable table = WikiDropTableParser.parseDropsFromWikitext("Man", wikitext);
		assertNotNull(table);
		assertEquals("Man", table.getTarget());
		assertEquals(5, table.getDrops().size());

		DropTable.Entry bones = table.getDrops().get(0);
		assertEquals("Bones", bones.getName());
		assertEquals(1.0, bones.getRate(), EPS);

		DropTable.Entry ranarr = table.getDrops().get(4);
		assertEquals("Grimy ranarr weed", ranarr.getName());
		assertEquals(1.0 / 64.8, ranarr.getRate(), EPS);
	}

	@Test
	public void parseDropsFromWikitext_skipsUnparseableEntries()
	{
		// Mixed wikitext: some entries have numeric rates, some have named ranks.
		// Only the numeric ones survive.
		final String wikitext =
			"{{DropsLine|name=Real drop|quantity=1|rarity=1/100}}\n"
				+ "{{DropsLine|name=Mystery item|quantity=1|rarity=Common}}\n"
				+ "{{DropsLine|name=Another|quantity=1|rarity=1/200}}\n";

		DropTable table = WikiDropTableParser.parseDropsFromWikitext("Test", wikitext);
		assertNotNull(table);
		assertEquals(2, table.getDrops().size());
		assertEquals("Real drop", table.getDrops().get(0).getName());
		assertEquals("Another", table.getDrops().get(1).getName());
	}

	// ---------------------------------------------------------------------
	// Quest/event-only drop filtering
	// ---------------------------------------------------------------------

	@Test
	public void extractQuestOnlyDropNames_detectsQuestCondition()
	{
		// The Goblin skull's real note: a <ref> saying it only drops during a quest.
		final String wikitext =
			"{{DropsLine|name=Bones|rarity=Always}}\n"
				+ "{{DropsLine|name=Goblin skull|quantity=1|rarity=1/4"
				+ "|raritynotes=<ref>Goblin skulls are only dropped during [[Rag and Bone Man I]].</ref>}}\n"
				+ "{{DropsLine|name=Coins|rarity=1/2}}";
		java.util.Set<String> questOnly = WikiDropTableParser.extractQuestOnlyDropNames(wikitext);
		assertEquals(1, questOnly.size());
		assertTrue(questOnly.contains("goblin skull"));
	}

	@Test
	public void extractQuestOnlyDropNames_ignoresConditionalRateNotes()
	{
		// "only ... when wearing" is a rate modifier, not a quest gate — must NOT match.
		final String wikitext =
			"{{DropsLine|name=Some seed|rarity=1/50|raritynotes=Rate shown is only when wearing a ring of wealth.}}";
		assertTrue(WikiDropTableParser.extractQuestOnlyDropNames(wikitext).isEmpty());
	}

	@Test
	public void parseDropsFromWikitext_dropsQuestOnlyEntries()
	{
		final String wikitext =
			"{{DropsLine|name=Bones|rarity=Always}}\n"
				+ "{{DropsLine|name=Goblin skull|rarity=1/4"
				+ "|raritynotes=Only dropped during [[Rag and Bone Man I]].}}\n";
		DropTable table = WikiDropTableParser.parseDropsFromWikitext("Goblin", wikitext);
		assertNotNull(table);
		assertEquals(1, table.getDrops().size());
		assertEquals("Bones", table.getDrops().get(0).getName());
	}

	@Test
	public void parseDropsFromWikitext_noDropsLines_returnsNull()
	{
		DropTable table = WikiDropTableParser.parseDropsFromWikitext("Test", "== Just some prose, no templates ==");
		assertNull(table);
	}

	@Test
	public void parseDropsFromWikitext_emptyInput_returnsNull()
	{
		assertNull(WikiDropTableParser.parseDropsFromWikitext("Test", ""));
		assertNull(WikiDropTableParser.parseDropsFromWikitext("Test", null));
	}
}

