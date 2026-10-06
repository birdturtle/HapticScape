package com.ashy0019.hapticscape.integration.desktop;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.ptr.PointerByReference;

/** GIO bindings; constructed only by the Linux tray backend. */
final class LinuxTrayNative
{
	final Gio gio = Native.load("gio-2.0", Gio.class);
	final Glib glib = Native.load("glib-2.0", Glib.class);
	final Gobject gobject = Native.load("gobject-2.0", Gobject.class);

	Pointer variant(String type, String text)
	{
		Pointer signature = glib.g_variant_type_new(type);
		PointerByReference error = new PointerByReference();
		try
		{
			Pointer value = glib.g_variant_parse(signature, text, null, null, error);
			check(error);
			if (value == null) throw new IllegalStateException("Invalid tray protocol value");
			return value;
		}
		finally { glib.g_variant_type_free(signature); }
	}

	void check(PointerByReference error)
	{
		if (error.getValue() != null)
		{
			glib.g_error_free(error.getValue());
			error.setValue(null);
			throw new IllegalStateException("Linux tray D-Bus operation failed");
		}
	}

	interface MethodCall extends Callback
	{
		void invoke(Pointer connection, String sender, String path, String iface, String method,
			Pointer parameters, Pointer invocation, Pointer data);
	}
	interface GetProperty extends Callback
	{
		Pointer invoke(Pointer connection, String sender, String path, String iface, String property,
			PointerByReference error, Pointer data);
	}
	interface Signal extends Callback
	{
		void invoke(Pointer connection, String sender, String path, String iface, String signal,
			Pointer parameters, Pointer data);
	}
	@Structure.FieldOrder({"method", "property", "setProperty", "padding"})
	public static class VTable extends Structure
	{
		public MethodCall method;
		public GetProperty property;
		public Pointer setProperty;
		public Pointer[] padding = new Pointer[8];
	}
	interface Gio extends Library
	{
		Pointer g_dbus_address_get_for_bus_sync(int busType, Pointer cancellable, PointerByReference error);
		Pointer g_dbus_connection_new_for_address_sync(String address, int flags, Pointer observer,
			Pointer cancellable, PointerByReference error);
		void g_dbus_connection_set_exit_on_close(Pointer connection, int exit);
		String g_dbus_connection_get_unique_name(Pointer connection);
		int g_dbus_connection_is_closed(Pointer connection);
		int g_dbus_connection_emit_signal(Pointer connection, String destination, String path,
			String iface, String name, Pointer parameters, PointerByReference error);
		int g_dbus_connection_close_sync(Pointer connection, Pointer cancellable, PointerByReference error);
		Pointer g_dbus_node_info_new_for_xml(String xml, PointerByReference error);
		Pointer g_dbus_node_info_lookup_interface(Pointer info, String name);
		void g_dbus_node_info_unref(Pointer info);
		int g_dbus_connection_register_object(Pointer connection, String path, Pointer iface,
			VTable vtable, Pointer data, Pointer destroy, PointerByReference error);
		int g_dbus_connection_unregister_object(Pointer connection, int id);
		Pointer g_dbus_connection_call_sync(Pointer connection, String destination, String path,
			String iface, String method, Pointer parameters, Pointer replyType, int flags,
			int timeout, Pointer cancellable, PointerByReference error);
		void g_dbus_method_invocation_return_value(Pointer invocation, Pointer parameters);
		void g_dbus_method_invocation_return_dbus_error(Pointer invocation, String name, String message);
		int g_dbus_connection_signal_subscribe(Pointer connection, String sender, String iface,
			String member, String path, String arg0, int flags, Signal callback, Pointer data, Pointer destroy);
		void g_dbus_connection_signal_unsubscribe(Pointer connection, int id);
		Pointer g_cancellable_new();
		void g_cancellable_cancel(Pointer cancellable);
	}
	interface Glib extends Library
	{
		Pointer g_main_context_new();
		void g_main_context_push_thread_default(Pointer context);
		void g_main_context_pop_thread_default(Pointer context);
		int g_main_context_iteration(Pointer context, int mayBlock);
		void g_main_context_unref(Pointer context);
		Pointer g_variant_type_new(String signature);
		void g_variant_type_free(Pointer type);
		Pointer g_variant_parse(Pointer type, String text, Pointer limit, Pointer end, PointerByReference error);
		Pointer g_variant_get_child_value(Pointer variant, NativeLong index);
		NativeLong g_variant_n_children(Pointer variant);
		int g_variant_get_int32(Pointer variant);
		int g_variant_get_boolean(Pointer variant);
		Pointer g_variant_get_variant(Pointer variant);
		String g_variant_get_string(Pointer variant, Pointer length);
		void g_variant_unref(Pointer variant);
		Pointer g_variant_print(Pointer variant, int annotate);
		void g_error_free(Pointer error);
		void g_free(Pointer memory);
	}
	interface Gobject extends Library { void g_object_unref(Pointer object); }
}
