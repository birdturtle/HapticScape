package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.AlertCategory;
import com.ashy0019.hapticscape.AlertProfiles;
import com.ashy0019.hapticscape.AlertTriggerSettings;
import com.ashy0019.hapticscape.CustomPatternEntry;
import com.ashy0019.hapticscape.CustomPatternLibrary;
import com.ashy0019.hapticscape.HapticPatternSelection;
import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.HapticScapeSettingsSource;
import com.ashy0019.hapticscape.NotificationFeedbackSettings;
import com.ashy0019.hapticscape.SkillCatalog;
import com.ashy0019.hapticscape.SkillClickProfiles;
import com.ashy0019.hapticscape.SkillFeedbackProfiles;
import com.ashy0019.hapticscape.SkillSelection;
import com.ashy0019.hapticscape.XpFeedbackSettings;
import com.ashy0019.hapticscape.clicker.ClickSequence;
import com.ashy0019.hapticscape.clicker.ClickerSettings;
import com.ashy0019.hapticscape.clicker.ClickerXpSettings;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRules;
import com.ashy0019.hapticscape.device.ConnectionSnapshot;
import com.ashy0019.hapticscape.device.ConnectionState;
import com.ashy0019.hapticscape.device.DeviceInfo;
import com.ashy0019.hapticscape.host.ExternalLinkOpener;
import com.ashy0019.hapticscape.host.GlobalUiHooks;
import com.ashy0019.hapticscape.host.TextClipboard;
import com.ashy0019.hapticscape.music.MusicSyncSettings;
import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureMode;
import com.ashy0019.hapticscape.music.MusicSyncSnapshot;
import com.ashy0019.hapticscape.rogue.KonamiCodeDetector;
import com.ashy0019.hapticscape.rogue.RogueFeedbackEvent;
import com.ashy0019.hapticscape.remote.RemoteLockSnapshot;
import com.ashy0019.hapticscape.remote.RemoteLockState;
import com.ashy0019.hapticscape.remote.DiscordPairingBridge;
import com.ashy0019.hapticscape.remote.DiscordJoinRequest;
import com.ashy0019.hapticscape.remote.RemotePermissions;
import com.ashy0019.hapticscape.remote.RemotePairingService;
import com.ashy0019.hapticscape.remote.RemoteRole;
import com.ashy0019.hapticscape.remote.RemoteSessionListener;
import com.ashy0019.hapticscape.remote.RemoteSessionManager;
import com.ashy0019.hapticscape.remote.RemoteSessionSnapshot;
import com.ashy0019.hapticscape.remote.RemoteSettingsSnapshot;
import com.ashy0019.hapticscape.remote.RemoteSessionState;
import com.ashy0019.hapticscape.remote.SettingsLockCatalog;
import com.ashy0019.hapticscape.remote.SettingsLockListener;
import com.ashy0019.hapticscape.remote.SettingsLockService;
import com.ashy0019.hapticscape.remote.SettingsLockSnapshot;
import com.ashy0019.hapticscape.remote.SettingsLockTarget;
import com.ashy0019.hapticscape.remote.SettingsStore;
import com.ashy0019.hapticscape.rogue.ui.RogueLauncherPanel;
import com.ashy0019.hapticscape.rogue.ui.RoguePanel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JScrollPane;
import javax.swing.JSlider;
import javax.swing.JSpinner;
import javax.swing.JTabbedPane;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

public final class HapticScapePanel extends JPanel
	implements RemoteSessionListener, SettingsLockListener
{
	private static final int DEVELOPER_UNLOCK_CLICKS = 8;
	private static final long DEVELOPER_UNLOCK_WINDOW_NANOS = TimeUnit.SECONDS.toNanos(4);
	private static final String NORMAL_CARD = "normal";
	private static final String ROGUE_CARD = "rogue";
	private static final String GAMEPLAY_WORKSPACE = "gameplay";
	private static final String PATTERNS_WORKSPACE = "patterns";
	private static final String REMOTE_WORKSPACE = "remote";
	private static final String SETTINGS_WORKSPACE = "settings";
	private static final int MINE_WORKSPACE_INDEX = 0;
	private static final int SUBJECT_WORKSPACE_INDEX = 1;
	private static final String SETTINGS_LOCKED_MESSAGE =
		"<html><b>Settings locked</b><br>Forge + Music<br>stay editable</html>";
	private static final String POST_SESSION_LOCK_MESSAGE =
		"<html><b>Post-session lock</b><br>armed after session</html>";

	private final HapticScapeSettingsSource config;
	private final SettingsStore settingsStore;
	private final Consumer<RogueFeedbackEvent> rogueFeedbackAction;
	private final Runnable rogueUnlockSoundAction;
	private final KonamiCodeDetector konamiCodeDetector = new KonamiCodeDetector();
	private final GlobalUiHooks.Registration rogueKeyHook;
	private final JTabbedPane tabs;
	private final JTabbedPane patternTabs;
	private final CardLayout contentLayout = new CardLayout();
	private final JPanel contentHost = new JPanel(contentLayout);
	private final JTabbedPane controllerWorkspaceTabs = new JTabbedPane(JTabbedPane.TOP);
	private final JLabel statusLabel = new JLabel("Disconnected");
	private final JLabel connectionIndicator = new JLabel("\u25cf");
	private final JLabel deviceSummaryLabel = new JLabel("No device connected");
	private final JLabel sourceStatusLabel = new JLabel(" ", SwingConstants.RIGHT);
	private final JLabel pageTitleLabel = new JLabel("Gameplay");
	private final DefaultListModel<DeviceInfo> deviceModel = new DefaultListModel<>();
	private final JButton connectButton = new JButton("Connect");
	private final JButton disconnectButton = new JButton("Disconnect");
	private final JButton testButton = new JButton("Test");
	private final JButton testLevelUpButton = new JButton("Test level-up");
	private final JButton previewLevel99Button = new JButton("Test 99");
	private final JButton resetRogueDiscoveryButton = new JButton("Reset Rogue");
	private final JButton hideRogueBannerButton = new JButton("Hide Rogue banner");
	private final JButton clearSettingsLockButton = new JButton("Clear settings lock");
	private final JButton stopButton = new JButton("STOP ALL");
	private final JPanel settingsLockBanner = new JPanel(new BorderLayout(6, 0));
	private final JLabel settingsLockLabel = new JLabel(SETTINGS_LOCKED_MESSAGE);
	private final JButton unlockSettingsButton = new JButton("Unlock");
	private final JPanel remoteBanner = new JPanel(new BorderLayout(6, 0));
	private final JLabel remoteBannerLabel = new JLabel();
	private final JButton remoteEmergencyButton = new JButton("Emergency Off");
	private final JButton remoteResumeButton = new JButton("Resume");
	private final JButton remoteReconnectButton = new JButton("Reconnect");
	private final JButton remoteEndButton = new JButton("End");
	private final JCheckBox levelUpCheckBox = new JCheckBox("Level-ups");
	private final JCheckBox milestoneCheckBox = new JCheckBox("Milestones");
	private final JCheckBox level99CheckBox = new JCheckBox("Celebrate level 99");
	private final JLabel intensityValueLabel = new JLabel();
	private final JSlider intensitySlider;
	private final JSpinner minimumXpSpinner;
	private final JSpinner durationSpinner;
	private final JComboBox<HapticPatternSelection> patternComboBox;
	private final JComboBox<HapticPatternSelection> levelUpPatternComboBox;
	private final JComboBox<HapticPatternSelection> milestonePatternComboBox;
	private final JPanel level99Row = new JPanel(new BorderLayout(8, 0));
	private final JPanel developerControlsRow = new JPanel(new GridLayout(0, 1, 0, 4));
	private final JPanel settingsPanel;
	private final SkillsPanel skillsPanel;
	private final ProfilesPanel profilesPanel;
	private final AlertsPanel alertsPanel;
	private final CustomPatternsPanel customPatternsPanel;
	private final MusicPanel musicPanel;
	private final ClickOutputPanel clickOutputPanel;
	private final XpClickSettingsPanel xpClickSettingsPanel;
	private final ClickerPhraseRulesPanel phraseRulesPanel;
	private final ApplicationStartupPanel applicationStartupPanel;
	private final RemoteSessionManager remoteSessionManager;
	private final SettingsLockService settingsLockService;
	private final SettingsLockDraft settingsLockDraft = new SettingsLockDraft();
	private final Runnable settingsLockDraftViewportListener =
		this::preserveSubjectLockDraftViewport;
	private final LockableCheckBoxBinding levelUpLockBinding;
	private final LockableCheckBoxBinding milestoneLockBinding;
	private final LockableCheckBoxBinding level99LockBinding;
	private final LockableSectionHeader feedbackBlockHeader;
	private final RemoteControlPanel remoteControlPanel;
	private final ForgeWorkspacePanel forgeWorkspacePanel;
	private final WorkspaceShell workspaceShell;
	private final JScrollPane pageScrollPane;
	private final GlobalUiHooks.Registration pageScrollRouting;
	private final RoguePanel roguePanel;
	private final RogueLauncherPanel rogueLauncher;
	private boolean rogueModeUnlocked;
	private boolean rogueViewActive;
	private boolean forgeDocked;
	private final Timer developerStatusTimer;

	private volatile int intensityPercent;
	private volatile int minimumXpGain;
	private volatile int durationMillis;
	private volatile HapticPatternSelection patternSelection;
	private volatile HapticPatternSelection levelUpPatternSelection;
	private volatile HapticPatternSelection milestonePatternSelection;
	private volatile boolean levelUpEnabled;
	private volatile boolean milestoneEnabled;
	private volatile boolean level99Enabled;
	private volatile CustomPatternLibrary customPatterns;
	private boolean updatingPatternSelectors;
	private boolean developerControlsUnlocked;
	private int developerUnlockClickCount;
	private long developerUnlockStartedNanos;
	private ConnectionSnapshot latestConnectionSnapshot = ConnectionSnapshot.disconnected();
	private boolean remoteReadOnly;
	private boolean displayingRemoteSettings;
	private boolean updatingDisplayedSettings;
	private boolean subjectWorkspaceSelected = true;
	private boolean controllerSubjectAvailable;
	private boolean updatingControllerWorkspaceTabs;
	private RemoteSessionSnapshot appliedRemoteSessionSnapshot = RemoteSessionSnapshot.local();

	public HapticScapePanel(
		HapticScapeSettingsSource config,
		SkillCatalog skillCatalog,
		SettingsStore settingsStore,
		ExternalLinkOpener externalLinkOpener,
		TextClipboard clipboard,
		GlobalUiHooks globalUiHooks,
		JScrollPane pageScrollPane,
		Runnable connectAction,
		Runnable disconnectAction,
		Runnable testAction,
		Runnable testLevelUpAction,
		Runnable previewLevel99Action,
		Runnable testSkillProfileAction,
		Runnable testGenericAlertAction,
		Consumer<AlertCategory> testSpecificAlertAction,
		Consumer<CustomPatternEntry> patternForgePreviewAction,
		Consumer<MusicSyncSettings> musicSettingsAction,
		Supplier<java.util.List<AudioCaptureEndpoint>> audioCaptureEndpoints,
		Consumer<AudioCaptureEndpoint> audioCaptureEndpointAction,
		Consumer<ClickerSettings> clickerSettingsAction,
		Runnable testClickAction,
		RemoteSessionManager remoteSessionManager,
		RemotePairingService remotePairingService,
		DiscordPairingBridge discordPairingBridge,
		SettingsLockService settingsLockService,
		Consumer<RogueFeedbackEvent> rogueFeedbackAction,
		Runnable rogueUnlockSoundAction,
		Runnable stopAction)
	{
		super();
		this.config = config;
		this.settingsStore = settingsStore;
		this.remoteSessionManager = remoteSessionManager;
		this.settingsLockService = settingsLockService;
		this.rogueFeedbackAction = rogueFeedbackAction;
		this.rogueUnlockSoundAction = rogueUnlockSoundAction;
		this.pageScrollPane = java.util.Objects.requireNonNull(
			pageScrollPane,
			"pageScrollPane"
		);
		workspaceShell = new WorkspaceShell(this.pageScrollPane);
		// Register before the lockable controls and Remote Play panel so the
		// current viewport is anchored before their synchronous draft listeners
		// can trigger a CardLayout/preferred-size reflow.
		settingsLockDraft.addListener(settingsLockDraftViewportListener);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder());

		statusLabel.setName("connectionStatusText");
		statusLabel.setForeground(HapticScapeTheme.TEXT);
		deviceSummaryLabel.setName("connectionDeviceSummary");
		deviceSummaryLabel.setForeground(HapticScapeTheme.MUTED_TEXT);
		sourceStatusLabel.setName("gameplaySourceStatus");
		sourceStatusLabel.setForeground(HapticScapeTheme.MUTED_TEXT);
		sourceStatusLabel.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 0));
		connectionIndicator.setName("connectionStatusIndicator");
		connectionIndicator.setForeground(HapticScapeTheme.MUTED_TEXT);
		developerStatusTimer = new Timer(1600, event ->
			statusLabel.setText(latestConnectionSnapshot.getMessage()));
		developerStatusTimer.setRepeats(false);

		intensityPercent = clamp(config.intensityPercent(), 0, 100);
		minimumXpGain = clamp(
			config.minimumXpGain(),
			XpFeedbackSettings.MINIMUM_XP_GAIN,
			XpFeedbackSettings.MAXIMUM_XP_GAIN
		);
		durationMillis = clamp(
			config.pulseDurationMillis(),
			XpFeedbackSettings.MINIMUM_DURATION_MILLIS,
			XpFeedbackSettings.MAXIMUM_DURATION_MILLIS
		);
		customPatterns = CustomPatternLibrary.fromConfigValue(config.customPatterns());
		patternSelection = HapticPatternSelection.fromConfigValue(config.patternPreset())
			.resolveAgainst(customPatterns);
		levelUpPatternSelection = HapticPatternSelection
			.fromConfigValue(config.levelUpPatternPreset())
			.resolveAgainst(customPatterns);
		milestonePatternSelection = HapticPatternSelection
			.fromConfigValue(config.milestonePatternPreset())
			.resolveAgainst(customPatterns);
		levelUpEnabled = config.levelUpFeedbackEnabled();
		milestoneEnabled = config.milestoneFeedbackEnabled();
		level99Enabled = config.level99CelebrationEnabled();

		intensitySlider = new JSlider(0, 100, intensityPercent);
		intensityValueLabel.setText(intensityPercent + "%");
		minimumXpSpinner = new JSpinner(new SpinnerNumberModel(
			minimumXpGain,
			XpFeedbackSettings.MINIMUM_XP_GAIN,
			XpFeedbackSettings.MAXIMUM_XP_GAIN,
			1
		));
		PanelUi.setFixedWidth(minimumXpSpinner, PanelUi.NUMERIC_CONTROL_WIDTH);
		durationSpinner = new JSpinner(new SpinnerNumberModel(
			durationMillis,
			XpFeedbackSettings.MINIMUM_DURATION_MILLIS,
			XpFeedbackSettings.MAXIMUM_DURATION_MILLIS,
			50
		));
		PanelUi.setFixedWidth(durationSpinner, PanelUi.NUMERIC_CONTROL_WIDTH);
		durationSpinner.setToolTipText(
			"Total time shared by all pulses and gaps in a built-in pattern"
		);

		patternComboBox = PanelUi.createPatternComboBox(() -> customPatterns);
		patternComboBox.setSelectedItem(patternSelection);
		patternComboBox.setToolTipText("Choose the pattern used for XP feedback");
		levelUpPatternComboBox = PanelUi.createPatternComboBox(() -> customPatterns);
		levelUpPatternComboBox.setSelectedItem(levelUpPatternSelection);
		levelUpPatternComboBox.setToolTipText("Choose the pattern used for ordinary level-ups");
		milestonePatternComboBox = PanelUi.createPatternComboBox(() -> customPatterns);
		milestonePatternComboBox.setSelectedItem(milestonePatternSelection);
		milestonePatternComboBox.setToolTipText("Choose the pattern used for levels 10–90");

		levelUpCheckBox.setSelected(levelUpEnabled);
		levelUpCheckBox.setToolTipText(
			"Replace ordinary XP feedback when a real skill level increases"
		);
		milestoneCheckBox.setSelected(milestoneEnabled);
		milestoneCheckBox.setToolTipText("Use distinct feedback for levels 10–90");
		level99CheckBox.setSelected(level99Enabled);
		testLevelUpButton.setToolTipText("Preview the configured ordinary level-up pattern");
		previewLevel99Button.setToolTipText(
			"Preview the Level 99 ceremony using the skill selected on Profiles"
		);
		previewLevel99Button.setMargin(new java.awt.Insets(2, 6, 2, 6));
		resetRogueDiscoveryButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
		resetRogueDiscoveryButton.setToolTipText(
			"Forget Rogue discovery and the unlock sting so the Konami code can be tested again"
		);
		hideRogueBannerButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
		hideRogueBannerButton.setToolTipText(
			"Hide Rogue Mode and require the Konami code to reveal the banner again"
		);
		hideRogueBannerButton.setVisible(false);
		clearSettingsLockButton.setMargin(new java.awt.Insets(2, 6, 2, 6));
		clearSettingsLockButton.setText("Release all locks");
		clearSettingsLockButton.setVisible(false);
		clearSettingsLockButton.setToolTipText(
			"Developer recovery: release every persistent settings lock"
		);
		developerControlsRow.setVisible(false);
		levelUpLockBinding = bindLockableCheckBox(
			levelUpCheckBox,
			SettingsLockCatalog.LEVEL_UP_HAPTICS,
			() -> levelUpEnabled
		);
		milestoneLockBinding = bindLockableCheckBox(
			milestoneCheckBox,
			SettingsLockCatalog.MILESTONE_HAPTICS,
			() -> milestoneEnabled
		);
		level99LockBinding = bindLockableCheckBox(
			level99CheckBox,
			SettingsLockCatalog.LEVEL_99_HAPTICS,
			() -> level99Enabled
		);
		feedbackBlockHeader = new LockableSectionHeader(
			"",
			() -> SettingsLockCatalog.FEEDBACK_BLOCK,
			settingsLockDraft,
			settingsLockService,
			remoteSessionManager::getLockSnapshot,
			this::isSubjectWorkspaceActive,
			this::isLockSelectionEnabled
		);

		configureGlobalListeners();
		settingsPanel = createGlobalSettingsPanel();

		skillsPanel = new SkillsPanel(
			skillCatalog,
			SkillSelection.fromConfigValue(config.disabledSkills()),
			SkillSelection.fromConfigValue(config.clickerDisabledSkills()),
			this::writeFeedbackSetting,
			remoteSessionManager,
			settingsLockService,
			settingsLockDraft,
			this::isSubjectWorkspaceActive,
			this::isLockSelectionEnabled
		);
		xpClickSettingsPanel = new XpClickSettingsPanel(
			config,
			this::writeFeedbackSetting,
			this::refreshInheritedProfileIfReady,
			remoteSessionManager,
			settingsLockService,
			settingsLockDraft,
			this::isSubjectWorkspaceActive,
			this::isLockSelectionEnabled
		);
		profilesPanel = new ProfilesPanel(
			skillCatalog,
			SkillFeedbackProfiles.fromConfigValue(config.skillFeedbackProfiles())
				.replaceMissingCustomPatterns(customPatterns),
			SkillClickProfiles.fromConfigValue(config.skillClickProfiles()),
			this::writeFeedbackSetting,
			this::getGlobalXpFeedbackSettings,
			xpClickSettingsPanel::getSettings,
			() -> customPatterns,
			testSkillProfileAction,
			remoteSessionManager,
			settingsLockService,
			settingsLockDraft,
			this::isSubjectWorkspaceActive,
			this::isLockSelectionEnabled
		);
		alertsPanel = new AlertsPanel(
			config,
			this::writeFeedbackSetting,
			() -> customPatterns,
			testGenericAlertAction,
			testSpecificAlertAction,
			remoteSessionManager,
			settingsLockService,
			settingsLockDraft,
			this::isSubjectWorkspaceActive,
			this::isLockSelectionEnabled
		);
		customPatternsPanel = new CustomPatternsPanel(
			customPatterns,
			this::writeFeedbackSetting,
			patternForgePreviewAction,
			this::applyCustomPatternLibrary
		);
		musicPanel = new MusicPanel(
			config,
			this::writeFeedbackSetting,
			(target, key, value) -> settingsStore.set(key, value),
			settings ->
			{
				if (!isSubjectWorkspaceActive())
				{
					musicSettingsAction.accept(settings);
				}
			},
			audioCaptureEndpoints,
			audioCaptureEndpointAction
		);
		phraseRulesPanel = new ClickerPhraseRulesPanel(
			config,
			this::writeFeedbackSetting,
			remoteSessionManager,
			settingsLockService,
			settingsLockDraft,
			this::isSubjectWorkspaceActive,
			this::isLockSelectionEnabled
		);
		clickOutputPanel = new ClickOutputPanel(
			config,
			settingsStore,
			clickerSettingsAction,
			testClickAction
		);
		applicationStartupPanel = new ApplicationStartupPanel(
			(key, value) -> writeFeedbackSetting(
				SettingsLockCatalog.STARTUP_BEHAVIOR, key, value),
			settingsLockService,
			settingsLockDraft,
			remoteSessionManager::getLockSnapshot,
			this::isSubjectWorkspaceActive,
			this::isLockSelectionEnabled
		);
		applicationStartupPanel.apply(config.startWithWindows(), config.startMinimized());
		RemoteLiveForgePanel liveForgePanel = new RemoteLiveForgePanel(
			remoteSessionManager,
			globalUiHooks
		);
		forgeWorkspacePanel = new ForgeWorkspacePanel(
			customPatternsPanel,
			liveForgePanel,
			remoteSessionManager
		);
		remoteControlPanel = new RemoteControlPanel(
			config,
			settingsStore,
			externalLinkOpener,
			clipboard,
			globalUiHooks,
			remoteSessionManager,
			remotePairingService,
			discordPairingBridge,
			settingsLockDraft,
			this::openSubjectSettings,
			this::openLiveForge
		);
		roguePanel = new RoguePanel(settingsStore, rogueFeedbackAction);
		rogueLauncher = new RogueLauncherPanel(this::toggleRogueView);

		tabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
		tabs.setName("gameplayWorkspaceTabs");
		PanelUi.configureWorkspaceTabs(tabs);
		JPanel xpDefaults = new JPanel();
		xpDefaults.setLayout(new BoxLayout(xpDefaults, BoxLayout.Y_AXIS));
		PanelUi.addPreferredHeightComponent(xpDefaults, settingsPanel);
		PanelUi.addPreferredHeightComponent(xpDefaults, xpClickSettingsPanel);
		JPanel xpAndSkills = new XpSkillsWorkspacePanel(
			xpDefaults,
			skillsPanel,
			profilesPanel
		);
		PanelUi.addCompactTab(tabs, "XP + Skills", xpAndSkills);
		PanelUi.addCompactTab(tabs, "Alerts", alertsPanel);
		PanelUi.addCompactTab(tabs, "Phrases", phraseRulesPanel);
		JPanel topPanel = new JPanel();
		topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));

		settingsLockBanner.setBorder(PanelUi.createSectionBorder("Settings access"));
		settingsLockBanner.add(settingsLockLabel, BorderLayout.CENTER);
		JPanel unlockButtonHost = new JPanel(new GridBagLayout());
		unlockButtonHost.setOpaque(false);
		unlockButtonHost.add(unlockSettingsButton);
		settingsLockBanner.add(unlockButtonHost, BorderLayout.EAST);
		settingsLockBanner.setVisible(false);
		PanelUi.addFlexibleVerticalComponent(topPanel, settingsLockBanner);

		remoteBanner.setBorder(PanelUi.createSectionBorder("Remote Control"));
		remoteBanner.add(remoteBannerLabel, BorderLayout.CENTER);
		JPanel remoteBannerButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 3, 0));
		remoteBannerButtons.add(remoteEmergencyButton);
		remoteBannerButtons.add(remoteResumeButton);
		remoteBannerButtons.add(remoteReconnectButton);
		remoteBannerButtons.add(remoteEndButton);
		remoteBanner.add(remoteBannerButtons, BorderLayout.SOUTH);
		remoteBanner.setVisible(false);
		PanelUi.addFlexibleVerticalComponent(topPanel, remoteBanner);

		controllerWorkspaceTabs.setName("controllerWorkspaceTabs");
		PanelUi.configureWorkspaceTabs(controllerWorkspaceTabs);
		PanelUi.addCompactTab(controllerWorkspaceTabs, "Mine", new JPanel());
		PanelUi.addCompactTab(controllerWorkspaceTabs, "Subject", new JPanel());
		controllerWorkspaceTabs.setSelectedIndex(SUBJECT_WORKSPACE_INDEX);
		controllerWorkspaceTabs.setPreferredSize(new Dimension(0, 31));
		controllerWorkspaceTabs.setMinimumSize(new Dimension(0, 31));
		controllerWorkspaceTabs.setMaximumSize(new Dimension(Integer.MAX_VALUE, 31));
		controllerWorkspaceTabs.setVisible(false);
		controllerWorkspaceTabs.addChangeListener(event ->
		{
			if (!updatingControllerWorkspaceTabs)
			{
				selectControllerWorkspace(
					controllerWorkspaceTabs.getSelectedIndex() == SUBJECT_WORKSPACE_INDEX
				);
			}
		});
		PanelUi.addFlexibleVerticalComponent(topPanel, controllerWorkspaceTabs);

		JList<DeviceInfo> deviceList = new JList<>(deviceModel);
		JScrollPane scrollPane = new JScrollPane(deviceList);
		PanelUi.setFlexibleWidthHeightHint(scrollPane, 140, 90);
		scrollPane.setBorder(PanelUi.createSectionBorder("Devices"));

		connectButton.addActionListener(event ->
		{
			connectButton.setEnabled(false);
			statusLabel.setText("Connecting");
			connectAction.run();
		});
		disconnectButton.addActionListener(event -> disconnectAction.run());
		testButton.addActionListener(event -> testAction.run());
		testLevelUpButton.addActionListener(event -> testLevelUpAction.run());
		previewLevel99Button.addActionListener(event -> previewLevel99Action.run());
		resetRogueDiscoveryButton.addActionListener(event -> resetRogueDiscovery());
		hideRogueBannerButton.addActionListener(event -> hideRogueBanner());
		clearSettingsLockButton.addActionListener(event -> clearSettingsLockFromDeveloperMode());
		unlockSettingsButton.addActionListener(event -> unlockSettings());
		stopButton.addActionListener(event ->
		{
			if (remoteSessionManager.getSnapshot().isParticipantControlled())
			{
				remoteSessionManager.emergencyPause();
				return;
			}
			musicPanel.disableMusicSync();
			stopAction.run();
		});
		remoteEmergencyButton.addActionListener(event -> remoteSessionManager.emergencyPause());
		remoteResumeButton.addActionListener(event -> remoteSessionManager.resumeParticipant());
		remoteReconnectButton.addActionListener(event -> remoteSessionManager.reconnect());
		remoteEndButton.addActionListener(event -> remoteSessionManager.endSession());

		JPanel primaryButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		primaryButtons.setOpaque(false);
		testButton.setToolTipText("Preview the current feedback pattern");
		stopButton.setToolTipText("Immediately stop every active output");
		stopButton.putClientProperty("JComponent.outline", "error");
		primaryButtons.add(testButton);
		primaryButtons.add(stopButton);

		JPanel deviceSettings = new JPanel(new BorderLayout(0, 6));
		deviceSettings.setBorder(PanelUi.createSectionBorder("Intiface devices"));
		deviceSettings.add(scrollPane, BorderLayout.CENTER);
		JPanel deviceButtons = new JPanel(new GridLayout(1, 2, 4, 0));
		deviceButtons.add(connectButton);
		deviceButtons.add(disconnectButton);
		deviceSettings.add(deviceButtons, BorderLayout.SOUTH);

		JPanel applicationSettings = new JPanel();
		applicationSettings.setLayout(new BoxLayout(applicationSettings, BoxLayout.Y_AXIS));
		PanelUi.addPreferredHeightComponent(applicationSettings, clickOutputPanel);
		PanelUi.addPreferredHeightComponent(applicationSettings, applicationStartupPanel);
		JPanel settingsWorkspace = new ResponsiveColumnsPanel(
			deviceSettings,
			applicationSettings,
			developerControlsRow
		);

		patternTabs = new JTabbedPane(JTabbedPane.TOP);
		patternTabs.setName("patternsWorkspaceTabs");
		PanelUi.configureWorkspaceTabs(patternTabs);
		PanelUi.addCompactTab(patternTabs, "Forge", forgeWorkspacePanel);
		PanelUi.addCompactTab(patternTabs, "Music Sync", musicPanel);

		workspaceShell.addWorkspace(GAMEPLAY_WORKSPACE, "Gameplay", tabs);
		workspaceShell.addWorkspace(PATTERNS_WORKSPACE, "Patterns + Audio", patternTabs);
		workspaceShell.addWorkspace(REMOTE_WORKSPACE, "Remote Play", remoteControlPanel);
		workspaceShell.addWorkspace(SETTINGS_WORKSPACE, "Settings", settingsWorkspace);
		workspaceShell.setUserSelectionAction(this::handleWorkspaceSelected);
		workspaceShell.setSelectionAction(workspace ->
			pageTitleLabel.setText(workspaceShell.getWorkspaceLabel(workspace)));
		workspaceShell.setStatusComponent(createConnectionStatusPanel());
		workspaceShell.setWideDockVisibilityAction(this::setForgeDocked);

		JPanel applicationHeader = new JPanel(new BorderLayout(8, 0));
		applicationHeader.setName("applicationHeader");
		applicationHeader.setBackground(HapticScapeTheme.SURFACE);
		applicationHeader.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 0, 1, 0, HapticScapeTheme.BORDER),
			BorderFactory.createEmptyBorder(7, 12, 7, 12)
		));
		pageTitleLabel.setName("applicationPageTitle");
		pageTitleLabel.setFont(pageTitleLabel.getFont().deriveFont(Font.BOLD));
		applicationHeader.add(pageTitleLabel, BorderLayout.WEST);
		JPanel developerHeaderButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
		developerHeaderButtons.setOpaque(false);
		developerHeaderButtons.add(hideRogueBannerButton);
		developerHeaderButtons.add(clearSettingsLockButton);
		applicationHeader.add(developerHeaderButtons, BorderLayout.CENTER);
		applicationHeader.add(primaryButtons, BorderLayout.EAST);

		JPanel fixedHeader = new JPanel();
		fixedHeader.setName("applicationFixedHeader");
		fixedHeader.setLayout(new BoxLayout(fixedHeader, BoxLayout.Y_AXIS));
		PanelUi.addFlexibleVerticalComponent(fixedHeader, applicationHeader);
		PanelUi.addFlexibleVerticalComponent(fixedHeader, topPanel);

		JPanel normalContent = new JPanel(new BorderLayout());
		normalContent.add(fixedHeader, BorderLayout.NORTH);
		normalContent.add(workspaceShell, BorderLayout.CENTER);

		contentHost.add(normalContent, NORMAL_CARD);
		JScrollPane rogueScrollPane = new JScrollPane(roguePanel);
		rogueScrollPane.setName("roguePageScrollPane");
		rogueScrollPane.setBorder(BorderFactory.createEmptyBorder());
		rogueScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		rogueScrollPane.getVerticalScrollBar().setUnitIncrement(16);
		contentHost.add(rogueScrollPane, ROGUE_CARD);
		add(rogueLauncher, BorderLayout.NORTH);
		add(contentHost, BorderLayout.CENTER);
		// WorkspaceShell owns the normal page viewport; global routing preserves
		// nested list/textarea scrolling and the existing remote viewport anchor.
		pageScrollRouting = globalUiHooks.installPageScrollRouting(pageScrollPane, this);
		contentLayout.show(contentHost, NORMAL_CARD);

		remoteSessionManager.addListener(this);
		settingsLockService.addListener(this);
		applyRemoteSessionState(remoteSessionManager.getSnapshot());
		applyState(ConnectionSnapshot.disconnected());
		rogueKeyHook = globalUiHooks.onScopedKeyPress(this, this::handleRogueKeyPress);
	}

	private JPanel createConnectionStatusPanel()
	{
		JPanel status = new JPanel(new BorderLayout(12, 0));
		status.setName("connectionStatus");
		status.setBackground(HapticScapeTheme.SURFACE);
		status.setBorder(BorderFactory.createEmptyBorder(7, 12, 7, 12));

		JPanel connection = new JPanel(new BorderLayout(7, 0));
		connection.setOpaque(false);
		connectionIndicator.setVerticalAlignment(SwingConstants.CENTER);
		connection.add(connectionIndicator, BorderLayout.WEST);

		JPanel details = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		details.setOpaque(false);
		details.add(statusLabel);
		JLabel separator = new JLabel("  \u2022  ");
		separator.setForeground(HapticScapeTheme.BORDER_HOVER);
		details.add(separator);
		details.add(deviceSummaryLabel);
		connection.add(details, BorderLayout.CENTER);
		status.add(connection, BorderLayout.WEST);
		status.add(sourceStatusLabel, BorderLayout.CENTER);
		return status;
	}

	public int getIntensityPercent()
	{
		return intensityPercent;
	}

	public void showDiscordRemoteView()
	{
		showRemoteView();
	}

	public void updateSourceStatus(String message, Integer rgb)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> updateSourceStatus(message, rgb));
			return;
		}
		String displayed = message == null || message.trim().isEmpty() ? " " : message;
		sourceStatusLabel.setText(displayed);
		sourceStatusLabel.setToolTipText(displayed.trim().isEmpty() ? null : displayed);
		sourceStatusLabel.setForeground(rgb == null
			? HapticScapeTheme.MUTED_TEXT
			: new java.awt.Color(rgb));
	}

	public boolean confirmDiscordRemoteControl(DiscordJoinRequest request)
	{
		showRemoteView();
		return remoteControlPanel.confirmDiscordRemoteControl(request);
	}

	public void showDiscordPairingError(String message)
	{
		showRemoteView();
		JOptionPane.showMessageDialog(
			this,
			message,
			"Discord Remote Play",
			JOptionPane.ERROR_MESSAGE
		);
	}

	public int getMinimumXpGain()
	{
		return minimumXpGain;
	}

	public int getPulseDurationMillis()
	{
		return durationMillis;
	}

	public HapticPatternSelection getPatternPreset()
	{
		return patternSelection;
	}

	public XpFeedbackSettings getGlobalXpFeedbackSettings()
	{
		return new XpFeedbackSettings(
			minimumXpGain,
			intensityPercent,
			durationMillis,
			patternSelection
		);
	}

	public XpFeedbackSettings getXpFeedbackSettings(String skillId)
	{
		return profilesPanel.getSettings(skillId);
	}

	public String getSelectedProfileSkillId()
	{
		return profilesPanel.getSelectedSkillId();
	}

	public HapticPatternSelection getLevelUpPatternPreset()
	{
		return levelUpPatternSelection;
	}

	public HapticPatternSelection getMilestonePatternPreset()
	{
		return milestonePatternSelection;
	}

	public boolean isLevelUpFeedbackEnabled()
	{
		return levelUpEnabled;
	}

	public boolean isMilestoneFeedbackEnabled()
	{
		return milestoneEnabled;
	}

	public boolean isLevel99CelebrationEnabled()
	{
		return level99Enabled;
	}

	public boolean isSkillEnabled(String skillId)
	{
		return isHapticSkillEnabled(skillId);
	}

	public boolean isHapticSkillEnabled(String skillId)
	{
		return skillsPanel.isHapticSkillEnabled(skillId);
	}

	public boolean isClickSkillEnabled(String skillId)
	{
		return skillsPanel.isClickSkillEnabled(skillId);
	}

	public CustomPatternLibrary getCustomPatterns()
	{
		return customPatterns;
	}

	public NotificationFeedbackSettings getNotificationFeedbackSettings()
	{
		return alertsPanel.getGenericSettings();
	}

	public AlertProfiles getAlertProfiles()
	{
		return alertsPanel.getAlertProfiles();
	}

	public boolean isGenericNotificationClickEnabled()
	{
		return alertsPanel.isGenericClickEnabled();
	}

	public ClickSequence getGenericNotificationClickSequence()
	{
		return alertsPanel.getGenericClickSequence();
	}

	public boolean isAlertClickEnabled(AlertCategory category)
	{
		return alertsPanel.isClickEnabled(category);
	}

	public ClickSequence getAlertClickSequence(AlertCategory category)
	{
		return alertsPanel.getClickSequence(category);
	}

	public AlertTriggerSettings getAlertTriggerSettings()
	{
		return alertsPanel.getTriggerSettings();
	}

	public MusicSyncSettings getMusicSyncSettings()
	{
		return musicPanel.getSettings();
	}

	public ClickerSettings getClickerSettings()
	{
		return clickOutputPanel.getSettings();
	}

	public ClickerXpSettings getClickerXpSettings()
	{
		return xpClickSettingsPanel.getSettings();
	}

	public ClickerPhraseRules getClickerPhraseRules()
	{
		return phraseRulesPanel.getRules();
	}

	public void updateMusicSync(MusicSyncSnapshot snapshot)
	{
		musicPanel.updateSnapshot(snapshot);
	}

	private void configureGlobalListeners()
	{
		intensitySlider.addChangeListener(event ->
		{
			if (updatingDisplayedSettings || isGlobalFeedbackReadOnly())
			{
				return;
			}
			intensityPercent = intensitySlider.getValue();
			intensityValueLabel.setText(intensityPercent + "%");
			refreshInheritedProfileIfReady();
			if (!intensitySlider.getValueIsAdjusting())
			{
				writeFeedbackSetting(
					SettingsLockCatalog.FEEDBACK_BLOCK,
					HapticScapeSettingKeys.INTENSITY_PERCENT,
					intensityPercent
				);
			}
		});
		minimumXpSpinner.addChangeListener(event ->
		{
			if (updatingDisplayedSettings || isGlobalFeedbackReadOnly())
			{
				return;
			}
			minimumXpGain = ((Number) minimumXpSpinner.getValue()).intValue();
			writeFeedbackSetting(
				SettingsLockCatalog.FEEDBACK_BLOCK,
				HapticScapeSettingKeys.MINIMUM_XP_GAIN,
				minimumXpGain
			);
			refreshInheritedProfileIfReady();
		});
		durationSpinner.addChangeListener(event ->
		{
			if (updatingDisplayedSettings || isGlobalFeedbackReadOnly())
			{
				return;
			}
			durationMillis = ((Number) durationSpinner.getValue()).intValue();
			writeFeedbackSetting(
				SettingsLockCatalog.FEEDBACK_BLOCK,
				HapticScapeSettingKeys.PULSE_DURATION_MILLIS,
				durationMillis
			);
			refreshInheritedProfileIfReady();
		});
		patternComboBox.addActionListener(event ->
		{
			if (updatingDisplayedSettings || isGlobalFeedbackReadOnly())
			{
				return;
			}
			if (updatingPatternSelectors)
			{
				return;
			}
			HapticPatternSelection selected =
				(HapticPatternSelection) patternComboBox.getSelectedItem();
			if (selected != null)
			{
				patternSelection = selected;
				writeFeedbackSetting(
					SettingsLockCatalog.FEEDBACK_BLOCK,
					HapticScapeSettingKeys.PATTERN_PRESET,
					selected.toConfigValue()
				);
				refreshInheritedProfileIfReady();
			}
		});
		levelUpPatternComboBox.addActionListener(event ->
		{
			if (updatingDisplayedSettings || isGlobalFeedbackReadOnly())
			{
				return;
			}
			if (!updatingPatternSelectors)
			{
				HapticPatternSelection selected =
					(HapticPatternSelection) levelUpPatternComboBox.getSelectedItem();
				if (selected != null)
				{
					levelUpPatternSelection = selected;
					writeFeedbackSetting(
						SettingsLockCatalog.FEEDBACK_BLOCK,
						HapticScapeSettingKeys.LEVEL_UP_PATTERN_PRESET,
						selected.toConfigValue()
					);
				}
			}
		});
		milestonePatternComboBox.addActionListener(event ->
		{
			if (updatingDisplayedSettings || isGlobalFeedbackReadOnly())
			{
				return;
			}
			if (!updatingPatternSelectors)
			{
				HapticPatternSelection selected =
					(HapticPatternSelection) milestonePatternComboBox.getSelectedItem();
				if (selected != null)
				{
					milestonePatternSelection = selected;
					writeFeedbackSetting(
						SettingsLockCatalog.FEEDBACK_BLOCK,
						HapticScapeSettingKeys.MILESTONE_PATTERN_PRESET,
						selected.toConfigValue()
					);
				}
			}
		});
		levelUpCheckBox.addActionListener(event ->
		{
			if (updatingDisplayedSettings || levelUpLockBinding.handleAction(event))
			{
				return;
			}
			if (isGlobalFeedbackReadOnly() || levelUpLockBinding.isEditLocked())
			{
				return;
			}
			levelUpEnabled = levelUpCheckBox.isSelected();
			writeFeedbackSetting(
				SettingsLockCatalog.LEVEL_UP_HAPTICS,
				HapticScapeSettingKeys.LEVEL_UP_FEEDBACK_ENABLED,
				levelUpEnabled
			);
		});
		milestoneCheckBox.addActionListener(event ->
		{
			if (updatingDisplayedSettings || milestoneLockBinding.handleAction(event))
			{
				return;
			}
			if (isGlobalFeedbackReadOnly() || milestoneLockBinding.isEditLocked())
			{
				return;
			}
			milestoneEnabled = milestoneCheckBox.isSelected();
			writeFeedbackSetting(
				SettingsLockCatalog.MILESTONE_HAPTICS,
				HapticScapeSettingKeys.MILESTONE_FEEDBACK_ENABLED,
				milestoneEnabled
			);
		});
		level99CheckBox.addActionListener(event ->
		{
			if (updatingDisplayedSettings || level99LockBinding.handleAction(event))
			{
				return;
			}
			if (isGlobalFeedbackReadOnly() || level99LockBinding.isEditLocked())
			{
				return;
			}
			level99Enabled = level99CheckBox.isSelected();
			writeFeedbackSetting(
				SettingsLockCatalog.LEVEL_99_HAPTICS,
				HapticScapeSettingKeys.LEVEL_99_CELEBRATION_ENABLED,
				level99Enabled
			);
		});
		statusLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent event)
			{
				handleDeveloperUnlockClick(event);
			}
		});
		pageTitleLabel.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent event)
			{
				handleDeveloperUnlockClick(event);
			}
		});
	}

	private void writeFeedbackSetting(
		SettingsLockTarget target,
		String key,
		Object value)
	{
		RemoteSessionSnapshot current = remoteSessionManager.getSnapshot();
		if (isSubjectWorkspaceActive(current))
		{
			remoteSessionManager.updateControllerSetting(key, value);
			return;
		}
		if (current.getRole() == RemoteRole.PARTICIPANT
			&& current.getState() != RemoteSessionState.LOCAL)
		{
			return;
		}
		if (target == null
			? !settingsLockService.canEditLocally(key)
			: !settingsLockService.canEditLocally(target, key))
		{
			return;
		}
		settingsStore.set(key, value);
	}

	private void writeFeedbackSetting(String key, Object value)
	{
		writeFeedbackSetting(null, key, value);
	}

	private JPanel createGlobalSettingsPanel()
	{
		JPanel settings = new JPanel();
		settings.setLayout(new BoxLayout(settings, BoxLayout.Y_AXIS));
		settings.setBorder(PanelUi.createSectionBorder("XP haptics"));
		PanelUi.addPreferredHeightComponent(settings, feedbackBlockHeader);

		JPanel thresholdRow = new JPanel(new BorderLayout(8, 0));
		thresholdRow.add(new JLabel("Minimum XP gain"), BorderLayout.CENTER);
		thresholdRow.add(minimumXpSpinner, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(settings, thresholdRow);

		JPanel intensityHeader = new JPanel(new BorderLayout());
		intensityHeader.add(new JLabel("Intensity"), BorderLayout.WEST);
		intensityHeader.add(intensityValueLabel, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(settings, intensityHeader);
		PanelUi.addPreferredHeightComponent(settings, intensitySlider);

		JPanel patternRow = new JPanel(new BorderLayout(8, 0));
		patternRow.add(new JLabel("Pattern"), BorderLayout.CENTER);
		patternRow.add(patternComboBox, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(settings, patternRow);

		JPanel durationRow = new JPanel(new BorderLayout(8, 0));
		durationRow.add(new JLabel(PanelUi.DURATION_LABEL), BorderLayout.CENTER);
		durationRow.add(durationSpinner, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(settings, durationRow);

		JPanel levelUpRow = new JPanel(new BorderLayout(8, 0));
		levelUpRow.add(levelUpCheckBox, BorderLayout.CENTER);
		levelUpRow.add(levelUpPatternComboBox, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(settings, levelUpRow);

		JPanel levelUpTestRow = new JPanel(new BorderLayout());
		levelUpTestRow.add(testLevelUpButton, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(settings, levelUpTestRow);

		JPanel milestoneRow = new JPanel(new BorderLayout(8, 0));
		milestoneRow.add(milestoneCheckBox, BorderLayout.CENTER);
		milestoneRow.add(milestonePatternComboBox, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(settings, milestoneRow);

		level99Row.add(level99CheckBox, BorderLayout.CENTER);
		PanelUi.addPreferredHeightComponent(settings, level99Row);

		developerControlsRow.add(previewLevel99Button);
		developerControlsRow.add(resetRogueDiscoveryButton);
		PanelUi.addPreferredHeightComponent(settings, developerControlsRow);
		return settings;
	}

	private void applyCustomPatternLibrary(CustomPatternLibrary updatedLibrary)
	{
		customPatterns = updatedLibrary;
		patternSelection = resolveAndPersist(
			patternSelection,
			HapticScapeSettingKeys.PATTERN_PRESET
		);
		levelUpPatternSelection = resolveAndPersist(
			levelUpPatternSelection,
			HapticScapeSettingKeys.LEVEL_UP_PATTERN_PRESET
		);
		milestonePatternSelection = resolveAndPersist(
			milestonePatternSelection,
			HapticScapeSettingKeys.MILESTONE_PATTERN_PRESET
		);
		profilesPanel.applyCustomPatternLibrary(updatedLibrary);
		alertsPanel.applyCustomPatternLibrary(updatedLibrary);

		updatingPatternSelectors = true;
		try
		{
			PanelUi.setPatternChoices(patternComboBox, patternSelection, updatedLibrary);
			PanelUi.setPatternChoices(
				levelUpPatternComboBox,
				levelUpPatternSelection,
				updatedLibrary
			);
			PanelUi.setPatternChoices(
				milestonePatternComboBox,
				milestonePatternSelection,
				updatedLibrary
			);
		}
		finally
		{
			updatingPatternSelectors = false;
		}
	}

	private HapticPatternSelection resolveAndPersist(
		HapticPatternSelection current,
		String configKey)
	{
		HapticPatternSelection resolved = current.resolveAgainst(customPatterns);
		if (!resolved.equals(current))
		{
			writeFeedbackSetting(
				SettingsLockCatalog.FEEDBACK_BLOCK,
				configKey,
				resolved.toConfigValue()
			);
		}
		return resolved;
	}

	private void refreshInheritedProfileIfReady()
	{
		if (profilesPanel != null)
		{
			profilesPanel.refreshInheritedProfile();
		}
	}

	public void close()
	{
		pageScrollRouting.close();
		settingsLockDraft.removeListener(settingsLockDraftViewportListener);
		remoteSessionManager.removeListener(this);
		settingsLockService.removeListener(this);
		remoteControlPanel.close();
		forgeWorkspacePanel.close();
		developerStatusTimer.stop();
		rogueKeyHook.close();
		roguePanel.close();
		rogueLauncher.close();
		customPatternsPanel.close();
	}

	private void handleRogueKeyPress(GlobalUiHooks.ScopedKeyPress event)
	{
		if (event.isTextInputFocused())
		{
			konamiCodeDetector.reset();
			return;
		}
		if (konamiCodeDetector.acceptKeyCode(event.getKeyCode()))
		{
			SwingUtilities.invokeLater(() -> unlockRogueMode(true));
		}
	}

	private void unlockRogueMode(boolean celebrate)
	{
		boolean firstUnlock = !rogueModeUnlocked;
		boolean unlockStingPlayed = Boolean.parseBoolean(settingsStore.get(HapticScapeSettingKeys.ROGUE_UNLOCK_STING_PLAYED));
		if (!unlockStingPlayed && rogueUnlockSoundAction != null)
		{
			rogueUnlockSoundAction.run();
			settingsStore.set(HapticScapeSettingKeys.ROGUE_UNLOCK_STING_PLAYED, true);
		}
		ensureRogueAccess(true, firstUnlock);
		settingsStore.set(HapticScapeSettingKeys.ROGUE_UNLOCKED, true);
		rogueLauncher.celebrate();
		roguePanel.reveal();
		if (celebrate && rogueFeedbackAction != null)
		{
			rogueFeedbackAction.accept(RogueFeedbackEvent.UNLOCK);
		}
		statusLabel.setText(firstUnlock ? "Rogue Mode unlocked" : "Rogue Mode summoned");
		developerStatusTimer.restart();
	}

	private void ensureRogueAccess(boolean showRogue, boolean animateEmergence)
	{
		if (!rogueModeUnlocked)
		{
			rogueModeUnlocked = true;
			rogueLauncher.showUnlocked(animateEmergence);
		}
		else if (!rogueLauncher.isVisible())
		{
			rogueLauncher.showUnlocked(false);
		}

		if (showRogue)
		{
			showRogueView();
		}
	}

	private void toggleRogueView()
	{
		if (!rogueModeUnlocked)
		{
			return;
		}
		if (rogueViewActive)
		{
			showNormalView();
		}
		else
		{
			showRogueView();
		}
	}

	private void showRogueView()
	{
		forgeWorkspacePanel.leaveWorkspace();
		rogueViewActive = true;
		contentLayout.show(contentHost, ROGUE_CARD);
		rogueLauncher.setActive(true);
		contentHost.revalidate();
		contentHost.repaint();
	}

	private void showNormalView()
	{
		rogueViewActive = false;
		contentLayout.show(contentHost, NORMAL_CARD);
		rogueLauncher.setActive(false);
		contentHost.revalidate();
		contentHost.repaint();
	}

	private void showRemoteView()
	{
		showNormalView();
		if (!forgeDocked)
		{
			forgeWorkspacePanel.leaveWorkspace();
		}
		workspaceShell.showWorkspace(REMOTE_WORKSPACE);
	}

	private void openLiveForge()
	{
		selectControllerWorkspace(false);
		showNormalView();
		forgeWorkspacePanel.showLive();
		if (!forgeDocked)
		{
			workspaceShell.showWorkspace(PATTERNS_WORKSPACE);
		}
	}

	private void openSubjectSettings()
	{
		selectControllerWorkspace(true);
		showNormalView();
		workspaceShell.showWorkspace(GAMEPLAY_WORKSPACE);
	}

	private void handleWorkspaceSelected(String workspace)
	{
		if (PATTERNS_WORKSPACE.equals(workspace) && !forgeDocked)
		{
			forgeWorkspacePanel.enterWorkspace();
		}
		else if (!forgeDocked)
		{
			forgeWorkspacePanel.leaveWorkspace();
		}
	}

	private void setForgeDocked(boolean docked)
	{
		if (forgeDocked == docked)
		{
			return;
		}
		forgeDocked = docked;
		if (docked)
		{
			boolean wasVisible = PATTERNS_WORKSPACE.equals(
				workspaceShell.getSelectedWorkspace()
			);
			patternTabs.remove(forgeWorkspacePanel);
			workspaceShell.setWideDockComponent(forgeWorkspacePanel);
			if (!wasVisible)
			{
				forgeWorkspacePanel.showCompose();
			}
		}
		else
		{
			workspaceShell.clearWideDockComponent(forgeWorkspacePanel);
			if (patternTabs.indexOfComponent(forgeWorkspacePanel) < 0)
			{
				patternTabs.insertTab("Forge", null, forgeWorkspacePanel, null, 0);
			}
			if (PATTERNS_WORKSPACE.equals(workspaceShell.getSelectedWorkspace()))
			{
				patternTabs.setSelectedComponent(forgeWorkspacePanel);
			}
			else
			{
				forgeWorkspacePanel.leaveWorkspace();
			}
		}
		patternTabs.revalidate();
		patternTabs.repaint();
	}

	private void resetRogueDiscovery()
	{
		hideRogueBanner();
		settingsStore.set(HapticScapeSettingKeys.ROGUE_UNLOCK_STING_PLAYED, false);
		statusLabel.setText("Rogue discovery reset - enter the Konami code again");
		developerStatusTimer.restart();
	}

	private void hideRogueBanner()
	{
		showNormalView();
		rogueModeUnlocked = false;
		konamiCodeDetector.reset();
		rogueLauncher.resetLocked();
		settingsStore.set(HapticScapeSettingKeys.ROGUE_UNLOCKED, false);
		statusLabel.setText("Rogue banner hidden - enter the Konami code to reveal it again");
		developerStatusTimer.restart();
	}

	private void unlockSettings()
	{
		JPasswordField passwordField = new JPasswordField(18);
		int choice = JOptionPane.showConfirmDialog(
			this,
			passwordField,
			"Enter settings unlock key",
			JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE
		);
		if (choice != JOptionPane.OK_OPTION)
		{
			return;
		}
		char[] password = passwordField.getPassword();
		try
		{
			if (!settingsLockService.unlock(password))
			{
				JOptionPane.showMessageDialog(
					this,
					"That key did not unlock these settings.",
					"Settings remain locked",
					JOptionPane.ERROR_MESSAGE
				);
			}
			else
			{
				int remaining = settingsLockService.getSnapshot().getLockCount();
				String message = remaining == 0
					? "Settings lock released."
					: "Unlock key accepted. " + remaining
						+ " other lock profile(s) remain.";
				JOptionPane.showMessageDialog(
					this,
					message,
					"Settings unlock complete",
					JOptionPane.INFORMATION_MESSAGE
				);
			}
		}
		catch (RuntimeException e)
		{
			JOptionPane.showMessageDialog(
				this,
				e.getMessage(),
				"Unable to unlock settings",
				JOptionPane.ERROR_MESSAGE
			);
		}
		finally
		{
			Arrays.fill(password, '\0');
			passwordField.setText("");
		}
	}

	private void clearSettingsLockFromDeveloperMode()
	{
		if (!settingsLockService.isLocked())
		{
			return;
		}
		int choice = JOptionPane.showConfirmDialog(
			this,
			"Clear the persistent settings lock? The current feedback settings "
				+ "will not be changed.",
			"Emergency settings-lock recovery",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);
		if (choice == JOptionPane.YES_OPTION)
		{
			settingsLockService.clearAllLocks();
			statusLabel.setText("Settings lock cleared through developer recovery");
			developerStatusTimer.restart();
		}
	}

	private void refreshDeveloperControlsLayout()
	{
		developerControlsRow.revalidate();
		java.awt.Container parent = developerControlsRow.getParent();
		if (parent != null)
		{
			parent.revalidate();
			parent.repaint();
		}
	}

	private void handleDeveloperUnlockClick(MouseEvent event)
	{
		if ((event.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) == 0)
		{
			developerUnlockClickCount = 0;
			return;
		}

		long now = System.nanoTime();
		if (developerUnlockClickCount == 0
			|| now - developerUnlockStartedNanos > DEVELOPER_UNLOCK_WINDOW_NANOS)
		{
			developerUnlockStartedNanos = now;
			developerUnlockClickCount = 1;
		}
		else
		{
			developerUnlockClickCount++;
		}

		if (developerUnlockClickCount < DEVELOPER_UNLOCK_CLICKS)
		{
			return;
		}
		developerUnlockClickCount = 0;
		developerControlsUnlocked = !developerControlsUnlocked;
		developerControlsRow.setVisible(developerControlsUnlocked);
		hideRogueBannerButton.setVisible(developerControlsUnlocked);
		clearSettingsLockButton.setVisible(developerControlsUnlocked);
		refreshDeveloperControlsLayout();
		revalidate();
		repaint();
		statusLabel.setText(developerControlsUnlocked
			? "Developer controls unlocked"
			: "Developer controls locked");
		developerStatusTimer.restart();
	}

	@Override
	public void onRemoteSessionChanged(RemoteSessionSnapshot snapshot)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> applyRemoteSessionState(snapshot));
			return;
		}
		applyRemoteSessionState(snapshot);
	}

	@Override
	public void onSettingsLockChanged(SettingsLockSnapshot locks)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> applySettingsLockState(locks));
			return;
		}
		applySettingsLockState(locks);
	}

	@Override
	public void onRemoteSettingsChanged(RemoteSettingsSnapshot settings)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> applyRemoteSettingsIfActive(settings));
			return;
		}
		applyRemoteSettingsIfActive(settings);
	}

	@Override
	public void onRemoteLockChanged(RemoteLockSnapshot lock)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(this::refreshSettingsAccessMode);
			return;
		}
		refreshSettingsAccessMode();
	}

	@Override
	public void onRemotePermissionsChanged(RemotePermissions permissions)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> applyRemoteSessionState(
				remoteSessionManager.getSnapshot()
			));
			return;
		}
		applyRemoteSessionState(remoteSessionManager.getSnapshot());
	}

	private void applyRemoteSettingsIfActive(RemoteSettingsSnapshot settings)
	{
		// Re-check on the EDT so a queued remote update cannot repaint stale remote
		// values after the participant has already ended the session.
		RemoteSessionSnapshot current = remoteSessionManager.getSnapshot();
		if (current.isParticipantControlled() || isSubjectWorkspaceActive(current))
		{
			ViewportAnchor viewportAnchor = ViewportAnchor.capture(
				pageScrollPane
			);
			viewportAnchor.holdThroughLayout(() ->
			{
				RemoteSessionSnapshot latest = remoteSessionManager.getSnapshot();
				return latest.getRole() == current.getRole()
					&& latest.getState() == current.getState()
					&& (latest.isParticipantControlled()
						|| isSubjectWorkspaceActive(latest));
			});
			displayingRemoteSettings = true;
			applyDisplayedSettings(settings);
		}
	}

	private void applyDisplayedSettings(RemoteSettingsSnapshot settings)
	{
		CustomPatternLibrary displayedPatterns = settings.getCustomPatterns();
		XpFeedbackSettings global = settings.getGlobalXpFeedbackSettings();

		updatingDisplayedSettings = true;
		try
		{
			customPatterns = displayedPatterns;
			minimumXpGain = global.getMinimumXpGain();
			intensityPercent = global.getIntensityPercent();
			durationMillis = global.getDurationMillis();
			patternSelection = global.getPatternSelection();
			levelUpEnabled = settings.isLevelUpFeedbackEnabled();
			milestoneEnabled = settings.isMilestoneFeedbackEnabled();
			level99Enabled = settings.isLevel99CelebrationEnabled();
			levelUpPatternSelection = settings.getLevelUpPatternPreset();
			milestonePatternSelection = settings.getMilestonePatternPreset();

			minimumXpSpinner.setValue(minimumXpGain);
			intensitySlider.setValue(intensityPercent);
			intensityValueLabel.setText(intensityPercent + "%");
			durationSpinner.setValue(durationMillis);
			levelUpCheckBox.setSelected(levelUpEnabled);
			milestoneCheckBox.setSelected(milestoneEnabled);
			level99CheckBox.setSelected(level99Enabled);

			updatingPatternSelectors = true;
			try
			{
				PanelUi.setPatternChoices(patternComboBox, patternSelection, displayedPatterns);
				PanelUi.setPatternChoices(
					levelUpPatternComboBox,
					levelUpPatternSelection,
					displayedPatterns
				);
				PanelUi.setPatternChoices(
					milestonePatternComboBox,
					milestonePatternSelection,
					displayedPatterns
				);
			}
			finally
			{
				updatingPatternSelectors = false;
			}

			skillsPanel.applyDisplayedSelections(
				settings.getHapticSkillSelection(),
				settings.getClickSkillSelection()
			);
			xpClickSettingsPanel.applyDisplayedSettings(settings.getClickerXpSettings());
			profilesPanel.applyDisplayedSettings(
				settings.getSkillFeedbackProfiles(),
				settings.getSkillClickProfiles(),
				displayedPatterns
			);
			alertsPanel.applyDisplayedSettings(
				settings.getNotificationFeedbackSettings(),
				settings.getGenericNotificationClickSequence(),
				settings.getAlertProfiles(),
				settings.getAlertTriggerSettings(),
				settings.getClickerAlertSettings(),
				displayedPatterns
			);
			customPatternsPanel.applyDisplayedLibrary(displayedPatterns);
			musicPanel.applyDisplayedSettings(settings.getMusicSyncSettings());
			phraseRulesPanel.applyDisplayedRules(settings.getClickerPhraseRules());
			applicationStartupPanel.apply(
				settings.isStartWithWindows(),
				settings.isStartMinimized()
			);
		}
		finally
		{
			updatingDisplayedSettings = false;
		}
	}

	private void applySettingsAccessMode(SettingsAccessPolicy.Mode mode)
	{
		boolean feedbackReadOnly = mode.isFeedbackReadOnly();
		boolean forgeAndMusicReadOnly = mode.areForgeAndMusicReadOnly();
		this.remoteReadOnly = feedbackReadOnly;
		boolean editable = !feedbackReadOnly;
		feedbackBlockHeader.refresh();
		boolean globalEditable = editable && !feedbackBlockHeader.isEditLocked();
		minimumXpSpinner.setEnabled(globalEditable);
		intensitySlider.setEnabled(globalEditable);
		intensityValueLabel.setEnabled(globalEditable);
		durationSpinner.setEnabled(globalEditable);
		patternComboBox.setEnabled(globalEditable);
		levelUpLockBinding.refresh();
		milestoneLockBinding.refresh();
		level99LockBinding.refresh();
		levelUpCheckBox.setEnabled(globalEditable && !levelUpLockBinding.isEditLocked());
		levelUpPatternComboBox.setEnabled(globalEditable);
		milestoneCheckBox.setEnabled(globalEditable && !milestoneLockBinding.isEditLocked());
		milestonePatternComboBox.setEnabled(globalEditable);
		level99CheckBox.setEnabled(globalEditable && !level99LockBinding.isEditLocked());
		skillsPanel.setRemoteReadOnly(feedbackReadOnly);
		profilesPanel.setRemoteReadOnly(feedbackReadOnly);
		alertsPanel.setRemoteReadOnly(feedbackReadOnly);
		xpClickSettingsPanel.setRemoteReadOnly(feedbackReadOnly);
		phraseRulesPanel.setRemoteReadOnly(feedbackReadOnly);
		customPatternsPanel.setRemoteReadOnly(forgeAndMusicReadOnly);
		musicPanel.setRemoteReadOnly(forgeAndMusicReadOnly);
		// Tabs, navigation selectors, Casino/Rogue, Intiface connection,
		// Remote Control controls, and Emergency Off intentionally remain usable.
	}

	private void refreshSettingsAccessMode()
	{
		// Always resolve against the latest manager state. Remote callbacks are
		// queued onto Swing's EDT and an older callback must not reapply the active
		// session lock after End session has already returned the subject to LOCAL.
		RemoteSessionSnapshot current = remoteSessionManager.getSnapshot();
		SettingsAccessPolicy.Mode mode = SettingsAccessPolicy.resolve(
			current,
			isSubjectWorkspaceActive(current),
			remoteSessionManager.getPeerPermissions().isSettingsAllowed(),
			settingsLockService.getSnapshot().isLegacyFullLock()
		);
		applySettingsAccessMode(mode);
	}

	private boolean isSubjectWorkspaceActive()
	{
		return isSubjectWorkspaceActive(remoteSessionManager.getSnapshot());
	}

	private boolean isLockSelectionEnabled()
	{
		if (!isSubjectWorkspaceActive())
		{
			return false;
		}
		RemoteLockState lockState = remoteSessionManager.getLockSnapshot().getState();
		return remoteSessionManager.getPeerPermissions().isSettingsAllowed()
			&& (lockState == RemoteLockState.INACTIVE
				|| lockState == RemoteLockState.DECLINED);
	}

	private void preserveSubjectLockDraftViewport()
	{
		if (!SwingUtilities.isEventDispatchThread()
			|| !GAMEPLAY_WORKSPACE.equals(workspaceShell.getSelectedWorkspace())
			|| !isSubjectWorkspaceActive())
		{
			return;
		}

		ViewportAnchor viewportAnchor = ViewportAnchor.capture(pageScrollPane);
		viewportAnchor.holdThroughLayout(() ->
			GAMEPLAY_WORKSPACE.equals(workspaceShell.getSelectedWorkspace())
				&& isSubjectWorkspaceActive());
	}

	private boolean isGlobalFeedbackReadOnly()
	{
		return remoteReadOnly || feedbackBlockHeader.isEditLocked();
	}

	private LockableCheckBoxBinding bindLockableCheckBox(
		JCheckBox checkBox,
		SettingsLockTarget target,
		java.util.function.BooleanSupplier authoritativeValue)
	{
		return new LockableCheckBoxBinding(
			checkBox,
			target,
			settingsLockDraft,
			settingsLockService,
			remoteSessionManager::getLockSnapshot,
			this::isSubjectWorkspaceActive,
			this::isLockSelectionEnabled,
			authoritativeValue
		);
	}

	private boolean isSubjectWorkspaceActive(RemoteSessionSnapshot snapshot)
	{
		return subjectWorkspaceSelected
			&& isControllerSubjectAvailable(snapshot);
	}

	private boolean isControllerSubjectAvailable(RemoteSessionSnapshot snapshot)
	{
		return snapshot.getRole() == RemoteRole.CONTROLLER
			&& (snapshot.getState() == RemoteSessionState.ACTIVE
				|| snapshot.getState() == RemoteSessionState.PEER_EMERGENCY_PAUSED)
			&& remoteSessionManager.getControllerSettingsSnapshot() != null;
	}

	private void selectControllerWorkspace(boolean subject)
	{
		RemoteSessionSnapshot snapshot = remoteSessionManager.getSnapshot();
		if (snapshot.getRole() != RemoteRole.CONTROLLER
			|| snapshot.getState() == RemoteSessionState.LOCAL
			|| snapshot.getState() == RemoteSessionState.DISCONNECTED)
		{
			return;
		}
		if (subject && !isControllerSubjectAvailable(snapshot))
		{
			setControllerWorkspaceTab(false);
			return;
		}

		subjectWorkspaceSelected = subject;
		setControllerWorkspaceTab(subject);
		if (subject)
		{
			RemoteSettingsSnapshot subjectSettings =
				remoteSessionManager.getControllerSettingsSnapshot();
			if (subjectSettings != null)
			{
				displayingRemoteSettings = true;
				applyDisplayedSettings(subjectSettings);
			}
		}
		else
		{
			applyDisplayedSettings(RemoteSettingsSnapshot.capture(config));
			displayingRemoteSettings = false;
		}
		applyRemoteSessionState(snapshot);
	}

	private void setControllerWorkspaceTab(boolean subject)
	{
		updatingControllerWorkspaceTabs = true;
		try
		{
			controllerWorkspaceTabs.setSelectedIndex(
				subject ? SUBJECT_WORKSPACE_INDEX : MINE_WORKSPACE_INDEX
			);
		}
		finally
		{
			updatingControllerWorkspaceTabs = false;
		}
	}

	private void applyRemoteSessionState(RemoteSessionSnapshot snapshot)
	{
		boolean preserveControllerViewport = isContinuingControllerSession(
			appliedRemoteSessionSnapshot,
			snapshot
		);
		ViewportAnchor viewportAnchor = ViewportAnchor.capture(
			pageScrollPane
		);
		if (preserveControllerViewport)
		{
			viewportAnchor.holdThroughLayout(() ->
			{
				RemoteSessionSnapshot current = remoteSessionManager.getSnapshot();
				return current.getRole() == snapshot.getRole()
					&& current.getState() == snapshot.getState();
			});
		}

		boolean participantControlled = snapshot.isParticipantControlled();
		boolean controllerSession = snapshot.getRole() == RemoteRole.CONTROLLER
			&& snapshot.getState() != RemoteSessionState.LOCAL;
		boolean workspaceVisible = controllerSession
			&& snapshot.getState() != RemoteSessionState.DISCONNECTED;
		boolean subjectAvailable = isControllerSubjectAvailable(snapshot);
		stopButton.setText(participantControlled ? "EMERGENCY OFF" : "STOP ALL");
		boolean workspaceWasVisible = controllerWorkspaceTabs.isVisible();
		boolean subjectBecameAvailable = subjectAvailable && !controllerSubjectAvailable;
		controllerSubjectAvailable = subjectAvailable;
		controllerWorkspaceTabs.setVisible(workspaceVisible);
		controllerWorkspaceTabs.setEnabledAt(MINE_WORKSPACE_INDEX, true);
		controllerWorkspaceTabs.setEnabledAt(SUBJECT_WORKSPACE_INDEX, subjectAvailable);
		if (workspaceVisible && subjectAvailable
			&& (!workspaceWasVisible || subjectBecameAvailable))
		{
			subjectWorkspaceSelected = true;
			setControllerWorkspaceTab(true);
			RemoteSettingsSnapshot subjectSettings =
				remoteSessionManager.getControllerSettingsSnapshot();
			if (subjectSettings != null)
			{
				displayingRemoteSettings = true;
				applyDisplayedSettings(subjectSettings);
			}
		}
		else if (workspaceVisible && !subjectAvailable
			&& (!workspaceWasVisible || subjectWorkspaceSelected))
		{
			subjectWorkspaceSelected = false;
			setControllerWorkspaceTab(false);
			if (displayingRemoteSettings)
			{
				applyDisplayedSettings(RemoteSettingsSnapshot.capture(config));
			}
			displayingRemoteSettings = false;
		}
		else if (!workspaceVisible)
		{
			controllerSubjectAvailable = false;
		}

		// Keep the normal HapticScape UI visible during Remote Control. The
		// participant can navigate it and watch remote values change, but cannot
		// mutate remotely authoritative feedback settings.
		if (!participantControlled
			&& !(controllerSession && subjectWorkspaceSelected && subjectAvailable))
		{
			if (!controllerSession && displayingRemoteSettings)
			{
				applyDisplayedSettings(RemoteSettingsSnapshot.capture(config));
			}
			if (!controllerSession)
			{
				displayingRemoteSettings = false;
				subjectWorkspaceSelected = true;
				setControllerWorkspaceTab(true);
			}
		}

		// Connection management stays local even during Remote Control. Manual
		// preview buttons are disabled so they cannot bypass the remote policy.
		boolean connected = latestConnectionSnapshot.getState() == ConnectionState.CONNECTED;
		boolean previewBlocked = participantControlled || isSubjectWorkspaceActive(snapshot);
		profilesPanel.setPreviewAllowed(!previewBlocked);
		alertsPanel.setPreviewAllowed(!previewBlocked);
		customPatternsPanel.setPreviewAllowed(!previewBlocked);
		clickOutputPanel.setPreviewAllowed(!previewBlocked);
		boolean emergencyPaused = snapshot.getState() == RemoteSessionState.EMERGENCY_PAUSED;
		stopButton.setEnabled(participantControlled ? !emergencyPaused : connected);
		testButton.setEnabled(!previewBlocked && connected);
		testLevelUpButton.setEnabled(!previewBlocked && connected);
		previewLevel99Button.setEnabled(
			!previewBlocked && developerControlsUnlocked && connected
		);

		boolean showBanner = snapshot.getState() != RemoteSessionState.LOCAL;
		remoteBanner.setVisible(showBanner);
		remoteBannerLabel.setText("<html>" + snapshot.getMessage()
			+ (participantControlled
				? "<br><small>Changes are saved locally and remain after the session.</small>"
				: "")
			+ "</html>");
		boolean participant = snapshot.getRole() == RemoteRole.PARTICIPANT && showBanner;
		boolean reconnectAvailable = remoteSessionManager.canReconnect();
		remoteEmergencyButton.setEnabled(participant && !emergencyPaused);
		remoteResumeButton.setEnabled(participant && emergencyPaused && !reconnectAvailable);
		remoteReconnectButton.setEnabled(reconnectAvailable);
		remoteEmergencyButton.setVisible(participant && !emergencyPaused);
		remoteResumeButton.setVisible(participant && emergencyPaused);
		remoteReconnectButton.setVisible(reconnectAvailable);
		remoteEndButton.setEnabled(showBanner);
		if (snapshot.getState() == RemoteSessionState.LOCAL)
		{
			settingsLockDraft.clear();
		}
		musicPanel.setCaptureSourceRemote(isSubjectWorkspaceActive(snapshot));
		applySettingsLockState(settingsLockService.getSnapshot());
		revalidate();
		repaint();
		appliedRemoteSessionSnapshot = snapshot;
	}

	private static boolean isContinuingControllerSession(
		RemoteSessionSnapshot previous,
		RemoteSessionSnapshot next)
	{
		return isConnectedController(previous) && isConnectedController(next);
	}

	private static boolean isConnectedController(RemoteSessionSnapshot snapshot)
	{
		return snapshot.getRole() == RemoteRole.CONTROLLER
			&& (snapshot.getState() == RemoteSessionState.ACTIVE
				|| snapshot.getState() == RemoteSessionState.PEER_EMERGENCY_PAUSED);
	}

	private void applySettingsLockState(SettingsLockSnapshot locks)
	{
		boolean locked = locks.isLocked();
		RemoteSessionSnapshot remote = remoteSessionManager.getSnapshot();
		boolean local = remote.getState() == RemoteSessionState.LOCAL;
		boolean participantSession = remote.getRole() == RemoteRole.PARTICIPANT && !local;
		boolean controllerMine = remote.getRole() == RemoteRole.CONTROLLER
			&& remote.getState() != RemoteSessionState.LOCAL
			&& !isSubjectWorkspaceActive(remote);
		boolean localWorkspace = local || controllerMine;

		settingsLockLabel.setText(participantSession
			? POST_SESSION_LOCK_MESSAGE
			: locks.isLegacyFullLock()
				? SETTINGS_LOCKED_MESSAGE
				: "<html><b>Settings locked</b><br>"
					+ locks.getTargets().size() + " selected controls</html>");
		settingsLockBanner.setVisible(locked && (localWorkspace || participantSession));
		unlockSettingsButton.setVisible(locked && localWorkspace);
		unlockSettingsButton.setEnabled(locked && localWorkspace);
		clearSettingsLockButton.setEnabled(locked);
		applicationStartupPanel.refreshLockState();
		refreshSettingsAccessMode();
		revalidate();
		repaint();
	}

	public void updateConnection(ConnectionSnapshot snapshot)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> applyState(snapshot));
			return;
		}
		applyState(snapshot);
	}

	public void showInputError(String message)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> showInputError(message));
			return;
		}
		statusLabel.setText(message);
		connectButton.setEnabled(true);
	}

	public void configureAudioApplicationCapture(
		Supplier<java.util.List<AudioCaptureApplication>> applicationSupplier,
		Consumer<AudioCaptureMode> modeListener,
		Consumer<AudioCaptureApplication> applicationListener)
	{
		musicPanel.configureApplicationCapture(
			applicationSupplier,
			modeListener,
			applicationListener
		);
	}

	private void applyState(ConnectionSnapshot snapshot)
	{
		latestConnectionSnapshot = snapshot;
		if (!developerStatusTimer.isRunning())
		{
			statusLabel.setText(snapshot.getMessage());
		}
		statusLabel.setToolTipText(snapshot.getMessage());
		connectionIndicator.setForeground(connectionColor(snapshot.getState()));
		deviceSummaryLabel.setText(deviceSummary(snapshot));
		deviceSummaryLabel.setToolTipText(deviceSummaryLabel.getText());

		deviceModel.clear();
		for (DeviceInfo device : snapshot.getDevices())
		{
			deviceModel.addElement(device);
		}

		ConnectionState state = snapshot.getState();
		boolean connected = state == ConnectionState.CONNECTED;
		connectButton.setEnabled(state == ConnectionState.DISCONNECTED);
		disconnectButton.setEnabled(state == ConnectionState.CONNECTING || connected);
		testButton.setEnabled(connected);
		testLevelUpButton.setEnabled(connected);
		profilesPanel.setConnected(connected);
		alertsPanel.setConnected(connected);
		customPatternsPanel.setConnected(connected);
		stopButton.setEnabled(connected);
		applyRemoteSessionState(remoteSessionManager.getSnapshot());
	}

	private static java.awt.Color connectionColor(ConnectionState state)
	{
		if (state == ConnectionState.CONNECTED)
		{
			return HapticScapeTheme.SUCCESS;
		}
		if (state == ConnectionState.CONNECTING || state == ConnectionState.DISCONNECTING)
		{
			return HapticScapeTheme.ACCENT;
		}
		return HapticScapeTheme.MUTED_TEXT;
	}

	private static String deviceSummary(ConnectionSnapshot snapshot)
	{
		if (snapshot.getDevices().isEmpty())
		{
			return "No device connected";
		}
		if (snapshot.getDevices().size() == 1)
		{
			return snapshot.getDevices().get(0).getName();
		}
		return snapshot.getDevices().size() + " devices";
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}

}
