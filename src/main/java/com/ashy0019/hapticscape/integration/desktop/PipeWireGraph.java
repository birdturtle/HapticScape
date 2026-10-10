package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/** One bounded pw-dump snapshot. Persist names, resolve live serials only for capture. */
final class PipeWireGraph
{
	static final String ENDPOINT_PREFIX = "pipewire:sink:";
	final List<Sink> sinks = new ArrayList<>();
	private final JsonArray objects;
	private String defaultSink;

	PipeWireGraph(String json)
	{
		JsonElement parsed = new JsonParser().parse(json);
		if (!parsed.isJsonArray()) throw new IllegalArgumentException("Invalid PipeWire graph");
		objects = parsed.getAsJsonArray();
		for (JsonElement element : objects)
		{
			if (!element.isJsonObject()) continue;
			JsonObject object = element.getAsJsonObject();
			JsonObject info = object(object, "info");
			JsonObject props = object(info, "props");
			if (text(object, "type").equals("PipeWire:Interface:Node")
				&& text(props, "media.class").equals("Audio/Sink"))
			{
				String name = text(props, "node.name"), serial = text(props, "object.serial");
				if (name.isEmpty() || !serial.matches("[0-9]+") || !object.has("id")) continue;
				String description = text(props, "node.description");
				if (description.isEmpty()) description = text(props, "node.nick");
				sinks.add(new Sink(object.get("id").getAsInt(), serial, name, description.isEmpty() ? name : description, nodeMuted(info)));
			}
			if (text(object, "type").equals("PipeWire:Interface:Metadata")
				&& text(object(object, "props"), "metadata.name").equals("default")
				&& object.has("metadata") && object.get("metadata").isJsonArray())
			{
				for (JsonElement entry : object.getAsJsonArray("metadata"))
				{
					if (!entry.isJsonObject()) continue;
					JsonObject metadata = entry.getAsJsonObject();
					if (!text(metadata, "subject").equals("0") || !text(metadata, "key").equals("default.audio.sink")) continue;
					JsonElement value = metadata.get("value");
					if (value == null || value.isJsonNull()) continue;
					if (value.isJsonPrimitive()) value = new JsonParser().parse(value.getAsString());
					if (value.isJsonObject()) defaultSink = text(value.getAsJsonObject(), "name");
				}
			}
		}
	}

	List<AudioCaptureEndpoint> endpoints()
	{
		List<AudioCaptureEndpoint> result = new ArrayList<>();
		result.add(AudioCaptureEndpoint.systemDefault());
		sinks.stream().sorted(Comparator.comparing(sink -> sink.description))
			.filter(sink -> sinks.stream().filter(other -> other.name.equals(sink.name)).count() == 1)
			.forEach(sink -> result.add(new AudioCaptureEndpoint(identity(sink.name), sink.description)));
		return result;
	}

	Sink resolve(AudioCaptureEndpoint endpoint)
	{
		String name;
		if (endpoint.isSystemDefault()) name = defaultSink;
		else
		{
			if (!endpoint.getId().startsWith(ENDPOINT_PREFIX))
				throw new IllegalStateException("Choose a Linux PipeWire output; the saved output belongs to another backend");
			try
			{
				name = new String(Base64.getUrlDecoder().decode(endpoint.getId().substring(ENDPOINT_PREFIX.length())), StandardCharsets.UTF_8);
			}
			catch (IllegalArgumentException malformed) { throw new IllegalStateException("The saved PipeWire output identity is invalid", malformed); }
		}
		List<Sink> matches = new ArrayList<>();
		for (Sink sink : sinks) if (sink.name.equals(name)) matches.add(sink);
		if (matches.size() != 1)
			throw new IllegalStateException(endpoint.isSystemDefault()
				? "No unambiguous default PipeWire output is available; check your audio settings and restart Music Sync"
				: "Selected PipeWire output is unavailable or ambiguous; reconnect it and restart Music Sync");
		return matches.get(0);
	}

	/** Verify actual links before delivering PCM; never accept a microphone or another sink. */
	boolean linkedOnlyTo(String captureName, Sink target)
	{
		int captureId = -1;
		for (JsonElement element : objects)
		{
			if (!element.isJsonObject()) continue;
			JsonObject object = element.getAsJsonObject();
			if (text(object, "type").equals("PipeWire:Interface:Node")
				&& text(object(object(object, "info"), "props"), "node.name").equals(captureName))
				captureId = object.get("id").getAsInt();
		}
		if (captureId < 0) return false;
		boolean linked = false;
		for (JsonElement element : objects)
		{
			if (!element.isJsonObject()) continue;
			JsonObject object = element.getAsJsonObject(), info = object(object, "info");
			if (!text(object, "type").equals("PipeWire:Interface:Link")
				|| !text(info, "input-node-id").equals(Integer.toString(captureId))) continue;
			if (!text(info, "output-node-id").equals(Integer.toString(target.id)))
				throw new IllegalStateException("PipeWire routed capture to a different source; Music Sync stopped");
			String state = text(info, "state");
			if (state.equals("active") || state.equals("paused")) linked = true;
		}
		return linked;
	}

	static String identity(String name)
	{
		return ENDPOINT_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
	}
	boolean defaultMuted()
	{
		return sinks.stream().anyMatch(sink -> sink.name.equals(defaultSink) && sink.muted);
	}
	static boolean nodeMuted(JsonObject info)
	{
		JsonElement params = object(info, "params").get("Props");
		if (params == null || !params.isJsonArray()) return false;
		for (JsonElement entry : params.getAsJsonArray())
			if (entry.isJsonObject())
				for (String key : List.of("mute", "softMute"))
				{
					JsonElement value = entry.getAsJsonObject().get(key);
					if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean()) return true;
				}
		return false;
	}
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
	static final class Sink
	{
		final int id;
		final String serial, name, description;
		final boolean muted;
		Sink(int id, String serial, String name, String description)
		{
			this(id, serial, name, description, false);
		}
		Sink(int id, String serial, String name, String description, boolean muted)
		{
			this.id = id; this.serial = serial; this.name = name; this.description = description;
			this.muted = muted;
		}
		boolean sameTarget(Sink other) { return name.equals(other.name) && serial.equals(other.serial); }
	}
}
