package com.ashy0019.hapticscape.music;

/** Selects whether Music Sync follows a whole desktop output or one application. */
public enum AudioCaptureMode
{
	OUTPUT("Entire output"),
	APPLICATION("Application audio");

	private final String label;

	AudioCaptureMode(String label)
	{
		this.label = label;
	}

	public static AudioCaptureMode fromConfigValue(String value)
	{
		try
		{
			return valueOf(value == null ? "" : value.trim().toUpperCase());
		}
		catch (IllegalArgumentException ignored)
		{
			return OUTPUT;
		}
	}

	@Override
	public String toString()
	{
		return label;
	}
}
