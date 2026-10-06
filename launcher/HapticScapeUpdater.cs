using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

internal static class HapticScapeUpdater
{
	private const int WaitForLauncherMillis = 30000;
	private const int MoveFileDelayUntilReboot = 0x4;

	[STAThread]
	private static int Main(string[] args)
	{
		Application.EnableVisualStyles();
		Application.SetCompatibleTextRenderingDefault(false);

        int result = Run(args, WebViewRuntime.EnsureInstalled, delegate(string message) {
            MessageBox.Show(message, "HapticScape update failed", MessageBoxButtons.OK, MessageBoxIcon.Error);
        });
        ScheduleSelfDeletion();
        return result;
    }

    // Production transaction, exercised with controlled launcher fixtures in CI.
    internal static int Run(string[] args, Action<string> ensureRuntime, Action<string> reportError)
    {

		string installDirectory = null;
		string stagedDirectory = null;
		string temporaryRoot = null;
		string backupDirectory = null;
		bool backupCreated = false;
		bool newVersionInstalled = false;
		Process newLauncher = null;

		try
		{
			Dictionary<string, string> options = ParseOptions(args);
			int parentPid = ParseParentPid(Required(options, "--parent-pid"));
			installDirectory = NormalizeDirectory(Required(options, "--install-dir"));
			stagedDirectory = NormalizeDirectory(Required(options, "--staged-dir"));
			temporaryRoot = NormalizeDirectory(Required(options, "--temporary-root"));

			ValidateTarget(installDirectory, stagedDirectory, temporaryRoot);
			WaitForParent(parentPid);

			string parentDirectory = Directory.GetParent(installDirectory).FullName;
			backupDirectory = Path.Combine(
				parentDirectory,
				"HapticScape-backup-" + Guid.NewGuid().ToString("N"));

			// A compatibility bootstrap may still be finishing its WaitForExit
            // after the native launcher exits. Give its executable lock time to clear.
            MoveInstallation(installDirectory, backupDirectory);
			backupCreated = true;
			Directory.Move(stagedDirectory, installDirectory);
			newVersionInstalled = true;

			string launcherPath = Path.Combine(installDirectory, "HapticScape.exe");
			bool unified = File.Exists(Path.Combine(installDirectory, "app", "suite.json"));
			if (unified) ensureRuntime(installDirectory);
			string readyPath = Path.Combine(temporaryRoot, "launcher-ready");
			string readyToken = Guid.NewGuid().ToString("N");
			ProcessStartInfo startInfo = new ProcessStartInfo();
			startInfo.FileName = unified
				? Path.Combine(installDirectory, "launcher", "HapticScapeLauncher.exe") : launcherPath;
			if (unified) startInfo.Arguments = "--update-ready-file \"" + readyPath + "\" --update-ready-token " + readyToken;
			startInfo.WorkingDirectory = installDirectory;
			startInfo.UseShellExecute = false;
			newLauncher = Process.Start(startInfo);
			if (unified) LauncherStartupValidation.WaitForReady(newLauncher, readyPath, readyToken, 90000);

			TryDeleteDirectory(backupDirectory);
			TryDeleteDirectory(temporaryRoot);
			return 0;
		}
		catch (Exception exception)
		{
			try
			{
				if (newLauncher != null && !newLauncher.HasExited)
				{
					// Request ordinary launcher closure. Never kill Java or protected clients.
					newLauncher.CloseMainWindow();
					newLauncher.WaitForExit(10000);
				}
			}
			catch (Exception) { /* Preserve the original failure and attempt rollback. */ }
			TryRollback(
				installDirectory,
				stagedDirectory,
				backupDirectory,
				backupCreated,
				newVersionInstalled);
			TryLaunchExisting(installDirectory);
            reportError("HapticScape could not finish installing the update. The previous version was restored when possible.\n\n" + exception.Message);
			return 1;
		}
	}

    private static void MoveInstallation(string source, string target)
    {
        Stopwatch timer = Stopwatch.StartNew();
        while (true)
        {
            try { Directory.Move(source, target); return; }
            catch (IOException) { if (timer.ElapsedMilliseconds >= 5000) throw; Thread.Sleep(100); }
        }
    }

	private static Dictionary<string, string> ParseOptions(string[] args)
	{
		Dictionary<string, string> options =
			new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
		if (args.Length % 2 != 0)
		{
			throw new ArgumentException("The updater arguments are malformed.");
		}
		for (int index = 0; index < args.Length; index += 2)
		{
			options[args[index]] = args[index + 1];
		}
		return options;
	}

	private static string Required(Dictionary<string, string> options, string name)
	{
		string value;
		if (!options.TryGetValue(name, out value) || string.IsNullOrWhiteSpace(value))
		{
			throw new ArgumentException("The updater is missing " + name + ".");
		}
		return value;
	}

	private static int ParseParentPid(string value)
	{
		int pid;
		if (!int.TryParse(value, out pid) || pid <= 0)
		{
			throw new ArgumentException("The launcher process ID is invalid.");
		}
		return pid;
	}

	private static string NormalizeDirectory(string directory)
	{
		return Path.GetFullPath(directory)
			.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar);
	}

	private static void ValidateTarget(
		string installDirectory,
		string stagedDirectory,
		string temporaryRoot)
	{
		if (string.Equals(
			installDirectory,
			Path.GetPathRoot(installDirectory).TrimEnd(Path.DirectorySeparatorChar),
			StringComparison.OrdinalIgnoreCase))
		{
			throw new InvalidOperationException("The updater refuses to replace a drive root.");
		}
		if (string.Equals(installDirectory, stagedDirectory, StringComparison.OrdinalIgnoreCase))
		{
			throw new InvalidOperationException("The installed and staged directories are identical.");
		}
		DirectoryInfo installParent = Directory.GetParent(installDirectory);
		DirectoryInfo temporaryParent = Directory.GetParent(temporaryRoot);
		string temporaryName = Path.GetFileName(temporaryRoot);
		string temporaryPrefix = temporaryRoot + Path.DirectorySeparatorChar;
		if (installParent == null
			|| temporaryParent == null
			|| !string.Equals(
				installParent.FullName,
				temporaryParent.FullName,
				StringComparison.OrdinalIgnoreCase)
			|| !temporaryName.StartsWith("HapticScape-update-", StringComparison.Ordinal)
			|| !stagedDirectory.StartsWith(temporaryPrefix, StringComparison.OrdinalIgnoreCase))
		{
			throw new InvalidOperationException("The updater staging directory failed safety validation.");
		}
		if (!Directory.Exists(installDirectory) || !Directory.Exists(stagedDirectory))
		{
			throw new DirectoryNotFoundException("The installed or staged HapticScape directory is missing.");
		}
		ApplicationLayoutValidation.ValidateInstalledApplication(installDirectory);
		ApplicationLayoutValidation.ValidateStagedApplication(stagedDirectory);
	}

	private static void WaitForParent(int parentPid)
	{
		try
		{
			using (Process parent = Process.GetProcessById(parentPid))
			{
				if (!parent.WaitForExit(WaitForLauncherMillis))
				{
					throw new TimeoutException("The HapticScape launcher did not exit in time.");
				}
			}
		}
		catch (ArgumentException)
		{
			// The launcher already exited.
		}
	}

	private static void TryRollback(
		string installDirectory,
		string stagedDirectory,
		string backupDirectory,
		bool backupCreated,
		bool newVersionInstalled)
	{
		try
		{
			if (!backupCreated || string.IsNullOrEmpty(backupDirectory)
				|| !Directory.Exists(backupDirectory))
			{
				return;
			}
			if (newVersionInstalled && !string.IsNullOrEmpty(installDirectory)
				&& Directory.Exists(installDirectory))
			{
				string failedDirectory = stagedDirectory + "-failed";
				if (!Directory.Exists(failedDirectory))
				{
					Directory.Move(installDirectory, failedDirectory);
				}
			}
			if (!Directory.Exists(installDirectory))
			{
				Directory.Move(backupDirectory, installDirectory);
			}
		}
		catch (Exception)
		{
			// The original exception remains the useful error to report.
		}
	}

	private static void TryLaunchExisting(string installDirectory)
	{
		try
		{
			if (string.IsNullOrEmpty(installDirectory))
			{
				return;
			}
			string launcher = Path.Combine(installDirectory, "HapticScape.exe");
			if (File.Exists(launcher))
			{
				Process.Start(launcher);
			}
		}
		catch (Exception)
		{
			// The error dialog still tells the user where installation failed.
		}
	}

	private static void TryDeleteDirectory(string directory)
	{
		try
		{
			if (!string.IsNullOrEmpty(directory) && Directory.Exists(directory))
			{
				Directory.Delete(directory, true);
			}
		}
		catch (Exception)
		{
			// Backup and staging cleanup can be retried manually if Windows has a lock.
		}
	}

	private static void ScheduleSelfDeletion()
	{
		try
		{
			MoveFileEx(Application.ExecutablePath, null, MoveFileDelayUntilReboot);
		}
		catch (Exception)
		{
			// A tiny temporary helper can be left behind without affecting the install.
		}
	}

	[DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
	private static extern bool MoveFileEx(string existingFileName, string newFileName, int flags);
}
