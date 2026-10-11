package com.ashy0019.hapticscape.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.ComponentEvent;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import org.junit.Test;

public class WorkspaceShellTest
{
	@Test
	public void navigationSelectsCardsAndReportsOnlyUserSelections() throws Exception
	{
		AtomicReference<String> selected = new AtomicReference<>();
		WorkspaceShell shell = onEdt(() ->
		{
			WorkspaceShell created = new WorkspaceShell(new JScrollPane());
			created.addWorkspace("gameplay", "Gameplay", new JPanel());
			created.addWorkspace("remote", "Remote Play", new JPanel());
			created.setUserSelectionAction(selected::set);
			return created;
		});
		assertEquals("gameplay", shell.getSelectedWorkspace());
		onEdt(() ->
		{
			component(shell, "workspace-remote", AbstractButton.class).doClick();
			return null;
		});
		assertEquals("remote", shell.getSelectedWorkspace());
		assertEquals("remote", selected.get());
		assertTrue(component(shell, "workspace-remote", AbstractButton.class).isFocusPainted());
		assertEquals(
			"toolBarButton",
			component(shell, "workspace-remote", javax.swing.JComponent.class)
				.getClientProperty("JButton.buttonType")
		);

		onEdt(() ->
		{
			shell.showWorkspace("gameplay");
			return null;
		});
		assertEquals("remote", selected.get());
	}

	@Test
	public void navigationMovesAboveContentAtCompactWidth() throws Exception
	{
		WorkspaceShell shell = onEdt(() ->
		{
			WorkspaceShell created = new WorkspaceShell(new JScrollPane());
			created.addWorkspace("gameplay", "Gameplay", new JPanel());
			created.addWorkspace("remote", "Remote Play", new JPanel());
			return created;
		});
		Container navigationHost = component(
			shell,
			"workspaceNavigationHost",
			Container.class
		);
		onEdt(() ->
		{
			shell.setSize(500, 600);
			shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
			return null;
		});
		assertEquals(
			BorderLayout.NORTH,
			((BorderLayout) shell.getLayout()).getConstraints(navigationHost)
		);
		onEdt(() ->
		{
			shell.setSize(900, 600);
			shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
			return null;
		});
		assertEquals(
			BorderLayout.NORTH,
			((BorderLayout) shell.getLayout()).getConstraints(navigationHost)
		);
		onEdt(() ->
		{
			shell.setSize(1000, 600);
			shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
			return null;
		});
		assertEquals(
			BorderLayout.WEST,
			((BorderLayout) shell.getLayout()).getConstraints(navigationHost)
		);
		assertTrue(navigationHost.getPreferredSize().width >= 140);
	}

	@Test
	public void workspaceCompositionStopsGrowingAtReferenceWidth()
		throws Exception
	{
		assertEquals(0, WorkspaceShell.workspaceContentWidthFor(-20));
		assertEquals(520, WorkspaceShell.workspaceContentWidthFor(520));
		assertEquals(800, WorkspaceShell.workspaceContentWidthFor(800));
		assertEquals(1000, WorkspaceShell.workspaceContentWidthFor(1600));

		WorkspaceShell shell = onEdt(() ->
		{
			WorkspaceShell created = new WorkspaceShell(new JScrollPane());
			created.addWorkspace("gameplay", "Gameplay", new JPanel());
			return created;
		});
		Container widthHost = component(
			shell,
			"workspaceContentWidthHost",
			Container.class
		);
		Component cards = component(shell, "workspaceCards", Component.class);
		onEdt(() ->
		{
			widthHost.setSize(1400, 600);
			widthHost.doLayout();
			return null;
		});
		assertEquals(1000, cards.getWidth());
		onEdt(() ->
		{
			widthHost.setSize(620, 600);
			widthHost.doLayout();
			return null;
		});
		assertEquals(620, cards.getWidth());
	}

	@Test
	public void wideWindowsExposeAnAdjacentDockWithoutStretchingTheWorkspace()
		throws Exception
	{
		AtomicReference<Boolean> dockVisible = new AtomicReference<>();
		JPanel dockContent = new JPanel();
		WorkspaceShell shell = onEdt(() ->
		{
			WorkspaceShell created = new WorkspaceShell(new JScrollPane());
			created.addWorkspace("gameplay", "Gameplay", new JPanel());
			created.setWideDockComponent(dockContent);
			created.setWideDockVisibilityAction(dockVisible::set);
			return created;
		});
		Container dock = component(shell, "workspaceWideDock", Container.class);
		Container body = component(shell, "workspaceBody", Container.class);
		Component cards = component(shell, "workspaceCards", Component.class);

		onEdt(() ->
		{
			shell.setSize(1599, 700);
			shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
			return null;
		});
		assertFalse(shell.isWideDockVisible());
		assertEquals(Boolean.FALSE, dockVisible.get());
		assertFalse(dock.isVisible());

		onEdt(() ->
		{
			shell.setSize(1600, 700);
			shell.dispatchEvent(new ComponentEvent(shell, ComponentEvent.COMPONENT_RESIZED));
			body.setSize(1412, 650);
			body.doLayout();
			component(shell, "workspaceContentWidthHost", Container.class).doLayout();
			return null;
		});
		assertTrue(shell.isWideDockVisible());
		assertEquals(Boolean.TRUE, dockVisible.get());
		assertTrue(dock.isVisible());
		assertSame(dock, dockContent.getParent());
		assertEquals(1000, cards.getWidth());
		assertEquals(1008, dock.getX());
		assertEquals(404, dock.getWidth());
	}

	@Test
	public void suppliedViewportScrollsInsideFixedShell() throws Exception
	{
		JScrollPane page = new JScrollPane();
		JPanel status = new JPanel();
		status.setPreferredSize(new java.awt.Dimension(100, 32));
		WorkspaceShell shell = onEdt(() ->
		{
			WorkspaceShell created = new WorkspaceShell(page);
			created.addWorkspace("gameplay", "Gameplay", new JPanel());
			created.setStatusComponent(status);
			return created;
		});

		assertSame(
			component(shell, "workspaceViewportContent", JPanel.class),
			page.getViewport().getView()
		);
		assertEquals(
			BorderLayout.CENTER,
			((BorderLayout) shell.getLayout()).getConstraints(page)
		);
		assertSame(
			component(shell, "workspaceStatusBar", Container.class),
			status.getParent()
		);
		assertEquals(
			BorderLayout.SOUTH,
			((BorderLayout) shell.getLayout()).getConstraints(status.getParent())
		);
		onEdt(() ->
		{
			shell.setSize(1000, 700);
			shell.doLayout();
			return null;
		});
		Container footer = status.getParent();
		assertEquals(shell.getHeight(), footer.getY() + footer.getHeight());
	}

	@Test
	public void selectionActionTracksProgrammaticWorkspaceChanges() throws Exception
	{
		AtomicReference<String> selected = new AtomicReference<>();
		WorkspaceShell shell = onEdt(() ->
		{
			WorkspaceShell created = new WorkspaceShell(new JScrollPane());
			created.addWorkspace("gameplay", "Gameplay", new JPanel());
			created.addWorkspace("remote", "Remote Play", new JPanel());
			created.setSelectionAction(selected::set);
			return created;
		});
		assertEquals("gameplay", selected.get());

		onEdt(() ->
		{
			shell.showWorkspace("remote");
			return null;
		});
		assertEquals("remote", selected.get());
		assertEquals("Remote Play", shell.getWorkspaceLabel("remote"));
	}

	private static <T extends Component> T component(
		Container root,
		String name,
		Class<T> type)
	{
		for (Component candidate : root.getComponents())
		{
			if (name.equals(candidate.getName()) && type.isInstance(candidate))
			{
				return type.cast(candidate);
			}
			if (candidate instanceof Container)
			{
				try
				{
					return component((Container) candidate, name, type);
				}
				catch (AssertionError ignored)
				{
					// Continue through the remaining branches.
				}
			}
		}
		throw new AssertionError("Missing component: " + name);
	}

	private static <T> T onEdt(Callable<T> operation) throws Exception
	{
		AtomicReference<T> result = new AtomicReference<>();
		AtomicReference<Exception> failure = new AtomicReference<>();
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				result.set(operation.call());
			}
			catch (Exception error)
			{
				failure.set(error);
			}
		});
		if (failure.get() != null)
		{
			throw failure.get();
		}
		return result.get();
	}
}
