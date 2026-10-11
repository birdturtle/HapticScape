package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.Level99CelebrationController;
import com.ashy0019.hapticscape.ui.Level99CelebrationRenderer;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Platform;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.ptr.IntByReference;
import java.awt.Color;
import java.awt.AlphaComposite;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.Timer;
import javax.swing.UIManager;

/** Desktop host for the existing ceremony, independent of the main window's visibility. */
public final class Level99DesktopOverlay implements AutoCloseable
{
	private static final Logger LOG = Logger.getLogger(Level99DesktopOverlay.class.getName());
	private static final int TOP_PADDING = 16;
	private final Level99CelebrationController controller;
	private final Window owner;
	private final JComponent fallback;
	private final Timer timer;
	private CelebrationWindow window;
	private boolean unavailable;

	public Level99DesktopOverlay(Level99CelebrationController controller, Window owner, JComponent fallback)
	{
		this.controller = controller;
		this.owner = owner;
		this.fallback = fallback;
		fallback.setVisible(false);
		timer = new Timer(100, event -> update());
		timer.start();
	}

	private void update()
	{
		boolean active = controller.snapshot().isActive();
		if (unavailable)
		{
			fallback.setVisible(active);
			return;
		}
		if (!active)
		{
			if (window != null) window.setVisible(false);
			timer.setDelay(100);
			return;
		}
		try
		{
			if (window == null) createWindow();
			if (!window.isVisible())
			{
				Rectangle bounds = screen().getBounds();
				window.setSize(renderSize(bounds));
				window.setLocation(bounds.x + Math.max(0, (bounds.width - window.getWidth()) / 2), bounds.y + 24);
				// Set the input region while hidden. Never expose a window that intercepts clicks.
				makeClickThrough(window);
				window.renderFrame();
				window.setVisible(true);
			}
			window.renderFrame();
			timer.setDelay(33);
			window.repaint();
		}
		catch (RuntimeException | LinkageError error)
		{
			LOG.log(Level.WARNING, "Desktop celebration unavailable; using application overlay", error);
			if (window != null) window.dispose();
			window = null;
			unavailable = true;
			fallback.setVisible(true);
		}
	}

	private GraphicsConfiguration screen()
	{
		Point pointer = MouseInfo.getPointerInfo() == null ? null : MouseInfo.getPointerInfo().getLocation();
		for (GraphicsDevice device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
		{
			GraphicsConfiguration configuration = device.getDefaultConfiguration();
			if (pointer != null && configuration.getBounds().contains(pointer)) return configuration;
		}
		return owner.getGraphicsConfiguration();
	}

	private void createWindow()
	{
		GraphicsConfiguration configuration = screen();
		if (!configuration.getDevice().isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT))
			throw new UnsupportedOperationException("Transparent windows are unavailable");
		window = new CelebrationWindow(configuration, controller);
		window.setFocusableWindowState(false);
		window.setAutoRequestFocus(false);
		window.setType(Window.Type.POPUP);
		window.setAlwaysOnTop(true);
		window.setBackground(new Color(0, 0, 0, 0));
		window.setSize(Level99CelebrationRenderer.getRenderSize());
		window.addNotify();
	}

	/** Paint one complete frame, without clearing the visible transparent surface first. */
	private static final class CelebrationWindow extends JWindow
	{
		private final Level99CelebrationRenderer renderer;
		private BufferedImage frame;

		CelebrationWindow(GraphicsConfiguration configuration, Level99CelebrationController controller)
		{
			super(configuration);
			renderer = new Level99CelebrationRenderer(controller);
		}

		void renderFrame()
		{
			if (frame == null || frame.getWidth() != getWidth() || frame.getHeight() != getHeight())
				frame = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
			Graphics2D graphics = frame.createGraphics();
			try
			{
				graphics.setComposite(AlphaComposite.Clear);
				graphics.fillRect(0, 0, frame.getWidth(), frame.getHeight());
				graphics.setComposite(AlphaComposite.SrcOver);
				graphics.setFont(UIManager.getFont("Label.font"));
				Dimension original = Level99CelebrationRenderer.getRenderSize();
				graphics.scale((double) getWidth() / original.width,
					(double) getHeight() / (original.height + TOP_PADDING));
				graphics.translate(0, TOP_PADDING);
				renderer.render(graphics);
			}
			finally { graphics.dispose(); }
		}

		@Override public void update(Graphics graphics) { paint(graphics); }

		@Override public void paint(Graphics graphics)
		{
			if (frame == null) return;
			Graphics2D copy = (Graphics2D) graphics.create();
			try
			{
				copy.setComposite(AlphaComposite.Src);
				copy.drawImage(frame, 0, 0, null);
			}
			finally { copy.dispose(); }
		}
	}

	static Dimension renderSize(Rectangle screen)
	{
		Dimension original = Level99CelebrationRenderer.getRenderSize();
		original.height += TOP_PADDING;
		double scale = Math.min(screen.width * 0.5 / original.width, screen.height * 0.6 / original.height);
		return new Dimension(Math.max(1, (int) Math.round(original.width * scale)),
			Math.max(1, (int) Math.round(original.height * scale)));
	}

	static void makeClickThrough(Window window)
	{
		if (Platform.isWindows())
		{
			HWND handle = new HWND(Native.getComponentPointer(window));
			int index = -20; // GWL_EXSTYLE
			int flags = 0x00080000 | 0x00000020 | 0x08000000 | 0x00000080; // layered, transparent, no-activate, tool window
			int style = User32.INSTANCE.GetWindowLong(handle, index);
			Native.setLastError(0);
			int result = User32.INSTANCE.SetWindowLong(handle, index, style | flags);
			if (result == 0 && Native.getLastError() != 0) throw new IllegalStateException("Cannot configure overlay input");
		}
		else if (Platform.isLinux())
		{
			XShape shape = Native.load("Xext", XShape.class);
			XDisplay x = Native.load("X11", XDisplay.class);
			Pointer display = x.XOpenDisplay(null);
			if (display == null) throw new UnsupportedOperationException("X11/XWayland display unavailable");
			try
			{
				IntByReference major = new IntByReference(), minor = new IntByReference();
				if (shape.XShapeQueryVersion(display, major, minor) == 0 || major.getValue() < 1
					|| (major.getValue() == 1 && minor.getValue() < 1))
					throw new UnsupportedOperationException("X11 input shapes unavailable");
				shape.XShapeCombineRectangles(display, new NativeLong(Native.getComponentID(window)), 2, 0, 0, null, 0, 0, 0);
				x.XSync(display, false);
			}
			finally { x.XCloseDisplay(display); }
		}
		else throw new UnsupportedOperationException("Desktop overlay platform unavailable");
	}

	interface XDisplay extends Library
	{
		Pointer XOpenDisplay(String name);
		int XSync(Pointer display, boolean discard);
		int XCloseDisplay(Pointer display);
	}

	interface XShape extends Library
	{
		int XShapeQueryVersion(Pointer display, IntByReference major, IntByReference minor);
		void XShapeCombineRectangles(Pointer display, NativeLong window, int kind, int x, int y,
			Pointer rectangles, int count, int operation, int ordering);
	}

	@Override public void close()
	{
		timer.stop();
		if (window != null) window.dispose();
		fallback.setVisible(false);
	}
}
