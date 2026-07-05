package com.systeminterface.services.wiki;

import com.google.gson.Gson;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BucketClientTest
{
	private static final Gson GSON = new Gson();

	@Test
	public void parsesSuccessRows()
	{
		String json = "{\"bucketQuery\":\"q\",\"bucket\":[{\"item_name\":\"Big bones\",\"item_id\":[\"532\"]}]}";
		BucketClient.BucketResult r = BucketClient.parse(json, GSON);
		assertTrue(r.ok);
		assertEquals(1, r.rows.size());
		assertEquals("Big bones", r.rows.get(0).str("item_name"));
		// item_id is a JSON array; str() returns the first element.
		assertEquals("532", r.rows.get(0).str("item_id"));
	}

	@Test
	public void parsesErrorEnvelope()
	{
		String json = "{\"bucketQuery\":\"q\",\"error\":\"Field x not found in bucket y.\"}";
		BucketClient.BucketResult r = BucketClient.parse(json, GSON);
		assertFalse(r.ok);
		assertTrue(r.rows.isEmpty());
	}

	@Test
	public void emptyBucketIsOkWithNoRows()
	{
		BucketClient.BucketResult r = BucketClient.parse("{\"bucket\":[]}", GSON);
		assertTrue(r.ok);
		assertTrue(r.rows.isEmpty());
	}

	@Test
	public void intAndBoolAccessors()
	{
		String json = "{\"bucket\":[{\"combat_level\":624,\"is_members_only\":true}]}";
		List<BucketRow> rows = BucketClient.parse(json, GSON).rows;
		assertEquals(Integer.valueOf(624), rows.get(0).intOrNull("combat_level"));
		assertTrue(rows.get(0).bool("is_members_only", false));
	}
}
