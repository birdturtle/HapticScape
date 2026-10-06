package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Playback identities and DSP ports from a bounded snapshot; no PID or sink fallback. */
final class PipeWireApplicationGraph
{
	static final String PREFIX = "pipewire:app:";
	private final JsonArray objects;
	private final Map<String, Group> groups = new LinkedHashMap<>();
	private final PipeWireGraph outputs;
	private static final Set<String> CHANNELS = Set.of("MONO", "FL", "FR", "FC", "LFE", "RL", "RR",
		"SL", "SR", "RC", "FLC", "FRC", "TC", "TFL", "TFC", "TFR", "TRL", "TRC", "TRR");

	PipeWireApplicationGraph(String json)
	{
		JsonElement parsed = new JsonParser().parse(json);
		if (!parsed.isJsonArray()) throw new IllegalArgumentException("Invalid PipeWire application graph");
		objects = parsed.getAsJsonArray();
		outputs = new PipeWireGraph(json);
		Map<String, JsonObject> clients = new LinkedHashMap<>();
		for (JsonElement entry : objects)
			if (entry.isJsonObject() && type(entry.getAsJsonObject(), "Client"))
				clients.put(text(entry.getAsJsonObject(), "id"), props(entry.getAsJsonObject()));
		for (JsonElement entry : objects)
		{
			if (!entry.isJsonObject()) continue;
			JsonObject node = entry.getAsJsonObject(), properties = props(node);
			if (!type(node, "Node") || !text(properties, "media.class").equals("Stream/Output/Audio")) continue;
			JsonObject merged = new JsonObject();
			JsonObject client = clients.get(text(properties, "client.id"));
			if (client != null) for (Map.Entry<String, JsonElement> property : client.entrySet()) merged.add(property.getKey(), property.getValue());
			for (Map.Entry<String, JsonElement> property : properties.entrySet()) merged.add(property.getKey(), property.getValue());
			String appId = text(merged, "application.id"), binary = text(merged, "application.process.binary");
			if (appId.isEmpty() && binary.isEmpty()) continue;
			String serial = text(properties, "object.serial");
			if (!serial.matches("[0-9]+") || !node.has("id")) continue;
			String id = identity(appId.isEmpty() ? "binary" : "id", appId.isEmpty() ? binary : appId);
			String name = text(merged, "application.name");
			if (name.isEmpty()) name = appId.isEmpty() ? binary : appId;
			Group group = groups.computeIfAbsent(id, ignored -> new Group(id));
			group.names.add(name);
			JsonObject info = object(node, "info");
			String state = text(info, "state");
			group.nodes.add(new Node(node.get("id").getAsInt(), serial,
				state.equals("idle") || state.equals("suspended"), PipeWireGraph.nodeMuted(info)));
		}
		for (Group group : groups.values())
		{
			group.name = group.names.iterator().next();
			group.ambiguous = group.id.startsWith(PREFIX + "binary:") && group.names.size() > 1;
			for (Node node : group.nodes) group.ports.addAll(ports(node.id, "out"));
		}
	}

	static PipeWireApplicationGraph snapshot(PipeWireProcesses helpers)
	{
		return new PipeWireApplicationGraph(helpers.query(List.of("pw-dump", "--no-colors"), 4 * 1024 * 1024));
	}

	List<AudioCaptureApplication> applications()
	{
		return groups.values().stream().filter(group -> !group.ambiguous && !group.ports.isEmpty())
			.sorted(Comparator.comparing(group -> group.name))
			.map(group -> new AudioCaptureApplication(group.id, group.name)).collect(Collectors.toList());
	}

	Group resolve(AudioCaptureApplication application)
	{
		if (!application.getId().startsWith(PREFIX))
			throw new IllegalStateException("Choose a Linux PipeWire application; the saved selection belongs to another backend");
		Group group = groups.get(application.getId());
		if (group == null) return null;
		if (group.ambiguous) throw new IllegalStateException("Selected playback application is ambiguous; an application ID is required");
		if (group.nodes.size() > 32 || group.ports.size() > 64)
			throw new IllegalStateException("Selected application has too many playback streams or channels");
		Set<String> channels = new LinkedHashSet<>();
		for (Port port : group.ports)
		{
			if (!CHANNELS.contains(port.channel))
				throw new IllegalStateException("Selected application exposes an unsupported audio channel: " + port.channel);
			channels.add(port.channel);
		}
		group.channels = channels.stream().sorted().collect(Collectors.toList());
		return group;
	}

	int captureNode(String name)
	{
		for (JsonElement entry : objects)
			if (entry.isJsonObject())
			{
				JsonObject node = entry.getAsJsonObject();
				if (type(node, "Node") && text(props(node), "node.name").equals(name)) return node.get("id").getAsInt();
			}
		return -1;
	}
	boolean silent(Group group)
	{
		if (outputs.defaultMuted() || group.nodes.stream().allMatch(node -> node.paused || node.muted)) return true;
		// Follow playback through filters to actual sinks; capture input nodes are not sinks.
		Set<Integer> reached = group.nodes.stream().map(node -> node.id).collect(Collectors.toSet());
		boolean changed;
		do
		{
			changed = false;
			for (JsonElement entry : objects)
			{
				if (!entry.isJsonObject()) continue;
				JsonObject link = entry.getAsJsonObject(), info = object(link, "info");
				if (!type(link, "Link")) continue;
				try
				{
					int from = Integer.parseInt(text(info, "output-node-id")), to = Integer.parseInt(text(info, "input-node-id"));
					if (reached.contains(from)) changed |= reached.add(to);
				}
				catch (NumberFormatException ignored) { }
			}
		} while (changed);
		List<PipeWireGraph.Sink> sinks = outputs.sinks.stream().filter(sink -> reached.contains(sink.id)).collect(Collectors.toList());
		return !sinks.isEmpty() && sinks.stream().allMatch(sink -> sink.muted);
	}

	List<Port> ports(int nodeId, String direction)
	{
		List<Port> ports = new ArrayList<>();
		for (JsonElement entry : objects)
		{
			if (!entry.isJsonObject()) continue;
			JsonObject port = entry.getAsJsonObject(), properties = props(port);
			if (!type(port, "Port") || !text(properties, "node.id").equals(Integer.toString(nodeId))
				|| !text(properties, "port.direction").equals(direction)) continue;
			String serial = text(properties, "object.serial");
			if (!serial.matches("[0-9]+")) continue;
			ports.add(new Port(port.get("id").getAsInt(), serial, text(properties, "audio.channel")));
		}
		ports.sort(Comparator.comparingInt(port -> port.id));
		return ports;
	}

	List<Route> routes(Group group, String captureName)
	{
		List<Port> inputs = ports(captureNode(captureName), "in");
		if (inputs.size() != group.channels.size()) return List.of();
		List<Route> result = new ArrayList<>();
		for (Port output : group.ports)
		{
			List<Port> matches = inputs.stream().filter(input -> input.channel.equals(output.channel)).collect(Collectors.toList());
			if (matches.size() != 1) throw new IllegalStateException("PipeWire application channel negotiation failed");
			result.add(new Route(output, matches.get(0)));
		}
		return result;
	}

	boolean linkedOnlyTo(String captureName, List<Route> routes)
	{
		int capture = captureNode(captureName);
		if (capture < 0 || routes.isEmpty()) return false;
		Set<String> expected = routes.stream().map(Route::key).collect(Collectors.toSet());
		Set<String> found = new LinkedHashSet<>();
		for (JsonElement entry : objects)
		{
			if (!entry.isJsonObject()) continue;
			JsonObject link = entry.getAsJsonObject(), info = object(link, "info");
			if (!type(link, "Link") || !text(info, "input-node-id").equals(Integer.toString(capture))) continue;
			String key = text(info, "output-port-id") + ":" + text(info, "input-port-id");
			if (!expected.contains(key) || !found.add(key))
				throw new IllegalStateException("Unexpected source linked to application capture; Music Sync stopped");
			String state = text(info, "state");
			if (!state.equals("active") && !state.equals("paused")) return false;
		}
		return found.equals(expected);
	}

	static String identity(String kind, String value)
	{
		StringBuilder hex = new StringBuilder();
		for (byte item : value.getBytes(StandardCharsets.UTF_8)) hex.append(String.format(java.util.Locale.ROOT, "%02x", item & 255));
		return PREFIX + kind + ":" + hex;
	}
	private static boolean type(JsonObject object, String suffix) { return text(object, "type").equals("PipeWire:Interface:" + suffix); }
	private static JsonObject props(JsonObject object) { return object(object(object, "info"), "props"); }
	private static JsonObject object(JsonObject object, String key)
	{
		JsonElement value = object.get(key);
		return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
	}
	private static String text(JsonObject object, String key)
	{
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
	}
	static final class Node
	{
		final int id; final String serial;
		final boolean paused, muted;
		Node(int id, String serial, boolean paused, boolean muted)
		{
			this.id = id; this.serial = serial; this.paused = paused; this.muted = muted;
		}
	}
	static final class Port
	{
		final int id; final String serial, channel;
		Port(int id, String serial, String channel) { this.id = id; this.serial = serial; this.channel = channel; }
	}
	static final class Route
	{
		final Port output, input;
		Route(Port output, Port input) { this.output = output; this.input = input; }
		String key() { return output.id + ":" + input.id; }
	}
	static final class Group
	{
		final String id;
		String name;
		boolean ambiguous;
		final Set<String> names = new LinkedHashSet<>();
		final List<Node> nodes = new ArrayList<>();
		final List<Port> ports = new ArrayList<>();
		List<String> channels = List.of();
		Group(String id) { this.id = id; }
		String signature()
		{
			return nodes.stream().map(node -> node.id + ":" + node.serial).sorted().collect(Collectors.joining(","))
				+ "/" + ports.stream().map(port -> port.id + ":" + port.serial + ":" + port.channel).sorted().collect(Collectors.joining(","));
		}
	}
}
