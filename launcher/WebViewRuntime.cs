using System;
using System.Diagnostics;
using System.IO;
using Microsoft.Win32;

internal static class WebViewRuntime
{
    internal static void EnsureInstalled(string root)
    {
        if (IsInstalled()) return;
        string installer = Path.Combine(root, "launcher", "MicrosoftEdgeWebview2Setup.exe");
        if (!File.Exists(installer)) throw new FileNotFoundException("The browser runtime installer is missing.", installer);
        using (Process process = Process.Start(new ProcessStartInfo(installer, "/silent /install") { UseShellExecute = false }))
        {
            if (!process.WaitForExit(180000)) throw new TimeoutException("The browser runtime installation has not finished. Please try opening HapticScape again.");
            if (!IsInstalled()) throw new InvalidOperationException("The browser runtime could not be installed (exit code " + process.ExitCode + ").");
        }
    }
    private static bool IsInstalled()
    {
        const string key = @"Software\Microsoft\EdgeUpdate\Clients\{F3017226-FE2A-4295-8BDF-00C3A9A7E4C5}";
        foreach (RegistryHive hive in new[] { RegistryHive.CurrentUser, RegistryHive.LocalMachine })
        foreach (RegistryView view in new[] { RegistryView.Registry32, RegistryView.Registry64 })
        {
            using (RegistryKey registry = RegistryKey.OpenBaseKey(hive, view))
            using (RegistryKey runtime = registry.OpenSubKey(key))
            {
                Version version;
                if (runtime != null && Version.TryParse(runtime.GetValue("pv") as string, out version) && version > new Version(0, 0, 0, 0)) return true;
            }
        }
        return false;
    }
}
