package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.HapticScapeSettingsSource;
import com.ashy0019.hapticscape.host.ExternalLinkOpener;
import com.ashy0019.hapticscape.host.TextClipboard;
import com.ashy0019.hapticscape.remote.DiscordLinkListener;
import com.ashy0019.hapticscape.remote.DiscordLinkSnapshot;
import com.ashy0019.hapticscape.remote.DiscordLinkState;
import com.ashy0019.hapticscape.remote.DiscordPairingBridge;
import com.ashy0019.hapticscape.remote.DiscordJoinRequest;
import com.ashy0019.hapticscape.remote.RemoteInvitation;
import com.ashy0019.hapticscape.remote.RemotePairingCode;
import com.ashy0019.hapticscape.remote.RemotePairingService;
import com.ashy0019.hapticscape.remote.RemoteRole;
import com.ashy0019.hapticscape.remote.RemoteSessionManager;
import com.ashy0019.hapticscape.remote.RemoteSessionSnapshot;
import com.ashy0019.hapticscape.remote.RemoteSessionState;
import com.ashy0019.hapticscape.remote.SettingsStore;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;

/** Owns invitation abstraction, temporary pairing codes, and direct-code fallback UI. */
final class RemotePairingPanel extends JPanel
{
	private final HapticScapeSettingsSource config;
	private final SettingsStore settingsStore;
	private final ExternalLinkOpener externalLinkOpener;
	private final TextClipboard clipboard;
	private final RemoteSessionManager sessionManager;
	private final RemotePairingService pairingService;
	private final DiscordPairingBridge discordPairingBridge;
	private final DiscordLinkListener discordLinkListener;
	private final Consumer<String> errorSink;
	private final JTextField relayUrlField = new JTextField();
	private final JPanel relaySettingsPanel = new JPanel(new BorderLayout(8, 0));
	private final JButton connectionSettingsButton = new JButton("Advanced...");
	private final JTextField connectionCodeOutput = new JTextField();
	private final JTextField connectionCodeInput = new JTextField();
	private final JButton createButton = new JButton("Create & copy code");
	private final JButton copyButton = new JButton("Copy again");
	private final JButton pasteButton = new JButton("Paste & join");
	private final JButton joinButton = new JButton("Join entered code");
	private final JButton cancelWaitingButton = new JButton("Cancel");
	private final JPanel controllerPanel = new JPanel();
	private final JPanel participantPanel = new JPanel();
	private final JPanel discordPanel = new JPanel();
	private final JPanel discordLinkSetup = new JPanel();
	private final JLabel discordStatus = new JLabel("Discord is not linked");
	private final WrappedTextLabel connectionStatus = new WrappedTextLabel(
		"Ready to connect"
	);
	private final WrappedTextLabel waitingStatus = new WrappedTextLabel(
		"Creating secure connection code..."
	);
	private final JTextField discordLinkCode = new JTextField();
	private final JButton installDiscordButton = new JButton("Install Discord app");
	private final JButton linkDiscordButton = new JButton("Link Discord");
	private final JButton unlinkDiscordButton = new JButton("Unlink");
	private final JButton retryDiscordButton = new JButton("Retry saved link");
	private final JPanel viewHost = new JPanel(new BorderLayout());
	private final JPanel connectView = new JPanel();
	private final JPanel waitingView = new JPanel();

	private boolean wasLocal = true;
	private boolean currentSessionLocal = true;
	private boolean pairingBusy;
	private boolean connectionSettingsExpanded;
	private long pairingAttempt;
	private RemotePairingCode activePairingCode;
	private String activePairingRelayUrl;
	private PairingView displayedView;

	RemotePairingPanel(
		HapticScapeSettingsSource config,
		SettingsStore settingsStore,
		ExternalLinkOpener externalLinkOpener,
		TextClipboard clipboard,
		RemoteSessionManager sessionManager,
		RemotePairingService pairingService,
		DiscordPairingBridge discordPairingBridge,
		Consumer<String> errorSink)
	{
		this.config = Objects.requireNonNull(config, "config");
		this.settingsStore = Objects.requireNonNull(settingsStore, "settingsStore");
		this.externalLinkOpener = Objects.requireNonNull(externalLinkOpener, "externalLinkOpener");
		this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
		this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
		this.pairingService = Objects.requireNonNull(pairingService, "pairingService");
		this.discordPairingBridge = Objects.requireNonNull(
			discordPairingBridge,
			"discordPairingBridge"
		);
		this.discordLinkListener = snapshot -> SwingUtilities.invokeLater(
			() -> applyDiscordSnapshot(snapshot)
		);
		this.errorSink = Objects.requireNonNull(errorSink, "errorSink");
		setName("remotePairingWorkspace");
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder());

		configureRelaySettings();
		configureControllerPanel();
		configureParticipantPanel();
		configureDiscordPanel();
		configureConnectView();
		configureWaitingView();

		viewHost.setName("remotePairingViewHost");
		add(viewHost, BorderLayout.CENTER);
		refreshRelaySettingsVisibility(false);

		createButton.addActionListener(event -> createConnectionCode());
		copyButton.addActionListener(event -> copyConnectionCode());
		pasteButton.addActionListener(event -> pasteAndJoinConnection());
		joinButton.addActionListener(event -> joinConnection());
		connectionCodeInput.addActionListener(event -> joinConnection());
		connectionSettingsButton.addActionListener(event ->
		{
			connectionSettingsExpanded = !connectionSettingsExpanded;
			refreshRelaySettingsVisibility(true);
		});
		linkDiscordButton.addActionListener(event -> linkDiscord());
		installDiscordButton.addActionListener(event -> installDiscord());
		unlinkDiscordButton.addActionListener(event -> unlinkDiscord());
		cancelWaitingButton.addActionListener(event -> cancelWaiting());
		discordPairingBridge.addListener(discordLinkListener);
		apply(sessionManager.getSnapshot());
	}

	void apply(RemoteSessionSnapshot snapshot)
	{
		boolean local = snapshot.getState() == RemoteSessionState.LOCAL;
		currentSessionLocal = local;
		PairingView view = viewFor(snapshot);
		if (local && !wasLocal)
		{
			pairingAttempt++;
			pairingBusy = false;
			cancelActivePairing();
			connectionCodeOutput.setText("");
			connectionCodeInput.setText("");
			setStatus("Ready to connect");
		}
		wasLocal = local;

		refreshConnectionControls();
		setVisible(view != PairingView.HIDDEN);
		showView(view);
		if (view == PairingView.WAITING)
		{
			waitingStatus.setPlainText(snapshot.getMessage());
		}
		applyDiscordSnapshot(discordPairingBridge.getSnapshot());
		refreshRelaySettingsVisibility(false);
		revalidate();
		repaint();
	}

	void close()
	{
		pairingAttempt++;
		cancelActivePairing();
		discordPairingBridge.removeListener(discordLinkListener);
	}

	boolean confirmDiscordRemoteControl(DiscordJoinRequest request)
	{
		if (sessionManager.getSnapshot().getState() != RemoteSessionState.LOCAL)
		{
			errorSink.accept("End the current Remote Play session before accepting another one.");
			return false;
		}
		return confirmRemoteControl(
			request.getRelayUrl(),
			"<html><b>" + escapeHtml(request.getControllerName())
				+ "</b> requested a Remote Play session through Discord.<br><br>"
		);
	}

	private void configureRelaySettings()
	{
		String configuredRelay = HapticScapeSettingsSource.resolveRemoteRelayUrl(
			config.remoteRelayUrl()
		);
		relayUrlField.setText(configuredRelay);
		relayUrlField.setToolTipText(
			"Hosted HapticScape relay by default; replace this URL to use a self-hosted relay"
		);
		connectionSettingsExpanded = !HapticScapeSettingsSource.DEFAULT_REMOTE_RELAY_URL.equals(
			configuredRelay
		);
		configureCompactButton(connectionSettingsButton);
		relaySettingsPanel.add(new JLabel("Relay"), BorderLayout.WEST);
		relaySettingsPanel.add(relayUrlField, BorderLayout.CENTER);
		allowHorizontalShrink(relaySettingsPanel);
	}

	private void configureControllerPanel()
	{
		controllerPanel.setName("remoteControlPartner");
		controllerPanel.setLayout(new BoxLayout(controllerPanel, BoxLayout.Y_AXIS));
		controllerPanel.setBorder(PanelUi.createSectionBorder("Control a partner"));
		PanelUi.addPreferredHeightComponent(controllerPanel, new WrappedTextLabel(
			"Create a temporary encrypted connection code and send it privately to your partner."
		));
		JPanel createRow = new JPanel(new GridLayout(1, 1));
		allowHorizontalShrink(createRow);
		configureCompactButton(createButton);
		createRow.add(createButton);
		createRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
		PanelUi.addPreferredHeightComponent(controllerPanel, createRow);
		allowHorizontalShrink(controllerPanel);
	}

	private void configureParticipantPanel()
	{
		participantPanel.setName("remoteJoinPartner");
		participantPanel.setLayout(new BoxLayout(participantPanel, BoxLayout.Y_AXIS));
		participantPanel.setBorder(PanelUi.createSectionBorder("Let a partner control you"));
		PanelUi.addPreferredHeightComponent(participantPanel, new WrappedTextLabel(
			"Paste the temporary connection code your partner sent you."
		));
		connectionCodeInput.setToolTipText(
			"Compact HSP1 connection codes and legacy HSR1 invitations are accepted"
		);
		allowHorizontalShrink(connectionCodeInput);
		PanelUi.addPreferredHeightComponent(participantPanel, connectionCodeInput);
		JPanel joinRow = new JPanel(new GridLayout(1, 2, 4, 0));
		allowHorizontalShrink(joinRow);
		configureCompactButton(pasteButton);
		configureCompactButton(joinButton);
		joinRow.add(pasteButton);
		joinRow.add(joinButton);
		PanelUi.addPreferredHeightComponent(participantPanel, joinRow);
		allowHorizontalShrink(participantPanel);
	}

	private void configureDiscordPanel()
	{
		discordPanel.setName("remoteDiscordLink");
		discordPanel.setLayout(new BoxLayout(discordPanel, BoxLayout.Y_AXIS));
		discordPanel.setBorder(PanelUi.createSectionBorder("Discord"));
		JPanel statusRow = new JPanel(new BorderLayout(8, 0));
		statusRow.add(discordStatus, BorderLayout.CENTER);
		configureCompactButton(unlinkDiscordButton);
		configureCompactButton(retryDiscordButton);
		retryDiscordButton.setName("remoteDiscordRetry");
		retryDiscordButton.addActionListener(event -> discordPairingBridge.retryStoredLink());
		JPanel linkActions = new JPanel(new GridLayout(1, 2, 4, 0));
		linkActions.add(retryDiscordButton);
		linkActions.add(unlinkDiscordButton);
		statusRow.add(linkActions, BorderLayout.EAST);
		allowHorizontalShrink(statusRow);
		PanelUi.addPreferredHeightComponent(discordPanel, statusRow);

		discordLinkSetup.setName("remoteDiscordLinkSetup");
		discordLinkSetup.setLayout(new BoxLayout(discordLinkSetup, BoxLayout.Y_AXIS));
		WrappedTextLabel explanation = new WrappedTextLabel(
			"Link this client once to receive consent-gated requests from Discord."
		);
		explanation.setBorder(BorderFactory.createEmptyBorder(5, 0, 4, 0));
		PanelUi.addPreferredHeightComponent(discordLinkSetup, explanation);
		Dimension statusSize = new Dimension(180, discordStatus.getPreferredSize().height);
		discordStatus.setPreferredSize(statusSize);
		discordStatus.setMinimumSize(new Dimension(0, statusSize.height));
		discordLinkCode.setName("remoteDiscordLinkCode");
		discordLinkCode.setToolTipText(
			"Paste the private HSL1 link code generated by /hapticscape link"
		);
		allowHorizontalShrink(discordLinkCode);
		PanelUi.addPreferredHeightComponent(discordLinkSetup, discordLinkCode);
		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		configureCompactButton(installDiscordButton);
		configureCompactButton(linkDiscordButton);
		buttons.add(installDiscordButton);
		buttons.add(linkDiscordButton);
		allowHorizontalShrink(buttons);
		buttons.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
		PanelUi.addPreferredHeightComponent(discordLinkSetup, buttons);
		PanelUi.addFlexibleVerticalComponent(discordPanel, discordLinkSetup);
		allowHorizontalShrink(discordPanel);
	}

	private void configureConnectView()
	{
		connectView.setName("remoteConnectView");
		connectView.setLayout(new BoxLayout(connectView, BoxLayout.Y_AXIS));
		connectionStatus.setName("remoteConnectionStatus");
		connectionStatus.setBorder(PanelUi.createSectionBorder("Remote Play"));
		PanelUi.addPreferredHeightComponent(connectView, connectionStatus);
		WrappedTextLabel heading = new WrappedTextLabel(
			"Start a private session with a temporary code, or accept a linked Discord request."
		);
		heading.setBorder(BorderFactory.createEmptyBorder(2, 2, 7, 2));
		PanelUi.addPreferredHeightComponent(connectView, heading);

		ResponsiveColumnsPanel connectionChoices = new ResponsiveColumnsPanel(
			controllerPanel,
			participantPanel
		);
		connectionChoices.setName("remoteConnectionChoices");
		PanelUi.addFlexibleVerticalComponent(connectView, connectionChoices);
		PanelUi.addPreferredHeightComponent(connectView, discordPanel);

		JPanel advanced = new JPanel();
		advanced.setLayout(new BoxLayout(advanced, BoxLayout.Y_AXIS));
		PanelUi.addPreferredHeightComponent(advanced, relaySettingsPanel);
		PanelUi.addPreferredHeightComponent(advanced, connectionSettingsButton);
		PanelUi.addFlexibleVerticalComponent(connectView, advanced);
	}

	private void configureWaitingView()
	{
		waitingView.setName("remoteWaitingView");
		waitingView.setLayout(new BoxLayout(waitingView, BoxLayout.Y_AXIS));
		waitingView.setBorder(PanelUi.createSectionBorder("Waiting for your partner"));
		waitingStatus.setName("remoteWaitingStatus");
		waitingStatus.setBorder(BorderFactory.createEmptyBorder(0, 1, 7, 1));
		PanelUi.addPreferredHeightComponent(waitingView, waitingStatus);

		connectionCodeOutput.setName("remoteConnectionCodeOutput");
		connectionCodeOutput.setEditable(false);
		connectionCodeOutput.setToolTipText(
			"The code expires after five minutes and can be redeemed only once"
		);
		allowHorizontalShrink(connectionCodeOutput);
		PanelUi.addPreferredHeightComponent(waitingView, connectionCodeOutput);

		configureCompactButton(copyButton);
		configureCompactButton(cancelWaitingButton);
		JPanel waitingButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		waitingButtons.setBorder(BorderFactory.createEmptyBorder(5, 0, 0, 0));
		waitingButtons.add(copyButton);
		waitingButtons.add(cancelWaitingButton);
		allowHorizontalShrink(waitingButtons);
		PanelUi.addPreferredHeightComponent(waitingView, waitingButtons);

		WrappedTextLabel hint = new WrappedTextLabel(
			"Keep this window open while your partner joins. The code is temporary and single-use."
		);
		hint.setBorder(BorderFactory.createEmptyBorder(7, 1, 0, 1));
		PanelUi.addPreferredHeightComponent(waitingView, hint);
	}

	private void installDiscord()
	{
		try
		{
			externalLinkOpener.open(
				RemotePairingService.discordInstallEndpoint(relayUrlField.getText().trim())
			);
		}
		catch (RuntimeException exception)
		{
			errorSink.accept("Could not open the Discord installation page.");
		}
	}

	private void linkDiscord()
	{
		String code = discordLinkCode.getText().trim();
		if (code.isEmpty())
		{
			errorSink.accept("Run /hapticscape link in Discord and paste its code first.");
			return;
		}
		String relayUrl = relayUrlField.getText().trim();
		try
		{
			discordPairingBridge.link(relayUrl, code).whenComplete((snapshot, error) ->
				SwingUtilities.invokeLater(() ->
				{
					if (error != null)
					{
						errorSink.accept(rootMessage(error));
					}
					else
					{
						discordLinkCode.setText("");
					}
				})
			);
		}
		catch (RuntimeException exception)
		{
			errorSink.accept(exception.getMessage());
		}
	}

	private void unlinkDiscord()
	{
		try
		{
			discordPairingBridge.unlink().whenComplete((ignored, error) ->
			{
				if (error != null)
				{
					SwingUtilities.invokeLater(() -> errorSink.accept(
						"The local Discord link was removed, but the relay could not be notified. "
							+ "You can also run /hapticscape unlink in Discord."
					));
				}
			});
		}
		catch (RuntimeException exception)
		{
			errorSink.accept(exception.getMessage());
		}
	}

	private void applyDiscordSnapshot(DiscordLinkSnapshot snapshot)
	{
		discordStatus.setText(snapshot.getMessage());
		boolean linking = snapshot.getState() == DiscordLinkState.CONNECTING;
		boolean linked = snapshot.isLinked();
		boolean available = snapshot.getState() != DiscordLinkState.UNAVAILABLE;
		discordLinkSetup.setVisible(showsDiscordSetup(snapshot.getState()));
		unlinkDiscordButton.setVisible(showsDiscordUnlink(snapshot.getState()));
		retryDiscordButton.setVisible(snapshot.getState() == DiscordLinkState.UNAVAILABLE
			&& discordPairingBridge.canRetryStoredLink());
		retryDiscordButton.setEnabled(currentSessionLocal && !linking);
		installDiscordButton.setEnabled(currentSessionLocal && available);
		discordLinkCode.setEnabled(currentSessionLocal && available && !linked && !linking);
		linkDiscordButton.setEnabled(
			currentSessionLocal && available && !linked && !linking
		);
		unlinkDiscordButton.setEnabled(currentSessionLocal && linked);
		discordStatus.setToolTipText(snapshot.getMessage());
		discordPanel.revalidate();
		discordPanel.repaint();
	}

	private void createConnectionCode()
	{
		String relayUrl = relayUrlField.getText().trim();
		if (relayUrl.isEmpty())
		{
			errorSink.accept("Enter the wss:// URL of your HapticScape relay first.");
			return;
		}
		try
		{
			settingsStore.set(HapticScapeSettingKeys.REMOTE_RELAY_URL, relayUrl);
			pairingBusy = true;
			long attempt = ++pairingAttempt;
			setStatus("Creating secure connection code...");
			RemoteInvitation invitation = sessionManager.startController(relayUrl);
			pairingService.publish(invitation).whenComplete((code, error) ->
				SwingUtilities.invokeLater(() -> finishPublishingCode(
					attempt,
					relayUrl,
					invitation,
					code,
					error
				))
			);
		}
		catch (RuntimeException exception)
		{
			pairingBusy = false;
			setStatus("Ready to connect");
			errorSink.accept(exception.getMessage());
		}
	}

	private void finishPublishingCode(
		long attempt,
		String relayUrl,
		RemoteInvitation invitation,
		RemotePairingCode code,
		Throwable error)
	{
		if (attempt != pairingAttempt || !sessionManager.isControllerSession())
		{
			if (code != null)
			{
				pairingService.cancel(relayUrl, code);
			}
			return;
		}

		pairingBusy = false;
		if (error == null)
		{
			activePairingCode = code;
			activePairingRelayUrl = relayUrl;
			showOutput(code.encode());
			setStatus(copyToClipboard(code.encode())
				? "Connection code copied. Waiting for your partner..."
				: "Connection code ready. Copy it and send it to your partner."
			);
		}
		else
		{
			String directCode = invitation.encode();
			showOutput(directCode);
			setStatus(copyToClipboard(directCode)
				? "Pairing service unavailable. A direct connection code was copied."
				: "Pairing service unavailable. Copy the direct connection code manually."
			);
		}
		copyButton.setEnabled(!connectionCodeOutput.getText().trim().isEmpty());
	}

	private void joinConnection()
	{
		String encoded = connectionCodeInput.getText().trim();
		if (encoded.isEmpty())
		{
			errorSink.accept("Paste a HapticScape connection code first.");
			return;
		}

		if (encoded.startsWith("HSP1."))
		{
			joinPairingCode(encoded);
			return;
		}

		try
		{
			RemoteInvitation invitation = RemoteInvitation.parse(encoded);
			sessionManager.validateParticipantJoin();
			if (confirmRemoteControl(invitation.getRelayUrl()))
			{
				sessionManager.joinParticipant(encoded);
			}
		}
		catch (RuntimeException exception)
		{
			errorSink.accept(exception.getMessage());
		}
	}

	private void joinPairingCode(String encoded)
	{
		String relayUrl = relayUrlField.getText().trim();
		try
		{
			RemotePairingCode.parse(encoded);
			sessionManager.validateParticipantJoin();
			if (relayUrl.isEmpty())
			{
				throw new IllegalArgumentException("Enter the relay URL used by your partner.");
			}
			if (!confirmRemoteControl(relayUrl))
			{
				return;
			}
			settingsStore.set(HapticScapeSettingKeys.REMOTE_RELAY_URL, relayUrl);
			pairingBusy = true;
			long attempt = ++pairingAttempt;
			refreshConnectionControls();
			setStatus("Retrieving secure connection...");
			pairingService.redeem(relayUrl, encoded).whenComplete((invitation, error) ->
				SwingUtilities.invokeLater(() -> finishRedeemingCode(
					attempt,
					invitation,
					error
				))
			);
		}
		catch (RuntimeException exception)
		{
			pairingBusy = false;
			refreshConnectionControls();
			setStatus("Ready to connect");
			errorSink.accept(exception.getMessage());
		}
	}

	private void finishRedeemingCode(
		long attempt,
		RemoteInvitation invitation,
		Throwable error)
	{
		if (attempt != pairingAttempt)
		{
			return;
		}
		pairingBusy = false;
		if (error != null)
		{
			refreshConnectionControls();
			setStatus("Ready to connect");
			errorSink.accept(rootMessage(error));
			return;
		}
		try
		{
			sessionManager.joinParticipant(invitation.encode());
		}
		catch (RuntimeException exception)
		{
			refreshConnectionControls();
			setStatus("Ready to connect");
			errorSink.accept(exception.getMessage());
		}
	}

	private boolean confirmRemoteControl(String relayUrl)
	{
		return confirmRemoteControl(relayUrl, "<html>");
	}

	private boolean confirmRemoteControl(String relayUrl, String introduction)
	{
		int choice = JOptionPane.showConfirmDialog(
			this,
			introduction + "Join Remote Control through:<br><b>"
				+ escapeHtml(relayUrl)
				+ "</b><br><br>The controller will become authoritative for "
				+ "HapticScape feedback settings during the session.<br>"
				+ "Your current settings will seed their controls. Accepted changes "
				+ "are saved here and remain after the session.<br>"
				+ "Remote actions are limited by the permissions shown on this page.<br>"
				+ "Emergency Off and End Session always remain local.<br><br>"
				+ "The relay operator can see your network IP. HapticScape does not "
				+ "send your IP to the paired client.</html>",
			"Accept Remote Control",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);
		return choice == JOptionPane.YES_OPTION;
	}

	private static String escapeHtml(String value)
	{
		return value
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	private void refreshConnectionControls()
	{
		RemoteSessionSnapshot snapshot = sessionManager.getSnapshot();
		boolean local = snapshot.getState() == RemoteSessionState.LOCAL;
		boolean controller = snapshot.getRole() == RemoteRole.CONTROLLER && !local;
		boolean locallyAvailable = local && !pairingBusy;
		createButton.setEnabled(locallyAvailable);
		joinButton.setEnabled(locallyAvailable);
		relayUrlField.setEnabled(locallyAvailable);
		connectionCodeInput.setEnabled(locallyAvailable);
		pasteButton.setEnabled(locallyAvailable);
		copyButton.setEnabled(
			controller && !connectionCodeOutput.getText().trim().isEmpty()
		);
		connectionSettingsButton.setEnabled(locallyAvailable);
	}

	private void refreshRelaySettingsVisibility(boolean layout)
	{
		boolean local = sessionManager.getSnapshot().getState() == RemoteSessionState.LOCAL;
		relaySettingsPanel.setVisible(local && connectionSettingsExpanded);
		connectionSettingsButton.setText(connectionSettingsExpanded
			? "Hide advanced"
			: "Advanced..."
		);
		if (layout)
		{
			revalidate();
			Dimension preferred = getPreferredSize();
			setMinimumSize(new Dimension(0, preferred.height));
			if (getParent() != null)
			{
				getParent().revalidate();
				getParent().repaint();
			}
			repaint();
		}
	}

	private void copyConnectionCode()
	{
		String code = connectionCodeOutput.getText().trim();
		if (!code.isEmpty() && copyToClipboard(code))
		{
			setStatus("Connection code copied. Waiting for your partner...");
		}
	}

	private void cancelWaiting()
	{
		pairingAttempt++;
		pairingBusy = false;
		cancelActivePairing();
		sessionManager.endSession();
	}

	private void pasteAndJoinConnection()
	{
		try
		{
			String value = clipboard.readText();
			if (value != null)
			{
				connectionCodeInput.setText(value.trim());
				connectionCodeInput.setCaretPosition(0);
				joinConnection();
			}
		}
		catch (RuntimeException exception)
		{
			errorSink.accept("Could not paste a connection code from the clipboard.");
		}
	}

	private void showOutput(String code)
	{
		connectionCodeOutput.setText(code);
		connectionCodeOutput.setCaretPosition(0);
	}

	private boolean copyToClipboard(String value)
	{
		try
		{
			clipboard.copyText(value);
			return true;
		}
		catch (RuntimeException exception)
		{
			errorSink.accept("Could not copy the connection code to the clipboard.");
			return false;
		}
	}

	private void setStatus(String message)
	{
		connectionStatus.setPlainText(message);
		waitingStatus.setPlainText(message);
	}

	private void showView(PairingView view)
	{
		if (view == displayedView)
		{
			return;
		}
		displayedView = view;
		viewHost.removeAll();
		if (view == PairingView.CONNECT)
		{
			viewHost.add(connectView, BorderLayout.CENTER);
		}
		else if (view == PairingView.WAITING)
		{
			viewHost.add(waitingView, BorderLayout.CENTER);
		}
		viewHost.revalidate();
		viewHost.repaint();
	}

	private void cancelActivePairing()
	{
		RemotePairingCode code = activePairingCode;
		String relayUrl = activePairingRelayUrl;
		activePairingCode = null;
		activePairingRelayUrl = null;
		if (code != null && relayUrl != null)
		{
			pairingService.cancel(relayUrl, code);
		}
	}

	private static String rootMessage(Throwable error)
	{
		Throwable current = error;
		while (current.getCause() != null)
		{
			current = current.getCause();
		}
		return current.getMessage() == null
			? "Remote pairing failed"
			: current.getMessage();
	}

	static PairingView viewFor(RemoteSessionSnapshot snapshot)
	{
		if (snapshot.getState() == RemoteSessionState.LOCAL)
		{
			return PairingView.CONNECT;
		}
		if (snapshot.getRole() == RemoteRole.CONTROLLER
			&& (snapshot.getState() == RemoteSessionState.CONNECTING
				|| snapshot.getState() == RemoteSessionState.WAITING_FOR_PEER))
		{
			return PairingView.WAITING;
		}
		return PairingView.HIDDEN;
	}

	static boolean showsDiscordSetup(DiscordLinkState state)
	{
		return state == DiscordLinkState.UNLINKED;
	}

	static boolean showsDiscordUnlink(DiscordLinkState state)
	{
		return state == DiscordLinkState.LINKED || state == DiscordLinkState.OFFLINE;
	}

	enum PairingView
	{
		CONNECT,
		WAITING,
		HIDDEN
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
}
