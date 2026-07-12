package com.systeminterface.services.lore;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.systeminterface.services.wiki.BucketRow;
import java.util.Arrays;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NpcLoreMapperTest
{
	private static final Gson GSON = new Gson();

	private static BucketRow row(String json)
	{
		return new BucketRow(GSON.fromJson(json, JsonObject.class));
	}

	@Test
	public void stripWikitext_links()
	{
		assertEquals("Lumbridge Castle kitchen", NpcLoreMapper.stripWikitext("[[Lumbridge Castle]] kitchen"));
		assertEquals("Cook's Assistant", NpcLoreMapper.stripWikitext("[[Cook's Assistant|Cook's Assistant]]"));
		assertEquals("B", NpcLoreMapper.stripWikitext("[[A|B]]"));
		assertNull(NpcLoreMapper.stripWikitext(null));
	}

	@Test
	public void map_fullRow()
	{
		String r = "{\"page_name\":\"Cook (Lumbridge)\",\"npc_name\":\"Cook\","
			+ "\"examine\":\"The head cook of Lumbridge castle.\","
			+ "\"location\":\"[[Lumbridge Castle]] kitchen\","
			+ "\"quest\":\"<span style=\\\"user-select:none;\\\">'''&bull;'''</span> [[Cook's Assistant]]"
			+ "<span style=\\\"user-select:none;\\\">'''&bull;'''</span> [[Recipe for Disaster]]\","
			+ "\"image\":[\"File:Cook (Lumbridge).png\"]}";
		NpcLore lore = NpcLoreMapper.map(row(r));
		assertEquals("The head cook of Lumbridge castle.", lore.getExamine());
		assertEquals("Lumbridge Castle kitchen", lore.getLocation());
		assertEquals(Arrays.asList("Cook's Assistant", "Recipe for Disaster"), lore.getQuests());
		assertEquals("Cook (Lumbridge).png", lore.getImageFile());
	}

	@Test
	public void map_absentFields_areNullOrEmpty()
	{
		NpcLore lore = NpcLoreMapper.map(row("{\"page_name\":\"Nobody\",\"npc_name\":\"Nobody\"}"));
		assertNull(lore.getExamine());
		assertNull(lore.getLocation());
		assertNull(lore.getImageFile());
		assertTrue(lore.getQuests().isEmpty());
		assertNull(NpcLoreMapper.map(null));
	}

	/** Exact page_name match preferred; else the first row. */
	@Test
	public void pickBest_prefersExactPageName()
	{
		BucketRow generic = row("{\"page_name\":\"Cook (Lumbridge)\",\"npc_name\":\"Cook\"}");
		BucketRow exact = row("{\"page_name\":\"Cook\",\"npc_name\":\"Cook\"}");
		assertEquals(exact, NpcLoreMapper.pickBest(Arrays.asList(generic, exact), "Cook"));
		assertEquals(generic, NpcLoreMapper.pickBest(Arrays.asList(generic), "Cook"));
		assertNull(NpcLoreMapper.pickBest(null, "Cook"));
	}

	@Test
	public void stripWikitext_decodesHtmlEntities()
	{
		assertEquals("2nd floor[UK] bank",
			NpcLoreMapper.stripWikitext("2nd&nbsp;floor&#91;UK&#93; bank"));
		assertEquals("Fish & chips", NpcLoreMapper.stripWikitext("Fish &amp; chips"));
	}

	@Test
	public void map_locationTruncatesParenthetical()
	{
		String r = "{\"page_name\":\"Banker tutor\",\"npc_name\":\"Banker tutor\","
			+ "\"location\":\"[[Lumbridge Castle]] (2nd&nbsp;floor&#91;UK&#93;3rd&nbsp;floor&#91;US&#93; bank)\"}";
		NpcLore lore = NpcLoreMapper.map(row(r));
		assertEquals("Lumbridge Castle", lore.getLocation());
	}

	/** Hans's quest field: leading MediaWiki strip marker + newline-* bullets (raw capture). */
	@Test
	public void map_questsWithStarBulletsAndUniqMarker()
	{
		String r = "{\"page_name\":\"Hans\",\"npc_name\":\"Hans\","
			+ "\"quest\":\"'\\\"`UNIQ--nowiki-00000000-QINU`\\\"'\n* [[The Lost Tribe]]\n* [[Death to the Dorgeshuun]]\"}";
		NpcLore lore = NpcLoreMapper.map(row(r));
		assertEquals(Arrays.asList("The Lost Tribe", "Death to the Dorgeshuun"), lore.getQuests());
	}

	/** Banker tutor's location (raw capture): span soup + entities + parenthetical → clean prefix. */
	@Test
	public void map_locationFloorTemplateSoup()
	{
		String r = "{\"page_name\":\"Banker tutor\",\"npc_name\":\"Banker tutor\","
			+ "\"location\":\"[[Lumbridge Castle]] (<span class=\\\"floornumber\\\">2<sup>nd</sup>&nbsp;floor"
			+ "<sup>&#91;<span title=\\\"British convention\\\">UK</span>&#93;</sup></span> bank)\"}";
		NpcLore lore = NpcLoreMapper.map(row(r));
		assertEquals("Lumbridge Castle", lore.getLocation());
	}

	/** Double-encoded entities (&amp;nbsp;) decode fully — &amp; must decode first. */
	@Test
	public void stripWikitext_decodesDoubleEncodedEntities()
	{
		assertEquals("2nd floor", NpcLoreMapper.stripWikitext("2nd&amp;nbsp;floor"));
	}

	/** Production strip markers are wrapped in invisible DEL () chars — they must not
	 *  survive as a phantom first quest entry (field bug: Hans showed a blank quest line). */
	@Test
	public void map_questsWithDelWrappedUniqMarker()
	{
		String r = "{\"page_name\":\"Hans\",\"npc_name\":\"Hans\","
			+ "\"quest\":\"\\u007F'\\\"`UNIQ--nowiki-00000000-QINU`\\\"'\\u007F\\n* [[The Lost Tribe]]\\n* [[Death to the Dorgeshuun]]\"}";
		NpcLore lore = NpcLoreMapper.map(row(r));
		assertEquals(Arrays.asList("The Lost Tribe", "Death to the Dorgeshuun"), lore.getQuests());
	}

	/** Father Aereck (raw capture): quests as an HTML <ul><li> list of links — link extraction
	 *  must split them (tag-stripping alone concatenates the names). */
	@Test
	public void map_questsAsHtmlListOfLinks()
	{
		String r = "{\"page_name\":\"Father Aereck\",\"npc_name\":\"Father Aereck\","
			+ "\"quest\":\"<ul><li>[[The Restless Ghost]]</li><li>[[The Lost Tribe]]</li>"
			+ "<li>[[Death to the Dorgeshuun]]</li></ul>\"}";
		NpcLore lore = NpcLoreMapper.map(row(r));
		assertEquals(Arrays.asList("The Restless Ghost", "The Lost Tribe", "Death to the Dorgeshuun"),
			lore.getQuests());
	}
}
