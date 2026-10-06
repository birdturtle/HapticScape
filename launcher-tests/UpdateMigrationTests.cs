using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
internal static class UpdateMigrationTests
{
    private static int Main(string[] args)
    {
        string root = Path.Combine(Path.GetTempPath(), "HapticScape-migration-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try {
            foreach (bool legacy in new[] { true, false })
            foreach (bool fail in new[] { false, true }) Test(root, args[0], legacy, fail);
            Console.WriteLine("Migration transactions passed: legacy/current layouts, successful handoff and startup-failure rollback.");
            return 0;
        } catch (Exception e) { Console.Error.WriteLine(e); return 1; }
        finally { Directory.Delete(root, true); }
    }
    private static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    private static void Test(string root, string fixture, bool legacy, bool fail)
    {
        string scenario = Path.Combine(root, Guid.NewGuid().ToString("N"));
        string installed = Path.Combine(scenario, "HapticScape");
        string temporary = Path.Combine(scenario, "HapticScape-update-test");
        string staged = Path.Combine(temporary, "HapticScape");
        string data = Path.Combine(scenario, "user-data");
        Directory.CreateDirectory(data);
        File.WriteAllText(Path.Combine(data, "pairing.json"), "existing pairing");
        Layout(installed, fixture);
        File.WriteAllText(Path.Combine(installed, "old-version"), "old");
        if (legacy) File.Delete(Path.Combine(installed, "app", "hapticscape-desktop.jar"));
        Layout(staged, fixture);
        File.WriteAllText(Path.Combine(staged, "app", "suite.json"), "{}");
        Directory.CreateDirectory(Path.Combine(staged, "launcher"));
        Directory.CreateDirectory(Path.Combine(staged, "LumBridge", "app"));
        File.Copy(fixture, Path.Combine(staged, "launcher", "HapticScapeLauncher.exe"));
        File.Copy(fixture, Path.Combine(staged, "HapticScapeLegacy.exe"));
        File.WriteAllText(Path.Combine(staged, "launcher", "MicrosoftEdgeWebview2Setup.exe"), "fixture");
        File.WriteAllText(Path.Combine(staged, "LumBridge", "app", "lumbridge.jar"), "fixture");
        if (fail) File.WriteAllText(Path.Combine(staged, "launcher", "fail-startup"), "yes");
        int parentPid;
        using (Process parent = Process.Start(new ProcessStartInfo("cmd.exe", "/c exit 0") { UseShellExecute = false, CreateNoWindow = true })) { parentPid = parent.Id; parent.WaitForExit(); }
        string error = null;
        int runtimeChecks = 0;
        int result = HapticScapeUpdater.Run(new[] {
            "--parent-pid", parentPid.ToString(), "--install-dir", installed,
            "--staged-dir", staged, "--temporary-root", temporary
        }, delegate(string path) { Check(path == installed, "runtime setup used wrong installation"); runtimeChecks++; }, delegate(string message) { error = message; });
        Check(runtimeChecks == 1, "runtime prerequisite was not checked");
        Check(result == (fail ? 1 : 0), "unexpected transaction result");
        Check((error != null) == fail, "unexpected error reporting");
        Check(File.Exists(Path.Combine(installed, "old-version")) == fail, "old installation was not replaced/restored correctly");
        Check(File.Exists(Path.Combine(installed, "app", "suite.json")) != fail, "suite activation/rollback failed");
        Check(File.ReadAllText(Path.Combine(data, "pairing.json")) == "existing pairing", "user data changed");
        Check(Directory.GetDirectories(scenario, "HapticScape-backup-*").Length == 0, "successful replacement or rollback left a backup");
        Check(Directory.Exists(temporary) == fail, "successful update did not clean staging");
        Thread.Sleep(2000); // Let the controlled successful launcher finish before cleanup.
    }
    private static void Layout(string root, string fixture)
    {
        Directory.CreateDirectory(Path.Combine(root, "app"));
        Directory.CreateDirectory(Path.Combine(root, "runtime", "bin"));
        File.Copy(fixture, Path.Combine(root, "HapticScape.exe"));
        foreach (string name in new[] { "hapticscape-client.jar", "hapticscape-desktop.jar", "release.json" })
            File.WriteAllText(Path.Combine(root, "app", name), "{}");
        File.WriteAllText(Path.Combine(root, "runtime", "bin", "javaw.exe"), "fixture");
    }
}
