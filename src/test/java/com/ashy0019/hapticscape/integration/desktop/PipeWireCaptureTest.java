package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureSource;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PipeWireCaptureTest
{
	@Test
	public void followsDefaultAndCleansUpBothStreams() throws Exception
	{
		Fixture fixture = new Fixture();
		Probe probe = new Probe();
		PipeWireLoopbackCapture source = fixture.source(AudioCaptureEndpoint.systemDefault());
		try
		{
			source.start(probe);
			assertTrue(probe.started.await(3, TimeUnit.SECONDS));
			assertTrue(probe.samples.await(1, TimeUnit.SECONDS));
			fixture.selected = "B";
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
			while (fixture.captures.size() < 2 && System.nanoTime() < deadline) Thread.sleep(10);
			assertEquals(2, fixture.captures.size());
			assertTrue(fixture.captures.get(0).destroyed);
			assertEquals("22", fixture.targets.get(1));
			assertNull(probe.error);
		}
		finally { source.close(); source.close(); }
		assertTrue(fixture.captures.stream().allMatch(process -> process.destroyed));
	}

	@Test
	public void selectedOutputDisappearanceDoesNotSwitchToDefault() throws Exception
	{
		Fixture fixture = new Fixture();
		Probe probe = new Probe();
		try (PipeWireLoopbackCapture source = fixture.source(new AudioCaptureEndpoint(PipeWireGraph.identity("A"), "A")))
		{
			source.start(probe);
			assertTrue(probe.started.await(3, TimeUnit.SECONDS));
			fixture.selected = "B";
			fixture.removeA = true;
			assertTrue(probe.failed.await(3, TimeUnit.SECONDS));
			assertTrue(probe.error.contains("unavailable"));
			assertEquals(1, fixture.captures.size());
			assertTrue(fixture.captures.get(0).destroyed);
		}
	}

	@Test
	public void missingSelectionFailsWithoutStartingOrFallingBack() throws Exception
	{
		Fixture fixture = new Fixture();
		Probe probe = new Probe();
		try (PipeWireLoopbackCapture source = fixture.source(new AudioCaptureEndpoint(PipeWireGraph.identity("missing"), "Missing")))
		{
			source.start(probe);
			assertTrue(probe.failed.await(3, TimeUnit.SECONDS));
			assertTrue(probe.error.contains("unavailable"));
			assertTrue(fixture.captures.isEmpty());
		}
	}

	@Test
	public void wrongRoutingNeverDeliversCapturedSamples() throws Exception
	{
		Fixture fixture = new Fixture();
		fixture.wrongRoute = true;
		Probe probe = new Probe();
		try (PipeWireLoopbackCapture source = fixture.source(AudioCaptureEndpoint.systemDefault()))
		{
			source.start(probe);
			assertTrue(probe.failed.await(3, TimeUnit.SECONDS));
			assertTrue(probe.error.contains("different source"));
			assertEquals(0, probe.nonzero.get());
			assertEquals(1, probe.started.getCount());
		}
	}

	@Test
	public void unavailableHelperReportsActionableErrorAsynchronously() throws Exception
	{
		PipeWireProcesses helpers = new PipeWireProcesses(command -> { throw new IOException("missing"); });
		Probe probe = new Probe();
		try (PipeWireLoopbackCapture source = new PipeWireLoopbackCapture(AudioCaptureEndpoint.systemDefault(), helpers))
		{
			source.start(probe);
			assertTrue(probe.failed.await(1, TimeUnit.SECONDS));
			assertTrue(probe.error.contains("install the PipeWire"));
		}
	}

	@Test
	public void closeDoesNotWaitForCallbackHoldingAnotherLock() throws Exception
	{
		Fixture fixture = new Fixture();
		CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
		PipeWireLoopbackCapture source = fixture.source(AudioCaptureEndpoint.systemDefault());
		source.start(new Probe()
		{
			@Override public void onStarted(String description)
			{
				entered.countDown();
				try { release.await(3, TimeUnit.SECONDS); }
				catch (InterruptedException ignored) { }
			}
		});
		try
		{
			assertTrue(entered.await(3, TimeUnit.SECONDS));
			long began = System.nanoTime();
			source.close();
			assertTrue(System.nanoTime() - began < TimeUnit.MILLISECONDS.toNanos(250));
			assertTrue(fixture.captures.stream().allMatch(process -> process.destroyed));
		}
		finally { release.countDown(); source.close(); }
	}

	@Test
	public void boundsDiscoveryOutput() throws Exception
	{
		try (PipeWireProcesses helpers = new PipeWireProcesses(command -> new FakeProcess("oversized")))
		{
			try { helpers.query(List.of("pw-dump"), 2); fail("Unbounded graph accepted"); }
			catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("bounded")); }
		}
	}

	private static class Probe implements AudioCaptureSource.Listener
	{
		final CountDownLatch started = new CountDownLatch(1), samples = new CountDownLatch(1), failed = new CountDownLatch(1);
		final AtomicInteger nonzero = new AtomicInteger();
		volatile String error;
		@Override public void onStarted(String description) { started.countDown(); }
		@Override public void onSamples(float[] mono, int rate, double volume)
		{
			assertEquals(48000, rate); assertEquals(1, volume, 0);
			if (mono[0] != 0) { nonzero.incrementAndGet(); samples.countDown(); }
		}
		@Override public void onError(String message, Throwable failure) { error = message; failed.countDown(); }
	}

	private static final class Fixture implements PipeWireProcesses.Starter
	{
		volatile String selected = "A", captureName;
		volatile boolean wrongRoute, removeA;
		final List<FakeProcess> captures = new CopyOnWriteArrayList<>();
		final List<String> targets = new CopyOnWriteArrayList<>();
		PipeWireLoopbackCapture source(AudioCaptureEndpoint endpoint) { return new PipeWireLoopbackCapture(endpoint, new PipeWireProcesses(this)); }
		@Override public Process start(List<String> command) throws IOException
		{
			if (command.get(0).equals("pw-dump"))
			{
				String json = "[" + (removeA ? "" : PipeWireGraphTest.sink(1, 11, "A") + ",")
					+ PipeWireGraphTest.sink(2, 22, "B") + "," + PipeWireGraphTest.metadata(selected);
				if (captureName != null) json += "," + PipeWireGraphTest.capture(captureName, wrongRoute ? 99 : Integer.parseInt(targets.get(targets.size() - 1)) == 11 ? 1 : 2);
				return new FakeProcess(json + "]");
			}
			if (command.contains("--help")) return new FakeProcess("--target --properties --raw --format --channels --rate");
			String properties = command.stream().filter(arg -> arg.startsWith("--properties=")).findFirst().get().substring(13);
			captureName = new JsonParser().parse(properties).getAsJsonObject().get("node.name").getAsString();
			targets.add(command.stream().filter(arg -> arg.startsWith("--target=")).findFirst().get().substring(9));
			FakeProcess process = new FakeProcess();
			captures.add(process);
			return process;
		}
	}

	private static final class FakeProcess extends Process
	{
		final InputStream stdout;
		volatile boolean destroyed;
		final boolean immediate;
		FakeProcess(String output) { stdout = new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)); immediate = true; }
		FakeProcess() throws IOException
		{
			immediate = false;
			PipedOutputStream write = new PipedOutputStream();
			stdout = new PipedInputStream(write, 65536);
			Thread producer = new Thread(() ->
			{
				ByteBuffer frame = ByteBuffer.allocate(3840).order(ByteOrder.nativeOrder());
				while (frame.hasRemaining()) frame.putFloat(0.25f);
				try (OutputStream stream = write)
				{
					while (!destroyed) { stream.write(frame.array()); Thread.sleep(10); }
				}
				catch (IOException | InterruptedException ignored) { }
			}, "fake-pipewire-pcm");
			producer.setDaemon(true); producer.start();
		}
		@Override public OutputStream getOutputStream() { return new ByteArrayOutputStream(); }
		@Override public InputStream getInputStream() { return stdout; }
		@Override public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
		@Override public int waitFor() throws InterruptedException { while (!immediate && !destroyed) Thread.sleep(10); return 0; }
		@Override public boolean waitFor(long timeout, TimeUnit unit) { return immediate || destroyed; }
		@Override public int exitValue() { if (!immediate && !destroyed) throw new IllegalThreadStateException(); return 0; }
		@Override public boolean isAlive() { return !immediate && !destroyed; }
		@Override public void destroy() { destroyed = true; }
		@Override public Process destroyForcibly() { destroy(); return this; }
	}
}
