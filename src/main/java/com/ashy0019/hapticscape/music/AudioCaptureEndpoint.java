package com.ashy0019.hapticscape.music;

import java.util.Objects;

/** A local desktop output endpoint that can be captured for Music Sync. */
public final class AudioCaptureEndpoint
{
	public static final String SYSTEM_DEFAULT_ID = "";

	private final String id;
	private final String displayName;
	private final boolean available;

	public AudioCaptureEndpoint(String id, String displayName)
	{
		this(id, displayName, true);
	}

	private AudioCaptureEndpoint(String id, String displayName, boolean available)
	{
		this.id = normalizeId(id);
		this.displayName = normalizeName(displayName, this.id);
		this.available = available;
	}

	public static AudioCaptureEndpoint systemDefault()
	{
		return new AudioCaptureEndpoint(SYSTEM_DEFAULT_ID, "Default system output");
	}

	public static AudioCaptureEndpoint unavailable(String id, String previousName)
	{
		if (normalizeId(id).isEmpty())
		{
			return systemDefault();
		}
		return new AudioCaptureEndpoint(id, previousName, false);
	}

	public static AudioCaptureEndpoint fromPersisted(String id, String displayName)
	{
		String normalizedId = normalizeId(id);
		return normalizedId.isEmpty()
			? systemDefault()
			: new AudioCaptureEndpoint(normalizedId, displayName);
	}

	public String getId()
	{
		return id;
	}

	public String getDisplayName()
	{
		return displayName;
	}

	public boolean isSystemDefault()
	{
		return id.isEmpty();
	}

	public boolean isAvailable()
	{
		return available;
	}

	public String getMenuLabel()
	{
		return available ? displayName : displayName + " (unavailable)";
	}

	@Override
	public boolean equals(Object other)
	{
		return other instanceof AudioCaptureEndpoint
			&& id.equals(((AudioCaptureEndpoint) other).id);
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
		return value == null ? SYSTEM_DEFAULT_ID : value.trim();
	}

	private static String normalizeName(String value, String id)
	{
		String normalized = value == null ? "" : value.trim();
		if (!normalized.isEmpty())
		{
			return normalized;
		}
		return id.isEmpty() ? "Default system output" : "Previously selected output";
	}
}
