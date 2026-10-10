package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.host.DesktopNotificationService;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;
import javax.imageio.ImageIO;

/** Desktop notifications and tray controls: native D-Bus on Linux, AWT elsewhere. */
public final class AwtDesktopNotificationService implements DesktopNotificationService, AutoCloseable
{
	private final String title;
	private TrayIcon trayIcon;
	private boolean unavailable;
	private LinuxStatusNotifierTray linuxTray;

	public AwtDesktopNotificationService(String title)
	{
		this.title = Objects.requireNonNull(title, "title");
	}

	@Override
	public synchronized void notify(String message)
	{
		Objects.requireNonNull(message, "message");
		if (DesktopPlatform.current() == DesktopPlatform.LINUX)
		{
			if (linuxTray != null) linuxTray.notify(message);
			return;
		}
		TrayIcon icon = ensureTrayIcon();
		if (icon != null)
		{
			icon.displayMessage(title, Objects.requireNonNull(message, "message"), TrayIcon.MessageType.NONE);
		}
	}

	/** Installs the persistent tray icon and its application controls. */
	public synchronized boolean installApplicationMenu(Runnable openAction, Runnable exitAction)
	{
		return installApplicationMenu(openAction, exitAction, available -> { });
	}

	/** Reports native tray-host changes on the Swing event thread. */
	public synchronized boolean installApplicationMenu(Runnable openAction, Runnable exitAction,
		Consumer<Boolean> availability)
	{
		Objects.requireNonNull(openAction, "openAction");
		Objects.requireNonNull(exitAction, "exitAction");
		Objects.requireNonNull(availability, "availability");
		if (DesktopPlatform.current() == DesktopPlatform.LINUX)
		{
			if (linuxTray != null) linuxTray.close();
			try
			{
				linuxTray = new LinuxStatusNotifierTray(title, openAction, exitAction, availability);
				boolean installed = linuxTray.start();
				if (!installed) { linuxTray.close(); linuxTray = null; }
				return installed;
			}
			catch (RuntimeException | LinkageError unavailable)
			{
				if (linuxTray != null) linuxTray.close();
				linuxTray = null;
				return false;
			}
		}
		TrayIcon icon = ensureTrayIcon();
		if (icon == null) return false;

		PopupMenu menu = new PopupMenu();
		MenuItem open = new MenuItem("Open HapticScape");
		open.addActionListener(event -> EventQueue.invokeLater(openAction));
		MenuItem exit = new MenuItem("Exit");
		exit.addActionListener(event -> EventQueue.invokeLater(exitAction));
		menu.add(open);
		menu.addSeparator();
		menu.add(exit);
		icon.setPopupMenu(menu);
		icon.addActionListener(event -> EventQueue.invokeLater(openAction));
		return true;
	}

	private TrayIcon ensureTrayIcon()
	{
		if (trayIcon != null)
		{
			return trayIcon;
		}
		if (unavailable || GraphicsEnvironment.isHeadless() || !SystemTray.isSupported())
		{
			unavailable = true;
			return null;
		}
		try
		{
			TrayIcon icon = new TrayIcon(loadIcon(), title);
			icon.setImageAutoSize(true);
			SystemTray.getSystemTray().add(icon);
			trayIcon = icon;
			return trayIcon;
		}
		catch (Exception ignored)
		{
			unavailable = true;
			return null;
		}
	}

	private static Image loadIcon() throws IOException
	{
		java.net.URL resource = AwtDesktopNotificationService.class.getResource("/hapticscape.png");
		if (resource != null)
		{
			BufferedImage image = ImageIO.read(resource);
			if (image != null)
			{
				return image;
			}
		}

		BufferedImage fallback = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = fallback.createGraphics();
		try
		{
			graphics.fillOval(2, 2, 12, 12);
		}
		finally
		{
			graphics.dispose();
		}
		return fallback;
	}

	@Override
	public synchronized void close()
	{
		if (linuxTray != null)
		{
			linuxTray.close();
			linuxTray = null;
		}
		if (trayIcon != null && SystemTray.isSupported())
		{
			SystemTray.getSystemTray().remove(trayIcon);
			trayIcon = null;
		}
	}
}
