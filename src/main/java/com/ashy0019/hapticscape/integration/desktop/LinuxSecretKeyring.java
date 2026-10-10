package com.ashy0019.hapticscape.integration.desktop;

/** Keys are immutable and identified by purpose plus a random, public ID. */
interface LinuxSecretKeyring
{
	boolean isAvailable();
	byte[] lookup(String purpose, String keyId);
	void store(String purpose, String keyId, byte[] key);
}
