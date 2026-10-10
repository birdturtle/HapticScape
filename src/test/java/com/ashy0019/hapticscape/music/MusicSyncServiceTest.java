package com.ashy0019.hapticscape.music;

import com.ashy0019.hapticscape.device.ConnectionSnapshot;
import com.ashy0019.hapticscape.device.HapticRequest;
import com.ashy0019.hapticscape.device.IntifaceService;
import java.net.URI;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MusicSyncServiceTest
{
	@Test
	public void capturesAnalyzesAndStopsLiveOutput()
	{
		FakeIntifaceService intiface = new FakeIntifaceService();
		FakeCaptureSource capture = new FakeCaptureSource();
		MusicSyncService service = new MusicSyncService(
			intiface,
			() -> capture,
			settings(false)
		);

		service.updateSettings(settings(true));
		assertTrue(capture.started);
		assertEquals(MusicSyncSnapshot.State.RUNNING, service.getSnapshot().getState());

		float[] bass = new float[4_096];
		for (int index = 0; index < bass.length; index++)
		{
			bass[index] = (float) (0.5 * Math.sin(2.0 * Math.PI * 110.0 * index / 48_000.0));
		}
		capture.emit(bass, 48_000);
		assertTrue(intiface.liveIntensity > 0.0);

		service.stopNow();
		assertTrue(capture.closed);
		assertTrue(intiface.liveStopped);
		assertFalse(service.getSettings().isEnabled());
		assertEquals(MusicSyncSnapshot.State.DISABLED, service.getSnapshot().getState());
	}

	@Test
	public void captureFailureBecomesVisibleState()
	{
		FakeIntifaceService intiface = new FakeIntifaceService();
		FakeCaptureSource capture = new FakeCaptureSource();
		MusicSyncService service = new MusicSyncService(
			intiface,
			() -> capture,
			settings(false)
		);

		service.updateSettings(settings(true));
		capture.fail("No output device");

		assertEquals(MusicSyncSnapshot.State.ERROR, service.getSnapshot().getState());
		assertEquals("No output device", service.getSnapshot().getMessage());
		assertTrue(intiface.liveStopped);
	}

	@Test
	public void startupFailurePreservesBackendMessageAndStopsOutput()
	{
		FakeIntifaceService intiface = new FakeIntifaceService();
		boolean[] closed = { false };
		MusicSyncService service = new MusicSyncService(intiface, () -> new AudioCaptureSource()
		{
			@Override
			public void start(Listener listener)
			{
				throw new UnsupportedOperationException("Linux PipeWire capture is not implemented yet");
			}

			@Override
			public void close() { closed[0] = true; }
		}, settings(false));

		service.updateSettings(settings(true));
		assertEquals(MusicSyncSnapshot.State.ERROR, service.getSnapshot().getState());
		assertEquals("Linux PipeWire capture is not implemented yet", service.getSnapshot().getMessage());
		assertTrue(closed[0]);
		assertTrue(intiface.liveStopped);
		service.close();
	}

	@Test
	public void changingEndpointSafelyRestartsActiveCapture()
	{
		FakeIntifaceService intiface = new FakeIntifaceService();
		List<FakeCaptureSource> captures = new ArrayList<>();
		MusicSyncService service = new MusicSyncService(
			intiface,
			endpoint ->
			{
				FakeCaptureSource capture = new FakeCaptureSource();
				captures.add(capture);
				return capture;
			},
			new AudioCaptureEndpoint("endpoint-1", "Speakers"),
			settings(false)
		);

		service.updateSettings(settings(true));
		service.updateCaptureEndpoint(
			new AudioCaptureEndpoint("endpoint-2", "Music channel")
		);

		assertEquals(2, captures.size());
		assertTrue(captures.get(0).closed);
		assertTrue(captures.get(1).started);
		assertEquals("endpoint-2", service.getCaptureEndpoint().getId());
	}

	@Test
	public void applicationModeUsesMixerLevelsAndReplacesEndpointCapture()
	{
		FakeIntifaceService intiface = new FakeIntifaceService();
		List<FakeCaptureSource> endpointCaptures = new ArrayList<>();
		List<FakeCaptureSource> applicationCaptures = new ArrayList<>();
		AudioCaptureSourceFactory factory = new AudioCaptureSourceFactory()
		{
			@Override
			public AudioCaptureSource create(AudioCaptureEndpoint endpoint)
			{
				FakeCaptureSource capture = new FakeCaptureSource();
				endpointCaptures.add(capture);
				return capture;
			}

			@Override
			public AudioCaptureSource createApplication(
				AudioCaptureApplication application)
			{
				FakeCaptureSource capture = new FakeCaptureSource();
				applicationCaptures.add(capture);
				return capture;
			}
		};
		MusicSyncService service = new MusicSyncService(
			intiface,
			factory,
			AudioCaptureMode.OUTPUT,
			AudioCaptureEndpoint.systemDefault(),
			new AudioCaptureApplication("command:spotify.exe", "Spotify"),
			settings(false)
		);

		service.updateSettings(settings(true));
		service.updateCaptureMode(AudioCaptureMode.APPLICATION);
		applicationCaptures.get(0).emitLevel(0.8);

		assertEquals(1, endpointCaptures.size());
		assertTrue(endpointCaptures.get(0).closed);
		assertEquals(1, applicationCaptures.size());
		assertTrue(applicationCaptures.get(0).started);
		assertTrue(intiface.liveIntensity > 0.0);
	}

	private static MusicSyncSettings settings(boolean enabled)
	{
		return new MusicSyncSettings(enabled, MusicResponse.RHYTHMIC, 100, 0, 60);
	}

	private static final class FakeCaptureSource implements AudioCaptureSource
	{
		private Listener listener;
		private boolean started;
		private boolean closed;

		@Override
		public void start(Listener listener)
		{
			this.listener = listener;
			started = true;
			listener.onStarted("Listening");
		}

		private void emit(float[] samples, int sampleRate)
		{
			listener.onSamples(samples, sampleRate, 1.0);
		}

		private void fail(String message)
		{
			listener.onError(message, null);
		}

		private void emitLevel(double level)
		{
			listener.onLevel(level);
		}

		@Override
		public void close()
		{
			closed = true;
		}
	}

	private static final class FakeIntifaceService implements IntifaceService
	{
		private double liveIntensity;
		private boolean liveStopped;

		@Override
		public void setLiveIntensity(double intensity)
		{
			liveIntensity = intensity;
		}

		@Override
		public void stopLiveOutput()
		{
			liveStopped = true;
		}

		@Override public void setRemoteLiveIntensity(double intensity) { }
		@Override public void releaseRemoteLiveOutput(Duration decayDuration) { }
		@Override public void stopRemoteLiveOutput() { }

		@Override public void connect(URI serverUri) { }
		@Override public void disconnect() { }
		@Override public void pulse(double intensity, Duration duration) { }
		@Override public void play(HapticRequest request) { }
		@Override public void stopAll() { }
		@Override public ConnectionSnapshot getSnapshot() { return ConnectionSnapshot.disconnected(); }
		@Override public void setConnectionListener(Consumer<ConnectionSnapshot> listener) { }
		@Override public void close() { }
	}
}
