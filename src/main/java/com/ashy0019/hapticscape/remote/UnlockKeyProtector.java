package com.ashy0019.hapticscape.remote;

public interface UnlockKeyProtector
{
	boolean isAvailable();

	String getUnavailableMessage();

	/** True when wallet operations can prompt or wait for a desktop service. */
	default boolean requiresBackgroundThread() { return false; }

	/** Checks payload format without reading a key or prompting the user. */
	default void validateCiphertext(byte[] ciphertext) { }

	byte[] protect(byte[] plaintext);

	byte[] unprotect(byte[] ciphertext);
}
