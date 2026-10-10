package com.ashy0019.hapticscape.remote;

import java.util.Objects;
import java.util.UUID;

/** Controller-bound, uniquely identifiable protected-exit audit event. */
final class UnauthorizedEndNotice
{
	private static final int SCHEMA_VERSION = 1;

	private final int schemaVersion;
	private final String eventId;
	private final String controllerId;
	private final long occurredAtMillis;
	private final String reason;
	private String lockId;

	UnauthorizedEndNotice(
		String eventId,
		String controllerId,
		long occurredAtMillis,
		String reason)
	{
		this.schemaVersion = SCHEMA_VERSION;
		this.eventId = requireUuid(eventId, "eventId");
		this.controllerId = requireUuid(controllerId, "controllerId");
		if (lockId != null) requireUuid(lockId, "lockId");
		if (occurredAtMillis <= 0)
		{
			throw new IllegalArgumentException("occurredAtMillis must be positive");
		}
		this.occurredAtMillis = occurredAtMillis;
		String normalizedReason = reason == null ? "" : reason.trim();
		this.reason = normalizedReason.isEmpty() ? "Unauthorized end" : normalizedReason;
	}

	UnauthorizedEndNotice(String eventId, String controllerId, long occurredAtMillis, String reason, String lockId)
	{
		this(eventId, controllerId, occurredAtMillis, reason);
		this.lockId = lockId == null ? null : requireUuid(lockId, "lockId");
	}

	String getLockId() { return lockId; }

	void validate()
	{
		if (schemaVersion != SCHEMA_VERSION)
		{
			throw new IllegalArgumentException("Unsupported unauthorized-end schema");
		}
		requireUuid(eventId, "eventId");
		requireUuid(controllerId, "controllerId");
		if (lockId != null) requireUuid(lockId, "lockId");
		if (occurredAtMillis <= 0 || reason == null || reason.trim().isEmpty())
		{
			throw new IllegalArgumentException("Invalid unauthorized-end notice");
		}
	}

	String getEventId()
	{
		return eventId;
	}

	String getControllerId()
	{
		return controllerId;
	}

	long getOccurredAtMillis()
	{
		return occurredAtMillis;
	}

	String getReason()
	{
		return reason;
	}

	private static String requireUuid(String value, String field)
	{
		String normalized = Objects.requireNonNull(value, field).trim();
		UUID.fromString(normalized);
		return normalized;
	}
}
