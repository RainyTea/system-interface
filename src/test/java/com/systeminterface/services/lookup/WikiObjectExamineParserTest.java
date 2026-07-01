package com.systeminterface.services.lookup;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class WikiObjectExamineParserTest
{
	@Test
	public void parsesGenericExamineWhenObjectIdMatchesPage()
	{
		final String wikitext = "{{Infobox Scenery\n"
			+ "|name = Oak tree\n"
			+ "|examine = A beautiful old oak.\n"
			+ "|id1 = 10820\n"
			+ "|id2 = 42395\n"
			+ "}}";

		assertEquals("A beautiful old oak.",
			WikiObjectExamineParser.parseExamineForObject(wikitext, 42395));
	}

	@Test
	public void parsesVersionedExamineForMatchingObjectId()
	{
		final String wikitext = "{{Infobox Scenery\n"
			+ "|name = Yew tree\n"
			+ "|examine1 = A splendid tree.\n"
			+ "|examine2 = A splendid tree.\n"
			+ "|examine3 = A splendid yew tree.\n"
			+ "|id1 = 10822\n"
			+ "|id2 = 36683\n"
			+ "|id3 = 40756\n"
			+ "}}";

		assertEquals("A splendid yew tree.",
			WikiObjectExamineParser.parseExamineForObject(wikitext, 40756));
	}

	@Test
	public void rejectsPageWhenObjectIdIsNotListed()
	{
		final String wikitext = "{{Infobox Scenery\n"
			+ "|name = Door\n"
			+ "|examine = A sturdy door.\n"
			+ "|id = 100\n"
			+ "}}";

		assertNull(WikiObjectExamineParser.parseExamineForObject(wikitext, 101));
	}

	@Test
	public void cleansSimpleWikiMarkup()
	{
		final String wikitext = "{{Infobox Scenery\n"
			+ "|name = Test rocks\n"
			+ "|examine = A [[rocky outcrop|rocky]] '''outcrop'''.<ref>unused</ref>\n"
			+ "|id = 200\n"
			+ "}}";

		assertEquals("A rocky outcrop.",
			WikiObjectExamineParser.parseExamineForObject(wikitext, 200));
	}

	@Test
	public void parsesSpecificWorldObjectPageWhenObjectIdMatches()
	{
		final String wikitext = "{{Infobox Scenery\n"
			+ "|name = Log pile\n"
			+ "|examine = Dead tree parts piled together neatly.\n"
			+ "|id1 = 309\n"
			+ "|id2 = 17322\n"
			+ "|id3 = 20370\n"
			+ "|id4 = 37444\n"
			+ "}}";

		assertEquals("Dead tree parts piled together neatly.",
			WikiObjectExamineParser.parseExamineForObject(wikitext, 309));
	}

	@Test
	public void pageNameFallbackUsesGenericExamineForSpecificMatchingPage()
	{
		final String wikitext = "{{Infobox Scenery\n"
			+ "|name = Log pile\n"
			+ "|examine = Dead tree parts piled together neatly.\n"
			+ "|id1 = 309\n"
			+ "}}";

		assertEquals("Dead tree parts piled together neatly.",
			WikiObjectExamineParser.parseExamineForPageName(wikitext, "Log pile", "Log pile"));
	}

	@Test
	public void pageNameFallbackUsesFirstVersionedExamineWhenNoGenericExists()
	{
		final String wikitext = "{{Infobox Scenery\n"
			+ "|name = Yew tree\n"
			+ "|examine1 = A splendid tree.\n"
			+ "|examine2 = A splendid tree.\n"
			+ "|examine3 = A splendid yew tree.\n"
			+ "|id1 = 10822\n"
			+ "|id2 = 36683\n"
			+ "|id3 = 40756\n"
			+ "}}";

		assertNull(WikiObjectExamineParser.parseExamineForObject(wikitext, 999999));
		assertEquals("A splendid tree.",
			WikiObjectExamineParser.parseExamineForPageName(wikitext, "Yew tree", "Yew tree"));
	}

	@Test
	public void pageNameFallbackRejectsUnrelatedPage()
	{
		final String wikitext = "{{Infobox Scenery\n"
			+ "|name = Log pile\n"
			+ "|examine = Dead tree parts piled together neatly.\n"
			+ "|id1 = 309\n"
			+ "}}";

		assertNull(WikiObjectExamineParser.parseExamineForPageName(wikitext, "Yew tree", "Log pile"));
	}
}
