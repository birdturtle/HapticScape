package com.ashy0019.hapticscape.integration.desktop;

import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;

/** Explicit native integration check on a disposable bus, never the user's desktop bus. */
public final class LinuxTrayNativeSmoke
{
	private static final String WATCHER = "org.kde.StatusNotifierWatcher";
	private static final String XML = "<node><interface name='org.kde.StatusNotifierWatcher'>"
		+ "<method name='RegisterStatusNotifierItem'><arg type='s' direction='in'/></method>"
		+ "<property name='IsStatusNotifierHostRegistered' type='b' access='read'/>"
		+ "<signal name='StatusNotifierHostRegistered'/><signal name='StatusNotifierHostUnregistered'/>"
		+ "</interface></node>";

	public static void main(String[] args) throws Exception
	{
		if (System.getenv("DBUS_SESSION_BUS_ADDRESS") == null
			|| !System.getenv("DBUS_SESSION_BUS_ADDRESS").startsWith("unix:path=/tmp/dbus-"))
			throw new IllegalStateException("Run this check through dbus-run-session on a disposable bus");
		try (Watcher watcher = new Watcher())
		{
			watcher.start();
			AtomicInteger opens = new AtomicInteger(), exits = new AtomicInteger();
			AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
			CountDownLatch lost = new CountDownLatch(1), regained = new CountDownLatch(1);
			Runnable open = () -> { if (!SwingUtilities.isEventDispatchThread()) callbackFailure.set(new AssertionError("Open outside EDT")); opens.incrementAndGet(); };
			Runnable exit = () -> { if (!SwingUtilities.isEventDispatchThread()) callbackFailure.set(new AssertionError("Exit outside EDT")); exits.incrementAndGet(); };
			LinuxStatusNotifierTray tray = new LinuxStatusNotifierTray("Native test ' profile", open, exit,
				available -> { if (available) regained.countDown(); else lost.countDown(); });
			try
			{
				check(tray.start(), "Tray failed to register");
				String item = watcher.item.get();
				check(item != null, "Watcher did not receive registration");
				String properties = watcher.call(item, "/StatusNotifierItem", "org.freedesktop.DBus.Properties",
					"GetAll", "(s)", "('org.kde.StatusNotifierItem',)");
				check(properties.contains("/Menu") && properties.contains("IconPixmap") && properties.contains("Native test"), "Missing tray properties");
				String layout = watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "GetLayout", "(iias)", "(0,-1,[])");
				check(layout.contains("Open HapticScape") && layout.contains("Exit") && layout.contains("separator"), "Incomplete native menu");
				String shallow = watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "GetLayout", "(iias)", "(0,0,[])");
				check(!shallow.contains("Open HapticScape"), "Depth not respected");
				check(watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "GetGroupProperties", "(aias)", "([1,3],['label'])").contains("Exit"), "Group properties failed");
				check(watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "GetProperty", "(is)", "(3,'label')").contains("Exit"), "Property failed");
				check(watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "AboutToShow", "(i)", "(0,)").contains("false"), "AboutToShow failed");
				watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "AboutToShowGroup", "(ai)", "([0,1,99],)");
				watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "Event", "(isvu)", "(1,'hovered',<0>,0)");
				watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "Event", "(isvu)", "(1,'clicked',<0>,0)");
				watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "EventGroup", "(a(isvu))", "([(3,'clicked',<0>,0),(99,'clicked',<0>,0)],)");
				watcher.call(item, "/StatusNotifierItem", LinuxStatusNotifierTray.ITEM, "Activate", "(ii)", "(0,0)");
				SwingUtilities.invokeAndWait(() -> { });
				check(opens.get() == 2 && exits.get() == 1 && callbackFailure.get() == null, "Actions were missing, duplicated or outside EDT");
				try
				{
					watcher.call(item, "/Menu", LinuxStatusNotifierTray.MENU, "GetLayout", "(iias)", "(99,-1,[])");
					throw new AssertionError("Invalid parent accepted");
				}
				catch (IllegalStateException expected) { }
				watcher.host = false;
				watcher.signal("StatusNotifierHostUnregistered");
				check(lost.await(3, TimeUnit.SECONDS), "Missing host-loss fallback");
				watcher.host = true;
				watcher.signal("StatusNotifierHostRegistered");
				check(regained.await(3, TimeUnit.SECONDS), "Missing re-registration");
				tray.close();
				tray.close();
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
				while (watcher.hasOwner(item) && System.nanoTime() < deadline) Thread.sleep(20);
				check(!watcher.hasOwner(item), "Tray connection leaked after close");
				// Distinct desktop profiles must never replace one another's tray registration.
				try (LinuxStatusNotifierTray first = new LinuxStatusNotifierTray("Profile one", open, exit, available -> { });
					LinuxStatusNotifierTray second = new LinuxStatusNotifierTray("Profile two", open, exit, available -> { }))
				{
					check(first.start(), "First profile failed");
					String firstName = watcher.item.get();
					check(second.start(), "Second profile failed");
					String secondName = watcher.item.get();
					check(!firstName.equals(secondName), "Profile registrations collided");
					check(watcher.hasOwner(firstName) && watcher.hasOwner(secondName), "One profile replaced another");
				}
				// Restarting the watcher must restore registration, without an AWT icon.
				CountDownLatch watcherLost = new CountDownLatch(1), watcherReturned = new CountDownLatch(1);
				try (LinuxStatusNotifierTray restarted = new LinuxStatusNotifierTray("Watcher restart", open, exit,
					available -> { if (available) watcherReturned.countDown(); else watcherLost.countDown(); }))
				{
					check(restarted.start(), "Restart test failed to register");
					watcher.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "ReleaseName", "(s)", "('org.kde.StatusNotifierWatcher',)");
					check(watcherLost.await(3, TimeUnit.SECONDS), "Watcher loss not reported");
					watcher.call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "(su)", "('org.kde.StatusNotifierWatcher',0)");
					check(watcherReturned.await(3, TimeUnit.SECONDS), "Watcher restart not recovered");
				}
				watcher.host = false;
				try (LinuxStatusNotifierTray absent = new LinuxStatusNotifierTray("No host", open, exit, available -> { }))
				{
					check(!absent.start(), "Tray enabled without a host");
				}
			}
			finally { tray.close(); }
		}
		System.out.println("Native tray registration, pixmap/menu protocol, EDT actions, host recovery and cleanup passed on an isolated bus.");
		System.exit(0); // Swing's event thread is intentionally initialized by the action checks.
	}

	private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

	private static final class Watcher implements AutoCloseable
	{
		private final LinuxTrayNative api = new LinuxTrayNative();
		private final CompletableFuture<Void> ready = new CompletableFuture<>();
		private final AtomicReference<String> item = new AtomicReference<>();
		private final LinuxTrayNative.VTable vtable = new LinuxTrayNative.VTable();
		private volatile boolean stopped, host = true;
		private Pointer connection;
		private Thread thread;

		void start() throws Exception
		{
			thread = new Thread(this::run, "test-status-notifier-watcher");
			thread.setDaemon(true);
			thread.start();
			ready.get(3, TimeUnit.SECONDS);
		}
		void run()
		{
			Pointer context = api.glib.g_main_context_new(), info = null;
			api.glib.g_main_context_push_thread_default(context);
			try
			{
				PointerByReference error = new PointerByReference();
				connection = api.gio.g_dbus_connection_new_for_address_sync(System.getenv("DBUS_SESSION_BUS_ADDRESS"), 9, null, null, error);
				api.check(error);
				api.gio.g_dbus_connection_set_exit_on_close(connection, 0);
				vtable.method = (bus, sender, path, iface, method, parameters, invocation, data) ->
				{
					Pointer name = api.glib.g_variant_get_child_value(parameters, new NativeLong(0));
					try { item.set(api.glib.g_variant_get_string(name, null)); }
					finally { api.glib.g_variant_unref(name); }
					Pointer reply = api.variant("()", "()");
					try { api.gio.g_dbus_method_invocation_return_value(invocation, reply); }
					finally { api.glib.g_variant_unref(reply); }
				};
				vtable.property = (bus, sender, path, iface, property, failure, data) -> api.variant("b", Boolean.toString(host));
				vtable.write();
				info = api.gio.g_dbus_node_info_new_for_xml(XML, error);
				api.check(error);
				api.gio.g_dbus_connection_register_object(connection, "/StatusNotifierWatcher",
					api.gio.g_dbus_node_info_lookup_interface(info, WATCHER), vtable, null, null, error);
				api.check(error);
				call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "RequestName", "(su)", "('org.kde.StatusNotifierWatcher',0)");
				ready.complete(null);
				while (!stopped)
				{
					while (api.glib.g_main_context_iteration(context, 0) != 0) { }
					Thread.sleep(10);
				}
			}
			catch (Throwable failure) { ready.completeExceptionally(failure); }
			finally
			{
				if (connection != null)
				{
					api.gio.g_dbus_connection_close_sync(connection, null, null);
					api.gobject.g_object_unref(connection);
				}
				if (info != null) api.gio.g_dbus_node_info_unref(info);
				api.glib.g_main_context_pop_thread_default(context);
				api.glib.g_main_context_unref(context);
			}
		}
		String call(String destination, String path, String iface, String method, String signature, String text)
		{
			Pointer parameters = api.variant(signature, text), result = null;
			try
			{
				PointerByReference error = new PointerByReference();
				result = api.gio.g_dbus_connection_call_sync(connection, destination, path, iface, method, parameters, null, 0, 2000, null, error);
				api.check(error);
				Pointer output = api.glib.g_variant_print(result, 1);
				try { return output.getString(0); }
				finally { api.glib.g_free(output); }
			}
			finally
			{
				api.glib.g_variant_unref(parameters);
				if (result != null) api.glib.g_variant_unref(result);
			}
		}
		boolean hasOwner(String name)
		{
			return call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
				"NameHasOwner", "(s)", "(" + LinuxTrayMenu.quote(name) + ",)").contains("true");
		}
		void signal(String name)
		{
			Pointer parameters = api.variant("()", "()");
			try
			{
				PointerByReference error = new PointerByReference();
				api.gio.g_dbus_connection_emit_signal(connection, null, "/StatusNotifierWatcher", WATCHER, name, parameters, error);
				api.check(error);
			}
			finally { api.glib.g_variant_unref(parameters); }
		}
		@Override
		public void close() throws Exception
		{
			stopped = true;
			if (thread != null) thread.join(3000);
		}
	}
}
