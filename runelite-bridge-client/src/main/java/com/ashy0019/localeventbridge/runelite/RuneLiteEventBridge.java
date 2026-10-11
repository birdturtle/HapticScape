package com.ashy0019.localeventbridge.runelite;

import com.ashy0019.localeventbridge.LocalEventSink;
import com.ashy0019.localeventbridge.event.ExperienceTracker;
import com.ashy0019.localeventbridge.protocol.SourceCapability;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.events.NotificationFired;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;

/**
 * RuneLite-facing event bridge.
 *
 * <p>RuneLite objects are translated into source-neutral observations before
 * they cross the local event boundary.</p>
 */
public final class RuneLiteEventBridge
{
	public static final String SOURCE_ID = "runelite";

	public static final Set<SourceCapability> CAPABILITIES = Collections.unmodifiableSet(
		EnumSet.of(
			SourceCapability.EXPERIENCE,
			SourceCapability.CHAT,
			SourceCapability.RESOURCES,
			SourceCapability.INVENTORY_OCCUPANCY,
			SourceCapability.STATUS,
			SourceCapability.LOOT,
			SourceCapability.ACTOR_DEATH,
			SourceCapability.NOTIFICATION
		)
	);

	private final Client client;
	private final LocalEventSink sink;
	private final ExperienceTracker experienceTracker = new ExperienceTracker();
	private final RuneLiteExperienceAdapter experienceAdapter = new RuneLiteExperienceAdapter();
	private final RuneLiteChatAdapter chatAdapter = new RuneLiteChatAdapter();
	private final RuneLiteInventoryAdapter inventoryAdapter = new RuneLiteInventoryAdapter();
	private final RuneLiteLootEventAdapter lootAdapter;
	private final RuneLiteNotificationAdapter notificationAdapter =
		new RuneLiteNotificationAdapter();
	private final RuneLiteActorDeathAdapter actorDeathAdapter =
		new RuneLiteActorDeathAdapter();
	private final RuneLiteStatusAdapter statusAdapter = new RuneLiteStatusAdapter();
	private final RuneLiteResourceAdapter resourceAdapter = new RuneLiteResourceAdapter();
	private boolean awaitingLoginSeed;

	public RuneLiteEventBridge(Client client, ItemManager itemManager, LocalEventSink sink)
	{
		this.client = Objects.requireNonNull(client, "client");
		this.lootAdapter = new RuneLiteLootEventAdapter(
			Objects.requireNonNull(itemManager, "itemManager")
		);
		this.sink = Objects.requireNonNull(sink, "sink");
	}

	public void start()
	{
		experienceTracker.reset();
		awaitingLoginSeed = false;
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// A plugin enabled mid-session sees an already-stable client state, so
			// it is safe to snapshot immediately. Fresh logins are handled below
			// after the first game tick, once RuneLite has populated its state.
			seedCurrentState();
		}
	}

	public void onGameStateChanged(GameState gameState)
	{
		Objects.requireNonNull(gameState, "gameState");
		if (gameState == GameState.LOGGED_IN)
		{
			// LOGGED_IN can arrive before skill XP, resources, varps, and
			// containers are fully populated. Seeding here can capture zero/partial
			// values and turn the subsequent initialization burst into fake XP
			// gains, level-ups, and resource changes. Prime on the first game tick
			// instead and ignore stateful observations until that baseline exists.
			experienceTracker.reset();
			awaitingLoginSeed = true;
		}
		else if (gameState == GameState.LOGIN_SCREEN
			|| gameState == GameState.HOPPING
			|| gameState == GameState.CONNECTION_LOST)
		{
			awaitingLoginSeed = false;
			experienceTracker.reset();
			sink.resetSourceState();
		}
	}

	public void onGameTick()
	{
		if (awaitingLoginSeed && isLoggedIn())
		{
			seedCurrentState();
			awaitingLoginSeed = false;
		}
	}

	public void onStatChanged(StatChanged event)
	{
		if (!isLoggedIn() || awaitingLoginSeed)
		{
			return;
		}

		resourceAdapter.adapt(event).ifPresent(sink::onResource);
		sink.onExperience(
			experienceAdapter.update(experienceTracker, event.getSkill(), event.getXp())
		);
	}

	public void onChatMessage(ChatMessage event)
	{
		sink.onChat(chatAdapter.adapt(event, client.getLocalPlayer() == null
			? null : client.getLocalPlayer().getName()));
	}

	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (!isLoggedIn() || awaitingLoginSeed)
		{
			return;
		}
		inventoryAdapter.adapt(event).ifPresent(sink::onInventory);
	}

	public void onVarbitChanged(VarbitChanged event)
	{
		if (!isLoggedIn() || awaitingLoginSeed)
		{
			return;
		}
		resourceAdapter.adapt(event).ifPresent(sink::onResource);
		statusAdapter.adapt(event).ifPresent(sink::onStatus);
	}

	public void onLootReceived(Collection<ItemStack> items)
	{
		if (items == null || !isLoggedIn())
		{
			return;
		}
		sink.onLoot(lootAdapter.adapt(items));
	}

	public void onActorDeath(ActorDeath event)
	{
		actorDeathAdapter
			.adapt(event, client.getLocalPlayer())
			.ifPresent(sink::onActorDeath);
	}

	public void onNotificationFired(NotificationFired event, boolean sourceFocused)
	{
		sink.onNotification(notificationAdapter.adapt(event, sourceFocused));
	}

	private boolean isLoggedIn()
	{
		return client.getGameState() == GameState.LOGGED_IN;
	}

	private void seedCurrentState()
	{
		for (Skill skill : Skill.values())
		{
			experienceAdapter.seed(
				experienceTracker,
				skill,
				client.getSkillExperience(skill)
			);
		}

		sink.seedResource(resourceAdapter.hitpoints(
			client.getBoostedSkillLevel(Skill.HITPOINTS),
			client.getRealSkillLevel(Skill.HITPOINTS)
		));
		sink.seedResource(resourceAdapter.prayer(
			client.getBoostedSkillLevel(Skill.PRAYER),
			client.getRealSkillLevel(Skill.PRAYER)
		));
		sink.seedResource(resourceAdapter.specialAttackFromVarp(
			client.getVarpValue(VarPlayerID.SA_ENERGY)
		));

		sink.seedStatus(statusAdapter.fromVarp(
			client.getVarpValue(VarPlayerID.POISON)
		));

		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory != null)
		{
			sink.seedInventory(inventoryAdapter.inventory(inventory));
		}
	}
}
