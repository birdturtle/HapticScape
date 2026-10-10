package com.ashy0019.hapticscape.remote;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SavedUnlockKeyStoreTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void exitNoticeSurvivesRestartEditsAndCopyWithoutChangingNotesOrKeys() throws Exception
	{
		Path path = temporaryFolder.getRoot().toPath().resolve("exit-notice.json");
		TestUnlockKeyProtector protector = new TestUnlockKeyProtector();
		Clock clock = Clock.systemUTC();
		SavedUnlockKeyStore store = new SavedUnlockKeyStore(new Gson(), path, protector, clock);
		String subject = java.util.UUID.randomUUID().toString();
		SavedUnlockKey saved = store.saveAcceptedProfileKey(java.util.UUID.randomUUID().toString(),
			subject, "Subject", "ABCD-EFGH-JKLM-NPQR-STUV".toCharArray());
		store.updateDetails(saved.getId(), "My subject", "Keep my note");
		assertNull(store.list().get(0).getLastUnauthorizedEndAt());
		assertFalse(store.recordUnauthorizedEnd(java.util.UUID.randomUUID().toString(), 1234L));
		String first = java.util.UUID.randomUUID().toString();
		String second = java.util.UUID.randomUUID().toString();
		assertTrue(store.recordUnauthorizedEnd(subject, saved.getLockId(), first, 2345L));
		assertTrue(store.recordUnauthorizedEnd(subject, saved.getLockId(), first, 2345L));
		assertTrue(store.recordUnauthorizedEnd(subject, saved.getLockId(), second, 1234L));
		assertFalse(store.recordUnauthorizedEnd(subject, java.util.UUID.randomUUID().toString(),
			java.util.UUID.randomUUID().toString(), 4567L));
		SavedUnlockKeyStore restarted = new SavedUnlockKeyStore(new Gson(), path, protector, clock);
		SavedUnlockKey loaded = restarted.list().get(0);
		assertEquals(Instant.ofEpochMilli(2345L), loaded.getLastUnauthorizedEndAt());
		assertEquals("Keep my note", loaded.getNote());
		assertEquals(2, loaded.getUnauthorizedEnds().size());
		assertEquals(second, loaded.getUnauthorizedEnds().get(0).getEventId());
		assertEquals(saved.getProtectedKey(), loaded.getProtectedKey());
		restarted.updateDetails(saved.getId(), "Renamed", "Edited note");
		char[] key = restarted.reveal(saved.getId());
		try { assertArrayEquals("ABCD-EFGH-JKLM-NPQR-STUV".toCharArray(), key); }
		finally { Arrays.fill(key, '\0'); }
		assertEquals(Instant.ofEpochMilli(2345L), restarted.list().get(0).getLastUnauthorizedEndAt());
		assertEquals("Edited note", restarted.list().get(0).getNote());
		assertEquals(1, protector.getProtectCount());
		restarted.saveAcceptedProfileKey(java.util.UUID.randomUUID().toString(), subject,
			"New profile", "WXYZ-2345-6789-BCDF-GHJK".toCharArray());
		assertEquals(1, restarted.list().size());
		assertNull(restarted.list().get(0).getLastUnauthorizedEndAt());
		assertTrue(restarted.list().get(0).getUnauthorizedEnds().isEmpty());
		assertFalse(restarted.recordUnauthorizedEnd(subject, saved.getLockId(), first, 2345L));
	}

	@Test
	public void acceptedKeyIsProtectedEditableAndReadableAfterRestart() throws Exception
	{
		Path path = temporaryFolder.getRoot().toPath().resolve("saved-keys.json");
		TestUnlockKeyProtector protector = new TestUnlockKeyProtector();
		Clock createdClock = Clock.fixed(
			Instant.parse("2026-09-04T01:42:00Z"),
			ZoneOffset.UTC
		);
		char[] original = "ABCD-EFGH-JKLM-NPQR-STUV".toCharArray();
		try
		{
			SavedUnlockKeyStore store = new SavedUnlockKeyStore(
				new Gson(),
				path,
				protector,
				createdClock
			);
			SavedUnlockKey saved = store.saveAcceptedKey(
				"a42ff467-2ec6-4c77-9306-b1d603121984",
				original
			);

			assertEquals("Session Sep 4, 1:42 AM", saved.getLabel());
			assertEquals(Instant.parse("2026-09-04T01:42:00Z"), saved.getCreatedAt());
			assertNull(saved.getLastUsedAt());
			assertEquals(1, protector.getProtectCount());
			String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			assertFalse(json.contains(new String(original)));

			store.updateDetails(saved.getId(), "Alex", "Use after the September session");
			Clock accessedClock = Clock.fixed(
				Instant.parse("2026-09-05T03:04:00Z"),
				ZoneOffset.UTC
			);
			SavedUnlockKeyStore restarted = new SavedUnlockKeyStore(
				new Gson(),
				path,
				protector,
				accessedClock
			);
			SavedUnlockKey loaded = restarted.list().get(0);
			assertEquals("Alex", loaded.getLabel());
			assertEquals("Use after the September session", loaded.getNote());

			char[] revealed = restarted.reveal(loaded.getId());
			try
			{
				assertArrayEquals(original, revealed);
			}
			finally
			{
				Arrays.fill(revealed, '\0');
			}
			assertEquals(
				Instant.parse("2026-09-05T03:04:00Z"),
				restarted.list().get(0).getLastUsedAt()
			);

			assertTrue(restarted.forget(loaded.getId()));
			assertTrue(restarted.list().isEmpty());
			assertFalse(Files.exists(path));
		}
		finally
		{
			Arrays.fill(original, '\0');
		}
	}

	@Test
	public void damagedVaultIsNotSilentlyOverwritten() throws Exception
	{
		Path path = temporaryFolder.getRoot().toPath().resolve("damaged-vault.json");
		Files.write(path, "not valid json".getBytes(StandardCharsets.UTF_8));
		SavedUnlockKeyStore store = new SavedUnlockKeyStore(
			new Gson(),
			path,
			new TestUnlockKeyProtector(),
			Clock.systemUTC()
		);
		assertFalse(store.isAvailable());
		assertTrue(store.getUnavailableMessage().contains("could not read"));
		try
		{
			store.saveAcceptedKey(
				"5ea7d5da-5a64-420d-91bb-ed06378a09f9",
				"ABCD-EFGH-JKLM-NPQR-STUV".toCharArray()
			);
			fail("Expected the damaged vault to block writes");
		}
		catch (IllegalStateException expected)
		{
			assertTrue(expected.getMessage().contains("could not read"));
		}
		assertEquals("not valid json", new String(
			Files.readAllBytes(path),
			StandardCharsets.UTF_8
		));
	}
}
