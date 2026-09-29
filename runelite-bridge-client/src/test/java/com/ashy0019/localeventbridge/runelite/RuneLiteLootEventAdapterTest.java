package com.ashy0019.localeventbridge.runelite;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RuneLiteLootEventAdapterTest
{
	@Test
	public void longUnitPricesRetainValueAndSaturateOnMultiply()
	{
		assertEquals(2L * ((long) Integer.MAX_VALUE + 1L),
			RuneLiteLootEventAdapter.saturatedMultiply((long) Integer.MAX_VALUE + 1L, 2));
		assertEquals(Long.MAX_VALUE,
			RuneLiteLootEventAdapter.saturatedMultiply(Long.MAX_VALUE / 2L + 1L, 2));
	}

	@Test
	public void saturatedAddProtectsNeutralValueFromOverflow()
	{
		assertEquals(30L, RuneLiteLootEventAdapter.saturatedAdd(10L, 20L));
		assertEquals(
			Long.MAX_VALUE,
			RuneLiteLootEventAdapter.saturatedAdd(Long.MAX_VALUE - 5L, 10L)
		);
	}
}
