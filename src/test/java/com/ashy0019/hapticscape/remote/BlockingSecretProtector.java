package com.ashy0019.hapticscape.remote;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;

/** Models a wallet prompt without using any desktop service. */
final class BlockingSecretProtector implements UnlockKeyProtector
{
	private final UnlockKeyProtector delegate;
	final CountDownLatch entered = new CountDownLatch(1);
	final CountDownLatch release = new CountDownLatch(1);
	boolean blockProtect;
	boolean blockUnprotect;

	BlockingSecretProtector(UnlockKeyProtector delegate) { this.delegate = delegate; }
	@Override public boolean isAvailable() { return delegate.isAvailable(); }
	@Override public String getUnavailableMessage() { return delegate.getUnavailableMessage(); }
	@Override public boolean requiresBackgroundThread() { return true; }
	@Override public void validateCiphertext(byte[] payload) { delegate.validateCiphertext(payload); }
	@Override public byte[] protect(byte[] plaintext)
	{
		if (blockProtect) awaitWallet();
		return delegate.protect(plaintext);
	}
	@Override public byte[] unprotect(byte[] ciphertext)
	{
		if (blockUnprotect) awaitWallet();
		return delegate.unprotect(ciphertext);
	}
	private void awaitWallet()
	{
		if (SwingUtilities.isEventDispatchThread()) throw new AssertionError("Wallet call on EDT");
		entered.countDown();
		try
		{
			if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Test wallet was not released");
		}
		catch (InterruptedException failure)
		{
			Thread.currentThread().interrupt();
			throw new SecretStoreAccessException("Cancelled test wallet");
		}
	}
}
