package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureSource;
import com.ashy0019.hapticscape.remote.UnlockKeyProtector;
import org.junit.Test;

import static org.junit.Assert.*;

public class DesktopBackendRoutingTest
{
	@Test
	public void windowsKeepsExistingBackendsWithoutOpeningNativeResources()
	{
		assertTrue(DesktopSecretProtectors.savedUnlockKeys(DesktopPlatform.WINDOWS)
			instanceof WindowsDpapiUnlockKeyProtector);
		assertTrue(DesktopSecretProtectors.discordCredentials(DesktopPlatform.WINDOWS)
			instanceof WindowsDpapiDiscordCredentialProtector);
		assertTrue(DesktopAudioCaptureSources.systemOutput(DesktopPlatform.WINDOWS,
			AudioCaptureEndpoint.systemDefault()) instanceof WasapiLoopbackCapture);
		assertTrue(DesktopAudioCaptureSources.application(DesktopPlatform.WINDOWS,
			new AudioCaptureApplication("example", "Example")) instanceof WasapiApplicationLoopbackCapture);
		assertTrue(DesktopAudioCaptureSources.endpointCatalog(DesktopPlatform.WINDOWS)
			instanceof WasapiAudioEndpointCatalog);
		assertTrue(DesktopAudioCaptureSources.applicationCatalog(DesktopPlatform.WINDOWS)
			instanceof WasapiAudioApplicationCatalog);
	}

	@Test
	public void linuxRoutesOutputAndUnsupportedFeaturesFailClosed()
	{
		for (DesktopPlatform platform : new DesktopPlatform[] {
			DesktopPlatform.LINUX, DesktopPlatform.UNSUPPORTED })
		{
			for (UnlockKeyProtector protector : new UnlockKeyProtector[] {
				DesktopSecretProtectors.savedUnlockKeys(platform),
				DesktopSecretProtectors.discordCredentials(platform) })
			{
				if (platform == DesktopPlatform.LINUX)
				{
					assertTrue(protector instanceof LinuxKeyringSecretProtector);
					assertTrue(protector.requiresBackgroundThread());
					continue; // Routing checks must never write to the real desktop wallet.
				}
				assertFalse(protector.isAvailable());
				assertFalse(protector.getUnavailableMessage().isEmpty());
				for (boolean decrypt : new boolean[] { false, true })
				{
					try
					{
						if (decrypt) protector.unprotect(new byte[] { 1 });
						else protector.protect(new byte[] { 1 });
						fail("Unavailable protection must not return data");
					}
					catch (IllegalStateException expected)
					{
						assertEquals(protector.getUnavailableMessage(), expected.getMessage());
					}
				}
			}
			if (platform == DesktopPlatform.LINUX)
			{
				assertTrue(DesktopAudioCaptureSources.endpointCatalog(platform) instanceof PipeWireAudioEndpointCatalog);
				assertTrue(DesktopAudioCaptureSources.factory(platform).create(
					AudioCaptureEndpoint.systemDefault()) instanceof PipeWireLoopbackCapture);
			}
			else
			{
				assertTrue(DesktopAudioCaptureSources.endpointCatalog(platform).listActiveEndpoints().isEmpty());
				assertUnavailable(DesktopAudioCaptureSources.factory(platform).create(
					AudioCaptureEndpoint.systemDefault()), platform);
			}
			assertTrue(DesktopAudioCaptureSources.applicationCatalog(platform).listActiveApplications().isEmpty());
			assertUnavailable(DesktopAudioCaptureSources.factory(platform).createApplication(
				new AudioCaptureApplication("example", "Example")), platform);
		}
	}

	private static void assertUnavailable(AudioCaptureSource source, DesktopPlatform platform)
	{
		try
		{
			source.start(null);
			fail("Unavailable capture must not start");
		}
		catch (UnsupportedOperationException expected)
		{
			assertTrue(expected.getMessage().contains(platform == DesktopPlatform.LINUX
				? "PipeWire" : "unavailable on this platform"));
		}
		finally
		{
			source.close();
			source.close();
		}
	}
}
