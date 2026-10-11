package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.update.HapticScapeVersion;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToggleButton;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/** Fixed application navigation surrounding one independently scrolling workspace. */
final class WorkspaceShell extends JPanel
{
	private static final int COMPACT_BREAKPOINT = 960;
	private static final int WIDE_DOCK_BREAKPOINT = 1600;
	private static final int RAIL_WIDTH = 164;
	private static final int WORKSPACE_CONTENT_WIDTH = 1000;
	private static final int WIDE_DOCK_GAP = 8;
	private static final int WIDE_DOCK_MAX_WIDTH = 900;

	private final CardLayout cards = new CardLayout();
	private final JPanel content = new JPanel(cards);
	private final JPanel contentWidthHost = new BoundedWidthHost(content);
	private final JPanel wideDockHost = new JPanel(new BorderLayout());
	private final WorkspaceBody workspaceBody = new WorkspaceBody();
	private final ScrollableWorkspace viewportContent = new ScrollableWorkspace();
	private final JScrollPane pageScrollPane;
	private final JPanel navigationHost = new JPanel(new BorderLayout());
	private final JPanel navigationBody = new JPanel(new BorderLayout());
	private final JPanel navigation = new JPanel();
	private final JPanel brand = new JPanel();
	private final JPanel statusBar = new JPanel(new BorderLayout());
	private final Map<String, JToggleButton> buttons = new LinkedHashMap<>();
	private final Map<String, String> labels = new LinkedHashMap<>();
	private final ButtonGroup buttonGroup = new ButtonGroup();
	private Consumer<String> userSelectionAction = ignored -> { };
	private Consumer<String> selectionAction = ignored -> { };
	private Consumer<Boolean> wideDockVisibilityAction = ignored -> { };
	private Component statusComponent;
	private String selectedId;
	private boolean compact;
	private boolean wideDockVisible;

	WorkspaceShell(JScrollPane pageScrollPane)
	{
		super(new BorderLayout());
		this.pageScrollPane = Objects.requireNonNull(pageScrollPane, "pageScrollPane");
		setName("workspaceShell");
		setBackground(HapticScapeTheme.CANVAS);

		configureBrand();
		navigationHost.setName("workspaceNavigationHost");
		navigationHost.setBackground(HapticScapeTheme.SIDEBAR);
		navigationBody.setBackground(HapticScapeTheme.SIDEBAR);
		navigation.setName("workspaceNavigation");
		navigation.setBackground(HapticScapeTheme.SIDEBAR);
		statusBar.setName("workspaceStatusBar");
		statusBar.setBackground(HapticScapeTheme.SURFACE);
		statusBar.setBorder(BorderFactory.createMatteBorder(
			1,
			0,
			0,
			0,
			HapticScapeTheme.BORDER
		));

		navigationBody.add(navigation, BorderLayout.NORTH);
		navigationHost.add(brand, BorderLayout.NORTH);
		navigationHost.add(navigationBody, BorderLayout.CENTER);

		viewportContent.setName("workspaceViewportContent");
		viewportContent.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
		content.setName("workspaceCards");
		contentWidthHost.setName("workspaceContentWidthHost");
		wideDockHost.setName("workspaceWideDock");
		wideDockHost.setOpaque(false);
		wideDockHost.setVisible(false);
		workspaceBody.setName("workspaceBody");
		viewportContent.add(workspaceBody, BorderLayout.CENTER);
		pageScrollPane.setName("workspacePageScrollPane");
		pageScrollPane.setBorder(BorderFactory.createEmptyBorder());
		pageScrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		pageScrollPane.getVerticalScrollBar().setUnitIncrement(16);
		pageScrollPane.setViewportView(viewportContent);

		add(navigationHost, BorderLayout.WEST);
		add(pageScrollPane, BorderLayout.CENTER);
		add(statusBar, BorderLayout.SOUTH);
		setNavigationOrientation(false);
		addComponentListener(new ComponentAdapter()
		{
			@Override
			public void componentResized(ComponentEvent event)
			{
				refreshResponsiveLayout();
			}
		});
	}

	void addWorkspace(String id, String label, Component component)
	{
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(label, "label");
		Objects.requireNonNull(component, "component");
		if (buttons.containsKey(id))
		{
			throw new IllegalArgumentException("Duplicate workspace: " + id);
		}
		JToggleButton button = new JToggleButton(label);
		button.setName("workspace-" + id);
		button.setHorizontalAlignment(JButton.LEFT);
		button.setFocusPainted(true);
		button.setBackground(HapticScapeTheme.SIDEBAR);
		button.putClientProperty("JButton.buttonType", "toolBarButton");
		button.putClientProperty(
			"FlatLaf.style",
			"arc: 0; focusWidth: 0; innerFocusWidth: 0; borderWidth: 0;"
				+ " background: #1F1F1F; foreground: #F2F2F2;"
				+ " toolbar.hoverBackground: #2B2B2B; toolbar.hoverForeground: #F2F2F2;"
				+ " toolbar.pressedBackground: #373737; toolbar.pressedForeground: #F2F2F2;"
				+ " toolbar.selectedBackground: #2B2B2B; toolbar.selectedForeground: #F2F2F2;"
				+ " disabledText: #747474"
		);
		button.addFocusListener(new FocusAdapter()
		{
			@Override
			public void focusGained(FocusEvent event)
			{
				updateNavigationBorders();
			}

			@Override
			public void focusLost(FocusEvent event)
			{
				updateNavigationBorders();
			}
		});
		button.addActionListener(event ->
		{
			showWorkspace(id);
			userSelectionAction.accept(id);
		});
		buttons.put(id, button);
		labels.put(id, label);
		buttonGroup.add(button);
		navigation.add(button);
		content.add(component, id);
		if (selectedId == null)
		{
			showWorkspace(id);
		}
	}

	void setStatusComponent(Component component)
	{
		statusComponent = Objects.requireNonNull(component, "component");
		placeStatusComponent();
	}

	void setUserSelectionAction(Consumer<String> action)
	{
		userSelectionAction = Objects.requireNonNull(action, "action");
	}

	void setSelectionAction(Consumer<String> action)
	{
		selectionAction = Objects.requireNonNull(action, "action");
		if (selectedId != null)
		{
			selectionAction.accept(selectedId);
		}
	}

	void setWideDockVisibilityAction(Consumer<Boolean> action)
	{
		wideDockVisibilityAction = Objects.requireNonNull(action, "action");
		wideDockVisibilityAction.accept(wideDockVisible);
	}

	void setWideDockComponent(Component component)
	{
		Objects.requireNonNull(component, "component");
		wideDockHost.removeAll();
		wideDockHost.add(component, BorderLayout.CENTER);
		workspaceBody.revalidate();
		workspaceBody.repaint();
	}

	void clearWideDockComponent(Component component)
	{
		if (component != null && component.getParent() == wideDockHost)
		{
			wideDockHost.remove(component);
			workspaceBody.revalidate();
			workspaceBody.repaint();
		}
	}

	boolean isWideDockVisible()
	{
		return wideDockVisible;
	}

	void showWorkspace(String id)
	{
		JToggleButton button = buttons.get(id);
		if (button == null)
		{
			throw new IllegalArgumentException("Unknown workspace: " + id);
		}
		boolean changed = !id.equals(selectedId);
		selectedId = id;
		button.setSelected(true);
		cards.show(content, id);
		updateNavigationBorders();
		content.revalidate();
		content.repaint();
		if (changed)
		{
			selectionAction.accept(id);
		}
	}

	String getSelectedWorkspace()
	{
		return selectedId;
	}

	String getWorkspaceLabel(String id)
	{
		String label = labels.get(id);
		if (label == null)
		{
			throw new IllegalArgumentException("Unknown workspace: " + id);
		}
		return label;
	}

	private void configureBrand()
	{
		brand.setName("workspaceBrand");
		brand.setLayout(new javax.swing.BoxLayout(brand, javax.swing.BoxLayout.Y_AXIS));
		brand.setBackground(HapticScapeTheme.SIDEBAR);
		brand.setBorder(BorderFactory.createEmptyBorder(16, 12, 12, 10));
		JLabel name = new JLabel("HapticScape");
		name.setName("workspaceBrandName");
		name.setForeground(HapticScapeTheme.ACCENT);
		name.setFont(name.getFont().deriveFont(Font.BOLD));
		JLabel version = new JLabel(displayVersion());
		version.setName("workspaceBrandVersion");
		version.setForeground(HapticScapeTheme.MUTED_TEXT);
		version.setFont(version.getFont().deriveFont(
			Math.max(9.0f, version.getFont().getSize2D() - 1.0f)
		));
		brand.add(name);
		brand.add(javax.swing.Box.createVerticalStrut(5));
		brand.add(version);
	}

	private static String displayVersion()
	{
		String version = HapticScapeVersion.current();
		return "development".equalsIgnoreCase(version) ? version : "v" + version;
	}

	private void refreshResponsiveLayout()
	{
		boolean shouldBeCompact = getWidth() > 0 && getWidth() < COMPACT_BREAKPOINT;
		boolean shouldShowWideDock = getWidth() >= WIDE_DOCK_BREAKPOINT;
		boolean changed = false;
		if (shouldBeCompact != compact)
		{
			compact = shouldBeCompact;
			remove(navigationHost);
			brand.setVisible(!compact);
			add(navigationHost, compact ? BorderLayout.NORTH : BorderLayout.WEST);
			setNavigationOrientation(compact);
			changed = true;
		}
		if (shouldShowWideDock != wideDockVisible)
		{
			wideDockVisible = shouldShowWideDock;
			wideDockHost.setVisible(wideDockVisible);
			workspaceBody.revalidate();
			wideDockVisibilityAction.accept(wideDockVisible);
			changed = true;
		}
		if (changed)
		{
			revalidate();
			repaint();
		}
	}

	private void placeStatusComponent()
	{
		statusBar.removeAll();
		if (statusComponent != null)
		{
			statusBar.add(statusComponent, BorderLayout.CENTER);
		}
		statusBar.revalidate();
		statusBar.repaint();
	}

	private void setNavigationOrientation(boolean horizontal)
	{
		navigation.setLayout(horizontal
			? new GridLayout(1, 0, 0, 0)
			: new GridLayout(0, 1, 0, 0));
		navigationHost.setBorder(horizontal
			? BorderFactory.createMatteBorder(0, 0, 1, 0, HapticScapeTheme.BORDER)
			: BorderFactory.createMatteBorder(0, 0, 0, 1, HapticScapeTheme.BORDER));
		navigationHost.setPreferredSize(horizontal ? null : new Dimension(RAIL_WIDTH, 0));
		updateNavigationBorders();
	}

	private void updateNavigationBorders()
	{
		for (Map.Entry<String, JToggleButton> entry : buttons.entrySet())
		{
			boolean selected = entry.getKey().equals(selectedId);
			JToggleButton button = entry.getValue();
			Color marker = selected ? HapticScapeTheme.ACCENT : HapticScapeTheme.SIDEBAR;
			Color focus = button.isFocusOwner()
				? HapticScapeTheme.ACCENT_HOVER
				: (selected ? HapticScapeTheme.SELECTION : HapticScapeTheme.SIDEBAR);
			button.setBorder(BorderFactory.createCompoundBorder(
				compact
					? BorderFactory.createMatteBorder(0, 0, 2, 0, marker)
					: BorderFactory.createMatteBorder(0, 2, 0, 0, marker),
				BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(focus),
					BorderFactory.createEmptyBorder(3, compact ? 5 : 9, 3, 5)
				)
			));
			button.setHorizontalAlignment(compact ? JButton.CENTER : JButton.LEFT);
		}
	}

	private static final class ScrollableWorkspace extends JPanel implements Scrollable
	{
		private ScrollableWorkspace()
		{
			super(new BorderLayout(0, 8));
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(
			Rectangle visibleRect,
			int orientation,
			int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(
			Rectangle visibleRect,
			int orientation,
			int direction)
		{
			return Math.max(16, orientation == SwingConstants.VERTICAL
				? visibleRect.height - 16
				: visibleRect.width - 16);
		}

		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return getParent() != null && getParent().getHeight() > getPreferredSize().height;
		}
	}

	static int workspaceContentWidthFor(int availableWidth)
	{
		return Math.max(0, Math.min(WORKSPACE_CONTENT_WIDTH, availableWidth));
	}

	private final class WorkspaceBody extends JPanel
	{
		private WorkspaceBody()
		{
			super(null);
			setOpaque(false);
			add(contentWidthHost);
			add(wideDockHost);
		}

		@Override
		public void doLayout()
		{
			int leftWidth = workspaceContentWidthFor(getWidth());
			contentWidthHost.setBounds(0, 0, leftWidth, getHeight());
			if (!wideDockVisible)
			{
				wideDockHost.setBounds(0, 0, 0, 0);
				return;
			}
			int dockX = Math.min(getWidth(), leftWidth + WIDE_DOCK_GAP);
			int dockWidth = Math.min(
				WIDE_DOCK_MAX_WIDTH,
				Math.max(0, getWidth() - dockX)
			);
			wideDockHost.setBounds(dockX, 0, dockWidth, getHeight());
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension left = contentWidthHost.getPreferredSize();
			if (!wideDockVisible)
			{
				return left;
			}
			Dimension dock = wideDockHost.getPreferredSize();
			return new Dimension(
				left.width + WIDE_DOCK_GAP
					+ Math.min(WIDE_DOCK_MAX_WIDTH, dock.width),
				Math.max(left.height, dock.height)
			);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return new Dimension(0, Math.max(
				contentWidthHost.getMinimumSize().height,
				wideDockVisible ? wideDockHost.getMinimumSize().height : 0
			));
		}
	}

	/** Keeps the desktop composition stable while still filling narrow windows. */
	private static final class BoundedWidthHost extends JPanel
	{
		private final Component child;

		private BoundedWidthHost(Component child)
		{
			super(null);
			this.child = Objects.requireNonNull(child, "child");
			setOpaque(false);
			add(child);
		}

		@Override
		public void doLayout()
		{
			child.setBounds(0, 0, workspaceContentWidthFor(getWidth()), getHeight());
		}

		@Override
		public Dimension getPreferredSize()
		{
			Dimension preferred = child.getPreferredSize();
			return new Dimension(
				workspaceContentWidthFor(preferred.width),
				preferred.height
			);
		}

		@Override
		public Dimension getMinimumSize()
		{
			return new Dimension(0, child.getMinimumSize().height);
		}
	}
}
