package com.systeminterface.services.wiki;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class BucketQueryTest
{
	@Test
	public void buildsSelectWhereLimitRun()
	{
		String q = new BucketQuery("dropsline")
			.select("item_name", "drop_json")
			.where("page_name", "General Graardor")
			.limit(500)
			.toQueryString();
		assertEquals("bucket('dropsline').select('item_name','drop_json')"
			+ ".where('page_name','General Graardor').limit(500).run()", q);
	}

	@Test
	public void escapesApostropheInValue()
	{
		String q = new BucketQuery("dropsline").select("item_name")
			.where("page_name", "Vet'ion").toQueryString();
		// Apostrophe must be backslash-escaped or the wiki query parser errors.
		assertEquals("bucket('dropsline').select('item_name').where('page_name','Vet\\'ion').run()", q);
	}
}
