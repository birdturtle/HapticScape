package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.remote.SecretStoreAccessException;
import com.ashy0019.hapticscape.remote.UnlockKeyProtector;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class LinuxKeyringSecretProtectorTest
{
	@Test
	public void authenticatedSecretsSurviveProtectorRestartAndKeysAreCleared()
	{
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		byte[] original = "an-unlock-key".getBytes(StandardCharsets.US_ASCII);
		byte[] encrypted = wallet.unlockKeys().protect(original);
		assertArrayEquals(new byte[32], wallet.lastBorrowedKey);
		assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains("an-unlock-key"));
		assertArrayEquals(original, wallet.unlockKeys().unprotect(encrypted));
		assertArrayEquals(new byte[32], wallet.lastBorrowedKey);
		assertArrayEquals("an-unlock-key".getBytes(StandardCharsets.US_ASCII), original);
		assertFalse(Arrays.equals(encrypted, wallet.unlockKeys().protect(original)));
	}

	@Test
	public void rejectsPurposeVersionAndForeignPayloadsBeforeReadingWallet()
	{
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		byte[] encrypted = wallet.unlockKeys().protect(new byte[] { 42 });
		assertRejected(wallet.discord(), encrypted);
		byte[] version = encrypted.clone(); version[4] = 2;
		assertRejected(wallet.unlockKeys(), version);
		assertRejected(wallet.unlockKeys(), new byte[] { 1, 2, 3 });
		assertRejected(wallet.unlockKeys(), Arrays.copyOf(encrypted, 38));
		assertRejected(wallet.unlockKeys(), new byte[5000]);
		assertEquals(0, wallet.reads);
	}

	@Test
	public void modifiedNonceCiphertextAndTagNeverReturnPlaintext()
	{
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		byte[] encrypted = wallet.unlockKeys().protect(new byte[] { 42, 43, 44 });
		for (int offset : new int[] { 22, 38, encrypted.length - 1 })
		{
			byte[] modified = encrypted.clone(); modified[offset] ^= 1;
			assertRejected(wallet.unlockKeys(), modified);
			assertArrayEquals(new byte[32], wallet.lastBorrowedKey);
		}
		assertEquals(1, wallet.writes);
	}

	@Test
	public void missingKeysAndCancelledWalletAccessNeverGenerateReplacementKeys()
	{
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		byte[] encrypted = wallet.unlockKeys().protect(new byte[] { 42 });
		wallet.locked = true;
		try { wallet.unlockKeys().unprotect(encrypted); fail(); }
		catch (SecretStoreAccessException expected) { }
		try { wallet.unlockKeys().protect(new byte[] { 43 }); fail(); }
		catch (SecretStoreAccessException expected) { }
		wallet.locked = false;
		assertArrayEquals(new byte[] { 42 }, wallet.unlockKeys().unprotect(encrypted));
		wallet.removeKeys();
		try { wallet.unlockKeys().unprotect(encrypted); fail(); }
		catch (SecretStoreAccessException expected) { assertTrue(expected.getMessage().contains("missing")); }
		assertEquals(1, wallet.writes);
	}

	private static void assertRejected(UnlockKeyProtector protector, byte[] payload)
	{
		try { protector.unprotect(payload); fail("Payload must be rejected"); }
		catch (IllegalArgumentException | IllegalStateException expected) { }
	}
}
