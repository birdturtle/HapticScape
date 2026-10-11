package com.ashy0019.hapticscape.clicker;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Prevents repeated matching chat from stacking output; no message text is retained. */
public final class PhraseTriggerCooldown
{
    private static final long DURATION_NANOS = 1_000_000_000L;
    private final LongSupplier clock;
    private final Map<String, Long> lastTriggers = new HashMap<>();

    public PhraseTriggerCooldown() { this(System::nanoTime); }
    PhraseTriggerCooldown(LongSupplier clock) { this.clock = clock; }

    public synchronized boolean allow(String ruleId)
    {
        long now = clock.getAsLong();
        lastTriggers.entrySet().removeIf(entry -> now - entry.getValue() >= DURATION_NANOS);
        if (lastTriggers.containsKey(ruleId)) return false;
        lastTriggers.put(ruleId, now);
        return true;
    }
}
