package com.ashy0019.hapticscape;

import com.ashy0019.hapticscape.clicker.ClickSequence;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRule;
import com.ashy0019.hapticscape.clicker.PhraseTriggerCooldown;
import com.ashy0019.hapticscape.event.ChatEvent;
import com.ashy0019.hapticscape.event.InventoryChangedEvent;
import com.ashy0019.hapticscape.event.LootReceivedEvent;
import com.ashy0019.hapticscape.event.NotificationEvent;
import com.ashy0019.hapticscape.event.PlayerDeathEvent;
import com.ashy0019.hapticscape.event.ToxicStatusChangedEvent;
import com.ashy0019.hapticscape.event.VitalsChangedEvent;
import com.ashy0019.hapticscape.event.XpEvent;
import com.ashy0019.hapticscape.remote.RemoteSettingsSnapshot;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Owns source-neutral gameplay state and turns observations into feedback decisions. */
final class GameplayEventCoordinator implements GameplayEventSink, AutoCloseable
{
	interface FeedbackSink
	{
		void handleXp(
			XpEvent event,
			XpOutputDecision decision,
			RemoteSettingsSnapshot settings,
			XpFeedbackSettings skillSettings);

		void dispatchSpecificAlert(AlertCategory category, boolean allowClick);

		void dispatchGenericAlert();

		void playClick(ClickSequence sequence);

		default void playPhraseHaptics(ClickerPhraseRule rule) { }
	}

	private final Supplier<RemoteSettingsSnapshot> settingsSupplier;
	private final FeedbackSink feedback;
	private final VitalsAlertTracker vitalsAlertTracker = new VitalsAlertTracker();
	private final InventoryAlertTracker inventoryAlertTracker = new InventoryAlertTracker();
	private final ToxicStatusAlertTracker toxicStatusAlertTracker = new ToxicStatusAlertTracker();
	private final AlertDeduplicator alertDeduplicator = new AlertDeduplicator();

	private ScheduledExecutorService alertScheduler;
	private final PhraseTriggerCooldown phraseCooldown = new PhraseTriggerCooldown();

	GameplayEventCoordinator(
		Supplier<RemoteSettingsSnapshot> settingsSupplier,
		FeedbackSink feedback)
	{
		this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
		this.feedback = Objects.requireNonNull(feedback, "feedback");
	}

	void start()
	{
		resetSourceState();
		alertScheduler = Executors.newSingleThreadScheduledExecutor(task ->
		{
			Thread thread = new Thread(task, "hapticscape-alerts");
			thread.setDaemon(true);
			return thread;
		});
	}

	@Override
	public void resetSourceState()
	{
		vitalsAlertTracker.reset();
		inventoryAlertTracker.reset();
		toxicStatusAlertTracker.reset();
		alertDeduplicator.reset();
	}

	@Override
	public void seedVitals(VitalsChangedEvent event)
	{
		vitalsAlertTracker.seed(Objects.requireNonNull(event, "event"));
	}

	@Override
	public void seedInventory(InventoryChangedEvent event)
	{
		inventoryAlertTracker.seed(Objects.requireNonNull(event, "event"));
	}

	@Override
	public void seedToxicStatus(ToxicStatusChangedEvent event)
	{
		toxicStatusAlertTracker.seed(Objects.requireNonNull(event, "event"));
	}

	@Override
	public void onXpEvent(XpEvent event)
	{
		Objects.requireNonNull(event, "event");
		RemoteSettingsSnapshot settings = settingsSupplier.get();
		String skillId = event.getSkillId();
		XpFeedbackSettings skillSettings = settings.getXpFeedbackSettings(skillId);
		XpOutputDecision decision = XpOutputDecision.classify(
			event,
			settings.isHapticSkillEnabled(skillId),
			skillSettings,
			settings.isLevelUpFeedbackEnabled(),
			settings.isMilestoneFeedbackEnabled(),
			settings.isLevel99CelebrationEnabled(),
			settings.isClickSkillEnabled(skillId),
			settings.getClickerXpSettings(skillId)
		);
		if (decision.shouldClick())
		{
			feedback.playClick(decision.getClickSequence());
		}
		feedback.handleXp(event, decision, settings, skillSettings);
	}

	@Override
	public void onChatEvent(ChatEvent event)
	{
		Objects.requireNonNull(event, "event");
		RemoteSettingsSnapshot settings = settingsSupplier.get();
		ChatOutputDecision decision = ChatOutputDecision.classify(
			event,
			settings.getClickerPhraseRules(),
			settings.getClickerAlertSettings()
		);
		ClickerPhraseRule phrase = decision.getPhraseRule();
		if (phrase != null && !phraseCooldown.allow(phrase.getId())) return;
		if (phrase != null) alertDeduplicator.recordSpecificAlert(System.nanoTime());
		if (phrase != null && phrase.isHapticEnabled()) feedback.playPhraseHaptics(phrase);
		if (decision.hasSpecificAlert() && (phrase == null || !phrase.isHapticEnabled()))
		{
			// Chat owns the combined phrase + semantic-alert click decision so
			// one observation cannot stack two independent click sequences.
			dispatchSpecificAlert(decision.getSpecificAlert(), false);
		}
		if (decision.shouldClick())
		{
			feedback.playClick(decision.getClickSequence());
		}
	}

	@Override
	public void onVitalsEvent(VitalsChangedEvent event)
	{
		Objects.requireNonNull(event, "event");
		vitalsAlertTracker.update(
			event,
			settingsSupplier.get().getAlertTriggerSettings()
		).ifPresent(this::dispatchSpecificAlert);
	}

	@Override
	public void onInventoryEvent(InventoryChangedEvent event)
	{
		Objects.requireNonNull(event, "event");
		inventoryAlertTracker.update(event).ifPresent(this::dispatchSpecificAlert);
	}

	@Override
	public void onToxicStatusEvent(ToxicStatusChangedEvent event)
	{
		Objects.requireNonNull(event, "event");
		toxicStatusAlertTracker.update(event).ifPresent(this::dispatchSpecificAlert);
	}

	@Override
	public void onLootEvent(LootReceivedEvent event)
	{
		Objects.requireNonNull(event, "event");
		long minimumValue = settingsSupplier.get().getAlertTriggerSettings()
			.get(AlertCategory.VALUABLE_DROP);
		if (LootAlertDecision.shouldAlert(event, minimumValue))
		{
			dispatchSpecificAlert(AlertCategory.VALUABLE_DROP);
		}
	}

	@Override
	public void onPlayerDeathEvent(PlayerDeathEvent event)
	{
		Objects.requireNonNull(event, "event");
		dispatchSpecificAlert(AlertCategory.PLAYER_DEATH);
	}

	@Override
	public void onNotificationEvent(NotificationEvent event)
	{
		Objects.requireNonNull(event, "event");
		RemoteSettingsSnapshot effective = settingsSupplier.get();
		NotificationFeedbackSettings settings = effective.getNotificationFeedbackSettings();
		if (!GenericNotificationDecision.shouldDispatch(
			event,
			settings,
			effective.isGenericNotificationClickEnabled()
		))
		{
			return;
		}

		ScheduledExecutorService scheduler = alertScheduler;
		if (scheduler == null)
		{
			return;
		}
		long notificationNanos = System.nanoTime();
		try
		{
			scheduler.schedule(
				() ->
				{
					if (!alertDeduplicator.shouldSuppressGeneric(
						notificationNanos,
						System.nanoTime()
					))
					{
						feedback.dispatchGenericAlert();
					}
				},
				AlertDeduplicator.GENERIC_DELAY_MILLIS,
				TimeUnit.MILLISECONDS
			);
		}
		catch (RejectedExecutionException ignored)
		{
			// Shutdown won the race with this notification.
		}
	}

	@Override
	public void close()
	{
		ScheduledExecutorService scheduler = alertScheduler;
		alertScheduler = null;
		if (scheduler != null)
		{
			scheduler.shutdownNow();
		}
		resetSourceState();
	}

	private void dispatchSpecificAlert(AlertCategory category)
	{
		dispatchSpecificAlert(category, true);
	}

	private void dispatchSpecificAlert(AlertCategory category, boolean allowClick)
	{
		alertDeduplicator.recordSpecificAlert(System.nanoTime());
		feedback.dispatchSpecificAlert(category, allowClick);
	}
}
