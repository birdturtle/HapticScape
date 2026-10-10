package com.ashy0019.hapticscape.remote;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class SavedUnlockKeyConcurrencyTest
{
	@Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
	private static final String ID = "a42ff467-2ec6-4c77-9306-b1d603121984";
	private static final String KEY = "ABCD-EFGH-JKLM-NPQR-STUV";

	@Test(timeout = 10000)
	public void cancellationDuringWalletPromptCannotResurrectForgottenKey() throws Exception
	{
		Path path = temporaryFolder.getRoot().toPath().resolve("keys.json");
		BlockingSecretProtector protector = new BlockingSecretProtector(new TestUnlockKeyProtector());
		protector.blockProtect = true;
		SavedUnlockKeyStore store = new SavedUnlockKeyStore(new Gson(), path, protector, Clock.systemUTC());
		CompletableFuture<SavedUnlockKey> writing = CompletableFuture.supplyAsync(() -> store.saveAcceptedKey(ID, KEY.toCharArray()));
		try
		{
			assertTrue(protector.entered.await(2, TimeUnit.SECONDS));
			assertTrue(CompletableFuture.supplyAsync(store::list).get(1, TimeUnit.SECONDS).isEmpty());
			assertFalse(CompletableFuture.supplyAsync(() -> store.forgetByLockId(ID)).get(1, TimeUnit.SECONDS));
			protector.release.countDown();
			try { writing.get(2, TimeUnit.SECONDS); fail("Cancelled key must not be saved"); }
			catch (ExecutionException expected) { assertTrue(expected.getCause().getMessage().contains("cancelled")); }
			assertFalse(Files.exists(path));
			assertTrue(store.list().isEmpty());
		}
		finally { protector.release.countDown(); }
	}

	@Test(timeout = 10000)
	public void editingMetadataWhileWalletOpensPreservesTheEdit() throws Exception
	{
		Path path = temporaryFolder.getRoot().toPath().resolve("keys-edit.json");
		BlockingSecretProtector protector = new BlockingSecretProtector(new TestUnlockKeyProtector());
		SavedUnlockKeyStore store = new SavedUnlockKeyStore(new Gson(), path, protector, Clock.systemUTC());
		SavedUnlockKey key = store.saveAcceptedKey(ID, KEY.toCharArray());
		protector.blockUnprotect = true;
		CompletableFuture<char[]> reading = CompletableFuture.supplyAsync(() -> store.reveal(key.getId()));
		try
		{
			assertTrue(protector.entered.await(2, TimeUnit.SECONDS));
			CompletableFuture.supplyAsync(() -> store.updateDetails(key.getId(), "Renamed", "New note"))
				.get(1, TimeUnit.SECONDS);
			protector.release.countDown();
			char[] opened = reading.get(2, TimeUnit.SECONDS);
			try { assertArrayEquals(KEY.toCharArray(), opened); }
			finally { java.util.Arrays.fill(opened, '\0'); }
			assertEquals("Renamed", store.list().get(0).getLabel());
			assertEquals("New note", store.list().get(0).getNote());
		}
		finally { protector.release.countDown(); }
	}
}
