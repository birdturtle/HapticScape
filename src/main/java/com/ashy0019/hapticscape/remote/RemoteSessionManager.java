package com.ashy0019.hapticscape.remote;

import com.google.gson.Gson;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.OkHttpClient;

/**
 * Coordinates one consented Remote Control session.
 *
 * <p>The relay transports only encrypted application messages. The participant
 * never connects directly to the controller, and the controller never receives
 * access to the participant's Intiface connection.</p>
 */
public final class RemoteSessionManager implements AutoCloseable
{
	private static final Logger LOG = Logger.getLogger(RemoteSessionManager.class.getName());
	private static final long HOUSEKEEPING_INTERVAL_MILLIS = 100;
	private static final long SETTINGS_DEBOUNCE_MILLIS = 40;
	private static final long SETTINGS_RECONCILE_INTERVAL_MILLIS = 5_000;
	private static final long HELLO_INTERVAL_MILLIS = 1_000;
	static final long HEARTBEAT_INTERVAL_MILLIS = 1_000;
	static final long PEER_LIVENESS_TIMEOUT_MILLIS = 3_500;
	private static final long CONNECTION_ATTEMPT_TIMEOUT_MILLIS = 10_000;
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final int UNAUTHORIZED_END_DEDUPE_LIMIT = 256;

	private final Gson gson;
	private final EffectiveSettingsService effectiveSettings;
	private final SettingsLockService settingsLockService;
	private final SavedUnlockKeyStore savedUnlockKeyStore;
	private final RemoteActionCoordinator actionCoordinator;
	private final RemoteActivityCoordinator activityCoordinator;
	private final RemoteLockCoordinator lockCoordinator;
	private final RemotePermissionsCoordinator permissionsCoordinator;
	private final RemoteSettingsCoordinator settingsCoordinator;
	private final RemoteMessageRouter messageRouter;
	private final Clock clock;
	private final RemoteTransportFactory transportFactory;
	private final String localClientId;
	private final SettingsLockListener settingsLockListener = this::handleLocalSettingsLockChanged;
	private final SecureRandom random = new SecureRandom();
	private final ScheduledExecutorService scheduler;
	private final CopyOnWriteArrayList<RemoteSessionListener> listeners =
		new CopyOnWriteArrayList<>();
	private final Set<String> receivedUnauthorizedEndIds = new LinkedHashSet<>();

	private volatile RemoteSessionSnapshot snapshot = RemoteSessionSnapshot.local();
	private volatile RemoteTransport relayClient;
	private volatile RemoteRole role = RemoteRole.NONE;
	private volatile RemoteCrypto crypto;
	private volatile RemoteInvitation invitation;
	private volatile String peerClientId;
	private volatile long lastHelloNanos;
	private volatile long lastHeartbeatNanos;
	private volatile long lastPeerActivityNanos;
	private volatile long connectionAttemptStartedNanos;
	private volatile long connectionGeneration;
	private volatile boolean reconnectAttemptInFlight;
	private ScheduledFuture<?> pendingSettingsSync;
	private volatile boolean closed;

	public RemoteSessionManager(
		OkHttpClient httpClient,
		Gson gson,
		RemoteSettingsStore settingsStore,
		EffectiveSettingsService effectiveSettings,
		SettingsLockService settingsLockService,
		SavedUnlockKeyStore savedUnlockKeyStore)
	{
		this(
			httpClient,
			gson,
			settingsStore,
			effectiveSettings,
			settingsLockService,
			savedUnlockKeyStore,
			new InMemoryRemotePermissionsStore(RemotePermissions.defaults()),
			RemoteActionExecutor.NO_OP
		);
	}

	public RemoteSessionManager(
		OkHttpClient httpClient,
		Gson gson,
		RemoteSettingsStore settingsStore,
		EffectiveSettingsService effectiveSettings,
		SettingsLockService settingsLockService,
		SavedUnlockKeyStore savedUnlockKeyStore,
		RemotePermissionsStore permissionsStore,
		RemoteActionExecutor remoteActionExecutor)
	{
		this(
			gson,
			settingsStore,
			effectiveSettings,
			settingsLockService,
			Objects.requireNonNull(savedUnlockKeyStore, "savedUnlockKeyStore"),
			permissionsStore,
			remoteActionExecutor,
			Clock.systemUTC(),
			listener -> new RemoteRelayClient(
				Objects.requireNonNull(httpClient, "httpClient"),
				listener
			)
		);
	}

	public RemoteSessionManager(
		OkHttpClient httpClient,
		Gson gson,
		RemoteSettingsStore settingsStore,
		EffectiveSettingsService effectiveSettings,
		SettingsLockService settingsLockService,
		SavedUnlockKeyStore savedUnlockKeyStore,
		RemotePermissionsStore permissionsStore,
		RemoteActionExecutor remoteActionExecutor,
		String localClientId)
	{
		this(
			gson,
			settingsStore,
			effectiveSettings,
			settingsLockService,
			Objects.requireNonNull(savedUnlockKeyStore, "savedUnlockKeyStore"),
			permissionsStore,
			remoteActionExecutor,
			Clock.systemUTC(),
			listener -> new RemoteRelayClient(
				Objects.requireNonNull(httpClient, "httpClient"),
				listener
			),
			localClientId
		);
	}

	RemoteSessionManager(
		Gson gson,
		RemoteSettingsStore settingsStore,
		EffectiveSettingsService effectiveSettings,
		SettingsLockService settingsLockService,
		RemoteTransportFactory transportFactory)
	{
		this(
			gson,
			settingsStore,
			effectiveSettings,
			settingsLockService,
			SavedUnlockKeyStore.disabled(gson),
			new InMemoryRemotePermissionsStore(RemotePermissions.defaults()),
			RemoteActionExecutor.NO_OP,
			Clock.systemUTC(),
			transportFactory
		);
	}

	RemoteSessionManager(
		Gson gson,
		RemoteSettingsStore settingsStore,
		EffectiveSettingsService effectiveSettings,
		SettingsLockService settingsLockService,
		SavedUnlockKeyStore savedUnlockKeyStore,
		RemoteTransportFactory transportFactory)
	{
		this(
			gson,
			settingsStore,
			effectiveSettings,
			settingsLockService,
			savedUnlockKeyStore,
			new InMemoryRemotePermissionsStore(RemotePermissions.defaults()),
			RemoteActionExecutor.NO_OP,
			Clock.systemUTC(),
			transportFactory
		);
	}

	RemoteSessionManager(
		Gson gson,
		RemoteSettingsStore settingsStore,
		EffectiveSettingsService effectiveSettings,
		SettingsLockService settingsLockService,
		SavedUnlockKeyStore savedUnlockKeyStore,
		RemotePermissionsStore permissionsStore,
		RemoteActionExecutor remoteActionExecutor,
		Clock clock,
		RemoteTransportFactory transportFactory)
	{
		this(
			gson,
			settingsStore,
			effectiveSettings,
			settingsLockService,
			savedUnlockKeyStore,
			permissionsStore,
			remoteActionExecutor,
			clock,
			transportFactory,
			UUID.randomUUID().toString()
		);
	}

	RemoteSessionManager(
		Gson gson,
		RemoteSettingsStore settingsStore,
		EffectiveSettingsService effectiveSettings,
		SettingsLockService settingsLockService,
		SavedUnlockKeyStore savedUnlockKeyStore,
		RemotePermissionsStore permissionsStore,
		RemoteActionExecutor remoteActionExecutor,
		Clock clock,
		RemoteTransportFactory transportFactory,
		String localClientId)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
		RemoteSettingsStore requiredSettingsStore = Objects.requireNonNull(
			settingsStore,
			"settingsStore"
		);
		this.effectiveSettings = Objects.requireNonNull(effectiveSettings, "effectiveSettings");
		this.settingsLockService = Objects.requireNonNull(settingsLockService, "settingsLockService");
		SavedUnlockKeyStore requiredSavedUnlockKeyStore = Objects.requireNonNull(
			savedUnlockKeyStore,
			"savedUnlockKeyStore"
		);
		this.savedUnlockKeyStore = requiredSavedUnlockKeyStore;
		RemotePermissionsStore requiredPermissionsStore = Objects.requireNonNull(
			permissionsStore,
			"permissionsStore"
		);
		this.clock = Objects.requireNonNull(clock, "clock");
		RemoteActionExecutor actionExecutor = Objects.requireNonNull(
			remoteActionExecutor,
			"remoteActionExecutor"
		);
		this.transportFactory = Objects.requireNonNull(transportFactory, "transportFactory");
		this.localClientId = normalizeClientId(localClientId);
		this.actionCoordinator = new RemoteActionCoordinator(
			gson,
			actionExecutor,
			clock,
			this::send,
			this::publishActionAcknowledgement
		);
		this.activityCoordinator = new RemoteActivityCoordinator(
			gson,
			this::send,
			this::publishActivity
		);
		this.permissionsCoordinator = new RemotePermissionsCoordinator(
			gson,
			requiredPermissionsStore,
			this::send,
			this::publishPermissions,
			actionCoordinator::clearControllerLiveStream
		);
		this.lockCoordinator = new RemoteLockCoordinator(
			gson,
			settingsLockService,
			requiredSavedUnlockKeyStore,
			this::send,
			this::publishLockSnapshot,
			this::publishLockProposal,
			this::publishLockNamingRequired,
			() -> permissionsCoordinator.getLocal().isProtectedExitAllowed()
		);
		this.lockCoordinator.setSecretCompletion(task ->
		{
			synchronized (RemoteSessionManager.this)
			{
				if (!closed) task.run();
			}
		});
		this.settingsCoordinator = new RemoteSettingsCoordinator(
			gson,
			requiredSettingsStore,
			effectiveSettings,
			this::send,
			this::publish,
			this::publishSettings,
			this::emergencyPause
		);
		this.messageRouter = new RemoteMessageRouter(
			settingsCoordinator,
			permissionsCoordinator,
			lockCoordinator,
			actionCoordinator,
			activityCoordinator,
			new LifecycleMessages()
		);
		this.settingsLockService.addListener(settingsLockListener);
		this.scheduler = Executors.newSingleThreadScheduledExecutor(task ->
		{
			Thread thread = new Thread(task, "hapticscape-remote");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.scheduleAtFixedRate(
			this::tickSafely,
			HOUSEKEEPING_INTERVAL_MILLIS,
			HOUSEKEEPING_INTERVAL_MILLIS,
			TimeUnit.MILLISECONDS
		);
		scheduler.scheduleAtFixedRate(
			this::reconcileSettingsSafely,
			SETTINGS_RECONCILE_INTERVAL_MILLIS,
			SETTINGS_RECONCILE_INTERVAL_MILLIS,
			TimeUnit.MILLISECONDS
		);
	}

	public RemoteSessionSnapshot getSnapshot()
	{
		return snapshot;
	}

	public RemoteLockSnapshot getLockSnapshot()
	{
		return lockCoordinator.getSnapshot();
	}

	/** Returns the controller's current participant draft, if one has loaded. */
	public RemoteSettingsSnapshot getControllerSettingsSnapshot()
	{
		return settingsCoordinator.getControllerSettings();
	}

	/** Participant-owned local permissions, or the controller's read-only peer view. */
	public RemotePermissions getVisiblePermissions()
	{
		return visiblePermissions();
	}

	public RemotePermissions getPeerPermissions()
	{
		return permissionsCoordinator.getPeer();
	}

	public synchronized RemotePermissions updateLocalPermissions(RemotePermissions permissions)
	{
		RemotePermissions saved = permissionsCoordinator.updateLocal(role, permissions);
		actionCoordinator.permissionsChanged(
			saved,
			snapshot.getState() != RemoteSessionState.ACTIVE
		);
		return saved;
	}

	public synchronized String sendRemoteHaptic(
		String patternSelection,
		int intensityPercent,
		int durationMillis)
	{
		return actionCoordinator.sendHaptic(
			role,
			snapshot.getState(),
			patternSelection,
			intensityPercent,
			durationMillis
		);
	}

	public synchronized String sendRemoteClick()
	{
		return actionCoordinator.sendClick(role, snapshot.getState());
	}

	public synchronized String sendRemoteMessage(
		String message,
		boolean desktopNotification,
		boolean localChatboxMessage)
	{
		return actionCoordinator.sendMessage(
			role,
			snapshot.getState(),
			message,
			desktopNotification,
			localChatboxMessage
		);
	}

	public synchronized String stopRemoteOutput()
	{
		return actionCoordinator.stop(role, snapshot.getState());
	}

	/** Publishes one sanitized local gameplay fact when the participant allows it. */
	public synchronized boolean publishGameplayActivity(RemoteActivityEvent event)
	{
		return activityCoordinator.publish(
			role,
			snapshot.getState(),
			permissionsCoordinator.getLocal(),
			event
		);
	}

	/** Reports an end which was not authorized by the protected-exit password. */
	public synchronized boolean reportUnauthorizedEnd(String reason)
	{
		if (peerClientId == null)
		{
			return false;
		}
		return reportUnauthorizedEnd(
			UUID.randomUUID().toString(),
			peerClientId,
			clock.millis(),
			reason
		);
	}

	/** Queues a durable audit event only for the controller which owns it. */
	public synchronized boolean reportUnauthorizedEnd(
		String eventId,
		String controllerId,
		long occurredAtMillis,
		String reason)
	{
		return reportUnauthorizedEnd(eventId, controllerId, occurredAtMillis, reason, null);
	}

	public synchronized boolean reportUnauthorizedEnd(
		String eventId, String controllerId, long occurredAtMillis, String reason, String lockId)
	{
		if (role != RemoteRole.PARTICIPANT
			|| snapshot.getState() == RemoteSessionState.LOCAL
			|| snapshot.getState() == RemoteSessionState.DISCONNECTED
			|| peerClientId == null
			|| !peerClientId.equals(controllerId))
		{
			return false;
		}
		UnauthorizedEndNotice notice = new UnauthorizedEndNotice(
			eventId,
			controllerId,
			occurredAtMillis,
			reason,
			lockId
		);
		return send(RemoteMessageType.UNAUTHORIZED_END, 0, gson.toJson(notice));
	}

	public Optional<String> getPeerClientId()
	{
		return Optional.ofNullable(peerClientId);
	}

	public synchronized void beginRemoteLiveHaptic(int intensityPercent)
	{
		actionCoordinator.beginLive(
			role,
			snapshot.getState(),
			permissionsCoordinator.getPeer(),
			intensityPercent
		);
	}

	public synchronized void updateRemoteLiveHaptic(int intensityPercent)
	{
		actionCoordinator.updateLive(
			role,
			snapshot.getState(),
			permissionsCoordinator.getPeer(),
			intensityPercent
		);
	}

	public synchronized void endRemoteLiveHaptic()
	{
		actionCoordinator.endLive(role, snapshot.getState());
	}

	public void addListener(RemoteSessionListener listener)
	{
		RemoteSessionListener required = Objects.requireNonNull(listener, "listener");
		listeners.add(required);
		required.onRemoteSessionChanged(snapshot);
		required.onRemoteLockChanged(lockCoordinator.getSnapshot());
		required.onRemotePermissionsChanged(visiblePermissions());
	}

	public void removeListener(RemoteSessionListener listener)
	{
		listeners.remove(listener);
	}

	public synchronized boolean updateControllerSetting(String key, Object value)
	{
		boolean updated = settingsCoordinator.updateControllerSetting(
			role,
			snapshot.getState(),
			permissionsCoordinator.getPeer(),
			key,
			value
		);
		if (!updated)
		{
			return false;
		}

		if (pendingSettingsSync != null)
		{
			pendingSettingsSync.cancel(false);
		}
		pendingSettingsSync = scheduler.schedule(
			this::sendDebouncedSettingsSafely,
			SETTINGS_DEBOUNCE_MILLIS,
			TimeUnit.MILLISECONDS
		);
		return true;
	}

	public boolean isControllerSession()
	{
		return role == RemoteRole.CONTROLLER
			&& snapshot.getState() != RemoteSessionState.LOCAL;
	}

	public char[] generateSettingsLockKey()
	{
		return lockCoordinator.generateUnlockKey();
	}

	public List<SavedUnlockKey> getSavedUnlockKeys()
	{
		return lockCoordinator.getSavedUnlockKeys();
	}

	public boolean isSavedUnlockKeyVaultAvailable()
	{
		return lockCoordinator.isSavedUnlockKeyVaultAvailable();
	}

	public String getSavedUnlockKeyVaultMessage()
	{
		return lockCoordinator.getSavedUnlockKeyVaultMessage();
	}

	public boolean savedKeysRequireBackgroundThread() { return lockCoordinator.savedKeysRequireBackgroundThread(); }

	public char[] revealSavedUnlockKey(String id)
	{
		return lockCoordinator.revealSavedUnlockKey(id);
	}

	public SavedUnlockKey updateSavedUnlockKey(
		String id,
		String label,
		String note)
	{
		return lockCoordinator.updateSavedUnlockKey(id, label, note);
	}

	public boolean forgetSavedUnlockKey(String id)
	{
		return lockCoordinator.forgetSavedUnlockKey(id);
	}

	public synchronized void proposeSettingsLock(char[] password)
	{
		lockCoordinator.propose(role, snapshot.getState(), password);
	}

	public synchronized void proposeSettingsLock(
		char[] password,
		java.util.Collection<SettingsLockTarget> targets)
	{
		if (targets != null
			&& (targets.contains(SettingsLockCatalog.PROTECTED_EXIT)
				|| targets.contains(SettingsLockCatalog.STARTUP_BEHAVIOR))
			&& !permissionsCoordinator.getPeer().isProtectedExitAllowed())
		{
			throw new IllegalStateException(
				"The participant has not allowed protected startup/exit requests"
			);
		}
		lockCoordinator.propose(role, snapshot.getState(), password, targets);
	}

	public synchronized void cancelSettingsLock()
	{
		lockCoordinator.cancel(role);
	}

	public synchronized void acceptPendingSettingsLock()
	{
		lockCoordinator.accept(role);
	}

	public synchronized void declinePendingSettingsLock()
	{
		lockCoordinator.decline(role);
	}

	public synchronized void finalizePendingSettingsLock(String profileName)
	{
		lockCoordinator.finalizeProfile(role, profileName, peerClientId);
	}

	public synchronized RemoteInvitation startController(String relayUrl)
	{
		requireOpen();
		endSessionInternal(false, "Starting a new remote session");

		byte[] roomBytes = new byte[12];
		byte[] keyBytes = new byte[32];
		random.nextBytes(roomBytes);
		random.nextBytes(keyBytes);
		RemoteInvitation created = new RemoteInvitation(
			relayUrl,
			ENCODER.encodeToString(roomBytes),
			ENCODER.encodeToString(keyBytes)
		);
		beginSession(RemoteRole.CONTROLLER, created);
		return created;
	}

	public synchronized void joinParticipant(String encodedInvitation)
	{
		validateParticipantJoin();
		endSessionInternal(false, "Joining a new remote session");
		beginSession(RemoteRole.PARTICIPANT, RemoteInvitation.parse(encodedInvitation));
	}

	/** Validates local preconditions before a one-use connection code is redeemed. */
	public synchronized void validateParticipantJoin()
	{
		requireOpen();
	}

	public boolean isClosed()
	{
		return closed;
	}

	public synchronized void emergencyPause()
	{
		if (role != RemoteRole.PARTICIPANT
			|| snapshot.getState() == RemoteSessionState.LOCAL
			|| snapshot.getState() == RemoteSessionState.EMERGENCY_PAUSED)
		{
			return;
		}
		actionCoordinator.stopParticipantOutput();
		publish(RemoteSessionState.EMERGENCY_PAUSED, "Emergency Off active");
		send(
			RemoteMessageType.EMERGENCY_PAUSED,
			settingsCoordinator.getLastReceivedVersion(),
			""
		);
	}

	public synchronized void resumeParticipant()
	{
		if (role != RemoteRole.PARTICIPANT
			|| snapshot.getState() != RemoteSessionState.EMERGENCY_PAUSED)
		{
			return;
		}
		RemoteTransport relay = relayClient;
		if (relay == null || !relay.isOpen())
		{
			publish(
				RemoteSessionState.EMERGENCY_PAUSED,
				"Cannot resume while the remote relay is disconnected"
			);
			return;
		}
		RemoteSessionState next = effectiveSettings.isRemoteControlled()
			? RemoteSessionState.ACTIVE
			: RemoteSessionState.WAITING_FOR_SETTINGS;
		publish(next, next == RemoteSessionState.ACTIVE
			? "Remote control active"
			: "Waiting for controller settings");
		send(
			RemoteMessageType.SESSION_RESUMED,
			settingsCoordinator.getLastReceivedVersion(),
			""
		);
	}

	/** Recreates the relay transport while retaining this session's invitation and keys. */
	public synchronized boolean reconnect()
	{
		requireOpen();
		if (!canReconnect())
		{
			return false;
		}
		RemoteTransport current = relayClient;
		relayClient = null;
		connectionGeneration++;
		reconnectAttemptInFlight = false;
		connectionAttemptStartedNanos = 0;
		if (current != null)
		{
			current.close();
		}
		publish(
			snapshot.getState(),
			role == RemoteRole.PARTICIPANT
				? "Reconnecting to relay. Emergency Off remains active."
				: "Reconnecting to relay..."
		);
		connectRetainedSession(true);
		return true;
	}

	/** Whether the current retained session can be manually retried. */
	public synchronized boolean canReconnect()
	{
		if (closed || role == RemoteRole.NONE || invitation == null)
		{
			return false;
		}
		RemoteTransport relay = relayClient;
		boolean disconnectedState = role == RemoteRole.CONTROLLER
			? snapshot.getState() == RemoteSessionState.DISCONNECTED
			: snapshot.getState() == RemoteSessionState.EMERGENCY_PAUSED;
		return disconnectedState
			&& !reconnectAttemptInFlight
			&& (relay == null || !relay.isOpen());
	}

	public synchronized void endSession()
	{
		endSessionInternal(true, "Remote session ended locally");
	}

	@Override
	public synchronized void close()
	{
		if (closed)
		{
			return;
		}
		closed = true;
		endSessionInternal(true, "Remote service closed");
		scheduler.shutdownNow();
		listeners.clear();
		settingsLockService.removeListener(settingsLockListener);
	}

	private void beginSession(RemoteRole nextRole, RemoteInvitation nextInvitation)
	{
		role = nextRole;
		permissionsCoordinator.beginSession();
		actionCoordinator.reset();
		invitation = nextInvitation;
		crypto = new RemoteCrypto(nextInvitation.getKey());
		lastHelloNanos = 0;
		lastHeartbeatNanos = 0;
		lastPeerActivityNanos = 0;
		connectionAttemptStartedNanos = 0;
		reconnectAttemptInFlight = false;
		settingsCoordinator.reset();
		lockCoordinator.reset();
		if (nextRole == RemoteRole.PARTICIPANT)
		{
			effectiveSettings.clearRemote();
		}
		publish(RemoteSessionState.CONNECTING, "Connecting to remote relay");
		publishPermissions(visiblePermissions());
		connectRetainedSession(false);
	}

	private void connectRetainedSession(boolean recovery)
	{
		if (closed || role == RemoteRole.NONE || invitation == null)
		{
			return;
		}
		long generation = ++connectionGeneration;
		reconnectAttemptInFlight = true;
		connectionAttemptStartedNanos = System.nanoTime();
		RemoteTransport client;
		try
		{
			client = transportFactory.create(new RelayListener(generation, recovery));
			relayClient = client;
			client.connect(
				invitation.getRelayUrl(),
				invitation.getRoomId(),
				role,
				RemoteReconnectSlot.derive(invitation.getKey(), role)
			);
		}
		catch (RuntimeException failure)
		{
			handleConnectionLost(generation, "Unable to connect to remote relay", failure);
			throw failure;
		}
	}

	private void tickSafely()
	{
		try
		{
			tick();
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.WARNING, "Remote session tick failed", e);
		}
	}

	private synchronized void tick()
	{
		long now = System.nanoTime();
		boolean activeTransport = !closed
			&& role != RemoteRole.NONE
			&& relayClient != null
			&& relayClient.isOpen();
		actionCoordinator.tick(
			role,
			snapshot.getState(),
			permissionsCoordinator.getLocal(),
			activeTransport
		);
		if (!activeTransport)
		{
			if (reconnectAttemptInFlight
				&& connectionAttemptStartedNanos > 0
				&& now - connectionAttemptStartedNanos >= TimeUnit.MILLISECONDS.toNanos(
					CONNECTION_ATTEMPT_TIMEOUT_MILLIS
				))
			{
				handleConnectionLost(
					connectionGeneration,
					"Remote relay connection timed out",
					null
				);
			}
			return;
		}
		if (peerClientId != null && lastPeerActivityNanos > 0
			&& now - lastPeerActivityNanos
				>= TimeUnit.MILLISECONDS.toNanos(PEER_LIVENESS_TIMEOUT_MILLIS))
		{
			handleConnectionLost(
				connectionGeneration,
				"Remote peer stopped responding",
				null
			);
			return;
		}
		if (role == RemoteRole.CONTROLLER && peerClientId != null
			&& now - lastHeartbeatNanos
				>= TimeUnit.MILLISECONDS.toNanos(HEARTBEAT_INTERVAL_MILLIS))
		{
			lastHeartbeatNanos = now;
			send(RemoteMessageType.HEARTBEAT, 0, "");
		}
		if (now - lastHelloNanos >= TimeUnit.MILLISECONDS.toNanos(HELLO_INTERVAL_MILLIS)
			&& (snapshot.getState() == RemoteSessionState.WAITING_FOR_PEER
				|| snapshot.getState() == RemoteSessionState.WAITING_FOR_SETTINGS
				|| snapshot.getState() == RemoteSessionState.DISCONNECTED
				|| snapshot.getState() == RemoteSessionState.EMERGENCY_PAUSED))
		{
			lastHelloNanos = now;
			retryHandshake();
		}
		lockCoordinator.tick(role, snapshot.getState(), now);
	}

	/** Retries every idempotent frame needed to finish the initial handshake. */
	void retryHandshakeSafely()
	{
		try
		{
			synchronized (this)
			{
				if (!closed && relayClient != null && relayClient.isOpen())
				{
					retryHandshake();
				}
			}
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.WARNING, "Remote handshake retry failed", e);
		}
	}

	private void retryHandshake()
	{
		send(RemoteMessageType.HELLO, 0, gson.toJson(new RemoteHello(role, localClientId)));
		if (role == RemoteRole.CONTROLLER
			&& (snapshot.getState() == RemoteSessionState.WAITING_FOR_SETTINGS
				|| snapshot.getState() == RemoteSessionState.DISCONNECTED))
		{
			send(RemoteMessageType.SETTINGS_SEED_REQUEST, 0, "");
		}
		else if (role == RemoteRole.PARTICIPANT
			&& (snapshot.getState() == RemoteSessionState.WAITING_FOR_SETTINGS
				|| snapshot.getState() == RemoteSessionState.EMERGENCY_PAUSED))
		{
			sendParticipantHandshakeState();
		}
	}

	private void sendParticipantHandshakeState()
	{
		sendPermissions();
		sendSettingsSeed();
		if (snapshot.getState() == RemoteSessionState.EMERGENCY_PAUSED)
		{
			send(
				RemoteMessageType.EMERGENCY_PAUSED,
				settingsCoordinator.getLastReceivedVersion(),
				""
			);
		}
		else if (snapshot.getState() == RemoteSessionState.ACTIVE)
		{
			send(
				RemoteMessageType.SESSION_RESUMED,
				settingsCoordinator.getLastReceivedVersion(),
				""
			);
		}
	}

	void reconcileSettingsSafely()
	{
		try
		{
			synchronized (this)
			{
				if (canSendControllerSettings())
				{
					sendSettings(false);
				}
			}
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.WARNING, "Remote settings reconciliation failed", e);
		}
	}

	private void sendDebouncedSettingsSafely()
	{
		try
		{
			synchronized (this)
			{
				pendingSettingsSync = null;
				if (canSendControllerSettings())
				{
					sendSettings(false);
				}
			}
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.WARNING, "Immediate remote settings sync failed", e);
		}
	}

	private boolean canSendControllerSettings()
	{
		RemoteTransport relay = relayClient;
		return settingsCoordinator.canSendControllerSettings(
			closed,
			relay != null && relay.isOpen(),
			role,
			snapshot.getState(),
			permissionsCoordinator.getPeer()
		);
	}

	private synchronized void handleOpen(long generation, boolean recovery)
	{
		if (generation != connectionGeneration || closed || role == RemoteRole.NONE)
		{
			return;
		}
		reconnectAttemptInFlight = false;
		connectionAttemptStartedNanos = 0;
		if (role == RemoteRole.CONTROLLER)
		{
			if (recovery)
			{
				publish(
					RemoteSessionState.DISCONNECTED,
					"Reconnected to relay. Waiting for participant..."
				);
			}
			else
			{
				publish(RemoteSessionState.WAITING_FOR_PEER, "Waiting for participant");
			}
		}
		else if (role == RemoteRole.PARTICIPANT)
		{
			if (recovery || snapshot.getState() == RemoteSessionState.EMERGENCY_PAUSED)
			{
				publish(
					RemoteSessionState.EMERGENCY_PAUSED,
					"Reconnected. Emergency Off remains active until you resume."
				);
			}
			else
			{
				publish(
					RemoteSessionState.WAITING_FOR_SETTINGS,
					"Sending local settings to controller"
				);
			}
		}
		long now = System.nanoTime();
		lastHelloNanos = 0;
		lastHeartbeatNanos = now;
		lastPeerActivityNanos = 0;
		retryHandshake();
	}

	private synchronized void handleEncryptedMessage(long generation, String encrypted)
	{
		if (generation != connectionGeneration)
		{
			return;
		}
		RemoteCrypto currentCrypto = crypto;
		if (currentCrypto == null)
		{
			return;
		}

		RemoteProtocolMessage message;
		try
		{
			String plaintext = currentCrypto.decrypt(encrypted);
			message = gson.fromJson(plaintext, RemoteProtocolMessage.class);
			if (message == null || message.getType() == null)
			{
				return;
			}
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.FINE, "Ignoring invalid remote session message", e);
			return;
		}
		lastPeerActivityNanos = System.nanoTime();

		messageRouter.route(role, snapshot.getState(), message);
	}

	private void sendSettingsSeed()
	{
		settingsCoordinator.sendSeed(role);
	}

	private void sendPermissions()
	{
		permissionsCoordinator.send(role);
	}

	private void sendSettings(boolean force)
	{
		settingsCoordinator.sendSettings(force, role);
	}

	private boolean send(RemoteMessageType type, long version, String payload)
	{
		RemoteTransport relay = relayClient;
		RemoteCrypto currentCrypto = crypto;
		if (relay == null || currentCrypto == null || !relay.isOpen())
		{
			return false;
		}
		RemoteProtocolMessage message = new RemoteProtocolMessage(type, version, payload);
		try
		{
			return relay.send(currentCrypto.encrypt(gson.toJson(message)));
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.WARNING, "Unable to send remote session message", e);
			return false;
		}
	}

	private synchronized void handleConnectionLost(
		long generation,
		String reason,
		Throwable error)
	{
		if (generation != connectionGeneration || closed || role == RemoteRole.NONE)
		{
			return;
		}
		connectionGeneration++;
		if (error != null)
		{
			LOG.log(Level.WARNING, "Remote relay connection lost: " + reason, error);
		}
		else
		{
			LOG.info("Remote relay connection lost: " + reason);
		}

		if (role == RemoteRole.PARTICIPANT)
		{
			actionCoordinator.stopParticipantOutput();
			publish(
				RemoteSessionState.EMERGENCY_PAUSED,
				"Remote connection lost. Emergency Off active. Reconnect when ready."
			);
		}
		else
		{
			actionCoordinator.clearControllerLiveStream();
			lockCoordinator.handleControllerConnectionLost();
			publish(
				RemoteSessionState.DISCONNECTED,
				"Remote connection lost. Reconnect when ready."
			);
		}
		RemoteTransport current = relayClient;
		relayClient = null;
		reconnectAttemptInFlight = false;
		connectionAttemptStartedNanos = 0;
		if (current != null)
		{
			current.close();
		}
		lastHeartbeatNanos = 0;
		lastPeerActivityNanos = 0;
	}

	private void publish(RemoteSessionState state, String message)
	{
		long version = settingsCoordinator.sessionVersion(role);
		RemoteSessionSnapshot next = new RemoteSessionSnapshot(role, state, message, version);
		snapshot = next;
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemoteSessionChanged(next);
		}
	}

	private void publishLockSnapshot(RemoteLockSnapshot next)
	{
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemoteLockChanged(next);
		}
	}

	private void publishLockProposal(SettingsLockProposal proposal)
	{
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemoteLockProposal(proposal);
		}
	}

	private void publishLockNamingRequired(String currentName)
	{
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemoteLockNamingRequired(currentName);
		}
	}

	private void publishSettings(RemoteSettingsSnapshot settings)
	{
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemoteSettingsChanged(settings);
		}
	}

	private void publishActionAcknowledgement(
		RemoteActionAcknowledgement acknowledgement)
	{
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemoteActionAcknowledged(acknowledgement);
		}
	}

	private void publishActivity(RemoteActivityEvent event)
	{
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemoteActivity(event);
		}
	}

	private RemotePermissions visiblePermissions()
	{
		return permissionsCoordinator.getVisible(role);
	}

	private void publishPermissions(RemotePermissions permissions)
	{
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemotePermissionsChanged(permissions);
		}
	}

	private synchronized void handleLocalSettingsLockChanged(SettingsLockSnapshot locks)
	{
		lockCoordinator.handleLocalSettingsLockChanged(role, locks);
	}

	private synchronized void endSessionInternal(boolean notifyPeer, String message)
	{
		if (pendingSettingsSync != null)
		{
			pendingSettingsSync.cancel(false);
			pendingSettingsSync = null;
		}
		if (notifyPeer && role != RemoteRole.NONE)
		{
			send(RemoteMessageType.SESSION_END, 0, "");
		}
		RemoteTransport current = relayClient;
		relayClient = null;
		connectionGeneration++;
		reconnectAttemptInFlight = false;
		connectionAttemptStartedNanos = 0;
		if (current != null)
		{
			current.close();
		}
		if (role == RemoteRole.PARTICIPANT)
		{
			actionCoordinator.stopParticipantOutput();
			effectiveSettings.clearRemote();
		}
		role = RemoteRole.NONE;
		actionCoordinator.reset();
		crypto = null;
		invitation = null;
		peerClientId = null;
		settingsCoordinator.reset();
		permissionsCoordinator.endSession();
		lastHelloNanos = 0;
		lastHeartbeatNanos = 0;
		lastPeerActivityNanos = 0;
		lockCoordinator.reset();
		snapshot = new RemoteSessionSnapshot(
			RemoteRole.NONE,
			RemoteSessionState.LOCAL,
			message == null ? "Local control" : message,
			0
		);
		for (RemoteSessionListener listener : listeners)
		{
			listener.onRemoteSessionChanged(snapshot);
			listener.onRemoteSettingsChanged(effectiveSettings.current());
			listener.onRemoteLockChanged(lockCoordinator.getSnapshot());
			listener.onRemotePermissionsChanged(permissionsCoordinator.getLocal());
		}
	}

	private void requireOpen()
	{
		if (closed)
		{
			throw new IllegalStateException("Remote session service is closed");
		}
	}

	private static String normalizeClientId(String value)
	{
		String normalized = Objects.requireNonNull(value, "localClientId").trim();
		UUID.fromString(normalized);
		return normalized;
	}

	private String parsePeerClientId(String payload)
	{
		try
		{
			RemoteHello hello = gson.fromJson(payload, RemoteHello.class);
			if (hello == null || hello.getRole() == role)
			{
				return null;
			}
			return hello.getClientId();
		}
		catch (RuntimeException e)
		{
			// Older peers sent only the role name. They can still connect, but
			// controller-owned persistent profile replacement is unavailable.
			return null;
		}
	}

	private final class LifecycleMessages implements RemoteLifecycleMessageHandler
	{
		@Override
		public void handleHello(String payload)
		{
			String nextPeerClientId = parsePeerClientId(payload);
			boolean firstIdentity = nextPeerClientId != null
				&& !nextPeerClientId.equals(peerClientId);
			if (firstIdentity)
			{
				peerClientId = nextPeerClientId;
				lockCoordinator.peerIdentityChanged(role, nextPeerClientId);
			}
			if (role == RemoteRole.CONTROLLER)
			{
				if (firstIdentity)
				{
					send(RemoteMessageType.HELLO, 0, gson.toJson(new RemoteHello(role, localClientId)));
				}
				if (!settingsCoordinator.hasControllerSettings())
				{
					publish(
						RemoteSessionState.WAITING_FOR_SETTINGS,
						"Participant connected. Loading their settings..."
					);
				}
				send(RemoteMessageType.SETTINGS_SEED_REQUEST, 0, "");
			}
			else if (role == RemoteRole.PARTICIPANT)
			{
				if (firstIdentity)
				{
					send(RemoteMessageType.HELLO, 0, gson.toJson(new RemoteHello(role, localClientId)));
				}
				sendParticipantHandshakeState();
			}
		}

		@Override
		public void handleSettingsSeedRequest()
		{
			if (role == RemoteRole.PARTICIPANT)
			{
				sendParticipantHandshakeState();
			}
		}

		@Override
		public void handlePeerEmergencyPause()
		{
			if (role == RemoteRole.CONTROLLER)
			{
				actionCoordinator.clearControllerLiveStream();
				publish(
					RemoteSessionState.PEER_EMERGENCY_PAUSED,
					"Participant used Emergency Off"
				);
			}
		}

		@Override
		public void handlePeerResume()
		{
			if (role != RemoteRole.CONTROLLER)
			{
				return;
			}
			if (!settingsCoordinator.hasControllerSettings())
			{
				publish(
					RemoteSessionState.WAITING_FOR_SETTINGS,
					"Participant resumed. Loading their settings..."
				);
				send(RemoteMessageType.SETTINGS_SEED_REQUEST, 0, "");
			}
			else
			{
				publish(RemoteSessionState.ACTIVE, "Participant resumed remote control");
				sendSettings(true);
			}
		}

		@Override
		public void handlePeerEnd()
		{
			endSessionInternal(false, "Remote peer ended the session");
		}

		@Override
		public void handleHeartbeat()
		{
			send(RemoteMessageType.HEARTBEAT_ACK, 0, "");
		}

		@Override
		public void handleHeartbeatAcknowledgement()
		{
			// Receipt is recorded centrally after authenticated decryption.
		}

		@Override
		public void handleUnauthorizedEnd(String reason)
		{
			if (role != RemoteRole.CONTROLLER)
			{
				return;
			}
			UnauthorizedEndNotice notice;
			try
			{
				notice = gson.fromJson(reason, UnauthorizedEndNotice.class);
				notice.validate();
			}
			catch (RuntimeException invalidNotice)
			{
				// Preserve visibility for older clients which sent a plain reason.
				savedUnlockKeyStore.recordUnauthorizedEnd(peerClientId, clock.millis());
				publish(snapshot.getState(), "Unauthorized end flagged by participant client");
				for (RemoteSessionListener listener : listeners)
				{
					listener.onUnauthorizedEnd(reason);
				}
				return;
			}
			if (!localClientId.equals(notice.getControllerId()))
			{
				return;
			}
			// Persist before acknowledging so a failed write can be retried on reconnect.
			savedUnlockKeyStore.recordUnauthorizedEnd(peerClientId, notice.getLockId(),
				notice.getEventId(), notice.getOccurredAtMillis());
			send(RemoteMessageType.UNAUTHORIZED_END_ACK, 0, notice.getEventId());
			if (!rememberUnauthorizedEnd(notice.getEventId()))
			{
				return;
			}
			publish(snapshot.getState(), "Unauthorized end flagged by participant client");
			for (RemoteSessionListener listener : listeners)
			{
				listener.onUnauthorizedEnd(notice.getReason());
			}
		}

		@Override
		public void handleUnauthorizedEndAcknowledgement(String eventId)
		{
			if (role != RemoteRole.PARTICIPANT)
			{
				return;
			}
			try
			{
				UUID.fromString(eventId);
			}
			catch (RuntimeException invalidId)
			{
				return;
			}
			for (RemoteSessionListener listener : listeners)
			{
				listener.onUnauthorizedEndAcknowledged(eventId);
			}
		}

		@Override
		public void publishStatus(String message)
		{
			publish(snapshot.getState(), message);
		}
	}

	private boolean rememberUnauthorizedEnd(String eventId)
	{
		if (!receivedUnauthorizedEndIds.add(eventId))
		{
			return false;
		}
		while (receivedUnauthorizedEndIds.size() > UNAUTHORIZED_END_DEDUPE_LIMIT)
		{
			String oldest = receivedUnauthorizedEndIds.iterator().next();
			receivedUnauthorizedEndIds.remove(oldest);
		}
		return true;
	}

	private final class RelayListener implements RemoteTransport.Listener
	{
		private final long generation;
		private final boolean recovery;

		private RelayListener(long generation, boolean recovery)
		{
			this.generation = generation;
			this.recovery = recovery;
		}

		@Override
		public void onOpen()
		{
			handleOpen(generation, recovery);
		}

		@Override
		public void onMessage(String message)
		{
			handleEncryptedMessage(generation, message);
		}

		@Override
		public void onClosed(String reason)
		{
			handleConnectionLost(generation, reason, null);
		}

		@Override
		public void onFailure(String message, Throwable error)
		{
			handleConnectionLost(generation, message, error);
		}
	}
}
