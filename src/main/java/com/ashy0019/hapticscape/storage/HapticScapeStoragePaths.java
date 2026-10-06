package com.ashy0019.hapticscape.storage;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Source-neutral locations for HapticScape's durable local state.
 *
 * <p>The host chooses the root directory. Core persistence code only asks for
 * the HapticScape-owned files it needs and does not know how that root was
 * selected.</p>
 */
public final class HapticScapeStoragePaths
{
	private final Path dataDirectory;

	public HapticScapeStoragePaths(Path dataDirectory)
	{
		this.dataDirectory = Objects.requireNonNull(dataDirectory, "dataDirectory");
	}

	public Path getDataDirectory()
	{
		return dataDirectory;
	}

	public Path getSettingsPath()
	{
		return dataDirectory.resolve("settings.properties");
	}

	public Path getSavedUnlockKeysPath()
	{
		return dataDirectory.resolve("saved-unlock-keys.json");
	}

	public Path getSettingsLockPath()
	{
		return dataDirectory.resolve("settings-lock.json");
	}

	public Path getRemoteClientIdentityPath()
	{
		return dataDirectory.resolve("remote-client-id.txt");
	}

	public Path getDiscordCredentialPath()
	{
		return dataDirectory.resolve("discord-device.json");
	}

	public Path getProtectedExitStatePath()
	{
		return dataDirectory.resolve("protected-exit-state.properties");
	}
}
