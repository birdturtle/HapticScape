package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.remote.SecretStoreAccessException;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import org.junit.Assume;
import org.junit.Test;
import static org.junit.Assert.*;

/** Uses a real GLib cancellation object, but never calls a Secret Service provider. */
public class LibsecretCancellationTest
{
	@Test(timeout = 5000)
	public void deadlineCancelsNativeLookupAndStoreWithoutTouchingDesktopWallet()
	{
		Assume.assumeTrue(DesktopPlatform.current() == DesktopPlatform.LINUX);
		LibsecretKeyring.Gio gio;
		try { gio = Native.load("gio-2.0", LibsecretKeyring.Gio.class); }
		catch (UnsatisfiedLinkError missing) { Assume.assumeNoException(missing); return; }
		LibsecretKeyring.Secret stalled = new LibsecretKeyring.Secret()
		{
			private void waitForCancellation(Pointer cancellable)
			{
				long deadline = System.nanoTime() + 2_000_000_000L;
				while (gio.g_cancellable_is_cancelled(cancellable) == 0)
				{
					if (System.nanoTime() > deadline) throw new AssertionError("Native operation was not cancelled");
					Thread.yield();
				}
			}
			@Override
			public Pointer secret_password_lookupv_sync(Pointer schema, Pointer attributes, Pointer cancellable, PointerByReference error)
			{
				waitForCancellation(cancellable);
				return null;
			}
			@Override
			public int secret_password_storev_sync(Pointer schema, Pointer attributes, String collection, String label,
				Pointer password, Pointer cancellable, PointerByReference error)
			{
				assertEquals("default", collection);
				waitForCancellation(cancellable);
				return 0;
			}
			@Override public void secret_password_free(Pointer password) { fail("No secret buffer was returned"); }
		};
		LibsecretKeyring wallet = new LibsecretKeyring(new LibsecretKeyring.Bindings(stalled), 20);
		try { wallet.lookup("unlock-keys", "test"); fail(); }
		catch (SecretStoreAccessException expected) { assertTrue(expected.getMessage().contains("timed out")); }
		try { wallet.store("unlock-keys", "test", new byte[32]); fail(); }
		catch (SecretStoreAccessException expected) { assertTrue(expected.getMessage().contains("timed out")); }
	}
}
