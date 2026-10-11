package com.ashy0019.hapticscape.ui;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import javax.swing.BorderFactory;
import javax.swing.JPanel;

/**
 * Purpose-built XP workspace which keeps related controls visible together.
 * It uses three columns on a desktop, a two-column editor at medium widths,
 * and a single vertical flow when the window is narrow.
 */
final class XpSkillsWorkspacePanel extends JPanel
{
	private static final int WIDE_BREAKPOINT = 1180;
	private static final int MEDIUM_BREAKPOINT = 760;

	private final JPanel layoutPanel = new JPanel(new GridBagLayout());
	private final JPanel skillsHost;
	private final JPanel defaultsHost;
	private final JPanel overrideHost;
	private int layoutMode = -1;

	XpSkillsWorkspacePanel(
		JPanel globalSettings,
		SkillsPanel skills,
		ProfilesPanel profiles)
	{
		super(new BorderLayout());
		setName("xpSkillsWorkspace");
		setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
		skillsHost = host(skills, 360);
		defaultsHost = host(globalSettings, 360);
		overrideHost = host(profiles, 360);
		skills.setSkillSelectionAction(profiles::selectSkill);
		add(layoutPanel, BorderLayout.NORTH);
		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent event)
			{
				reflow();
			}
		});
		reflow();
	}

	int getLayoutMode()
	{
		return layoutMode;
	}

	@Override
	public void doLayout()
	{
		reflow();
		super.doLayout();
	}

	private void reflow()
	{
		int width = getWidth();
		int desired = layoutModeForWidth(width, skillsHost.getPreferredSize().width,
			defaultsHost.getPreferredSize().width, overrideHost.getPreferredSize().width);
		if (desired == layoutMode)
		{
			return;
		}
		layoutMode = desired;
		layoutPanel.removeAll();
		if (layoutMode == 3)
		{
			addSection(skillsHost, 0, 0, 1, 1, 0.0, 0.0, GridBagConstraints.HORIZONTAL);
			addSection(defaultsHost, 1, 0, 1, 1, 0.0, 0.0, GridBagConstraints.HORIZONTAL);
			addSection(overrideHost, 2, 0, 1, 1, 0.0, 0.0, GridBagConstraints.HORIZONTAL);
			addRemainder(3, 0);
		}
		else if (layoutMode == 2)
		{
			addSection(skillsHost, 0, 0, 1, 2, 0.48, 1.0, GridBagConstraints.BOTH);
			addSection(defaultsHost, 1, 0, 1, 1, 0.52, 0.0, GridBagConstraints.HORIZONTAL);
			addSection(overrideHost, 1, 1, 1, 1, 0.52, 1.0, GridBagConstraints.HORIZONTAL);
		}
		else
		{
			addSection(skillsHost, 0, 0, 1, 1, 1.0, 0.0, GridBagConstraints.HORIZONTAL);
			addSection(defaultsHost, 0, 1, 1, 1, 1.0, 0.0, GridBagConstraints.HORIZONTAL);
			addSection(overrideHost, 0, 2, 1, 1, 1.0, 0.0, GridBagConstraints.HORIZONTAL);
		}
		layoutPanel.revalidate();
		layoutPanel.repaint();
	}

	static int layoutModeForWidth(int width)
	{
		return layoutModeForWidth(width, 360, 360, 360);
	}

	static int layoutModeForWidth(int width, int skills, int defaults, int overrides)
	{
		int wide = Math.max(WIDE_BREAKPOINT, skills + defaults + overrides + 31);
		int medium = Math.max(MEDIUM_BREAKPOINT, skills + Math.max(defaults, overrides) + 24);
		return width >= wide ? 3 : width >= medium ? 2 : 1;
	}

	private void addSection(
		Component component,
		int x,
		int y,
		int width,
		int height,
		double weightX,
		double weightY,
		int fill)
	{
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = x;
		constraints.gridy = y;
		constraints.gridwidth = width;
		constraints.gridheight = height;
		constraints.weightx = weightX;
		constraints.weighty = weightY;
		constraints.fill = fill;
		constraints.anchor = GridBagConstraints.NORTHWEST;
		constraints.insets = new Insets(0, 0, 7, 7);
		layoutPanel.add(component, constraints);
	}

	private void addRemainder(int x, int y)
	{
		GridBagConstraints constraints = new GridBagConstraints();
		constraints.gridx = x;
		constraints.gridy = y;
		constraints.weightx = 1.0;
		constraints.fill = GridBagConstraints.HORIZONTAL;
		layoutPanel.add(new JPanel(), constraints);
	}

	private static JPanel host(Component component, int preferredWidth)
	{
		JPanel host = new WidthHintPanel(preferredWidth);
		host.add(component, BorderLayout.NORTH);
		return host;
	}

	/** Width stays orderly on a large desktop without freezing dynamic height. */
	private static final class WidthHintPanel extends JPanel
	{
		private final int preferredWidth;

		private WidthHintPanel(int preferredWidth)
		{
			super(new BorderLayout());
			this.preferredWidth = preferredWidth;
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension preferred = super.getPreferredSize();
			return new Dimension(Math.max(preferredWidth, preferred.width), preferred.height);
		}

		@Override
		public Dimension getMinimumSize()
		{
			Dimension minimum = super.getMinimumSize();
			return new Dimension(getPreferredSize().width, minimum.height);
		}
	}
}
