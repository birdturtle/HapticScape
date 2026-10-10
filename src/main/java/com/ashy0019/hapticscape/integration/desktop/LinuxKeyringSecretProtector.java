package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.remote.SecretStoreAccessException;
import com.ashy0019.hapticscape.remote.UnlockKeyProtector;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated Linux envelopes; encryption keys live only in Secret Service. */
final class LinuxKeyringSecretProtector implements UnlockKeyProtector
{
	private static final byte[] MAGIC = { 'H', 'S', 'L', 'K' };
	private static final int HEADER_BYTES = 4 + 1 + 1 + 16 + 12;
	private static final int MAX_PLAINTEXT_BYTES = 4096;
	private final byte purpose;
	private final LinuxSecretKeyring keyring;
	private final SecureRandom random = new SecureRandom();

	LinuxKeyringSecretProtector(byte purpose, LinuxSecretKeyring keyring)
	{
		if (purpose != 1 && purpose != 2) throw new IllegalArgumentException("Unknown secret purpose");
		this.purpose = purpose;
		this.keyring = Objects.requireNonNull(keyring, "keyring");
	}

	@Override
	public boolean isAvailable() { return keyring.isAvailable(); }

	@Override
	public String getUnavailableMessage()
	{
		return isAvailable() ? "" : "Linux secure storage requires libsecret and a desktop session bus";
	}

	@Override
	public boolean requiresBackgroundThread() { return true; }

	@Override
	public void validateCiphertext(byte[] ciphertext)
	{
		Objects.requireNonNull(ciphertext, "ciphertext");
		if (ciphertext.length < HEADER_BYTES + 16
			|| ciphertext.length > HEADER_BYTES + MAX_PLAINTEXT_BYTES + 16
			|| !Arrays.equals(MAGIC, Arrays.copyOf(ciphertext, MAGIC.length))
			|| ciphertext[4] != 1 || ciphertext[5] != purpose)
		{
			throw new IllegalArgumentException("Unsupported or damaged Linux secret payload; Windows DPAPI data cannot be opened on Linux");
		}
	}

	@Override
	public byte[] protect(byte[] plaintext)
	{
		Objects.requireNonNull(plaintext, "plaintext");
		if (plaintext.length > MAX_PLAINTEXT_BYTES) throw new IllegalArgumentException("Secret is too large");
		requireAvailable();
		UUID id = UUID.randomUUID();
		byte[] nonce = new byte[12];
		random.nextBytes(nonce);
		byte[] header = ByteBuffer.allocate(HEADER_BYTES).put(MAGIC).put((byte) 1).put(purpose)
			.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).put(nonce).array();
		byte[] key = new byte[32];
		random.nextBytes(key);
		try
		{
			// Never overwrite another writer's wrapping key. Persist it before publishing ciphertext.
			keyring.store(purposeName(), id.toString(), key);
			byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, nonce, header, plaintext);
			return ByteBuffer.allocate(header.length + encrypted.length).put(header).put(encrypted).array();
		}
		finally
		{
			Arrays.fill(key, (byte) 0);
		}
	}

	@Override
	public byte[] unprotect(byte[] ciphertext)
	{
		validateCiphertext(ciphertext);
		requireAvailable();
		ByteBuffer input = ByteBuffer.wrap(ciphertext);
		input.position(6);
		UUID id = new UUID(input.getLong(), input.getLong());
		byte[] nonce = new byte[12];
		input.get(nonce);
		byte[] key = keyring.lookup(purposeName(), id.toString());
		if (key == null) throw new SecretStoreAccessException("The Linux keyring encryption key is missing; restore the original wallet to open this data");
		try
		{
			if (key.length != 32) throw new IllegalArgumentException("The Linux keyring encryption key is damaged");
			return crypt(Cipher.DECRYPT_MODE, key, nonce, Arrays.copyOf(ciphertext, HEADER_BYTES),
				Arrays.copyOfRange(ciphertext, HEADER_BYTES, ciphertext.length));
		}
		finally
		{
			Arrays.fill(key, (byte) 0);
		}
	}

	private byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] header, byte[] input)
	{
		try
		{
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
			cipher.updateAAD(header);
			return cipher.doFinal(input);
		}
		catch (GeneralSecurityException exception)
		{
			throw new IllegalStateException("Linux secret authentication or encryption failed", exception);
		}
	}

	private String purposeName() { return purpose == 1 ? "unlock-keys" : "discord-credentials"; }

	private void requireAvailable()
	{
		if (!isAvailable()) throw new SecretStoreAccessException(getUnavailableMessage());
	}
}
