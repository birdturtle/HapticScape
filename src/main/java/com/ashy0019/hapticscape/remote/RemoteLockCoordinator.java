package com.ashy0019.hapticscape.remote;

import com.google.gson.Gson;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Owns the complete post-session settings-lock protocol conversation. */
final class RemoteLockCoordinator
{
	private static final Logger LOG = Logger.getLogger(RemoteLockCoordinator.class.getName());
	private static final long PROPOSAL_RETRY_NANOS = 2_000_000_000L;

	private final Gson gson;
	private final SettingsLockService settingsLockService;
	private final SavedUnlockKeyStore savedUnlockKeyStore;
	private final RemoteMessageSender sender;
	private final Consumer<RemoteLockSnapshot> snapshotPublisher;
	private final Consumer<SettingsLockProposal> proposalPublisher;
	private final Consumer<String> namingPublisher;
	private final BooleanSupplier protectedExitAllowed;
	private Consumer<Runnable> secretCompletion = Runnable::run;
	private static final java.util.concurrent.Executor SECRET_WORKER = new java.util.concurrent.ThreadPoolExecutor(
		1, 1, 0, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(16), task ->
		{
			Thread thread = new Thread(task, "hapticscape-saved-key-wallet");
			thread.setDaemon(true);
			return thread;
		});

	private volatile RemoteLockSnapshot snapshot = RemoteLockSnapshot.inactive();
	private SettingsLockProposal controllerProposal;
	private char[] pendingControllerUnlockKey;
	private String controllerPeerId;
	private String controllerProfileId;
	private String controllerProfileName;
	private java.util.Set<SettingsLockTarget> controllerProfileTargets = Collections.emptySet();
	private SettingsLockProposal participantProposal;
	private SettingsLockProposal participantAcceptedProposal;
	private String participantOwnerId;
	private String participantArmedLockId;
	private SettingsLockProposal participantArmedProposal;
	private String participantDeclinedLockId;
	private long lastProposalNanos;

	RemoteLockCoordinator(
		Gson gson,
		SettingsLockService settingsLockService,
		SavedUnlockKeyStore savedUnlockKeyStore,
		RemoteMessageSender sender,
		Consumer<RemoteLockSnapshot> snapshotPublisher,
		Consumer<SettingsLockProposal> proposalPublisher,
		Consumer<String> namingPublisher,
		BooleanSupplier protectedExitAllowed)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
		this.settingsLockService = Objects.requireNonNull(settingsLockService, "settingsLockService");
		this.savedUnlockKeyStore = Objects.requireNonNull(savedUnlockKeyStore, "savedUnlockKeyStore");
		this.sender = Objects.requireNonNull(sender, "sender");
		this.snapshotPublisher = Objects.requireNonNull(snapshotPublisher, "snapshotPublisher");
		this.proposalPublisher = Objects.requireNonNull(proposalPublisher, "proposalPublisher");
		this.namingPublisher = Objects.requireNonNull(namingPublisher, "namingPublisher");
		this.protectedExitAllowed = Objects.requireNonNull(protectedExitAllowed, "protectedExitAllowed");
	}

	void setSecretCompletion(Consumer<Runnable> completion) { secretCompletion = completion; }

	private void saveInBackground(char[] key, Consumer<char[]> save, String success)
	{
		RemoteLockSnapshot expected = snapshot;
		try
		{
			SECRET_WORKER.execute(() ->
			{
				String result = success;
				try { save.accept(key); }
				catch (RuntimeException failure) { result = "Lock armed; unlock key could not be saved. Check your wallet."; }
				finally { Arrays.fill(key, '\0'); }
				String message = result;
				secretCompletion.accept(() ->
				{
					if (snapshot == expected) publish(RemoteLockState.ARMED, message,
						expected.getProfileId(), expected.getProfileName(), expected.getTargets());
				});
			});
		}
		catch (RuntimeException rejected)
		{
			Arrays.fill(key, '\0');
			publish(RemoteLockState.ARMED, "Lock armed; secure key vault is busy, unlock key could not be saved");
		}
	}

	RemoteLockSnapshot getSnapshot()
	{
		return snapshot;
	}

	char[] generateUnlockKey()
	{
		return settingsLockService.generateUnlockKey();
	}

	List<SavedUnlockKey> getSavedUnlockKeys()
	{
		return savedUnlockKeyStore.list();
	}

	boolean isSavedUnlockKeyVaultAvailable()
	{
		return savedUnlockKeyStore.isAvailable();
	}

	String getSavedUnlockKeyVaultMessage()
	{
		return savedUnlockKeyStore.getUnavailableMessage();
	}

	boolean savedKeysRequireBackgroundThread() { return savedUnlockKeyStore.requiresBackgroundThread(); }

	char[] revealSavedUnlockKey(String id)
	{
		return savedUnlockKeyStore.reveal(id);
	}

	SavedUnlockKey updateSavedUnlockKey(String id, String label, String note)
	{
		return savedUnlockKeyStore.updateDetails(id, label, note);
	}

	boolean forgetSavedUnlockKey(String id)
	{
		return savedUnlockKeyStore.forget(id);
	}

	void peerIdentityChanged(RemoteRole role, String peerId)
	{
		if (peerId == null || peerId.trim().isEmpty())
		{
			return;
		}
		if (role == RemoteRole.PARTICIPANT)
		{
			participantOwnerId = peerId;
			sendParticipantProfileState(peerId);
		}
		else if (role == RemoteRole.CONTROLLER)
		{
			controllerPeerId = peerId;
		}
	}

	void propose(RemoteRole role, RemoteSessionState state, char[] password)
	{
		propose(role, state, password, null);
	}

	void propose(
		RemoteRole role,
		RemoteSessionState state,
		char[] password,
		java.util.Collection<SettingsLockTarget> targets)
	{
		if (role != RemoteRole.CONTROLLER
			|| (state != RemoteSessionState.ACTIVE
				&& state != RemoteSessionState.PEER_EMERGENCY_PAUSED))
		{
			throw new IllegalStateException("A participant must be connected first");
		}
		if (snapshot.getState() == RemoteLockState.AWAITING_APPROVAL
			|| snapshot.getState() == RemoteLockState.AWAITING_FINALIZE
			|| snapshot.getState() == RemoteLockState.ARMED)
		{
			throw new IllegalStateException(
				"Cancel the current post-session lock before creating another"
			);
		}

		SettingsLockProposal proposal = targets == null
			? settingsLockService.createProposal(password)
			: settingsLockService.createProposal(password, targets);
		char[] pendingKey = Arrays.copyOf(password, password.length);
		clearPendingControllerUnlockKey();
		controllerProposal = proposal;
		pendingControllerUnlockKey = pendingKey;
		lastProposalNanos = 0;
		publish(RemoteLockState.AWAITING_APPROVAL, "Waiting for participant approval");
		sendControllerProposal();
	}

	void cancel(RemoteRole role)
	{
		if (role != RemoteRole.CONTROLLER || controllerProposal == null)
		{
			return;
		}
		if (snapshot.getState() == RemoteLockState.DECLINED)
		{
			clearPendingControllerUnlockKey();
			controllerProposal = null;
			publishControllerProfileOrInactive("No post-session lock requested");
			return;
		}
		sender.send(
			RemoteMessageType.LOCK_CANCEL_REQUEST,
			0,
			controllerProposal.getProposalId()
		);
	}

	void accept(RemoteRole role)
	{
		if (role != RemoteRole.PARTICIPANT || participantProposal == null)
		{
			return;
		}
		SettingsLockProposal proposal = participantProposal;
		try
		{
			if (proposal.isLegacyFullLock())
			{
				participantArmedLockId = proposal.getProposalId();
				participantArmedProposal = proposal;
				settingsLockService.arm(proposal);
				participantProposal = null;
				participantDeclinedLockId = null;
				publish(RemoteLockState.ARMED, "Post-session settings lock armed");
				sender.send(RemoteMessageType.LOCK_ACCEPTED, 0, participantArmedLockId);
				return;
			}

			participantAcceptedProposal = proposal;
			participantProposal = null;
			participantDeclinedLockId = null;
			publish(
				RemoteLockState.AWAITING_FINALIZE,
				"Participant approved the lock. Waiting for the controller to name it."
			);
			sender.send(RemoteMessageType.LOCK_ACCEPTED, 0, proposal.getProposalId());
		}
		catch (RuntimeException e)
		{
			participantAcceptedProposal = null;
			participantProposal = null;
			participantDeclinedLockId = proposal.getProposalId();
			publish(RemoteLockState.DECLINED, "Settings lock could not be approved");
			sender.send(RemoteMessageType.LOCK_DECLINED, 0, participantDeclinedLockId);
			LOG.log(Level.WARNING, "Unable to approve participant settings lock", e);
		}
	}

	void decline(RemoteRole role)
	{
		if (role != RemoteRole.PARTICIPANT || participantProposal == null)
		{
			return;
		}
		participantDeclinedLockId = participantProposal.getProposalId();
		participantProposal = null;
		publish(RemoteLockState.DECLINED, "Post-session lock declined");
		sender.send(RemoteMessageType.LOCK_DECLINED, 0, participantDeclinedLockId);
	}

	void finalizeProfile(RemoteRole role, String profileName, String peerClientId)
	{
		if (role != RemoteRole.CONTROLLER
			|| controllerProposal == null
			|| snapshot.getState() != RemoteLockState.AWAITING_FINALIZE)
		{
			throw new IllegalStateException("No accepted settings lock is waiting to be named");
		}
		if (controllerProposal.isLegacyFullLock())
		{
			throw new IllegalStateException("Legacy whole-settings locks do not use named profiles");
		}
		String normalizedName = SettingsLockProposal.normalizeProfileName(profileName);
		String subjectId = Objects.requireNonNull(peerClientId, "peerClientId");
		controllerPeerId = subjectId;
		FinalizeRequest request = new FinalizeRequest(
			controllerProposal.getProposalId(),
			normalizedName
		);
		publish(RemoteLockState.AWAITING_FINALIZE, "Saving lock profile \"" + normalizedName + "\"...");
		if (!sender.send(RemoteMessageType.LOCK_FINALIZE, 0, gson.toJson(request)))
		{
			throw new IllegalStateException("Unable to send the lock profile name");
		}
	}

	void handle(RemoteRole role, RemoteProtocolMessage message)
	{
		switch (message.getType())
		{
			case LOCK_PROPOSAL:
				handleProposal(role, message);
				break;
			case LOCK_ACCEPTED:
				handleAccepted(role, message);
				break;
			case LOCK_FINALIZE:
				handleFinalize(role, message);
				break;
			case LOCK_COMMITTED:
				handleCommitted(role, message);
				break;
			case LOCK_PROFILE_STATE:
				handleProfileState(role, message);
				break;
			case LOCK_DECLINED:
				handleDeclined(role, message);
				break;
			case LOCK_CANCEL_REQUEST:
				handleCancelRequest(role, message);
				break;
			case LOCK_CANCELLED:
				handleCancelled(role, message);
				break;
			default:
				throw new IllegalArgumentException("Not a settings-lock message: " + message.getType());
		}
	}

	void tick(RemoteRole role, RemoteSessionState state, long nowNanos)
	{
		if (role == RemoteRole.CONTROLLER
			&& controllerProposal != null
			&& state != RemoteSessionState.LOCAL
			&& snapshot.getState() == RemoteLockState.AWAITING_APPROVAL
			&& nowNanos - lastProposalNanos >= PROPOSAL_RETRY_NANOS)
		{
			sendControllerProposal();
		}
	}

	void handleLocalSettingsLockChanged(RemoteRole role, SettingsLockSnapshot localLocks)
	{
		if (role != RemoteRole.PARTICIPANT
			|| participantArmedLockId == null
			|| localLocks.containsLock(participantArmedLockId))
		{
			return;
		}
		String clearedId = participantArmedLockId;
		participantArmedLockId = null;
		participantArmedProposal = null;
		publish(RemoteLockState.INACTIVE, "Settings lock cleared locally");
		sender.send(RemoteMessageType.LOCK_CANCELLED, 0, clearedId);
	}

	void handleControllerConnectionLost()
	{
		clearPendingControllerUnlockKey();
		controllerProposal = null;
		publishControllerProfileOrInactive("Pending unlock key discarded");
	}

	void reset()
	{
		controllerProposal = null;
		clearPendingControllerUnlockKey();
		controllerPeerId = null;
		controllerProfileId = null;
		controllerProfileName = null;
		controllerProfileTargets = Collections.emptySet();
		participantProposal = null;
		participantAcceptedProposal = null;
		participantOwnerId = null;
		participantArmedLockId = null;
		participantArmedProposal = null;
		participantDeclinedLockId = null;
		lastProposalNanos = 0;
		snapshot = RemoteLockSnapshot.inactive();
	}

	private void handleProposal(RemoteRole role, RemoteProtocolMessage message)
	{
		if (role != RemoteRole.PARTICIPANT)
		{
			return;
		}
		try
		{
			SettingsLockProposal proposal = gson.fromJson(message.getPayload(), SettingsLockProposal.class);
			if (proposal == null)
			{
				return;
			}
			proposal.validate();
			String proposalId = proposal.getProposalId();
			if ((proposal.getTargets().contains(SettingsLockCatalog.PROTECTED_EXIT)
				|| proposal.getTargets().contains(SettingsLockCatalog.STARTUP_BEHAVIOR))
				&& !protectedExitAllowed.getAsBoolean())
			{
				participantDeclinedLockId = proposalId;
				publish(RemoteLockState.DECLINED, "Protected startup/exit permission was not granted");
				sender.send(RemoteMessageType.LOCK_DECLINED, 0, proposalId);
				return;
			}
			if (proposalId.equals(participantArmedLockId))
			{
				sender.send(RemoteMessageType.LOCK_ACCEPTED, 0, proposalId);
				return;
			}
			if (proposalId.equals(participantDeclinedLockId))
			{
				sender.send(RemoteMessageType.LOCK_DECLINED, 0, proposalId);
				return;
			}
			if ((participantProposal != null && proposalId.equals(participantProposal.getProposalId()))
				|| (participantAcceptedProposal != null
					&& proposalId.equals(participantAcceptedProposal.getProposalId())))
			{
				return;
			}
			try
			{
				if (proposal.isLegacyFullLock())
				{
					settingsLockService.validateCanArm(proposal);
				}
				else if (participantOwnerId == null)
				{
					throw new IllegalStateException("Controller identity is not available yet");
				}
				else
				{
					settingsLockService.validateCanReplace(participantOwnerId, proposal);
				}
			}
			catch (IllegalStateException conflict)
			{
				participantDeclinedLockId = proposalId;
				publish(RemoteLockState.DECLINED, conflict.getMessage());
				sender.send(RemoteMessageType.LOCK_DECLINED, 0, proposalId);
				return;
			}
			participantProposal = proposal;
			publish(
				RemoteLockState.APPROVAL_REQUIRED,
				settingsLockService.getProfileForOwner(participantOwnerId == null ? "" : participantOwnerId).isPresent()
					? "Controller requests an update to its persistent lock profile"
					: "Controller requests a persistent settings lock"
			);
			proposalPublisher.accept(proposal);
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.WARNING, "Rejected invalid settings-lock proposal", e);
		}
	}

	private void handleAccepted(RemoteRole role, RemoteProtocolMessage message)
	{
		if (role != RemoteRole.CONTROLLER || !matchesControllerProposal(message.getPayload()))
		{
			return;
		}
		if (controllerProposal.isLegacyFullLock())
		{
			handleLegacyAccepted(message.getPayload());
			return;
		}
		publish(RemoteLockState.AWAITING_FINALIZE, "Participant accepted. Name the lock profile to finish.");
		namingPublisher.accept(controllerProfileName == null ? "" : controllerProfileName);
	}

	private void handleLegacyAccepted(String lockId)
	{
		if (pendingControllerUnlockKey != null && savedUnlockKeyStore.isAvailable()
			&& savedUnlockKeyStore.requiresBackgroundThread())
		{
			char[] key = Arrays.copyOf(pendingControllerUnlockKey, pendingControllerUnlockKey.length);
			clearPendingControllerUnlockKey();
			publish(RemoteLockState.ARMED, "Participant accepted; saving unlock key...");
			saveInBackground(key, secret -> savedUnlockKeyStore.saveAcceptedKey(lockId, secret),
				"Participant accepted; unlock key saved");
			return;
		}
		String status = "Participant accepted; settings lock armed";
		try
		{
			if (pendingControllerUnlockKey == null)
			{
				status = savedUnlockKeyStore.findByLockId(lockId).isPresent()
					? "Participant accepted; unlock key saved"
					: "Participant accepted; unlock key is not available";
			}
			else if (savedUnlockKeyStore.isAvailable())
			{
				savedUnlockKeyStore.saveAcceptedKey(lockId, pendingControllerUnlockKey);
				status = "Participant accepted; unlock key saved";
			}
			else
			{
				status = "Participant accepted; secure key vault unavailable";
			}
		}
		catch (RuntimeException e)
		{
			status = "Participant accepted; unlock key could not be saved";
			LOG.log(Level.WARNING, "Unable to save accepted unlock key", e);
		}
		finally
		{
			clearPendingControllerUnlockKey();
		}
		publish(RemoteLockState.ARMED, status);
	}

	private void handleFinalize(RemoteRole role, RemoteProtocolMessage message)
	{
		if (role != RemoteRole.PARTICIPANT || participantAcceptedProposal == null)
		{
			return;
		}
		try
		{
			FinalizeRequest request = gson.fromJson(message.getPayload(), FinalizeRequest.class);
			if (request == null
				|| !participantAcceptedProposal.getProposalId().equals(request.proposalId)
				|| participantOwnerId == null)
			{
				return;
			}
			String name = SettingsLockProposal.normalizeProfileName(request.profileName);
			SettingsLockProposal finalized = settingsLockService.replaceProfile(
				participantOwnerId,
				participantAcceptedProposal,
				name
			);
			participantAcceptedProposal = null;
			participantArmedLockId = finalized.getProposalId();
			participantArmedProposal = finalized;
			participantDeclinedLockId = null;
			publish(
				RemoteLockState.ARMED,
				"Persistent lock profile \"" + name + "\" armed",
				finalized.getProposalId(),
				name,
				finalized.getTargets()
			);
			sender.send(
				RemoteMessageType.LOCK_COMMITTED,
				0,
				gson.toJson(new CommitNotice(finalized.getProposalId(), name))
			);
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.WARNING, "Unable to finalize participant settings-lock profile", e);
			String proposalId = participantAcceptedProposal == null
				? ""
				: participantAcceptedProposal.getProposalId();
			participantAcceptedProposal = null;
			participantDeclinedLockId = proposalId;
			publish(RemoteLockState.DECLINED, "Settings lock profile could not be saved");
			sender.send(RemoteMessageType.LOCK_DECLINED, 0, proposalId);
		}
	}

	private void handleCommitted(RemoteRole role, RemoteProtocolMessage message)
	{
		if (role != RemoteRole.CONTROLLER || controllerProposal == null)
		{
			return;
		}
		try
		{
			CommitNotice notice = gson.fromJson(message.getPayload(), CommitNotice.class);
			if (notice == null || !matchesControllerProposal(notice.proposalId))
			{
				return;
			}
			String name = SettingsLockProposal.normalizeProfileName(notice.profileName);
			String status = "Lock profile \"" + name + "\" armed";
			char[] backgroundKey = null;
			if (pendingControllerUnlockKey != null && savedUnlockKeyStore.isAvailable())
			{
				if (savedUnlockKeyStore.requiresBackgroundThread())
				{
					backgroundKey = Arrays.copyOf(pendingControllerUnlockKey, pendingControllerUnlockKey.length);
					status = "Lock profile armed; saving unlock key...";
				}
				else
				{
					savedUnlockKeyStore.saveAcceptedProfileKey(notice.proposalId, controllerPeerId, name, pendingControllerUnlockKey);
					status = "Lock profile \"" + name + "\" armed; unlock key saved";
				}
			}
			else if (!savedUnlockKeyStore.isAvailable())
			{
				status = "Lock profile armed; secure key vault unavailable";
			}
			controllerProfileId = notice.proposalId;
			controllerProfileName = name;
			controllerProfileTargets = controllerProposal.getTargets();
			clearPendingControllerUnlockKey();
			publish(
				RemoteLockState.ARMED,
				status,
				controllerProfileId,
				controllerProfileName,
				controllerProfileTargets
			);
			if (backgroundKey != null)
			{
				String subject = controllerPeerId;
				saveInBackground(backgroundKey,
					secret -> savedUnlockKeyStore.saveAcceptedProfileKey(notice.proposalId, subject, name, secret),
					"Lock profile \"" + name + "\" armed; unlock key saved");
			}
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.WARNING, "Unable to save committed lock profile", e);
			clearPendingControllerUnlockKey();
			publish(RemoteLockState.ARMED, "Lock profile armed; unlock key could not be saved");
		}
	}

	private void handleProfileState(RemoteRole role, RemoteProtocolMessage message)
	{
		if (role != RemoteRole.CONTROLLER || controllerProposal != null)
		{
			return;
		}
		try
		{
			ProfileState state = gson.fromJson(message.getPayload(), ProfileState.class);
			if (state == null || !state.present)
			{
				controllerProfileId = null;
				controllerProfileName = null;
				controllerProfileTargets = Collections.emptySet();
				publish(RemoteLockState.INACTIVE, "No persistent lock profile for this controller");
				return;
			}
			controllerProfileId = Objects.requireNonNull(state.profileId, "profileId");
			controllerProfileName = SettingsLockProposal.normalizeProfileName(state.profileName);
			controllerProfileTargets = SettingsLockCatalog.resolve(state.targets);
			publish(
				RemoteLockState.INACTIVE,
				"Loaded persistent lock profile \"" + controllerProfileName + "\"",
				controllerProfileId,
				controllerProfileName,
				controllerProfileTargets
			);
		}
		catch (RuntimeException e)
		{
			LOG.log(Level.FINE, "Ignoring invalid lock profile state", e);
		}
	}

	private void handleDeclined(RemoteRole role, RemoteProtocolMessage message)
	{
		if (role == RemoteRole.CONTROLLER && matchesControllerProposal(message.getPayload()))
		{
			clearPendingControllerUnlockKey();
			publish(RemoteLockState.DECLINED, "Participant declined the settings lock");
		}
	}

	private void handleCancelRequest(RemoteRole role, RemoteProtocolMessage message)
	{
		if (role != RemoteRole.PARTICIPANT)
		{
			return;
		}
		String proposalId = message.getPayload();
		boolean pendingMatch = participantProposal != null
			&& participantProposal.getProposalId().equals(proposalId);
		boolean acceptedMatch = participantAcceptedProposal != null
			&& participantAcceptedProposal.getProposalId().equals(proposalId);
		boolean armedMatch = proposalId != null && proposalId.equals(participantArmedLockId);
		if (!pendingMatch && !acceptedMatch && !armedMatch)
		{
			return;
		}
		participantProposal = null;
		participantAcceptedProposal = null;
		participantDeclinedLockId = null;
		if (armedMatch)
		{
			participantArmedLockId = null;
			participantArmedProposal = null;
			settingsLockService.removeLock(proposalId);
		}
		publish(RemoteLockState.INACTIVE, "Post-session settings lock cancelled");
		sender.send(RemoteMessageType.LOCK_CANCELLED, 0, proposalId);
		if (participantOwnerId != null)
		{
			sendParticipantProfileState(participantOwnerId);
		}
	}

	private void handleCancelled(RemoteRole role, RemoteProtocolMessage message)
	{
		if (role != RemoteRole.CONTROLLER || !matchesControllerProposal(message.getPayload()))
		{
			return;
		}
		String lockId = controllerProposal.getProposalId();
		clearPendingControllerUnlockKey();
		controllerProposal = null;
		String status = "Post-session settings lock cancelled";
		try
		{
			savedUnlockKeyStore.forgetByLockId(lockId);
		}
		catch (RuntimeException e)
		{
			status = "Settings lock cancelled; saved key could not be removed";
			LOG.log(Level.WARNING, "Unable to remove cancelled unlock key", e);
		}
		if (lockId.equals(controllerProfileId))
		{
			controllerProfileId = null;
			controllerProfileName = null;
			controllerProfileTargets = Collections.emptySet();
		}
		publishControllerProfileOrInactive(status);
	}

	private boolean matchesControllerProposal(String proposalId)
	{
		return controllerProposal != null
			&& controllerProposal.getProposalId().equals(proposalId);
	}

	private void sendControllerProposal()
	{
		SettingsLockProposal proposal = controllerProposal;
		if (proposal == null)
		{
			return;
		}
		lastProposalNanos = System.nanoTime();
		sender.send(RemoteMessageType.LOCK_PROPOSAL, 0, gson.toJson(proposal));
	}

	private void sendParticipantProfileState(String controllerId)
	{
		Optional<SettingsLockProposal> profile = settingsLockService.getProfileForOwner(controllerId);
		ProfileState state = profile
			.map(lock -> new ProfileState(
				true,
				lock.getProposalId(),
				lock.getProfileName(),
				SettingsLockCatalog.ids(lock.getTargets())
			))
			.orElseGet(ProfileState::empty);
		sender.send(RemoteMessageType.LOCK_PROFILE_STATE, 0, gson.toJson(state));
	}

	private void publishControllerProfileOrInactive(String message)
	{
		if (controllerProfileId != null && controllerProfileName != null)
		{
			publish(
				RemoteLockState.INACTIVE,
				message,
				controllerProfileId,
				controllerProfileName,
				controllerProfileTargets
			);
		}
		else
		{
			publish(RemoteLockState.INACTIVE, message);
		}
	}

	private void publish(RemoteLockState state, String message)
	{
		SettingsLockProposal visibleProposal = controllerProposal != null
			? controllerProposal
			: participantProposal != null
				? participantProposal
				: participantAcceptedProposal != null
					? participantAcceptedProposal
					: participantArmedProposal;
		java.util.Collection<SettingsLockTarget> targets = visibleProposal == null
			? Collections.emptySet()
			: visibleProposal.getTargets();
		String profileId = state == RemoteLockState.INACTIVE ? controllerProfileId : null;
		String profileName = state == RemoteLockState.INACTIVE ? controllerProfileName : null;
		publish(state, message, profileId, profileName, targets);
	}

	private void publish(
		RemoteLockState state,
		String message,
		String profileId,
		String profileName,
		java.util.Collection<SettingsLockTarget> targets)
	{
		RemoteLockSnapshot next = new RemoteLockSnapshot(
			state,
			message,
			targets,
			profileId,
			profileName
		);
		snapshot = next;
		snapshotPublisher.accept(next);
	}

	private void clearPendingControllerUnlockKey()
	{
		if (pendingControllerUnlockKey != null)
		{
			Arrays.fill(pendingControllerUnlockKey, '\0');
			pendingControllerUnlockKey = null;
		}
	}

	private static final class FinalizeRequest
	{
		private final String proposalId;
		private final String profileName;

		private FinalizeRequest(String proposalId, String profileName)
		{
			this.proposalId = proposalId;
			this.profileName = profileName;
		}
	}

	private static final class CommitNotice
	{
		private final String proposalId;
		private final String profileName;

		private CommitNotice(String proposalId, String profileName)
		{
			this.proposalId = proposalId;
			this.profileName = profileName;
		}
	}

	private static final class ProfileState
	{
		private final boolean present;
		private final String profileId;
		private final String profileName;
		private final List<String> targets;

		private ProfileState(
			boolean present,
			String profileId,
			String profileName,
			List<String> targets)
		{
			this.present = present;
			this.profileId = profileId;
			this.profileName = profileName;
			this.targets = targets == null ? Collections.emptyList() : targets;
		}

		private static ProfileState empty()
		{
			return new ProfileState(false, null, null, Collections.emptyList());
		}
	}
}
