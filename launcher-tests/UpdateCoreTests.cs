using System;
using System.IO;
using System.Diagnostics;
using System.IO.Compression;
using System.Threading;

internal static class UpdateCoreTests
{
	private static int assertions;

	private static int Main()
	{
		string root = Path.Combine(Path.GetTempPath(), "HapticScape-tests-" + Guid.NewGuid().ToString("N"));
		Directory.CreateDirectory(root);
		try
		{
			TestStartupConfirmation(root);
			TestVersions();
			TestDeepLinks();
			TestDeepLinkHandoff(root);
			TestLaunchOptions();
			TestPolicy();
			TestReleaseParsing();
			TestLumBridgeReleaseParsing();
			TestLumBridgeInstalledRecognition(root);
			TestUpdaterApplicationLayouts(root);
			TestPreferences(root);
			TestPreferenceMigration(root);
			TestChecksum(root);
			TestSafeExtraction(root);
			TestTraversalRejection(root);
			Console.WriteLine("HapticScape updater tests passed: " + assertions);
			return 0;
		}
		catch (Exception exception)
		{
			Console.Error.WriteLine(exception);
			return 1;
		}
		finally
		{
			UpdatePackagePreparer.TryDeleteDirectory(root);
		}
	}

    private static void TestStartupConfirmation(string root)
    {
        string marker = Path.Combine(root, "launcher-ready");
        Assert(!LauncherStartupValidation.IsReady(marker, "expected"), "missing startup acknowledgement is rejected");
        File.WriteAllText(marker, "partial");
        Assert(!LauncherStartupValidation.IsReady(marker, "expected"), "wrong startup acknowledgement is rejected");
        File.WriteAllText(marker, "expected");
        Assert(LauncherStartupValidation.IsReady(marker, "expected"), "exact startup acknowledgement is accepted");
        using (Process live = Process.Start(new ProcessStartInfo("cmd.exe", "/c ping -n 6 127.0.0.1 >nul") { UseShellExecute = false, CreateNoWindow = true }))
        {
            try
            {
                LauncherStartupValidation.WaitForReady(live, marker, "expected", 1000);
                Assert(true, "a live launcher with the correct acknowledgement is accepted");
                bool timedOut = false;
                try { LauncherStartupValidation.WaitForReady(live, marker, "wrong", 150); }
                catch (TimeoutException) { timedOut = true; }
                Assert(timedOut, "a launcher without the matching acknowledgement times out");
            }
            finally { if (!live.HasExited) live.Kill(); live.WaitForExit(); }
        }
        using (Process exited = Process.Start(new ProcessStartInfo("cmd.exe", "/c exit 0") { UseShellExecute = false, CreateNoWindow = true }))
        {
            exited.WaitForExit();
            bool rejected = false;
            try { LauncherStartupValidation.WaitForReady(exited, marker, "expected", 1000); }
            catch (InvalidOperationException) { rejected = true; }
            Assert(rejected, "an exited launcher is rejected even if its acknowledgement exists");
        }
    }

	private static void TestLaunchOptions()
	{
		HapticScapeLaunchOptions defaults = HapticScapeLaunchOptions.Parse(new string[0]);
		Assert(defaults.Profile == null, "ordinary launches use default storage");
		Assert(defaults.GameplayPort == 41713, "ordinary launches preserve the bridge port");
		Assert(defaults.MutexName == @"Local\HapticScape.Client",
			"ordinary launches preserve the existing instance mutex");

		HapticScapeLaunchOptions controller = HapticScapeLaunchOptions.Parse(new[]
		{
			"--profile", "Controller", "--gameplay-port=41714"
		});
		Assert(controller.Profile == "controller", "profile names are normalized");
		Assert(controller.GameplayPort == 41714, "named clients can select another port");
		Assert(controller.MutexName == @"Local\HapticScape.Client.controller",
			"named clients receive independent process mutexes");
		Assert(controller.JavaArguments() == " --profile controller --gameplay-port 41714",
			"validated options are forwarded to Java");

		HapticScapeLaunchOptions minimized = HapticScapeLaunchOptions.Parse(
			new[] { "--minimized" });
		Assert(minimized.Minimized, "minimized startup should be parsed");
		Assert(minimized.JavaArguments() == " --minimized",
			"minimized startup should be forwarded to Java");

		AssertThrows<InvalidOperationException>(delegate
		{
			HapticScapeLaunchOptions.Parse(new[] { "--profile", "../escape" });
		}, "profile path traversal should be rejected");
		AssertThrows<InvalidOperationException>(delegate
		{
			HapticScapeLaunchOptions.Parse(new[] { "--gameplay-port", "70000" });
		}, "invalid gameplay ports should be rejected");
	}

	private static void TestDeepLinks()
	{
		string valid = "hapticscape://discord/accept"
			+ "?controller=123456789012345678"
			+ "&request=abcdefghijklmnop"
			+ "&token=abcdefghijklmnopqrstuvwxyzABCDEFGH123456789";
		Assert(HapticScapeDeepLink.Read(new string[0]) == null,
			"ordinary launches should not create a deep link");
		Assert(HapticScapeDeepLink.Read(new[] { valid }) == valid,
			"strict Discord accept links should parse");
		AssertThrows<InvalidOperationException>(delegate
		{
			HapticScapeDeepLink.Read(new[] { valid + "&invitation=HSP1.secret" });
		}, "authority-bearing deep-link fields should be rejected");
		AssertThrows<InvalidOperationException>(delegate
		{
			HapticScapeDeepLink.Read(new[] { valid.Replace("discord/", "discord:1234/") });
		}, "explicit deep-link ports should be rejected");
	}

	private static void TestDeepLinkHandoff(string root)
	{
		string consumedRequest = Path.Combine(root, "deep-link-consumed.request");
		File.WriteAllText(consumedRequest, "pending");
		string consumedMutexName = @"Local\HapticScape.Test." + Guid.NewGuid().ToString("N");
		using (ManualResetEvent ownerReady = new ManualResetEvent(false))
		using (ManualResetEvent releaseOwner = new ManualResetEvent(false))
		{
			Thread ownerThread = new Thread(delegate()
			{
				bool ownerCreated;
				using (Mutex owner = new Mutex(true, consumedMutexName, out ownerCreated))
				{
					ownerReady.Set();
					releaseOwner.WaitOne();
					owner.ReleaseMutex();
				}
			});
			ownerThread.Start();
			ownerReady.WaitOne();
			bool contenderCreated;
			using (Mutex contender = new Mutex(true, consumedMutexName, out contenderCreated))
			{
				Assert(!contenderCreated, "deep-link handoff test should start with another live instance");
				Thread deleteThread = new Thread(delegate()
				{
					Thread.Sleep(100);
					File.Delete(consumedRequest);
				});
				deleteThread.Start();
				Assert(!DeepLinkInstanceHandoff.WaitForTakeover(
					contender, consumedRequest, 1500),
					"a request consumed by the live client should not launch a replacement");
				deleteThread.Join();
			}
			releaseOwner.Set();
			ownerThread.Join();
		}

		string takeoverRequest = Path.Combine(root, "deep-link-takeover.request");
		File.WriteAllText(takeoverRequest, "pending");
		string takeoverMutexName = @"Local\HapticScape.Test." + Guid.NewGuid().ToString("N");
		using (ManualResetEvent ownerReady = new ManualResetEvent(false))
		{
			Thread ownerThread = new Thread(delegate()
			{
				bool ownerCreated;
				using (Mutex owner = new Mutex(true, takeoverMutexName, out ownerCreated))
				{
					ownerReady.Set();
					Thread.Sleep(150);
					owner.ReleaseMutex();
				}
			});
			ownerThread.Start();
			ownerReady.WaitOne();
			bool contenderCreated;
			using (Mutex contender = new Mutex(true, takeoverMutexName, out contenderCreated))
			{
				Assert(!contenderCreated, "takeover test should start behind the exiting instance");
				Assert(DeepLinkInstanceHandoff.WaitForTakeover(
					contender, takeoverRequest, 1500),
					"a pending request should take over when the exiting instance releases its mutex");
				contender.ReleaseMutex();
			}
			ownerThread.Join();
		}
	}

	private static void TestVersions()
	{
		Assert(VersionUtility.IsNewer("1.6.1", "1.6.0"), "patch version should be newer");
		Assert(VersionUtility.IsNewer("v2.0.0", "1.99.0"), "v prefix should be accepted");
		Assert(!VersionUtility.IsNewer("1.6.0", "1.6.0"), "equal versions are not newer");
		Version ignored;
		Assert(!VersionUtility.TryParseStable("1.7.0-beta", out ignored), "prereleases are rejected");
	}

	private static void TestPolicy()
	{
		DateTime now = DateTime.UtcNow;
		UpdatePreferences disabled = UpdatePreferences.Defaults();
		disabled.UpdateNotifications = false;
		Assert(!UpdatePolicy.ShouldCheck(disabled, now), "fully disabled updates should not check");

		UpdatePreferences enabled = UpdatePreferences.Defaults();
		Assert(UpdatePolicy.ShouldCheck(enabled, now), "notifications should permit a check");
		enabled.LastCheckUtc = now.Subtract(TimeSpan.FromHours(1));
		Assert(!UpdatePolicy.ShouldCheck(enabled, now), "recent successful checks should be cached");
		enabled.ForceCheck = true;
		Assert(UpdatePolicy.ShouldCheck(enabled, now), "manual check should bypass the cache");
	}

	private static void TestReleaseParsing()
	{
		string json = "{"
			+ "\"tag_name\":\"v1.6.0\",\"draft\":false,\"prerelease\":false,"
			+ "\"assets\":["
			+ "{\"name\":\"HapticScape-Windows-x64-1.6.0.zip\","
			+ "\"browser_download_url\":\"https://github.com/ashy0019/HapticScape/releases/download/v1.6.0/HapticScape-Windows-x64-1.6.0.zip\"},"
			+ "{\"name\":\"HapticScape-Windows-x64-1.6.0.zip.sha256\","
			+ "\"browser_download_url\":\"https://github.com/ashy0019/HapticScape/releases/download/v1.6.0/HapticScape-Windows-x64-1.6.0.zip.sha256\"}]}";
		UpdateRelease release = GitHubReleaseClient.ParseLatest(json, "x64");
		Assert(release.Version == "1.6.0", "release version should be normalized");
		Assert(release.ZipName == "HapticScape-Windows-x64-1.6.0.zip", "asset name should match exactly");
	}

	private static void TestLumBridgeReleaseParsing()
	{
		Assert(!LumBridgeSupport.IsRequired("2.4.0"),
			"HapticScape 2 should not require LumBridge");
		Assert(LumBridgeSupport.IsRequired("3.0.0"),
			"HapticScape 3 should require LumBridge");
		Assert(LumBridgeSupport.AssetName("3.0.0", "x64")
			== "LumBridge-Windows-x64-3.0.0.zip",
			"LumBridge asset naming should match package-all output");

		string json = "{"
			+ "\"tag_name\":\"v3.0.0\",\"draft\":false,\"prerelease\":false,"
			+ "\"assets\":["
			+ "{\"name\":\"HapticScape-Windows-x64-3.0.0.zip\","
			+ "\"browser_download_url\":\"https://github.com/ashy0019/HapticScape/releases/download/v3.0.0/HapticScape-Windows-x64-3.0.0.zip\"},"
			+ "{\"name\":\"HapticScape-Windows-x64-3.0.0.zip.sha256\","
			+ "\"browser_download_url\":\"https://github.com/ashy0019/HapticScape/releases/download/v3.0.0/HapticScape-Windows-x64-3.0.0.zip.sha256\"},"
			+ "{\"name\":\"LumBridge-Windows-x64-3.0.0.zip\","
			+ "\"browser_download_url\":\"https://github.com/ashy0019/HapticScape/releases/download/v3.0.0/LumBridge-Windows-x64-3.0.0.zip\"},"
			+ "{\"name\":\"LumBridge-Windows-x64-3.0.0.zip.sha256\","
			+ "\"browser_download_url\":\"https://github.com/ashy0019/HapticScape/releases/download/v3.0.0/LumBridge-Windows-x64-3.0.0.zip.sha256\"}]}";
		UpdateRelease release = GitHubReleaseClient.ParseLatest(json, "x64");
		Assert(release.HasLumBridge,
			"HapticScape 3 releases should carry the LumBridge companion asset");
		Assert(release.LumBridgeZipName == "LumBridge-Windows-x64-3.0.0.zip",
			"the matching LumBridge asset should be selected");

		string missingLumBridge = "{"
			+ "\"tag_name\":\"v3.0.0\",\"draft\":false,\"prerelease\":false,"
			+ "\"assets\":["
			+ "{\"name\":\"HapticScape-Windows-x64-3.0.0.zip\","
			+ "\"browser_download_url\":\"https://github.com/ashy0019/HapticScape/releases/download/v3.0.0/HapticScape-Windows-x64-3.0.0.zip\"},"
			+ "{\"name\":\"HapticScape-Windows-x64-3.0.0.zip.sha256\","
			+ "\"browser_download_url\":\"https://github.com/ashy0019/HapticScape/releases/download/v3.0.0/HapticScape-Windows-x64-3.0.0.zip.sha256\"}]}";
		UpdateRelease hapticScapeOnly = GitHubReleaseClient.ParseLatest(
			missingLumBridge,
			"x64");
		Assert(!hapticScapeOnly.HasLumBridge,
			"a missing companion must not block the HapticScape update itself");
	}

	private static void TestLumBridgeInstalledRecognition(string root)
	{
		string application = Path.Combine(root, "lumbridge-install-check");
		Directory.CreateDirectory(application);
		string installedManifestPath = Path.Combine(root, "hapticscape-release.json");
		File.WriteAllText(
			installedManifestPath,
			"{\"version\":\"3.0.0\",\"architecture\":\"x64\","
				+ "\"repository\":\"ashy0019/HapticScape\"}");
		ReleaseManifest manifest = ReleaseManifest.Load(installedManifestPath);
		Assert(!LumBridgeSupport.IsInstalled(manifest, application),
			"missing LumBridge should require companion setup");

		string lumBridgeApp = Path.Combine(application, "LumBridge", "app");
		Directory.CreateDirectory(lumBridgeApp);
		File.WriteAllText(Path.Combine(application, "LumBridge", "LumBridge.exe"), "stub");
		File.WriteAllText(Path.Combine(lumBridgeApp, "lumbridge.jar"), "stub");
		File.WriteAllText(
			Path.Combine(lumBridgeApp, "release.json"),
			"{\"version\":\"3.0.0\",\"architecture\":\"x64\","
				+ "\"repository\":\"ashy0019/HapticScape\"}");
		Assert(LumBridgeSupport.IsInstalled(manifest, application),
			"matching LumBridge should satisfy companion setup");

		File.WriteAllText(
			Path.Combine(lumBridgeApp, "release.json"),
			"{\"version\":\"2.9.9\",\"architecture\":\"x64\","
				+ "\"repository\":\"ashy0019/HapticScape\"}");
		Assert(!LumBridgeSupport.IsInstalled(manifest, application),
			"a mismatched LumBridge should be refreshed");
	}

	private static void TestUpdaterApplicationLayouts(string root)
	{
		string legacy = Path.Combine(root, "legacy-2x-layout");
		Directory.CreateDirectory(Path.Combine(legacy, "app"));
		File.WriteAllText(Path.Combine(legacy, "HapticScape.exe"), "stub");
		File.WriteAllText(Path.Combine(legacy, "app", "hapticscape-client.jar"), "stub");
		File.WriteAllText(Path.Combine(legacy, "app", "release.json"), "{}");
		Assert(ApplicationLayoutValidation.IsValidInstalledApplication(legacy),
			"2.x installs should be accepted as an update source");
		Assert(!ApplicationLayoutValidation.IsValidStagedApplication(legacy),
			"2.x layouts must not be accepted as a new staged application");

		string standalone = Path.Combine(root, "standalone-3x-layout");
		Directory.CreateDirectory(Path.Combine(standalone, "app"));
		Directory.CreateDirectory(Path.Combine(standalone, "runtime", "bin"));
		File.WriteAllText(Path.Combine(standalone, "HapticScape.exe"), "stub");
		File.WriteAllText(Path.Combine(standalone, "app", "hapticscape-desktop.jar"), "stub");
		File.WriteAllText(Path.Combine(standalone, "app", "hapticscape-client.jar"), "stub");
		File.WriteAllText(Path.Combine(standalone, "app", "release.json"), "{}");
		File.WriteAllText(Path.Combine(standalone, "runtime", "bin", "javaw.exe"), "stub");
		Assert(ApplicationLayoutValidation.IsValidInstalledApplication(standalone),
			"3.x installs should be accepted as an update source");
		Assert(ApplicationLayoutValidation.IsValidStagedApplication(standalone),
			"complete 3.x layouts should be accepted for installation");

        File.WriteAllText(Path.Combine(standalone, "app", "suite.json"), "{}");
        Assert(!ApplicationLayoutValidation.IsValidStagedApplication(standalone), "a suite marker cannot hide missing launcher components");
        Directory.CreateDirectory(Path.Combine(standalone, "launcher"));
        Directory.CreateDirectory(Path.Combine(standalone, "LumBridge", "app"));
        foreach (string name in new[] { "HapticScapeLauncher.exe", "MicrosoftEdgeWebview2Setup.exe" })
            File.WriteAllText(Path.Combine(standalone, "launcher", name), "stub");
        File.WriteAllText(Path.Combine(standalone, "HapticScapeLegacy.exe"), "stub");
        File.WriteAllText(Path.Combine(standalone, "LumBridge", "app", "lumbridge.jar"), "stub");
        Assert(ApplicationLayoutValidation.IsValidStagedApplication(standalone), "a complete suite retains the historical accepted layout");

		File.Delete(Path.Combine(standalone, "runtime", "bin", "javaw.exe"));
		Assert(!ApplicationLayoutValidation.IsValidInstalledApplication(standalone),
			"a damaged 3.x install must not fall back to its legacy compatibility JAR");
		Assert(!ApplicationLayoutValidation.IsValidStagedApplication(standalone),
			"staged 3.x applications require their bundled runtime");
	}

	private static void TestPreferences(string root)
	{
		string path = Path.Combine(root, "preferences", "updater-settings.json");
		UpdatePreferences saved = UpdatePreferences.Defaults();
		saved.AutomaticUpdates = true;
		saved.UpdateNotifications = false;
		saved.SkippedVersion = "1.6.2";
		saved.ForceCheck = true;
		UpdatePreferencesStore.Save(path, saved);
		UpdatePreferences loaded = UpdatePreferencesStore.Load(path);
		Assert(loaded.AutomaticUpdates, "automatic update choice should round-trip");
		Assert(!loaded.UpdateNotifications, "notification choice should round-trip");
		Assert(loaded.SkippedVersion == "1.6.2", "skipped version should round-trip");
		Assert(loaded.ForceCheck, "manual check request should round-trip");
	}

	private static void TestPreferenceMigration(string root)
	{
		string legacy = Path.Combine(root, "legacy", "updater-settings.json");
		string current = Path.Combine(root, "current", "updater-settings.json");
		Directory.CreateDirectory(Path.GetDirectoryName(legacy));
		File.WriteAllText(legacy, "{\"automaticUpdates\":true}");

		UpdatePreferencesStore.TryMigrateLegacyPath(current, legacy);
		Assert(File.Exists(current), "legacy updater preferences should migrate to HapticScape storage");
		Assert(UpdatePreferencesStore.Load(current).AutomaticUpdates,
			"migrated updater preferences should remain readable");

		File.WriteAllText(current, "{\"automaticUpdates\":false}");
		UpdatePreferencesStore.TryMigrateLegacyPath(current, legacy);
		Assert(!UpdatePreferencesStore.Load(current).AutomaticUpdates,
			"existing HapticScape preferences should win over legacy settings");
	}

	private static void TestChecksum(string root)
	{
		string path = Path.Combine(root, "checksum.txt");
		File.WriteAllText(path, "abc");
		UpdatePackagePreparer.VerifySha256(
			path,
			"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad *checksum.txt");
		Assert(true, "known SHA-256 should verify");
		AssertThrows<InvalidDataException>(delegate
		{
			UpdatePackagePreparer.VerifySha256(path, new string('0', 64));
		}, "incorrect SHA-256 should fail");
	}

	private static void TestSafeExtraction(string root)
	{
		string zip = Path.Combine(root, "safe.zip");
		using (ZipArchive archive = ZipFile.Open(zip, ZipArchiveMode.Create))
		{
			ZipArchiveEntry entry = archive.CreateEntry("HapticScape/app/test.txt");
			using (StreamWriter writer = new StreamWriter(entry.Open()))
			{
				writer.Write("safe");
			}
		}
		string output = Path.Combine(root, "safe-output");
		UpdatePackagePreparer.ExtractSafely(zip, output);
		Assert(File.Exists(Path.Combine(output, "HapticScape", "app", "test.txt")),
			"safe archive should extract");
	}

	private static void TestTraversalRejection(string root)
	{
		string zip = Path.Combine(root, "unsafe.zip");
		using (ZipArchive archive = ZipFile.Open(zip, ZipArchiveMode.Create))
		{
			ZipArchiveEntry entry = archive.CreateEntry("../escape.txt");
			using (StreamWriter writer = new StreamWriter(entry.Open()))
			{
				writer.Write("unsafe");
			}
		}
		AssertThrows<InvalidDataException>(delegate
		{
			UpdatePackagePreparer.ExtractSafely(zip, Path.Combine(root, "unsafe-output"));
		}, "path traversal should be rejected");
	}

	private static void Assert(bool condition, string message)
	{
		assertions++;
		if (!condition)
		{
			throw new InvalidOperationException("Assertion failed: " + message);
		}
	}

	private static void AssertThrows<T>(Action action, string message) where T : Exception
	{
		assertions++;
		try
		{
			action();
		}
		catch (T)
		{
			return;
		}
		throw new InvalidOperationException("Assertion failed: " + message);
	}
}
