package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.remote.SecretStoreAccessException;
import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** Lazy, cancellable libsecret access. Never transports secrets via a shell or files. */
final class LibsecretKeyring implements LinuxSecretKeyring
{
	private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task ->
	{
		Thread thread = new Thread(task, "hapticscape-keyring-deadlines");
		thread.setDaemon(true);
		return thread;
	});
	private volatile Bindings bindings;
	private final long timeoutMillis;

	LibsecretKeyring() { timeoutMillis = 30_000; }

	LibsecretKeyring(Bindings bindings, long timeoutMillis)
	{
		this.bindings = bindings;
		this.timeoutMillis = timeoutMillis;
	}

	@Override
	public boolean isAvailable()
	{
		if (DesktopPlatform.current() != DesktopPlatform.LINUX
			|| System.getenv("DBUS_SESSION_BUS_ADDRESS") == null) return false;
		try
		{
			bindings();
			return true;
		}
		catch (LinkageError | RuntimeException unavailable)
		{
			return false;
		}
	}

	@Override
	public byte[] lookup(String purpose, String keyId)
	{
		requireWorker();
		Bindings nativeApi = bindings();
		try (Operation operation = new Operation(nativeApi, purpose, keyId, timeoutMillis))
		{
			PointerByReference error = new PointerByReference();
			Pointer secret = nativeApi.secret.secret_password_lookupv_sync(null, operation.attributes,
				operation.cancellable, error);
			try
			{
				operation.check(error);
				if (secret == null) return null;
				long length = secret.indexOf(0, (byte) 0);
				if (length != 44) throw new IllegalArgumentException("The Linux keyring encryption key is damaged");
				byte[] encoded = secret.getByteArray(0, (int) length);
				try
				{
					return Base64.getDecoder().decode(encoded);
				}
				finally
				{
					Arrays.fill(encoded, (byte) 0);
				}
			}
			finally
			{
				if (secret != null) nativeApi.secret.secret_password_free(secret);
			}
		}
	}

	@Override
	public void store(String purpose, String keyId, byte[] key)
	{
		requireWorker();
		Bindings nativeApi = bindings();
		byte[] encoded = Base64.getEncoder().encode(key);
		Memory secret = new Memory(encoded.length + 1L);
		secret.write(0, encoded, 0, encoded.length);
		secret.setByte(encoded.length, (byte) 0);
		Arrays.fill(encoded, (byte) 0);
		try (Operation operation = new Operation(nativeApi, purpose, keyId, timeoutMillis))
		{
			PointerByReference error = new PointerByReference();
			int stored = nativeApi.secret.secret_password_storev_sync(null, operation.attributes,
				"default", "HapticScape " + purpose + " encryption key", secret, operation.cancellable, error);
			operation.check(error);
			if (stored == 0) throw accessFailure();
		}
		finally
		{
			secret.clear();
		}
	}

	private synchronized Bindings bindings()
	{
		if (bindings == null) bindings = new Bindings();
		return bindings;
	}

	private static void requireWorker()
	{
		if (SwingUtilities.isEventDispatchThread())
			throw new SecretStoreAccessException("Linux wallet access must run in the background; retry the operation");
	}

	private static SecretStoreAccessException accessFailure()
	{
		return new SecretStoreAccessException("Linux keyring access failed or was cancelled; unlock your wallet, check Secret Service, and retry");
	}

	private static final class Operation implements AutoCloseable
	{
		private final Bindings api;
		private final Pointer cancellable;
		private final Pointer attributes;
		private final List<Memory> strings = new ArrayList<>();
		private final ScheduledFuture<?> deadline;
		private boolean closed;
		private boolean expired;

		private Operation(Bindings api, String purpose, String keyId, long timeoutMillis)
		{
			this.api = api;
			cancellable = api.gio.g_cancellable_new();
			attributes = api.glib.g_hash_table_new(api.glibLibrary.getFunction("g_str_hash"),
				api.glibLibrary.getFunction("g_str_equal"));
			attribute("application", "com.ashy0019.hapticscape");
			attribute("purpose", purpose);
			attribute("key-id", keyId);
			attribute("format", "aes256-gcm-v1");
			deadline = DEADLINES.schedule(this::expire, timeoutMillis, TimeUnit.MILLISECONDS);
		}

		private void attribute(String name, String value)
		{
			api.glib.g_hash_table_insert(attributes, string(name), string(value));
		}

		private Memory string(String value)
		{
			byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
			Memory memory = new Memory(bytes.length + 1L);
			memory.setString(0, value, "UTF-8");
			strings.add(memory);
			return memory;
		}

		private synchronized void expire()
		{
			if (!closed)
			{
				expired = true;
				api.gio.g_cancellable_cancel(cancellable);
			}
		}

		private synchronized void check(PointerByReference error)
		{
			boolean failed = error.getValue() != null;
			if (failed)
			{
				// Native messages may contain provider data; keep them out of logs and exceptions.
				api.glib.g_error_free(error.getValue());
				error.setValue(null);
			}
			if (expired) throw new SecretStoreAccessException("Linux keyring access timed out; unlock your wallet and retry");
			if (failed) throw accessFailure();
		}

		@Override
		public synchronized void close()
		{
			closed = true;
			deadline.cancel(false);
			api.glib.g_hash_table_unref(attributes);
			api.gobject.g_object_unref(cancellable);
			strings.forEach(Memory::clear);
		}
	}

	static final class Bindings
	{
		private final Secret secret;

		Bindings() { this(Native.load("secret-1", Secret.class)); }
		Bindings(Secret secret) { this.secret = secret; }
		private final Glib glib = Native.load("glib-2.0", Glib.class);
		private final Gio gio = Native.load("gio-2.0", Gio.class);
		private final Gobject gobject = Native.load("gobject-2.0", Gobject.class);
		private final NativeLibrary glibLibrary = NativeLibrary.getInstance("glib-2.0");
	}

	interface Secret extends Library
	{
		Pointer secret_password_lookupv_sync(Pointer schema, Pointer attributes, Pointer cancellable, PointerByReference error);
		int secret_password_storev_sync(Pointer schema, Pointer attributes, String collection, String label,
			Pointer password, Pointer cancellable, PointerByReference error);
		void secret_password_free(Pointer password);
	}

	interface Glib extends Library
	{
		Pointer g_hash_table_new(Pointer hash, Pointer equal);
		int g_hash_table_insert(Pointer table, Pointer key, Pointer value);
		void g_hash_table_unref(Pointer table);
		void g_error_free(Pointer error);
	}

	interface Gio extends Library
	{
		Pointer g_cancellable_new();
		void g_cancellable_cancel(Pointer cancellable);
		int g_cancellable_is_cancelled(Pointer cancellable);
	}

	interface Gobject extends Library
	{
		void g_object_unref(Pointer object);
	}
}
