package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureSource;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owned, non-lingering taps of selected playback ports; original playback stays linked. */
public final class PipeWireApplicationCapture implements AudioCaptureSource
{
	static final long QUIET_MILLIS = 150;
	private final AudioCaptureApplication application;
	private final PipeWireProcesses helpers;
	private final AtomicBoolean started = new AtomicBoolean();
	private volatile boolean closed;
	private volatile Thread worker;

	public PipeWireApplicationCapture(AudioCaptureApplication application) { this(application, new PipeWireProcesses()); }
	PipeWireApplicationCapture(AudioCaptureApplication application, PipeWireProcesses helpers)
	{
		this.application = Objects.requireNonNull(application, "application");
		this.helpers = Objects.requireNonNull(helpers, "helpers");
	}

	@Override
	public void start(Listener listener)
	{
		Objects.requireNonNull(listener, "listener");
		if (closed || !started.compareAndSet(false, true)) throw new IllegalStateException("PipeWire application capture is already started or closed");
		worker = new Thread(() -> capture(listener), "hapticscape-pipewire-application");
		worker.setDaemon(true);
		worker.start();
	}

	private void capture(Listener listener)
	{
		Session session = null;
		try
		{
			String help = helpers.query(List.of("pw-cat", "--help"), 65536);
			for (String capability : List.of("--target", "--properties", "--raw", "--format", "--channels", "--channel-map", "--rate"))
				if (!help.contains(capability)) throw new IllegalStateException("Installed pw-cat lacks " + capability + "; update PipeWire tools");
			help = helpers.query(List.of("pw-link", "--help"), 65536);
			if (!help.contains("--monitor") || !help.contains("--props")) throw new IllegalStateException("Installed pw-link lacks owned link support; update PipeWire tools");
			long nextGraph = 0;
			boolean selectedOnce = false;
			boolean waiting = false, silenceSent = false;
			while (!closed)
			{
				if (System.nanoTime() >= nextGraph || (session != null
					&& (session.failure != null || session.links.stream().anyMatch(link -> !link.isAlive()))))
				{
					PipeWireApplicationGraph graph = PipeWireApplicationGraph.snapshot(helpers);
					PipeWireApplicationGraph.Group group = graph.resolve(application);
					if (group == null || group.ports.isEmpty())
					{
						if (!selectedOnce) throw new IllegalStateException("Selected application has no playback ports; start playback and refresh applications");
						if (session != null)
						{
							session.close(); session = null;
						}
						if (!waiting && !closed)
						{
							silence(listener);
							listener.onStarted("Waiting for " + application.getDisplayName() + " playback");
							waiting = true; silenceSent = true;
						}
					}
					else
					{
						selectedOnce = true; waiting = false;
						String signature = group.signature();
						if (session == null || !session.signature.equals(signature))
						{
							if (session != null)
							{
								session.close();
								if (!closed) silence(listener);
							}
							session = new Session(group);
						}
						if (session.routes.isEmpty())
						{
							List<PipeWireApplicationGraph.Route> routes = graph.routes(group, session.name);
							if (!routes.isEmpty()) session.link(routes);
						}
						boolean linked = graph.linkedOnlyTo(session.name, session.routes);
						if (linked && !session.linked) session.samples.clear();
						session.linked = linked;
						session.silent = graph.silent(group);
					}
					nextGraph = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(session != null && session.linked ? 250 : 100);
				}
				if (session == null) { Thread.sleep(50); continue; }
				if (session.failure != null) throw new IllegalStateException("Application PCM stopped; restart playback and Music Sync", session.failure);
				for (Process link : session.links)
					if (!link.isAlive()) throw new IllegalStateException("PipeWire application link failed; check port access and restart Music Sync");
				if ((!session.linked && System.nanoTime() - session.opened > TimeUnit.SECONDS.toNanos(4))
					|| (!session.announced && System.nanoTime() - session.lastSamples > TimeUnit.SECONDS.toNanos(4)))
					throw new IllegalStateException("Application capture did not provide linked PCM; check playback/port access and restart Music Sync");
				float[] samples = session.samples.poll(50, TimeUnit.MILLISECONDS);
				if (session.linked && !session.announced && (samples != null || session.silent) && !closed)
				{
					listener.onStarted("PipeWire application: " + application.getDisplayName());
					session.announced = true;
				}
				boolean quiet = session.silent || !session.linked
					|| (samples == null && System.nanoTime() - session.lastSamples >= TimeUnit.MILLISECONDS.toNanos(QUIET_MILLIS))
					|| (samples != null && allZero(samples));
				if (quiet)
				{
					if (!silenceSent && !closed) { silence(listener); silenceSent = true; }
				}
				else if (samples != null && !closed)
				{
					if (!closed) listener.onSamples(samples, PipeWirePcm.SAMPLE_RATE, 1.0);
					silenceSent = false;
				}
			}
		}
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		catch (RuntimeException failure)
		{
			if (!closed) listener.onError(failure.getMessage() == null ? "PipeWire application capture failed" : failure.getMessage(), failure);
		}
		finally
		{
			if (session != null) session.close();
			helpers.close();
		}
	}
	private static void silence(Listener listener)
	{
		// Zero gain resets the analyzer immediately, rather than one smoothed silent FFT.
		listener.onSamples(new float[0], PipeWirePcm.SAMPLE_RATE, 0.0);
	}
	private static boolean allZero(float[] samples)
	{
		for (float sample : samples) if (sample != 0) return false;
		return true;
	}

	static List<String> command(List<String> channels, String name)
	{
		JsonObject props = new JsonObject();
		props.addProperty("node.name", name);
		props.addProperty("application.name", "HapticScape Music Sync");
		props.addProperty("node.autoconnect", false);
		props.addProperty("node.dont-fallback", true);
		props.addProperty("node.dont-move", true);
		props.addProperty("node.dont-reconnect", true);
		return List.of("pw-cat", "--record", "--raw", "--format=f32", "--rate=48000",
			"--channels=" + channels.size(), "--channel-map=" + String.join(",", channels), "--latency=20ms",
			"--target=0", "--properties=" + props, "-");
	}

	private final class Session implements AutoCloseable
	{
		final String signature, name = "hapticscape.application." + UUID.randomUUID();
		final Process process;
		final List<Process> links = new ArrayList<>();
		List<PipeWireApplicationGraph.Route> routes = List.of();
		final ArrayBlockingQueue<float[]> samples = new ArrayBlockingQueue<>(8);
		final long opened = System.nanoTime();
		volatile long lastSamples = opened;
		volatile Throwable failure;
		volatile boolean stopping;
		boolean linked, announced, silent;
		Session(PipeWireApplicationGraph.Group group)
		{
			signature = group.signature();
			process = helpers.start(command(group.channels, name));
			PipeWireProcesses.reader(process.getErrorStream(), 8192, true);
			Thread reader = new Thread(() ->
			{
				try
				{
					float[] frame;
					while (!closed && !stopping && (frame = PipeWirePcm.read(process.getInputStream(), group.channels.size())) != null)
					{
						lastSamples = System.nanoTime();
						if (!samples.offer(frame)) { samples.poll(); samples.offer(frame); }
					}
					if (!closed && !stopping) failure = new IOException("Application PCM stream ended");
				}
				catch (IOException error) { if (!closed && !stopping) failure = error; }
			}, "hapticscape-pipewire-application-pcm");
			reader.setDaemon(true); reader.start();
		}
		void link(List<PipeWireApplicationGraph.Route> routes)
		{
			for (PipeWireApplicationGraph.Route route : routes)
			{
				Process link = helpers.start(List.of("pw-link", "--monitor", "--props={\"object.linger\":false}",
					Integer.toString(route.output.id), Integer.toString(route.input.id)));
				links.add(link);
				PipeWireProcesses.reader(link.getInputStream(), 8192, true);
				PipeWireProcesses.reader(link.getErrorStream(), 8192, true);
			}
			this.routes = routes;
		}
		@Override
		public void close()
		{
			if (stopping) return;
			stopping = true;
			for (Process link : links) helpers.release(link);
			helpers.release(process);
			samples.clear();
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
