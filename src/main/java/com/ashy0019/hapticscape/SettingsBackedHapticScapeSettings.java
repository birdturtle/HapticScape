package com.ashy0019.hapticscape;

import com.ashy0019.hapticscape.clicker.ClickSequence;
import com.ashy0019.hapticscape.remote.SettingsStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Host-neutral HapticScape settings view backed by the generic SettingsStore.
 *
 * <p>This is the settings facade used by the standalone runtime. Host-specific
 * settings APIs remain outside the runtime boundary.</p>
 */
public final class SettingsBackedHapticScapeSettings implements HapticScapeSettingsSource
{
    private final SettingsStore store;
    private final List<String> skillIds;

    public SettingsBackedHapticScapeSettings(SettingsStore store, SkillCatalog skillCatalog)
    {
        this.store = Objects.requireNonNull(store, "store");
        Objects.requireNonNull(skillCatalog, "skillCatalog");
        List<String> ids = new ArrayList<>();
        for (SkillDescriptor skill : skillCatalog.getSkills())
        {
            ids.add(skill.getId());
        }
        this.skillIds = ids;
    }

    @Override
    public String intifaceServer()
    {
        return stringValue(HapticScapeSettingKeys.INTIFACE_SERVER, "ws://localhost:12345");
    }

    @Override
    public int minimumXpGain()
    {
        return intValue(HapticScapeSettingKeys.MINIMUM_XP_GAIN, 1, 1, 200_000_000);
    }

    @Override
    public int intensityPercent()
    {
        return intValue(HapticScapeSettingKeys.INTENSITY_PERCENT, 50, 0, 100);
    }

    @Override
    public int pulseDurationMillis()
    {
        return intValue(HapticScapeSettingKeys.PULSE_DURATION_MILLIS, 500, 50, 10_000);
    }

    @Override
    public String patternPreset()
    {
        return stringValue(HapticScapeSettingKeys.PATTERN_PRESET, HapticPatternSelection.SINGLE.toConfigValue());
    }

    @Override
    public String disabledSkills()
    {
        return stringValue(HapticScapeSettingKeys.DISABLED_SKILLS, "");
    }

    @Override
    public boolean levelUpFeedbackEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.LEVEL_UP_FEEDBACK_ENABLED, true);
    }

    @Override
    public String levelUpPatternPreset()
    {
        return stringValue(HapticScapeSettingKeys.LEVEL_UP_PATTERN_PRESET, HapticPatternSelection.DOUBLE.toConfigValue());
    }

    @Override
    public boolean milestoneFeedbackEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.MILESTONE_FEEDBACK_ENABLED, true);
    }

    @Override
    public String milestonePatternPreset()
    {
        return stringValue(HapticScapeSettingKeys.MILESTONE_PATTERN_PRESET, HapticPatternSelection.TRIPLE.toConfigValue());
    }

    @Override
    public boolean level99CelebrationEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.LEVEL_99_CELEBRATION_ENABLED, true);
    }

    @Override
    public String skillFeedbackProfiles()
    {
        return stringValue(HapticScapeSettingKeys.SKILL_FEEDBACK_PROFILES, "");
    }

    @Override
    public String skillClickProfiles()
    {
        return stringValue(HapticScapeSettingKeys.SKILL_CLICK_PROFILES, "");
    }

    @Override
    public boolean notificationFeedbackEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.NOTIFICATION_FEEDBACK_ENABLED, false);
    }

    @Override
    public int notificationIntensityPercent()
    {
        return intValue(HapticScapeSettingKeys.NOTIFICATION_INTENSITY_PERCENT, 50, 0, 100);
    }

    @Override
    public String notificationPatternPreset()
    {
        return stringValue(HapticScapeSettingKeys.NOTIFICATION_PATTERN_PRESET, HapticPatternSelection.DOUBLE.toConfigValue());
    }

    @Override
    public int notificationDurationMillis()
    {
        return intValue(HapticScapeSettingKeys.NOTIFICATION_DURATION_MILLIS, 500, 50, 10_000);
    }

    @Override
    public boolean notificationRespectFocus()
    {
        return booleanValue(HapticScapeSettingKeys.NOTIFICATION_RESPECT_FOCUS, true);
    }

    @Override
    public String alertProfiles()
    {
        return stringValue(HapticScapeSettingKeys.ALERT_PROFILES, "");
    }

    @Override
    public String alertTriggerSettings()
    {
        return stringValue(HapticScapeSettingKeys.ALERT_TRIGGER_SETTINGS, "");
    }

    @Override
    public String customPatterns()
    {
        return stringValue(HapticScapeSettingKeys.CUSTOM_PATTERNS, "");
    }

    @Override
    public boolean musicSyncEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.MUSIC_SYNC_ENABLED, false);
    }

    @Override
    public String musicResponse()
    {
        return stringValue(HapticScapeSettingKeys.MUSIC_RESPONSE, "RHYTHMIC");
    }

    @Override
    public int musicSensitivityPercent()
    {
        return intValue(HapticScapeSettingKeys.MUSIC_SENSITIVITY_PERCENT, 100, 25, 200);
    }

    @Override
    public int musicMinimumIntensityPercent()
    {
        return intValue(HapticScapeSettingKeys.MUSIC_MINIMUM_INTENSITY_PERCENT, 0, 0, 100);
    }

    @Override
    public int musicMaximumIntensityPercent()
    {
        return intValue(HapticScapeSettingKeys.MUSIC_MAXIMUM_INTENSITY_PERCENT, 60, 0, 100);
    }

    @Override
    public String musicCaptureEndpointId()
    {
        return stringValue(HapticScapeSettingKeys.MUSIC_CAPTURE_ENDPOINT_ID, "");
    }

    @Override
    public String musicCaptureEndpointName()
    {
        return stringValue(
            HapticScapeSettingKeys.MUSIC_CAPTURE_ENDPOINT_NAME,
            "Default system output"
        );
    }

	@Override
	public String musicCaptureMode()
	{
		return stringValue(HapticScapeSettingKeys.MUSIC_CAPTURE_MODE, "OUTPUT");
	}

	@Override
	public String musicCaptureApplicationId()
	{
		return stringValue(HapticScapeSettingKeys.MUSIC_CAPTURE_APPLICATION_ID, "");
	}

	@Override
	public String musicCaptureApplicationName()
	{
		return stringValue(
			HapticScapeSettingKeys.MUSIC_CAPTURE_APPLICATION_NAME,
			"Previously selected application"
		);
	}

    @Override
    public boolean clickerEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.CLICKER_ENABLED, false);
    }

    @Override
    public int clickerVolumePercent()
    {
        return intValue(HapticScapeSettingKeys.CLICKER_VOLUME_PERCENT, 70, 0, 100);
    }

    @Override
    public int clickerMinimumXpGain()
    {
        return intValue(HapticScapeSettingKeys.CLICKER_MINIMUM_XP_GAIN, 1, 1, 200_000_000);
    }

    @Override
    public String clickerDisabledSkills()
    {
        String fallback = SkillSelection.allEnabled()
            .withAllEnabled(skillIds, false)
            .toConfigValue();
        return stringValue(HapticScapeSettingKeys.CLICKER_DISABLED_SKILLS, fallback);
    }

    @Override
    public String clickerXpSequence()
    {
        return clickSequenceValue(
            HapticScapeSettingKeys.CLICKER_XP_SEQUENCE,
            ClickSequence.ONE
        ).toConfigValue();
    }

    @Override
    public String clickerLevelUpSequence()
    {
        String configured = store.get(HapticScapeSettingKeys.CLICKER_LEVEL_UP_SEQUENCE);
        ClickSequence fallback = clickerLevelUpEnabled()
            ? ClickSequence.ONE
            : ClickSequence.NONE;
        return ClickSequence.fromConfigValue(configured, fallback).toConfigValue();
    }

    @Override
    public String clickerMilestoneSequence()
    {
        String configured = store.get(HapticScapeSettingKeys.CLICKER_MILESTONE_SEQUENCE);
        ClickSequence fallback = clickerMilestoneEnabled()
            ? ClickSequence.ONE
            : ClickSequence.NONE;
        return ClickSequence.fromConfigValue(configured, fallback).toConfigValue();
    }

    @Override
    public String clickerGenericNotificationSequence()
    {
        String configured = store.get(HapticScapeSettingKeys.CLICKER_GENERIC_NOTIFICATION_SEQUENCE);
        ClickSequence fallback = clickerGenericNotificationEnabled()
            ? ClickSequence.ONE
            : ClickSequence.NONE;
        return ClickSequence.fromConfigValue(configured, fallback).toConfigValue();
    }

    @Override
    public boolean clickerLevelUpEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.CLICKER_LEVEL_UP_ENABLED, true);
    }

    @Override
    public boolean clickerMilestoneEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.CLICKER_MILESTONE_ENABLED, true);
    }

    @Override
    public boolean clickerLevel99Enabled()
    {
        return booleanValue(HapticScapeSettingKeys.CLICKER_LEVEL_99_ENABLED, true);
    }

    @Override
    public boolean clickerGenericNotificationEnabled()
    {
        return booleanValue(HapticScapeSettingKeys.CLICKER_GENERIC_NOTIFICATION_ENABLED, false);
    }

    @Override
    public String clickerAlertSettings()
    {
        return stringValue(HapticScapeSettingKeys.CLICKER_ALERT_SETTINGS, "");
    }

    @Override
    public String clickerPhraseRules()
    {
        return stringValue(HapticScapeSettingKeys.CLICKER_PHRASE_RULES, "");
    }

    @Override
    public String remoteRelayUrl()
    {
        return HapticScapeSettingsSource.resolveRemoteRelayUrl(
            stringValue(HapticScapeSettingKeys.REMOTE_RELAY_URL, DEFAULT_REMOTE_RELAY_URL)
        );
    }

    @Override
    public boolean startWithWindows()
    {
        return booleanValue(HapticScapeSettingKeys.START_WITH_WINDOWS, false);
    }

    @Override
    public boolean startMinimized()
    {
        return booleanValue(HapticScapeSettingKeys.START_MINIMIZED, false);
    }

    @Override
    public boolean remoteSettingsAllowed()
    {
        return booleanValue(HapticScapeSettingKeys.REMOTE_SETTINGS_ALLOWED, true);
    }

    @Override
    public boolean remoteHapticsAllowed()
    {
        return booleanValue(HapticScapeSettingKeys.REMOTE_HAPTICS_ALLOWED, true);
    }

    @Override
    public boolean remoteLiveHapticsAllowed()
    {
        return booleanValue(HapticScapeSettingKeys.REMOTE_LIVE_HAPTICS_ALLOWED, false);
    }

    @Override
    public boolean remoteClicksAllowed()
    {
        return booleanValue(HapticScapeSettingKeys.REMOTE_CLICKS_ALLOWED, true);
    }

    @Override
    public boolean remoteDesktopNotificationsAllowed()
    {
        return booleanValue(HapticScapeSettingKeys.REMOTE_DESKTOP_NOTIFICATIONS_ALLOWED, true);
    }

    @Override
    public boolean remoteLocalChatboxMessagesAllowed()
    {
        return booleanValue(HapticScapeSettingKeys.REMOTE_LOCAL_CHATBOX_MESSAGES_ALLOWED, false);
    }

    @Override
    public boolean remoteProtectedExitAllowed()
    {
        return booleanValue(HapticScapeSettingKeys.REMOTE_PROTECTED_EXIT_ALLOWED, false);
    }

    @Override
    public boolean remoteActivitySharingAllowed()
    {
        return booleanValue(HapticScapeSettingKeys.REMOTE_ACTIVITY_SHARING_ALLOWED, true);
    }

    @Override
    public int remoteMaximumIntensityPercent()
    {
        return intValue(HapticScapeSettingKeys.REMOTE_MAXIMUM_INTENSITY_PERCENT, 60, 0, 100);
    }

    @Override
    public int remoteMaximumDurationMillis()
    {
        return intValue(HapticScapeSettingKeys.REMOTE_MAXIMUM_DURATION_MILLIS, 3_000, 50, 10_000);
    }

    @Override
    public int remoteMaximumLiveDurationMillis()
    {
        return intValue(HapticScapeSettingKeys.REMOTE_MAXIMUM_LIVE_DURATION_MILLIS, 30_000, 1_000, 300_000);
    }

    private String stringValue(String key, String fallback)
    {
        String value = store.get(key);
        return value == null ? fallback : value;
    }

    private ClickSequence clickSequenceValue(String key, ClickSequence fallback)
    {
        return ClickSequence.fromConfigValue(store.get(key), fallback);
    }

    private boolean booleanValue(String key, boolean fallback)
    {
        String value = store.get(key);
        if (value == null)
        {
            return fallback;
        }
        if ("true".equalsIgnoreCase(value.trim()))
        {
            return true;
        }
        if ("false".equalsIgnoreCase(value.trim()))
        {
            return false;
        }
        return fallback;
    }

    private int intValue(String key, int fallback, int minimum, int maximum)
    {
        String value = store.get(key);
        if (value == null)
        {
            return fallback;
        }
        try
        {
            int parsed = Integer.parseInt(value.trim());
            return Math.max(minimum, Math.min(maximum, parsed));
        }
        catch (NumberFormatException ignored)
        {
            return fallback;
        }
    }
}
