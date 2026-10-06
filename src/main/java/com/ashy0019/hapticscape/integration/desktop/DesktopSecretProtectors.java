package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.remote.UnlockKeyProtector;

/** Supplies secret protection for the standalone desktop host. */
public final class DesktopSecretProtectors
{
	private DesktopSecretProtectors()
	{
	}

	public static UnlockKeyProtector savedUnlockKeys()
	{
		return savedUnlockKeys(DesktopPlatform.current());
	}

	public static UnlockKeyProtector discordCredentials()
	{
		return discordCredentials(DesktopPlatform.current());
	}

	static UnlockKeyProtector savedUnlockKeys(DesktopPlatform platform)
	{
		return platform == DesktopPlatform.WINDOWS
			? new WindowsDpapiUnlockKeyProtector()
			: platform == DesktopPlatform.LINUX
				? new LinuxKeyringSecretProtector((byte) 1, new LibsecretKeyring())
				: unavailable(platform, "Saved Unlock Keys");
	}

	static UnlockKeyProtector discordCredentials(DesktopPlatform platform)
	{
		return platform == DesktopPlatform.WINDOWS
			? new WindowsDpapiDiscordCredentialProtector()
			: platform == DesktopPlatform.LINUX
				? new LinuxKeyringSecretProtector((byte) 2, new LibsecretKeyring())
				: unavailable(platform, "Discord credential storage");
	}

	private static UnlockKeyProtector unavailable(DesktopPlatform platform, String feature)
	{
		String message = platform == DesktopPlatform.LINUX
			? feature + ": Linux keyring support is not implemented yet"
			: feature + " is unavailable on this platform";
		return new UnlockKeyProtector()
		{
			@Override
			public boolean isAvailable() { return false; }

			@Override
			public String getUnavailableMessage() { return message; }

			@Override
			public byte[] protect(byte[] plaintext)
			{
				throw new IllegalStateException(message);
			}

			@Override
			public byte[] unprotect(byte[] ciphertext)
			{
				throw new IllegalStateException(message);
			}
		};
	}
}
