package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.HapticScapeSettingsSource;
import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.ashy0019.hapticscape.music.AudioCaptureMode;
import com.ashy0019.hapticscape.music.MusicResponse;
import com.ashy0019.hapticscape.music.MusicSyncSettings;
import com.ashy0019.hapticscape.music.MusicSyncSnapshot;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.CardLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JSlider;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

final class MusicPanel extends JPanel
{
	private final SettingsChangeSink settingsSink;
	private final SettingsChangeSink localSettingsSink;
	private final Consumer<MusicSyncSettings> settingsListener;
	private final Supplier<List<AudioCaptureEndpoint>> endpointSupplier;
	private final Consumer<AudioCaptureEndpoint> endpointListener;
	private Supplier<List<AudioCaptureApplication>> applicationSupplier =
		java.util.Collections::emptyList;
	private Consumer<AudioCaptureMode> modeListener = ignored -> { };
	private Consumer<AudioCaptureApplication> applicationListener = ignored -> { };
	private final JToggleButton enabledButton = new JToggleButton("Start music sync");
	private final JComboBox<MusicResponse> responseComboBox =
		new JComboBox<>(MusicResponse.values());
	private final JSlider sensitivitySlider = new JSlider(25, 200);
	private final JSlider minimumSlider = new JSlider(0, 100);
	private final JSlider maximumSlider = new JSlider(0, 100);
	private final JLabel sensitivityValue = new JLabel();
	private final JLabel minimumValue = new JLabel();
	private final JLabel maximumValue = new JLabel();
	private final JLabel rangeValue = new JLabel();
	private final JLabel responseHint = new JLabel();
	private final JLabel statusLabel = new JLabel("Music sync is off");
	private final JProgressBar outputMeter = new JProgressBar(0, 100);
	private final JComboBox<AudioCaptureEndpoint> audioSourceComboBox = new JComboBox<>();
	private final JComboBox<AudioCaptureMode> captureModeComboBox =
		new JComboBox<>(AudioCaptureMode.values());
	private final JComboBox<AudioCaptureApplication> applicationComboBox = new JComboBox<>();
	private final JButton refreshSourcesButton = new JButton("Refresh");
	private final JPanel sourceControls = new JPanel();
	private final CardLayout sourcePickerLayout = new CardLayout();
	private final JPanel sourcePickerCards = new JPanel(sourcePickerLayout);
	private final JLabel sourceHint = new JLabel(
		"Local to this computer; never shared remotely."
	);
	private final JLabel remoteSourceNotice = new JLabel(
		"Audio source is chosen on the participant's computer."
	);
	private boolean updating;
	private boolean remoteReadOnly;
	private boolean captureSourceRemote;
	private boolean sourceScanRunning;
	private int sourceScanGeneration;
	private AudioCaptureMode selectedCaptureMode;
	private AudioCaptureEndpoint selectedCaptureEndpoint;
	private AudioCaptureApplication selectedCaptureApplication;
	private MusicSyncSnapshot.State displayedState = MusicSyncSnapshot.State.DISABLED;
	private String displayedMessage = "Music sync is off";
	private int displayedLevel;

	MusicPanel(
		HapticScapeSettingsSource config,
		SettingsChangeSink settingsSink,
		Consumer<MusicSyncSettings> settingsListener)
	{
		this(
			config,
			settingsSink,
			settingsSink,
			settingsListener,
			() -> java.util.Collections.singletonList(AudioCaptureEndpoint.systemDefault()),
			ignored -> { }
		);
	}

	MusicPanel(
		HapticScapeSettingsSource config,
		SettingsChangeSink settingsSink,
		SettingsChangeSink localSettingsSink,
		Consumer<MusicSyncSettings> settingsListener,
		Supplier<List<AudioCaptureEndpoint>> endpointSupplier,
		Consumer<AudioCaptureEndpoint> endpointListener)
	{
		this.settingsSink = settingsSink;
		this.localSettingsSink = localSettingsSink;
		this.settingsListener = settingsListener;
		this.endpointSupplier = Objects.requireNonNull(endpointSupplier, "endpointSupplier");
		this.endpointListener = Objects.requireNonNull(endpointListener, "endpointListener");
		selectedCaptureEndpoint = AudioCaptureEndpoint.fromPersisted(
			config.musicCaptureEndpointId(),
			config.musicCaptureEndpointName()
		);
		selectedCaptureMode = AudioCaptureMode.fromConfigValue(config.musicCaptureMode());
		selectedCaptureApplication = AudioCaptureApplication.fromPersisted(
			config.musicCaptureApplicationId(),
			config.musicCaptureApplicationName()
		);
		setName("musicSyncWorkspace");
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));

		enabledButton.setName("musicSyncToggle");
		enabledButton.setSelected(config.musicSyncEnabled());
		responseComboBox.setName("musicResponse");
		responseComboBox.setSelectedItem(parseResponse(config.musicResponse()));
		PanelUi.setFixedWidth(responseComboBox, PanelUi.SELECTOR_CONTROL_WIDTH);
		sensitivitySlider.setName("musicSensitivity");
		minimumSlider.setName("musicMinimumIntensity");
		maximumSlider.setName("musicMaximumIntensity");
		audioSourceComboBox.setName("musicAudioSource");
		captureModeComboBox.setName("musicCaptureMode");
		applicationComboBox.setName("musicAudioApplication");
		refreshSourcesButton.setName("musicAudioSourceRefresh");
		sourceControls.setName("musicAudioSourceLocalControls");
		remoteSourceNotice.setName("musicAudioSourceRemoteNotice");
		PanelUi.setFixedWidth(audioSourceComboBox, PanelUi.SELECTOR_CONTROL_WIDTH);
		PanelUi.setFixedWidth(captureModeComboBox, PanelUi.SELECTOR_CONTROL_WIDTH);
		PanelUi.setFixedWidth(applicationComboBox, PanelUi.SELECTOR_CONTROL_WIDTH);
		captureModeComboBox.setSelectedItem(selectedCaptureMode);
		audioSourceComboBox.addItem(selectedCaptureEndpoint);
		audioSourceComboBox.setSelectedItem(selectedCaptureEndpoint);
		audioSourceComboBox.setToolTipText(selectedCaptureEndpoint.getMenuLabel());
		if (selectedCaptureApplication != null)
		{
			applicationComboBox.addItem(selectedCaptureApplication);
			applicationComboBox.setSelectedItem(selectedCaptureApplication);
			applicationComboBox.setToolTipText(selectedCaptureApplication.getMenuLabel());
		}
		sensitivitySlider.setValue(clamp(config.musicSensitivityPercent(), 25, 200));
		minimumSlider.setValue(clamp(config.musicMinimumIntensityPercent(), 0, 100));
		maximumSlider.setValue(clamp(config.musicMaximumIntensityPercent(), 0, 100));
		if (minimumSlider.getValue() > maximumSlider.getValue())
		{
			minimumSlider.setValue(maximumSlider.getValue());
		}

		statusLabel.setName("musicCaptureDetail");
		outputMeter.setName("musicOutputMeter");
		outputMeter.setStringPainted(true);
		outputMeter.setString("Output 0%");
		PanelUi.reserveSingleLineTextHeight(outputMeter, 2);

		ResponsiveColumnsPanel sections = new ResponsiveColumnsPanel(
			capturePanel(),
			responsePanel(),
			outputPanel()
		);
		sections.setName("musicSyncSections");
		add(sections, BorderLayout.CENTER);

		refreshLabels();
		refreshEnabledState();
		configureListeners();
		refreshAudioSources();
	}

	void configureApplicationCapture(
		Supplier<List<AudioCaptureApplication>> applicationSupplier,
		Consumer<AudioCaptureMode> modeListener,
		Consumer<AudioCaptureApplication> applicationListener)
	{
		this.applicationSupplier = Objects.requireNonNull(
			applicationSupplier,
			"applicationSupplier"
		);
		this.modeListener = Objects.requireNonNull(modeListener, "modeListener");
		this.applicationListener = Objects.requireNonNull(
			applicationListener,
			"applicationListener"
		);
		if (selectedCaptureMode == AudioCaptureMode.APPLICATION)
		{
			refreshAudioSources();
		}
	}

	MusicSyncSettings getSettings()
	{
		return new MusicSyncSettings(
			enabledButton.isSelected(),
			(MusicResponse) responseComboBox.getSelectedItem(),
			sensitivitySlider.getValue(),
			minimumSlider.getValue(),
			maximumSlider.getValue()
		);
	}

	void applyDisplayedSettings(MusicSyncSettings displayed)
	{
		updating = true;
		try
		{
			enabledButton.setSelected(displayed.isEnabled());
			responseComboBox.setSelectedItem(displayed.getResponse());
			sensitivitySlider.setValue(displayed.getSensitivityPercent());
			minimumSlider.setValue(displayed.getMinimumIntensityPercent());
			maximumSlider.setValue(displayed.getMaximumIntensityPercent());
			refreshLabels();
		}
		finally
		{
			updating = false;
		}
		refreshEnabledState();
	}

	void setRemoteReadOnly(boolean remoteReadOnly)
	{
		this.remoteReadOnly = remoteReadOnly;
		refreshEnabledState();
	}

	void setCaptureSourceRemote(boolean captureSourceRemote)
	{
		this.captureSourceRemote = captureSourceRemote;
		sourceControls.setVisible(!captureSourceRemote);
		remoteSourceNotice.setVisible(captureSourceRemote);
		refreshEnabledState();
	}

	AudioCaptureEndpoint getSelectedCaptureEndpoint()
	{
		return selectedCaptureEndpoint;
	}

	AudioCaptureMode getSelectedCaptureMode()
	{
		return selectedCaptureMode;
	}

	AudioCaptureApplication getSelectedCaptureApplication()
	{
		return selectedCaptureApplication;
	}

	void disableMusicSync()
	{
		if (!enabledButton.isSelected())
		{
			return;
		}
		enabledButton.setSelected(false);
		persist(HapticScapeSettingKeys.MUSIC_SYNC_ENABLED, false);
		refreshEnabledState();
		settingsListener.accept(getSettings());
	}

	void updateSnapshot(MusicSyncSnapshot snapshot)
	{
		if (!SwingUtilities.isEventDispatchThread())
		{
			SwingUtilities.invokeLater(() -> updateSnapshot(snapshot));
			return;
		}
		if (snapshot.getState() != displayedState)
		{
			displayedState = snapshot.getState();
			refreshEnabledState();
		}
		if (!snapshot.getMessage().equals(displayedMessage))
		{
			displayedMessage = snapshot.getMessage();
			statusLabel.setText(displayedMessage);
			statusLabel.setToolTipText(displayedMessage);
		}
		if (snapshot.getLevelPercent() != displayedLevel)
		{
			displayedLevel = snapshot.getLevelPercent();
			outputMeter.setValue(displayedLevel);
			outputMeter.setString("Output " + displayedLevel + "%");
		}
	}

	private JPanel capturePanel()
	{
		JPanel panel = verticalSection("Capture", "musicCaptureSection");
		sourceControls.setLayout(new BoxLayout(sourceControls, BoxLayout.Y_AXIS));
		PanelUi.addPreferredHeightComponent(
			sourceControls,
			row("Capture mode", captureModeComboBox)
		);
		JPanel endpointPicker = new JPanel(new BorderLayout(6, 0));
		endpointPicker.add(audioSourceComboBox, BorderLayout.CENTER);
		JPanel applicationPicker = new JPanel(new BorderLayout(6, 0));
		applicationPicker.add(applicationComboBox, BorderLayout.CENTER);
		sourcePickerCards.add(
			row("Audio source", endpointPicker),
			AudioCaptureMode.OUTPUT.name()
		);
		sourcePickerCards.add(
			row("Application", applicationPicker),
			AudioCaptureMode.APPLICATION.name()
		);
		JPanel pickerWithRefresh = new JPanel(new BorderLayout(6, 0));
		pickerWithRefresh.add(sourcePickerCards, BorderLayout.CENTER);
		pickerWithRefresh.add(refreshSourcesButton, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(sourceControls, pickerWithRefresh);
		showSelectedCaptureMode();
		sourceHint.setBorder(BorderFactory.createEmptyBorder(3, 1, 6, 1));
		PanelUi.addPreferredHeightComponent(sourceControls, sourceHint);
		panel.add(sourceControls);
		remoteSourceNotice.setBorder(BorderFactory.createEmptyBorder(3, 1, 7, 1));
		remoteSourceNotice.setVisible(false);
		PanelUi.addPreferredHeightComponent(panel, remoteSourceNotice);
		PanelUi.addPreferredHeightComponent(panel, outputMeter);

		statusLabel.setBorder(BorderFactory.createEmptyBorder(5, 1, 5, 1));
		PanelUi.addPreferredHeightComponent(panel, statusLabel);
		PanelUi.addPreferredHeightComponent(panel, enabledButton);

		JLabel privacy = new JLabel("Analyzed locally; audio is never recorded.");
		privacy.setToolTipText("Audio samples remain in memory on this computer");
		privacy.setBorder(BorderFactory.createEmptyBorder(6, 1, 0, 1));
		PanelUi.addPreferredHeightComponent(panel, privacy);
		return panel;
	}

	private JPanel responsePanel()
	{
		JPanel panel = verticalSection("Response", "musicResponseSection");
		PanelUi.addPreferredHeightComponent(panel, row("Feel", responseComboBox));
		responseHint.setBorder(BorderFactory.createEmptyBorder(5, 1, 7, 1));
		PanelUi.addPreferredHeightComponent(panel, responseHint);
		PanelUi.addPreferredHeightComponent(panel, row("Sensitivity", sensitivityValue));
		sensitivitySlider.setToolTipText(
			"Raises or lowers how strongly HapticScape reacts to captured audio"
		);
		PanelUi.addPreferredHeightComponent(panel, sensitivitySlider);
		return panel;
	}

	private JPanel outputPanel()
	{
		JPanel panel = verticalSection("Output range", "musicOutputSection");
		PanelUi.addPreferredHeightComponent(panel, row("Active range", rangeValue));
		panel.add(Box.createVerticalStrut(5));
		PanelUi.addPreferredHeightComponent(panel, row("Minimum", minimumValue));
		minimumSlider.setToolTipText("Lowest non-silent haptic intensity");
		PanelUi.addPreferredHeightComponent(panel, minimumSlider);
		panel.add(Box.createVerticalStrut(5));
		PanelUi.addPreferredHeightComponent(panel, row("Maximum", maximumValue));
		maximumSlider.setToolTipText("Highest haptic intensity music sync may request");
		PanelUi.addPreferredHeightComponent(panel, maximumSlider);
		return panel;
	}

	private void configureListeners()
	{
		refreshSourcesButton.addActionListener(event -> refreshAudioSources());
		captureModeComboBox.addActionListener(event ->
		{
			if (updating || captureSourceRemote)
			{
				return;
			}
			AudioCaptureMode selected =
				(AudioCaptureMode) captureModeComboBox.getSelectedItem();
			if (selected == null)
			{
				return;
			}
			selectedCaptureMode = selected;
			localSettingsSink.set(HapticScapeSettingKeys.MUSIC_CAPTURE_MODE, selected.name());
			modeListener.accept(selected);
			showSelectedCaptureMode();
			refreshEnabledState();
			refreshAudioSources();
		});
		audioSourceComboBox.addActionListener(event ->
		{
			if (updating || captureSourceRemote)
			{
				return;
			}
			AudioCaptureEndpoint selected =
				(AudioCaptureEndpoint) audioSourceComboBox.getSelectedItem();
			if (selected == null)
			{
				return;
			}
			selectedCaptureEndpoint = selected;
			audioSourceComboBox.setToolTipText(selected.getMenuLabel());
			localSettingsSink.set(
				HapticScapeSettingKeys.MUSIC_CAPTURE_ENDPOINT_ID,
				selected.getId()
			);
			localSettingsSink.set(
				HapticScapeSettingKeys.MUSIC_CAPTURE_ENDPOINT_NAME,
				selected.getDisplayName()
			);
			endpointListener.accept(selected);
		});
		applicationComboBox.addActionListener(event ->
		{
			if (updating || captureSourceRemote)
			{
				return;
			}
			AudioCaptureApplication selected =
				(AudioCaptureApplication) applicationComboBox.getSelectedItem();
			if (selected == null)
			{
				return;
			}
			selectedCaptureApplication = selected;
			applicationComboBox.setToolTipText(selected.getMenuLabel());
			localSettingsSink.set(
				HapticScapeSettingKeys.MUSIC_CAPTURE_APPLICATION_ID,
				selected.getId()
			);
			localSettingsSink.set(
				HapticScapeSettingKeys.MUSIC_CAPTURE_APPLICATION_NAME,
				selected.getDisplayName()
			);
			applicationListener.accept(selected);
			refreshEnabledState();
		});
		enabledButton.addActionListener(event ->
		{
			if (updating || remoteReadOnly)
			{
				return;
			}
			if (displayedState == MusicSyncSnapshot.State.ERROR)
			{
				// Capture is already stopped. Keep the setting enabled so this click
				// retries opening the source instead of requiring Stop, then Start.
				enabledButton.setSelected(true);
			}
			persist(HapticScapeSettingKeys.MUSIC_SYNC_ENABLED, enabledButton.isSelected());
			refreshEnabledState();
			fireSettings();
		});
		responseComboBox.addActionListener(event ->
		{
			refreshResponseHint();
			if (updating || remoteReadOnly)
			{
				return;
			}
			MusicResponse response = (MusicResponse) responseComboBox.getSelectedItem();
			persist(HapticScapeSettingKeys.MUSIC_RESPONSE, response.name());
			fireSettings();
		});
		sensitivitySlider.addChangeListener(event ->
		{
			refreshLabels();
			if (updating || remoteReadOnly)
			{
				return;
			}
			if (!sensitivitySlider.getValueIsAdjusting())
			{
				persist(HapticScapeSettingKeys.MUSIC_SENSITIVITY_PERCENT,
					sensitivitySlider.getValue());
				fireSettings();
			}
		});
		minimumSlider.addChangeListener(event ->
		{
			if (updating || remoteReadOnly)
			{
				return;
			}
			if (minimumSlider.getValue() > maximumSlider.getValue())
			{
				updating = true;
				maximumSlider.setValue(minimumSlider.getValue());
				updating = false;
			}
			refreshLabels();
			if (!minimumSlider.getValueIsAdjusting())
			{
				persist(HapticScapeSettingKeys.MUSIC_MINIMUM_INTENSITY_PERCENT,
					minimumSlider.getValue());
				persist(HapticScapeSettingKeys.MUSIC_MAXIMUM_INTENSITY_PERCENT,
					maximumSlider.getValue());
				fireSettings();
			}
		});
		maximumSlider.addChangeListener(event ->
		{
			if (updating || remoteReadOnly)
			{
				return;
			}
			if (maximumSlider.getValue() < minimumSlider.getValue())
			{
				updating = true;
				minimumSlider.setValue(maximumSlider.getValue());
				updating = false;
			}
			refreshLabels();
			if (!maximumSlider.getValueIsAdjusting())
			{
				persist(HapticScapeSettingKeys.MUSIC_MINIMUM_INTENSITY_PERCENT,
					minimumSlider.getValue());
				persist(HapticScapeSettingKeys.MUSIC_MAXIMUM_INTENSITY_PERCENT,
					maximumSlider.getValue());
				fireSettings();
			}
		});
	}

	private void refreshLabels()
	{
		sensitivityValue.setText(sensitivitySlider.getValue() + "%");
		minimumValue.setText(minimumSlider.getValue() + "%");
		maximumValue.setText(maximumSlider.getValue() + "%");
		rangeValue.setText(minimumSlider.getValue() + "–" + maximumSlider.getValue() + "%");
		refreshResponseHint();
	}

	private void refreshResponseHint()
	{
		MusicResponse response = (MusicResponse) responseComboBox.getSelectedItem();
		if (response == MusicResponse.SMOOTH)
		{
			responseHint.setText("Even movement with gradual changes.");
		}
		else if (response == MusicResponse.PUNCHY)
		{
			responseHint.setText("Fast attacks with pronounced hits.");
		}
		else
		{
			responseHint.setText("Balanced motion with clear rhythm.");
		}
	}

	private void refreshEnabledState()
	{
		boolean editable = !remoteReadOnly;
		boolean captureReady = selectedCaptureMode == AudioCaptureMode.OUTPUT
			|| selectedCaptureApplication != null;
		enabledButton.setEnabled(editable && (enabledButton.isSelected() || captureReady));
		if (displayedState == MusicSyncSnapshot.State.ERROR && enabledButton.isSelected())
		{
			enabledButton.setText("Retry music sync");
		}
		else
		{
			enabledButton.setText(enabledButton.isSelected()
				? "Stop music sync"
				: "Start music sync");
		}
		responseComboBox.setEnabled(editable);
		sensitivitySlider.setEnabled(editable);
		minimumSlider.setEnabled(editable);
		maximumSlider.setEnabled(editable);
		audioSourceComboBox.setEnabled(!captureSourceRemote);
		captureModeComboBox.setEnabled(!captureSourceRemote);
		applicationComboBox.setEnabled(!captureSourceRemote);
		refreshSourcesButton.setEnabled(!captureSourceRemote && !sourceScanRunning);
	}

	private void refreshAudioSources()
	{
		if (captureSourceRemote)
		{
			return;
		}
		if (selectedCaptureMode == AudioCaptureMode.APPLICATION)
		{
			refreshApplications();
			return;
		}
		sourceScanRunning = true;
		int scanGeneration = ++sourceScanGeneration;
		refreshSourcesButton.setEnabled(false);
		refreshSourcesButton.setText("Scanning…");
		javax.swing.Timer timeout = new javax.swing.Timer(5_000, event ->
		{
			if (sourceScanRunning && sourceScanGeneration == scanGeneration)
			{
				sourceScanRunning = false;
				refreshSourcesButton.setText("Refresh");
				refreshSourcesButton.setEnabled(!captureSourceRemote);
				sourceHint.setText("Audio-source scan timed out. Press Refresh to try again.");
			}
		});
		timeout.setRepeats(false);
		timeout.start();
		Thread worker = new Thread(() ->
		{
			try
			{
				List<AudioCaptureEndpoint> endpoints = endpointSupplier.get();
				SwingUtilities.invokeLater(() ->
				{
					if (sourceScanRunning && sourceScanGeneration == scanGeneration)
					{
						timeout.stop();
						applyAvailableSources(endpoints);
					}
				});
			}
			catch (RuntimeException failure)
			{
				SwingUtilities.invokeLater(() ->
				{
					if (!sourceScanRunning || sourceScanGeneration != scanGeneration)
					{
						return;
					}
					timeout.stop();
					sourceScanRunning = false;
					refreshSourcesButton.setText("Refresh");
					refreshSourcesButton.setEnabled(!captureSourceRemote);
					sourceHint.setText("Unable to list audio outputs.");
					sourceHint.setToolTipText(failure.getMessage());
				});
			}
		}, "hapticscape-audio-endpoints");
		worker.setDaemon(true);
		worker.start();
	}

	void applyAvailableSources(List<AudioCaptureEndpoint> discovered)
	{
		List<AudioCaptureEndpoint> endpoints = new ArrayList<>();
		if (discovered != null)
		{
			endpoints.addAll(discovered);
		}
		if (endpoints.stream().noneMatch(AudioCaptureEndpoint::isSystemDefault))
		{
			endpoints.add(0, AudioCaptureEndpoint.systemDefault());
		}

		AudioCaptureEndpoint matched = endpoints.stream()
			.filter(endpoint -> endpoint.equals(selectedCaptureEndpoint))
			.findFirst()
			.orElse(null);
		if (matched == null)
		{
			matched = AudioCaptureEndpoint.unavailable(
				selectedCaptureEndpoint.getId(),
				selectedCaptureEndpoint.getDisplayName()
			);
			endpoints.add(matched);
		}

		updating = true;
		try
		{
			audioSourceComboBox.removeAllItems();
			for (AudioCaptureEndpoint endpoint : endpoints)
			{
				audioSourceComboBox.addItem(endpoint);
			}
			audioSourceComboBox.setSelectedItem(matched);
			selectedCaptureEndpoint = matched;
		}
		finally
		{
			updating = false;
		}
		endpointListener.accept(matched);
		sourceScanRunning = false;
		refreshSourcesButton.setText("Refresh");
		refreshSourcesButton.setEnabled(!captureSourceRemote);
		audioSourceComboBox.setToolTipText(matched.getMenuLabel());
		sourceHint.setText(matched.isAvailable()
			? "Local to this computer; never shared remotely."
			: "Selected source is unavailable. Choose another output or refresh.");
		sourceHint.setToolTipText(null);
	}

	private void refreshApplications()
	{
		sourceScanRunning = true;
		int scanGeneration = ++sourceScanGeneration;
		refreshSourcesButton.setEnabled(false);
		refreshSourcesButton.setText("Scanning…");
		javax.swing.Timer timeout = new javax.swing.Timer(5_000, event ->
		{
			if (sourceScanRunning && sourceScanGeneration == scanGeneration)
			{
				sourceScanRunning = false;
				refreshSourcesButton.setText("Refresh");
				refreshSourcesButton.setEnabled(!captureSourceRemote);
				sourceHint.setText("Application scan timed out. Press Refresh to try again.");
			}
		});
		timeout.setRepeats(false);
		timeout.start();
		Thread worker = new Thread(() ->
		{
			try
			{
				List<AudioCaptureApplication> applications = applicationSupplier.get();
				SwingUtilities.invokeLater(() ->
				{
					if (sourceScanRunning && sourceScanGeneration == scanGeneration)
					{
						timeout.stop();
						applyAvailableApplications(applications);
					}
				});
			}
			catch (RuntimeException failure)
			{
				SwingUtilities.invokeLater(() ->
				{
					if (!sourceScanRunning || sourceScanGeneration != scanGeneration)
					{
						return;
					}
					timeout.stop();
					sourceScanRunning = false;
					refreshSourcesButton.setText("Refresh");
					refreshSourcesButton.setEnabled(!captureSourceRemote);
					sourceHint.setText("Unable to list playback applications.");
					sourceHint.setToolTipText(failure.getMessage());
				});
			}
		}, "hapticscape-audio-applications");
		worker.setDaemon(true);
		worker.start();
	}

	void applyAvailableApplications(List<AudioCaptureApplication> discovered)
	{
		List<AudioCaptureApplication> applications = new ArrayList<>();
		if (discovered != null)
		{
			applications.addAll(discovered);
		}
		AudioCaptureApplication matched = selectedCaptureApplication == null ? null
			: applications.stream()
				.filter(application -> application.equals(selectedCaptureApplication))
				.findFirst()
				.orElse(null);
		if (matched == null && selectedCaptureApplication != null)
		{
			matched = AudioCaptureApplication.unavailable(
				selectedCaptureApplication.getId(),
				selectedCaptureApplication.getDisplayName()
			);
			applications.add(matched);
		}
		if (matched == null && !applications.isEmpty())
		{
			matched = applications.get(0);
		}
		boolean newlySelected = selectedCaptureApplication == null && matched != null;

		updating = true;
		try
		{
			applicationComboBox.removeAllItems();
			for (AudioCaptureApplication application : applications)
			{
				applicationComboBox.addItem(application);
			}
			if (matched != null)
			{
				applicationComboBox.setSelectedItem(matched);
				selectedCaptureApplication = matched;
			}
		}
		finally
		{
			updating = false;
		}
		if (matched != null)
		{
			selectedCaptureApplication = matched;
			applicationComboBox.setToolTipText(matched.getMenuLabel());
			if (newlySelected)
			{
				localSettingsSink.set(
					HapticScapeSettingKeys.MUSIC_CAPTURE_APPLICATION_ID,
					matched.getId()
				);
				localSettingsSink.set(
					HapticScapeSettingKeys.MUSIC_CAPTURE_APPLICATION_NAME,
					matched.getDisplayName()
				);
			}
			applicationListener.accept(matched);
		}
		sourceScanRunning = false;
		refreshSourcesButton.setText("Refresh");
		refreshSourcesButton.setEnabled(!captureSourceRemote);
		sourceHint.setText(applications.isEmpty()
			? "No mixer applications found. Start playback, then press Refresh."
			: matched != null && !matched.isAvailable()
				? "Selected application is not currently playing. Music Sync will wait for it."
				: "Local to this computer; never shared remotely.");
		sourceHint.setToolTipText(null);
		refreshEnabledState();
	}

	private void showSelectedCaptureMode()
	{
		sourcePickerLayout.show(sourcePickerCards, selectedCaptureMode.name());
	}

	private void fireSettings()
	{
		settingsListener.accept(getSettings());
	}

	private void persist(String key, Object value)
	{
		settingsSink.set(key, value);
	}

	private static JPanel verticalSection(String title, String name)
	{
		JPanel panel = new JPanel();
		panel.setName(name);
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.setBorder(PanelUi.createSectionBorder(title));
		panel.setAlignmentY(Component.TOP_ALIGNMENT);
		return panel;
	}

	private static JPanel row(String name, Component control)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.add(new JLabel(name), BorderLayout.CENTER);
		row.add(control, BorderLayout.EAST);
		return row;
	}

	private static MusicResponse parseResponse(String value)
	{
		try
		{
			return MusicResponse.valueOf(value);
		}
		catch (IllegalArgumentException | NullPointerException ignored)
		{
			return MusicResponse.RHYTHMIC;
		}
	}

	private static int clamp(int value, int minimum, int maximum)
	{
		return Math.max(minimum, Math.min(maximum, value));
	}
}
