package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.HapticScapeSettingsSource;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRule;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRules;
import com.ashy0019.hapticscape.host.ExternalLinkOpener;
import com.ashy0019.hapticscape.host.GlobalUiHooks;
import com.ashy0019.hapticscape.host.TextClipboard;
import com.ashy0019.hapticscape.remote.RemoteActionAcknowledgement;
import com.ashy0019.hapticscape.remote.RemoteActivityEvent;
import com.ashy0019.hapticscape.remote.DiscordPairingBridge;
import com.ashy0019.hapticscape.remote.DiscordJoinRequest;
import com.ashy0019.hapticscape.remote.RemoteLockSnapshot;
import com.ashy0019.hapticscape.remote.RemoteLockState;
import com.ashy0019.hapticscape.remote.RemotePairingService;
import com.ashy0019.hapticscape.remote.RemotePermissions;
import com.ashy0019.hapticscape.remote.RemoteRole;
import com.ashy0019.hapticscape.remote.RemoteSessionListener;
import com.ashy0019.hapticscape.remote.RemoteSessionManager;
import com.ashy0019.hapticscape.remote.RemoteSessionSnapshot;
import com.ashy0019.hapticscape.remote.RemoteSessionState;
import com.ashy0019.hapticscape.remote.RemoteSettingsSnapshot;
import com.ashy0019.hapticscape.remote.SettingsLockCatalog;
import com.ashy0019.hapticscape.remote.SettingsLockProposal;
import com.ashy0019.hapticscape.remote.SettingsLockTarget;
import com.ashy0019.hapticscape.remote.SettingsStore;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

final class RemoteControlPanel extends JPanel implements RemoteSessionListener
{
	private final HapticScapeSettingsSource config;
	private final RemoteSessionManager sessionManager;
	private final TextClipboard clipboard;
	private final JButton emergencyButton = new JButton("EMERGENCY OFF");
	private final JButton resumeButton = new JButton("Resume");
	private final JButton reconnectButton = new JButton("Reconnect");
	private final JButton endButton = new JButton("End session");
	private final JButton editSubjectButton = new JButton("Edit subject settings");
	private final JButton openLiveForgeButton = new JButton("Open Live Forge");
	private final RemoteSessionHeaderPanel sessionHeader;
	private final JPanel controllerTools = new JPanel(new GridLayout(0, 1, 0, 4));
	private final JPanel controllerSide = new JPanel();
	private final RemoteControllerDashboardPanel controllerDashboard;
	private final RemotePermissionSummaryPanel permissionSummary =
		new RemotePermissionSummaryPanel();
	private final RemoteActivityFeedPanel activityFeed = new RemoteActivityFeedPanel();
	private final RemoteLockPreparationPanel lockPreparationPanel;
	private final RemotePairingPanel pairingPanel;
	private final SavedUnlockKeysPanel savedUnlockKeysPanel;
	private final RemotePermissionsPanel permissionsPanel;
	private final RemoteActionsPanel actionsPanel;
	private final SettingsLockDraft settingsLockDraft;
	private final Runnable settingsLockDraftListener;
	private int nextLayoutRow;
	private boolean wasLocal = true;
	private String loadedLockProfileId;
	private boolean namingDialogOpen;

	RemoteControlPanel(
		HapticScapeSettingsSource config,
		SettingsStore settingsStore,
		ExternalLinkOpener externalLinkOpener,
		TextClipboard clipboard,
		GlobalUiHooks globalUiHooks,
		RemoteSessionManager sessionManager,
		RemotePairingService pairingService,
		DiscordPairingBridge discordPairingBridge,
		SettingsLockDraft settingsLockDraft,
		Runnable editSubjectSettingsAction,
		Runnable openLiveForgeAction)
	{
		this.config = config;
		this.clipboard = clipboard;
		this.sessionManager = sessionManager;
		this.settingsLockDraft = settingsLockDraft;
		this.settingsLockDraftListener = this::handleSettingsLockDraftChanged;
		this.savedUnlockKeysPanel = new SavedUnlockKeysPanel(sessionManager, clipboard);
		this.permissionsPanel = new RemotePermissionsPanel(sessionManager);
		this.actionsPanel = new RemoteActionsPanel(sessionManager);
		this.sessionHeader = new RemoteSessionHeaderPanel(
			emergencyButton,
			resumeButton,
			reconnectButton,
			endButton
		);
		emergencyButton.setName("remoteEmergency");
		resumeButton.setName("remoteResume");
		reconnectButton.setName("remoteReconnect");
		endButton.setName("remoteEndSession");
		java.util.Objects.requireNonNull(editSubjectSettingsAction, "editSubjectSettingsAction");
		java.util.Objects.requireNonNull(openLiveForgeAction, "openLiveForgeAction");
		this.lockPreparationPanel = new RemoteLockPreparationPanel(
			editSubjectSettingsAction,
			this::armSettingsLock,
			sessionManager::cancelSettingsLock,
			() -> settingsLockDraft.toggle(SettingsLockCatalog.PROTECTED_EXIT),
			() -> settingsLockDraft.toggle(SettingsLockCatalog.STARTUP_BEHAVIOR)
		);
		this.pairingPanel = new RemotePairingPanel(
			config,
			settingsStore,
			externalLinkOpener,
			clipboard,
			sessionManager,
			pairingService,
			discordPairingBridge,
			this::showError
		);
		setLayout(new GridBagLayout());
		setBorder(BorderFactory.createEmptyBorder(0, 4, 8, 4));

		WrappedTextLabel privacy = new WrappedTextLabel(
			"Remote settings are end-to-end encrypted. Relay operators can see connection IPs."
		);
		privacy.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 2));
		privacy.setToolTipText(
			"Settings are encrypted before relay transport. If your partner operates the relay, they may be able to see connection metadata such as your IP address."
		);
		addSection(pairingPanel);

		addSection(sessionHeader);

		controllerTools.setBorder(PanelUi.createSectionBorder("Subject workspace"));
		editSubjectButton.setToolTipText(
			"Open the participant's synced feedback settings"
		);
		openLiveForgeButton.setToolTipText(
			"Open continuous haptic control for this session"
		);
		configureCompactButton(editSubjectButton);
		configureCompactButton(openLiveForgeButton);
		controllerTools.add(editSubjectButton);
		controllerTools.add(openLiveForgeButton);

		controllerSide.setName("remoteControllerSubjectWorkspace");
		controllerSide.setLayout(new BoxLayout(controllerSide, BoxLayout.Y_AXIS));
		PanelUi.addFlexibleVerticalComponent(controllerSide, activityFeed);
		PanelUi.addFlexibleVerticalComponent(controllerSide, permissionSummary);
		PanelUi.addPreferredHeightComponent(controllerSide, controllerTools);
		PanelUi.addFlexibleVerticalComponent(controllerSide, lockPreparationPanel);
		controllerDashboard = new RemoteControllerDashboardPanel(
			actionsPanel,
			controllerSide
		);
		addSection(controllerDashboard);
		addSection(permissionsPanel);

		addSection(savedUnlockKeysPanel);
		addSection(privacy);

		emergencyButton.addActionListener(event -> sessionManager.emergencyPause());
		resumeButton.addActionListener(event -> sessionManager.resumeParticipant());
		reconnectButton.addActionListener(event -> sessionManager.reconnect());
		endButton.addActionListener(event -> sessionManager.endSession());
		editSubjectButton.addActionListener(event -> editSubjectSettingsAction.run());
		openLiveForgeButton.addActionListener(event -> openLiveForgeAction.run());
		sessionManager.addListener(this);
		settingsLockDraft.addListener(settingsLockDraftListener);
		savedUnlockKeysPanel.refresh();
		applySnapshot(sessionManager.getSnapshot());
	}

	void close()
	{
		pairingPanel.close();
		settingsLockDraft.removeListener(settingsLockDraftListener);
		sessionManager.removeListener(this);
	}

	boolean confirmDiscordRemoteControl(DiscordJoinRequest request)
	{
		return pairingPanel.confirmDiscordRemoteControl(request);
	}

	@Override
	public void onRemoteSessionChanged(RemoteSessionSnapshot snapshot)
	{
		SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
	}

	@Override
	public void onRemoteLockChanged(RemoteLockSnapshot snapshot)
	{
		SwingUtilities.invokeLater(() -> applyLockSnapshot(snapshot));
	}

	@Override
	public void onRemotePermissionsChanged(RemotePermissions permissions)
	{
		SwingUtilities.invokeLater(() ->
		{
			permissionsPanel.apply(permissions);
			permissionSummary.apply(permissions);
			activityFeed.apply(sessionManager.getSnapshot(), permissions);
			refreshControllerTools(sessionManager.getSnapshot(), permissions);
			refreshLockPreparation(sessionManager.getLockSnapshot());
			actionsPanel.apply(
				sessionManager.getSnapshot(),
				sessionManager.getPeerPermissions(),
				sessionManager.getControllerSettingsSnapshot()
			);
		});
	}

	@Override
	public void onRemoteSettingsChanged(RemoteSettingsSnapshot settings)
	{
		SwingUtilities.invokeLater(() -> actionsPanel.apply(
			sessionManager.getSnapshot(),
			sessionManager.getPeerPermissions(),
			settings
		));
	}

	@Override
	public void onRemoteActionAcknowledged(RemoteActionAcknowledgement acknowledgement)
	{
		SwingUtilities.invokeLater(() -> actionsPanel.showAcknowledgement(acknowledgement));
	}

	@Override
	public void onRemoteActivity(RemoteActivityEvent event)
	{
		SwingUtilities.invokeLater(() -> activityFeed.addActivity(event));
	}

	@Override
	public void onRemoteLockProposal(SettingsLockProposal proposal)
	{
		SwingUtilities.invokeLater(() -> confirmSettingsLockProposal(proposal));
	}

	@Override
	public void onRemoteLockNamingRequired(String currentProfileName)
	{
		SwingUtilities.invokeLater(() -> promptForLockProfileName(currentProfileName));
	}

	private void armSettingsLock()
	{
		Collection<SettingsLockTarget> targets = settingsLockDraft.snapshot();
		if (targets.isEmpty())
		{
			showError(
				"Shift-click one or more settings in the Subject workspace first."
			);
			return;
		}
		if (!sessionManager.isSavedUnlockKeyVaultAvailable())
		{
			showError(sessionManager.getSavedUnlockKeyVaultMessage());
			return;
		}
		char[] unlockKey = sessionManager.generateSettingsLockKey();
		JTextField keyField = new JTextField(new String(unlockKey));
		keyField.setEditable(false);
		keyField.setHorizontalAlignment(JTextField.CENTER);
		WrappedTextLabel explanation = new WrappedTextLabel(
			"HapticScape will save this unlock key only if the participant accepts "
				+ "the lock. The saved copy is encrypted for your account."
		);
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		PanelUi.addPreferredHeightComponent(content, explanation);
		PanelUi.addPreferredHeightComponent(
			content,
			new JLabel(targets.size() + (targets.size() == 1
				? " setting will be locked:"
				: " settings will be locked:"))
		);
		PanelUi.addPreferredHeightComponent(content, createTargetList(targets));
		PanelUi.addPreferredHeightComponent(content, keyField);
		try
		{
			Object[] options = {"Copy key & request", "Cancel"};
			int choice = JOptionPane.showOptionDialog(
				this,
				content,
				"Generated settings unlock key",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE,
				null,
				options,
				options[0]
			);
			if (choice != JOptionPane.YES_OPTION)
			{
				return;
			}
			clipboard.copyText(keyField.getText());
			sessionManager.proposeSettingsLock(unlockKey, targets);
		}
		catch (RuntimeException e)
		{
			showError(e.getMessage());
		}
		finally
		{
			Arrays.fill(unlockKey, '\0');
			keyField.setText("");
		}
	}

	private void confirmSettingsLockProposal(SettingsLockProposal proposal)
	{
		if ((proposal.getTargets().contains(SettingsLockCatalog.PROTECTED_EXIT)
			|| proposal.getTargets().contains(SettingsLockCatalog.STARTUP_BEHAVIOR))
			&& !sessionManager.getVisiblePermissions().isProtectedExitAllowed())
		{
			sessionManager.declinePendingSettingsLock();
			showError("Protected startup/exit requests are not permitted on this client.");
			return;
		}
		if (!phraseTargetsExist(proposal.getTargets()))
		{
			sessionManager.declinePendingSettingsLock();
			showError(
				"The lock request referenced a phrase rule that no longer exists. "
					+ "Ask the controller to create a new request."
			);
			return;
		}
		WrappedTextLabel explanation = new WrappedTextLabel(
			"The controller requests a persistent lock on the settings listed below. "
				+ "Their final values will stay locked after this session ends. Only the "
				+ "controller's generated key can unlock this bundle normally."
		);
		WrappedTextLabel safety = new WrappedTextLabel(
			"Emergency Off, End Session, Intiface controls, remote permissions, "
				+ "Forge, Music, and developer recovery remain available. Protected "
				+ "exit still permits a flagged passwordless exit after 10 seconds."
		);
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		PanelUi.addPreferredHeightComponent(content, explanation);
		PanelUi.addPreferredHeightComponent(content, createTargetList(proposal.getTargets()));
		PanelUi.addPreferredHeightComponent(content, safety);
		int choice = JOptionPane.showConfirmDialog(
			this,
			content,
			"Accept post-session settings lock?",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);
		if (choice == JOptionPane.YES_OPTION)
		{
			sessionManager.acceptPendingSettingsLock();
		}
		else
		{
			sessionManager.declinePendingSettingsLock();
		}
	}

	private void promptForLockProfileName(String currentProfileName)
	{
		if (namingDialogOpen)
		{
			return;
		}
		namingDialogOpen = true;
		try
		{
			JTextField nameField = new JTextField(
				currentProfileName == null ? "" : currentProfileName,
				28
			);
			WrappedTextLabel explanation = new WrappedTextLabel(
				"The participant accepted this lock update. Give the persistent profile a name "
					+ "to finish. Their previous lock stays active until the named profile is saved."
			);
			JPanel content = new JPanel();
			content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
			PanelUi.addPreferredHeightComponent(content, explanation);
			PanelUi.addPreferredHeightComponent(content, new JLabel("Profile name"));
			PanelUi.addPreferredHeightComponent(content, nameField);
			while (true)
			{
				Object[] options = {"Save profile", "Not now"};
				int choice = JOptionPane.showOptionDialog(
					this,
					content,
					"Name persistent lock profile",
					JOptionPane.YES_NO_OPTION,
					JOptionPane.PLAIN_MESSAGE,
					null,
					options,
					options[0]
				);
				if (choice != JOptionPane.YES_OPTION)
				{
					return;
				}
				try
				{
					sessionManager.finalizePendingSettingsLock(nameField.getText());
					return;
				}
				catch (RuntimeException e)
				{
					showError(e.getMessage());
					nameField.requestFocusInWindow();
				}
			}
		}
		finally
		{
			namingDialogOpen = false;
		}
	}

	private void applySnapshot(RemoteSessionSnapshot snapshot)
	{
		RemoteSessionViewState view = RemoteSessionViewState.from(snapshot);
		if (view.isLocal() && !wasLocal)
		{
			loadedLockProfileId = null;
			settingsLockDraft.clear();
		}
		wasLocal = view.isLocal();

		pairingPanel.apply(snapshot);
		sessionHeader.apply(snapshot);
		sessionHeader.setVisible(view.showsSessionHeader());
		controllerDashboard.setVisible(view.showsControllerDashboard());
		permissionsPanel.setVisible(view.showsParticipantPermissions());
		permissionSummary.apply(sessionManager.getPeerPermissions());
		activityFeed.apply(snapshot, sessionManager.getPeerPermissions());
		actionsPanel.apply(
			snapshot,
			sessionManager.getPeerPermissions(),
			sessionManager.getControllerSettingsSnapshot()
		);
		refreshControllerTools(snapshot, sessionManager.getPeerPermissions());
		savedUnlockKeysPanel.setLocalMode(view.isLocal());
		emergencyButton.setEnabled(view.showsEmergency());
		boolean reconnectAvailable = sessionManager.canReconnect();
		resumeButton.setEnabled(view.showsResume() && !reconnectAvailable);
		reconnectButton.setEnabled(reconnectAvailable);
		emergencyButton.setVisible(view.showsEmergency());
		resumeButton.setVisible(view.showsResume());
		reconnectButton.setVisible(reconnectAvailable);
		endButton.setEnabled(view.showsEnd());
		endButton.setVisible(view.showsEnd());
		applyLockSnapshot(sessionManager.getLockSnapshot());
		refreshSectionMinimumHeights();
		revalidate();
		repaint();
	}

	private void refreshControllerTools(
		RemoteSessionSnapshot snapshot,
		RemotePermissions permissions)
	{
		boolean controller = snapshot.getRole() == RemoteRole.CONTROLLER
			&& (snapshot.getState() == RemoteSessionState.ACTIVE
				|| snapshot.getState() == RemoteSessionState.PEER_EMERGENCY_PAUSED);
		controllerTools.setVisible(controller);
		editSubjectButton.setEnabled(controller && permissions.isSettingsAllowed());
		openLiveForgeButton.setEnabled(
			controller
				&& snapshot.getState() == RemoteSessionState.ACTIVE
				&& permissions.isLiveHapticsAllowed()
				&& permissions.getMaximumIntensityPercent() > 0
		);
	}

	private void applyLockSnapshot(RemoteLockSnapshot snapshot)
	{
		if (snapshot.getState() == RemoteLockState.INACTIVE)
		{
			if (snapshot.hasProfile()
				&& !snapshot.getProfileId().equals(loadedLockProfileId))
			{
				loadedLockProfileId = snapshot.getProfileId();
				settingsLockDraft.replaceAll(snapshot.getTargets());
			}
			else if (!snapshot.hasProfile() && loadedLockProfileId != null)
			{
				loadedLockProfileId = null;
				settingsLockDraft.clear();
			}
		}
		else if (snapshot.getState() == RemoteLockState.ARMED)
		{
			if (snapshot.hasProfile())
			{
				loadedLockProfileId = snapshot.getProfileId();
			}
			if (settingsLockDraft.size() > 0)
			{
				settingsLockDraft.clear();
			}
		}
		savedUnlockKeysPanel.refresh();
		refreshLockPreparation(snapshot);
		refreshSectionMinimumHeights();
		lockPreparationPanel.revalidate();
		lockPreparationPanel.repaint();
	}

	private void handleSettingsLockDraftChanged()
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::handleSettingsLockDraftChanged);
			return;
		}
		refreshLockPreparation(sessionManager.getLockSnapshot());
	}

	private void refreshLockPreparation(RemoteLockSnapshot snapshot)
	{
		RemoteSessionSnapshot session = sessionManager.getSnapshot();
		boolean controllerActive = session.getRole() == RemoteRole.CONTROLLER
			&& (session.getState() == RemoteSessionState.ACTIVE
				|| session.getState() == RemoteSessionState.PEER_EMERGENCY_PAUSED);
		RemotePermissions permissions = sessionManager.getPeerPermissions();
		lockPreparationPanel.apply(
			snapshot,
			settingsLockDraft.size(),
			controllerActive,
			permissions.isSettingsAllowed(),
			settingsLockDraft.contains(SettingsLockCatalog.PROTECTED_EXIT),
			settingsLockDraft.contains(SettingsLockCatalog.STARTUP_BEHAVIOR),
			permissions.isProtectedExitAllowed(),
			sessionManager.isSavedUnlockKeyVaultAvailable(),
			sessionManager.getSavedUnlockKeyVaultMessage()
		);
	}

	private JScrollPane createTargetList(
		Collection<SettingsLockTarget> targets)
	{
		JTextArea list = new JTextArea(formatTargets(targets), 9, 28);
		list.setEditable(false);
		list.setLineWrap(false);
		list.setCaretPosition(0);
		JScrollPane scroll = new JScrollPane(list);
		scroll.setPreferredSize(new Dimension(300, 150));
		return scroll;
	}

	private String formatTargets(
		Collection<SettingsLockTarget> targets)
	{
		List<SettingsLockTarget> ordered = new ArrayList<>(targets);
		ordered.sort(Comparator.naturalOrder());
		StringBuilder text = new StringBuilder();
		String group = null;
		for (SettingsLockTarget target : ordered)
		{
			if (!target.getGroup().equals(group))
			{
				if (text.length() > 0)
				{
					text.append('\n');
				}
				group = target.getGroup();
				text.append(group).append(':').append('\n');
			}
			text.append("  • ").append(displayName(target)).append('\n');
		}
		return text.toString();
	}

	private String displayName(SettingsLockTarget target)
	{
		if (!SettingsLockCatalog.isPhraseRule(target))
		{
			return target.getDisplayName();
		}
		String id = SettingsLockCatalog.phraseRuleId(target);
		for (ClickerPhraseRule rule : visiblePhraseRules().getRules())
		{
			if (id.equals(rule.getId()))
			{
				return "Phrase rule: " + rule.toString();
			}
		}
		return target.getDisplayName() + " (no longer present)";
	}

	private boolean phraseTargetsExist(Collection<SettingsLockTarget> targets)
	{
		ClickerPhraseRules rules = visiblePhraseRules();
		for (SettingsLockTarget target : targets)
		{
			if (!SettingsLockCatalog.isPhraseRule(target))
			{
				continue;
			}
			String id = SettingsLockCatalog.phraseRuleId(target);
			boolean found = false;
			for (ClickerPhraseRule rule : rules.getRules())
			{
				if (id.equals(rule.getId()))
				{
					found = true;
					break;
				}
			}
			if (!found)
			{
				return false;
			}
		}
		return true;
	}

	private ClickerPhraseRules visiblePhraseRules()
	{
		RemoteSettingsSnapshot remote = sessionManager.getControllerSettingsSnapshot();
		if (sessionManager.getSnapshot().getRole() == RemoteRole.CONTROLLER && remote != null)
		{
			return remote.getClickerPhraseRules();
		}
		return ClickerPhraseRules.fromConfigValue(config.clickerPhraseRules());
	}

	private void showError(String message)
	{
		JOptionPane.showMessageDialog(
			this,
			message == null ? "Remote Control operation failed" : message,
			"Remote Control",
			JOptionPane.ERROR_MESSAGE
		);
	}

	private static void configureCompactButton(JButton button)
	{
		button.setMargin(new java.awt.Insets(2, 6, 2, 6));
		allowHorizontalShrink(button);
	}

	private static void allowHorizontalShrink(JComponent component)
	{
		Dimension preferred = component.getPreferredSize();
		component.setMinimumSize(new Dimension(0, preferred.height));
	}

	private void addSection(JComponent component)
	{
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = 0;
		constraints.gridy = nextLayoutRow++;
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		constraints.anchor = GridBagConstraints.NORTHWEST;
		constraints.insets = new java.awt.Insets(0, 0, 6, 0);
		allowHorizontalShrink(component);
		add(component, constraints);
	}

	private void refreshSectionMinimumHeights()
	{
		for (java.awt.Component component : getComponents())
		{
			if (component instanceof JComponent)
			{
				allowHorizontalShrink((JComponent) component);
			}
		}
	}
}
