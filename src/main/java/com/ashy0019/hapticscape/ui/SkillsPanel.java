package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.SkillCatalog;
import com.ashy0019.hapticscape.SkillDescriptor;
import com.ashy0019.hapticscape.SkillSelection;
import com.ashy0019.hapticscape.remote.RemoteSessionManager;
import com.ashy0019.hapticscape.remote.SettingsLockCatalog;
import com.ashy0019.hapticscape.remote.SettingsLockService;
import com.ashy0019.hapticscape.remote.SettingsLockTarget;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingConstants;
import javax.swing.UIManager;

/**
 * Desktop skill matrix. Both feedback channels are visible at once and the
 * selected row drives the adjacent per-skill profile editor.
 */
final class SkillsPanel extends JPanel
{
	private final SettingsChangeSink settingsSink;
	private final SkillCatalog skillCatalog;
	private final Map<SkillDescriptor, EnumMap<SkillOutput, JCheckBox>> skillCheckBoxes =
		new LinkedHashMap<>();
	private final Map<SkillDescriptor, EnumMap<SkillOutput, LockableCheckBoxBinding>>
		lockBindings = new LinkedHashMap<>();
	private final Map<SkillDescriptor, JToggleButton> skillButtons = new LinkedHashMap<>();
	private final EnumMap<SkillOutput, JLabel> enabledCountLabels =
		new EnumMap<>(SkillOutput.class);
	private final EnumMap<SkillOutput, JButton> allButtons =
		new EnumMap<>(SkillOutput.class);
	private final EnumMap<SkillOutput, JButton> noneButtons =
		new EnumMap<>(SkillOutput.class);
	private final RemoteSessionManager sessionManager;
	private final SettingsLockDraft lockDraft;
	private final BooleanSupplier editingRemoteSubject;
	private final BooleanSupplier lockSelectionEnabled;
	private volatile SkillSelection hapticSkillSelection;
	private volatile SkillSelection clickSkillSelection;
	private Consumer<SkillDescriptor> skillSelectionAction = ignored -> { };
	private SkillDescriptor selectedSkill;
	private boolean updatingSkillCheckBoxes;
	private boolean remoteReadOnly;

	SkillsPanel(
		SkillCatalog skillCatalog,
		SkillSelection hapticSkillSelection,
		SkillSelection clickSkillSelection,
		SettingsChangeSink settingsSink,
		RemoteSessionManager sessionManager,
		SettingsLockService lockService,
		SettingsLockDraft lockDraft,
		BooleanSupplier editingRemoteSubject,
		BooleanSupplier lockSelectionEnabled)
	{
		this.skillCatalog = skillCatalog;
		this.hapticSkillSelection = hapticSkillSelection;
		this.clickSkillSelection = clickSkillSelection;
		this.settingsSink = settingsSink;
		this.sessionManager = sessionManager;
		this.lockDraft = lockDraft;
		this.editingRemoteSubject = editingRemoteSubject;
		this.lockSelectionEnabled = lockSelectionEnabled;
		setName("skillMatrix");
		setLayout(new BorderLayout(0, 5));
		setBorder(PanelUi.createSectionBorder("Skills"));

		JPanel header = new JPanel(new GridBagLayout());
		header.setName("skillMatrixHeader");
		addCell(header, new JLabel("Skill"), 0, 0, 1.0, GridBagConstraints.HORIZONTAL);
		for (SkillOutput output : SkillOutput.values())
		{
			JLabel label = new JLabel(output.toString(), SwingConstants.CENTER);
			label.setToolTipText(output.tooltip);
			setOutputColumnWidth(label);
			addCell(header, label, output.column, 0, 0.0, GridBagConstraints.NONE);

			JLabel count = new JLabel("0/0", SwingConstants.CENTER);
			count.setName("skillCount-" + output.name().toLowerCase());
			setOutputColumnWidth(count);
			enabledCountLabels.put(output, count);
			JPanel actions = new JPanel(new GridLayout(1, 2, 2, 0));
			actions.setName("skillActions-" + output.name().toLowerCase());
			JButton all = compactButton("All");
			JButton none = compactButton("None");
			all.setToolTipText("Enable " + output.toString().toLowerCase() + " for every editable skill");
			none.setToolTipText("Disable " + output.toString().toLowerCase() + " for every editable skill");
			all.addActionListener(event -> handleBulkAction(event, output, true));
			none.addActionListener(event -> handleBulkAction(event, output, false));
			allButtons.put(output, all);
			noneButtons.put(output, none);
			actions.add(all);
			actions.add(none);
			setOutputColumnWidth(actions);
			addCell(header, count, output.column, 1, 0.0, GridBagConstraints.NONE);
			addCell(header, actions, output.column, 2, 0.0, GridBagConstraints.NONE);
		}
		JLabel hint = new JLabel("Select a row to edit its XP override");
		hint.setEnabled(false);
		addCell(header, hint, 0, 2, 1.0, GridBagConstraints.HORIZONTAL);
		add(header, BorderLayout.NORTH);

		JPanel rows = new JPanel(new GridLayout(0, 1, 0, 1));
		ButtonGroup skillGroup = new ButtonGroup();
		for (SkillDescriptor skill : skillCatalog.getSkills())
		{
			JPanel row = new JPanel(new GridBagLayout());
			JToggleButton skillButton = new JToggleButton(skill.getDisplayName());
			skillButton.setName("skillRow-" + skill.getId());
			skillButton.setHorizontalAlignment(SwingConstants.LEFT);
			skillButton.setFocusPainted(false);
			skillButton.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
			skillButton.setContentAreaFilled(false);
			skillButton.setOpaque(true);
			skillButton.addActionListener(event -> selectSkill(skill));
			skillGroup.add(skillButton);
			skillButtons.put(skill, skillButton);
			addCell(row, skillButton, 0, 0, 1.0, GridBagConstraints.HORIZONTAL);

			EnumMap<SkillOutput, JCheckBox> boxes = new EnumMap<>(SkillOutput.class);
			EnumMap<SkillOutput, LockableCheckBoxBinding> bindings =
				new EnumMap<>(SkillOutput.class);
			for (SkillOutput output : SkillOutput.values())
			{
				JCheckBox checkBox = new JCheckBox();
				checkBox.setName(
					"skill-" + skill.getId() + "-" + output.name().toLowerCase()
				);
				checkBox.setHorizontalAlignment(SwingConstants.CENTER);
				setOutputColumnWidth(checkBox);
				checkBox.setToolTipText(
					output.toString() + " feedback for " + skill.getDisplayName()
				);
				LockableCheckBoxBinding binding = new LockableCheckBoxBinding(
					checkBox,
					() -> targetFor(output, skill),
					lockDraft,
					lockService,
					sessionManager::getLockSnapshot,
					editingRemoteSubject,
					lockSelectionEnabled,
					() -> selectionFor(output).isEnabled(skill.getId())
				);
				checkBox.addActionListener(event ->
				{
					if (!binding.handleAction(event))
					{
						setSkillEnabled(output, skill, checkBox.isSelected());
					}
				});
				boxes.put(output, checkBox);
				bindings.put(output, binding);
				addCell(row, checkBox, output.column, 0, 0.0, GridBagConstraints.NONE);
			}
			skillCheckBoxes.put(skill, boxes);
			lockBindings.put(skill, bindings);
			rows.add(row);
			if (selectedSkill == null)
			{
				selectedSkill = skill;
				skillButton.setSelected(true);
			}
		}
		add(rows, BorderLayout.CENTER);
		refreshSkillButtonStyles();
		refreshSkillCheckBoxes();
	}

	void setSkillSelectionAction(Consumer<SkillDescriptor> action)
	{
		skillSelectionAction = action == null ? ignored -> { } : action;
		if (selectedSkill != null)
		{
			skillSelectionAction.accept(selectedSkill);
		}
	}

	SkillDescriptor getSelectedSkill()
	{
		return selectedSkill;
	}

	void applyDisplayedSelections(
		SkillSelection hapticSelection,
		SkillSelection clickSelection)
	{
		hapticSkillSelection = hapticSelection;
		clickSkillSelection = clickSelection;
		refreshSkillCheckBoxes();
	}

	void setRemoteReadOnly(boolean remoteReadOnly)
	{
		this.remoteReadOnly = remoteReadOnly;
		refreshReadOnlyState();
	}

	boolean isHapticSkillEnabled(String skillId)
	{
		return hapticSkillSelection.isEnabled(skillId);
	}

	boolean isClickSkillEnabled(String skillId)
	{
		return clickSkillSelection.isEnabled(skillId);
	}

	boolean isSkillEnabled(String skillId)
	{
		return isHapticSkillEnabled(skillId);
	}

	private void selectSkill(SkillDescriptor skill)
	{
		selectedSkill = skill;
		JToggleButton button = skillButtons.get(skill);
		if (button != null)
		{
			button.setSelected(true);
		}
		refreshSkillButtonStyles();
		skillSelectionAction.accept(skill);
	}

	private void refreshSkillButtonStyles()
	{
		Color selectedBackground = UIManager.getColor("List.selectionBackground");
		Color selectedForeground = HapticScapeTheme.ACCENT;
		Color normalForeground = UIManager.getColor("Label.foreground");
		if (selectedBackground == null)
		{
			selectedBackground = getBackground().darker();
		}
		for (Map.Entry<SkillDescriptor, JToggleButton> entry : skillButtons.entrySet())
		{
			boolean selected = entry.getKey().equals(selectedSkill);
			JToggleButton button = entry.getValue();
			button.setBackground(selected ? selectedBackground : getBackground());
			button.setForeground(selected ? selectedForeground : normalForeground);
		}
	}

	private void handleBulkAction(ActionEvent event, SkillOutput output, boolean enabled)
	{
		if (isShift(event) && lockSelectionEnabled.getAsBoolean())
		{
			setAllSkillsLockSelected(output, enabled);
		}
		else
		{
			setAllSkillsEnabled(output, enabled);
		}
	}

	private void setSkillEnabled(SkillOutput output, SkillDescriptor skill, boolean enabled)
	{
		if (remoteReadOnly || updatingSkillCheckBoxes)
		{
			return;
		}
		SkillSelection updated = selectionFor(output).withEnabled(skill.getId(), enabled);
		setSelection(output, updated);
		persist(output, skill, updated);
		updateEnabledSkillsLabels();
	}

	private void setAllSkillsEnabled(SkillOutput output, boolean enabled)
	{
		if (remoteReadOnly)
		{
			return;
		}
		SkillSelection updated = selectionFor(output);
		for (SkillDescriptor skill : skillCatalog.getSkills())
		{
			LockableCheckBoxBinding binding = lockBindings.get(skill).get(output);
			if (editingRemoteSubject.getAsBoolean() || !binding.isEditLocked())
			{
				updated = updated.withEnabled(skill.getId(), enabled);
			}
		}
		setSelection(output, updated);
		refreshSkillCheckBoxes();
		persist(output, null, updated);
	}

	private void setAllSkillsLockSelected(SkillOutput output, boolean selected)
	{
		Set<SettingsLockTarget> targets = new LinkedHashSet<>();
		for (SkillDescriptor skill : skillCatalog.getSkills())
		{
			targets.add(targetFor(output, skill));
		}
		lockDraft.setAll(targets, selected);
		refreshReadOnlyState();
	}

	private void persist(
		SkillOutput output,
		SkillDescriptor skill,
		SkillSelection selection)
	{
		SettingsLockTarget target = skill == null ? null : targetFor(output, skill);
		settingsSink.set(
			target,
			output == SkillOutput.HAPTICS
				? HapticScapeSettingKeys.DISABLED_SKILLS
				: HapticScapeSettingKeys.CLICKER_DISABLED_SKILLS,
			selection.toConfigValue()
		);
	}

	private void updateEnabledSkillsLabels()
	{
		int skillCount = skillCatalog.getSkills().size();
		for (SkillOutput output : SkillOutput.values())
		{
			int enabledCount = selectionFor(output).getEnabledCount(skillCatalog.getSkillIds());
			JLabel label = enabledCountLabels.get(output);
			label.setText(enabledCount + "/" + skillCount);
			label.setToolTipText(
				enabledCount + " of " + skillCount + " skills enabled for " + output
			);
		}
	}

	private void refreshSkillCheckBoxes()
	{
		updatingSkillCheckBoxes = true;
		try
		{
			for (Map.Entry<SkillDescriptor, EnumMap<SkillOutput, JCheckBox>> entry
				: skillCheckBoxes.entrySet())
			{
				for (SkillOutput output : SkillOutput.values())
				{
					entry.getValue().get(output).setSelected(
						selectionFor(output).isEnabled(entry.getKey().getId())
					);
				}
			}
		}
		finally
		{
			updatingSkillCheckBoxes = false;
		}
		updateEnabledSkillsLabels();
		refreshReadOnlyState();
	}

	private void refreshReadOnlyState()
	{
		for (Map.Entry<SkillDescriptor, EnumMap<SkillOutput, JCheckBox>> entry
			: skillCheckBoxes.entrySet())
		{
			for (SkillOutput output : SkillOutput.values())
			{
				LockableCheckBoxBinding binding = lockBindings.get(entry.getKey()).get(output);
				binding.refresh();
				entry.getValue().get(output).setEnabled(
					!remoteReadOnly && !binding.isEditLocked()
				);
			}
		}
		for (SkillOutput output : SkillOutput.values())
		{
			allButtons.get(output).setEnabled(!remoteReadOnly);
			noneButtons.get(output).setEnabled(!remoteReadOnly);
		}
	}

	private SkillSelection selectionFor(SkillOutput output)
	{
		return output == SkillOutput.HAPTICS
			? hapticSkillSelection
			: clickSkillSelection;
	}

	private void setSelection(SkillOutput output, SkillSelection selection)
	{
		if (output == SkillOutput.HAPTICS)
		{
			hapticSkillSelection = selection;
		}
		else
		{
			clickSkillSelection = selection;
		}
	}

	private SettingsLockTarget targetFor(SkillOutput output, SkillDescriptor skill)
	{
		return output == SkillOutput.HAPTICS
			? SettingsLockCatalog.skillHaptics(skill.getId())
			: SettingsLockCatalog.skillClicks(skill.getId());
	}

	private static JButton compactButton(String label)
	{
		JButton button = new JButton(label);
		button.setMargin(new Insets(1, 4, 1, 4));
		button.setFocusPainted(false);
		return button;
	}

	private static void setOutputColumnWidth(JComponent component)
	{
		Dimension preferred = component.getPreferredSize();
		// GridLayout gives both buttons equal width, including the wider "None" label.
		int buttonWidth = Math.max(compactButton("All").getPreferredSize().width,
			compactButton("None").getPreferredSize().width);
		int width = Math.max(96, 2 * buttonWidth + 2);
		component.setPreferredSize(new Dimension(width, preferred.height));
		component.setMinimumSize(new Dimension(width, preferred.height));
	}

	private static void addCell(
		JPanel panel,
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
		constraints.fill = fill;
		constraints.anchor = GridBagConstraints.CENTER;
		constraints.insets = new Insets(1, x == 0 ? 0 : 4, 1, 0);
		panel.add(component, constraints);
	}

	private static boolean isShift(ActionEvent event)
	{
		return (event.getModifiers() & ActionEvent.SHIFT_MASK) != 0;
	}

	private enum SkillOutput
	{
		HAPTICS("Haptics", 1, "Vibration feedback for XP gains"),
		CLICKER("Clicks", 2, "Audio click feedback for XP gains");

		private final String displayName;
		private final int column;
		private final String tooltip;

		SkillOutput(String displayName, int column, String tooltip)
		{
			this.displayName = displayName;
			this.column = column;
			this.tooltip = tooltip;
		}

		@Override
		public String toString()
		{
			return displayName;
		}
	}
}
