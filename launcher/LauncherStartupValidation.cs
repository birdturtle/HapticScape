using System;
using System.Diagnostics;
using System.IO;
using System.Threading;

internal static class LauncherStartupValidation
{
    internal static bool IsReady(string path, string token)
    {
        try { return File.Exists(path) && File.ReadAllText(path) == token; }
        catch (IOException) { return false; }
    }
    internal static void WaitForReady(Process process, string path, string token, int timeoutMillis)
    {
        Stopwatch timer = Stopwatch.StartNew();
        while (timer.ElapsedMilliseconds < timeoutMillis)
        {
            if (process.HasExited) throw new InvalidOperationException("The updated launcher exited before confirming startup.");
            if (IsReady(path, token)) return;
            Thread.Sleep(100);
        }
        throw new TimeoutException("The updated launcher did not confirm startup. The previous installation will be restored when possible.");
    }
}
