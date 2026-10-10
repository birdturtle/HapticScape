using System;
using System.IO;
using System.Threading;
internal static class MigrationLauncherFixture
{
    private static void Main(string[] args)
    {
        string root = AppDomain.CurrentDomain.BaseDirectory;
        if (File.Exists(Path.Combine(root, "fail-startup"))) return;
        for (int i = 0; i + 1 < args.Length; i++)
            if (args[i] == "--update-ready-file") {
                string token = null;
                for (int j = 0; j + 1 < args.Length; j++) if (args[j] == "--update-ready-token") token = args[j + 1];
                File.WriteAllText(args[i + 1], token);
                Thread.Sleep(1500);
                return;
            }
    }
}
