package com.systeminterface.services.lookup;

import com.google.gson.Gson;
import com.systeminterface.modules.skills.ResourceData;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ObjectWikiExamineServiceTest
{
	@Test
	public void confidentPage_acceptsExplicitResourceWikiPage()
	{
		ResourceData data = ResourceData.load(new Gson());

		assertEquals("Yew tree",
			ObjectWikiExamineService.confidentPage(data.forObjectId(10822)));
		assertEquals("Copper rocks",
			ObjectWikiExamineService.confidentPage(data.forObjectId(10943)));
	}

	@Test
	public void confidentPage_rejectsGenericResourceWithoutWikiPage()
	{
		ResourceData data = ResourceData.load(new Gson());

		assertNull(ObjectWikiExamineService.confidentPage(data.forObjectId(1276)));
	}

	@Test
	public void confidentObjectPage_acceptsSpecificWorldObjectNames()
	{
		assertEquals("Log pile", ObjectWikiExamineService.confidentObjectPage("Log pile"));
		assertEquals("Yew tree", ObjectWikiExamineService.confidentObjectPage("Yew tree"));
	}

	@Test
	public void confidentObjectPage_rejectsAmbiguousWorldObjectNames()
	{
		assertNull(ObjectWikiExamineService.confidentObjectPage("Tree"));
		assertNull(ObjectWikiExamineService.confidentObjectPage("Rocks"));
		assertNull(ObjectWikiExamineService.confidentObjectPage("Door"));
		assertNull(ObjectWikiExamineService.confidentObjectPage("Crate"));
	}
}
