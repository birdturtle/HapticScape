package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.Level99CelebrationController;
import com.ashy0019.hapticscape.SkillDescriptor;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;

/** Run on a disposable graphical display; sends real mouse input. */
public final class Level99DesktopOverlaySmoke
{
	public static void main(String[] args) throws Exception
	{
		Level99CelebrationController controller = new Level99CelebrationController();
		AtomicInteger clicks = new AtomicInteger();
		JFrame[] frames = new JFrame[2];
		Level99DesktopOverlay[] host = new Level99DesktopOverlay[1];
		try
		{
			SwingUtilities.invokeAndWait(() -> {
				frames[0] = new JFrame("Underlying game test");
				JButton button = new JButton("Click through target");
				button.addActionListener(event -> clicks.incrementAndGet());
				frames[0].setContentPane(button);
				frames[0].setBounds(0, 0, 1000, 500);
				frames[0].setVisible(true);
				frames[1] = new JFrame("Hidden HapticScape test");
				host[0] = new Level99DesktopOverlay(controller, frames[1], new StandaloneLevel99GlassPane(controller));
			});
			Robot robot = new Robot();
			robot.mouseMove(500, 100);
			controller.start(new SkillDescriptor("attack", "Attack"));
			Thread.sleep(500);
			SwingUtilities.invokeAndWait(() -> {
				Window overlay = overlay();
				if (overlay == null || !overlay.isVisible()) throw new AssertionError("Desktop overlay did not appear with hidden owner");
				if (overlay.isFocusableWindow()) throw new AssertionError("Overlay can take keyboard focus");
			});
			robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
			robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
			robot.waitForIdle();
			if (clicks.get() != 1) throw new AssertionError("Overlay intercepted mouse input");
			controller.reset();
			Thread.sleep(250);
			SwingUtilities.invokeAndWait(() -> {
				if (overlay().isVisible()) throw new AssertionError("Reset left overlay visible");
			});
			controller.start(new SkillDescriptor("mining", "Mining"));
			Thread.sleep(250);
			SwingUtilities.invokeAndWait(() -> {
				if (!overlay().isVisible()) throw new AssertionError("Second celebration did not appear");
				host[0].close();
				if (overlay() != null) throw new AssertionError("Close left overlay displayable");
			});
			System.out.println("Hidden-owner celebration, native click-through, non-focusable window, reset, repeat and disposal passed.");
		}
		finally
		{
			SwingUtilities.invokeAndWait(() -> {
				if (host[0] != null) host[0].close();
				for (JFrame frame : frames) if (frame != null) frame.dispose();
			});
		}
	}

	private static Window overlay()
	{
		for (Window window : Window.getWindows())
			if (window instanceof JWindow && window.isDisplayable()) return window;
		return null;
	}
}
