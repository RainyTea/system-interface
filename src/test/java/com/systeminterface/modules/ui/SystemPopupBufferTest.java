package com.systeminterface.modules.ui;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SystemPopupBufferTest
{
	@Test
	public void popupAppearsDebouncesAndExpires()
	{
		SystemPopupBuffer buffer = new SystemPopupBuffer(3_000L, 1_500L);

		assertTrue(buffer.push("System: Shadow Veil faded.", 1_000L));
		assertEquals("System: Shadow Veil faded.", buffer.activeMessage(1_500L));
		assertFalse(buffer.push("System: Shadow Veil faded.", 2_000L));
		assertEquals("System: Shadow Veil faded.", buffer.activeMessage(3_999L));
		assertNull(buffer.activeMessage(4_000L));
		assertTrue(buffer.push("System: Shadow Veil faded.", 4_001L));
	}
}
