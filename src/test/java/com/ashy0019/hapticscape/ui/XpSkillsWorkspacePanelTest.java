package com.ashy0019.hapticscape.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class XpSkillsWorkspacePanelTest
{
	@Test
	public void choosesLayoutForAvailableDesktopWidth()
	{
		assertEquals(1, XpSkillsWorkspacePanel.layoutModeForWidth(500));
		assertEquals(2, XpSkillsWorkspacePanel.layoutModeForWidth(760));
		assertEquals(2, XpSkillsWorkspacePanel.layoutModeForWidth(1179));
		assertEquals(3, XpSkillsWorkspacePanel.layoutModeForWidth(1180));
	}

	@Test
	public void stacksBeforeMeasuredControlsWouldBeCompressed()
	{
		assertEquals(1, XpSkillsWorkspacePanel.layoutModeForWidth(800, 500, 360, 400));
		assertEquals(2, XpSkillsWorkspacePanel.layoutModeForWidth(1000, 500, 360, 400));
		assertEquals(2, XpSkillsWorkspacePanel.layoutModeForWidth(1180, 500, 360, 400));
		assertEquals(3, XpSkillsWorkspacePanel.layoutModeForWidth(1400, 500, 360, 400));
	}
}
