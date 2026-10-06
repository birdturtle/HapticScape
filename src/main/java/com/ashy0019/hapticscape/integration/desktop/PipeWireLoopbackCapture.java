package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureSource;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Output monitor PCM capture. Default changes restart the owned stream deliberately. */
public final class PipeWireLoopbackCapture implements AudioCaptureSource
{
	private final AudioCaptureEndpoint endpoint;
	private final PipeWireProcesses helpers;
	private final AtomicBoolean started = new AtomicBoolean();
	private volatile boolean closed;
	private volatile Thread worker;

	public PipeWireLoopbackCapture(AudioCaptureEndpoint endpoint) { this(endpoint, new PipeWireProcesses()); }
	PipeWireLoopbackCapture(AudioCaptureEndpoint endpoint, PipeWireProcesses helpers)
	{
		this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
		this.helpers = helpers;
	}

	@Override
	public void start(Listener listener)
	{
		Objects.requireNonNull(listener, "listener");
		if (closed || !started.compareAndSet(false, true)) throw new IllegalStateException("PipeWire capture is already started or closed");
		worker = new Thread(() -> capture(listener), "hapticscape-pipewire-output");
		worker.setDaemon(true);
		worker.start();
	}

	private void capture(Listener listener)
	{
		Session session = null;
		try
		{
			String help = helpers.query(List.of("pw-cat", "--help"), 65536);
			for (String capability : List.of("--target", "--properties", "--raw", "--format", "--channels", "--rate"))
				if (!help.contains(capability)) throw new IllegalStateException("Installed pw-cat lacks " + capability + "; update PipeWire tools");
			long nextGraph = 0;
			while (!closed)
			{
				long now = System.nanoTime();
				if (now >= nextGraph || (session != null && session.failure != null))
				{
					PipeWireGraph graph = PipeWireAudioEndpointCatalog.snapshot(helpers);
					PipeWireGraph.Sink target = graph.resolve(endpoint);
					if (session == null || !target.sameTarget(session.target))
					{
						if (session != null)
						{
							session.close();
							// Feed a complete silent analyzer window while deliberately reconnecting.
							if (!closed) listener.onSamples(new float[2048], PipeWirePcm.SAMPLE_RATE, 1.0);
						}
						session = new Session(target);
					}
					boolean linked = graph.linkedOnlyTo(session.name, target);
					if (linked && !session.linked) session.samples.clear();
					session.linked = linked;
					nextGraph = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(linked ? 500 : 100);
				}
				if (session.failure != null) throw new IllegalStateException("PipeWire capture stopped; check the output and restart Music Sync", session.failure);
				if (System.nanoTime() - session.lastSamples > TimeUnit.SECONDS.toNanos(4)
					|| (!session.linked && System.nanoTime() - session.opened > TimeUnit.SECONDS.toNanos(4)))
					throw new IllegalStateException("PipeWire output did not provide linked PCM; check monitor access and restart Music Sync");
				float[] samples = session.samples.poll(50, TimeUnit.MILLISECONDS);
				if (samples != null && session.linked && !closed)
				{
					if (!session.announced)
					{
						listener.onStarted("PipeWire output: " + session.target.description + (endpoint.isSystemDefault() ? " (following default)" : ""));
						session.announced = true;
					}
					// Monitor PCM may already include gain. Never apply output gain twice.
					if (!closed) listener.onSamples(samples, PipeWirePcm.SAMPLE_RATE, 1.0);
				}
			}
		}
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		catch (RuntimeException failure)
		{
			if (!closed) listener.onError(failure.getMessage() == null ? "PipeWire output capture failed" : failure.getMessage(), failure);
		}
		finally
		{
			if (session != null) session.close();
			helpers.close();
		}
	}

	static List<String> command(PipeWireGraph.Sink target, String name)
	{
		// Use current serial, and forbid policy fallback or metadata moves to another node.
		String properties = "{ \"stream.capture.sink\": true, \"stream.monitor\": true, "
			+ "\"node.dont-fallback\": true, \"node.dont-move\": true, \"node.dont-reconnect\": true, "
			+ "\"node.name\": \"" + name + "\", \"application.name\": \"HapticScape Music Sync\" }";
		return List.of("pw-cat", "--record", "--raw", "--format=f32", "--rate=48000", "--channels=2",
			"--channel-map=FL,FR", "--latency=20ms", "--target=" + target.serial, "--properties=" + properties, "-");
	}

	private final class Session implements AutoCloseable
	{
		final PipeWireGraph.Sink target;
		final String name = "hapticscape.output." + UUID.randomUUID();
		final Process process;
		final ArrayBlockingQueue<float[]> samples = new ArrayBlockingQueue<>(8);
		final long opened = System.nanoTime();
		volatile long lastSamples = opened;
		volatile Throwable failure;
		volatile boolean stopping;
		boolean linked, announced;
		Session(PipeWireGraph.Sink target)
		{
			this.target = target;
			process = helpers.start(command(target, name));
			PipeWireProcesses.reader(process.getErrorStream(), 8192, true);
			Thread reader = new Thread(() ->
			{
				try
				{
					float[] frame;
					while (!closed && !stopping && (frame = PipeWirePcm.read(process.getInputStream())) != null)
					{
						lastSamples = System.nanoTime();
						if (!samples.offer(frame)) { samples.poll(); samples.offer(frame); }
					}
					if (!closed && !stopping) failure = new IOException("PipeWire PCM stream ended");
				}
				catch (IOException error) { if (!closed && !stopping) failure = error; }
			}, "hapticscape-pipewire-pcm");
			reader.setDaemon(true);
			reader.start();
		}
		@Override
		public void close()
		{
			if (stopping) return;
			stopping = true;
			samples.clear();
			helpers.release(process);
		}
	}

	@Override
	public void close()
	{
		closed = true;
		helpers.close();
		Thread current = worker;
		if (current != null) current.interrupt();
	}
}
