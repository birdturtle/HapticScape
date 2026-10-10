package com.ashy0019.hapticscape.integration.desktop;

import com.ashy0019.hapticscape.music.AudioCaptureApplication;
import org.junit.Test;
import static org.junit.Assert.*;

public class PipeWireApplicationGraphTest
{
	@Test
	public void groupsApplicationStreamsUsingClientMetadataAndRejectsCaptureAndSinkNodes()
	{
		PipeWireApplicationGraph graph = new PipeWireApplicationGraph("["
			+ "{\"id\":9,\"type\":\"PipeWire:Interface:Client\",\"info\":{\"props\":{\"application.id\":\"org.Example.Player\",\"application.name\":\"Player\"}}},"
			+ node(10, "100", "Stream/Output/Audio", "\"client.id\":9") + ","
			+ node(11, "101", "Stream/Output/Audio", "\"client.id\":9") + ","
			+ node(12, "102", "Stream/Input/Audio", "\"client.id\":9") + ","
			+ node(13, "103", "Audio/Sink", "\"client.id\":9") + ","
			+ port(20, 10, "FL", "out") + "," + port(21, 11, "FL", "out") + "]");
		assertEquals(1, graph.applications().size());
		AudioCaptureApplication app = graph.applications().get(0);
		assertEquals("Player", app.getDisplayName());
		assertEquals(PipeWireApplicationGraph.identity("id", "org.Example.Player"), app.getId());
		assertEquals(2, graph.resolve(app).nodes.size());
		assertEquals(2, graph.resolve(app).ports.size());
	}

	@Test
	public void identitiesSurviveNormalizationAndRestartsWithoutUsingPidOrDisplayName()
	{
		String id = PipeWireApplicationGraph.identity("binary", "/opt/MixedCase/Player");
		assertNotEquals(id, PipeWireApplicationGraph.identity("binary", "/opt/mixedcase/player"));
		AudioCaptureApplication saved = AudioCaptureApplication.fromPersisted(id, "Old name");
		assertEquals(id, saved.getId());
		PipeWireApplicationGraph graph = new PipeWireApplicationGraph("["
			+ node(55, "555", "Stream/Output/Audio", "\"application.process.binary\":\"/opt/MixedCase/Player\",\"application.process.id\":88,\"application.name\":\"New name\"")
			+ "," + port(66, 55, "MONO", "out") + "]");
		assertEquals("New name", graph.resolve(saved).name);
		assertEquals("555", graph.resolve(saved).nodes.get(0).serial);
		assertEquals(java.util.List.of("MONO"), graph.resolve(saved).channels);
	}

	@Test
	public void ambiguousExecutableDoesNotAppearAndDoesNotFallback()
	{
		PipeWireApplicationGraph graph = new PipeWireApplicationGraph("["
			+ node(10, "100", "Stream/Output/Audio", "\"application.process.binary\":\"player\",\"application.name\":\"One\"") + ","
			+ node(11, "101", "Stream/Output/Audio", "\"application.process.binary\":\"player\",\"application.name\":\"Two\"") + ","
			+ port(20, 10, "FL", "out") + "," + port(21, 11, "FL", "out") + "]");
		assertTrue(graph.applications().isEmpty());
		try { graph.resolve(new AudioCaptureApplication(PipeWireApplicationGraph.identity("binary", "player"), "Player")); fail(); }
		catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("ambiguous")); }
		assertNull(graph.resolve(new AudioCaptureApplication(PipeWireApplicationGraph.identity("id", "missing"), "Missing")));
		try { graph.resolve(new AudioCaptureApplication("windows-id", "Foreign")); fail(); }
		catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("another backend")); }
	}

	@Test
	public void verifiesEveryExactChannelRouteAndRejectsUnexpectedSourcesAndDuplicates()
	{
		String base = node(10, "100", "Stream/Output/Audio", "\"application.id\":\"selected\"") + ","
			+ node(50, "500", "Stream/Input/Audio", "\"node.name\":\"capture\"") + ","
			+ port(20, 10, "FL", "out") + "," + port(21, 10, "FR", "out") + ","
			+ port(60, 50, "FL", "in") + "," + port(61, 50, "FR", "in");
		AudioCaptureApplication app = new AudioCaptureApplication(PipeWireApplicationGraph.identity("id", "selected"), "Selected");
		PipeWireApplicationGraph graph = new PipeWireApplicationGraph("[" + base + "," + link(20, 60) + "," + link(21, 61) + "]");
		assertTrue(graph.linkedOnlyTo("capture", graph.routes(graph.resolve(app), "capture")));
		graph = new PipeWireApplicationGraph("[" + base + "," + link(20, 60) + "]");
		assertFalse(graph.linkedOnlyTo("capture", graph.routes(graph.resolve(app), "capture")));
		for (int other : new int[] { 20, 99 })
		{
			graph = new PipeWireApplicationGraph("[" + base + "," + link(20, 60) + "," + link(other, 60) + "]");
			try { graph.linkedOnlyTo("capture", graph.routes(graph.resolve(app), "capture")); fail(); }
			catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("Unexpected source")); }
		}
	}

	@Test
	public void idlePlaybackAndDefaultOrRoutedSinkMuteSilenceApplicationCapture()
	{
		String player = node(10, "100", "Stream/Output/Audio", "\"application.id\":\"selected\"");
		AudioCaptureApplication app = new AudioCaptureApplication(PipeWireApplicationGraph.identity("id", "selected"), "Selected");
		String sink = "{\"id\":70,\"type\":\"PipeWire:Interface:Node\",\"info\":{\"props\":{\"media.class\":\"Audio/Sink\",\"node.name\":\"output\",\"object.serial\":70},\"params\":{\"Props\":[{\"mute\":true}]}}}";
		String metadata = "{\"type\":\"PipeWire:Interface:Metadata\",\"props\":{\"metadata.name\":\"default\"},\"metadata\":[{\"subject\":0,\"key\":\"default.audio.sink\",\"value\":{\"name\":\"output\"}}]}";
		String ports = "," + port(20, 10, "FL", "out");
		for (String graphJson : new String[] {
			player.replace("\"info\":{", "\"info\":{\"state\":\"idle\",") + ports,
			player + ports + "," + sink + "," + metadata,
			player + ports + "," + sink + ",{\"type\":\"PipeWire:Interface:Link\",\"info\":{\"output-node-id\":10,\"input-node-id\":70}}"
		})
		{
			PipeWireApplicationGraph graph = new PipeWireApplicationGraph("[" + graphJson + "]");
			assertTrue(graph.silent(graph.resolve(app)));
		}
		PipeWireApplicationGraph unmuted = new PipeWireApplicationGraph("[" + player + ports + "]");
		assertFalse(unmuted.silent(unmuted.resolve(app)));
	}

	@Test
	public void unsupportedChannelIsVisibleInsteadOfSilentlyDroppingAudio()
	{
		PipeWireApplicationGraph graph = new PipeWireApplicationGraph("["
			+ node(10, "100", "Stream/Output/Audio", "\"application.id\":\"selected\"") + "," + port(20, 10, "UNKNOWN", "out") + "]");
		try { graph.resolve(graph.applications().get(0)); fail(); }
		catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("unsupported audio channel")); }
	}

	static String node(int id, String serial, String mediaClass, String extra)
	{
		return "{\"id\":" + id + ",\"type\":\"PipeWire:Interface:Node\",\"info\":{\"props\":{\"object.serial\":\"" + serial
			+ "\",\"media.class\":\"" + mediaClass + "\"," + extra + "}}}";
	}
	static String port(int id, int node, String channel, String direction)
	{
		return "{\"id\":" + id + ",\"type\":\"PipeWire:Interface:Port\",\"info\":{\"props\":{\"node.id\":" + node
			+ ",\"object.serial\":" + (1000 + id) + ",\"audio.channel\":\"" + channel + "\",\"port.direction\":\"" + direction + "\"}}}";
	}
	static String link(int outputPort, int inputPort)
	{
		return "{\"type\":\"PipeWire:Interface:Link\",\"info\":{\"input-node-id\":50,\"output-port-id\":" + outputPort
			+ ",\"input-port-id\":" + inputPort + ",\"state\":\"active\"}}";
	}
}
