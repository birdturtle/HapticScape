package com.ashy0019.hapticscape.clicker;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class PhraseTriggerCooldownTest
{
	@Test
	public void cooldownIsPerRuleAndExpiresAfterOneSecond()
	{
		AtomicLong clock = new AtomicLong();
		PhraseTriggerCooldown cooldown = new PhraseTriggerCooldown(clock::get);
		assertTrue(cooldown.allow("first"));
		assertFalse(cooldown.allow("first"));
		assertTrue(cooldown.allow("second"));
		clock.set(999_999_999L);
		assertFalse(cooldown.allow("first"));
		clock.incrementAndGet();
		assertTrue(cooldown.allow("first"));
	}
}
