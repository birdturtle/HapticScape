package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.CustomPatternLibrary;
import com.ashy0019.hapticscape.HapticPatternSelection;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.util.Locale;
import java.util.function.Supplier;
import javax.swing.border.AbstractBorder;
import javax.swing.border.Border;
import javax.swing.DefaultComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;

final class PanelUi
{
	static final int NUMERIC_CONTROL_WIDTH = 96;
	static final int SELECTOR_CONTROL_WIDTH = 108;
	static final String DURATION_LABEL = "Duration (ms)";

	private PanelUi()
	{
	}

	static JComboBox<HapticPatternSelection> createPatternComboBox(
		Supplier<CustomPatternLibrary> librarySupplier)
	{
		CustomPatternLibrary library = librarySupplier.get();
		HapticPatternSelection[] choices = HapticPatternSelection
			.availableSelections(library)
			.toArray(new HapticPatternSelection[0]);
		JComboBox<HapticPatternSelection> comboBox = new JComboBox<>(choices);
		comboBox.setRenderer(new DefaultListCellRenderer()
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
				setText(value instanceof HapticPatternSelection
					? ((HapticPatternSelection) value).getDisplayName(librarySupplier.get())
					: "");
				return this;
			}
		});
		setFixedWidth(comboBox, SELECTOR_CONTROL_WIDTH);
		return comboBox;
	}

	static void setPatternChoices(
		JComboBox<HapticPatternSelection> comboBox,
		HapticPatternSelection selected,
		CustomPatternLibrary library)
	{
		HapticPatternSelection[] choices = HapticPatternSelection
			.availableSelections(library)
			.toArray(new HapticPatternSelection[0]);
		comboBox.setModel(new DefaultComboBoxModel<>(choices));
		comboBox.setSelectedItem(selected.resolveAgainst(library));
		comboBox.repaint();
	}

	static void setFixedWidth(JComponent component, int width)
	{
		Dimension preferredSize = component.getPreferredSize();
		Dimension fixedSize = new Dimension(Math.max(width, preferredSize.width), preferredSize.height);
		component.setPreferredSize(fixedSize);
		component.setMinimumSize(fixedSize);
		component.setMaximumSize(fixedSize);
	}

	/**
	 * Keeps a component at its current preferred height while allowing its width
	 * to follow the surrounding BoxLayout. Use this only for controls whose height
	 * is intentionally stable after construction.
	 */
	static void addPreferredHeightComponent(JPanel panel, JComponent component)
	{
		Dimension preferredSize = component.getPreferredSize();
		int stableHeight = preferredSize.height;
		if (component instanceof JLabel && component.getFont() != null)
		{
			Insets insets = component.getInsets();
			stableHeight = Math.max(
				stableHeight,
				component.getFontMetrics(component.getFont()).getHeight()
					+ insets.top + insets.bottom
			);
		}
		Dimension minimumSize = component.getMinimumSize();
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		component.setMinimumSize(new Dimension(
			minimumSize.width,
			Math.max(minimumSize.height, stableHeight)
		));
		component.setMaximumSize(new Dimension(Integer.MAX_VALUE, stableHeight));
		panel.add(component);
	}

	static void reserveSingleLineTextHeight(JComponent component, int verticalPadding)
	{
		Insets insets = component.getInsets();
		int textHeight = component.getFontMetrics(component.getFont()).getHeight()
			+ insets.top + insets.bottom + Math.max(0, verticalPadding) * 2;
		Dimension preferred = component.getPreferredSize();
		Dimension minimum = component.getMinimumSize();
		component.setPreferredSize(new Dimension(
			preferred.width,
			Math.max(preferred.height, textHeight)
		));
		component.setMinimumSize(new Dimension(
			minimum.width,
			Math.max(minimum.height, textHeight)
		));
	}

	/**
	 * Supplies useful vertical space without imposing a preferred or minimum
	 * width on a component inside a responsive workspace.
	 */
	static void setFlexibleWidthHeightHint(
		JComponent component,
		int preferredHeight,
		int minimumHeight)
	{
		component.setPreferredSize(new Dimension(0, preferredHeight));
		component.setMinimumSize(new Dimension(0, minimumHeight));
	}

	/**
	 * Adds content whose preferred height may legitimately change after construction.
	 * The surrounding BoxLayout still uses the preferred height, but does not retain a
	 * stale maximum that can clip a tab page or CardLayout child later.
	 */
	static void addFlexibleVerticalComponent(JPanel panel, JComponent component)
	{
		component.setAlignmentX(Component.LEFT_ALIGNMENT);
		component.setMaximumSize(new Dimension(Integer.MAX_VALUE, Short.MAX_VALUE));
		panel.add(component);
	}

	static void addCompactTab(JTabbedPane tabs, String title, Component component)
	{
		tabs.addTab(title, component);
	}

	/**
	 * Applies the shared flat, leading-aligned workspace tab treatment. Native
	 * tab titles are intentional: unlike custom JLabel tab components, they
	 * inherit selected, hover, focus, disabled, and high-DPI states from FlatLaf.
	 */
	static void configureWorkspaceTabs(JTabbedPane tabs)
	{
		tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
		tabs.setBackground(HapticScapeTheme.CANVAS);
		tabs.setForeground(HapticScapeTheme.MUTED_TEXT);
		tabs.putClientProperty("JTabbedPane.tabType", "underlined");
		tabs.putClientProperty("JTabbedPane.tabAreaAlignment", "leading");
		tabs.putClientProperty("JTabbedPane.tabAlignment", "leading");
		tabs.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
		tabs.putClientProperty("JTabbedPane.tabsPopupPolicy", "asNeeded");
		tabs.putClientProperty("JTabbedPane.showTabSeparators", true);
	}

	/** Styles a toggle as a compact mode tab without changing its button model. */
	static void configureModeTab(javax.swing.JToggleButton button)
	{
		button.setFocusPainted(true);
		button.putClientProperty("JButton.buttonType", "tab");
		button.putClientProperty(
			"FlatLaf.style",
			"arc: 0; focusWidth: 1; innerFocusWidth: 0; borderWidth: 0;"
				+ " background: #232323; foreground: #A9B0BA;"
				+ " tab.underlineHeight: 2; tab.underlineColor: #F0A000;"
				+ " tab.selectedBackground: #232323; tab.selectedForeground: #F0A000;"
				+ " tab.hoverBackground: #2B2B2B; tab.hoverForeground: #F2F2F2;"
				+ " tab.focusBackground: #232323; tab.focusForeground: #F0A000;"
				+ " disabledText: #747474; margin: 3,8,3,8"
		);
	}

	/**
	 * Creates the shared rectangular section frame used throughout the desktop
	 * UI. The solid header band makes neighboring controls read as one module
	 * instead of a titled Swing fieldset floating on the canvas.
	 */
	static Border createSectionBorder(String title)
	{
		return new FlatSectionBorder(title);
	}

	private static final class FlatSectionBorder extends AbstractBorder
	{
		private static final int HORIZONTAL_PADDING = 8;
		private static final int BODY_PADDING = 7;
		private static final int HEADER_VERTICAL_PADDING = 5;
		private final String title;

		private FlatSectionBorder(String title)
		{
			this.title = title == null ? "" : title.toUpperCase(Locale.ROOT);
		}

		@Override
		public Insets getBorderInsets(Component component, Insets insets)
		{
			insets.top = headerHeight(component) + BODY_PADDING;
			insets.left = BODY_PADDING;
			insets.bottom = BODY_PADDING;
			insets.right = BODY_PADDING;
			return insets;
		}

		@Override
		public void paintBorder(
			Component component,
			Graphics graphics,
			int x,
			int y,
			int width,
			int height)
		{
			if (width <= 0 || height <= 0)
			{
				return;
			}
			Graphics2D drawing = (Graphics2D) graphics.create();
			try
			{
				drawing.setRenderingHint(
					RenderingHints.KEY_TEXT_ANTIALIASING,
					RenderingHints.VALUE_TEXT_ANTIALIAS_ON
				);
				int headerHeight = Math.min(headerHeight(component), height);
				drawing.setColor(HapticScapeTheme.SURFACE);
				drawing.fillRect(x, y, width, headerHeight);
				drawing.setColor(HapticScapeTheme.BORDER);
				drawing.drawRect(x, y, Math.max(0, width - 1), Math.max(0, height - 1));
				drawing.drawLine(x, y + headerHeight - 1, x + width - 1, y + headerHeight - 1);

				Font titleFont = titleFont(component);
				drawing.setFont(titleFont);
				drawing.setColor(HapticScapeTheme.MUTED_TEXT);
				FontMetrics metrics = drawing.getFontMetrics(titleFont);
				int baseline = y + (headerHeight - metrics.getHeight()) / 2 + metrics.getAscent();
				drawing.drawString(title, x + HORIZONTAL_PADDING, baseline);
			}
			finally
			{
				drawing.dispose();
			}
		}

		private static int headerHeight(Component component)
		{
			FontMetrics metrics = component.getFontMetrics(titleFont(component));
			return metrics.getHeight() + HEADER_VERTICAL_PADDING * 2;
		}

		private static Font titleFont(Component component)
		{
			Font base = component.getFont();
			if (base == null)
			{
				base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
			}
			return base.deriveFont(Font.BOLD, Math.max(9.0f, base.getSize2D() - 1.0f));
		}
	}
}
