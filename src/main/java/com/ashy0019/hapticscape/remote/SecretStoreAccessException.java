package com.ashy0019.hapticscape.remote;

/** A wallet access failure that may be retried without replacing stored data. */
public final class SecretStoreAccessException extends IllegalStateException
{
	public SecretStoreAccessException(String message)
	{
		super(message);
	}
}
