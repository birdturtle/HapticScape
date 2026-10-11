package com.ashy0019.hapticscape.ui;

import java.awt.Dimension;
import java.awt.Insets;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class PanelUiTest
{
	@Test
	public void controlWidthsKeepRoomForTheirText() throws Exception
	{
		SwingUtilities.invokeAndWait(() ->
		{
			javax.swing.JComboBox<String> selector = new javax.swing.JComboBox<>(
				new String[] { "Ascending", "A longer custom pattern name" });
			int needed = selector.getPreferredSize().width;
			PanelUi.setFixedWidth(selector, PanelUi.SELECTOR_CONTROL_WIDTH);
			assertTrue(selector.getPreferredSize().width >= needed);
			assertTrue(selector.getMinimumSize().width >= needed);
		});
	}
	@Test
	public void preferredHeightRowsReserveSpaceForTextAddedLater() throws Exception
	{
		AtomicReference<JLabel> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			JPanel host = new JPanel();
			host.setLayout(new BoxLayout(host, BoxLayout.Y_AXIS));
			JLabel dynamic = new JLabel();
			dynamic.setBorder(BorderFactory.createEmptyBorder(3, 2, 4, 2));
			PanelUi.addPreferredHeightComponent(host, dynamic);
			dynamic.setText("Uses the settings in Generic defaults.");
			result.set(dynamic);
		});

		JLabel dynamic = result.get();
		int requiredHeight = dynamic.getFontMetrics(dynamic.getFont()).getHeight()
			+ dynamic.getInsets().top + dynamic.getInsets().bottom;
		assertTrue(dynamic.getMinimumSize().height >= requiredHeight);
		assertTrue(dynamic.getMaximumSize().height >= requiredHeight);
	}

	@Test
	public void flexibleWidthHeightHintDoesNotImposeOldWorkspaceWidths()
		throws Exception
	{
		AtomicReference<Dimension> preferredSize = new AtomicReference<>();
		AtomicReference<Dimension> minimumSize = new AtomicReference<>();

		SwingUtilities.invokeAndWait(() ->
		{
			JScrollPane scrollPane = new JScrollPane();
			PanelUi.setFlexibleWidthHeightHint(scrollPane, 210, 120);
			preferredSize.set(scrollPane.getPreferredSize());
			minimumSize.set(scrollPane.getMinimumSize());
		});

		assertEquals(new Dimension(0, 210), preferredSize.get());
		assertEquals(new Dimension(0, 120), minimumSize.get());
	}

	@Test
	public void compactTabsUseNativeStateAwareLabels() throws Exception
	{
		AtomicReference<JTabbedPane> result = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			JTabbedPane tabs = new JTabbedPane();
			PanelUi.configureWorkspaceTabs(tabs);
			PanelUi.addCompactTab(tabs, "XP + Skills", new JPanel());
			PanelUi.addCompactTab(tabs, "Phrases", new JPanel());
			result.set(tabs);
		});

		JTabbedPane tabs = result.get();
		assertEquals("XP + Skills", tabs.getTitleAt(0));
		assertEquals("Phrases", tabs.getTitleAt(1));
		assertNull(tabs.getTabComponentAt(0));
		assertEquals(JTabbedPane.SCROLL_TAB_LAYOUT, tabs.getTabLayoutPolicy());
		assertEquals("underlined", tabs.getClientProperty("JTabbedPane.tabType"));
	}

	@Test
	public void sectionBorderReservesAFullHeaderBand() throws Exception
	{
		AtomicReference<Insets> insets = new AtomicReference<>();
		AtomicReference<Integer> headerColor = new AtomicReference<>();
		AtomicReference<Integer> bodyColor = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			JPanel panel = new JPanel();
			panel.setSize(220, 100);
			panel.setBorder(PanelUi.createSectionBorder("Example"));
			insets.set(panel.getBorder().getBorderInsets(panel));
			BufferedImage image = new BufferedImage(220, 100, BufferedImage.TYPE_INT_ARGB);
			panel.getBorder().paintBorder(panel, image.getGraphics(), 0, 0, 220, 100);
			headerColor.set(image.getRGB(100, 8));
			bodyColor.set(image.getRGB(100, 70));
		});

		assertTrue(insets.get().top > insets.get().bottom);
		assertEquals(insets.get().left, insets.get().right);
		assertEquals(HapticScapeTheme.SURFACE.getRGB(), (int) headerColor.get());
		assertTrue(bodyColor.get() != HapticScapeTheme.SURFACE.getRGB());
	}

	@Test
	public void flexibleVerticalComponentAllowsTabHeightToGrow() throws Exception
	{
		AtomicReference<Dimension> initialSize = new AtomicReference<>();
		AtomicReference<Dimension> grownSize = new AtomicReference<>();
		AtomicReference<Dimension> maximumSize = new AtomicReference<>();
		AtomicReference<Dimension> alternateTabSize = new AtomicReference<>();
		AtomicReference<Dimension> laidOutContentSize = new AtomicReference<>();

		SwingUtilities.invokeAndWait(() ->
		{
			JPanel host = new JPanel();
			host.setLayout(new BoxLayout(host, BoxLayout.Y_AXIS));

			JPanel shortTab = new JPanel();
			shortTab.setPreferredSize(new Dimension(180, 100));
			JPanel growingTab = new JPanel();
			growingTab.setPreferredSize(new Dimension(180, 160));
			JTabbedPane tabs = new JTabbedPane();
			tabs.addTab("Skills", shortTab);
			tabs.addTab("Click", growingTab);
			PanelUi.addFlexibleVerticalComponent(host, tabs);

			initialSize.set(tabs.getPreferredSize());
			growingTab.setPreferredSize(new Dimension(180, 320));
			growingTab.revalidate();
			grownSize.set(tabs.getPreferredSize());
			maximumSize.set(tabs.getMaximumSize());

			tabs.setSelectedComponent(growingTab);
			alternateTabSize.set(tabs.getPreferredSize());
			host.setSize(host.getPreferredSize());
			host.doLayout();
			tabs.doLayout();
			laidOutContentSize.set(growingTab.getSize());
		});

		assertTrue(grownSize.get().height > initialSize.get().height);
		assertTrue(maximumSize.get().height >= grownSize.get().height);
		assertTrue(alternateTabSize.get().height >= grownSize.get().height);
		assertTrue(laidOutContentSize.get().height >= 320);
	}
}
