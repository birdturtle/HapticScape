package com.ashy0019.hapticscape.integration.desktop;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Static DBusMenu tree. Values use GVariant text, never shell commands. */
final class LinuxTrayMenu
{
	static boolean contains(int id) { return id >= 0 && id <= 3; }

	static Map<String, String> properties(int id)
	{
		if (!contains(id)) throw new IllegalArgumentException("Unknown tray menu item");
		Map<String, String> result = new LinkedHashMap<>();
		if (id == 0) result.put("children-display", quote("submenu"));
		else if (id == 2) result.put("type", quote("separator"));
		else
		{
			result.put("label", quote(id == 1 ? "Open HapticScape" : "Exit"));
			result.put("enabled", "true");
			result.put("visible", "true");
		}
		return result;
	}

	static String dictionary(int id, List<String> names)
	{
		return properties(id).entrySet().stream()
			.filter(entry -> names.isEmpty() || names.contains(entry.getKey()))
			.map(entry -> quote(entry.getKey()) + ": <" + entry.getValue() + ">")
			.collect(Collectors.joining(",", "{", "}"));
	}

	static String layout(int id, int depth, List<String> names)
	{
		String children = "";
		if (id == 0 && depth != 0)
		{
			children = java.util.stream.IntStream.rangeClosed(1, 3)
				.mapToObj(child -> "<" + layout(child, 0, names) + ">")
				.collect(Collectors.joining(","));
		}
		// Child layouts are wrapped in variants, so their empty containers need explicit types.
		return "(" + id + ",@a{sv} " + dictionary(id, names) + ",@av [" + children + "])";
	}

	static String quote(String value)
	{
		return "'" + value.replace("\\", "\\\\").replace("'", "\\'")
			.replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'";
	}
}
