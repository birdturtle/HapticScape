package com.ashy0019.hapticscape.music;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioCaptureEndpointTest
{
	@Test
	public void persistedSelectionDefaultsSafelyAndRetainsMissingDeviceLabel()
	{
		AudioCaptureEndpoint fallback = AudioCaptureEndpoint.fromPersisted(null, null);
		assertTrue(fallback.isSystemDefault());
		assertEquals("Default system output", fallback.getDisplayName());

		AudioCaptureEndpoint missing = AudioCaptureEndpoint.unavailable(
			"endpoint-2",
			"Virtual output"
		);
		assertFalse(missing.isAvailable());
		assertEquals("Virtual output (unavailable)", missing.getMenuLabel());
	}

	@Test
	public void identityUsesOpaqueEndpointIdRatherThanFriendlyName()
	{
		assertEquals(
			new AudioCaptureEndpoint("endpoint-1", "Speakers"),
			new AudioCaptureEndpoint("endpoint-1", "Renamed speakers")
		);
	}
}
