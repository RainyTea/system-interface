package com.systeminterface.services.drops;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.systeminterface.services.wiki.BucketRow;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class DropslineMapperTest
{
	private static final double EPS = 1e-9;
	private static final Gson GSON = new Gson();

	private static BucketRow row(String json)
	{
		return new BucketRow(GSON.fromJson(json, JsonObject.class));
	}

	@Test
	public void parseRarity_alwaysAndFraction()
	{
		assertEquals(1.0, DropslineMapper.parseRarity("Always"), EPS);
		assertEquals(1.0 / 1024.0, DropslineMapper.parseRarity("1/1,024"), EPS);
		assertEquals(1.0 / 128.0, DropslineMapper.parseRarity("1/128"), EPS);
	}

	@Test
	public void parseRarity_variesOrBlank_isNegative()
	{
		assertTrue(DropslineMapper.parseRarity("Varies") < 0);
		assertTrue(DropslineMapper.parseRarity("") < 0);
		assertTrue(DropslineMapper.parseRarity(null) < 0);
	}

	@Test
	public void mapDrop_alwaysDrop_withQuantityRange()
	{
		// Zulrah's scales: Always, 100–299.
		String r = "{\"item_name\":\"Zulrah's scales\",\"drop_json\":\"{\\\"Rarity\\\":\\\"Always\\\","
			+ "\\\"Quantity Low\\\":100,\\\"Quantity High\\\":299,\\\"Rolls\\\":1,\\\"Drop Value\\\":12,"
			+ "\\\"League region\\\":\\\"Tirannwn\\\"}\"}";
		DropTable.Entry e = DropslineMapper.mapDrop(row(r), GSON);
		assertEquals("Zulrah's scales", e.getName());
		assertEquals(1.0, e.getRate(), EPS);
		assertEquals(100, e.getQuantityLow());
		assertEquals(299, e.getQuantityHigh());
		assertEquals(1, e.getRolls());
		assertEquals(12, e.getDropValue());
		assertEquals("Tirannwn", e.getRegion());
	}

	@Test
	public void mapDrop_rareRoll()
	{
		// Tanzanite fang: 1/1,024, 2 rolls, value 66000.
		String r = "{\"item_name\":\"Tanzanite fang\",\"drop_json\":\"{\\\"Rarity\\\":\\\"1/1,024\\\","
			+ "\\\"Quantity Low\\\":1,\\\"Quantity High\\\":1,\\\"Rolls\\\":2,\\\"Drop Value\\\":66000}\"}";
		DropTable.Entry e = DropslineMapper.mapDrop(row(r), GSON);
		assertEquals(1.0 / 1024.0, e.getRate(), EPS);
		assertEquals(2, e.getRolls());
		assertEquals(66000, e.getDropValue());
	}

	@Test
	public void mapDrop_ratelessRow_isNull()
	{
		String r = "{\"item_name\":\"Something\",\"drop_json\":\"{\\\"Rarity\\\":\\\"Varies\\\"}\"}";
		assertNull(DropslineMapper.mapDrop(row(r), GSON));
	}

	@Test
	public void applyMonster_populatesTableFields()
	{
		String r = "{\"name\":\"General Graardor\",\"combat_level\":624,\"hitpoints\":255,"
			+ "\"is_members_only\":true,\"examine\":\"Big and mean.\",\"max_hit\":\"60\","
			+ "\"attack_style\":\"Melee\",\"elemental_weakness\":\"None\",\"slayer_level\":1,"
			+ "\"image\":\"General Graardor.png\"}";
		DropTable t = DropslineMapper.newTable("General Graardor");
		DropslineMapper.applyMonster(t, row(r));
		assertEquals(624, t.getCombatLevel());
		assertEquals(255, t.getMaxHp());
		assertTrue(t.isMembers());
		assertEquals("Big and mean.", t.getExamine());
		assertEquals("60", t.getMaxHit());
		assertEquals("Melee", t.getAttackStyle());
		assertEquals("None", t.getWeakness());
		assertEquals(1, t.getSlayerLevel());
		assertEquals("General Graardor.png", t.getImageFile());
	}

	@Test
	public void nonCombatDropType_isSkipped()
	{
		// Man's pickpocket coins arrive in dropsline as Drop type "thieving" — not combat loot.
		String r = "{\"item_name\":\"Coins\",\"drop_json\":\"{\\\"Rarity\\\":\\\"Always\\\","
			+ "\\\"Drop type\\\":\\\"thieving\\\",\\\"Quantity Low\\\":15,\\\"Quantity High\\\":15}\"}";
		assertNull(DropslineMapper.mapDrop(row(r), GSON));
	}

	@Test
	public void missingDropType_isTreatedAsCombat()
	{
		String r = "{\"item_name\":\"Bones\",\"drop_json\":\"{\\\"Rarity\\\":\\\"Always\\\"}\"}";
		DropTable.Entry e = DropslineMapper.mapDrop(row(r), GSON);
		assertEquals("Bones", e.getName());
	}

	@Test
	public void curatedConditionalDrop_isSkipped()
	{
		// Clue-step key: Drop type "combat", Rarity "Always" — only the curated list catches it.
		String r = "{\"item_name\":\"Key (medium)\",\"drop_json\":\"{\\\"Rarity\\\":\\\"Always\\\","
			+ "\\\"Drop type\\\":\\\"combat\\\"}\"}";
		assertNull(DropslineMapper.mapDrop(row(r), GSON));
	}

	@Test
	public void mapDrop_rareDropTableFlag_presentEmptyString_setsFlag()
	{
		// Bucket serializes boolean true as "" — presence of the field means flagged.
		// Shield left half on Mithril dragon: wiki pre-multiplied effective rate.
		String r = "{\"item_name\":\"Shield left half\",\"rare_drop_table\":\"\","
			+ "\"drop_json\":\"{\\\"Rarity\\\":\\\"1/27,369.03\\\",\\\"Drop type\\\":\\\"combat\\\","
			+ "\\\"Quantity Low\\\":1,\\\"Quantity High\\\":1,\\\"Rolls\\\":1,\\\"Drop Value\\\":66000}\"}";
		DropTable.Entry e = DropslineMapper.mapDrop(row(r), GSON);
		assertTrue(e.isRareDropTable());
		assertEquals(1.0 / 27369.03, e.getRate(), EPS);
	}

	@Test
	public void mapDrop_noRareDropTableField_flagFalse()
	{
		String r = "{\"item_name\":\"Dragon bones\",\"drop_json\":\"{\\\"Rarity\\\":\\\"Always\\\","
			+ "\\\"Quantity Low\\\":1,\\\"Quantity High\\\":1}\"}";
		DropTable.Entry e = DropslineMapper.mapDrop(row(r), GSON);
		assertEquals(false, e.isRareDropTable());
	}

	@Test
	public void mapDrop_flaggedNonCombatRow_stillSkipped()
	{
		// The flag never bypasses the Drop-type gate.
		String r = "{\"item_name\":\"Coins\",\"rare_drop_table\":\"\","
			+ "\"drop_json\":\"{\\\"Rarity\\\":\\\"1/128\\\",\\\"Drop type\\\":\\\"thieving\\\"}\"}";
		assertNull(DropslineMapper.mapDrop(row(r), GSON));
	}

	@Test
	public void parseRarity_commaAndDecimalDenominator()
	{
		assertEquals(1.0 / 27369.03, DropslineMapper.parseRarity("1/27,369.03"), EPS);
	}

	@Test
	public void schemaIsNine()
	{
		assertEquals(9, DropslineMapper.CURRENT_SCHEMA);
		assertEquals(9, DropslineMapper.newTable("X").getSchema());
	}

	@Test
	public void entryLoadedFromLegacyJson_flagDefaultsFalse()
	{
		// Old cached/bundled/user tables predate the field — Gson leaves it at the boolean default.
		DropTable t = GSON.fromJson(
			"{\"target\":\"Man\",\"schema\":8,\"drops\":[{\"name\":\"Bones\",\"rate\":1.0}]}",
			DropTable.class);
		assertEquals(false, t.getDrops().get(0).isRareDropTable());
	}
}
