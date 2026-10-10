package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.remote.SettingsLockCatalog;
import com.ashy0019.hapticscape.remote.SettingsLockService;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.ashy0019.hapticscape.remote.RemoteLockSnapshot;
import java.awt.event.ActionEvent;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JPanel;

/** Local and remotely viewable application startup preferences. */
final class ApplicationStartupPanel extends JPanel
{
	private final JCheckBox launch = new JCheckBox("Start HapticScape at sign-in");
	private final JCheckBox minimized = new JCheckBox("Start minimized to the system tray");
	private final BiConsumer<String, Object> writer;
	private final BooleanSupplier subjectWorkspace;
	private final LockableCheckBoxBinding launchLock;
	private final LockableCheckBoxBinding minimizedLock;
	private boolean applying;
	private boolean authoritativeLaunch;
	private boolean authoritativeMinimized;

	ApplicationStartupPanel(
		BiConsumer<String, Object> writer,
		SettingsLockService locks,
		SettingsLockDraft draft,
		Supplier<RemoteLockSnapshot> remoteLocks,
		BooleanSupplier subjectWorkspace,
		BooleanSupplier lockSelectionEnabled)
	{
		this.writer = writer;
		this.subjectWorkspace = subjectWorkspace;
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(PanelUi.createSectionBorder("Application startup"));
		launch.setName("startWithWindows");
		minimized.setName("startMinimized");
		launch.setToolTipText("Launch the normal HapticScape client when you sign in");
		minimized.setToolTipText("Launch silently; use the tray icon to open the window");
		launchLock = new LockableCheckBoxBinding(
			launch, () -> SettingsLockCatalog.STARTUP_BEHAVIOR, draft, locks,
			remoteLocks,
			subjectWorkspace, lockSelectionEnabled, () -> authoritativeLaunch
		);
		minimizedLock = new LockableCheckBoxBinding(
			minimized, () -> SettingsLockCatalog.STARTUP_BEHAVIOR, draft, locks,
			remoteLocks,
			subjectWorkspace, lockSelectionEnabled, () -> authoritativeMinimized
		);
		PanelUi.addPreferredHeightComponent(this, launch);
		PanelUi.addPreferredHeightComponent(this, minimized);
		launch.addActionListener(event -> changed(launchLock, event));
		minimized.addActionListener(event -> changed(minimizedLock, event));
	}

	void apply(boolean launchAtSignIn, boolean startMinimized)
	{
		applying = true;
		try
		{
			authoritativeLaunch = launchAtSignIn;
			authoritativeMinimized = startMinimized;
			launch.setSelected(launchAtSignIn);
			minimized.setSelected(startMinimized);
			minimized.setEnabled(launchAtSignIn);
		}
		finally
		{
			applying = false;
		}
	}

	void refreshLockState()
	{
		launchLock.refresh();
		minimizedLock.refresh();
		launch.setEnabled(subjectWorkspace.getAsBoolean() || !launchLock.isEditLocked());
		minimized.setEnabled(
			(subjectWorkspace.getAsBoolean() || !minimizedLock.isEditLocked())
				&& launch.isSelected()
		);
	}

	private void changed(LockableCheckBoxBinding binding, ActionEvent event)
	{
		if (applying || binding.handleAction(event))
		{
			return;
		}
		if (binding == launchLock)
		{
			authoritativeLaunch = launch.isSelected();
			writer.accept(HapticScapeSettingKeys.START_WITH_WINDOWS, launch.isSelected());
			if (!launch.isSelected() && minimized.isSelected())
			{
				minimized.setSelected(false);
				authoritativeMinimized = false;
				writer.accept(HapticScapeSettingKeys.START_MINIMIZED, false);
			}
			minimized.setEnabled(launch.isSelected());
		}
		else
		{
			authoritativeMinimized = minimized.isSelected();
			writer.accept(HapticScapeSettingKeys.START_MINIMIZED, minimized.isSelected());
		}
	}
}
