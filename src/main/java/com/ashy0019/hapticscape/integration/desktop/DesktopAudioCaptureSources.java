package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureEndpointCatalog;
import com.ashy0019.hapticscape.music.AudioCaptureSource;
import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureApplicationCatalog;
import com.ashy0019.hapticscape.music.AudioCaptureSourceFactory;

/** Desktop-host factory for audio capture sources used by HapticScape. */
public final class DesktopAudioCaptureSources
{
	private DesktopAudioCaptureSources()
	{
	}

	/** Returns a capture source for the desktop system-output stream. */
	public static AudioCaptureSource systemOutput()
	{
		return systemOutput(AudioCaptureEndpoint.systemDefault());
	}

	/** Returns a capture source for the selected desktop output endpoint. */
	public static AudioCaptureSource systemOutput(AudioCaptureEndpoint endpoint)
	{
		return systemOutput(DesktopPlatform.current(), endpoint);
	}

	/** Lists active capture outputs for the current desktop platform. */
	public static AudioCaptureEndpointCatalog endpointCatalog()
	{
		return endpointCatalog(DesktopPlatform.current());
	}

	/** Lists playback applications for the current desktop platform. */
	public static AudioCaptureApplicationCatalog applicationCatalog()
	{
		return applicationCatalog(DesktopPlatform.current());
	}

	/** Creates both whole-output and per-application PCM Music Sync sources. */
	public static AudioCaptureSourceFactory factory()
	{
		return factory(DesktopPlatform.current());
	}

	static AudioCaptureSourceFactory factory(DesktopPlatform platform)
	{
		return new AudioCaptureSourceFactory()
		{
			@Override
			public AudioCaptureSource create(AudioCaptureEndpoint endpoint)
			{
				return systemOutput(platform, endpoint);
			}

			@Override
			public AudioCaptureSource createApplication(
				AudioCaptureApplication application)
			{
				return application(platform, application);
			}
		};
	}

	static AudioCaptureSource systemOutput(DesktopPlatform platform, AudioCaptureEndpoint endpoint)
	{
		return platform == DesktopPlatform.WINDOWS
			? new WasapiLoopbackCapture(endpoint) : unavailable(platform);
	}

	static AudioCaptureSource application(DesktopPlatform platform, AudioCaptureApplication application)
	{
		return platform == DesktopPlatform.WINDOWS
			? new WasapiApplicationLoopbackCapture(application) : unavailable(platform);
	}

	static AudioCaptureEndpointCatalog endpointCatalog(DesktopPlatform platform)
	{
		return platform == DesktopPlatform.WINDOWS
			? new WasapiAudioEndpointCatalog() : java.util.Collections::emptyList;
	}

	static AudioCaptureApplicationCatalog applicationCatalog(DesktopPlatform platform)
	{
		return platform == DesktopPlatform.WINDOWS
			? new WasapiAudioApplicationCatalog() : AudioCaptureApplicationCatalog.empty();
	}

	private static AudioCaptureSource unavailable(DesktopPlatform platform)
	{
		String message = platform == DesktopPlatform.LINUX
			? "Music Sync: Linux PipeWire capture is not implemented yet"
			: "Music Sync capture is unavailable on this platform";
		return new AudioCaptureSource()
		{
			@Override
			public void start(Listener listener)
			{
				throw new UnsupportedOperationException(message);
			}

			@Override
			public void close() { }
		};
	}
}
