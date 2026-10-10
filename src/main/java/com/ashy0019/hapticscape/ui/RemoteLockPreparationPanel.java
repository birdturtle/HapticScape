package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.remote.RemoteLockSnapshot;
import com.ashy0019.hapticscape.remote.RemoteLockState;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Compact controller workflow for an approval-gated post-session lock. */
final class RemoteLockPreparationPanel extends JPanel
{
	private final JLabel profileLabel = new JLabel();
	private final JLabel stateLabel = new JLabel();
	private final WrappedTextLabel detailLabel = new WrappedTextLabel("");
	private final JButton openSubjectButton = new JButton("Open Subject settings");
	private final JButton requestButton = new JButton("Generate key & request");
	private final JButton cancelButton = new JButton("Cancel request");
	private final JCheckBox protectedExit = new JCheckBox("Protect application exit");
	private final JCheckBox startupBehavior = new JCheckBox("Protect startup behavior");
	private final JPanel actions = new JPanel(new GridLayout(0, 1, 0, 4));

	RemoteLockPreparationPanel(
		Runnable openSubjectSettings,
		Runnable requestLock,
		Runnable cancelLock,
		Runnable toggleProtectedExit,
		Runnable toggleStartupBehavior)
	{
		Objects.requireNonNull(openSubjectSettings, "openSubjectSettings");
		Objects.requireNonNull(requestLock, "requestLock");
		Objects.requireNonNull(cancelLock, "cancelLock");
		Objects.requireNonNull(toggleProtectedExit, "toggleProtectedExit");
		Objects.requireNonNull(toggleStartupBehavior, "toggleStartupBehavior");
		setName("remoteLockPreparation");
		setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
		setBorder(PanelUi.createSectionBorder("Post-session lock"));

		profileLabel.setName("remoteLockProfileName");
		profileLabel.setFont(profileLabel.getFont().deriveFont(Font.BOLD, 14f));
		profileLabel.setForeground(HapticScapeTheme.ACCENT);
		profileLabel.setVisible(false);
		stateLabel.setName("remoteLockState");
		stateLabel.setFont(stateLabel.getFont().deriveFont(Font.BOLD));
		detailLabel.setName("remoteLockDetail");
		openSubjectButton.setName("remoteLockOpenSubject");
		requestButton.setName("remoteLockRequest");
		cancelButton.setName("remoteLockCancel");
		protectedExit.setName("remoteLockProtectedExit");
		startupBehavior.setName("remoteLockStartupBehavior");
		actions.setName("remoteLockActions");
		protectedExit.setToolTipText(
			"The participant must explicitly allow and approve protected exit"
		);
		configureCompactButton(openSubjectButton);
		configureCompactButton(requestButton);
		configureCompactButton(cancelButton);
		actions.add(openSubjectButton);
		actions.add(requestButton);
		actions.add(cancelButton);

		PanelUi.addPreferredHeightComponent(this, profileLabel);
		PanelUi.addPreferredHeightComponent(this, stateLabel);
		PanelUi.addFlexibleVerticalComponent(this, detailLabel);
		PanelUi.addPreferredHeightComponent(this, protectedExit);
		PanelUi.addPreferredHeightComponent(this, startupBehavior);
		PanelUi.addPreferredHeightComponent(this, actions);
		openSubjectButton.addActionListener(event -> openSubjectSettings.run());
		requestButton.addActionListener(event -> requestLock.run());
		cancelButton.addActionListener(event -> cancelLock.run());
		protectedExit.addActionListener(event -> toggleProtectedExit.run());
		startupBehavior.addActionListener(event -> toggleStartupBehavior.run());
	}

	void apply(
		RemoteLockSnapshot snapshot,
		int draftCount,
		boolean controllerActive,
		boolean subjectSettingsAvailable,
		boolean protectedExitSelected,
		boolean startupBehaviorSelected,
		boolean protectedExitAllowed,
		boolean vaultAvailable,
		String vaultMessage)
	{
		RemoteLockState state = snapshot.getState();
		int effectiveCount = state == RemoteLockState.INACTIVE
			|| state == RemoteLockState.DECLINED
			? draftCount
			: snapshot.getTargets().size();
		LockView view = viewFor(state, effectiveCount);
		String profileName = snapshot.getProfileName();
		profileLabel.setText(profileName);
		profileLabel.setVisible(!profileName.isEmpty());
		stateLabel.setText(view.getTitle());
		detailLabel.setPlainText(detailFor(snapshot, effectiveCount));
		openSubjectButton.setEnabled(controllerActive && subjectSettingsAvailable);
		openSubjectButton.setToolTipText(subjectSettingsAvailable
			? null
			: "The participant has not allowed settings changes");
		protectedExit.setSelected(protectedExitSelected);
		startupBehavior.setSelected(startupBehaviorSelected);
		protectedExit.setEnabled(
			controllerActive
				&& protectedExitAllowed
				&& (state == RemoteLockState.INACTIVE || state == RemoteLockState.DECLINED)
		);
		protectedExit.setToolTipText(protectedExitAllowed
			? "Include password-protected application exit in this lock request"
			: "The participant has not allowed protected exit requests");
		startupBehavior.setEnabled(protectedExit.isEnabled());
		startupBehavior.setToolTipText(protectedExitAllowed
			? "Preserve the accepted startup and minimized settings"
			: "The participant has not allowed protected startup requests");
		requestButton.setEnabled(controllerActive && effectiveCount > 0 && vaultAvailable);
		requestButton.setToolTipText(vaultAvailable ? null : vaultMessage);
		cancelButton.setText(view.getCancelLabel());
		cancelButton.setEnabled(controllerActive && view.showsCancel());
		boolean actionsChanged = showActions(view);
		boolean visibilityChanged = isVisible() != controllerActive;
		setVisible(controllerActive);
		if (actionsChanged || visibilityChanged)
		{
			revalidate();
		}
		repaint();
	}

	private boolean showActions(LockView view)
	{
		if (actionsMatch(view))
		{
			return false;
		}

		actions.removeAll();
		if (view.showsOpenSubject())
		{
			actions.add(openSubjectButton);
		}
		if (view.showsRequest())
		{
			actions.add(requestButton);
		}
		if (view.showsCancel())
		{
			actions.add(cancelButton);
		}
		return true;
	}

	private boolean actionsMatch(LockView view)
	{
		int index = 0;
		if (view.showsOpenSubject())
		{
			if (actions.getComponentCount() <= index
				|| actions.getComponent(index++) != openSubjectButton)
			{
				return false;
			}
		}
		if (view.showsRequest())
		{
			if (actions.getComponentCount() <= index
				|| actions.getComponent(index++) != requestButton)
			{
				return false;
			}
		}
		if (view.showsCancel())
		{
			if (actions.getComponentCount() <= index
				|| actions.getComponent(index++) != cancelButton)
			{
				return false;
			}
		}
		return actions.getComponentCount() == index;
	}

	private static String detailFor(RemoteLockSnapshot snapshot, int count)
	{
		switch (snapshot.getState())
		{
			case INACTIVE:
				return count == 0
					? "Open Subject settings and Shift-click the settings to preserve."
					: "The participant must approve before these final values are locked.";
			case DECLINED:
				return snapshot.getMessage() + (count > 0
					? " The current selection is ready to request again."
					: " Open Subject settings to make a new selection.");
			case AWAITING_APPROVAL:
			case APPROVAL_REQUIRED:
			case AWAITING_FINALIZE:
			case ARMED:
			default:
				return count > 0
					? snapshot.getMessage() + " · " + countLabel(count)
					: snapshot.getMessage();
		}
	}

	static LockView viewFor(RemoteLockState state, int count)
	{
		switch (state)
		{
			case INACTIVE:
				return new LockView(
					count == 0 ? "Nothing selected" : countLabel(count),
					true,
					count > 0,
					false,
					""
				);
			case AWAITING_APPROVAL:
				return new LockView(
					"Waiting for approval",
					false,
					false,
					true,
					"Cancel request"
				);
			case AWAITING_FINALIZE:
				return new LockView(
					"Accepted, waiting for profile name",
					false,
					false,
					true,
					"Cancel update"
				);
			case ARMED:
				return new LockView(
					"Post-session lock armed",
					false,
					false,
					true,
					"Cancel lock"
				);
			case DECLINED:
				return new LockView(
					"Lock not armed",
					true,
					count > 0,
					true,
					"Start over"
				);
			case APPROVAL_REQUIRED:
			default:
				return new LockView(
					"Approval required",
					false,
					false,
					false,
					""
				);
		}
	}

	private static String countLabel(int count)
	{
		return count + (count == 1 ? " setting selected" : " settings selected");
	}

	private static void configureCompactButton(JButton button)
	{
		button.setMargin(new java.awt.Insets(2, 6, 2, 6));
		button.setFocusable(false);
	}

	static final class LockView
	{
		private final String title;
		private final boolean openSubject;
		private final boolean request;
		private final boolean cancel;
		private final String cancelLabel;

		private LockView(
			String title,
			boolean openSubject,
			boolean request,
			boolean cancel,
			String cancelLabel)
		{
			this.title = title;
			this.openSubject = openSubject;
			this.request = request;
			this.cancel = cancel;
			this.cancelLabel = cancelLabel;
		}

		String getTitle()
		{
			return title;
		}

		boolean showsOpenSubject()
		{
			return openSubject;
		}

		boolean showsRequest()
		{
			return request;
		}

		boolean showsCancel()
		{
			return cancel;
		}

		String getCancelLabel()
		{
			return cancelLabel;
		}
	}
}
