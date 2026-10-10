package com.ashy0019.hapticscape;

import com.ashy0019.hapticscape.audio.HapticScapeSound;
import com.ashy0019.hapticscape.clicker.ClickSequence;
import com.ashy0019.hapticscape.clicker.ClickerService;
import com.ashy0019.hapticscape.clicker.ClickerSettings;
import com.ashy0019.hapticscape.clicker.SoundPlayerClickPlayback;
import com.ashy0019.hapticscape.device.DefaultIntifaceService;
import com.ashy0019.hapticscape.device.GatedIntifaceService;
import com.ashy0019.hapticscape.device.HapticEventType;
import com.ashy0019.hapticscape.device.HapticRequest;
import com.ashy0019.hapticscape.music.MusicResponse;
import com.ashy0019.hapticscape.music.AudioCaptureEndpoint;
import com.ashy0019.hapticscape.music.MusicSyncService;
import com.ashy0019.hapticscape.music.MusicSyncSettings;
import com.ashy0019.hapticscape.protocol.LocalhostGameplayEventServer;
import com.ashy0019.hapticscape.protocol.TransportWireCodec;
import com.ashy0019.hapticscape.remote.DiscordCredentialStore;
import com.ashy0019.hapticscape.remote.DiscordPairingBridge;
import com.ashy0019.hapticscape.remote.EffectiveSettingsService;
import com.ashy0019.hapticscape.remote.RemoteActivityPublisher;
import com.ashy0019.hapticscape.remote.RemoteClientIdentity;
import com.ashy0019.hapticscape.remote.RemotePairingService;
import com.ashy0019.hapticscape.remote.RemoteSessionListener;
import com.ashy0019.hapticscape.remote.RemoteSessionManager;
import com.ashy0019.hapticscape.remote.RemoteSessionSnapshot;
import com.ashy0019.hapticscape.remote.RemoteSettingsSnapshot;
import com.ashy0019.hapticscape.remote.SavedUnlockKeyStore;
import com.ashy0019.hapticscape.remote.SettingsBackedRemotePermissionsStore;
import com.ashy0019.hapticscape.remote.SettingsBackedRemoteSettingsStore;
import com.ashy0019.hapticscape.remote.SettingsLockCatalog;
import com.ashy0019.hapticscape.remote.SettingsLockService;
import com.ashy0019.hapticscape.rogue.RogueFeedbackEvent;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns the source-neutral HapticScape runtime graph.
 *
 * <p>The standalone desktop host composes the runtime by supplying platform
 * services through {@link HapticScapeRuntimeDependencies}. Gameplay enters
 * through the local transport server rather than through source-specific
 * calls.</p>
 */
@Slf4j
public final class HapticScapeRuntime implements AutoCloseable
{
    private static final float LEVEL_99_CHEER_GAIN_DB = -4.0f;
    private static final float ROGUE_UNLOCK_STING_GAIN_DB = -4.0f;

    private final HapticScapeRuntimeDependencies dependencies;
    private final Level99CelebrationController level99CelebrationController =
        new Level99CelebrationController();

    private GatedIntifaceService intifaceService;
    private EffectiveSettingsService effectiveSettingsService;
    private SettingsLockService settingsLockService;
    private MusicSyncService musicSyncService;
    private ClickerService clickerService;
    private FeedbackCoordinator feedbackCoordinator;
    private RemoteSessionManager remoteSessionManager;
    private GameplayEventCoordinator gameplayEvents;
    private LocalhostGameplayEventServer gameplayTransportServer;
    private RemotePairingService remotePairingService;
    private DiscordPairingBridge discordPairingBridge;
    private boolean started;

    public HapticScapeRuntime(HapticScapeRuntimeDependencies dependencies)
    {
        this.dependencies = Objects.requireNonNull(dependencies, "dependencies");
    }

    public synchronized void start()
    {
        if (started)
        {
            return;
        }

        try
        {
            level99CelebrationController.reset();
            SettingsLockCatalog.registerSkills(dependencies.getSkillCatalog().getSkills());

            intifaceService = new GatedIntifaceService(
                new DefaultIntifaceService(
                    dependencies.getHttpClient(),
                    dependencies.getGson()
                )
            );
            effectiveSettingsService = new EffectiveSettingsService(dependencies.getSettings());
            settingsLockService = new SettingsLockService(
                dependencies.getGson(),
                dependencies.getStoragePaths()
            );
            musicSyncService = new MusicSyncService(
                intifaceService,
                dependencies.getAudioCaptureSourceFactory(),
				dependencies.getInitialAudioCaptureMode(),
                dependencies.getInitialAudioCaptureEndpoint(),
				dependencies.getInitialAudioCaptureApplication(),
                musicSettingsFromSettings(dependencies.getSettings())
            );
            clickerService = new ClickerService(
                new SoundPlayerClickPlayback(dependencies.getSoundPlayer()),
                clickerSettingsFromSettings(dependencies.getSettings())
            );
            feedbackCoordinator = new FeedbackCoordinator(
                intifaceService,
                clickerService,
                musicSyncService,
                effectiveSettingsService::current,
                () -> clickerSettingsFromSettings(dependencies.getSettings()),
                this::startLevel99Ceremony,
                dependencies.getDesktopNotifications(),
                dependencies.getSourceMessages()
            );

            SavedUnlockKeyStore savedUnlockKeyStore = new SavedUnlockKeyStore(
                dependencies.getGson(),
                dependencies.getStoragePaths(),
                dependencies.getSavedUnlockKeyProtector()
            );
            remoteSessionManager = new RemoteSessionManager(
                dependencies.getHttpClient(),
                dependencies.getGson(),
                new SettingsBackedRemoteSettingsStore(
                    dependencies.getSettings(),
                    dependencies.getSettingsStore()
                ),
                effectiveSettingsService,
                settingsLockService,
                savedUnlockKeyStore,
                new SettingsBackedRemotePermissionsStore(
                    dependencies.getSettings(),
                    dependencies.getSettingsStore()
                ),
                feedbackCoordinator.createRemoteActionExecutor(),
                new RemoteClientIdentity(dependencies.getStoragePaths()).getId()
            );
            remoteSessionManager.addListener(new RemoteSessionListener()
            {
                @Override
                public void onRemoteSessionChanged(RemoteSessionSnapshot snapshot)
                {
                    feedbackCoordinator.handleRemoteSessionChanged(snapshot);
                }

                @Override
                public void onRemoteSettingsChanged(RemoteSettingsSnapshot settings)
                {
                    feedbackCoordinator.handleRemoteSettingsChanged(
                        settings,
                        remoteSessionManager.getSnapshot()
                    );
                }
            });

            gameplayEvents = new GameplayEventCoordinator(
                effectiveSettingsService::current,
                feedbackCoordinator
            );
            gameplayEvents.start();
            RemoteActivityPublisher remoteActivityPublisher = new RemoteActivityPublisher(
                remoteSessionManager::publishGameplayActivity
            );
            gameplayTransportServer = new LocalhostGameplayEventServer(
                new TransportWireCodec(dependencies.getGson()),
                new ResetAwareGameplayEventSink(
                    new CompositeGameplayEventSink(gameplayEvents, remoteActivityPublisher),
                    level99CelebrationController::reset
                ),
                dependencies.getGameplayPort()
            );

            remotePairingService = new RemotePairingService(dependencies.getHttpClient());
            discordPairingBridge = new DiscordPairingBridge(
                dependencies.getHttpClient(),
                dependencies.getGson(),
                remoteSessionManager,
                remotePairingService,
                new DiscordCredentialStore(
                    dependencies.getGson(),
                    dependencies.getStoragePaths(),
                    dependencies.getDiscordCredentialProtector()
                )
            );
            discordPairingBridge.start();
            musicSyncService.updateSettings(effectiveSettingsService.current().getMusicSyncSettings());
            started = true;
        }
        catch (RuntimeException failure)
        {
            close();
            throw failure;
        }
    }

    public synchronized boolean isStarted()
    {
        return started;
    }

    public int getGameplayTransportPort()
    {
        ensureStarted();
        return gameplayTransportServer.getPort();
    }

    public Level99CelebrationController getLevel99CelebrationController()
    {
        return level99CelebrationController;
    }

    public GatedIntifaceService getIntifaceService()
    {
        ensureStarted();
        return intifaceService;
    }

    public MusicSyncService getMusicSyncService()
    {
        ensureStarted();
        return musicSyncService;
    }

    public List<AudioCaptureEndpoint> listAudioCaptureEndpoints()
    {
        ensureStarted();
        return dependencies.getAudioCaptureEndpointCatalog().listActiveEndpoints();
    }

	public List<com.ashy0019.hapticscape.music.AudioCaptureApplication>
		listAudioCaptureApplications()
	{
		ensureStarted();
		return dependencies.getAudioCaptureApplicationCatalog().listActiveApplications();
	}

    public ClickerService getClickerService()
    {
        ensureStarted();
        return clickerService;
    }

    public RemoteSessionManager getRemoteSessionManager()
    {
        ensureStarted();
        return remoteSessionManager;
    }

    public RemotePairingService getRemotePairingService()
    {
        ensureStarted();
        return remotePairingService;
    }

    public DiscordPairingBridge getDiscordPairingBridge()
    {
        ensureStarted();
        return discordPairingBridge;
    }

    public SettingsLockService getSettingsLockService()
    {
        ensureStarted();
        return settingsLockService;
    }

    public RemoteSettingsSnapshot effectiveSettings()
    {
        ensureStarted();
        return effectiveSettingsService.current();
    }

    public void connectToIntiface() throws URISyntaxException
    {
        ensureStarted();
        String configuredServer = dependencies.getSettings().intifaceServer().trim();
        URI serverUri = new URI(configuredServer);
        String scheme = serverUri.getScheme();
        if (serverUri.getHost() == null
            || !("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme)))
        {
            throw new URISyntaxException(configuredServer, "Expected a ws:// or wss:// server URI");
        }
        intifaceService.connect(serverUri);
    }

    public void previewCustomPattern(CustomPatternEntry pattern)
    {
        ensureStarted();
        feedbackCoordinator.previewCustomPattern(pattern);
    }

    public void dispatchRogueFeedback(RogueFeedbackEvent event)
    {
        ensureStarted();
        feedbackCoordinator.dispatchRogueFeedback(event);
    }

    public void sendConfiguredPattern(
        HapticEventType eventType,
        HapticPatternSelection preset,
        String triggerName)
    {
        ensureStarted();
        feedbackCoordinator.sendConfiguredPattern(eventType, preset, triggerName);
    }

    public void sendPattern(
        HapticEventType eventType,
        HapticPatternSelection preset,
        String triggerName,
        int intensityPercent,
        int durationMillis)
    {
        ensureStarted();
        feedbackCoordinator.sendPattern(
            eventType,
            preset,
            triggerName,
            intensityPercent,
            durationMillis
        );
    }

    public void playClick()
    {
        playClick(ClickSequence.ONE);
    }

    public void playClick(ClickSequence sequence)
    {
        ensureStarted();
        clickerService.click(sequence);
    }

    public void stopAll()
    {
        if (feedbackCoordinator != null)
        {
            feedbackCoordinator.stopAll();
        }
    }

    public void startLevel99Ceremony(String skillId, boolean announceInSourceMessages)
    {
        ensureStarted();
        level99CelebrationController.start(
            dependencies.getSkillCatalog().require(skillId)
        );
        CompletableFuture.runAsync(this::playLevel99Cheer);
        if (announceInSourceMessages)
        {
            dependencies.getSourceMessages().postColored(
                Level99Ceremony.CHAT_MESSAGE,
                0xFFAE00
            );
        }
        intifaceService.play(new HapticRequest(
            HapticEventType.LEVEL_99,
            Level99Ceremony.pattern()
        ));
    }

    public void playRogueUnlockStingAsync()
    {
        ensureStarted();
        CompletableFuture.runAsync(this::playRogueUnlockSting);
    }

    private void playLevel99Cheer()
    {
        playSound(HapticScapeSound.LEVEL_99_CHEER, LEVEL_99_CHEER_GAIN_DB, "Level 99 cheer");
    }

    private void playRogueUnlockSting()
    {
        playSound(
            HapticScapeSound.ROGUE_UNLOCK_STING,
            ROGUE_UNLOCK_STING_GAIN_DB,
            "Rogue Mode unlock sting"
        );
    }

    private void playSound(HapticScapeSound sound, float gainDb, String description)
    {
        try
        {
            dependencies.getSoundPlayer().play(sound, gainDb);
        }
        catch (Exception failure)
        {
            log.warn("Unable to play {}", description, failure);
        }
    }

    private static MusicSyncSettings musicSettingsFromSettings(HapticScapeSettingsSource settings)
    {
        MusicResponse response;
        try
        {
            response = MusicResponse.valueOf(settings.musicResponse());
        }
        catch (IllegalArgumentException | NullPointerException ignored)
        {
            response = MusicResponse.RHYTHMIC;
        }
        int maximum = clamp(settings.musicMaximumIntensityPercent(), 0, 100);
        int minimum = Math.min(
            clamp(settings.musicMinimumIntensityPercent(), 0, 100),
            maximum
        );
        return new MusicSyncSettings(
            settings.musicSyncEnabled(),
            response,
            clamp(settings.musicSensitivityPercent(), 25, 200),
            minimum,
            maximum
        );
    }

    private static ClickerSettings clickerSettingsFromSettings(HapticScapeSettingsSource settings)
    {
        return new ClickerSettings(
            settings.clickerEnabled(),
            clamp(
                settings.clickerVolumePercent(),
                ClickerSettings.MINIMUM_VOLUME_PERCENT,
                ClickerSettings.MAXIMUM_VOLUME_PERCENT
            )
        );
    }

    private static int clamp(int value, int minimum, int maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private void ensureStarted()
    {
        if (!started)
        {
            throw new IllegalStateException("HapticScape runtime is not started");
        }
    }

    @Override
    public synchronized void close()
    {
        started = false;
        level99CelebrationController.reset();

        if (gameplayTransportServer != null)
        {
            gameplayTransportServer.close();
            gameplayTransportServer = null;
        }
        if (gameplayEvents != null)
        {
            gameplayEvents.close();
            gameplayEvents = null;
        }
        if (discordPairingBridge != null)
        {
            discordPairingBridge.setJoinConsentHandler(null);
            discordPairingBridge.close();
            discordPairingBridge = null;
        }
        if (remoteSessionManager != null)
        {
            remoteSessionManager.close();
            remoteSessionManager = null;
        }
        feedbackCoordinator = null;
        if (musicSyncService != null)
        {
            musicSyncService.setListener(snapshot -> { });
            musicSyncService.close();
            musicSyncService = null;
        }
        effectiveSettingsService = null;
        if (clickerService != null)
        {
            clickerService.close();
            clickerService = null;
        }
        if (intifaceService != null)
        {
            intifaceService.setConnectionListener(snapshot -> { });
            intifaceService.close();
            intifaceService = null;
        }
        settingsLockService = null;
        remotePairingService = null;
    }
}
