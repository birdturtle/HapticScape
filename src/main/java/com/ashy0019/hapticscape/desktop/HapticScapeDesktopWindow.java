package com.ashy0019.hapticscape.desktop;

import com.ashy0019.hapticscape.AlertCategory;
import com.ashy0019.hapticscape.HapticScapeRuntime;
import com.ashy0019.hapticscape.HapticScapeSettingsSource;
import com.ashy0019.hapticscape.NotificationFeedbackSettings;
import com.ashy0019.hapticscape.SkillCatalog;
import com.ashy0019.hapticscape.SkillIds;
import com.ashy0019.hapticscape.XpFeedbackSettings;
import com.ashy0019.hapticscape.device.HapticEventType;
import com.ashy0019.hapticscape.integration.desktop.AwtExternalLinkOpener;
import com.ashy0019.hapticscape.integration.desktop.AwtGlobalUiHooks;
import com.ashy0019.hapticscape.integration.desktop.AwtTextClipboard;
import com.ashy0019.hapticscape.integration.desktop.DesktopSourceMessageService;
import com.ashy0019.hapticscape.integration.desktop.StandaloneLevel99GlassPane;
import com.ashy0019.hapticscape.remote.DiscordJoinConsentHandler;
import com.ashy0019.hapticscape.remote.DiscordJoinRequest;
import com.ashy0019.hapticscape.remote.DiscordPairingBridge;
import com.ashy0019.hapticscape.remote.SettingsStore;
import com.ashy0019.hapticscape.remote.SettingsLockCatalog;
import com.ashy0019.hapticscape.remote.SettingsLockService;
import com.ashy0019.hapticscape.ui.HapticScapePanel;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.Image;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/** Hosts the reusable HapticScape Swing panel in a standalone desktop window. */
public final class HapticScapeDesktopWindow implements AutoCloseable
{
	static final int DEFAULT_WINDOW_WIDTH = 1024;
	static final int DEFAULT_WINDOW_HEIGHT = 900;
	static final int MINIMUM_WINDOW_WIDTH = 480;
	static final int MINIMUM_WINDOW_HEIGHT = 640;
	static final int AUTHORIZED_UNLOCK_FLUSH_MILLIS = 250;
	static final int UNAUTHORIZED_EXIT_FLUSH_MILLIS = 750;

	private final HapticScapeRuntime runtime;
	private final JFrame frame = new JFrame("HapticScape");
	private final JScrollPane pageScrollPane = new JScrollPane();
	private final HapticScapePanel panel;
	private final DesktopSourceMessageService sourceMessages;
	private final Runnable closeAction;
	private final SettingsLockService settingsLockService;
	private final ProtectedExitAuditStore protectedExitAudit;
	private ProtectedExitDialog protectedExitDialog;
	private boolean exitScheduled;
	private boolean trayAvailable;

	public HapticScapeDesktopWindow(
		HapticScapeRuntime runtime,
		HapticScapeSettingsSource settings,
		SkillCatalog skillCatalog,
		SettingsStore settingsStore,
		DesktopSourceMessageService sourceMessages,
		ProtectedExitAuditStore protectedExitAudit,
		String windowTitle,
		Runnable closeAction)
	{
		this.runtime = Objects.requireNonNull(runtime, "runtime");
		this.sourceMessages = Objects.requireNonNull(sourceMessages, "sourceMessages");
		this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
		this.settingsLockService = runtime.getSettingsLockService();
		this.protectedExitAudit = Objects.requireNonNull(
			protectedExitAudit,
			"protectedExitAudit"
		);
		frame.setTitle(Objects.requireNonNull(windowTitle, "windowTitle"));
		Objects.requireNonNull(settings, "settings");
		Objects.requireNonNull(skillCatalog, "skillCatalog");
		Objects.requireNonNull(settingsStore, "settingsStore");

		pageScrollPane.setBorder(BorderFactory.createEmptyBorder());
		pageScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		pageScrollPane.getVerticalScrollBar().setUnitIncrement(16);
		panel = new HapticScapePanel(
			settings,
			skillCatalog,
			settingsStore,
			new AwtExternalLinkOpener(),
			new AwtTextClipboard(),
			new AwtGlobalUiHooks(),
			pageScrollPane,
			this::connectToIntiface,
			runtime.getIntifaceService()::disconnect,
			this::sendTestPattern,
			this::sendTestLevelUpPattern,
			this::previewLevel99Ceremony,
			this::sendTestSkillProfile,
			this::sendTestGenericNotificationPattern,
			this::sendTestAlert,
			runtime::previewCustomPattern,
			runtime.getMusicSyncService()::updateSettings,
			runtime::listAudioCaptureEndpoints,
			runtime.getMusicSyncService()::updateCaptureEndpoint,
			runtime.getClickerService()::updateSettings,
			runtime::playClick,
			runtime.getUpdatePreferencesStore(),
			runtime.getUpdateCheckService(),
			runtime.getRemoteSessionManager(),
			runtime.getRemotePairingService(),
			runtime.getDiscordPairingBridge(),
			runtime.getSettingsLockService(),
			runtime::dispatchRogueFeedback,
			runtime::playRogueUnlockStingAsync,
			 runtime::stopAll
		);
		panel.configureAudioApplicationCapture(
			runtime::listAudioCaptureApplications,
			runtime.getMusicSyncService()::updateCaptureMode,
			runtime.getMusicSyncService()::updateCaptureApplication
		);

		frame.setContentPane(panel);
		frame.setGlassPane(new StandaloneLevel99GlassPane(runtime.getLevel99CelebrationController()));
		frame.getGlassPane().setVisible(true);
		frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		frame.setMinimumSize(new Dimension(MINIMUM_WINDOW_WIDTH, MINIMUM_WINDOW_HEIGHT));
		frame.setSize(DEFAULT_WINDOW_WIDTH, DEFAULT_WINDOW_HEIGHT);
		frame.setLocationByPlatform(true);
		frame.addWindowListener(new WindowAdapter()
		{
			@Override
			public void windowClosing(WindowEvent event)
			{
				if (trayAvailable)
				{
					frame.setVisible(false);
				}
				else
				{
					requestClose();
				}
			}
		});
		loadWindowIcon();

		runtime.getIntifaceService().setConnectionListener(panel::updateConnection);
		runtime.getMusicSyncService().setListener(panel::updateMusicSync);
		sourceMessages.setListener(this::showSourceMessage);
	}

	private void requestClose()
	{
		if (exitScheduled)
		{
			return;
		}
		if (!settingsLockService.isLocked(SettingsLockCatalog.PROTECTED_EXIT))
		{
			finishAuthorizedExit();
			return;
		}
		if (protectedExitDialog != null && protectedExitDialog.isDisplayable())
		{
			protectedExitDialog.toFront();
			return;
		}
		protectedExitDialog = new ProtectedExitDialog(
			frame,
			settingsLockService,
			this::authorizedExit,
			this::unauthorizedExit,
			this::emergencyOff
		);
		protectedExitDialog.setVisible(true);
	}

	private void authorizedExit()
	{
		exitScheduled = true;
		try
		{
			protectedExitAudit.markAuthorizedEnd();
		}
		finally
		{
			// The local lock is already durably removed. Keep the runtime alive
			// briefly so the controller can receive LOCK_CANCELLED as well.
			Timer flushTimer = new Timer(
				AUTHORIZED_UNLOCK_FLUSH_MILLIS,
				event -> closeAction.run()
			);
			flushTimer.setRepeats(false);
			flushTimer.start();
		}
	}

	private void finishAuthorizedExit()
	{
		exitScheduled = true;
		try
		{
			protectedExitAudit.markAuthorizedEnd();
		}
		finally
		{
			closeAction.run();
		}
	}

	private void unauthorizedExit()
	{
		exitScheduled = true;
		ProtectedExitAuditStore.UnauthorizedEndRecord record;
		try
		{
			record = protectedExitAudit.markUnauthorizedEnd(
				runtime.getRemoteSessionManager().getPeerClientId().orElse(null)
			);
			runtime.stopAll();
		}
		catch (RuntimeException failure)
		{
			closeAction.run();
			return;
		}
		// WebSocket.send confirms that the message was queued, not that OkHttp put
		// it on the wire. Keep the runtime alive briefly so close() cannot tear the
		// transport down before the controller receives the flag. The durable flag
		// remains as a fallback if delivery still fails.
		boolean queued = false;
		try
		{
			queued = record.getControllerId() != null
				&& runtime.getRemoteSessionManager().reportUnauthorizedEnd(
					record.getEventId(),
					record.getControllerId(),
					record.getOccurredAtMillis(),
					"Unauthorized end",
					record.getLockId()
				);
		}
		catch (RuntimeException ignored)
		{
			// The durable event remains pending for the next matching session.
		}
		if (!queued)
		{
			closeAction.run();
			return;
		}
		Timer flushTimer = new Timer(
			UNAUTHORIZED_EXIT_FLUSH_MILLIS,
			event -> closeAction.run()
		);
		flushTimer.setRepeats(false);
		flushTimer.start();
	}

	private void emergencyOff()
	{
		runtime.stopAll();
		runtime.getRemoteSessionManager().emergencyPause();
	}

	public void show()
	{
		requireEventDispatchThread();
		frame.setVisible(true);
	}

	void setTrayAvailable(boolean available)
	{
		trayAvailable = available;
	}

	void hideToTray()
	{
		if (trayAvailable)
		{
			frame.setVisible(false);
		}
	}

	void restoreFromTray()
	{
		frame.setVisible(true);
		frame.setState(JFrame.NORMAL);
		frame.toFront();
		frame.requestFocus();
	}

	void requestCloseFromTray()
	{
		restoreFromTray();
		requestClose();
	}

	public DiscordJoinConsentHandler createDiscordJoinConsentHandler()
	{
		return new DiscordJoinConsentHandler()
		{
			@Override
			public void onDeepLinkOpened()
			{
				SwingUtilities.invokeLater(HapticScapeDesktopWindow.this::focusRemotePlay);
			}

			@Override
			public CompletableFuture<Boolean> requestConsent(DiscordJoinRequest request)
			{
				CompletableFuture<Boolean> result = new CompletableFuture<>();
				SwingUtilities.invokeLater(() ->
				{
					try
					{
						focusRemotePlay();
						result.complete(panel.confirmDiscordRemoteControl(request));
					}
					catch (RuntimeException exception)
					{
						result.completeExceptionally(exception);
					}
				});
				return result;
			}

			@Override
			public void showError(String message)
			{
				SwingUtilities.invokeLater(() ->
				{
					focusRemotePlay();
					panel.showDiscordPairingError(message);
				});
			}
		};
	}

	private void focusRemotePlay()
	{
		frame.setVisible(true);
		frame.setState(JFrame.NORMAL);
		frame.toFront();
		frame.requestFocus();
		panel.showDiscordRemoteView();
	}

	private void connectToIntiface()
	{
		try
		{
			runtime.connectToIntiface();
		}
		catch (URISyntaxException exception)
		{
			panel.showInputError("Invalid Intiface server URI");
		}
	}

	private void sendTestPattern()
	{
		runtime.sendConfiguredPattern(
			HapticEventType.MANUAL_PREVIEW,
			panel.getPatternPreset(),
			"TEST_XP"
		);
	}

	private void sendTestLevelUpPattern()
	{
		runtime.sendConfiguredPattern(
			HapticEventType.MANUAL_PREVIEW,
			panel.getLevelUpPatternPreset(),
			"TEST_LEVEL_UP"
		);
	}

	private void previewLevel99Ceremony()
	{
		String skillId = panel.getSelectedProfileSkillId();
		runtime.startLevel99Ceremony(skillId == null ? "attack" : skillId, false);
	}

	private void sendTestSkillProfile()
	{
		String skillId = panel.getSelectedProfileSkillId();
		if (skillId == null)
		{
			return;
		}
		XpFeedbackSettings settings = panel.getXpFeedbackSettings(skillId);
		runtime.sendPattern(
			HapticEventType.MANUAL_PREVIEW,
			settings.getPatternSelection(),
			"TEST_SKILL_" + SkillIds.toConfigToken(skillId),
			settings.getIntensityPercent(),
			settings.getDurationMillis()
		);
	}

	private void sendTestGenericNotificationPattern()
	{
		NotificationFeedbackSettings settings = panel.getNotificationFeedbackSettings();
		runtime.playClick(panel.getGenericNotificationClickSequence());
		runtime.sendPattern(
			HapticEventType.MANUAL_PREVIEW,
			settings.getPatternSelection(),
			"TEST_ALERT_GENERIC_NOTIFICATION",
			settings.getIntensityPercent(),
			settings.getDurationMillis()
		);
	}

	private void sendTestAlert(AlertCategory category)
	{
		runtime.playClick(panel.getAlertClickSequence(category));
		panel.getAlertProfiles()
			.resolve(category, panel.getNotificationFeedbackSettings())
			.ifPresent(playback -> runtime.sendPattern(
				HapticEventType.MANUAL_PREVIEW,
				playback.getPatternSelection(),
				"TEST_ALERT_" + category.name(),
				playback.getIntensityPercent(),
				playback.getDurationMillis()
			));
	}

	private void showSourceMessage(DesktopSourceMessageService.Message message)
	{
		panel.updateSourceStatus(message.getText(), message.getRgb());
	}

	private void loadWindowIcon()
	{
		java.net.URL resource = HapticScapeDesktopWindow.class.getResource("/hapticscape.png");
		if (resource == null)
		{
			return;
		}
		try
		{
			Image image = ImageIO.read(resource);
			if (image != null)
			{
				frame.setIconImage(image);
			}
		}
		catch (IOException ignored)
		{
			// An application icon is cosmetic only.
		}
	}

	private static void requireEventDispatchThread()
	{
		if (!EventQueue.isDispatchThread())
		{
			throw new IllegalStateException("Desktop window must be shown on the Swing event thread");
		}
	}

	@Override
	public void close()
	{
		if (protectedExitDialog != null)
		{
			protectedExitDialog.dispose();
			protectedExitDialog = null;
		}
		sourceMessages.setListener(null);
		panel.close();
		frame.dispose();
	}
}
