package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import static org.junit.Assert.*;

public class PipeWireApplicationCaptureTest
{
	private static final AudioCaptureApplication APP = new AudioCaptureApplication(PipeWireApplicationGraph.identity("id", "selected"), "Selected");

	@Test
	public void capturesOnlyOwnedExplicitLinksAndRebuildsAfterStreamRestart() throws Exception
	{
		Fixture fixture = new Fixture();
		CountDownLatch samples = new CountDownLatch(1);
		AtomicReference<String> error = new AtomicReference<>();
		try (PipeWireApplicationCapture source = fixture.source(APP))
		{
			source.start(listener(samples, error));
			assertTrue(samples.await(3, TimeUnit.SECONDS));
			assertNull(error.get());
			FakeProcess old = fixture.capture;
			fixture.generation.incrementAndGet();
			await(() -> fixture.captureCount.get() == 2);
			assertTrue(old.dead);
			await(() -> fixture.commands.stream().anyMatch(command -> command.get(0).equals("pw-link") && command.contains("30")));
			assertNull(error.get());
			for (List<String> command : fixture.commands)
			{
				if (command.get(0).equals("pw-cat") && command.contains("--record"))
				{
					assertTrue(command.contains("--target=0"));
					assertTrue(command.toString().contains("\"node.autoconnect\":false"));
				}
				if (command.get(0).equals("pw-link") && !command.contains("--help"))
				{
					assertTrue(command.contains("--monitor"));
					assertTrue(command.contains("--props={\"object.linger\":false}"));
					assertFalse(command.contains("--disconnect"));
				}
			}
		}
		assertTrue(fixture.processes.stream().allMatch(process -> process.dead));
	}

	@Test
	public void missingSelectionDoesNotOpenAWholeOutputOrMicrophone() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.missing = true;
		AtomicReference<String> error = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		try (PipeWireApplicationCapture source = fixture.source(APP))
		{
			source.start(listener(done, error));
			assertTrue(done.await(3, TimeUnit.SECONDS));
			assertTrue(error.get().contains("no playback ports"));
			assertEquals(0, fixture.captureCount.get());
		}
	}

	@Test
	public void unexpectedRouteStopsBeforePcmDelivery() throws Exception
	{
		Fixture fixture = new Fixture(); fixture.unexpected = true;
		AtomicReference<String> error = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		try (PipeWireApplicationCapture source = fixture.source(APP))
		{
			source.start(listener(done, error));
			assertTrue(done.await(3, TimeUnit.SECONDS));
			assertTrue(error.get().contains("Unexpected source"));
		}
		assertTrue(fixture.processes.stream().allMatch(process -> process.dead));
	}

	@Test
	public void exitedLinkHelperReportsPortAccessFailure() throws Exception
	{
		Fixture fixture = new Fixture(); fixture.failLink = true;
		AtomicReference<String> error = new AtomicReference<>(); CountDownLatch done = new CountDownLatch(1);
		try (PipeWireApplicationCapture source = fixture.source(APP))
		{
			source.start(listener(done, error));
			assertTrue(done.await(3, TimeUnit.SECONDS));
			assertTrue(error.get().contains("link failed"));
		}
	}

	@Test
	public void shortApplicationExitWaitsForTheSameIdentityAndRecovers() throws Exception
	{
		Fixture fixture = new Fixture();
		AtomicReference<String> error = new AtomicReference<>(); CountDownLatch samples = new CountDownLatch(1);
		try (PipeWireApplicationCapture source = fixture.source(APP))
		{
			source.start(listener(samples, error)); assertTrue(samples.await(3, TimeUnit.SECONDS));
			fixture.missing = true;
			await(() -> fixture.capture.dead);
			fixture.generation.incrementAndGet(); fixture.missing = false;
			await(() -> fixture.captureCount.get() == 2);
			assertNull(error.get());
		}
	}

	@Test
	public void applicationExitClearsSignalAndWaitsWithoutFallback() throws Exception
	{
		Fixture fixture = new Fixture();
		CountDownLatch captured = new CountDownLatch(1), silent = new CountDownLatch(1), failed = new CountDownLatch(1);
		AtomicReference<String> error = new AtomicReference<>();
		try (PipeWireApplicationCapture source = fixture.source(APP))
		{
			source.start(new AudioCaptureSource.Listener()
			{
				public void onStarted(String text) { }
				public void onSamples(float[] pcm, int rate, double scale)
				{
					if (pcm.length > 0 && pcm[0] != 0) captured.countDown();
					else silent.countDown();
				}
				public void onError(String text, Throwable failure) { error.set(text); failed.countDown(); }
			});
			assertTrue(captured.await(3, TimeUnit.SECONDS));
			fixture.missing = true;
			assertTrue(silent.await(2, TimeUnit.SECONDS));
			assertFalse(failed.await(4, TimeUnit.SECONDS));
			assertNull(error.get());
			assertEquals(1, fixture.captureCount.get());
		}
	}

	@Test
	public void noPcmPauseAndSystemMuteEmitImmediateZeroAndResume() throws Exception
	{
		for (boolean mute : new boolean[] { false, true })
		{
			Fixture fixture = new Fixture();
			CountDownLatch started = new CountDownLatch(1), zero = new CountDownLatch(1), resumed = new CountDownLatch(1);
			AtomicReference<String> error = new AtomicReference<>();
			java.util.concurrent.atomic.AtomicBoolean resume = new java.util.concurrent.atomic.AtomicBoolean();
			java.util.concurrent.atomic.AtomicBoolean paused = new java.util.concurrent.atomic.AtomicBoolean();
			try (PipeWireApplicationCapture source = fixture.source(APP))
			{
				source.start(new AudioCaptureSource.Listener()
				{
					public void onStarted(String text) { }
					public void onSamples(float[] pcm, int rate, double scale)
					{
						if (scale == 0 && paused.get()) zero.countDown();
						else if (pcm.length > 0)
						{
							started.countDown();
							if (resume.get()) resumed.countDown();
						}
					}
					public void onError(String text, Throwable failure) { error.set(text); }
				});
				assertTrue(started.await(3, TimeUnit.SECONDS));
				// Ignore initial graph-negotiation silence.
				paused.set(true);
				if (mute) fixture.muted = true; else fixture.starved = true;
				assertTrue(zero.await(2, TimeUnit.SECONDS));
				assertNull(error.get());
				if (!mute) Thread.sleep(4100);
				resume.set(true); fixture.starved = false; fixture.muted = false;
				assertTrue(resumed.await(3, TimeUnit.SECONDS));
				assertNull(error.get());
			}
		}
	}

	@Test
	public void closeDoesNotWaitForListenerOrLeaveLinkHelpers() throws Exception
	{
		Fixture fixture = new Fixture();
		CountDownLatch callback = new CountDownLatch(1), release = new CountDownLatch(1);
		java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();
		PipeWireApplicationCapture source = fixture.source(APP);
		try
		{
			source.start(new AudioCaptureSource.Listener()
			{
				public void onStarted(String text)
				{
					callback.countDown();
					try { release.await(); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
				}
				public void onSamples(float[] pcm, int rate, double scale)
				{
					assertFalse("Closed capture delivered PCM", closing.get());
					assertEquals(0.0, scale, 0.0); // Negotiation may explicitly silence before onStarted.
				}
				public void onError(String text, Throwable failure) { fail(text); }
			});
			assertTrue(callback.await(3, TimeUnit.SECONDS));
			closing.set(true);
			long before = System.nanoTime(); source.close(); source.close();
			assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 300);
			assertTrue(fixture.processes.stream().allMatch(process -> process.dead));
		}
		finally { release.countDown(); source.close(); }
	}

	private static AudioCaptureSource.Listener listener(CountDownLatch done, AtomicReference<String> error)
	{
		return new AudioCaptureSource.Listener()
		{
			public void onStarted(String text) { }
			public void onSamples(float[] pcm, int rate, double scale) { if (pcm.length > 0 && pcm[0] != 0) done.countDown(); }
			public void onError(String text, Throwable failure) { error.set(text); done.countDown(); }
		};
	}
	private interface Condition { boolean get(); }
	private static void await(Condition condition) throws Exception
	{
		long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (!condition.get()) { if (System.nanoTime() > until) fail("Capture lifecycle timed out"); Thread.sleep(10); }
	}
	private static final class Fixture
	{
		final AtomicInteger generation = new AtomicInteger(), captureCount = new AtomicInteger();
		final List<List<String>> commands = new CopyOnWriteArrayList<>();
		final List<FakeProcess> processes = new CopyOnWriteArrayList<>();
		volatile FakeProcess capture;
		volatile String name;
		volatile boolean missing, unexpected, failLink, starved, muted;
		PipeWireApplicationCapture source(AudioCaptureApplication app) { return new PipeWireApplicationCapture(app, new PipeWireProcesses(this::start)); }
		Process start(List<String> command) throws java.io.IOException
		{
			commands.add(command);
			FakeProcess process;
			if (command.contains("--help")) process = new FakeProcess("--target --properties --raw --format --channels --channel-map --rate --monitor --props", false);
			else if (command.get(0).equals("pw-dump")) process = new FakeProcess(graph(), false);
			else if (command.get(0).equals("pw-link")) process = new FakeProcess("", !failLink);
			else
			{
				String properties = command.stream().filter(value -> value.startsWith("--properties=")).findFirst().get().substring(13);
				name = new com.google.gson.JsonParser().parse(properties).getAsJsonObject().get("node.name").getAsString();
				process = new FakeProcess("", true); capture = process; captureCount.incrementAndGet();
				PipedInputStream input = new PipedInputStream(8192); PipedOutputStream output = new PipedOutputStream(input); process.input = input;
				Thread writer = new Thread(() ->
				{
					try
					{
						ByteBuffer pcm = ByteBuffer.allocate(480 * 8).order(ByteOrder.nativeOrder());
						while (pcm.hasRemaining()) pcm.putFloat(.25f);
						while (!process.dead)
						{
							if (!starved) { output.write(pcm.array()); output.flush(); }
							Thread.sleep(10);
						}
					}
					catch (Exception ignored) { }
					finally { try { output.close(); } catch (Exception ignored) { } }
				}, "fake-application-pcm"); writer.setDaemon(true); writer.start();
			}
			processes.add(process); return process;
		}
		String graph()
		{
			List<String> objects = new java.util.ArrayList<>();
			int gen = generation.get(), node = 10 + gen, port = 20 + 10 * gen;
			objects.add("{\"id\":70,\"type\":\"PipeWire:Interface:Node\",\"info\":{\"props\":{\"media.class\":\"Audio/Sink\",\"node.name\":\"default\",\"object.serial\":70},\"params\":{\"Props\":[{\"mute\":" + muted + "}]}}}");
			objects.add("{\"type\":\"PipeWire:Interface:Metadata\",\"props\":{\"metadata.name\":\"default\"},\"metadata\":[{\"subject\":0,\"key\":\"default.audio.sink\",\"value\":{\"name\":\"default\"}}]}");
			if (!missing)
			{
				objects.add(PipeWireApplicationGraphTest.node(node, Integer.toString(100 + gen), "Stream/Output/Audio", "\"application.id\":\"selected\""));
				objects.add(PipeWireApplicationGraphTest.port(port, node, "FL", "out"));
				objects.add(PipeWireApplicationGraphTest.port(port + 1, node, "FR", "out"));
			}
			if (capture != null && !capture.dead)
			{
				objects.add(PipeWireApplicationGraphTest.node(50, "500", "Stream/Input/Audio", "\"node.name\":\"" + name + "\""));
				objects.add(PipeWireApplicationGraphTest.port(60, 50, "FL", "in"));
				objects.add(PipeWireApplicationGraphTest.port(61, 50, "FR", "in"));
				for (List<String> command : commands)
					if (command.get(0).equals("pw-link") && !command.contains("--help") && !failLink
						&& (command.get(3).equals(Integer.toString(port)) || command.get(3).equals(Integer.toString(port + 1))))
						objects.add(PipeWireApplicationGraphTest.link(Integer.parseInt(command.get(3)), Integer.parseInt(command.get(4))));
				if (unexpected) objects.add(PipeWireApplicationGraphTest.link(99, 60));
			}
			return "[" + String.join(",", objects) + "]";
		}
	}
	private static final class FakeProcess extends Process
	{
		volatile boolean dead;
		private final boolean running;
		InputStream input;
		FakeProcess(String text, boolean running) { this.running = running; input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
		public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
		public InputStream getInputStream() { return input; }
		public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
		public int waitFor() { return 0; }
		public boolean waitFor(long timeout, TimeUnit unit) { return !running || dead; }
		public int exitValue() { if (running && !dead) throw new IllegalThreadStateException(); return 0; }
		public boolean isAlive() { return running && !dead; }
		public void destroy() { dead = true; }
		public Process destroyForcibly() { dead = true; return this; }
	}
}
