package com.ashy0019.hapticscape;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ashy0019.hapticscape.remote.SettingsStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;

public class SettingsBackedHapticScapeSettingsTest
{
    @Test
    public void usesStandaloneSafeDefaultsWithoutHostConfig()
    {
        SettingsBackedHapticScapeSettings settings = new SettingsBackedHapticScapeSettings(
            new MapSettingsStore(),
            catalog()
        );

        assertEquals("ws://localhost:12345", settings.intifaceServer());
        assertEquals(50, settings.intensityPercent());
        assertEquals(500, settings.pulseDurationMillis());
        assertEquals(HapticPatternSelection.SINGLE.toConfigValue(), settings.patternPreset());
        assertEquals("", settings.skillClickProfiles());
        assertEquals("ATTACK,COOKING", settings.clickerDisabledSkills());
        assertEquals(HapticScapeSettingsSource.DEFAULT_REMOTE_RELAY_URL, settings.remoteRelayUrl());
		assertEquals("", settings.musicCaptureEndpointId());
        assertEquals("Default system output", settings.musicCaptureEndpointName());
		assertEquals("OUTPUT", settings.musicCaptureMode());
		assertEquals("", settings.musicCaptureApplicationId());
        assertTrue(settings.remoteSettingsAllowed());
        assertFalse(settings.remoteLiveHapticsAllowed());
        assertFalse(settings.remoteProtectedExitAllowed());
        assertTrue(settings.remoteActivitySharingAllowed());
        assertEquals(30_000, settings.remoteMaximumLiveDurationMillis());
    }

    @Test
    public void readsPersistedValuesAndClampsNumericRanges()
    {
        MapSettingsStore store = new MapSettingsStore();
        store.set(HapticScapeSettingKeys.INTIFACE_SERVER, "ws://127.0.0.1:9999");
        store.set(HapticScapeSettingKeys.INTENSITY_PERCENT, 120);
        store.set(HapticScapeSettingKeys.CLICKER_ENABLED, true);
        store.set(HapticScapeSettingKeys.CLICKER_DISABLED_SKILLS, "COOKING");
        store.set(HapticScapeSettingKeys.SKILL_CLICK_PROFILES, "v1|ATTACK,10,TWO,THREE,ONE");
        store.set(HapticScapeSettingKeys.REMOTE_RELAY_URL, "  wss://relay.example/relay  ");
        store.set(HapticScapeSettingKeys.REMOTE_MAXIMUM_DURATION_MILLIS, 25);
		store.set(HapticScapeSettingKeys.MUSIC_CAPTURE_ENDPOINT_ID, "endpoint-2");
		store.set(HapticScapeSettingKeys.MUSIC_CAPTURE_ENDPOINT_NAME, "Music channel");
		store.set(HapticScapeSettingKeys.MUSIC_CAPTURE_MODE, "APPLICATION");
		store.set(HapticScapeSettingKeys.MUSIC_CAPTURE_APPLICATION_ID, "command:spotify.exe");
		store.set(HapticScapeSettingKeys.MUSIC_CAPTURE_APPLICATION_NAME, "Spotify");
		store.set(HapticScapeSettingKeys.REMOTE_ACTIVITY_SHARING_ALLOWED, false);

        SettingsBackedHapticScapeSettings settings = new SettingsBackedHapticScapeSettings(
            store,
            catalog()
        );

        assertEquals("ws://127.0.0.1:9999", settings.intifaceServer());
        assertEquals(100, settings.intensityPercent());
        assertTrue(settings.clickerEnabled());
        assertEquals("COOKING", settings.clickerDisabledSkills());
        assertEquals("v1|ATTACK,10,TWO,THREE,ONE", settings.skillClickProfiles());
        assertEquals("wss://relay.example/relay", settings.remoteRelayUrl());
        assertEquals(50, settings.remoteMaximumDurationMillis());
		assertEquals("endpoint-2", settings.musicCaptureEndpointId());
		assertEquals("Music channel", settings.musicCaptureEndpointName());
		assertEquals("APPLICATION", settings.musicCaptureMode());
		assertEquals("command:spotify.exe", settings.musicCaptureApplicationId());
		assertEquals("Spotify", settings.musicCaptureApplicationName());
		assertFalse(settings.remoteActivitySharingAllowed());
    }

    @Test
    public void malformedStoredValuesFallBackToDefaults()
    {
        MapSettingsStore store = new MapSettingsStore();
        store.set(HapticScapeSettingKeys.MUSIC_SENSITIVITY_PERCENT, "not-a-number");
        store.set(HapticScapeSettingKeys.REMOTE_SETTINGS_ALLOWED, "maybe");

        SettingsBackedHapticScapeSettings settings = new SettingsBackedHapticScapeSettings(
            store,
            catalog()
        );

        assertEquals(100, settings.musicSensitivityPercent());
        assertTrue(settings.remoteSettingsAllowed());
    }


    @Test
    public void legacyClickBooleansBecomeOneClickSequenceFallbacks()
    {
        MapSettingsStore store = new MapSettingsStore();
        store.set(HapticScapeSettingKeys.CLICKER_LEVEL_UP_ENABLED, false);
        store.set(HapticScapeSettingKeys.CLICKER_MILESTONE_ENABLED, true);
        store.set(HapticScapeSettingKeys.CLICKER_GENERIC_NOTIFICATION_ENABLED, true);

        SettingsBackedHapticScapeSettings settings = new SettingsBackedHapticScapeSettings(
            store,
            catalog()
        );

        assertEquals("ONE", settings.clickerXpSequence());
        assertEquals("NONE", settings.clickerLevelUpSequence());
        assertEquals("ONE", settings.clickerMilestoneSequence());
        assertEquals("ONE", settings.clickerGenericNotificationSequence());
    }

    @Test
    public void sequenceSettingsOverrideLegacyBooleanFallbacks()
    {
        MapSettingsStore store = new MapSettingsStore();
        store.set(HapticScapeSettingKeys.CLICKER_LEVEL_UP_ENABLED, false);
        store.set(HapticScapeSettingKeys.CLICKER_LEVEL_UP_SEQUENCE, "THREE");

        SettingsBackedHapticScapeSettings settings = new SettingsBackedHapticScapeSettings(
            store,
            catalog()
        );

        assertEquals("THREE", settings.clickerLevelUpSequence());
    }

    private static SkillCatalog catalog()
    {
        return new SkillCatalog(Arrays.asList(
            new SkillDescriptor("attack", "Attack"),
            new SkillDescriptor("cooking", "Cooking")
        ));
    }

    private static final class MapSettingsStore implements SettingsStore
    {
        private final Map<String, String> values = new HashMap<>();

        @Override
        public String get(String key)
        {
            return values.get(key);
        }

        @Override
        public void set(String key, Object value)
        {
            values.put(key, String.valueOf(value));
        }
    }
}
