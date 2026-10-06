package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class PipeWireGraphTest
{
	static String sink(int id, int serial, String name)
	{
		return "{\"id\":" + id + ",\"type\":\"PipeWire:Interface:Node\",\"info\":{\"props\":{"
			+ "\"media.class\":\"Audio/Sink\",\"node.name\":\"" + name + "\",\"object.serial\":" + serial + "}}}";
	}
	static String metadata(String name)
	{
		return "{\"type\":\"PipeWire:Interface:Metadata\",\"props\":{\"metadata.name\":\"default\"},"
			+ "\"metadata\":[{\"subject\":0,\"key\":\"default.audio.sink\",\"value\":{\"name\":\"" + name + "\"}}]}";
	}
	static String capture(String name, int output)
	{
		return "{\"id\":100,\"type\":\"PipeWire:Interface:Node\",\"info\":{\"props\":{\"node.name\":\"" + name + "\"}}},"
			+ "{\"type\":\"PipeWire:Interface:Link\",\"info\":{\"input-node-id\":100,\"output-node-id\":" + output + ",\"state\":\"active\"}}";
	}

	@Test
	public void persistsCaseSensitiveNamesInsteadOfTransientIds()
	{
		PipeWireGraph first = new PipeWireGraph("[" + sink(10, 12, "Output_A") + "," + metadata("Output_A") + "]");
		PipeWireGraph restarted = new PipeWireGraph("[" + sink(20, 30, "Output_A") + "," + metadata("Output_A") + "]");
		assertEquals(first.endpoints().get(1), restarted.endpoints().get(1));
		assertEquals("30", restarted.resolve(first.endpoints().get(1)).serial);
		assertNotEquals(PipeWireGraph.identity("Output_A"), PipeWireGraph.identity("output_a"));
		assertEquals("Output_A", restarted.resolve(AudioCaptureEndpoint.systemDefault()).name);
	}

	@Test
	public void neverSelectsSourcesOrPlaybackStreamsAsOutputs()
	{
		String json = "[" + sink(1, 1, "speakers") + ","
			+ sink(2, 2, "microphone").replace("Audio/Sink", "Audio/Source") + ","
			+ sink(3, 3, "app").replace("Audio/Sink", "Stream/Output/Audio") + "," + metadata("microphone") + "]";
		PipeWireGraph graph = new PipeWireGraph(json);
		assertEquals(2, graph.endpoints().size());
		try { graph.resolve(AudioCaptureEndpoint.systemDefault()); fail("Must not use default microphone"); }
		catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("default")); }
	}

	@Test
	public void doesNotFallBackForMissingOrForeignSelections()
	{
		PipeWireGraph graph = new PipeWireGraph("[" + sink(1, 1, "speakers") + "," + metadata("speakers") + "]");
		for (String id : new String[] { PipeWireGraph.identity("missing"), "windows-endpoint", "pipewire:sink:!" })
		{
			try { graph.resolve(new AudioCaptureEndpoint(id, "Missing")); fail("Unexpected fallback"); }
			catch (IllegalStateException expected) { }
		}
	}

	@Test
	public void acceptsMetadataWithJsonEncodedStringValue()
	{
		String meta = metadata("speakers").replace("\"value\":{\"name\":\"speakers\"}", "\"value\":\"{\\\"name\\\":\\\"speakers\\\"}\"");
		assertEquals("speakers", new PipeWireGraph("[" + sink(1, 2, "speakers") + "," + meta + "]").resolve(AudioCaptureEndpoint.systemDefault()).name);
	}

	@Test
	public void refusesAmbiguousNamesAndWrongLiveLinks()
	{
		PipeWireGraph graph = new PipeWireGraph("[" + sink(1, 1, "same") + "," + sink(2, 2, "same") + "," + metadata("same") + "]");
		assertEquals(1, graph.endpoints().size());
		try { graph.resolve(AudioCaptureEndpoint.systemDefault()); fail("Ambiguous default"); }
		catch (IllegalStateException expected) { }
		graph = new PipeWireGraph("[" + sink(1, 1, "speakers") + "," + capture("capture", 2) + "]");
		try { graph.linkedOnlyTo("capture", graph.sinks.get(0)); fail("Wrong source was linked"); }
		catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("different source")); }
	}
}
