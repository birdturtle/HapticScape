package com.ashy0019.hapticscape.remote;

import com.ashy0019.hapticscape.integration.desktop.InMemoryLinuxKeyring;
import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class LinuxSecretStoresTest
{
	@Rule public TemporaryFolder temporaryFolder = new TemporaryFolder();
	private static final String LOCK_ID = "a42ff467-2ec6-4c77-9306-b1d603121984";
	private static final String KEY = "ABCD-EFGH-JKLM-NPQR-STUV";
	private static final String SECRET = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG";

	@Test
	public void vaultLoadsWhileUnavailableAndNeverOverwritesSkippedData() throws Exception
	{
		Path path = path("keys.json");
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		SavedUnlockKey saved = vault(path, wallet).saveAcceptedKey(LOCK_ID, KEY.toCharArray());
		byte[] previous = Files.readAllBytes(path);
		wallet.available = false;
		SavedUnlockKeyStore restarted = vault(path, wallet);
		assertFalse(restarted.isAvailable());
		assertEquals(1, restarted.list().size());
		wallet.available = true;
		wallet.locked = true;
		try { restarted.reveal(saved.getId()); fail(); }
		catch (SecretStoreAccessException expected) { }
		assertArrayEquals(previous, Files.readAllBytes(path));
		wallet.locked = false;
		assertArrayEquals(KEY.toCharArray(), restarted.reveal(saved.getId()));
		assertTrue(restarted.forget(saved.getId()));
		assertFalse(Files.exists(path));
	}

	@Test
	public void foreignVaultIsRejectedBeforeItCanBeChanged() throws Exception
	{
		Path path = path("foreign-keys.json");
		SavedUnlockKeyStore windows = new SavedUnlockKeyStore(new Gson(), path,
			new TestUnlockKeyProtector(), Clock.systemUTC());
		windows.saveAcceptedKey(LOCK_ID, KEY.toCharArray());
		byte[] original = Files.readAllBytes(path);
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		SavedUnlockKeyStore linux = vault(path, wallet);
		assertFalse(linux.isAvailable());
		try { linux.saveAcceptedKey(LOCK_ID, KEY.toCharArray()); fail(); }
		catch (IllegalStateException expected) { }
		assertEquals(0, wallet.writes);
		assertEquals(0, wallet.reads);
		assertArrayEquals(original, Files.readAllBytes(path));
	}

	@Test
	public void discordWalletCancellationIsRetryableAndBlocksReplacement() throws Exception
	{
		Path path = path("discord.json");
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		DiscordCredentialStore initial = discord(path, wallet);
		initial.save(credential());
		byte[] previous = Files.readAllBytes(path);
		assertFalse(new String(previous, StandardCharsets.UTF_8).contains(SECRET));
		wallet.available = false;
		DiscordCredentialStore restarted = discord(path, wallet);
		assertEquals(0, wallet.reads); // Construction validates format without wallet access.
		wallet.available = true;
		wallet.locked = true;
		try { restarted.get(); fail(); }
		catch (SecretStoreAccessException expected) { }
		try { restarted.save(credential()); fail(); }
		catch (SecretStoreAccessException expected) { }
		assertTrue(restarted.isAvailable()); // Access failure does not permanently poison the store.
		assertArrayEquals(previous, Files.readAllBytes(path));
		assertEquals(1, wallet.writes);
		wallet.locked = false;
		assertEquals(SECRET, restarted.get().get().getSecret());
		restarted.clear();
		assertFalse(Files.exists(path));
		assertFalse(restarted.get().isPresent());
	}

	@Test
	public void persistenceFailureDoesNotPublishCredentialOrDamagePriorFile() throws Exception
	{
		Path path = path("discord-write.json");
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		DiscordCredentialStore store = discord(path, wallet);
		store.save(credential());
		byte[] previous = Files.readAllBytes(path);
		Files.createDirectory(path.resolveSibling(path.getFileName() + ".tmp"));
		try { store.save(new DiscordDeviceCredential("123456789012345678", "Changed",
			"wss://relay.example/relay", SECRET)); fail(); }
		catch (IllegalStateException expected) { }
		assertArrayEquals(previous, Files.readAllBytes(path));
		assertEquals("Test User", store.get().get().getDisplayName());
	}

	@Test
	public void missingDiscordKeyAndAuthenticationFailurePreserveExistingFile() throws Exception
	{
		Path path = path("discord-damaged.json");
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		discord(path, wallet).save(credential());
		byte[] original = Files.readAllBytes(path);
		wallet.removeKeys();
		DiscordCredentialStore missingKey = discord(path, wallet);
		try { missingKey.get(); fail(); }
		catch (SecretStoreAccessException expected) { }
		assertArrayEquals(original, Files.readAllBytes(path));
		assertEquals(1, wallet.writes);

		// A valid envelope with a changed authentication tag passes format checks,
		// but must fail authentication before it can be replaced.
		Path tampered = path("discord-tampered.json");
		InMemoryLinuxKeyring secondWallet = new InMemoryLinuxKeyring();
		discord(tampered, secondWallet).save(credential());
		com.google.gson.JsonObject file = new Gson().fromJson(
			new String(Files.readAllBytes(tampered), StandardCharsets.UTF_8), com.google.gson.JsonObject.class);
		byte[] payload = java.util.Base64.getDecoder().decode(file.get("protectedSecret").getAsString());
		payload[payload.length - 1] ^= 1;
		file.addProperty("protectedSecret", java.util.Base64.getEncoder().encodeToString(payload));
		Files.write(tampered, file.toString().getBytes(StandardCharsets.UTF_8));
		byte[] modified = Files.readAllBytes(tampered);
		DiscordCredentialStore corrupt = discord(tampered, secondWallet);
		try { corrupt.get(); fail(); }
		catch (IllegalStateException expected) { }
		assertFalse(corrupt.isAvailable());
		try { corrupt.save(credential()); fail(); }
		catch (IllegalStateException expected) { }
		assertArrayEquals(modified, Files.readAllBytes(tampered));
		assertEquals(1, secondWallet.writes);
	}

	@Test
	public void foreignDiscordPayloadBlocksWritesWithoutOpeningWallet() throws Exception
	{
		Path path = path("discord-foreign.json");
		DiscordCredentialStore foreign = new DiscordCredentialStore(new Gson(), path, new TestUnlockKeyProtector());
		foreign.save(credential());
		byte[] original = Files.readAllBytes(path);
		InMemoryLinuxKeyring wallet = new InMemoryLinuxKeyring();
		DiscordCredentialStore linux = discord(path, wallet);
		assertFalse(linux.isAvailable());
		try { linux.save(credential()); fail(); }
		catch (IllegalStateException expected) { }
		assertArrayEquals(original, Files.readAllBytes(path));
		assertEquals(0, wallet.writes);
		assertEquals(0, wallet.reads);
	}

	private Path path(String name) { return temporaryFolder.getRoot().toPath().resolve(name); }
	private SavedUnlockKeyStore vault(Path path, InMemoryLinuxKeyring wallet)
	{
		return new SavedUnlockKeyStore(new Gson(), path, wallet.unlockKeys(), Clock.systemUTC());
	}
	private DiscordCredentialStore discord(Path path, InMemoryLinuxKeyring wallet)
	{
		return new DiscordCredentialStore(new Gson(), path, wallet.discord());
	}
	private DiscordDeviceCredential credential()
	{
		return new DiscordDeviceCredential("123456789012345678", "Test User", "wss://relay.example/relay", SECRET);
	}
}
