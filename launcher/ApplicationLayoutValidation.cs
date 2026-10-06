using System;
using System.IO;

internal static class ApplicationLayoutValidation
{
	internal static bool IsValidInstalledApplication(string directory)
	{
		string launcher = Path.Combine(directory, "HapticScape.exe");
		string manifest = Path.Combine(directory, "app", "release.json");
		string desktopJar = Path.Combine(directory, "app", "hapticscape-desktop.jar");
		string legacyJar = Path.Combine(directory, "app", "hapticscape-client.jar");
		string bundledJava = Path.Combine(directory, "runtime", "bin", "javaw.exe");

		bool legacyLayout = !File.Exists(desktopJar) && File.Exists(legacyJar);
		bool standaloneLayout = File.Exists(desktopJar) && File.Exists(bundledJava);
		return File.Exists(launcher)
			&& File.Exists(manifest)
			&& (legacyLayout || standaloneLayout);
	}

	internal static bool IsValidStagedApplication(string directory)
	{
		bool suite = File.Exists(Path.Combine(directory, "app", "suite.json"));
        if (suite && (!File.Exists(Path.Combine(directory, "launcher", "HapticScapeLauncher.exe"))
            || !File.Exists(Path.Combine(directory, "launcher", "MicrosoftEdgeWebview2Setup.exe"))
            || !File.Exists(Path.Combine(directory, "HapticScapeLegacy.exe"))
            || !File.Exists(Path.Combine(directory, "LumBridge", "app", "lumbridge.jar")))) return false;
		return File.Exists(Path.Combine(directory, "HapticScape.exe"))
			&& File.Exists(Path.Combine(directory, "app", "hapticscape-desktop.jar"))
			&& File.Exists(Path.Combine(directory, "app", "release.json"))
			&& File.Exists(Path.Combine(directory, "runtime", "bin", "javaw.exe"));
	}

	internal static void ValidateInstalledApplication(string directory)
	{
		if (!IsValidInstalledApplication(directory))
		{
			throw new InvalidDataException(
				"The installed HapticScape application directory failed validation.");
		}
	}

	internal static void ValidateStagedApplication(string directory)
	{
		if (!IsValidStagedApplication(directory))
		{
			throw new InvalidDataException(
				"The staged HapticScape application directory failed validation.");
		}
	}
}
