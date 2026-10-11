package com.ashy0019.hapticscape;

import com.ashy0019.hapticscape.clicker.ClickSequence;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRule;
import com.ashy0019.hapticscape.clicker.ClickerSettings;
import com.ashy0019.hapticscape.clicker.ClickerService;
import com.ashy0019.hapticscape.event.XpEvent;
import com.ashy0019.hapticscape.host.DesktopNotificationService;
import com.ashy0019.hapticscape.host.SourceMessageService;
import com.ashy0019.hapticscape.device.GatedIntifaceService;
import com.ashy0019.hapticscape.device.HapticEventType;
import com.ashy0019.hapticscape.device.HapticPattern;
import com.ashy0019.hapticscape.device.HapticRequest;
import com.ashy0019.hapticscape.music.MusicSyncService;
import com.ashy0019.hapticscape.remote.RemoteActionExecutor;
import com.ashy0019.hapticscape.remote.RemoteRole;
import com.ashy0019.hapticscape.remote.RemoteSessionSnapshot;
import com.ashy0019.hapticscape.remote.RemoteSessionState;
import com.ashy0019.hapticscape.remote.RemoteSettingsSnapshot;
import com.ashy0019.hapticscape.rogue.RogueFeedbackEvent;
import com.ashy0019.hapticscape.rogue.feedback.CasinoFeedbackMapper;
import java.time.Duration;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/** Resolves settings into local click, haptic, music, and remote feedback output. */
@Slf4j
final class FeedbackCoordinator implements GameplayEventCoordinator.FeedbackSink
{
	private static final Duration REMOTE_LIVE_RELEASE_DURATION = Duration.ofMillis(300);

	private final GatedIntifaceService haptics;
	private final ClickerService clicks;
	private final MusicSyncService music;
	private final Supplier<RemoteSettingsSnapshot> settingsSupplier;
	private final Supplier<ClickerSettings> localClickerSettingsSupplier;
	private final BiConsumer<String, Boolean> level99Starter;
	private final DesktopNotificationService desktopNotifications;
	private final SourceMessageService sourceMessages;
	private volatile boolean outputPaused;

	FeedbackCoordinator(
		GatedIntifaceService haptics,
		ClickerService clicks,
		MusicSyncService music,
		Supplier<RemoteSettingsSnapshot> settingsSupplier,
		Supplier<ClickerSettings> localClickerSettingsSupplier,
		BiConsumer<String, Boolean> level99Starter,
		DesktopNotificationService desktopNotifications,
		SourceMessageService sourceMessages)
	{
		this.haptics = Objects.requireNonNull(haptics, "haptics");
		this.clicks = Objects.requireNonNull(clicks, "clicks");
		this.music = Objects.requireNonNull(music, "music");
		this.settingsSupplier = Objects.requireNonNull(settingsSupplier, "settingsSupplier");
		this.localClickerSettingsSupplier = Objects.requireNonNull(
			localClickerSettingsSupplier,
			"localClickerSettingsSupplier"
		);
		this.level99Starter = Objects.requireNonNull(level99Starter, "level99Starter");
		this.desktopNotifications = Objects.requireNonNull(
			desktopNotifications,
			"desktopNotifications"
		);
		this.sourceMessages = Objects.requireNonNull(sourceMessages, "sourceMessages");
	}

	@Override
	public void handleXp(
		XpEvent event,
		XpOutputDecision decision,
		RemoteSettingsSnapshot settings,
		XpFeedbackSettings skillSettings)
	{
		XpFeedbackTrigger trigger = decision.getHapticTrigger();
		if (trigger == XpFeedbackTrigger.LEVEL_99)
		{
			if (outputPaused)
			{
				return;
			}
			log.debug(
				"Level 99 ceremony for {}: level {} -> {}",
				event.getSkillId(),
				event.getPreviousLevel(),
				event.getCurrentLevel()
			);
			level99Starter.accept(event.getSkillId(), true);
			return;
		}

		HapticPatternSelection preset;
		switch (trigger)
		{
			case XP_GAIN:
				preset = skillSettings.getPatternSelection();
				break;
			case LEVEL_UP:
				preset = settings.getLevelUpPatternPreset();
				break;
			case MILESTONE:
				preset = settings.getMilestonePatternPreset();
				break;
			case NONE:
			case LEVEL_99:
			default:
				return;
		}

		log.debug(
			"{} feedback for {}: {} XP, level {} -> {}",
			trigger,
			event.getSkillId(),
			event.getGainedXp(),
			event.getPreviousLevel(),
			event.getCurrentLevel()
		);
		if (trigger == XpFeedbackTrigger.XP_GAIN)
		{
			sendPattern(
				HapticEventType.XP_GAIN,
				preset,
				trigger.name(),
				skillSettings.getIntensityPercent(),
				skillSettings.getDurationMillis()
			);
		}
		else
		{
			sendConfiguredPattern(
				trigger == XpFeedbackTrigger.MILESTONE
					? HapticEventType.MILESTONE
					: HapticEventType.LEVEL_UP,
				preset,
				trigger.name()
			);
		}
	}

	@Override
	public void dispatchSpecificAlert(AlertCategory category, boolean allowClick)
	{
		RemoteSettingsSnapshot effective = settingsSupplier.get();
		ClickSequence clickSequence = effective.getAlertClickSequence(category);
		if (allowClick && clickSequence.isEnabled())
		{
			log.debug("{} click sequence requested: {}", category, clickSequence);
			clicks.click(clickSequence);
		}
		effective.getAlertProfiles()
			.resolve(category, effective.getNotificationFeedbackSettings())
			.ifPresent(playback ->
			{
				log.debug("{} haptic requested", category);
				sendPattern(
					hapticEventType(category),
					playback.getPatternSelection(),
					"ALERT_" + category.name(),
					playback.getIntensityPercent(),
					playback.getDurationMillis()
				);
			});
	}

	@Override
	public void playPhraseHaptics(ClickerPhraseRule rule)
	{
		sendPattern(HapticEventType.PHRASE_TRIGGER, rule.getHapticPattern(), "CHAT_TRIGGER",
			rule.getIntensityPercent(), rule.getDurationMillis());
	}

	@Override
	public void dispatchGenericAlert()
	{
		RemoteSettingsSnapshot effective = settingsSupplier.get();
		NotificationFeedbackSettings settings = effective.getNotificationFeedbackSettings();
		ClickSequence clickSequence = effective.getGenericNotificationClickSequence();
		if (clickSequence.isEnabled())
		{
			log.debug("Generic notification click sequence requested: {}", clickSequence);
			clicks.click(clickSequence);
		}
		if (!settings.isEnabled())
		{
			return;
		}
		log.debug("Generic notification haptic requested");
		sendPattern(
			HapticEventType.GENERIC_NOTIFICATION,
			settings.getPatternSelection(),
			"ALERT_GENERIC_NOTIFICATION",
			settings.getIntensityPercent(),
			settings.getDurationMillis()
		);
	}

	@Override
	public void playClick(ClickSequence sequence)
	{
		clicks.click(sequence);
	}

	void sendConfiguredPattern(
		HapticEventType eventType,
		HapticPatternSelection preset,
		String triggerName)
	{
		XpFeedbackSettings global = settingsSupplier.get().getGlobalXpFeedbackSettings();
		sendPattern(
			eventType,
			preset,
			triggerName,
			global.getIntensityPercent(),
			global.getDurationMillis()
		);
	}

	void sendPattern(
		HapticEventType eventType,
		HapticPatternSelection preset,
		String triggerName,
		int intensityPercent,
		int durationMillis)
	{
		double intensity = intensityPercent / 100.0;
		Duration duration = Duration.ofMillis(durationMillis);
		HapticPattern pattern = preset.createPattern(
			settingsSupplier.get().getCustomPatterns(),
			intensity,
			duration
		);
		long playbackDurationMillis = pattern.getSteps().stream()
			.map(HapticPattern.Step::getDuration)
			.mapToLong(Duration::toMillis)
			.sum();
		log.debug(
			"Requesting {} pattern for {} at {}% for {} ms",
			preset,
			triggerName,
			intensityPercent,
			playbackDurationMillis
		);
		haptics.play(new HapticRequest(eventType, pattern));
	}

	void previewCustomPattern(CustomPatternEntry pattern)
	{
		log.debug(
			"Previewing unsaved Pattern Forge curve as {} beats of {} ms",
			pattern.getBeatCount(),
			pattern.getBeatDurationMillis()
		);
		haptics.play(new HapticRequest(
			HapticEventType.MANUAL_PREVIEW,
			pattern.createPattern(1.0)
		));
	}

	void dispatchRogueFeedback(RogueFeedbackEvent event)
	{
		if (event == null)
		{
			return;
		}
		if (event.shouldClick())
		{
			clicks.click();
		}
		double scale = settingsSupplier.get().getGlobalXpFeedbackSettings()
			.getIntensityPercent() / 100.0;
		haptics.play(CasinoFeedbackMapper.toRequest(event, scale));
	}

	RemoteActionExecutor createRemoteActionExecutor()
	{
		return new RemoteActionExecutor()
		{
			@Override
			public void playHaptic(
				String patternSelection,
				int intensityPercent,
				int durationMillis)
			{
				playRemoteHaptic(patternSelection, intensityPercent, durationMillis);
			}

			@Override
			public void setRemoteLiveIntensity(int intensityPercent)
			{
				haptics.setRemoteLiveIntensity(intensityPercent / 100.0);
			}

			@Override
			public void releaseRemoteLiveHaptic()
			{
				haptics.releaseRemoteLiveOutput(REMOTE_LIVE_RELEASE_DURATION);
			}

			@Override
			public void stopRemoteLiveHaptic()
			{
				haptics.stopRemoteLiveOutput();
			}

			@Override
			public void playClick()
			{
				clicks.click();
			}

			@Override
			public void showMessage(
				String message,
				boolean desktopNotification,
				boolean localChatboxMessage)
			{
				String formattedMessage = "HapticScape Remote: " + message;
				if (desktopNotification)
				{
					desktopNotifications.notify(formattedMessage);
				}
				if (localChatboxMessage)
				{
					sourceMessages.post(formattedMessage);
				}
			}

			@Override
			public void stopRemoteOutput()
			{
				haptics.stopAll();
				haptics.stopLiveOutput();
			}
		};
	}

	void handleRemoteSessionChanged(RemoteSessionSnapshot snapshot)
	{
		boolean pauseOutput = isRemoteOutputPaused(snapshot);
		outputPaused = pauseOutput;
		haptics.setOutputPaused(pauseOutput);
		clicks.setPaused(pauseOutput);
		if (!pauseOutput)
		{
			clicks.updateSettings(localClickerSettingsSupplier.get());
		}
		if (pauseOutput)
		{
			music.stopNow();
		}
		else
		{
			music.updateSettings(settingsSupplier.get().getMusicSyncSettings());
		}
	}

	void handleRemoteSettingsChanged(
		RemoteSettingsSnapshot settings,
		RemoteSessionSnapshot session)
	{
		if (session.getRole() != RemoteRole.PARTICIPANT)
		{
			return;
		}
		// Click enablement and volume are participant-local authority and are
		// updated directly by the local settings UI, never by Remote Settings.
		if (!isRemoteOutputPaused(session))
		{
			music.updateSettings(settings.getMusicSyncSettings());
		}
	}

	void stopAll()
	{
		haptics.stopAll();
	}

	private void playRemoteHaptic(
		String patternValue,
		int intensityPercent,
		int durationMillis)
	{
		CustomPatternLibrary patterns = settingsSupplier.get().getCustomPatterns();
		HapticPatternSelection selection = HapticPatternSelection
			.fromConfigValue(patternValue)
			.resolveAgainst(patterns);
		double intensity = intensityPercent / 100.0;
		Duration duration = Duration.ofMillis(durationMillis);
		HapticPattern pattern;
		if (selection.isCustom())
		{
			pattern = patterns.findById(selection.getCustomPatternId())
				.map(entry -> entry.getPattern().createPattern(intensity, duration))
				.orElseGet(() -> HapticPattern.single(intensity, duration));
		}
		else
		{
			pattern = selection.createPattern(patterns, intensity, duration);
		}
		haptics.play(new HapticRequest(HapticEventType.REMOTE_ACTION, pattern));
	}

	static boolean isRemoteOutputPaused(RemoteSessionSnapshot snapshot)
	{
		return snapshot.getRole() == RemoteRole.PARTICIPANT
			&& snapshot.getState() != RemoteSessionState.ACTIVE
			&& snapshot.getState() != RemoteSessionState.LOCAL;
	}

	private static HapticEventType hapticEventType(AlertCategory category)
	{
		switch (category)
		{
			case DIRECT_MESSAGE:
				return HapticEventType.DIRECT_MESSAGE;
			case TRADE_REQUEST:
				return HapticEventType.TRADE_REQUEST;
			case LOW_HITPOINTS:
				return HapticEventType.LOW_HITPOINTS;
			case LOW_PRAYER:
				return HapticEventType.LOW_PRAYER;
			case VALUABLE_DROP:
				return HapticEventType.VALUABLE_DROP;
			case INVENTORY_FULL:
				return HapticEventType.INVENTORY_FULL;
			case POISONED_OR_VENOMED:
				return HapticEventType.POISONED_OR_VENOMED;
			case SPECIAL_ATTACK_READY:
				return HapticEventType.SPECIAL_ATTACK_READY;
			case PLAYER_DEATH:
				return HapticEventType.PLAYER_DEATH;
			default:
				throw new IllegalArgumentException("Unknown alert category: " + category);
		}
	}
}
