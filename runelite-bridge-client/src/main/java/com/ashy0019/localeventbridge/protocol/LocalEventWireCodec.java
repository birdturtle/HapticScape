package com.ashy0019.localeventbridge.protocol;

import com.ashy0019.localeventbridge.event.ChatMessageEvent;
import com.ashy0019.localeventbridge.event.LocalEvent;
import com.ashy0019.localeventbridge.event.InventoryOccupancyEvent;
import com.ashy0019.localeventbridge.event.LootReceivedEvent;
import com.ashy0019.localeventbridge.event.NotificationEmittedEvent;
import com.ashy0019.localeventbridge.event.ActorDeathObservation;
import com.ashy0019.localeventbridge.event.StatusChangedEvent;
import com.ashy0019.localeventbridge.event.ResourceChangedEvent;
import com.ashy0019.localeventbridge.event.ExperienceChangedEvent;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Objects;

/**
 * Explicit JSON codec for source-neutral gameplay events.
 *
 * <p>This deliberately does not use Gson reflection on event classes. Wire
 * field names are protocol-owned and remain stable if Java implementation
 * details change.</p>
 */
public final class LocalEventWireCodec
{
	private final Gson gson;

	public LocalEventWireCodec(Gson gson)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
	}

	public String encode(LocalEvent event)
	{
		return encodeEnvelope(envelope(event));
	}

	public String encodeEnvelope(EventEnvelope envelope)
	{
		Objects.requireNonNull(envelope, "envelope");
		JsonObject root = new JsonObject();
		root.addProperty("protocol", envelope.getProtocol());
		root.addProperty("version", envelope.getVersion());
		root.addProperty("source", envelope.getSource());
		root.addProperty("type", envelope.getType());
		root.add("payload", envelope.getPayload());
		return gson.toJson(root);
	}

	public EventEnvelope envelope(LocalEvent event)
	{
		Objects.requireNonNull(event, "event");
		JsonObject payload = new JsonObject();

		if (event instanceof ExperienceChangedEvent)
		{
			ExperienceChangedEvent xp = (ExperienceChangedEvent) event;
			payload.addProperty("skillId", xp.getSkillId());
			payload.addProperty("previousXp", xp.getPreviousXp());
			payload.addProperty("currentXp", xp.getCurrentXp());
			payload.addProperty("gainedXp", xp.getGainedXp());
			payload.addProperty("previousLevel", xp.getPreviousLevel());
			payload.addProperty("currentLevel", xp.getCurrentLevel());
		}
		else if (event instanceof ChatMessageEvent)
		{
			ChatMessageEvent chat = (ChatMessageEvent) event;
			payload.addProperty("kind", chatKindToWire(chat.getKind()));
			payload.addProperty("rawMessage", chat.getRawMessage());
			payload.addProperty("normalizedMessage", chat.getNormalizedMessage());
			payload.addProperty("receivedPlayerMessage", chat.isReceivedPlayerMessage());
		}
		else if (event instanceof ActorDeathObservation)
		{
			// Player death currently has no event-specific payload.
		}
		else if (event instanceof ResourceChangedEvent)
		{
			ResourceChangedEvent vitals = (ResourceChangedEvent) event;
			payload.addProperty("kind", vitalKindToWire(vitals.getKind()));
			payload.addProperty("currentValue", vitals.getCurrentValue());
			payload.addProperty("maximumValue", vitals.getMaximumValue());
		}
		else if (event instanceof InventoryOccupancyEvent)
		{
			InventoryOccupancyEvent inventory = (InventoryOccupancyEvent) event;
			payload.addProperty("filledSlots", inventory.getFilledSlots());
			payload.addProperty("capacity", inventory.getCapacity());
		}
		else if (event instanceof StatusChangedEvent)
		{
			StatusChangedEvent toxic = (StatusChangedEvent) event;
			payload.addProperty("status", toxicStatusToWire(toxic.getStatus()));
		}
		else if (event instanceof LootReceivedEvent)
		{
			LootReceivedEvent loot = (LootReceivedEvent) event;
			payload.addProperty("stackCount", loot.getStackCount());
			payload.addProperty("totalValue", loot.getTotalValue());
		}
		else if (event instanceof NotificationEmittedEvent)
		{
			NotificationEmittedEvent notification = (NotificationEmittedEvent) event;
			payload.addProperty("sourceFocused", notification.isSourceFocused());
			payload.addProperty("sendWhenFocused", notification.isSendWhenFocused());
		}
		else
		{
			throw new LocalEventProtocolException(
				"Unsupported event implementation: " + event.getClass().getName()
			);
		}

		return new EventEnvelope(
			LocalEventProtocol.NAME,
			LocalEventProtocol.VERSION,
			event.getSource(),
			event.getType(),
			payload
		);
	}

	public LocalEvent decode(String json)
	{
		return decode(parseEnvelope(json));
	}

	public EventEnvelope parseEnvelope(String json)
	{
		if (json == null)
		{
			throw new LocalEventProtocolException("Event message must not be null");
		}
		if (json.length() > LocalEventProtocol.MAX_MESSAGE_CHARS)
		{
			throw new LocalEventProtocolException("Event message exceeds maximum size");
		}

		final JsonElement parsed;
		try
		{
			parsed = new JsonParser().parse(json);
		}
		catch (RuntimeException ex)
		{
			throw new LocalEventProtocolException("Event message is not valid JSON", ex);
		}

		if (parsed == null || !parsed.isJsonObject())
		{
			throw new LocalEventProtocolException("Event message root must be an object");
		}

		JsonObject root = parsed.getAsJsonObject();
		return new EventEnvelope(
			requireString(root, "protocol"),
			requireInt(root, "version"),
			requireString(root, "source"),
			requireString(root, "type"),
			requireObject(root, "payload")
		);
	}

	public LocalEvent decode(EventEnvelope envelope)
	{
		Objects.requireNonNull(envelope, "envelope");
		if (!LocalEventProtocol.NAME.equals(envelope.getProtocol()))
		{
			throw new LocalEventProtocolException(
				"Unsupported event protocol: " + envelope.getProtocol()
			);
		}
		if (envelope.getVersion() != LocalEventProtocol.VERSION)
		{
			throw new LocalEventProtocolException(
				"Unsupported event protocol version: " + envelope.getVersion()
			);
		}

		String source = envelope.getSource();
		JsonObject payload = envelope.getPayload();
		switch (envelope.getType())
		{
			case ExperienceChangedEvent.TYPE:
				return new ExperienceChangedEvent(
					source,
					requireString(payload, "skillId"),
					requireInt(payload, "previousXp"),
					requireInt(payload, "currentXp"),
					requireInt(payload, "gainedXp"),
					requireInt(payload, "previousLevel"),
					requireInt(payload, "currentLevel")
				);
			case ChatMessageEvent.TYPE:
				return new ChatMessageEvent(
					source,
					chatKindFromWire(requireString(payload, "kind")),
					requireString(payload, "rawMessage"),
					requireString(payload, "normalizedMessage"),
					payload.has("receivedPlayerMessage")
						? requireBoolean(payload, "receivedPlayerMessage")
						: "direct_message".equals(requireString(payload, "kind"))
				);
			case ActorDeathObservation.TYPE:
				return new ActorDeathObservation(source);
			case ResourceChangedEvent.TYPE:
				return new ResourceChangedEvent(
					source,
					vitalKindFromWire(requireString(payload, "kind")),
					requireInt(payload, "currentValue"),
					requireInt(payload, "maximumValue")
				);
			case InventoryOccupancyEvent.TYPE:
				return new InventoryOccupancyEvent(
					source,
					requireInt(payload, "filledSlots"),
					requireInt(payload, "capacity")
				);
			case StatusChangedEvent.TYPE:
				return new StatusChangedEvent(
					source,
					toxicStatusFromWire(requireString(payload, "status"))
				);
			case LootReceivedEvent.TYPE:
				return new LootReceivedEvent(
					source,
					requireInt(payload, "stackCount"),
					requireLong(payload, "totalValue")
				);
			case NotificationEmittedEvent.TYPE:
				return new NotificationEmittedEvent(
					source,
					requireBoolean(payload, "sourceFocused"),
					requireBoolean(payload, "sendWhenFocused")
				);
			default:
				throw new LocalEventProtocolException(
					"Unsupported event type: " + envelope.getType()
				);
		}
	}

	private static String chatKindToWire(ChatMessageEvent.Kind kind)
	{
		switch (kind)
		{
			case DIRECT_MESSAGE:
				return "direct_message";
			case TRADE_REQUEST:
				return "trade_request";
			case OTHER:
				return "other";
			default:
				throw new LocalEventProtocolException("Unsupported chat kind: " + kind);
		}
	}

	private static ChatMessageEvent.Kind chatKindFromWire(String value)
	{
		switch (normalizeWireEnum(value))
		{
			case "direct_message":
				return ChatMessageEvent.Kind.DIRECT_MESSAGE;
			case "trade_request":
				return ChatMessageEvent.Kind.TRADE_REQUEST;
			case "other":
				return ChatMessageEvent.Kind.OTHER;
			default:
				throw new LocalEventProtocolException("Unsupported chat kind: " + value);
		}
	}

	private static String vitalKindToWire(ResourceChangedEvent.Kind kind)
	{
		switch (kind)
		{
			case HITPOINTS:
				return "hitpoints";
			case PRAYER:
				return "prayer";
			case SPECIAL_ATTACK:
				return "special_attack";
			default:
				throw new LocalEventProtocolException("Unsupported vitals kind: " + kind);
		}
	}

	private static ResourceChangedEvent.Kind vitalKindFromWire(String value)
	{
		switch (normalizeWireEnum(value))
		{
			case "hitpoints":
				return ResourceChangedEvent.Kind.HITPOINTS;
			case "prayer":
				return ResourceChangedEvent.Kind.PRAYER;
			case "special_attack":
				return ResourceChangedEvent.Kind.SPECIAL_ATTACK;
			default:
				throw new LocalEventProtocolException("Unsupported vitals kind: " + value);
		}
	}

	private static String toxicStatusToWire(StatusChangedEvent.Status status)
	{
		switch (status)
		{
			case CLEAR:
				return "clear";
			case POISONED:
				return "poisoned";
			case VENOMED:
				return "venomed";
			default:
				throw new LocalEventProtocolException("Unsupported toxic status: " + status);
		}
	}

	private static StatusChangedEvent.Status toxicStatusFromWire(String value)
	{
		switch (normalizeWireEnum(value))
		{
			case "clear":
				return StatusChangedEvent.Status.CLEAR;
			case "poisoned":
				return StatusChangedEvent.Status.POISONED;
			case "venomed":
				return StatusChangedEvent.Status.VENOMED;
			default:
				throw new LocalEventProtocolException("Unsupported toxic status: " + value);
		}
	}

	private static String normalizeWireEnum(String value)
	{
		return value.trim().toLowerCase(Locale.ROOT);
	}

	private static JsonElement require(JsonObject object, String name)
	{
		JsonElement value = object.get(name);
		if (value == null || value.isJsonNull())
		{
			throw new LocalEventProtocolException("Missing required field: " + name);
		}
		return value;
	}

	private static JsonObject requireObject(JsonObject object, String name)
	{
		JsonElement value = require(object, name);
		if (!value.isJsonObject())
		{
			throw new LocalEventProtocolException("Field must be an object: " + name);
		}
		return value.getAsJsonObject();
	}

	private static String requireString(JsonObject object, String name)
	{
		JsonElement value = require(object, name);
		if (!value.isJsonPrimitive())
		{
			throw new LocalEventProtocolException("Field must be a string: " + name);
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (!primitive.isString())
		{
			throw new LocalEventProtocolException("Field must be a string: " + name);
		}
		return primitive.getAsString();
	}

	private static int requireInt(JsonObject object, String name)
	{
		BigDecimal value = requireNumber(object, name);
		try
		{
			return value.intValueExact();
		}
		catch (ArithmeticException ex)
		{
			throw new LocalEventProtocolException("Field must be an integer: " + name, ex);
		}
	}

	private static long requireLong(JsonObject object, String name)
	{
		BigDecimal value = requireNumber(object, name);
		try
		{
			return value.longValueExact();
		}
		catch (ArithmeticException ex)
		{
			throw new LocalEventProtocolException("Field must be a long integer: " + name, ex);
		}
	}

	private static BigDecimal requireNumber(JsonObject object, String name)
	{
		JsonElement value = require(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			throw new LocalEventProtocolException("Field must be numeric: " + name);
		}
		try
		{
			return new BigDecimal(value.getAsString());
		}
		catch (NumberFormatException ex)
		{
			throw new LocalEventProtocolException("Field contains an invalid number: " + name, ex);
		}
	}

	private static boolean requireBoolean(JsonObject object, String name)
	{
		JsonElement value = require(object, name);
		if (!value.isJsonPrimitive())
		{
			throw new LocalEventProtocolException("Field must be boolean: " + name);
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (!primitive.isBoolean())
		{
			throw new LocalEventProtocolException("Field must be boolean: " + name);
		}
		return primitive.getAsBoolean();
	}
}
