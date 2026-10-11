package com.ashy0019.hapticscape.device;

import java.time.Duration;

/**
 * Central playback policy for every finite haptic source.
 */
public enum HapticEventType
{
	XP_GAIN(HapticPriority.ROUTINE, PlaybackPolicy.DROP_IF_BUSY, Duration.ZERO),
	GENERIC_NOTIFICATION(HapticPriority.GENERIC, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),

	LEVEL_UP(HapticPriority.PROGRESS, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(3)),
	MILESTONE(HapticPriority.PROGRESS, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(3)),

	VALUABLE_DROP(HapticPriority.GAMEPLAY, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(4)),
	INVENTORY_FULL(HapticPriority.GAMEPLAY, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(3)),
	SPECIAL_ATTACK_READY(HapticPriority.GAMEPLAY, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(3)),

	PHRASE_TRIGGER(HapticPriority.DIRECT, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(2)),

	DIRECT_MESSAGE(HapticPriority.DIRECT, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(5)),
	TRADE_REQUEST(HapticPriority.DIRECT, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(5)),
	REMOTE_ACTION(HapticPriority.MANUAL, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),

	MANUAL_PREVIEW(HapticPriority.MANUAL, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),
	ROGUE_UNLOCK(HapticPriority.MANUAL, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),
	BLACKJACK_ACTION(HapticPriority.GAMEPLAY, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(1)),
	BLACKJACK_RESULT(HapticPriority.DIRECT, PlaybackPolicy.INTERRUPT_LOWER_OR_QUEUE, Duration.ofSeconds(2)),
	BLACKJACK_NATURAL(HapticPriority.MANUAL, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),
	LEVEL_99(HapticPriority.CEREMONY, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),

	LOW_HITPOINTS(HapticPriority.URGENT, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),
	LOW_PRAYER(HapticPriority.URGENT, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),
	POISONED_OR_VENOMED(HapticPriority.URGENT, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO),

	PLAYER_DEATH(HapticPriority.CRITICAL, PlaybackPolicy.INTERRUPT_LOWER, Duration.ZERO);

	private final HapticPriority priority;
	private final PlaybackPolicy playbackPolicy;
	private final Duration maximumQueueAge;

	HapticEventType(
		HapticPriority priority,
		PlaybackPolicy playbackPolicy,
		Duration maximumQueueAge)
	{
		this.priority = priority;
		this.playbackPolicy = playbackPolicy;
		this.maximumQueueAge = maximumQueueAge;
	}

	public HapticPriority getPriority()
	{
		return priority;
	}

	public PlaybackPolicy getPlaybackPolicy()
	{
		return playbackPolicy;
	}

	public Duration getMaximumQueueAge()
	{
		return maximumQueueAge;
	}
}
