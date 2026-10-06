package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.remote.SecretStoreAccessException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;
import javax.swing.SwingUtilities;

/** Invoked only by verifyLinuxKeyringBindings, against a nonexistent session bus. */
public final class LibsecretFailureSmoke
{
	public static void main(String[] args) throws Exception
	{
		String address = System.getenv("DBUS_SESSION_BUS_ADDRESS");
		if (address == null || !address.startsWith("unix:path=")
			|| Files.exists(Paths.get(address.substring("unix:path=".length()))))
			throw new IllegalStateException("Smoke check requires a nonexistent isolated bus socket");
		LibsecretKeyring wallet = new LibsecretKeyring();
		if (!wallet.isAvailable()) throw new IllegalStateException("libsecret prerequisites are unavailable");
		long began = System.nanoTime();
		try { wallet.lookup("unlock-keys", UUID.randomUUID().toString()); throw new AssertionError("Missing bus must fail"); }
		catch (SecretStoreAccessException expected) { }
		try { wallet.store("unlock-keys", UUID.randomUUID().toString(), new byte[32]); throw new AssertionError("Missing bus must fail"); }
		catch (SecretStoreAccessException expected) { }
		SwingUtilities.invokeAndWait(() ->
		{
			try { wallet.lookup("unlock-keys", "unused"); throw new AssertionError("EDT wallet access must fail"); }
			catch (SecretStoreAccessException expected)
			{
				if (!expected.getMessage().contains("background")) throw new AssertionError(expected);
			}
		});
		if ((System.nanoTime() - began) / 1_000_000_000L > 65)
			throw new AssertionError("Wallet failure handling exceeded its deadlines");
		System.out.println("libsecret lookup/store bindings and EDT guard passed on an isolated unreachable bus; no desktop secrets accessed.");
	}
}
