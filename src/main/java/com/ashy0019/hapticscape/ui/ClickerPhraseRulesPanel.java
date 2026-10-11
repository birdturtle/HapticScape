package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.HapticScapeSettingsSource;
import com.ashy0019.hapticscape.clicker.ClickSequence;
import com.ashy0019.hapticscape.clicker.ClickerPhraseMatchMode;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRule;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRules;
import com.ashy0019.hapticscape.remote.RemoteLockSnapshot;
import com.ashy0019.hapticscape.remote.RemoteLockState;
import com.ashy0019.hapticscape.remote.RemoteSessionManager;
import com.ashy0019.hapticscape.remote.SettingsLockCatalog;
import com.ashy0019.hapticscape.remote.SettingsLockService;
import com.ashy0019.hapticscape.remote.SettingsLockTarget;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import com.ashy0019.hapticscape.CustomPatternLibrary;
import com.ashy0019.hapticscape.HapticPatternSelection;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Inline, responsive phrase-rule list and editor. */
final class ClickerPhraseRulesPanel extends JPanel
{
	private static final int SIDE_BY_SIDE_BREAKPOINT = 620;

	private final SettingsChangeSink settingsSink;
	private final DefaultListModel<ClickerPhraseRule> ruleModel =
		new DefaultListModel<>();
	private final JList<ClickerPhraseRule> ruleList = new JList<>(ruleModel);
	private final JButton addButton = new JButton("New trigger");
	private final JButton deleteButton = new JButton("Delete");
	private final JLabel ruleCountLabel = new JLabel();
	private final JLabel editorTitle = new JLabel("No rule selected");
	private final JCheckBox editorEnabled = new JCheckBox("Enabled");
	private final JComboBox<ClickerPhraseMatchMode> editorMode =
		new JComboBox<>(ClickerPhraseMatchMode.values());
	private final JComboBox<ClickSequence> editorSequence =
		ClickSequenceControls.optional();
	private final JTextArea editorExpression = new JTextArea(2, 28);
	private final JPanel legacyMatchRow = new JPanel(new BorderLayout(8, 0));
	private final JCheckBox editorHaptics = new JCheckBox("Haptics");
	private final JComboBox<HapticPatternSelection> editorPattern;
	private final JSpinner editorIntensity = new JSpinner(new SpinnerNumberModel(10, 0, 100, 1));
	private final JSpinner editorDuration = new JSpinner(new SpinnerNumberModel(500, 50, 10000, 50));
	private final Supplier<CustomPatternLibrary> patternLibrary;
	private final JButton saveButton = new JButton("Save trigger");
	private final JButton cancelButton = new JButton("Cancel");
	private final JPanel editorPanel = new JPanel();
	private final JPanel responsiveContent = new JPanel(new GridBagLayout());
	private final SettingsLockDraft lockDraft;
	private final SettingsLockService lockService;
	private final RemoteSessionManager sessionManager;
	private final BooleanSupplier editingRemoteSubject;
	private final BooleanSupplier lockSelectionEnabled;

	private volatile ClickerPhraseRules rules;
	private boolean remoteReadOnly;
	private boolean loadingEditor;
	private boolean refreshingModel;
	private boolean editingNewRule;
	private boolean dirty;
	private String selectionBeforeAddId;
	private int layoutMode = -1;

	ClickerPhraseRulesPanel(
		HapticScapeSettingsSource config,
		SettingsChangeSink settingsSink,
		RemoteSessionManager sessionManager,
		SettingsLockService lockService,
		SettingsLockDraft lockDraft,
		BooleanSupplier editingRemoteSubject,
		BooleanSupplier lockSelectionEnabled, Supplier<CustomPatternLibrary> patternLibrary)
	{
		this.patternLibrary = patternLibrary;
		editorPattern = PanelUi.createPatternComboBox(patternLibrary);
		this.settingsSink = settingsSink;
		this.sessionManager = sessionManager;
		this.lockService = lockService;
		this.lockDraft = lockDraft;
		this.editingRemoteSubject = editingRemoteSubject;
		this.lockSelectionEnabled = lockSelectionEnabled;
		String configuredRules = config.clickerPhraseRules();
		rules = ClickerPhraseRules.fromConfigValue(configuredRules);
		if (ClickerPhraseRules.requiresMigration(configuredRules))
		{
			settingsSink.set(
				HapticScapeSettingKeys.CLICKER_PHRASE_RULES,
				rules.toConfigValue()
			);
		}

		setName("phraseRulesWorkspace");
		setLayout(new BorderLayout(0, 5));
		setBorder(PanelUi.createSectionBorder("Chat triggers"));
		JPanel heading = new JPanel(new BorderLayout(8, 0));
		JLabel description = new JLabel("Respond to words or phrases received from other players.");
		description.setToolTipText(
			"Your own messages are ignored. The first matching trigger wins, with a one-second cooldown."
		);
		heading.add(description, BorderLayout.CENTER);
		heading.add(ruleCountLabel, BorderLayout.EAST);
		add(heading, BorderLayout.NORTH);

		JPanel listPanel = createListPanel();
		createEditorPanel();
		add(responsiveContent, BorderLayout.CENTER);
		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent event)
			{
				reflow(listPanel, editorPanel);
			}
		});
		reflow(listPanel, editorPanel);

		configureListeners();
		lockDraft.addListener(this::refreshLockState);
		refreshModel(null);
	}

	ClickerPhraseRules getRules()
	{
		return rules;
	}

	void applyDisplayedRules(ClickerPhraseRules displayedRules)
	{
		rules = displayedRules;
		editingNewRule = false;
		dirty = false;
		refreshModel(selectedRuleId());
	}

	void setClickerEnabled(boolean enabled)
	{
		// Phrase rules remain editable while click output is off.
		refreshEnabledState();
	}

	void setRemoteReadOnly(boolean remoteReadOnly)
	{
		this.remoteReadOnly = remoteReadOnly;
		refreshEnabledState();
	}

	static int layoutModeForWidth(int width)
	{
		return width >= SIDE_BY_SIDE_BREAKPOINT ? 2 : 1;
	}

	private JPanel createListPanel()
	{
		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setBorder(PanelUi.createSectionBorder("Rules"));
		ruleList.setName("phraseRuleList");
		ruleList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		ruleList.setCellRenderer(new PhraseRuleRenderer());
		ruleList.setFixedCellHeight(29);
		JScrollPane scrollPane = new JScrollPane(ruleList);
		scrollPane.setName("phraseRuleScroll");
		PanelUi.setFlexibleWidthHeightHint(scrollPane, 210, 120);
		scrollPane.setToolTipText(
			"Shift-click a rule during Remote Play to select its post-session lock."
		);
		panel.add(scrollPane, BorderLayout.CENTER);

		JPanel buttons = new JPanel(new GridLayout(1, 2, 4, 0));
		buttons.add(addButton);
		buttons.add(deleteButton);
		panel.add(buttons, BorderLayout.SOUTH);
		return panel;
	}

	private void createEditorPanel()
	{
		editorPanel.setLayout(new BoxLayout(editorPanel, BoxLayout.Y_AXIS));
		editorPanel.setBorder(PanelUi.createSectionBorder("Rule editor"));
		editorTitle.setBorder(BorderFactory.createEmptyBorder(2, 2, 4, 2));
		PanelUi.addPreferredHeightComponent(editorPanel, editorTitle);
		PanelUi.addPreferredHeightComponent(editorPanel, editorEnabled);
		PanelUi.setFixedWidth(editorMode, PanelUi.SELECTOR_CONTROL_WIDTH);
		legacyMatchRow.add(new JLabel("Legacy matching"), BorderLayout.WEST);
		legacyMatchRow.add(editorMode, BorderLayout.EAST);
		PanelUi.addPreferredHeightComponent(editorPanel, legacyMatchRow);
		PanelUi.addPreferredHeightComponent(editorPanel, row("Clicks", editorSequence));
		PanelUi.addPreferredHeightComponent(editorPanel, editorHaptics);
		PanelUi.addPreferredHeightComponent(editorPanel, row("Pattern", editorPattern));
		PanelUi.setFixedWidth(editorIntensity, PanelUi.NUMERIC_CONTROL_WIDTH);
		PanelUi.setFixedWidth(editorDuration, PanelUi.NUMERIC_CONTROL_WIDTH);
		PanelUi.addPreferredHeightComponent(editorPanel, row("Intensity (%)", editorIntensity));
		PanelUi.addPreferredHeightComponent(editorPanel, row("Duration (ms)", editorDuration));
		PanelUi.addPreferredHeightComponent(editorPanel, new JLabel("Word or phrase"));
		editorExpression.setLineWrap(false);
		JScrollPane expressionScroll = new JScrollPane(editorExpression);
		expressionScroll.setName("phraseExpressionScroll");
		PanelUi.setFlexibleWidthHeightHint(expressionScroll, 112, 80);
		PanelUi.addPreferredHeightComponent(editorPanel, expressionScroll);
		JLabel hint = new JLabel("Whole words and phrases; capitalization is ignored.");
		hint.setToolTipText(
			"For example, good job matches You did a good job!, but good does not match goodbye."
		);
		PanelUi.addPreferredHeightComponent(editorPanel, hint);

		JPanel actions = new JPanel(new GridLayout(1, 2, 4, 0));
		actions.add(saveButton);
		actions.add(cancelButton);
		PanelUi.addPreferredHeightComponent(editorPanel, actions);
	}

	private void configureListeners()
	{
		ruleList.addListSelectionListener(event ->
		{
			if (!event.getValueIsAdjusting() && !refreshingModel && !dirty)
			{
				editingNewRule = false;
				loadEditor(ruleList.getSelectedValue(), false);
			}
		});
		ruleList.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent event)
			{
				handleRuleShiftClick(event);
			}
		});
		addButton.addActionListener(event -> startNewRule());
		deleteButton.addActionListener(event -> deleteSelectedRule());
		saveButton.addActionListener(event -> saveEditor());
		cancelButton.addActionListener(event -> cancelEditor());
		editorEnabled.addActionListener(event -> markDirty());
		editorMode.addActionListener(event -> markDirty());
		editorSequence.addActionListener(event -> markDirty());
		editorHaptics.addActionListener(event -> { markDirty(); refreshEnabledState(); });
		editorPattern.addActionListener(event -> markDirty());
		editorIntensity.addChangeListener(event -> markDirty());
		editorDuration.addChangeListener(event -> markDirty());
		editorExpression.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				markDirty();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				markDirty();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				markDirty();
			}
		});
	}

	private void startNewRule()
	{
		if (remoteReadOnly || rules.getRules().size() >= ClickerPhraseRules.MAXIMUM_RULES)
		{
			return;
		}
		selectionBeforeAddId = selectedRuleId();
		editingNewRule = true;
		dirty = true;
		refreshingModel = true;
		try
		{
			ruleList.clearSelection();
		}
		finally
		{
			refreshingModel = false;
		}
		loadEditor(null, true);
		editorExpression.requestFocusInWindow();
	}

	private void saveEditor()
	{
		if (remoteReadOnly || !dirty)
		{
			return;
		}
		ClickerPhraseRule existing = editingNewRule ? null : ruleList.getSelectedValue();
		SettingsLockTarget target = existing == null ? null : target(existing);
		if (target != null && isPersistentlyLocked(target))
		{
			return;
		}
		try
		{
			ClickerPhraseMatchMode mode =
				(ClickerPhraseMatchMode) editorMode.getSelectedItem();
			ClickSequence sequence = (ClickSequence) editorSequence.getSelectedItem();
			ClickerPhraseRule updated = existing == null
				? new ClickerPhraseRule(
					editorEnabled.isSelected(),
					ClickSequence.ONE,
					mode,
					editorExpression.getText()
				)
				: existing.withValues(
					editorEnabled.isSelected(),
					ClickSequence.ONE,
					mode,
					editorExpression.getText()
				);
			updated = updated.withFeedback(sequence, editorHaptics.isSelected(),
				(HapticPatternSelection) editorPattern.getSelectedItem(),
				(Integer) editorIntensity.getValue(), (Integer) editorDuration.getValue());
			if (existing == null)
			{
				rules = rules.withAdded(updated);
			}
			else
			{
				rules = rules.withReplaced(ruleList.getSelectedIndex(), updated);
			}
			persist(target);
			editingNewRule = false;
			dirty = false;
			selectionBeforeAddId = null;
			refreshModel(updated.getId());
		}
		catch (IllegalArgumentException | IllegalStateException exception)
		{
			JOptionPane.showMessageDialog(
				this,
				exception.getMessage(),
				"Invalid phrase rule",
				JOptionPane.ERROR_MESSAGE
			);
		}
	}

	private void cancelEditor()
	{
		if (!dirty)
		{
			return;
		}
		String restoreId = editingNewRule ? selectionBeforeAddId : selectedRuleId();
		editingNewRule = false;
		dirty = false;
		selectionBeforeAddId = null;
		refreshModel(restoreId);
	}

	private void deleteSelectedRule()
	{
		int index = ruleList.getSelectedIndex();
		if (remoteReadOnly || dirty || index < 0)
		{
			return;
		}
		ClickerPhraseRule selected = rules.getRules().get(index);
		SettingsLockTarget target = target(selected);
		if (isPersistentlyLocked(target))
		{
			return;
		}

		rules = rules.withRemoved(index);
		persist(target);
		String nextId = rules.getRules().isEmpty()
			? null
			: rules.getRules().get(Math.min(index, rules.getRules().size() - 1)).getId();
		refreshModel(nextId);
	}

	private void markDirty()
	{
		if (loadingEditor)
		{
			return;
		}
		dirty = true;
		refreshEnabledState();
	}

	public void refreshPatternChoices()
	{
		loadingEditor = true;
		try
		{
			PanelUi.setPatternChoices(editorPattern,
				(HapticPatternSelection) editorPattern.getSelectedItem(), patternLibrary.get());
		}
		finally
		{
			loadingEditor = false;
		}
	}

	private void loadEditor(ClickerPhraseRule rule, boolean newRule)
	{
		loadingEditor = true;
		try
		{
			editorTitle.setText(newRule
				? "New chat trigger"
				: rule == null ? "No rule selected" : rule.toString());
			editorEnabled.setSelected(rule == null || rule.isEnabled());
			editorMode.setSelectedItem(
				rule == null ? ClickerPhraseMatchMode.PHRASE : rule.getMode()
			);
			editorSequence.setSelectedItem(
				rule == null ? ClickSequence.ONE : rule.getSequence()
			);
			editorHaptics.setSelected(rule != null && rule.isHapticEnabled());
			editorIntensity.setValue(rule == null ? 10 : rule.getIntensityPercent());
			editorDuration.setValue(rule == null ? 500 : rule.getDurationMillis());
			PanelUi.setPatternChoices(editorPattern, rule == null ? HapticPatternSelection.SINGLE : rule.getHapticPattern(), patternLibrary.get());
			legacyMatchRow.setVisible(rule != null && rule.getMode() != ClickerPhraseMatchMode.PHRASE);
			editorExpression.setText(rule == null ? "" : rule.getExpression());
			editorExpression.setCaretPosition(0);
		}
		finally
		{
			loadingEditor = false;
		}
		dirty = newRule;
		refreshEnabledState();
	}

	private void refreshModel(String preferredId)
	{
		String selectedId = preferredId == null ? selectedRuleId() : preferredId;
		refreshingModel = true;
		try
		{
			ruleModel.clear();
			for (ClickerPhraseRule rule : rules.getRules())
			{
				ruleModel.addElement(rule);
			}
			if (!ruleModel.isEmpty())
			{
				int selectedIndex = findRuleIndex(selectedId);
				ruleList.setSelectedIndex(selectedIndex < 0 ? 0 : selectedIndex);
			}
			else
			{
				ruleList.clearSelection();
			}
		}
		finally
		{
			refreshingModel = false;
		}
		ruleCountLabel.setText(
			rules.getRules().size() + "/" + ClickerPhraseRules.MAXIMUM_RULES
		);
		loadEditor(ruleList.getSelectedValue(), false);
	}

	private void refreshEnabledState()
	{
		ClickerPhraseRule selected = ruleList.getSelectedValue();
		boolean selectedLocked = selected != null && isPersistentlyLocked(target(selected));
		boolean hasEditor = editingNewRule || selected != null;
		boolean editorEditable = !remoteReadOnly && hasEditor && !selectedLocked;
		ruleList.setEnabled(!dirty);
		addButton.setEnabled(
			!remoteReadOnly
				&& !dirty
				&& rules.getRules().size() < ClickerPhraseRules.MAXIMUM_RULES
		);
		deleteButton.setEnabled(!remoteReadOnly && !dirty && selected != null && !selectedLocked);
		editorEnabled.setEnabled(editorEditable);
		editorMode.setEnabled(editorEditable);
		editorSequence.setEnabled(editorEditable);
		editorHaptics.setEnabled(editorEditable);
		editorPattern.setEnabled(editorEditable && editorHaptics.isSelected());
		editorIntensity.setEnabled(editorEditable && editorHaptics.isSelected());
		editorDuration.setEnabled(editorEditable && editorHaptics.isSelected());
		editorExpression.setEnabled(editorEditable);
		saveButton.setEnabled(editorEditable && dirty);
		cancelButton.setEnabled(dirty);
	}

	private void persist(SettingsLockTarget target)
	{
		settingsSink.set(
			target,
			HapticScapeSettingKeys.CLICKER_PHRASE_RULES,
			rules.toConfigValue()
		);
	}

	private void handleRuleShiftClick(MouseEvent event)
	{
		if (dirty
			|| (event.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) == 0
			|| !editingRemoteSubject.getAsBoolean()
			|| !lockSelectionEnabled.getAsBoolean())
		{
			return;
		}
		int index = ruleList.locationToIndex(event.getPoint());
		if (index < 0)
		{
			return;
		}
		Rectangle bounds = ruleList.getCellBounds(index, index);
		if (bounds == null || !bounds.contains(event.getPoint()))
		{
			return;
		}
		ruleList.setSelectedIndex(index);
		lockDraft.toggle(target(ruleModel.get(index)));
		refreshLockState();
		event.consume();
	}

	private void refreshLockState()
	{
		ruleList.repaint();
		refreshEnabledState();
	}

	private String selectedRuleId()
	{
		ClickerPhraseRule selected = ruleList.getSelectedValue();
		return selected == null ? null : selected.getId();
	}

	private int findRuleIndex(String id)
	{
		if (id == null)
		{
			return -1;
		}
		for (int index = 0; index < ruleModel.size(); index++)
		{
			if (id.equals(ruleModel.get(index).getId()))
			{
				return index;
			}
		}
		return -1;
	}

	private SettingsLockTarget target(ClickerPhraseRule rule)
	{
		return SettingsLockCatalog.phraseRule(rule.getId());
	}

	private boolean isPersistentlyLocked(SettingsLockTarget target)
	{
		return !editingRemoteSubject.getAsBoolean() && lockService.isLocked(target);
	}

	private LockState lockState(ClickerPhraseRule rule)
	{
		SettingsLockTarget target = target(rule);
		if (editingRemoteSubject.getAsBoolean())
		{
			RemoteLockSnapshot remote = sessionManager.getLockSnapshot();
			if (remote.getTargets().contains(target)
				&& remote.getState() == RemoteLockState.ARMED)
			{
				return LockState.ARMED;
			}
			if ((remote.getState() != RemoteLockState.INACTIVE
				&& remote.getTargets().contains(target))
				|| lockDraft.contains(target))
			{
				return LockState.PROPOSED;
			}
			return LockState.NONE;
		}
		return lockService.isLocked(target) ? LockState.PERSISTENT : LockState.NONE;
	}

	private void reflow(Component listPanel, Component editor)
	{
		int desired = layoutModeForWidth(getWidth());
		if (desired == layoutMode)
		{
			return;
		}
		layoutMode = desired;
		responsiveContent.removeAll();
		if (layoutMode == 2)
		{
			addSection(listPanel, 0, 0, 0.46, GridBagConstraints.BOTH);
			addSection(editor, 1, 0, 0.54, GridBagConstraints.BOTH);
		}
		else
		{
			addSection(listPanel, 0, 0, 1.0, GridBagConstraints.HORIZONTAL);
			addSection(editor, 0, 1, 1.0, GridBagConstraints.HORIZONTAL);
		}
		responsiveContent.revalidate();
		responsiveContent.repaint();
	}

	private void addSection(
		Component component,
		int x,
		int y,
		double weightX,
		int fill)
	{
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = x;
		constraints.gridy = y;
		constraints.weightx = weightX;
		constraints.weighty = 1.0;
		constraints.fill = fill;
		constraints.anchor = GridBagConstraints.NORTHWEST;
		constraints.insets = new Insets(0, 0, 0, x == 0 && layoutMode == 2 ? 6 : 0);
		responsiveContent.add(component, constraints);
	}

	private enum LockState
	{
		NONE,
		PROPOSED,
		ARMED,
		PERSISTENT
	}

	private final class PhraseRuleRenderer extends DefaultListCellRenderer
	{
		@Override
		public Component getListCellRendererComponent(
			JList<?> list,
			Object value,
			int index,
			boolean isSelected,
			boolean cellHasFocus)
		{
			super.getListCellRendererComponent(
				list,
				value,
				index,
				isSelected,
				cellHasFocus
			);
			ClickerPhraseRule rule = value instanceof ClickerPhraseRule
				? (ClickerPhraseRule) value
				: null;
			setIcon(new PhraseLockIcon(rule));
			return this;
		}
	}

	private final class PhraseLockIcon implements Icon
	{
		private final ClickerPhraseRule rule;

		private PhraseLockIcon(ClickerPhraseRule rule)
		{
			this.rule = rule;
		}

		@Override
		public int getIconWidth()
		{
			return 11;
		}

		@Override
		public int getIconHeight()
		{
			return 11;
		}

		@Override
		public void paintIcon(Component component, Graphics graphics, int x, int y)
		{
			if (rule == null)
			{
				return;
			}
			LockState state = lockState(rule);
			if (state == LockState.NONE)
			{
				return;
			}
			graphics.setColor(state == LockState.PERSISTENT
				? new Color(170, 170, 170)
				: new Color(255, 174, 0));
			graphics.drawArc(x + 2, y, 5, 6, 0, 180);
			if (state == LockState.ARMED || state == LockState.PERSISTENT)
			{
				graphics.fillRect(x + 1, y + 5, 8, 6);
			}
			else
			{
				graphics.drawRect(x + 1, y + 5, 7, 5);
			}
		}
	}

	private static JPanel row(String name, Component control)
	{
		JPanel row = new JPanel(new BorderLayout(8, 0));
		row.add(new JLabel(name), BorderLayout.CENTER);
		row.add(control, BorderLayout.EAST);
		return row;
	}
}
