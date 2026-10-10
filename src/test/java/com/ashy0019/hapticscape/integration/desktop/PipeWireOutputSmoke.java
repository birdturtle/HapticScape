package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureSource;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit live PipeWire check using disposable virtual sinks and generated PCM only. */
public final class PipeWireOutputSmoke
{
	public static void main(String[] args) throws Exception
	{
		if (args.length != 1 || !args[0].equals("--live-virtual-sinks"))
			throw new IllegalArgumentException("Explicit live virtual-sink test flag required");
		String prefix = "hapticscape.test." + UUID.randomUUID();
		AtomicReference<String> error = new AtomicReference<>();
		CountDownLatch started = new CountDownLatch(1), captured = new CountDownLatch(1);
		List<Float> signal = new ArrayList<>();
		try (PipeWireProcesses helpers = new PipeWireProcesses())
		{
			for (String suffix : List.of(".selected", ".other"))
			{
				Process sink = helpers.start(List.of("pw-cli", "--monitor", "create-node", "adapter",
					"{ factory.name = support.null-audio-sink node.name = " + prefix + suffix
					+ " node.description = \"HapticScape disposable test output\" media.class = Audio/Sink"
					+ " audio.position = [ FL FR ] node.driver = true object.linger = false priority.session = -10000 }"));
				PipeWireProcesses.reader(sink.getInputStream(), 65536, true);
				PipeWireProcesses.reader(sink.getErrorStream(), 8192, true);
			}
			PipeWireGraph graph = waitForSinks(helpers, prefix);
			PipeWireGraph.Sink selected = graph.resolve(new AudioCaptureEndpoint(PipeWireGraph.identity(prefix + ".selected"), "Selected"));
			PipeWireGraph.Sink other = graph.resolve(new AudioCaptureEndpoint(PipeWireGraph.identity(prefix + ".other"), "Other"));
			try (PipeWireLoopbackCapture source = new PipeWireLoopbackCapture(new AudioCaptureEndpoint(PipeWireGraph.identity(selected.name), "Selected")))
			{
				source.start(new AudioCaptureSource.Listener()
				{
					@Override public void onStarted(String description) { started.countDown(); }
					@Override public void onError(String message, Throwable failure) { error.set(message); started.countDown(); captured.countDown(); }
					@Override public void onSamples(float[] mono, int rate, double volume)
					{
						if (rate != 48000 || volume != 1.0) error.set("Incorrect PCM rate or duplicated volume scaling");
						synchronized (signal)
						{
							for (float sample : mono) if (signal.size() < 144000) signal.add(sample);
							if (signal.size() >= 96000) captured.countDown();
						}
					}
				});
				Thread selectedPlayback = playback(helpers, selected, 440);
				Thread otherPlayback = playback(helpers, other, 997);
				if (!started.await(7, TimeUnit.SECONDS)) throw new AssertionError("PipeWire capture startup timed out");
				if (error.get() != null) throw new AssertionError(error.get());
				if (!captured.await(7, TimeUnit.SECONDS)) throw new AssertionError("No sustained monitor PCM");
				if (error.get() != null) throw new AssertionError(error.get());
				selectedPlayback.join(5000); otherPlayback.join(5000);
				float[] samples;
				synchronized (signal)
				{
					// Ignore startup silence and graph negotiation; analyze the middle of capture.
					int length = Math.min(48000, signal.size() - 24000);
					if (length < 48000) throw new AssertionError("Insufficient PCM for signal isolation check");
					samples = new float[length];
					for (int i = 0; i < length; i++) samples[i] = signal.get(i + 24000);
				}
				double wanted = amplitude(samples, 440), excluded = amplitude(samples, 997);
				if (wanted < 0.02 || excluded > wanted / 20)
					throw new AssertionError("Output monitor signal/isolation failed: wanted=" + wanted + " other=" + excluded);
				System.out.println("Live PipeWire selected-output PCM passed: 440Hz amplitude=" + wanted + ", excluded 997Hz=" + excluded);
			}
		}
		// Confirm both disposable sinks disappear after closing their owning helpers.
		try (PipeWireProcesses verify = new PipeWireProcesses())
		{
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (PipeWireAudioEndpointCatalog.snapshot(verify).sinks.stream().anyMatch(sink -> sink.name.startsWith(prefix)))
			{
				if (System.nanoTime() > deadline) throw new AssertionError("Disposable output nodes leaked");
				Thread.sleep(50);
			}
		}
		System.out.println("Disposable virtual outputs removed; no microphones, recorded files or desktop defaults used.");
	}

	private static PipeWireGraph waitForSinks(PipeWireProcesses helpers, String prefix) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true)
		{
			PipeWireGraph graph = PipeWireAudioEndpointCatalog.snapshot(helpers);
			if (graph.sinks.stream().filter(sink -> sink.name.startsWith(prefix)).count() == 2) return graph;
			if (System.nanoTime() > deadline) throw new AssertionError("Disposable PipeWire output creation failed");
			Thread.sleep(100);
		}
	}

	private static Thread playback(PipeWireProcesses helpers, PipeWireGraph.Sink sink, int frequency)
	{
		Process player = helpers.start(List.of("pw-cat", "--playback", "--raw", "--format=f32", "--rate=48000", "--channels=2",
			"--target=" + sink.serial, "--properties={ \"node.dont-fallback\": true, \"node.dont-move\": true }", "-"));
		PipeWireProcesses.reader(player.getInputStream(), 8192, true);
		PipeWireProcesses.reader(player.getErrorStream(), 8192, true);
		Thread writer = new Thread(() ->
		{
			try (OutputStream stream = player.getOutputStream())
			{
				ByteBuffer samples = ByteBuffer.allocate(48000 * 8 * 4).order(ByteOrder.nativeOrder());
				for (int i = 0; i < 48000 * 4; i++)
				{
					float sample = (float) (0.1 * Math.sin(2 * Math.PI * frequency * i / 48000));
					samples.putFloat(sample).putFloat(sample);
				}
				stream.write(samples.array());
			}
			catch (Exception failure) { throw new IllegalStateException("Generated PCM playback failed", failure); }
		}, "hapticscape-test-tone");
		writer.setDaemon(true); writer.start();
		return writer;
	}

	private static double amplitude(float[] samples, int frequency)
	{
		double real = 0, imaginary = 0;
		for (int i = 0; i < samples.length; i++)
		{
			double angle = 2 * Math.PI * frequency * i / 48000;
			real += samples[i] * Math.cos(angle); imaginary += samples[i] * Math.sin(angle);
		}
		return 2 * Math.hypot(real, imaginary) / samples.length;
	}
}
