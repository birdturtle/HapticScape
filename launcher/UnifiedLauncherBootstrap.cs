using System;
using Microsoft.Win32;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Windows.Forms;

// Keep the historical shortcut/protocol target while the launcher lives beside
// the existing app/runtime layout. Special old entry points retain their behavior.
internal static class UnifiedLauncherBootstrap
{
    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            string root = AppDomain.CurrentDomain.BaseDirectory;
            bool legacy = false;
            foreach (string arg in args)
            {
                if (arg == "--update-settings" || arg == "--profile" || arg.StartsWith("--profile=", StringComparison.Ordinal)
                    || arg == "--gameplay-port" || arg.StartsWith("--gameplay-port=", StringComparison.Ordinal)
                    || arg == "--minimized") legacy = true;
            }
            if (!legacy) { RegisterProtocol(root); WebViewRuntime.EnsureInstalled(root); }
            string target = legacy ? Path.Combine(root, "HapticScapeLegacy.exe") : Path.Combine(root, "launcher", "HapticScapeLauncher.exe");
            if (!File.Exists(target)) throw new IOException("Open the launcher first to install HapticScape.");
            ProcessStartInfo info = new ProcessStartInfo(target);
            info.WorkingDirectory = root;
            info.UseShellExecute = false;
            StringBuilder arguments = new StringBuilder();
            foreach (string arg in args) { if (arguments.Length > 0) arguments.Append(' '); arguments.Append(Quote(arg)); }
            info.Arguments = arguments.ToString();
            using (Process process = Process.Start(info)) { process.WaitForExit(); return process.ExitCode; }
        }
        catch (Exception error)
        {
            MessageBox.Show("The HapticScape launcher could not start.\n\n" + error.Message, "HapticScape", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }
    private static void RegisterProtocol(string root)
    {
        using (RegistryKey key = Registry.CurrentUser.CreateSubKey(@"Software\Classes\hapticscape"))
        {
            key.SetValue("", "URL:HapticScape Protocol");
            key.SetValue("URL Protocol", "");
            using (RegistryKey icon = key.CreateSubKey("DefaultIcon"))
                icon.SetValue("", Quote(Path.Combine(root, "HapticScape.exe")) + ",0");
            using (RegistryKey command = key.CreateSubKey(@"shell\open\command"))
                command.SetValue("", Quote(Path.Combine(root, "HapticScape.exe")) + " \"%1\"");
        }
    }
    private static string Quote(string value)
    {
        StringBuilder quoted = new StringBuilder("\""); int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') { quoted.Append('\\', slashes * 2 + 1); quoted.Append(c); }
            else { quoted.Append('\\', slashes); quoted.Append(c); }
            slashes = 0;
        }
        quoted.Append('\\', slashes * 2); quoted.Append('"'); return quoted.ToString();
    }
}
