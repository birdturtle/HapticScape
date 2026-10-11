package com.ashy0019.hapticscape.protocol;

import com.ashy0019.hapticscape.event.ChatEvent;
import com.ashy0019.hapticscape.event.HapticScapeEvent;
import com.ashy0019.hapticscape.event.InventoryChangedEvent;
import com.ashy0019.hapticscape.event.LootReceivedEvent;
import com.ashy0019.hapticscape.event.NotificationEvent;
import com.ashy0019.hapticscape.event.PlayerDeathEvent;
import com.ashy0019.hapticscape.event.ToxicStatusChangedEvent;
import com.ashy0019.hapticscape.event.VitalsChangedEvent;
import com.ashy0019.hapticscape.event.XpEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EventWireCodecTest
{
	private final EventWireCodec codec = new EventWireCodec(new Gson());

	@Test
	public void playerOriginSurvivesWireAndLegacyDefaultsFailClosed()
	{
		for (ChatEvent.Kind kind : ChatEvent.Kind.values())
		{
			for (boolean incoming : new boolean[] {false, true})
			{
				String wire = codec.encode(new ChatEvent("test", kind, "hello", "hello", incoming));
				assertEquals(incoming, ((ChatEvent) codec.decode(wire)).isReceivedPlayerMessage());
				JsonObject root = new JsonParser().parse(wire).getAsJsonObject();
				root.getAsJsonObject("payload").remove("receivedPlayerMessage");
				assertEquals(kind == ChatEvent.Kind.DIRECT_MESSAGE,
					((ChatEvent) codec.decode(root.toString())).isReceivedPlayerMessage());
			}
		}
	}

	@Test
	public void roundTripsEveryEventFamily()
	{
		List<HapticScapeEvent> events = Arrays.asList(
			new XpEvent("test-source", "WOODCUTTING", 1_000, 1_420, 420, 9, 10),
			new ChatEvent(
				"test-source",
				ChatEvent.Kind.DIRECT_MESSAGE,
				"<col=ffffff>Hello</col>",
				"Hello"
			),
			new PlayerDeathEvent("test-source"),
			new VitalsChangedEvent(
				"test-source",
				VitalsChangedEvent.Kind.HITPOINTS,
				17,
				99
			),
			new InventoryChangedEvent("test-source", 28, 28),
			new ToxicStatusChangedEvent(
				"test-source",
				ToxicStatusChangedEvent.Status.VENOMED
			),
			new LootReceivedEvent("test-source", 2, 1_500_000L),
			new NotificationEvent("test-source", true, false)
		);

		for (HapticScapeEvent event : events)
		{
			HapticScapeEvent decoded = codec.decode(codec.encode(event));
			assertSameEvent(event, decoded);
		}
	}

	@Test
	public void writesStableVersionedEnvelope()
	{
		String json = codec.encode(new PlayerDeathEvent("test-source"));
		JsonObject root = new JsonParser().parse(json).getAsJsonObject();

		assertEquals(EventProtocol.NAME, root.get("protocol").getAsString());
		assertEquals(EventProtocol.VERSION, root.get("version").getAsInt());
		assertEquals("test-source", root.get("source").getAsString());
		assertEquals(PlayerDeathEvent.TYPE, root.get("type").getAsString());
		assertTrue(root.get("payload").getAsJsonObject().entrySet().isEmpty());
	}

	@Test
	public void wireEnumsAreExplicitAndLowercase()
	{
		JsonObject chat = payload(codec.encode(new ChatEvent(
			"test-source",
			ChatEvent.Kind.TRADE_REQUEST,
			"trade",
			"trade"
		)));
		JsonObject vitals = payload(codec.encode(new VitalsChangedEvent(
			"test-source",
			VitalsChangedEvent.Kind.SPECIAL_ATTACK,
			75,
			100
		)));
		JsonObject toxic = payload(codec.encode(new ToxicStatusChangedEvent(
			"test-source",
			ToxicStatusChangedEvent.Status.POISONED
		)));

		assertEquals("trade_request", chat.get("kind").getAsString());
		assertEquals("special_attack", vitals.get("kind").getAsString());
		assertEquals("poisoned", toxic.get("status").getAsString());
	}

	@Test
	public void acceptsLegacyEventProtocolIdentifierDuringMigration()
	{
		HapticScapeEvent decoded = codec.decode(
			"{\"protocol\":\"hapticscape-local-events\",\"version\":1,"
				+ "\"source\":\"test-source\",\"type\":\"actor.death\",\"payload\":{}}"
		);

		assertTrue(decoded instanceof PlayerDeathEvent);
	}

	@Test(expected = EventProtocolException.class)
	public void rejectsWrongProtocol()
	{
		codec.decode("{\"protocol\":\"something-else\",\"version\":1,"
			+ "\"source\":\"test-source\",\"type\":\"actor.death\",\"payload\":{}}");
	}

	@Test(expected = EventProtocolException.class)
	public void rejectsFutureVersion()
	{
		codec.decode("{\"protocol\":\"local-event-bridge-events\",\"version\":2,"
			+ "\"source\":\"test-source\",\"type\":\"actor.death\",\"payload\":{}}");
	}

	@Test(expected = EventProtocolException.class)
	public void rejectsUnknownEventType()
	{
		codec.decode("{\"protocol\":\"local-event-bridge-events\",\"version\":1,"
			+ "\"source\":\"test-source\",\"type\":\"future_magic\",\"payload\":{}}");
	}

	@Test(expected = EventProtocolException.class)
	public void rejectsMissingRequiredPayloadField()
	{
		codec.decode("{\"protocol\":\"local-event-bridge-events\",\"version\":1,"
			+ "\"source\":\"test-source\",\"type\":\"inventory.occupancy\","
			+ "\"payload\":{\"filledSlots\":28}}");
	}

	@Test(expected = EventProtocolException.class)
	public void rejectsWrongPayloadFieldType()
	{
		codec.decode("{\"protocol\":\"local-event-bridge-events\",\"version\":1,"
			+ "\"source\":\"test-source\",\"type\":\"notification.emitted\","
			+ "\"payload\":{\"sourceFocused\":\"true\",\"sendWhenFocused\":false}}");
	}

	@Test(expected = EventProtocolException.class)
	public void rejectsOversizedMessage()
	{
		StringBuilder json = new StringBuilder(EventProtocol.MAX_MESSAGE_CHARS + 1);
		for (int i = 0; i <= EventProtocol.MAX_MESSAGE_CHARS; i++)
		{
			json.append('x');
		}
		codec.decode(json.toString());
	}

	@Test(expected = EventProtocolException.class)
	public void refusesUnknownJavaEventImplementations()
	{
		codec.encode(new HapticScapeEvent()
		{
			@Override
			public String getSource()
			{
				return "test";
			}

			@Override
			public String getType()
			{
				return "future_event";
			}
		});
	}

	private static JsonObject payload(String json)
	{
		return new JsonParser()
			.parse(json)
			.getAsJsonObject()
			.get("payload")
			.getAsJsonObject();
	}

	private static void assertSameEvent(HapticScapeEvent expected, HapticScapeEvent actual)
	{
		assertEquals(expected.getClass(), actual.getClass());
		assertEquals(expected.getSource(), actual.getSource());
		assertEquals(expected.getType(), actual.getType());

		if (expected instanceof XpEvent)
		{
			XpEvent a = (XpEvent) expected;
			XpEvent b = (XpEvent) actual;
			assertEquals(a.getSkillId(), b.getSkillId());
			assertEquals(a.getPreviousXp(), b.getPreviousXp());
			assertEquals(a.getCurrentXp(), b.getCurrentXp());
			assertEquals(a.getGainedXp(), b.getGainedXp());
			assertEquals(a.getPreviousLevel(), b.getPreviousLevel());
			assertEquals(a.getCurrentLevel(), b.getCurrentLevel());
		}
		else if (expected instanceof ChatEvent)
		{
			ChatEvent a = (ChatEvent) expected;
			ChatEvent b = (ChatEvent) actual;
			assertEquals(a.getKind(), b.getKind());
			assertEquals(a.getRawMessage(), b.getRawMessage());
			assertEquals(a.getNormalizedMessage(), b.getNormalizedMessage());
		}
		else if (expected instanceof VitalsChangedEvent)
		{
			VitalsChangedEvent a = (VitalsChangedEvent) expected;
			VitalsChangedEvent b = (VitalsChangedEvent) actual;
			assertEquals(a.getKind(), b.getKind());
			assertEquals(a.getCurrentValue(), b.getCurrentValue());
			assertEquals(a.getMaximumValue(), b.getMaximumValue());
		}
		else if (expected instanceof InventoryChangedEvent)
		{
			InventoryChangedEvent a = (InventoryChangedEvent) expected;
			InventoryChangedEvent b = (InventoryChangedEvent) actual;
			assertEquals(a.getFilledSlots(), b.getFilledSlots());
			assertEquals(a.getCapacity(), b.getCapacity());
		}
		else if (expected instanceof ToxicStatusChangedEvent)
		{
			assertEquals(
				((ToxicStatusChangedEvent) expected).getStatus(),
				((ToxicStatusChangedEvent) actual).getStatus()
			);
		}
		else if (expected instanceof LootReceivedEvent)
		{
			LootReceivedEvent a = (LootReceivedEvent) expected;
			LootReceivedEvent b = (LootReceivedEvent) actual;
			assertEquals(a.getStackCount(), b.getStackCount());
			assertEquals(a.getTotalValue(), b.getTotalValue());
		}
		else if (expected instanceof NotificationEvent)
		{
			NotificationEvent a = (NotificationEvent) expected;
			NotificationEvent b = (NotificationEvent) actual;
			assertEquals(a.isSourceFocused(), b.isSourceFocused());
			assertEquals(a.isSendWhenFocused(), b.isSendWhenFocused());
		}
		else
		{
			assertTrue(expected instanceof PlayerDeathEvent);
			assertTrue(actual instanceof PlayerDeathEvent);
		}
	}
}
