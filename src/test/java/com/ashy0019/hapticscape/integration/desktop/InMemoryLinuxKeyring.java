package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.remote.SecretStoreAccessException;
import com.ashy0019.hapticscape.remote.UnlockKeyProtector;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Contract test fixture; never contacts a desktop wallet. */
public final class InMemoryLinuxKeyring implements LinuxSecretKeyring
{
	public boolean available = true;
	public boolean locked;
	public int writes;
	public int reads;
	public byte[] lastBorrowedKey;
	private final Map<String, byte[]> keys = new HashMap<>();

	public UnlockKeyProtector unlockKeys() { return new LinuxKeyringSecretProtector((byte) 1, this); }
	public UnlockKeyProtector discord() { return new LinuxKeyringSecretProtector((byte) 2, this); }
	public void removeKeys() { keys.values().forEach(key -> Arrays.fill(key, (byte) 0)); keys.clear(); }

	@Override
	public boolean isAvailable() { return available; }

	@Override
	public byte[] lookup(String purpose, String id)
	{
		reads++;
		if (locked) throw new SecretStoreAccessException("Wallet locked or unlock cancelled");
		byte[] key = keys.get(purpose + id);
		lastBorrowedKey = key == null ? null : key.clone();
		return lastBorrowedKey;
	}

	@Override
	public void store(String purpose, String id, byte[] key)
	{
		if (locked) throw new SecretStoreAccessException("Wallet locked or unlock cancelled");
		writes++;
		if (keys.containsKey(purpose + id)) throw new AssertionError("Wrapping keys must never be replaced");
		keys.put(purpose + id, key.clone());
		lastBorrowedKey = key;
	}
}
