package com.systeminterface.services.wiki;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class BucketRowIntsTest
{
	@Test
	public void ints_returnsAllArrayElements()
	{
		JsonObject o = new Gson().fromJson("{\"id\":[\"10822\",\"36683\"]}", JsonObject.class);
		List<Integer> ids = new BucketRow(o).ints("id");
		assertEquals(2, ids.size());
		assertEquals(Integer.valueOf(10822), ids.get(0));
		assertEquals(Integer.valueOf(36683), ids.get(1));
	}

	@Test
	public void ints_scalar_returnsSingleton()
	{
		JsonObject o = new Gson().fromJson("{\"id\":42}", JsonObject.class);
		assertEquals(1, new BucketRow(o).ints("id").size());
	}
}
