package com.ashy0019.hapticscape.integration.desktop;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Native KDE tray and DBusMenu on a private GIO connection and main context. */
final class LinuxStatusNotifierTray implements AutoCloseable
{
	static final String ITEM = "org.kde.StatusNotifierItem";
	static final String MENU = "com.canonical.dbusmenu";
	private static final String WATCHER = "org.kde.StatusNotifierWatcher";
	private static final String WATCHER_PATH = "/StatusNotifierWatcher";
	private static final Logger log = LoggerFactory.getLogger(LinuxStatusNotifierTray.class);
	private final String title;
	private final Runnable open;
	private final Runnable exit;
	private final Consumer<Boolean> availability;
	private final CompletableFuture<Boolean> started = new CompletableFuture<>();
	private final ConcurrentLinkedQueue<String> notifications = new ConcurrentLinkedQueue<>();
	private final Object nativeLock = new Object();
	private final LinuxTrayNative api;
	// Retain callbacks for the entire lifetime of their native registrations.
	private final LinuxTrayNative.VTable vtable = new LinuxTrayNative.VTable();
	private final LinuxTrayNative.Signal watcherChanged = this::watcherChanged;
	private final LinuxTrayNative.Signal hostChanged = this::hostChanged;
	private volatile boolean closed;
	private boolean available;
	private Pointer connection;
	private Pointer cancellable;
	private String iconPixmap;
	private Thread worker;

	LinuxStatusNotifierTray(String title, Runnable open, Runnable exit, Consumer<Boolean> availability)
	{
		this.title = title;
		this.open = open;
		this.exit = exit;
		this.availability = availability;
		api = new LinuxTrayNative();
		vtable.method = this::method;
		vtable.property = this::property;
		vtable.write();
	}

	boolean start()
	{
		worker = new Thread(this::run, "hapticscape-linux-tray");
		worker.setDaemon(true);
		worker.start();
		try { return started.get(5, TimeUnit.SECONDS); }
		catch (InterruptedException interrupted)
		{
			Thread.currentThread().interrupt();
			close();
			return false;
		}
		catch (Exception failure)
		{
			log.warn("Linux tray startup failed; keeping the desktop window accessible");
			close();
			return false;
		}
	}

	private void run()
	{
		Pointer context = api.glib.g_main_context_new();
		Pointer info = null;
		List<Integer> objects = new ArrayList<>();
		List<Integer> subscriptions = new ArrayList<>();
		api.glib.g_main_context_push_thread_default(context);
		try
		{
			synchronized (nativeLock)
			{
				cancellable = api.gio.g_cancellable_new();
				if (closed) api.gio.g_cancellable_cancel(cancellable);
			}
			PointerByReference error = new PointerByReference();
			Pointer address = api.gio.g_dbus_address_get_for_bus_sync(2, cancellable, error);
			api.check(error);
			try
			{
				// AUTHENTICATION_CLIENT | MESSAGE_BUS_CONNECTION. Never close the keyring's shared bus.
				connection = api.gio.g_dbus_connection_new_for_address_sync(address.getString(0), 9,
					null, cancellable, error);
			}
			finally { api.glib.g_free(address); }
			api.check(error);
			api.gio.g_dbus_connection_set_exit_on_close(connection, 0);
			iconPixmap = iconPixmap();
			try (InputStream xml = LinuxStatusNotifierTray.class.getResourceAsStream("/linux-tray.xml"))
			{
				if (xml == null) throw new IllegalStateException("Missing Linux tray interface");
				info = api.gio.g_dbus_node_info_new_for_xml(new String(xml.readAllBytes(), StandardCharsets.UTF_8), error);
			}
			api.check(error);
			objects.add(register(info, ITEM, "/StatusNotifierItem"));
			objects.add(register(info, MENU, "/Menu"));
			subscriptions.add(api.gio.g_dbus_connection_signal_subscribe(connection, "org.freedesktop.DBus",
				"org.freedesktop.DBus", "NameOwnerChanged", "/org/freedesktop/DBus", WATCHER,
				0, watcherChanged, null, null));
			subscriptions.add(api.gio.g_dbus_connection_signal_subscribe(connection, WATCHER,
				WATCHER, null, WATCHER_PATH, null, 0, hostChanged, null, null));
			if (!registerWatcher())
			{
				started.complete(false);
				return;
			}
			available = true;
			started.complete(true);
			while (!closed && api.gio.g_dbus_connection_is_closed(connection) == 0)
			{
				// Bound dispatch per tick so even a busy bus cannot prevent shutdown.
				for (int count = 0; count < 32 && !closed; count++)
					if (api.glib.g_main_context_iteration(context, 0) == 0) break;
				String message = notifications.poll();
				if (message != null) sendNotification(message);
				Thread.sleep(20);
			}
		}
		catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
		catch (Exception | LinkageError failure)
		{
			log.warn("Linux tray unavailable; requires GLib/GIO, a session bus and a StatusNotifier host");
		}
		finally
		{
			started.complete(false);
			if (!closed) publishAvailability(false);
			if (connection != null)
			{
				subscriptions.forEach(id -> api.gio.g_dbus_connection_signal_unsubscribe(connection, id));
				objects.forEach(id -> api.gio.g_dbus_connection_unregister_object(connection, id));
				api.gio.g_dbus_connection_close_sync(connection, null, null);
				api.gobject.g_object_unref(connection);
			}
			if (info != null) api.gio.g_dbus_node_info_unref(info);
			synchronized (nativeLock)
			{
				if (cancellable != null) api.gobject.g_object_unref(cancellable);
				cancellable = null;
			}
			api.glib.g_main_context_pop_thread_default(context);
			api.glib.g_main_context_unref(context);
		}
	}

	private int register(Pointer info, String iface, String path)
	{
		PointerByReference error = new PointerByReference();
		int id = api.gio.g_dbus_connection_register_object(connection, path,
			api.gio.g_dbus_node_info_lookup_interface(info, iface), vtable, null, null, error);
		api.check(error);
		if (id == 0) throw new IllegalStateException("Linux tray object registration failed");
		return id;
	}

	private Pointer call(String destination, String path, String iface, String method, String type, String value)
	{
		PointerByReference error = new PointerByReference();
		Pointer parameters = api.variant(type, value);
		try
		{
			Pointer result = api.gio.g_dbus_connection_call_sync(connection, destination, path, iface, method,
				parameters, null, 0, 1500, cancellable, error);
			api.check(error);
			if (result == null) throw new IllegalStateException("Linux tray call failed");
			return result;
		}
		finally { api.glib.g_variant_unref(parameters); }
	}

	private boolean registerWatcher()
	{
		try
		{
			Pointer result = call(WATCHER, WATCHER_PATH, "org.freedesktop.DBus.Properties", "Get",
				"(ss)", "(" + LinuxTrayMenu.quote(WATCHER) + ",'IsStatusNotifierHostRegistered')");
			boolean host;
			try
			{
				Pointer wrapped = child(result, 0);
				try
				{
					Pointer value = api.glib.g_variant_get_variant(wrapped);
					try { host = api.glib.g_variant_get_boolean(value) != 0; }
					finally { api.glib.g_variant_unref(value); }
				}
				finally { api.glib.g_variant_unref(wrapped); }
			}
			finally { api.glib.g_variant_unref(result); }
			if (!host) return false;
			result = call(WATCHER, WATCHER_PATH, WATCHER, "RegisterStatusNotifierItem", "(s)",
				"(" + LinuxTrayMenu.quote(api.gio.g_dbus_connection_get_unique_name(connection)) + ",)");
			api.glib.g_variant_unref(result);
			return true;
		}
		catch (RuntimeException failure) { return false; }
	}

	private void watcherChanged(Pointer bus, String sender, String path, String iface, String signal,
		Pointer parameters, Pointer data)
	{
		if (closed) return;
		try { publishAvailability(!string(parameters, 2).isEmpty() && registerWatcher()); }
		catch (RuntimeException failure) { publishAvailability(false); }
	}

	private void hostChanged(Pointer bus, String sender, String path, String iface, String signal,
		Pointer parameters, Pointer data)
	{
		if (closed) return;
		if (signal.equals("StatusNotifierHostUnregistered")) publishAvailability(false);
		else if (signal.equals("StatusNotifierHostRegistered")) publishAvailability(registerWatcher());
	}

	private void publishAvailability(boolean value)
	{
		if (available == value) return;
		available = value;
		EventQueue.invokeLater(() -> { if (!closed) availability.accept(value); });
	}

	private void action(Runnable action)
	{
		EventQueue.invokeLater(() -> { if (!closed) action.run(); });
	}

	private void event(int id, String event)
	{
		if (event.equals("clicked"))
		{
			if (id == 1) action(open);
			else if (id == 3) action(exit);
		}
	}

	private void method(Pointer bus, String sender, String path, String iface, String method,
		Pointer parameters, Pointer invocation, Pointer data)
	{
		try
		{
			String type = "()", value = "()";
			if (ITEM.equals(iface))
			{
				if (method.equals("Activate") || method.equals("SecondaryActivate")) action(open);
				// ContextMenu is rendered by the host using /Menu. Scroll and activation tokens are optional.
			}
			else if (MENU.equals(iface))
			{
				switch (method)
				{
					case "GetLayout":
						type = "(u(ia{sv}av))";
						value = "(1," + LinuxTrayMenu.layout(integer(parameters, 0), integer(parameters, 1), strings(parameters, 2)) + ")";
						break;
					case "GetGroupProperties":
						type = "(a(ia{sv}))";
						List<Integer> ids = integers(parameters, 0);
						if (ids.isEmpty()) ids = List.of(0, 1, 2, 3);
						List<String> names = strings(parameters, 1);
						value = "([" + ids.stream().filter(LinuxTrayMenu::contains)
							.map(id -> "(" + id + "," + LinuxTrayMenu.dictionary(id, names) + ")")
							.collect(Collectors.joining(",")) + "],)";
						break;
					case "GetProperty":
						type = "(v)";
						String property = LinuxTrayMenu.properties(integer(parameters, 0)).get(string(parameters, 1));
						if (property == null) throw new IllegalArgumentException("Unknown tray menu property");
						value = "(<" + property + ">,)";
						break;
					case "Event": event(integer(parameters, 0), string(parameters, 1)); break;
					case "EventGroup":
						type = "(ai)";
						List<Integer> errors = new ArrayList<>();
						Pointer events = child(parameters, 0);
						try
						{
							int count = count(events);
							for (int i = 0; i < count; i++)
							{
								Pointer entry = child(events, i);
								try
								{
									int id = integer(entry, 0);
									if (LinuxTrayMenu.contains(id)) event(id, string(entry, 1));
									else errors.add(id);
								}
								finally { api.glib.g_variant_unref(entry); }
							}
						}
						finally { api.glib.g_variant_unref(events); }
						value = "(" + errors + ",)";
						break;
					case "AboutToShow":
						if (!LinuxTrayMenu.contains(integer(parameters, 0))) throw new IllegalArgumentException("Unknown tray item");
						type = "(b)"; value = "(false,)"; break;
					case "AboutToShowGroup":
						type = "(aiai)";
						value = "([]," + integers(parameters, 0).stream().filter(id -> !LinuxTrayMenu.contains(id))
							.collect(Collectors.toList()) + ")";
						break;
					default: throw new IllegalArgumentException("Unknown tray method");
				}
			}
			Pointer reply = api.variant(type, value);
			try { api.gio.g_dbus_method_invocation_return_value(invocation, reply); }
			finally { api.glib.g_variant_unref(reply); }
		}
		catch (RuntimeException failure)
		{
			api.gio.g_dbus_method_invocation_return_dbus_error(invocation,
				"org.freedesktop.DBus.Error.InvalidArgs", "Invalid tray menu request");
		}
	}

	private Pointer property(Pointer bus, String sender, String path, String iface, String name,
		PointerByReference error, Pointer data)
	{
		// All declared properties have fixed, valid types; GIO rejects undeclared names.
		if (MENU.equals(iface))
		{
			switch (name)
			{
				case "Version": return api.variant("u", "3");
				case "TextDirection": return api.variant("s", "'ltr'");
				case "Status": return api.variant("s", "'normal'");
				case "IconThemePath": return api.variant("as", "[]");
				default: throw new IllegalArgumentException("Unknown menu property");
			}
		}
		switch (name)
		{
			case "Category": return api.variant("s", "'ApplicationStatus'");
			case "Id": return api.variant("s", LinuxTrayMenu.quote("hapticscape-" + title));
			case "Title": return api.variant("s", LinuxTrayMenu.quote(title));
			case "Status": return api.variant("s", "'Active'");
			case "WindowId": return api.variant("i", "0");
			case "ItemIsMenu": return api.variant("b", "false");
			case "Menu": return api.variant("o", "'/Menu'");
			case "IconPixmap": return api.variant("a(iiay)", iconPixmap);
			case "AttentionIconPixmap": case "OverlayIconPixmap": return api.variant("a(iiay)", "[]");
			case "ToolTip": return api.variant("(sa(iiay)ss)", "('',[]," + LinuxTrayMenu.quote(title) + ",'')");
			default: return api.variant("s", "''");
		}
	}

	private Pointer child(Pointer value, int index) { return api.glib.g_variant_get_child_value(value, new NativeLong(index)); }
	private int count(Pointer value)
	{
		long count = api.glib.g_variant_n_children(value).longValue();
		if (count > 1024) throw new IllegalArgumentException("Oversized tray request");
		return (int) count;
	}
	private int integer(Pointer value, int index)
	{
		Pointer child = child(value, index);
		try { return api.glib.g_variant_get_int32(child); }
		finally { api.glib.g_variant_unref(child); }
	}
	private String string(Pointer value, int index)
	{
		Pointer child = child(value, index);
		try { return api.glib.g_variant_get_string(child, null); }
		finally { api.glib.g_variant_unref(child); }
	}
	private List<String> strings(Pointer value, int index)
	{
		Pointer array = child(value, index);
		try
		{
			List<String> result = new ArrayList<>();
			for (int i = 0, count = count(array); i < count; i++) result.add(string(array, i));
			return result;
		}
		finally { api.glib.g_variant_unref(array); }
	}
	private List<Integer> integers(Pointer value, int index)
	{
		Pointer array = child(value, index);
		try
		{
			List<Integer> result = new ArrayList<>();
			for (int i = 0, count = count(array); i < count; i++) result.add(integer(array, i));
			return result;
		}
		finally { api.glib.g_variant_unref(array); }
	}

	private static String iconPixmap() throws Exception
	{
		BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try (InputStream input = LinuxStatusNotifierTray.class.getResourceAsStream("/hapticscape.png"))
		{
			BufferedImage original = input == null ? null : ImageIO.read(input);
			if (original != null) graphics.drawImage(original, 0, 0, 32, 32, null);
			else { graphics.setColor(java.awt.Color.CYAN); graphics.fillOval(4, 4, 24, 24); }
		}
		finally { graphics.dispose(); }
		StringBuilder bytes = new StringBuilder("[(32,32,[");
		for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++)
		{
			int argb = image.getRGB(x, y);
			for (int shift = 24; shift >= 0; shift -= 8)
			{
				if (bytes.charAt(bytes.length() - 1) != '[') bytes.append(',');
				bytes.append((argb >>> shift) & 255);
			}
		}
		return bytes.append("]) ]").toString();
	}

	void notify(String message)
	{
		if (!closed && notifications.size() < 16) notifications.add(message);
	}
	private void sendNotification(String message)
	{
		try
		{
			Pointer result = call("org.freedesktop.Notifications", "/org/freedesktop/Notifications",
				"org.freedesktop.Notifications", "Notify", "(susssasa{sv}i)",
				"('HapticScape',0,''," + LinuxTrayMenu.quote(title) + "," + LinuxTrayMenu.quote(message) + ",[],{},-1)");
			api.glib.g_variant_unref(result);
		}
		catch (RuntimeException unavailable) { log.debug("Linux desktop notification service unavailable"); }
	}

	@Override
	public void close()
	{
		closed = true;
		notifications.clear();
		synchronized (nativeLock)
		{
			if (cancellable != null) api.gio.g_cancellable_cancel(cancellable);
		}
		if (worker != null) worker.interrupt();
	}
}
