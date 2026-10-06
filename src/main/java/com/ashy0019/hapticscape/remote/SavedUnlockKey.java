package com.ashy0019.hapticscape.remote;

import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;

/** Controller-owned metadata for one platform-protected settings unlock key. */
public final class SavedUnlockKey
{
	static final int MAXIMUM_LABEL_LENGTH = 80;
	static final int MAXIMUM_NOTE_LENGTH = 500;

	private final String id;
	private final String label;
	private final String lockId;
	private final String subjectId;
	private final long createdAtEpochMillis;
	private final long lastUsedAtEpochMillis;
	private final String note;
	private final String protectedKey;
	private long lastUnauthorizedEndEpochMillis;
	private List<ExitEvent> unauthorizedEnds;

	SavedUnlockKey(
		String id,
		String label,
		String lockId,
		String subjectId,
		long createdAtEpochMillis,
		long lastUsedAtEpochMillis,
		String note,
		String protectedKey)
	{
		this.id = id;
		this.label = label;
		this.lockId = lockId;
		this.subjectId = subjectId;
		this.createdAtEpochMillis = createdAtEpochMillis;
		this.lastUsedAtEpochMillis = lastUsedAtEpochMillis;
		this.note = note;
		this.protectedKey = protectedKey;
	}

	public String getId()
	{
		return id;
	}

	public String getLabel()
	{
		return label;
	}

	public String getLockId()
	{
		return lockId;
	}

	public String getSubjectId()
	{
		return subjectId;
	}

	public boolean isProfileKey()
	{
		return subjectId != null;
	}

	public Instant getCreatedAt()
	{
		return Instant.ofEpochMilli(createdAtEpochMillis);
	}

	public Instant getLastUsedAt()
	{
		return lastUsedAtEpochMillis == 0
			? null
			: Instant.ofEpochMilli(lastUsedAtEpochMillis);
	}

	public String getNote()
	{
		return note;
	}

	public Instant getLastUnauthorizedEndAt()
	{
		return lastUnauthorizedEndEpochMillis == 0
			? null : Instant.ofEpochMilli(lastUnauthorizedEndEpochMillis);
	}

	/** Chronological exit history for this particular lock. */
	public List<ExitEvent> getUnauthorizedEnds()
	{
		if (unauthorizedEnds != null) return Collections.unmodifiableList(unauthorizedEnds);
		// Preserve the single notice written by the previous build.
		return lastUnauthorizedEndEpochMillis == 0 ? Collections.emptyList()
			: Collections.singletonList(new ExitEvent("legacy-" + lastUnauthorizedEndEpochMillis,
				lastUnauthorizedEndEpochMillis));
	}

	SavedUnlockKey withUnauthorizedEnd(String eventId, long epochMillis)
	{
		SavedUnlockKey updated = withDetails(label, note);
		List<ExitEvent> events = new ArrayList<>(getUnauthorizedEnds());
		events.add(new ExitEvent(eventId, epochMillis));
		events.sort(java.util.Comparator.comparingLong(event -> event.occurredAtEpochMillis));
		updated.unauthorizedEnds = events;
		updated.lastUnauthorizedEndEpochMillis = events.get(events.size() - 1).occurredAtEpochMillis;
		return updated;
	}

	private SavedUnlockKey retainExitNotice(SavedUnlockKey updated)
	{
		updated.lastUnauthorizedEndEpochMillis = lastUnauthorizedEndEpochMillis;
		updated.unauthorizedEnds = unauthorizedEnds == null ? null : new ArrayList<>(unauthorizedEnds);
		return updated;
	}

	public static final class ExitEvent
	{
		private final String eventId;
		private final long occurredAtEpochMillis;

		private ExitEvent(String eventId, long occurredAtEpochMillis)
		{
			this.eventId = eventId;
			this.occurredAtEpochMillis = occurredAtEpochMillis;
		}

		public String getEventId() { return eventId; }
		public Instant getOccurredAt() { return Instant.ofEpochMilli(occurredAtEpochMillis); }
	}

	String getProtectedKey()
	{
		return protectedKey;
	}

	SavedUnlockKey withDetails(String nextLabel, String nextNote)
	{
		return retainExitNotice(new SavedUnlockKey(
			id,
			normalizeLabel(nextLabel),
			lockId,
			subjectId,
			createdAtEpochMillis,
			lastUsedAtEpochMillis,
			normalizeNote(nextNote),
			protectedKey
		));
	}

	SavedUnlockKey withLastUsedAt(long epochMillis)
	{
		return retainExitNotice(new SavedUnlockKey(
			id,
			label,
			lockId,
			subjectId,
			createdAtEpochMillis,
			epochMillis,
			note,
			protectedKey
		));
	}

	void validate()
	{
		validateUuid(id, "saved-key ID");
		validateUuid(lockId, "lock ID");
		if (subjectId != null)
		{
			validateUuid(subjectId, "subject ID");
		}
		if (!Objects.equals(label, normalizeLabel(label)))
		{
			throw new IllegalArgumentException("Invalid saved-key label");
		}
		if (!Objects.equals(note, normalizeNote(note)))
		{
			throw new IllegalArgumentException("Invalid saved-key note");
		}
		if (lastUnauthorizedEndEpochMillis < 0
			|| createdAtEpochMillis <= 0
			|| lastUsedAtEpochMillis < 0
			|| (lastUsedAtEpochMillis > 0
				&& lastUsedAtEpochMillis < createdAtEpochMillis))
		{
			throw new IllegalArgumentException("Invalid saved-key timestamp");
		}
		Set<String> eventIds = new HashSet<>();
		for (ExitEvent event : getUnauthorizedEnds())
		{
			if (event == null || event.eventId == null || event.eventId.isEmpty()
				|| event.eventId.length() > 80 || event.occurredAtEpochMillis <= 0
				|| !eventIds.add(event.eventId))
				throw new IllegalArgumentException("Invalid exit history");
		}
		try
		{
			byte[] decoded = Base64.getDecoder().decode(
				Objects.requireNonNull(protectedKey, "protectedKey")
			);
			try
			{
				if (decoded.length == 0 || decoded.length > 16_384)
				{
					throw new IllegalArgumentException("Invalid protected unlock key");
				}
			}
			finally
			{
				java.util.Arrays.fill(decoded, (byte) 0);
			}
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw new IllegalArgumentException("Invalid protected unlock key", e);
		}
	}

	static String normalizeLabel(String value)
	{
		String normalized = Objects.requireNonNull(value, "label").trim();
		if (normalized.isEmpty() || normalized.length() > MAXIMUM_LABEL_LENGTH)
		{
			throw new IllegalArgumentException(
				"Label must contain 1 to " + MAXIMUM_LABEL_LENGTH + " characters"
			);
		}
		return normalized;
	}

	static String normalizeNote(String value)
	{
		String normalized = value == null ? "" : value.trim();
		if (normalized.length() > MAXIMUM_NOTE_LENGTH)
		{
			throw new IllegalArgumentException(
				"Note must contain no more than " + MAXIMUM_NOTE_LENGTH + " characters"
			);
		}
		return normalized;
	}

	private static void validateUuid(String value, String name)
	{
		try
		{
			UUID.fromString(Objects.requireNonNull(value, name));
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw new IllegalArgumentException("Invalid " + name, e);
		}
	}
}
