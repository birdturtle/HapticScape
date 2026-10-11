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
public final class EventWireCodec
{
	private final Gson gson;

	public EventWireCodec(Gson gson)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
	}

	public String encode(HapticScapeEvent event)
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

	public EventEnvelope envelope(HapticScapeEvent event)
	{
		Objects.requireNonNull(event, "event");
		JsonObject payload = new JsonObject();

		if (event instanceof XpEvent)
		{
			XpEvent xp = (XpEvent) event;
			payload.addProperty("skillId", xp.getSkillId());
			payload.addProperty("previousXp", xp.getPreviousXp());
			payload.addProperty("currentXp", xp.getCurrentXp());
			payload.addProperty("gainedXp", xp.getGainedXp());
			payload.addProperty("previousLevel", xp.getPreviousLevel());
			payload.addProperty("currentLevel", xp.getCurrentLevel());
		}
		else if (event instanceof ChatEvent)
		{
			ChatEvent chat = (ChatEvent) event;
			payload.addProperty("kind", chatKindToWire(chat.getKind()));
			payload.addProperty("rawMessage", chat.getRawMessage());
			payload.addProperty("normalizedMessage", chat.getNormalizedMessage());
			payload.addProperty("receivedPlayerMessage", chat.isReceivedPlayerMessage());
		}
		else if (event instanceof PlayerDeathEvent)
		{
			// Player death currently has no event-specific payload.
		}
		else if (event instanceof VitalsChangedEvent)
		{
			VitalsChangedEvent vitals = (VitalsChangedEvent) event;
			payload.addProperty("kind", vitalKindToWire(vitals.getKind()));
			payload.addProperty("currentValue", vitals.getCurrentValue());
			payload.addProperty("maximumValue", vitals.getMaximumValue());
		}
		else if (event instanceof InventoryChangedEvent)
		{
			InventoryChangedEvent inventory = (InventoryChangedEvent) event;
			payload.addProperty("filledSlots", inventory.getFilledSlots());
			payload.addProperty("capacity", inventory.getCapacity());
		}
		else if (event instanceof ToxicStatusChangedEvent)
		{
			ToxicStatusChangedEvent toxic = (ToxicStatusChangedEvent) event;
			payload.addProperty("status", toxicStatusToWire(toxic.getStatus()));
		}
		else if (event instanceof LootReceivedEvent)
		{
			LootReceivedEvent loot = (LootReceivedEvent) event;
			payload.addProperty("stackCount", loot.getStackCount());
			payload.addProperty("totalValue", loot.getTotalValue());
		}
		else if (event instanceof NotificationEvent)
		{
			NotificationEvent notification = (NotificationEvent) event;
			payload.addProperty("sourceFocused", notification.isSourceFocused());
			payload.addProperty("sendWhenFocused", notification.isSendWhenFocused());
		}
		else
		{
			throw new EventProtocolException(
				"Unsupported event implementation: " + event.getClass().getName()
			);
		}

		return new EventEnvelope(
			EventProtocol.NAME,
			EventProtocol.VERSION,
			event.getSource(),
			event.getType(),
			payload
		);
	}

	public HapticScapeEvent decode(String json)
	{
		return decode(parseEnvelope(json));
	}

	public EventEnvelope parseEnvelope(String json)
	{
		if (json == null)
		{
			throw new EventProtocolException("Event message must not be null");
		}
		if (json.length() > EventProtocol.MAX_MESSAGE_CHARS)
		{
			throw new EventProtocolException("Event message exceeds maximum size");
		}

		final JsonElement parsed;
		try
		{
			parsed = new JsonParser().parse(json);
		}
		catch (RuntimeException ex)
		{
			throw new EventProtocolException("Event message is not valid JSON", ex);
		}

		if (parsed == null || !parsed.isJsonObject())
		{
			throw new EventProtocolException("Event message root must be an object");
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

	public HapticScapeEvent decode(EventEnvelope envelope)
	{
		Objects.requireNonNull(envelope, "envelope");
		if (!EventProtocol.supports(envelope.getProtocol()))
		{
			throw new EventProtocolException(
				"Unsupported event protocol: " + envelope.getProtocol()
			);
		}
		if (envelope.getVersion() != EventProtocol.VERSION)
		{
			throw new EventProtocolException(
				"Unsupported event protocol version: " + envelope.getVersion()
			);
		}

		String source = envelope.getSource();
		JsonObject payload = envelope.getPayload();
		switch (envelope.getType())
		{
			case XpEvent.TYPE:
				return new XpEvent(
					source,
					requireString(payload, "skillId"),
					requireInt(payload, "previousXp"),
					requireInt(payload, "currentXp"),
					requireInt(payload, "gainedXp"),
					requireInt(payload, "previousLevel"),
					requireInt(payload, "currentLevel")
				);
			case ChatEvent.TYPE:
				return new ChatEvent(
					source,
					chatKindFromWire(requireString(payload, "kind")),
					requireString(payload, "rawMessage"),
					requireString(payload, "normalizedMessage"),
					payload.has("receivedPlayerMessage")
						? requireBoolean(payload, "receivedPlayerMessage")
						: "direct_message".equals(requireString(payload, "kind"))
				);
			case PlayerDeathEvent.TYPE:
				return new PlayerDeathEvent(source);
			case VitalsChangedEvent.TYPE:
				return new VitalsChangedEvent(
					source,
					vitalKindFromWire(requireString(payload, "kind")),
					requireInt(payload, "currentValue"),
					requireInt(payload, "maximumValue")
				);
			case InventoryChangedEvent.TYPE:
				return new InventoryChangedEvent(
					source,
					requireInt(payload, "filledSlots"),
					requireInt(payload, "capacity")
				);
			case ToxicStatusChangedEvent.TYPE:
				return new ToxicStatusChangedEvent(
					source,
					toxicStatusFromWire(requireString(payload, "status"))
				);
			case LootReceivedEvent.TYPE:
				return new LootReceivedEvent(
					source,
					requireInt(payload, "stackCount"),
					requireLong(payload, "totalValue")
				);
			case NotificationEvent.TYPE:
				return new NotificationEvent(
					source,
					requireBoolean(payload, "sourceFocused"),
					requireBoolean(payload, "sendWhenFocused")
				);
			default:
				throw new EventProtocolException(
					"Unsupported event type: " + envelope.getType()
				);
		}
	}

	private static String chatKindToWire(ChatEvent.Kind kind)
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
				throw new EventProtocolException("Unsupported chat kind: " + kind);
		}
	}

	private static ChatEvent.Kind chatKindFromWire(String value)
	{
		switch (normalizeWireEnum(value))
		{
			case "direct_message":
				return ChatEvent.Kind.DIRECT_MESSAGE;
			case "trade_request":
				return ChatEvent.Kind.TRADE_REQUEST;
			case "other":
				return ChatEvent.Kind.OTHER;
			default:
				throw new EventProtocolException("Unsupported chat kind: " + value);
		}
	}

	private static String vitalKindToWire(VitalsChangedEvent.Kind kind)
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
				throw new EventProtocolException("Unsupported vitals kind: " + kind);
		}
	}

	private static VitalsChangedEvent.Kind vitalKindFromWire(String value)
	{
		switch (normalizeWireEnum(value))
		{
			case "hitpoints":
				return VitalsChangedEvent.Kind.HITPOINTS;
			case "prayer":
				return VitalsChangedEvent.Kind.PRAYER;
			case "special_attack":
				return VitalsChangedEvent.Kind.SPECIAL_ATTACK;
			default:
				throw new EventProtocolException("Unsupported vitals kind: " + value);
		}
	}

	private static String toxicStatusToWire(ToxicStatusChangedEvent.Status status)
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
				throw new EventProtocolException("Unsupported toxic status: " + status);
		}
	}

	private static ToxicStatusChangedEvent.Status toxicStatusFromWire(String value)
	{
		switch (normalizeWireEnum(value))
		{
			case "clear":
				return ToxicStatusChangedEvent.Status.CLEAR;
			case "poisoned":
				return ToxicStatusChangedEvent.Status.POISONED;
			case "venomed":
				return ToxicStatusChangedEvent.Status.VENOMED;
			default:
				throw new EventProtocolException("Unsupported toxic status: " + value);
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
			throw new EventProtocolException("Missing required field: " + name);
		}
		return value;
	}

	private static JsonObject requireObject(JsonObject object, String name)
	{
		JsonElement value = require(object, name);
		if (!value.isJsonObject())
		{
			throw new EventProtocolException("Field must be an object: " + name);
		}
		return value.getAsJsonObject();
	}

	private static String requireString(JsonObject object, String name)
	{
		JsonElement value = require(object, name);
		if (!value.isJsonPrimitive())
		{
			throw new EventProtocolException("Field must be a string: " + name);
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (!primitive.isString())
		{
			throw new EventProtocolException("Field must be a string: " + name);
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
			throw new EventProtocolException("Field must be an integer: " + name, ex);
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
			throw new EventProtocolException("Field must be a long integer: " + name, ex);
		}
	}

	private static BigDecimal requireNumber(JsonObject object, String name)
	{
		JsonElement value = require(object, name);
		if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
		{
			throw new EventProtocolException("Field must be numeric: " + name);
		}
		try
		{
			return new BigDecimal(value.getAsString());
		}
		catch (NumberFormatException ex)
		{
			throw new EventProtocolException("Field contains an invalid number: " + name, ex);
		}
	}

	private static boolean requireBoolean(JsonObject object, String name)
	{
		JsonElement value = require(object, name);
		if (!value.isJsonPrimitive())
		{
			throw new EventProtocolException("Field must be boolean: " + name);
		}
		JsonPrimitive primitive = value.getAsJsonPrimitive();
		if (!primitive.isBoolean())
		{
			throw new EventProtocolException("Field must be boolean: " + name);
		}
		return primitive.getAsBoolean();
	}
}
