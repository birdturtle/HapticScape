package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.host.TextClipboard;
import com.ashy0019.hapticscape.remote.RemoteSessionManager;
import com.ashy0019.hapticscape.remote.SavedUnlockKey;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;

/** Disconnected-only manager for platform-protected post-session unlock keys. */
final class SavedUnlockKeysPanel extends JPanel
{
	private final RemoteSessionManager sessionManager;
	private final TextClipboard clipboard;
	private final JPanel summaryView = new JPanel(new BorderLayout(8, 0));
	private final JPanel managerView = new JPanel(new BorderLayout(0, 7));
	private final WrappedTextLabel summaryStatus = new WrappedTextLabel("");
	private final WrappedTextLabel managerStatus = new WrappedTextLabel("");
	private final JButton manageButton = new JButton("Manage");
	private final JButton backButton = new JButton("Back");
	private final DefaultListModel<SavedUnlockKey> keyModel = new DefaultListModel<>();
	private final JList<SavedUnlockKey> keyList = new JList<>(keyModel);
	private final WrappedTextLabel detailLabel = new WrappedTextLabel("No key selected");
	private final JLabel createdLabel = metadataLabel("");
	private final JLabel lastUsedLabel = metadataLabel("");
	private final JTextArea exitNotice = new JTextArea(4, 20);
	private final JTextArea note = new JTextArea(4, 20);
	private final JButton copyButton = new JButton("Copy key");
	private final JButton editButton = new JButton("Edit details");
	private final JButton forgetButton = new JButton("Forget");
	private final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern(
		"MMM d, yyyy h:mm a"
	).withZone(ZoneId.systemDefault());
	private boolean managerOpen;
	private boolean copying;

	SavedUnlockKeysPanel(RemoteSessionManager sessionManager, TextClipboard clipboard)
	{
		this.sessionManager = Objects.requireNonNull(sessionManager, "sessionManager");
		this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
		setName("savedUnlockKeys");
		setLayout(new BorderLayout());
		setBorder(PanelUi.createSectionBorder("Saved unlock keys"));
		buildSummary();
		buildManager();
		showSummary();
	}

	private void buildSummary()
	{
		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		WrappedTextLabel explanation = new WrappedTextLabel(
			"Accepted post-session unlock keys use secure storage for your desktop account. "
				+ "Invitations and session keys are never saved."
		);
		explanation.setBorder(BorderFactory.createEmptyBorder(0, 2, 3, 2));
		summaryStatus.setName("savedUnlockKeySummaryStatus");
		summaryStatus.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 2));
		PanelUi.addFlexibleVerticalComponent(text, explanation);
		PanelUi.addFlexibleVerticalComponent(text, summaryStatus);
		configureCompactButton(manageButton);
		manageButton.setName("savedUnlockKeyManage");
		manageButton.addActionListener(event -> showManager());
		JPanel manageHost = new JPanel(new GridBagLayout());
		manageHost.add(manageButton);
		summaryView.add(text, BorderLayout.CENTER);
		summaryView.add(manageHost, BorderLayout.EAST);
		allowHorizontalShrink(summaryView);
	}

	private void buildManager()
	{
		configureCompactButton(backButton);
		backButton.setName("savedUnlockKeyBack");
		backButton.addActionListener(event -> showSummary());
		managerStatus.setName("savedUnlockKeyManagerStatus");
		JPanel heading = new JPanel(new BorderLayout(8, 0));
		heading.add(backButton, BorderLayout.WEST);
		heading.add(managerStatus, BorderLayout.CENTER);
		managerView.add(heading, BorderLayout.NORTH);

		keyList.setName("savedUnlockKeyList");
		keyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		keyList.setCellRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(
				JList<?> list,
				Object value,
				int index,
				boolean selected,
				boolean focused)
			{
				super.getListCellRendererComponent(
					list,
					value,
					index,
					selected,
					focused
				);
				SavedUnlockKey entry = value instanceof SavedUnlockKey
					? (SavedUnlockKey) value
					: null;
				setText(entry == null ? "" : entry.getLabel()
					+ (entry.getLastUnauthorizedEndAt() == null ? "" : " — " + entry.getUnauthorizedEnds().size() + " exits without unlocking"));
				if (entry != null && entry.isProfileKey())
				{
					setFont(getFont().deriveFont(Font.BOLD));
					if (!selected)
					{
						setForeground(HapticScapeTheme.ACCENT);
					}
				}
				return this;
			}
		});
		keyList.addListSelectionListener(event ->
		{
			if (!event.getValueIsAdjusting())
			{
				showSelectedKey();
			}
		});
		JScrollPane listScroll = new JScrollPane(keyList);
		listScroll.setName("savedUnlockKeyScroll");
		listScroll.setBorder(PanelUi.createSectionBorder("Keys"));
		PanelUi.setFlexibleWidthHeightHint(listScroll, 190, 120);

		JPanel details = new JPanel();
		details.setName("savedUnlockKeyDetails");
		details.setLayout(new BoxLayout(details, BoxLayout.Y_AXIS));
		details.setBorder(PanelUi.createSectionBorder("Details"));
		detailLabel.setName("savedUnlockKeyLabel");
		detailLabel.setFont(detailLabel.getFont().deriveFont(Font.BOLD, 15f));
		PanelUi.addPreferredHeightComponent(details, detailLabel);
		PanelUi.addPreferredHeightComponent(details, createdLabel);
		PanelUi.addPreferredHeightComponent(details, lastUsedLabel);
		exitNotice.setName("savedUnlockKeyExitNotice");
		exitNotice.setEditable(false);
		exitNotice.setLineWrap(true);
		exitNotice.setWrapStyleWord(true);
		JScrollPane exitScroll = new JScrollPane(exitNotice);
		exitScroll.setBorder(PanelUi.createSectionBorder("Exits without unlocking — this lock"));
		PanelUi.addPreferredHeightComponent(details, exitScroll);
		note.setName("savedUnlockKeyNote");
		note.setEditable(false);
		note.setFocusable(false);
		note.setLineWrap(true);
		note.setWrapStyleWord(true);
		JScrollPane noteScroll = new JScrollPane(note);
		noteScroll.setBorder(PanelUi.createSectionBorder("Note"));
		allowHorizontalShrink(noteScroll);
		PanelUi.addPreferredHeightComponent(details, noteScroll);

		configureCompactButton(copyButton);
		configureCompactButton(editButton);
		configureCompactButton(forgetButton);
		copyButton.setName("savedUnlockKeyCopy");
		editButton.setName("savedUnlockKeyEdit");
		forgetButton.setName("savedUnlockKeyForget");
		copyButton.addActionListener(event -> copySelected());
		editButton.addActionListener(event -> editSelected());
		forgetButton.addActionListener(event -> forgetSelected());
		JPanel actions = new JPanel(new GridLayout(1, 3, 4, 0));
		actions.add(copyButton);
		actions.add(editButton);
		actions.add(forgetButton);
		allowHorizontalShrink(actions);
		PanelUi.addPreferredHeightComponent(details, actions);

		SavedUnlockKeyVaultWorkspacePanel workspace =
			new SavedUnlockKeyVaultWorkspacePanel(listScroll, details);
		managerView.add(workspace, BorderLayout.CENTER);
		allowHorizontalShrink(managerView);
		showSelectedKey();
	}

	void refresh()
	{
		boolean available = sessionManager.isSavedUnlockKeyVaultAvailable();
		List<SavedUnlockKey> entries = available
			? sessionManager.getSavedUnlockKeys()
			: Collections.emptyList();
		SavedUnlockKeyVaultState state = SavedUnlockKeyVaultState.from(
			available,
			sessionManager.getSavedUnlockKeyVaultMessage(),
			entries.size()
		);
		long exitCount = entries.stream().filter(entry -> entry.getLastUnauthorizedEndAt() != null).count();
		String summary = state.getStatus() + (exitCount == 0 ? ""
			: " • " + exitCount + " saved profile(s) exited without unlocking. Open Manage for details.");
		summaryStatus.setPlainText(summary);
		summaryStatus.setToolTipText(summary);
		manageButton.setEnabled(state.isManageable());
		manageButton.setToolTipText(available ? null : state.getStatus());
		managerStatus.setPlainText(state.getStatus());

		String selectedId = selectedId();
		keyModel.clear();
		for (SavedUnlockKey entry : entries)
		{
			keyModel.addElement(entry);
		}
		selectById(selectedId);
		if (keyList.getSelectedIndex() < 0 && !entries.isEmpty())
		{
			keyList.setSelectedIndex(0);
		}
		showSelectedKey();
		updateDynamicSizeConstraints();
		revalidate();
		repaint();
	}

	void setLocalMode(boolean local)
	{
		if (!local && managerOpen)
		{
			showSummary();
		}
		setVisible(local);
	}

	private void showManager()
	{
		refresh();
		if (!manageButton.isEnabled())
		{
			return;
		}
		managerOpen = true;
		showView(managerView);
		keyList.requestFocusInWindow();
	}

	private void showSummary()
	{
		managerOpen = false;
		showView(summaryView);
	}

	private void showView(JPanel view)
	{
		removeAll();
		add(view, BorderLayout.CENTER);
		updateDynamicSizeConstraints();
		revalidate();
		repaint();
	}

	private void showSelectedKey()
	{
		SavedUnlockKey selected = keyList.getSelectedValue();
		boolean present = selected != null;
		detailLabel.setPlainText(present ? selected.getLabel() : "No key selected");
		detailLabel.setForeground(
			present && selected.isProfileKey()
				? HapticScapeTheme.ACCENT
				: javax.swing.UIManager.getColor("Label.foreground")
		);
		createdLabel.setText(present
			? "Created " + dateFormat.format(selected.getCreatedAt())
			: "");
		lastUsedLabel.setText(!present
			? ""
			: selected.getLastUsedAt() == null
				? "Not copied yet"
				: "Copied " + dateFormat.format(selected.getLastUsedAt()));
		StringBuilder history = new StringBuilder();
		if (present)
		{
			List<SavedUnlockKey.ExitEvent> events = selected.getUnauthorizedEnds();
			history.append(events.size()).append(events.size() == 1 ? " exit without unlocking" : " exits without unlocking");
			for (int index = events.size() - 1; index >= 0; index--)
			{
				history.append("\n").append(index + 1).append(". ")
					.append(dateFormat.format(events.get(index).getOccurredAt()));
			}
		}
		exitNotice.setText(history.toString());
		exitNotice.setCaretPosition(0);
		note.setText(!present
			? ""
			: selected.getNote().isEmpty() ? "No note" : selected.getNote());
		note.setCaretPosition(0);
		copyButton.setEnabled(present && !copying);
		editButton.setEnabled(present);
		forgetButton.setEnabled(present);
	}

	private void copySelected()
	{
		SavedUnlockKey selected = keyList.getSelectedValue();
		if (selected == null || copying) return;
		if (sessionManager.savedKeysRequireBackgroundThread())
		{
			copying = true;
			copyButton.setEnabled(false);
			managerStatus.setPlainText("Opening saved unlock key...");
			new javax.swing.SwingWorker<char[], Void>()
			{
				@Override
				protected char[] doInBackground()
				{
					return sessionManager.revealSavedUnlockKey(selected.getId());
				}

				@Override
				protected void done()
				{
					char[] key = null;
					try
					{
						key = get();
						clipboard.copyText(new String(key));
						refresh();
						managerStatus.setPlainText("Unlock key copied");
					}
					catch (Exception failure)
					{
						Throwable cause = failure.getCause();
						showError(cause == null ? failure.getMessage() : cause.getMessage());
					}
					finally
					{
						if (key != null) Arrays.fill(key, '\0');
						copying = false;
						showSelectedKey();
					}
				}
			}.execute();
			return;
		}
		char[] key = null;
		try
		{
			key = sessionManager.revealSavedUnlockKey(selected.getId());
			clipboard.copyText(new String(key));
			refresh();
			managerStatus.setPlainText("Unlock key copied");
		}
		catch (RuntimeException e)
		{
			showError(e.getMessage());
		}
		finally
		{
			if (key != null)
			{
				Arrays.fill(key, '\0');
			}
		}
	}

	private void editSelected()
	{
		SavedUnlockKey selected = keyList.getSelectedValue();
		if (selected == null)
		{
			return;
		}
		JTextField label = new JTextField(selected.getLabel());
		JTextArea updatedNote = new JTextArea(selected.getNote(), 3, 24);
		updatedNote.setLineWrap(true);
		updatedNote.setWrapStyleWord(true);
		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		PanelUi.addPreferredHeightComponent(content, labeledRow("Label", label));
		PanelUi.addPreferredHeightComponent(content, new JLabel("Note (optional)"));
		PanelUi.addPreferredHeightComponent(content, new JScrollPane(updatedNote));
		int choice = JOptionPane.showConfirmDialog(
			this,
			content,
			"Edit saved unlock key",
			JOptionPane.OK_CANCEL_OPTION,
			JOptionPane.PLAIN_MESSAGE
		);
		if (choice != JOptionPane.OK_OPTION)
		{
			return;
		}
		try
		{
			sessionManager.updateSavedUnlockKey(
				selected.getId(),
				label.getText(),
				updatedNote.getText()
			);
			refresh();
			managerStatus.setPlainText("Saved key details updated");
		}
		catch (RuntimeException e)
		{
			showError(e.getMessage());
		}
	}

	private void forgetSelected()
	{
		SavedUnlockKey selected = keyList.getSelectedValue();
		if (selected == null)
		{
			return;
		}
		int choice = JOptionPane.showConfirmDialog(
			this,
			"Forget the saved unlock key for \"" + selected.getLabel() + "\"?",
			"Forget unlock key",
			JOptionPane.YES_NO_OPTION,
			JOptionPane.WARNING_MESSAGE
		);
		if (choice != JOptionPane.YES_OPTION)
		{
			return;
		}
		try
		{
			sessionManager.forgetSavedUnlockKey(selected.getId());
			refresh();
			managerStatus.setPlainText("Saved unlock key forgotten");
		}
		catch (RuntimeException e)
		{
			showError(e.getMessage());
		}
	}

	private String selectedId()
	{
		SavedUnlockKey selected = keyList.getSelectedValue();
		return selected == null ? null : selected.getId();
	}

	private void selectById(String id)
	{
		if (id == null)
		{
			return;
		}
		for (int index = 0; index < keyModel.size(); index++)
		{
			if (id.equals(keyModel.get(index).getId()))
			{
				keyList.setSelectedIndex(index);
				return;
			}
		}
	}

	private void showError(String message)
	{
		JOptionPane.showMessageDialog(
			this,
			message == null ? "Saved Unlock Keys operation failed" : message,
			"Saved Unlock Keys",
			JOptionPane.ERROR_MESSAGE
		);
	}

	private static JPanel labeledRow(String name, Component control)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.add(new JLabel(name), BorderLayout.WEST);
		row.add(control, BorderLayout.CENTER);
		return row;
	}

	private void updateDynamicSizeConstraints()
	{
		setMinimumSize(new Dimension(0, 0));
		setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
	}

	private static JLabel metadataLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setFont(label.getFont().deriveFont(Font.PLAIN, 10f));
		allowHorizontalShrink(label);
		return label;
	}

	private static void configureCompactButton(JButton button)
	{
		button.setMargin(new Insets(2, 6, 2, 6));
		button.setFocusable(false);
		allowHorizontalShrink(button);
	}

	private static void allowHorizontalShrink(JComponent component)
	{
		Dimension preferred = component.getPreferredSize();
		component.setMinimumSize(new Dimension(0, preferred.height));
	}
}
