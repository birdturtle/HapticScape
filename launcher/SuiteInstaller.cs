using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Threading;
using System.Windows.Forms;

// The public download contains only the launcher. Java and both apps are fetched
// by the native launcher, using the release's verified suite payload.
internal static class SuiteInstaller
{
    internal static readonly string[] Files = {
        "HapticScape.exe", "launcher/HapticScapeLauncher.exe",
        "launcher/MicrosoftEdgeWebview2Setup.exe", "app/release.json",
        "app/suite.json", "app/bootstrap.json", "licenses/LICENSE", "licenses/launcher-assets.md"
    };

    internal static void Extract(string destination, Func<string, Stream> resource)
    {
        string temporary = destination + ".install-" + Guid.NewGuid().ToString("N");
        try
        {
            foreach (string file in Files)
            {
                string path = Path.Combine(temporary, file.Replace('/', Path.DirectorySeparatorChar));
                Directory.CreateDirectory(Path.GetDirectoryName(path));
                using (Stream source = resource(file))
                {
                    if (source == null) throw new InvalidDataException("The launcher installer is incomplete.");
                    using (FileStream output = File.Create(path)) source.CopyTo(output);
                }
            }
            // Never overwrite a live launcher or an installation upgraded in place.
            if (!Directory.Exists(destination)) Directory.Move(temporary, destination);
        }
        finally { if (Directory.Exists(temporary)) Directory.Delete(temporary, true); }
    }

    [STAThread]
    private static int Main(string[] args)
    {
        try
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            string version;
            using (Stream stream = assembly.GetManifestResourceStream("version"))
            using (StreamReader reader = new StreamReader(stream)) version = reader.ReadToEnd().Trim();
            if (!System.Text.RegularExpressions.Regex.IsMatch(version, @"\A[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?\z"))
                throw new InvalidDataException("The installer version is invalid.");
            string home = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "HapticScape");
            string target = Path.Combine(home, "launchers", version, "HapticScape");
            Directory.CreateDirectory(Path.GetDirectoryName(target));
            using (Mutex mutex = new Mutex(false, @"Local\HapticScape-Launcher-Install"))
            {
                bool acquired;
                try { acquired = mutex.WaitOne(TimeSpan.FromSeconds(30)); }
                catch (AbandonedMutexException) { acquired = true; }
                if (!acquired) throw new IOException("Another launcher installation is in progress. Try again.");
                try
                {
                    if (!File.Exists(Path.Combine(target, "HapticScape.exe")))
                    {
                        if (Directory.Exists(target)) throw new IOException("The launcher installation needs repair. Remove " + target + " and run this installer again.");
                        Extract(target, delegate(string name) { return assembly.GetManifestResourceStream("payload/" + name); });
                    }
                    string entry = Path.Combine(home, "HapticScape.exe");
                    string current = assembly.Location;
                    if (!String.Equals(Path.GetFullPath(current), Path.GetFullPath(entry), StringComparison.OrdinalIgnoreCase))
                    {
                        string pending = entry + ".new";
                        File.Copy(current, pending, true);
                        if (File.Exists(entry)) File.Replace(pending, entry, null); else File.Move(pending, entry);
                    }
                    string menu = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
                    Type shellType = Type.GetTypeFromProgID("WScript.Shell", true);
                    object shell = Activator.CreateInstance(shellType);
                    object shortcut = null;
                    try
                    {
                        shortcut = shellType.InvokeMember("CreateShortcut", BindingFlags.InvokeMethod, null, shell, new object[] { Path.Combine(menu, "HapticScape Launcher.lnk") });
                        Type shortcutType = shortcut.GetType();
                        shortcutType.InvokeMember("TargetPath", BindingFlags.SetProperty, null, shortcut, new object[] { entry });
                        shortcutType.InvokeMember("WorkingDirectory", BindingFlags.SetProperty, null, shortcut, new object[] { home });
                        shortcutType.InvokeMember("IconLocation", BindingFlags.SetProperty, null, shortcut, new object[] { entry + ",0" });
                        shortcutType.InvokeMember("Save", BindingFlags.InvokeMethod, null, shortcut, null);
                    }
                    finally
                    {
                        if (shortcut != null) System.Runtime.InteropServices.Marshal.FinalReleaseComObject(shortcut);
                        System.Runtime.InteropServices.Marshal.FinalReleaseComObject(shell);
                    }
                }
                finally { mutex.ReleaseMutex(); }
            }
            ProcessStartInfo info = new ProcessStartInfo(Path.Combine(target, "HapticScape.exe"));
            info.WorkingDirectory = target;
            info.UseShellExecute = false;
            info.Arguments = String.Join(" ", Array.ConvertAll(args, Quote));
            using (Process process = Process.Start(info)) { process.WaitForExit(); return process.ExitCode; }
        }
        catch (Exception error)
        {
            MessageBox.Show("The launcher could not be installed.\n\n" + error.Message, "HapticScape", MessageBoxButtons.OK, MessageBoxIcon.Error);
            return 1;
        }
    }

    private static string Quote(string value)
    {
        System.Text.StringBuilder output = new System.Text.StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\') { slashes++; continue; }
            output.Append('\\', c == '"' ? slashes * 2 + 1 : slashes);
            output.Append(c); slashes = 0;
        }
        output.Append('\\', slashes * 2); return output.Append('"').ToString();
    }
}
