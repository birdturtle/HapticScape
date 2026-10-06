using System;
using System.IO;
internal static class SuiteInstallerTests
{
    private static void Check(bool condition, string message) { if (!condition) throw new Exception(message); }
    private static int Main()
    {
        string root = Path.Combine(Path.GetTempPath(), "HapticScape-installer-tests-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(root);
        try
        {
            string installed = Path.Combine(root, "HapticScape");
            Func<string, Stream> resource = delegate(string file) { return new MemoryStream(System.Text.Encoding.UTF8.GetBytes(file)); };
            bool rejected = false;
            try { SuiteInstaller.Extract(installed, delegate(string file) { return file == "app/suite.json" ? null : resource(file); }); }
            catch (InvalidDataException) { rejected = true; }
            Check(rejected && !Directory.Exists(installed), "Partial installation was activated");
            Check(Directory.GetDirectories(root).Length == 0, "Failed extraction left temporary files");
            SuiteInstaller.Extract(installed, resource);
            Check(ApplicationLayoutValidation.IsValidInstalledApplication(installed), "Bootstrap layout cannot be updated");
            Check(!ApplicationLayoutValidation.IsValidStagedApplication(installed), "Incomplete suite was accepted as update payload");
            Check(!Directory.Exists(Path.Combine(installed, "runtime")) && !Directory.Exists(Path.Combine(installed, "LumBridge")), "Installer contains applications");
            string marker = Path.Combine(installed, "updated-version");
            File.WriteAllText(marker, "preserved");
            SuiteInstaller.Extract(installed, resource);
            Check(File.ReadAllText(marker) == "preserved", "Reinstallation overwrote an updated launcher");
            File.Delete(Path.Combine(installed, "launcher", "HapticScapeLauncher.exe"));
            Check(!ApplicationLayoutValidation.IsValidInstalledApplication(installed), "Broken bootstrap was accepted");
            Console.WriteLine("Launcher-only extraction, repeat launch, failed-install cleanup and update layout validation passed.");
            return 0;
        }
        catch (Exception error) { Console.Error.WriteLine(error); return 1; }
        finally { Directory.Delete(root, true); }
    }
}
