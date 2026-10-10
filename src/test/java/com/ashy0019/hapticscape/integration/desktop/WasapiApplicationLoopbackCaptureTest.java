package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.sun.jna.Memory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WasapiApplicationLoopbackCaptureTest
{
	@Test
	public void activationTargetsTheSelectedProcessAndItsChildren()
	{
		WasapiApplicationLoopbackCapture.AudioClientActivationParams activation =
			WasapiApplicationLoopbackCapture.AudioClientActivationParams
				.includeProcessTree(12_345);

		assertEquals(1, activation.activationType);
		assertEquals(12_345, activation.targetProcessId);
		assertEquals(0, activation.processLoopbackMode);
	}

	@Test
	public void activationParametersArePassedAsAPropVariantBlob()
	{
		WasapiApplicationLoopbackCapture.AudioClientActivationParams activation =
			WasapiApplicationLoopbackCapture.AudioClientActivationParams
				.includeProcessTree(42);
		WasapiApplicationLoopbackCapture.ProcessLoopbackPropVariant parameters =
			WasapiApplicationLoopbackCapture.ProcessLoopbackPropVariant
				.forActivation(activation);

		assertEquals(65, parameters.type);
		assertEquals(activation.size(), parameters.blob.size);
		assertEquals(
			activation.getPointer(),
			parameters.blob.data
		);
	}

	@Test
	public void windowsDesktopFactoryUsesPcmApplicationLoopbackCapture()
	{
		assertTrue(
			DesktopAudioCaptureSources.factory(DesktopPlatform.WINDOWS).createApplication(
				new AudioCaptureApplication("command:c:\\apps\\player.exe", "Player")
			) instanceof WasapiApplicationLoopbackCapture
		);
	}

	@Test
	public void processLoopbackRequestsRealStereoPcmSamples()
	{
		Memory format = WasapiApplicationLoopbackCapture.requestedFormat();

		assertEquals(3, Short.toUnsignedInt(format.getShort(0)));
		assertEquals(2, Short.toUnsignedInt(format.getShort(2)));
		assertEquals(48_000, format.getInt(4));
		assertEquals(8, Short.toUnsignedInt(format.getShort(12)));
		assertEquals(32, Short.toUnsignedInt(format.getShort(14)));
	}

}
