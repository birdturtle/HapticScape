package com.ashy0019.hapticscape.desktop;

import com.ashy0019.hapticscape.HapticScapeRuntime;
import com.ashy0019.hapticscape.HapticScapeRuntimeDependencies;
import com.ashy0019.hapticscape.HapticScapeSettingsSource;
import com.ashy0019.hapticscape.SettingsBackedHapticScapeSettings;
import com.ashy0019.hapticscape.SkillCatalog;
import com.ashy0019.hapticscape.integration.desktop.AwtDesktopNotificationService;
import com.ashy0019.hapticscape.integration.desktop.DesktopAudioCaptureSources;
import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureMode;
import com.ashy0019.hapticscape.integration.desktop.DesktopDiscordDeepLinkInbox;
import com.ashy0019.hapticscape.integration.desktop.DesktopSecretProtectors;
import com.ashy0019.hapticscape.integration.desktop.DesktopSourceMessageService;
import com.ashy0019.hapticscape.integration.desktop.DesktopStoragePaths;
import com.ashy0019.hapticscape.integration.desktop.JavaSoundPlayer;
import com.ashy0019.hapticscape.integration.osrs.OldSchoolRuneScapeSkillCatalog;
import com.ashy0019.hapticscape.protocol.LocalhostTransportEndpoint;
import com.ashy0019.hapticscape.remote.DiscordDeepLinkInbox;
import com.ashy0019.hapticscape.remote.DiscordPairingBridge;
import com.ashy0019.hapticscape.remote.SettingsStore;
import com.ashy0019.hapticscape.remote.RemoteSessionListener;
import com.ashy0019.hapticscape.remote.RemoteSessionSnapshot;
import com.ashy0019.hapticscape.remote.SettingsLockCatalog;
import com.ashy0019.hapticscape.storage.FileSettingsStore;
import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.storage.HapticScapeStoragePaths;
import com.google.gson.Gson;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;

/** Owns the standalone desktop host lifecycle around the neutral HapticScape runtime. */
public final class HapticScapeDesktopApplication implements AutoCloseable
{
	private final DesktopLaunchOptions launchOptions;
	private final AtomicBoolean closed = new AtomicBoolean();
	private OkHttpClient httpClient;
	private AwtDesktopNotificationService desktopNotifications;
	private HapticScapeRuntime runtime;
	private HapticScapeDesktopWindow window;
	private DiscordDeepLinkInbox deepLinkInbox;
	private ProtectedExitAuditStore protectedExitAudit;
	private WindowsStartupService startupService;

	public HapticScapeDesktopApplication()
	{
		this(DesktopLaunchOptions.defaults());
	}

	public HapticScapeDesktopApplication(DesktopLaunchOptions launchOptions)
	{
		this.launchOptions = java.util.Objects.requireNonNull(launchOptions, "launchOptions");
	}

	public void start()
	{
		if (runtime != null)
		{
			return;
		}

		SkillCatalog skillCatalog = OldSchoolRuneScapeSkillCatalog.get();
		HapticScapeStoragePaths storagePaths = DesktopStoragePaths.hapticScapeStoragePaths(
			launchOptions.getProfile()
		);
		FileSettingsStore settingsStore = new FileSettingsStore(storagePaths.getSettingsPath());
		HapticScapeSettingsSource settings = new SettingsBackedHapticScapeSettings(
			settingsStore,
			skillCatalog
		);
		httpClient = new OkHttpClient();
		Gson gson = new Gson();
		desktopNotifications = new AwtDesktopNotificationService("HapticScape");
		DesktopSourceMessageService sourceMessages = new DesktopSourceMessageService();
		protectedExitAudit = new ProtectedExitAuditStore(
			storagePaths.getProtectedExitStatePath()
		);
		startupService = new WindowsStartupService();
		Runnable reconcileStartup = () -> startupService.apply(
			booleanSetting(settingsStore, HapticScapeSettingKeys.START_WITH_WINDOWS),
			booleanSetting(settingsStore, HapticScapeSettingKeys.START_MINIMIZED)
		);
		settingsStore.addChangeListener((key, value) ->
		{
			if (HapticScapeSettingKeys.START_WITH_WINDOWS.equals(key)
				|| HapticScapeSettingKeys.START_MINIMIZED.equals(key))
			{
				reconcileStartup.run();
			}
		});
		reconcileStartup.run();

		runtime = new HapticScapeRuntime(new HapticScapeRuntimeDependencies(
			httpClient,
			gson,
			settings,
			settingsStore,
			skillCatalog,
			storagePaths,
			new JavaSoundPlayer(),
			desktopNotifications,
			sourceMessages,
			DesktopAudioCaptureSources.factory(),
			DesktopAudioCaptureSources.endpointCatalog(),
			AudioCaptureEndpoint.fromPersisted(
				settings.musicCaptureEndpointId(),
				settings.musicCaptureEndpointName()
			),
			DesktopAudioCaptureSources.applicationCatalog(),
			AudioCaptureMode.fromConfigValue(settings.musicCaptureMode()),
			AudioCaptureApplication.fromPersisted(
				settings.musicCaptureApplicationId(),
				settings.musicCaptureApplicationName()
			),
			DesktopSecretProtectors.savedUnlockKeys(),
			DesktopSecretProtectors.discordCredentials(),
			launchOptions.getGameplayPort()
		));

		try
		{
			runtime.start();
			boolean protectedExitActive = runtime.getSettingsLockService()
				.isLocked(SettingsLockCatalog.PROTECTED_EXIT);
			protectedExitAudit.beginRun(
				protectedExitActive,
				protectedExitOwner(),
				protectedExitLockId()
			);
			runtime.getSettingsLockService().addListener(snapshot ->
				protectedExitAudit.setProtectionActive(
					snapshot.isLocked(SettingsLockCatalog.PROTECTED_EXIT),
					protectedExitOwner(),
					protectedExitLockId()
				)
			);
			wireProtectedExitAudit();
			createWindow(settings, skillCatalog, settingsStore, sourceMessages);
			boolean trayInstalled = desktopNotifications.installApplicationMenu(
				window::restoreFromTray,
				window::requestCloseFromTray,
				available ->
				{
					if (closed.get()) return;
					window.setTrayAvailable(available);
					if (!available) window.restoreFromTray();
				}
			);
			window.setTrayAvailable(trayInstalled);
			if (!launchOptions.isMinimized() || !trayInstalled)
			{
				showWindow();
			}
			wireDiscordDeepLinks();
		}
		catch (RuntimeException failure)
		{
			if (protectedExitAudit != null)
			{
				protectedExitAudit.markAuthorizedEnd();
			}
			close();
			throw failure;
		}
	}

	private void wireProtectedExitAudit()
	{
		runtime.getRemoteSessionManager().addListener(new RemoteSessionListener()
		{
			@Override
			public void onRemoteSessionChanged(RemoteSessionSnapshot snapshot)
			{
				tryReportPendingUnauthorizedEnd();
			}

			@Override
			public void onUnauthorizedEndAcknowledged(String eventId)
			{
				protectedExitAudit.clearPendingUnauthorizedEnd(eventId);
			}

		});
	}

	private String protectedExitOwner()
	{
		return runtime.getSettingsLockService()
			.getOwnerForTarget(SettingsLockCatalog.PROTECTED_EXIT)
			.orElse(null);
	}

	private String protectedExitLockId()
	{
		String owner = protectedExitOwner();
		return owner == null ? null : runtime.getSettingsLockService().getProfileForOwner(owner)
			.map(profile -> profile.getProposalId()).orElse(null);
	}

	private void tryReportPendingUnauthorizedEnd()
	{
		protectedExitAudit.getPendingUnauthorizedEnds().forEach(record ->
			runtime.getRemoteSessionManager().reportUnauthorizedEnd(
				record.getEventId(),
				record.getControllerId(),
				record.getOccurredAtMillis(),
				"Unauthorized end",
				record.getLockId()
			)
		);
	}

	private void createWindow(
		HapticScapeSettingsSource settings,
		SkillCatalog skillCatalog,
		SettingsStore settingsStore,
		DesktopSourceMessageService sourceMessages)
	{
		Runnable create = () ->
		{
			window = new HapticScapeDesktopWindow(
				runtime,
				settings,
				skillCatalog,
				settingsStore,
				sourceMessages,
				protectedExitAudit,
				launchOptions.getWindowTitle(),
				this::closeAndExit
			);
		};
		if (SwingUtilities.isEventDispatchThread())
		{
			create.run();
			return;
		}
		try
		{
			SwingUtilities.invokeAndWait(create);
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while opening HapticScape", interrupted);
		}
		catch (InvocationTargetException failure)
		{
			Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException)
			{
				throw (RuntimeException) cause;
			}
			throw new IllegalStateException("Unable to open HapticScape", cause);
		}
	}

	private static boolean booleanSetting(SettingsStore store, String key)
	{
		return Boolean.parseBoolean(store.get(key));
	}

	private void wireDiscordDeepLinks()
	{
		DiscordPairingBridge bridge = runtime.getDiscordPairingBridge();
		bridge.setJoinConsentHandler(window.createDiscordJoinConsentHandler());
		deepLinkInbox = DesktopDiscordDeepLinkInbox.forProfile(launchOptions.getProfile());
		deepLinkInbox.start();
		deepLinkInbox.setHandler(bridge::acceptDeepLink);
	}

	private void showWindow()
	{
		Runnable show = () -> window.show();
		if (SwingUtilities.isEventDispatchThread())
		{
			show.run();
			return;
		}
		try
		{
			SwingUtilities.invokeAndWait(show);
		}
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while showing HapticScape", interrupted);
		}
		catch (InvocationTargetException failure)
		{
			Throwable cause = failure.getCause();
			if (cause instanceof RuntimeException)
			{
				throw (RuntimeException) cause;
			}
			throw new IllegalStateException("Unable to show HapticScape", cause);
		}
	}

	private void closeAndExit()
	{
		try
		{
			close();
		}
		finally
		{
			System.exit(0);
		}
	}

	@Override
	public void close()
	{
		if (!closed.compareAndSet(false, true))
		{
			return;
		}
		if (deepLinkInbox != null)
		{
			deepLinkInbox.close();
			deepLinkInbox = null;
		}
		HapticScapeDesktopWindow currentWindow = window;
		window = null;
		if (currentWindow != null)
		{
			if (SwingUtilities.isEventDispatchThread())
			{
				currentWindow.close();
			}
			else
			{
				SwingUtilities.invokeLater(currentWindow::close);
			}
		}
		if (runtime != null)
		{
			runtime.close();
			runtime = null;
		}
		if (desktopNotifications != null)
		{
			desktopNotifications.close();
			desktopNotifications = null;
		}
		if (httpClient != null)
		{
			httpClient.dispatcher().executorService().shutdown();
			httpClient.connectionPool().evictAll();
			httpClient = null;
		}
	}
}
