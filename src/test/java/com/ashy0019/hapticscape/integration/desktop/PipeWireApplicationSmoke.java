package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Opt-in same-sink application isolation, multi-stream mixing and playback preservation. */
public final class PipeWireApplicationSmoke
{
	public static void main(String[] args) throws Exception
	{
		if (args.length != 1 || !args[0].equals("--live-virtual-sink")) throw new IllegalArgumentException("Explicit live virtual-sink flag required");
		String prefix = "hapticscape.application-test." + UUID.randomUUID();
		Set<String> initialCaptures;
		try (PipeWireProcesses helpers = new PipeWireProcesses())
		{
			initialCaptures = captures(snapshot(helpers));
			Process sinkProcess = helpers.start(List.of("pw-cli", "--monitor", "create-node", "adapter",
				"{ factory.name=support.null-audio-sink node.name=" + prefix + ".sink"
				+ " node.description=\"HapticScape disposable application test\" media.class=Audio/Sink"
				+ " audio.position=[ FL FR ] node.driver=true object.linger=false priority.session=-10000 }"));
			drain(sinkProcess);
			PipeWireGraph.Sink sink = waitForSink(helpers, prefix + ".sink");
			Process firstPlayer = playback(helpers, sink, prefix + ".tone440", prefix + ".selected", 440, 2);
			playback(helpers, sink, prefix + ".tone660", prefix + ".selected", 660, 1);
			playback(helpers, sink, prefix + ".tone997", prefix + ".other", 997, 2);
			AudioCaptureApplication app = new AudioCaptureApplication(PipeWireApplicationGraph.identity("id", prefix + ".selected"), "Selected test app");
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
			while (true)
			{
				PipeWireApplicationGraph.Group group = PipeWireApplicationGraph.snapshot(helpers).resolve(app);
				if (group != null && group.nodes.size() == 2 && group.ports.size() >= 2) break;
				if (System.nanoTime() > deadline) throw new AssertionError("Generated playback streams were not discovered");
				Thread.sleep(100);
			}
			Set<String> playbackLinks = playbackLinks(snapshot(helpers), prefix, sink.id);
			if (playbackLinks.size() < 3) throw new AssertionError("Generated playback did not link to its virtual sink");
			Signal selected = new Signal(), output = new Signal();
			try (PipeWireApplicationCapture capture = new PipeWireApplicationCapture(app);
				PipeWireLoopbackCapture monitor = new PipeWireLoopbackCapture(new AudioCaptureEndpoint(PipeWireGraph.identity(sink.name), "Test output")))
			{
				capture.start(selected); monitor.start(output);
				selected.await(); output.await();
				float[] appPcm = selected.middle(), outputPcm = output.middle();
				double first = amplitude(appPcm, 440), second = amplitude(appPcm, 660), excluded = amplitude(appPcm, 997);
				if (first < .02 || second < .02 || excluded > Math.min(first, second) / 20)
					throw new AssertionError("Application PCM isolation/mixing failed: 440=" + first + " 660=" + second + " other=" + excluded);
				for (int frequency : new int[] { 440, 660, 997 })
					if (amplitude(outputPcm, frequency) < .02) throw new AssertionError("Playback was interrupted at " + frequency + " Hz");
				if (!playbackLinks(snapshot(helpers), prefix, sink.id).equals(playbackLinks)) throw new AssertionError("Original playback links changed during capture");
				System.out.println("Application PCM passed: two selected streams 440Hz=" + first + ", 660Hz=" + second + "; excluded same-sink 997Hz=" + excluded);
				helpers.release(firstPlayer);
				playback(helpers, sink, prefix + ".tone440", prefix + ".selected", 440, 2);
				deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
				while (selected.started.get() < 2)
				{
					if (selected.error.get() != null) throw new AssertionError(selected.error.get());
					if (System.nanoTime() > deadline) throw new AssertionError("Application capture did not follow stream restart");
					Thread.sleep(50);
				}
				selected.reset(); selected.await();
				appPcm = selected.middle();
				if (amplitude(appPcm, 440) < .02 || amplitude(appPcm, 660) < .02 || amplitude(appPcm, 997) > .001)
					throw new AssertionError("Application stream restart lost PCM isolation");
				playbackLinks = playbackLinks(snapshot(helpers), prefix, sink.id);
				if (playbackLinks.size() < 3) throw new AssertionError("Restarted playback did not remain linked");
				System.out.println("Selected stream restart followed with the same saved application identity and preserved isolation.");
				int beforeMute = selected.zeros.get();
				helpers.query(List.of("pw-cli", "set-param", Integer.toString(sink.id), "Props", "{ mute: true }"), 8192);
				try
				{
					deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
					while (selected.zeros.get() == beforeMute)
					{
						if (System.nanoTime() > deadline) throw new AssertionError("Muted playback sink did not silence application haptics");
						Thread.sleep(25);
					}
				}
				finally { helpers.query(List.of("pw-cli", "set-param", Integer.toString(sink.id), "Props", "{ mute: false }"), 8192); }
				selected.reset(); selected.await();
				appPcm = selected.middle();
				if (amplitude(appPcm, 440) < .02 || amplitude(appPcm, 660) < .02 || amplitude(appPcm, 997) > .001)
					throw new AssertionError("Unmute did not resume isolated PCM");
				System.out.println("Playback-sink mute immediately silenced output; unmute resumed isolated PCM.");
			}
			deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (!captures(snapshot(helpers)).equals(initialCaptures))
			{
				if (System.nanoTime() > deadline) throw new AssertionError("Capture nodes leaked after close");
				Thread.sleep(50);
			}
			if (!playbackLinks(snapshot(helpers), prefix, sink.id).equals(playbackLinks)) throw new AssertionError("Capture cleanup removed original playback links");
		}
		try (PipeWireProcesses verify = new PipeWireProcesses())
		{
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (snapshot(verify).contains(prefix))
			{
				if (System.nanoTime() > deadline) throw new AssertionError("Disposable playback/output objects leaked");
				Thread.sleep(50);
			}
		}
		System.out.println("Original playback PCM/links preserved; owned capture nodes and disposable output removed. No microphones or desktop defaults used.");
	}

	private static String snapshot(PipeWireProcesses helpers) { return helpers.query(List.of("pw-dump", "--no-colors"), 4 * 1024 * 1024); }
	private static void drain(Process process)
	{
		PipeWireProcesses.reader(process.getInputStream(), 8192, true);
		PipeWireProcesses.reader(process.getErrorStream(), 8192, true);
	}
	private static PipeWireGraph.Sink waitForSink(PipeWireProcesses helpers, String name) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (true)
		{
			for (PipeWireGraph.Sink sink : PipeWireAudioEndpointCatalog.snapshot(helpers).sinks) if (sink.name.equals(name)) return sink;
			if (System.nanoTime() > deadline) throw new AssertionError("Virtual sink startup failed");
			Thread.sleep(100);
		}
	}
	private static Process playback(PipeWireProcesses helpers, PipeWireGraph.Sink sink, String name, String appId, int frequency, int channels)
	{
		JsonObject props = new JsonObject();
		props.addProperty("node.name", name); props.addProperty("application.id", appId);
		props.addProperty("application.name", "Generated HapticScape test playback");
		props.addProperty("node.dont-fallback", true); props.addProperty("node.dont-move", true);
		Process player = helpers.start(List.of("pw-cat", "--playback", "--raw", "--format=f32", "--rate=48000", "--channels=" + channels,
			"--target=" + sink.serial, "--properties=" + props, "-"));
		drain(player);
		Thread writer = new Thread(() ->
		{
			try (OutputStream stream = player.getOutputStream())
			{
				for (int block = 0; block < 1000; block++)
				{
					ByteBuffer pcm = ByteBuffer.allocate(480 * channels * 4).order(ByteOrder.nativeOrder());
					for (int i = 0; i < 480; i++)
					{
						float value = (float) (.1 * Math.sin(2 * Math.PI * frequency * (block * 480 + i) / 48000));
						for (int channel = 0; channel < channels; channel++) pcm.putFloat(value);
					}
					stream.write(pcm.array()); stream.flush();
				}
			}
			catch (Exception ignored) { /* Owning helper shutdown interrupts generated playback. */ }
		}, "hapticscape-application-test-tone"); writer.setDaemon(true); writer.start();
		return player;
	}
	private static Set<String> captures(String json)
	{
		Set<String> result = new HashSet<>();
		for (JsonElement entry : new JsonParser().parse(json).getAsJsonArray())
		{
			JsonObject node = entry.getAsJsonObject();
			if (!node.has("info") || !node.getAsJsonObject("info").has("props")) continue;
			JsonObject props = node.getAsJsonObject("info").getAsJsonObject("props");
			String name = props.has("node.name") ? props.get("node.name").getAsString() : "";
			if (name.startsWith("hapticscape.application.") || name.startsWith("hapticscape.output.")) result.add(name);
		}
		return result;
	}
	private static Set<String> playbackLinks(String json, String prefix, int sinkId)
	{
		Set<Integer> players = new HashSet<>(); Set<String> links = new HashSet<>();
		for (JsonElement entry : new JsonParser().parse(json).getAsJsonArray())
		{
			JsonObject node = entry.getAsJsonObject();
			if (!node.has("info") || !node.getAsJsonObject("info").has("props")) continue;
			JsonObject props = node.getAsJsonObject("info").getAsJsonObject("props");
			if (props.has("node.name") && props.get("node.name").getAsString().startsWith(prefix + ".tone")) players.add(node.get("id").getAsInt());
		}
		for (JsonElement entry : new JsonParser().parse(json).getAsJsonArray())
		{
			JsonObject link = entry.getAsJsonObject();
			if (!link.has("info")) continue;
			JsonObject info = link.getAsJsonObject("info");
			if (info.has("input-node-id") && info.get("input-node-id").getAsInt() == sinkId && players.contains(info.get("output-node-id").getAsInt()))
				links.add(link.get("id").getAsString());
		}
		return links;
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
	private static final class Signal implements AudioCaptureSource.Listener
	{
		final List<Float> samples = new ArrayList<>();
		volatile CountDownLatch enough = new CountDownLatch(1);
		final AtomicReference<String> error = new AtomicReference<>();
		final AtomicInteger started = new AtomicInteger();
		final AtomicInteger zeros = new AtomicInteger();
		public void onStarted(String description) { started.incrementAndGet(); }
		public void onError(String message, Throwable failure) { error.set(message); enough.countDown(); }
		public synchronized void onSamples(float[] pcm, int rate, double scale)
		{
			if (scale == 0.0) { zeros.incrementAndGet(); return; }
			if (rate != 48000 || scale != 1.0) error.set("Incorrect PCM format or duplicated volume scaling");
			for (float sample : pcm) if (samples.size() < 144000) samples.add(sample);
			if (samples.size() >= 96000) enough.countDown();
		}
		void await() throws Exception
		{
			if (!enough.await(7, TimeUnit.SECONDS)) throw new AssertionError("Sustained application/monitor PCM timed out");
			if (error.get() != null) throw new AssertionError(error.get());
		}
		synchronized float[] middle()
		{
			float[] result = new float[48000];
			for (int i = 0; i < result.length; i++) result[i] = samples.get(i + 24000);
			return result;
		}
		synchronized void reset() { samples.clear(); enough = new CountDownLatch(1); }
	}
}
