package com.ashy0019.hapticscape.storage;

import static org.junit.Assert.assertEquals;

import java.nio.file.Paths;
import org.junit.Test;

public class HapticScapeStoragePathsTest
{
	@Test
	public void resolvesLegacyCompatiblePersistenceFilesFromHostRoot()
	{
		java.nio.file.Path root = Paths.get("test-root", "hapticscape");
		HapticScapeStoragePaths paths = new HapticScapeStoragePaths(root);

		assertEquals(root, paths.getDataDirectory());
		assertEquals(root.resolve("settings.properties"), paths.getSettingsPath());
		assertEquals(root.resolve("saved-unlock-keys.json"), paths.getSavedUnlockKeysPath());
		assertEquals(root.resolve("settings-lock.json"), paths.getSettingsLockPath());
		assertEquals(root.resolve("discord-device.json"), paths.getDiscordCredentialPath());
	}
}
