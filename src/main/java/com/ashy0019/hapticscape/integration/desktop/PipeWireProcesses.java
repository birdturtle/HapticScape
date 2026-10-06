package com.ashy0019.hapticscape.integration.desktop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

/** Owns helper processes, bounded output and cancellation. Never invokes a shell. */
final class PipeWireProcesses implements AutoCloseable
{
	interface Starter { Process start(List<String> command) throws IOException; }
	private final Starter starter;
	private final Set<Process> processes = ConcurrentHashMap.newKeySet();
	private volatile boolean closed;
	PipeWireProcesses() { this(command -> new ProcessBuilder(command).start()); }
	PipeWireProcesses(Starter starter) { this.starter = starter; }

	Process start(List<String> command)
	{
		if (closed) throw new IllegalStateException("PipeWire capture was closed");
		try
		{
			Process process = starter.start(command);
			processes.add(process);
			if (closed) { release(process); throw new IllegalStateException("PipeWire capture was closed"); }
			return process;
		}
		catch (IOException missing)
		{
			throw new IllegalStateException("Music Sync requires " + command.get(0) + "; install the PipeWire command-line tools", missing);
		}
	}

	String query(List<String> command, int limit)
	{
		Process process = start(command);
		FutureTask<byte[]> stdout = reader(process.getInputStream(), limit, false);
		FutureTask<byte[]> stderr = reader(process.getErrorStream(), 8192, true);
		try
		{
			if (!process.waitFor(2500, TimeUnit.MILLISECONDS))
				throw new IllegalStateException(command.get(0) + " timed out; check PipeWire and your desktop session");
			byte[] output = stdout.get(300, TimeUnit.MILLISECONDS);
			if (process.exitValue() != 0)
			{
				String detail = new String(stderr.get(300, TimeUnit.MILLISECONDS), StandardCharsets.UTF_8).trim();
				if (detail.length() > 300) detail = detail.substring(0, 300);
				throw new IllegalStateException(command.get(0) + " failed; check PipeWire/session access" + (detail.isEmpty() ? "" : ": " + detail));
			}
			return new String(output, StandardCharsets.UTF_8);
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("PipeWire operation cancelled", interrupted);
		}
		catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failed)
		{
			throw new IllegalStateException("Unable to read bounded output from " + command.get(0), failed);
		}
		finally { release(process); }
	}

	static FutureTask<byte[]> reader(InputStream input, int limit, boolean truncate)
	{
		FutureTask<byte[]> task = new FutureTask<>(() ->
		{
			try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream())
			{
				byte[] buffer = new byte[8192];
				int count;
				while ((count = stream.read(buffer)) != -1)
				{
					int remaining = limit - bytes.size();
					if (count > remaining && !truncate) throw new IOException("PipeWire output exceeded limit");
					if (remaining > 0) bytes.write(buffer, 0, Math.min(count, remaining));
				}
				return bytes.toByteArray();
			}
		});
		Thread worker = new Thread(task, "hapticscape-pipewire-drain");
		worker.setDaemon(true);
		worker.start();
		return task;
	}

	void release(Process process)
	{
		if (!processes.remove(process)) return;
		process.destroyForcibly();
		// waitFor runs outside the MusicSync lock, including when release is called by close().
		Thread reaper = new Thread(() ->
		{
			try { process.waitFor(1500, TimeUnit.MILLISECONDS); }
			catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
			finally
			{
				try { process.getInputStream().close(); } catch (IOException ignored) { }
				try { process.getErrorStream().close(); } catch (IOException ignored) { }
				try { process.getOutputStream().close(); } catch (IOException ignored) { }
			}
		}, "hapticscape-pipewire-reaper");
		reaper.setDaemon(true);
		reaper.start();
	}

	@Override
	public void close()
	{
		if (closed) return;
		closed = true;
		processes.forEach(this::release);
	}
}
