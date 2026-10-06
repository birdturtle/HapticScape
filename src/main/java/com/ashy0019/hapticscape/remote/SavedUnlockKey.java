package com.ashy0019.hapticscape.remote;

import java.time.Instant;
import java.util.Base64;
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

	String getProtectedKey()
	{
		return protectedKey;
	}

	SavedUnlockKey withDetails(String nextLabel, String nextNote)
	{
		return new SavedUnlockKey(
			id,
			normalizeLabel(nextLabel),
			lockId,
			subjectId,
			createdAtEpochMillis,
			lastUsedAtEpochMillis,
			normalizeNote(nextNote),
			protectedKey
		);
	}

	SavedUnlockKey withLastUsedAt(long epochMillis)
	{
		return new SavedUnlockKey(
			id,
			label,
			lockId,
			subjectId,
			createdAtEpochMillis,
			epochMillis,
			note,
			protectedKey
		);
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
		if (createdAtEpochMillis <= 0
			|| lastUsedAtEpochMillis < 0
			|| (lastUsedAtEpochMillis > 0
				&& lastUsedAtEpochMillis < createdAtEpochMillis))
		{
			throw new IllegalArgumentException("Invalid saved-key timestamp");
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
