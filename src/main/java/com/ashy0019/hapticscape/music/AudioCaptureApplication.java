package com.ashy0019.hapticscape.music;

import java.util.Objects;

/** One local application currently represented in the desktop playback graph. */
public final class AudioCaptureApplication
{
	private final String id;
	private final String displayName;
	private final boolean available;

	public AudioCaptureApplication(String id, String displayName)
	{
		this(id, displayName, true);
	}

	private AudioCaptureApplication(String id, String displayName, boolean available)
	{
		this.id = normalizeId(id);
		if (this.id.isEmpty())
		{
			throw new IllegalArgumentException("Application id must not be blank");
		}
		this.displayName = normalizeName(displayName);
		this.available = available;
	}

	public static AudioCaptureApplication unavailable(String id, String previousName)
	{
		return new AudioCaptureApplication(id, previousName, false);
	}

	public static AudioCaptureApplication fromPersisted(String id, String displayName)
	{
		String normalizedId = normalizeId(id);
		return normalizedId.isEmpty() ? null : new AudioCaptureApplication(
			normalizedId,
			displayName
		);
	}

	public String getId()
	{
		return id;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public boolean isAvailable()
	{
		return available;
	}

	public String getMenuLabel()
	{
		return available ? displayName : displayName + " (not currently playing)";
	}

	@Override
	public boolean equals(Object other)
	{
		return other instanceof AudioCaptureApplication
			&& id.equals(((AudioCaptureApplication) other).id);
	}

	@Override
	public int hashCode()
	{
		return id.hashCode();
	}

	@Override
	public String toString()
	{
		return getMenuLabel();
	}

	private static String normalizeId(String value)
	{
		return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
	}

	private static String normalizeName(String value)
	{
		String normalized = Objects.toString(value, "").trim();
		return normalized.isEmpty() ? "Previously selected application" : normalized;
	}
}
