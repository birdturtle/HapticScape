package com.ashy0019.hapticscape.remote;

import com.ashy0019.hapticscape.HapticScapeSettingKeys;
import com.ashy0019.hapticscape.TestHapticScapeSettings;

import com.ashy0019.hapticscape.CustomPattern;
import com.ashy0019.hapticscape.CustomPatternLibrary;
import com.google.gson.Gson;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RemoteSessionManagerTest
{
	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Test
	public void participantSeedsControllerAndKeepsAcceptedChanges() throws Exception
	{
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(12);
		MutableConfig participantConfig = new MutableConfig(47);
		MemoryStore controllerStore = new MemoryStore(controllerConfig);
		MemoryStore participantStore = new MemoryStore(participantConfig);
		EffectiveSettingsService controllerEffective =
			new EffectiveSettingsService(controllerConfig);
		EffectiveSettingsService participantEffective =
			new EffectiveSettingsService(participantConfig);
		SettingsLockService controllerLock = lockService("controller-lock.json");
		SettingsLockService participantLock = lockService("participant-lock.json");

		try (RemoteSessionManager controller = new RemoteSessionManager(
			new Gson(),
			controllerStore,
			controllerEffective,
			controllerLock,
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				new Gson(),
				participantStore,
				participantEffective,
				participantLock,
				relay))
		{
			List<RemoteSettingsSnapshot> controllerViews = new CopyOnWriteArrayList<>();
			controller.addListener(new RecordingListener(controllerViews));
			AtomicInteger controllerSessionEvents = new AtomicInteger();
			controller.addListener(new RemoteSessionListener()
			{
				@Override
				public void onRemoteSessionChanged(RemoteSessionSnapshot snapshot)
				{
					controllerSessionEvents.incrementAndGet();
				}
			});
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());

			awaitActive(controller, participant);
			await(() -> !controllerViews.isEmpty());
			assertEquals(47, last(controllerViews).getGlobalXpFeedbackSettings()
				.getIntensityPercent());
			assertEquals(47, participantEffective.current().getGlobalXpFeedbackSettings()
				.getIntensityPercent());
			assertEquals(12, controllerEffective.current().getGlobalXpFeedbackSettings()
				.getIntensityPercent());
			int controllerViewCountAfterSeed = controllerViews.size();
			int controllerSessionEventsAfterSeed = controllerSessionEvents.get();

			assertTrue(controller.updateControllerSetting(
				HapticScapeSettingKeys.INTENSITY_PERCENT,
				68
			));
			relay.dropNextFrom(RemoteRole.PARTICIPANT);
			await(() -> participantConfig.intensityPercent() == 68);
			controller.reconcileSettingsSafely();
			assertEquals("Participant settings loaded", controller.getSnapshot().getMessage());
			assertEquals(
				"An unchanged acknowledgement must not rebuild the controller UI",
				controllerViewCountAfterSeed,
				controllerViews.size()
			);
			assertEquals(
				"Settings synchronization must not masquerade as a lifecycle change",
				controllerSessionEventsAfterSeed,
				controllerSessionEvents.get()
			);

			assertEquals(68, participantStore.capture().getGlobalXpFeedbackSettings()
				.getIntensityPercent());
			assertEquals(68, participantEffective.current().getGlobalXpFeedbackSettings()
				.getIntensityPercent());
			assertEquals(12, controllerConfig.intensityPercent());
			assertEquals(0, controllerStore.getSaveCount());

			CustomPatternLibrary subjectPatterns = CustomPatternLibrary.defaults()
				.addBlankPattern()
				.withName(2, "Remote forge")
				.withPattern(2, new CustomPattern(0, 60, 100, 0), 700, 3);
			String persistedPatterns = subjectPatterns.toConfigValue();
			assertTrue(controller.updateControllerSetting(
				HapticScapeSettingKeys.CUSTOM_PATTERNS,
				persistedPatterns
			));
			await(() -> participantConfig.customPatterns().equals(persistedPatterns));
			controller.reconcileSettingsSafely();
			assertEquals(
				"Remote forge",
				participantStore.capture().getCustomPatterns().findById(2)
					.orElseThrow(AssertionError::new)
					.getName()
			);

			participant.endSession();
			assertEquals(RemoteSessionState.LOCAL, participant.getSnapshot().getState());
			assertEquals(68, participantEffective.current().getGlobalXpFeedbackSettings()
				.getIntensityPercent());
			assertEquals(persistedPatterns, participantConfig.customPatterns());
		}
	}

	@Test
	public void localAndParticipantSessionsRejectControllerEdits()
	{
		MutableConfig config = new MutableConfig(35);
		MemoryStore store = new MemoryStore(config);
		try (RemoteSessionManager manager = new RemoteSessionManager(
			new Gson(),
			store,
			new EffectiveSettingsService(config),
			lockService("single-lock.json"),
			new TestRelay()))
		{
			assertFalse(manager.updateControllerSetting(
				HapticScapeSettingKeys.INTENSITY_PERCENT,
				80
			));
			assertEquals(35, config.intensityPercent());
		}
	}

	@Test
	public void controllerTimesOutWhenEndFrameAndCloseCallbackAreBothLost() throws Exception
	{
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		try (RemoteSessionManager controller = new RemoteSessionManager(
			new Gson(),
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("liveness-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				new Gson(),
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				lockService("liveness-participant.json"),
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);

			// Model the observed production failure: the relay accepts but loses
			// SESSION_END, and removing the participant produces no peer callback.
			relay.suppressFrom(RemoteRole.PARTICIPANT);
			participant.endSession();
			awaitState(controller, RemoteSessionState.DISCONNECTED, 6);
		}
	}

	@Test
	public void closingParticipantServiceExplicitlyEndsControllerSession()
		throws Exception
	{
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		try (RemoteSessionManager controller = new RemoteSessionManager(
			new Gson(),
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("service-close-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				new Gson(),
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				lockService("service-close-participant.json"),
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);

			participant.close();

			awaitState(controller, RemoteSessionState.LOCAL, 2);
			assertEquals(RemoteRole.NONE, controller.getSnapshot().getRole());
		}
	}

	@Test
	public void healthySessionRemainsActiveAcrossLivenessWindow() throws Exception
	{
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		try (RemoteSessionManager controller = new RemoteSessionManager(
			new Gson(),
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("healthy-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				new Gson(),
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				lockService("healthy-participant.json"),
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);
			Thread.sleep(RemoteSessionManager.PEER_LIVENESS_TIMEOUT_MILLIS + 1_000);

			assertEquals(RemoteSessionState.ACTIVE, controller.getSnapshot().getState());
			assertEquals(RemoteSessionState.ACTIVE, participant.getSnapshot().getState());
		}
	}

	@Test
	public void participantEntersEmergencyPauseWhenControllerSilentlyDisappears()
		throws Exception
	{
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		try (RemoteSessionManager controller = new RemoteSessionManager(
			new Gson(),
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("controller-loss-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				new Gson(),
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				lockService("controller-loss-participant.json"),
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);
			relay.suppressFrom(RemoteRole.CONTROLLER);
			controller.endSession();

			awaitState(participant, RemoteSessionState.EMERGENCY_PAUSED, 6);
		}
	}

	@Test
	public void droppedTransportWaitsForManualReconnectAndRequiresParticipantResume()
		throws Exception
	{
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		try (RemoteSessionManager controller = new RemoteSessionManager(
			new Gson(),
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("reconnect-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				new Gson(),
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				lockService("reconnect-participant.json"),
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);

			relay.disconnectAll("test network drop");

			assertEquals(RemoteRole.CONTROLLER, controller.getSnapshot().getRole());
			assertEquals(RemoteSessionState.DISCONNECTED, controller.getSnapshot().getState());
			assertEquals(RemoteRole.PARTICIPANT, participant.getSnapshot().getRole());
			assertEquals(
				RemoteSessionState.EMERGENCY_PAUSED,
				participant.getSnapshot().getState()
			);
			assertTrue(controller.canReconnect());
			assertTrue(participant.canReconnect());

			Thread.sleep(1_000);
			assertEquals(0, relay.connectedCount());
			assertEquals(
				RemoteSessionState.DISCONNECTED,
				controller.getSnapshot().getState()
			);
			assertEquals(
				RemoteSessionState.EMERGENCY_PAUSED,
				participant.getSnapshot().getState()
			);

			assertTrue(controller.reconnect());
			assertFalse(controller.canReconnect());
			assertTrue(participant.canReconnect());
			assertTrue(participant.reconnect());
			awaitState(controller, RemoteSessionState.PEER_EMERGENCY_PAUSED, 6);
			assertEquals(
				RemoteSessionState.EMERGENCY_PAUSED,
				participant.getSnapshot().getState()
			);
			relay.repeatLastDisconnectCallbacks("late callback from old transport");
			assertEquals(
				RemoteSessionState.PEER_EMERGENCY_PAUSED,
				controller.getSnapshot().getState()
			);
			assertEquals(
				RemoteSessionState.EMERGENCY_PAUSED,
				participant.getSnapshot().getState()
			);

			participant.resumeParticipant();
			awaitState(controller, RemoteSessionState.ACTIVE, 2);
			awaitState(participant, RemoteSessionState.ACTIVE, 2);
		}
	}

	@Test
	public void unauthorizedEndIsControllerBoundAcknowledgedAndDeduplicated()
		throws Exception
	{
		TestRelay relay = new TestRelay();
		try (RemoteSessionManager controller = new RemoteSessionManager(
			new Gson(),
			new MemoryStore(new MutableConfig(20)),
			new EffectiveSettingsService(new MutableConfig(20)),
			lockService("audit-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				new Gson(),
				new MemoryStore(new MutableConfig(60)),
				new EffectiveSettingsService(new MutableConfig(60)),
				lockService("audit-participant.json"),
				relay))
		{
			List<String> received = new CopyOnWriteArrayList<>();
			List<String> acknowledged = new CopyOnWriteArrayList<>();
			controller.addListener(new RemoteSessionListener()
			{
				@Override
				public void onRemoteSessionChanged(RemoteSessionSnapshot snapshot) { }

				@Override
				public void onUnauthorizedEnd(String reason) { received.add(reason); }
			});
			participant.addListener(new RemoteSessionListener()
			{
				@Override
				public void onRemoteSessionChanged(RemoteSessionSnapshot snapshot) { }

				@Override
				public void onUnauthorizedEndAcknowledged(String eventId)
				{
					acknowledged.add(eventId);
				}
			});

			RemoteInvitation invitation = controller.startController("wss://relay.example/relay");
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);
			await(() -> participant.getPeerClientId().isPresent());
			String controllerId = participant.getPeerClientId().orElseThrow(AssertionError::new);
			String eventId = java.util.UUID.randomUUID().toString();

			assertFalse(participant.reportUnauthorizedEnd(
				eventId, java.util.UUID.randomUUID().toString(), 1234L, "wrong target"));
			assertTrue(participant.reportUnauthorizedEnd(
				eventId, controllerId, 1234L, "Unauthorized end"));
			assertTrue(participant.reportUnauthorizedEnd(
				eventId, controllerId, 1234L, "Unauthorized end"));

			await(() -> received.size() == 1 && acknowledged.size() == 2);
			assertEquals(Collections.singletonList("Unauthorized end"), received);
			assertEquals(2, acknowledged.size());
			assertEquals(eventId, acknowledged.get(0));
		}
	}

	@Test
	public void participantApprovalPersistsLockAndAllowsFreshSession()
		throws Exception
	{
		Gson gson = new Gson();
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		SettingsLockService controllerLock = lockService("approval-controller.json");
		SettingsLockService participantLock = lockService("approval-participant.json");
		char[] password = "correct horse battery staple".toCharArray();
		try (RemoteSessionManager controller = new RemoteSessionManager(
			gson,
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			controllerLock,
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				gson,
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				participantLock,
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);
			controller.proposeSettingsLock(password);

			await(() -> participant.getLockSnapshot().getState()
				== RemoteLockState.APPROVAL_REQUIRED);
			assertFalse(participantLock.isLocked());
			participant.acceptPendingSettingsLock();

			await(() -> controller.getLockSnapshot().getState() == RemoteLockState.ARMED
				&& participant.getLockSnapshot().getState() == RemoteLockState.ARMED
				&& participantLock.isLocked());
			participant.endSession();
			assertTrue(participantLock.isLocked());

			RemoteInvitation reconnect = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(reconnect.encode());
			awaitActive(controller, participant);
			assertTrue(participantLock.isLocked());
			assertEquals(RemoteSessionState.ACTIVE, participant.getSnapshot().getState());
			participant.endSession();

			assertFalse(participantLock.unlock("wrong password".toCharArray()));
			assertTrue(participantLock.isLocked());
			assertTrue(participantLock.unlock(password));
			assertFalse(participantLock.isLocked());
			assertFalse(controllerLock.isLocked());
		}
		finally
		{
			java.util.Arrays.fill(password, '\0');
		}
	}

	@Test
	public void targetedLockProposalPreservesExactTargetAcrossRelay()
		throws Exception
	{
		Gson gson = new Gson();
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		SettingsLockService participantLock = lockService("targeted-participant.json");
		char[] password = "targeted lock password".toCharArray();
		try (RemoteSessionManager controller = new RemoteSessionManager(
			gson,
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("targeted-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				gson,
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				participantLock,
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);
			controller.proposeSettingsLock(
				password,
				Collections.singleton(SettingsLockCatalog.skillClicks(
					"fishing"
				))
			);

			await(() -> Collections.singleton(SettingsLockCatalog.skillClicks(
				"fishing"
			)).equals(participant.getLockSnapshot().getTargets()));
			participant.acceptPendingSettingsLock();
			await(() -> controller.getLockSnapshot().getState()
				== RemoteLockState.AWAITING_FINALIZE);
			assertFalse(participantLock.isLocked(
				SettingsLockCatalog.skillClicks("fishing")
			));
			controller.finalizePendingSettingsLock("Fishing clicks");
			await(() -> participantLock.isLocked(
				SettingsLockCatalog.skillClicks("fishing")
			));
			await(() -> "Fishing clicks".equals(
				controller.getLockSnapshot().getProfileName()));
			assertFalse(participantLock.isLocked(
				SettingsLockCatalog.skillHaptics("fishing")
			));
			assertTrue(participantLock.unlock(password));
		}
		finally
		{
			java.util.Arrays.fill(password, '\0');
		}
	}

	@Test
	public void controllerCanCancelAnAcceptedSessionLock()
		throws Exception
	{
		Gson gson = new Gson();
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		SettingsLockService participantLock = lockService("cancel-participant.json");
		try (RemoteSessionManager controller = new RemoteSessionManager(
			gson,
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("cancel-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				gson,
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				participantLock,
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);
			controller.proposeSettingsLock("cancel this password".toCharArray());
			await(() -> participant.getLockSnapshot().getState()
				== RemoteLockState.APPROVAL_REQUIRED);
			participant.acceptPendingSettingsLock();
			await(participantLock::isLocked);

			controller.cancelSettingsLock();

			await(() -> !participantLock.isLocked()
				&& controller.getLockSnapshot().getState() == RemoteLockState.INACTIVE
				&& participant.getLockSnapshot().getState() == RemoteLockState.INACTIVE);
		}
	}

	@Test
	public void controllerVaultSavesOnlyAcceptedKeysAndRemovesCancelledLocks()
		throws Exception
	{
		Gson gson = new Gson();
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		TestUnlockKeyProtector protector = new TestUnlockKeyProtector();
		SavedUnlockKeyStore vault = new SavedUnlockKeyStore(
			gson,
			temporaryFolder.getRoot().toPath().resolve("controller-vault.json"),
			protector,
			Clock.systemUTC()
		);
		char[] declinedKey = "ABCD-EFGH-JKLM-NPQR-STUV".toCharArray();
		char[] acceptedKey = "WXYZ-2345-6789-BCDF-GHJK".toCharArray();
		try (RemoteSessionManager controller = new RemoteSessionManager(
			gson,
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("vault-controller-lock.json"),
			vault,
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				gson,
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				lockService("vault-participant-lock.json"),
				relay))
		{
			RemoteInvitation invitation = controller.startController(
				"wss://relay.example/relay"
			);
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);

			controller.proposeSettingsLock(declinedKey);
			assertTrue(vault.list().isEmpty());
			assertEquals(0, protector.getProtectCount());
			participant.declinePendingSettingsLock();
			assertTrue(vault.list().isEmpty());
			assertEquals(0, protector.getProtectCount());
			controller.cancelSettingsLock();

			controller.proposeSettingsLock(acceptedKey);
			assertTrue(vault.list().isEmpty());
			participant.acceptPendingSettingsLock();
			await(() -> vault.list().size() == 1);
			assertEquals(1, vault.list().size());
			assertEquals(1, protector.getProtectCount());
			char[] revealed = controller.revealSavedUnlockKey(vault.list().get(0).getId());
			try
			{
				assertArrayEquals(acceptedKey, revealed);
			}
			finally
			{
				java.util.Arrays.fill(revealed, '\0');
			}

			controller.cancelSettingsLock();
			await(() -> vault.list().isEmpty());
			assertTrue(vault.list().isEmpty());
		}
		finally
		{
			java.util.Arrays.fill(declinedKey, '\0');
			java.util.Arrays.fill(acceptedKey, '\0');
		}
	}

	@Test
	public void acceptedProfileUpdateDoesNotReplaceOldProfileUntilNamed()
		throws Exception
	{
		Gson gson = new Gson();
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		SettingsLockService participantLock = lockService("profile-pending-participant.json");
		char[] firstKey = "ABCD-EFGH-JKLM-NPQR-STUV".toCharArray();
		char[] secondKey = "WXYZ-2345-6789-BCDF-GHJK".toCharArray();
		try (RemoteSessionManager controller = new RemoteSessionManager(
			gson,
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("profile-pending-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				gson,
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				participantLock,
				relay))
		{
			RemoteInvitation invitation = controller.startController("wss://relay.example/relay");
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);
			controller.proposeSettingsLock(
				firstKey,
				Collections.singleton(SettingsLockCatalog.LEVEL_UP_HAPTICS)
			);
			await(() -> participant.getLockSnapshot().getState()
				== RemoteLockState.APPROVAL_REQUIRED);
			participant.acceptPendingSettingsLock();
			await(() -> controller.getLockSnapshot().getState()
				== RemoteLockState.AWAITING_FINALIZE);
			controller.finalizePendingSettingsLock("Bossing");
			await(() -> participantLock.isLocked(SettingsLockCatalog.LEVEL_UP_HAPTICS));

			participant.endSession();
			RemoteInvitation reconnect = controller.startController("wss://relay.example/relay");
			participant.joinParticipant(reconnect.encode());
			awaitActive(controller, participant);
			controller.proposeSettingsLock(
				secondKey,
				Collections.singleton(SettingsLockCatalog.MILESTONE_HAPTICS)
			);
			await(() -> participant.getLockSnapshot().getState()
				== RemoteLockState.APPROVAL_REQUIRED);
			participant.acceptPendingSettingsLock();
			await(() -> controller.getLockSnapshot().getState()
				== RemoteLockState.AWAITING_FINALIZE);

			participant.endSession();
			assertTrue(participantLock.isLocked(SettingsLockCatalog.LEVEL_UP_HAPTICS));
			assertFalse(participantLock.isLocked(SettingsLockCatalog.MILESTONE_HAPTICS));
			assertFalse(participantLock.unlock(secondKey));
			assertTrue(participantLock.unlock(firstKey));
		}
		finally
		{
			java.util.Arrays.fill(firstKey, '\0');
			java.util.Arrays.fill(secondKey, '\0');
		}
	}

	@Test
	public void reconnectingControllerCanAtomicallyReplaceItsNamedProfile()
		throws Exception
	{
		Gson gson = new Gson();
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		SettingsLockService participantLock = lockService("profile-replace-participant.json");
		char[] firstKey = "ABCD-EFGH-JKLM-NPQR-STUV".toCharArray();
		char[] secondKey = "WXYZ-2345-6789-BCDF-GHJK".toCharArray();
		try (RemoteSessionManager controller = new RemoteSessionManager(
			gson,
			new MemoryStore(controllerConfig),
			new EffectiveSettingsService(controllerConfig),
			lockService("profile-replace-controller.json"),
			relay);
			RemoteSessionManager participant = new RemoteSessionManager(
				gson,
				new MemoryStore(participantConfig),
				new EffectiveSettingsService(participantConfig),
				participantLock,
				relay))
		{
			RemoteInvitation invitation = controller.startController("wss://relay.example/relay");
			participant.joinParticipant(invitation.encode());
			awaitActive(controller, participant);
			controller.proposeSettingsLock(
				firstKey,
				Collections.singleton(SettingsLockCatalog.LEVEL_UP_HAPTICS)
			);
			await(() -> participant.getLockSnapshot().getState()
				== RemoteLockState.APPROVAL_REQUIRED);
			participant.acceptPendingSettingsLock();
			await(() -> controller.getLockSnapshot().getState()
				== RemoteLockState.AWAITING_FINALIZE);
			controller.finalizePendingSettingsLock("Bossing");
			await(() -> participantLock.isLocked(SettingsLockCatalog.LEVEL_UP_HAPTICS)
				&& "Bossing".equals(controller.getLockSnapshot().getProfileName()));

			participant.endSession();
			RemoteInvitation reconnect = controller.startController("wss://relay.example/relay");
			participant.joinParticipant(reconnect.encode());
			awaitActive(controller, participant);
			assertEquals("Bossing", controller.getLockSnapshot().getProfileName());
			assertTrue(controller.getLockSnapshot().getTargets().contains(
				SettingsLockCatalog.LEVEL_UP_HAPTICS
			));

			controller.proposeSettingsLock(
				secondKey,
				Collections.singleton(SettingsLockCatalog.MILESTONE_HAPTICS)
			);
			await(() -> participant.getLockSnapshot().getState()
				== RemoteLockState.APPROVAL_REQUIRED);
			participant.acceptPendingSettingsLock();
			await(() -> controller.getLockSnapshot().getState()
				== RemoteLockState.AWAITING_FINALIZE);
			assertTrue("Old profile stays active until finalize",
				participantLock.isLocked(SettingsLockCatalog.LEVEL_UP_HAPTICS));
			assertFalse(participantLock.isLocked(SettingsLockCatalog.MILESTONE_HAPTICS));

			controller.finalizePendingSettingsLock("Skilling");
			await(() -> !participantLock.isLocked(SettingsLockCatalog.LEVEL_UP_HAPTICS)
				&& participantLock.isLocked(SettingsLockCatalog.MILESTONE_HAPTICS)
				&& "Skilling".equals(controller.getLockSnapshot().getProfileName()));
			assertFalse(participantLock.unlock(firstKey));
			assertTrue(participantLock.unlock(secondKey));
		}
		finally
		{
			java.util.Arrays.fill(firstKey, '\0');
			java.util.Arrays.fill(secondKey, '\0');
		}
	}

	@Test(timeout = 15000)
	public void acceptedKeyWalletPromptDoesNotBlockRemoteSessionActions() throws Exception
	{
		Gson gson = new Gson();
		TestRelay relay = new TestRelay();
		MutableConfig controllerConfig = new MutableConfig(20);
		MutableConfig participantConfig = new MutableConfig(60);
		BlockingSecretProtector protector = new BlockingSecretProtector(new TestUnlockKeyProtector());
		protector.blockProtect = true;
		SavedUnlockKeyStore vault = new SavedUnlockKeyStore(gson,
			temporaryFolder.getRoot().toPath().resolve("background-vault.json"), protector, Clock.systemUTC());
		try (RemoteSessionManager controller = new RemoteSessionManager(gson,
			new MemoryStore(controllerConfig), new EffectiveSettingsService(controllerConfig),
			lockService("background-controller.json"), vault, relay);
			RemoteSessionManager participant = new RemoteSessionManager(gson,
				new MemoryStore(participantConfig), new EffectiveSettingsService(participantConfig),
				lockService("background-participant.json"), relay))
		{
			participant.joinParticipant(controller.startController("wss://relay.example/relay").encode());
			awaitActive(controller, participant);
			controller.proposeSettingsLock("ABCD-EFGH-JKLM-NPQR-STUV".toCharArray());
			participant.acceptPendingSettingsLock();
			assertTrue(protector.entered.await(2, TimeUnit.SECONDS));
			assertFalse(java.util.concurrent.CompletableFuture.supplyAsync(controller::canReconnect)
				.get(1, TimeUnit.SECONDS));
			assertTrue(vault.list().isEmpty());
			protector.release.countDown();
			await(() -> vault.list().size() == 1);
			await(() -> controller.getLockSnapshot().getMessage().contains("unlock key saved"));
		}
		finally { protector.release.countDown(); }
	}

	private SettingsLockService lockService(String name)
	{
		return new SettingsLockService(
			new Gson(),
			temporaryFolder.getRoot().toPath().resolve(name)
		);
	}

	private static RemoteSettingsSnapshot last(List<RemoteSettingsSnapshot> settings)
	{
		return settings.get(settings.size() - 1);
	}

	private static void awaitActive(
		RemoteSessionManager controller,
		RemoteSessionManager participant) throws Exception
	{
		await(() -> controller.getSnapshot().getState() == RemoteSessionState.ACTIVE
			&& participant.getSnapshot().getState() == RemoteSessionState.ACTIVE);
	}

	private static void await(BooleanSupplier condition) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
		while (!condition.getAsBoolean() && System.nanoTime() < deadline)
		{
			Thread.sleep(10);
		}
		assertTrue("Timed out waiting for remote state", condition.getAsBoolean());
	}

	private static void awaitState(
		RemoteSessionManager manager,
		RemoteSessionState expected,
		int timeoutSeconds) throws Exception
	{
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
		while (manager.getSnapshot().getState() != expected
			&& System.nanoTime() < deadline)
		{
			Thread.sleep(20);
		}
		assertEquals(expected, manager.getSnapshot().getState());
	}

	private static final class RecordingListener implements RemoteSessionListener
	{
		private final List<RemoteSettingsSnapshot> settings;

		private RecordingListener(List<RemoteSettingsSnapshot> settings)
		{
			this.settings = settings;
		}

		@Override
		public void onRemoteSessionChanged(RemoteSessionSnapshot snapshot)
		{
		}

		@Override
		public void onRemoteSettingsChanged(RemoteSettingsSnapshot snapshot)
		{
			settings.add(snapshot);
		}
	}

	private static final class MutableConfig extends TestHapticScapeSettings
	{
		private volatile int intensity;
		private volatile String customPatterns =
			CustomPatternLibrary.defaults().toConfigValue();

		private MutableConfig(int intensity)
		{
			this.intensity = intensity;
		}

		@Override
		public int intensityPercent()
		{
			return intensity;
		}

		@Override
		public String customPatterns()
		{
			return customPatterns;
		}
	}

	private static final class MemoryStore implements RemoteSettingsStore
	{
		private final MutableConfig config;
		private volatile int saveCount;

		private MemoryStore(MutableConfig config)
		{
			this.config = config;
		}

		@Override
		public RemoteSettingsSnapshot capture()
		{
			return RemoteSettingsSnapshot.capture(config);
		}

		@Override
		public RemoteSettingsSnapshot save(RemoteSettingsSnapshot settings)
		{
			settings.validate();
			config.intensity = settings.getGlobalXpFeedbackSettings().getIntensityPercent();
			config.customPatterns = settings.getCustomPatterns().toConfigValue();
			saveCount++;
			return capture();
		}

		private int getSaveCount()
		{
			return saveCount;
		}
	}

	private static final class TestRelay implements RemoteTransportFactory
	{
		private final Map<RemoteRole, TestConnection> peers =
			new EnumMap<>(RemoteRole.class);
		private final ArrayDeque<Delivery> deliveries = new ArrayDeque<>();
		private RemoteRole dropNextRole = RemoteRole.NONE;
		private RemoteRole suppressedRole = RemoteRole.NONE;
		private List<TestConnection> lastDisconnected = Collections.emptyList();
		private boolean delivering;

		@Override
		public synchronized RemoteTransport create(RemoteTransport.Listener listener)
		{
			return new TestConnection(this, listener);
		}

		private void connect(TestConnection connection, RemoteRole role)
		{
			synchronized (this)
			{
				connection.role = role;
				connection.open = true;
				peers.put(role, connection);
			}
			connection.listener.onOpen();
		}

		private boolean send(TestConnection sender, String message)
		{
			boolean shouldDrain;
			synchronized (this)
			{
				if (!sender.open)
				{
					return false;
				}
				if (sender.role == dropNextRole)
				{
					dropNextRole = RemoteRole.NONE;
					return true;
				}
				if (sender.role == suppressedRole)
				{
					return true;
				}
				for (TestConnection peer : peers.values())
				{
					if (peer != sender && peer.open)
					{
						deliveries.addLast(new Delivery(peer, message));
					}
				}
				shouldDrain = !delivering;
				if (shouldDrain)
				{
					delivering = true;
				}
			}
			if (shouldDrain)
			{
				drainDeliveries();
			}
			return true;
		}

		private void drainDeliveries()
		{
			while (true)
			{
				Delivery delivery;
				synchronized (this)
				{
					delivery = deliveries.pollFirst();
					if (delivery == null)
					{
						delivering = false;
						return;
					}
					if (!delivery.recipient.open)
					{
						continue;
					}
				}
				try
				{
					delivery.recipient.listener.onMessage(delivery.message);
				}
				catch (RuntimeException | Error failure)
				{
					synchronized (this)
					{
						deliveries.clear();
						delivering = false;
					}
					throw failure;
				}
			}
		}

		private synchronized void dropNextFrom(RemoteRole role)
		{
			dropNextRole = role;
		}

		private synchronized void suppressFrom(RemoteRole role)
		{
			suppressedRole = role;
		}

		private synchronized int connectedCount()
		{
			return peers.size();
		}

		private void disconnectAll(String reason)
		{
			List<TestConnection> disconnected;
			synchronized (this)
			{
				disconnected = new ArrayList<>(peers.values());
				lastDisconnected = new ArrayList<>(disconnected);
				peers.clear();
				for (TestConnection connection : disconnected)
				{
					connection.open = false;
				}
				deliveries.clear();
			}
			for (TestConnection connection : disconnected)
			{
				connection.listener.onClosed(reason);
			}
		}

		private void repeatLastDisconnectCallbacks(String reason)
		{
			List<TestConnection> disconnected;
			synchronized (this)
			{
				disconnected = new ArrayList<>(lastDisconnected);
			}
			for (TestConnection connection : disconnected)
			{
				connection.listener.onClosed(reason);
			}
		}

		private synchronized void close(TestConnection connection)
		{
			connection.open = false;
			peers.remove(connection.role, connection);
			deliveries.removeIf(delivery -> delivery.recipient == connection);
		}

		private static final class Delivery
		{
			private final TestConnection recipient;
			private final String message;

			private Delivery(TestConnection recipient, String message)
			{
				this.recipient = recipient;
				this.message = message;
			}
		}
	}

	private static final class TestConnection implements RemoteTransport
	{
		private final TestRelay relay;
		private final Listener listener;
		private RemoteRole role = RemoteRole.NONE;
		private volatile boolean open;

		private TestConnection(TestRelay relay, Listener listener)
		{
			this.relay = relay;
			this.listener = listener;
		}

		@Override
		public void connect(
			String relayUrl,
			String roomId,
			RemoteRole role,
			String reconnectSlot)
		{
			relay.connect(this, role);
		}

		@Override
		public boolean send(String message)
		{
			return relay.send(this, message);
		}

		@Override
		public boolean isOpen()
		{
			return open;
		}

		@Override
		public void close()
		{
			relay.close(this);
		}
	}
}
