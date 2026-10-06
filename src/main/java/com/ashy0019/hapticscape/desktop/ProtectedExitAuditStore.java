package com.ashy0019.hapticscape.desktop;

import com.google.gson.Gson;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/** Durable, controller-bound marker for protected exits and interrupted runs. */
public final class ProtectedExitAuditStore
{
	private static final String RUNNING = "running";
	private static final String PENDING = "unauthorizedEndPending";
	private static final String PROTECTED = "protected";
	private static final String PROTECTED_CONTROLLER = "protectedControllerId";
	private static final String EVENT_ID = "unauthorizedEndEventId";
	private static final String EVENT_CONTROLLER = "unauthorizedEndControllerId";
	private static final String PROTECTED_LOCK = "protectedLockId";
	private static final String EVENT_LOCK = "unauthorizedEndLockId";
	private static final String EVENT_TIME = "unauthorizedEndOccurredAt";

	private final Path path;
	private final List<UnauthorizedEndRecord> backlog = new ArrayList<>();
	private boolean running;
	private boolean pending;
	private boolean protectedExit;
	private String protectedControllerId;
	private String eventId;
	private String protectedLockId;
	private String eventLockId;
	private String eventControllerId;
	private long eventTime;

	public ProtectedExitAuditStore(Path path)
	{
		this.path = Objects.requireNonNull(path, "path");
		load();
	}

	public synchronized void beginRun(boolean protectionActive)
	{
		beginRun(protectionActive, null);
	}

	public synchronized void beginRun(boolean protectionActive, String controllerId)
	{
		beginRun(protectionActive, controllerId, null);
	}

	public synchronized void beginRun(boolean protectionActive, String controllerId, String lockId)
	{
		if (running && protectedExit)
		{
			createPendingEvent(protectedControllerId != null
				? protectedControllerId
				: controllerId);
		}
		else if (pending && (eventId == null || eventControllerId == null || eventTime <= 0))
		{
			// Upgrade the original boolean-only audit marker using the current
			// protected-exit owner, never whichever controller happens to join next.
			createPendingEvent(protectedControllerId != null
				? protectedControllerId
				: controllerId);
		}
		running = true;
		protectedExit = protectionActive;
		protectedControllerId = protectionActive ? validUuidOrNull(controllerId) : null;
		protectedLockId = protectionActive ? validUuidOrNull(lockId) : null;
		persist();
	}

	public synchronized void setProtectionActive(boolean protectionActive)
	{
		setProtectionActive(protectionActive, null);
	}

	public synchronized void setProtectionActive(boolean protectionActive, String controllerId)
	{
		setProtectionActive(protectionActive, controllerId, null);
	}

	public synchronized void setProtectionActive(boolean protectionActive, String controllerId, String lockId)
	{
		protectedExit = protectionActive;
		protectedControllerId = protectionActive ? validUuidOrNull(controllerId) : null;
		protectedLockId = protectionActive ? validUuidOrNull(lockId) : null;
		persist();
	}

	public synchronized void markAuthorizedEnd()
	{
		running = false;
		protectedExit = false;
		protectedControllerId = null;
		protectedLockId = null;
		persist();
	}

	public synchronized void markUnauthorizedEnd()
	{
		markUnauthorizedEnd(null);
	}

	public synchronized UnauthorizedEndRecord markUnauthorizedEnd(String currentControllerId)
	{
		running = false;
		protectedExit = false;
		String owner = protectedControllerId != null
			? protectedControllerId
			: validUuidOrNull(currentControllerId);
		protectedControllerId = null;
		createPendingEvent(owner);
		protectedLockId = null;
		persist();
		return new UnauthorizedEndRecord(eventId, eventControllerId, eventTime, eventLockId);
	}

	public synchronized boolean hasPendingUnauthorizedEnd()
	{
		return pending || !backlog.isEmpty();
	}

	public synchronized Optional<UnauthorizedEndRecord> getPendingUnauthorizedEnd()
	{
		if (!pending || eventId == null || eventControllerId == null || eventTime <= 0)
		{
			return Optional.empty();
		}
		return Optional.of(new UnauthorizedEndRecord(eventId, eventControllerId, eventTime, eventLockId));
	}

	public synchronized List<UnauthorizedEndRecord> getPendingUnauthorizedEnds()
	{
		List<UnauthorizedEndRecord> records = new ArrayList<>(backlog);
		getPendingUnauthorizedEnd().ifPresent(records::add);
		return Collections.unmodifiableList(records);
	}

	public synchronized void clearPendingUnauthorizedEnd()
	{
		clearPending();
		persist();
	}

	public synchronized boolean clearPendingUnauthorizedEnd(String acknowledgedEventId)
	{
		if (backlog.removeIf(record -> record.getEventId().equals(acknowledgedEventId)))
		{
			persist();
			return true;
		}
		if (!pending || eventId == null || !eventId.equals(acknowledgedEventId)) return false;
		clearCurrentPending();
		persist();
		return true;
	}

	private void createPendingEvent(String controllerId)
	{
		getPendingUnauthorizedEnd().ifPresent(backlog::add);
		pending = true;
		eventLockId = protectedLockId;
		eventId = UUID.randomUUID().toString();
		eventControllerId = validUuidOrNull(controllerId);
		eventTime = System.currentTimeMillis();
	}

	private void clearPending()
	{
		backlog.clear();
		clearCurrentPending();
	}

	private void clearCurrentPending()
	{
		pending = false;
		eventId = null;
		eventControllerId = null;
		eventTime = 0;
		eventLockId = null;
	}

	private static String validUuidOrNull(String value)
	{
		if (value == null || value.trim().isEmpty())
		{
			return null;
		}
		String normalized = value.trim();
		try
		{
			UUID.fromString(normalized);
			return normalized;
		}
		catch (IllegalArgumentException ignored)
		{
			return null;
		}
	}

	private void load()
	{
		if (!Files.exists(path))
		{
			return;
		}
		Properties properties = new Properties();
		try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
		{
			properties.load(reader);
			UnauthorizedEndRecord[] queued = new Gson().fromJson(
				properties.getProperty("unauthorizedEndBacklog", "[]"), UnauthorizedEndRecord[].class);
			if (queued != null)
			{
				for (UnauthorizedEndRecord record : queued)
				{
					if (record != null && validUuidOrNull(record.eventId) != null
						&& validUuidOrNull(record.controllerId) != null && record.occurredAtMillis > 0
						&& (record.lockId == null || validUuidOrNull(record.lockId) != null)) backlog.add(record);
				}
			}
			running = Boolean.parseBoolean(properties.getProperty(RUNNING));
			pending = Boolean.parseBoolean(properties.getProperty(PENDING));
			protectedExit = Boolean.parseBoolean(properties.getProperty(PROTECTED));
			protectedControllerId = validUuidOrNull(properties.getProperty(PROTECTED_CONTROLLER));
			eventId = validUuidOrNull(properties.getProperty(EVENT_ID));
			protectedLockId = validUuidOrNull(properties.getProperty(PROTECTED_LOCK));
			eventLockId = validUuidOrNull(properties.getProperty(EVENT_LOCK));
			eventControllerId = validUuidOrNull(properties.getProperty(EVENT_CONTROLLER));
			try
			{
				eventTime = Long.parseLong(properties.getProperty(EVENT_TIME, "0"));
			}
			catch (NumberFormatException ignored)
			{
				eventTime = 0;
			}
		}
		catch (IOException failure)
		{
			throw new IllegalStateException("Unable to read protected-exit state", failure);
		}
	}

	private void persist()
	{
		Path absolute = path.toAbsolutePath();
		Path parent = absolute.getParent();
		if (parent == null)
		{
			throw new IllegalStateException("Protected-exit state has no parent directory");
		}
		Path temporary = null;
		try
		{
			Files.createDirectories(parent);
			temporary = Files.createTempFile(parent, "protected-exit-", ".tmp");
			Properties properties = new Properties();
			properties.setProperty("unauthorizedEndBacklog", new Gson().toJson(backlog));
			properties.setProperty(RUNNING, Boolean.toString(running));
			properties.setProperty(PENDING, Boolean.toString(pending));
			properties.setProperty(PROTECTED, Boolean.toString(protectedExit));
			put(properties, PROTECTED_CONTROLLER, protectedControllerId);
			put(properties, EVENT_ID, eventId);
			put(properties, PROTECTED_LOCK, protectedLockId);
			put(properties, EVENT_LOCK, eventLockId);
			put(properties, EVENT_CONTROLLER, eventControllerId);
			properties.setProperty(EVENT_TIME, Long.toString(eventTime));
			try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))
			{
				properties.store(writer, "HapticScape protected-exit state");
			}
			try
			{
				Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException ignored)
			{
				Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
			}
			temporary = null;
		}
		catch (IOException failure)
		{
			throw new IllegalStateException("Unable to write protected-exit state", failure);
		}
		finally
		{
			if (temporary != null)
			{
				try
				{
					Files.deleteIfExists(temporary);
				}
				catch (IOException ignored)
				{
					// Best-effort cleanup only.
				}
			}
		}
	}

	private static void put(Properties properties, String key, String value)
	{
		if (value != null)
		{
			properties.setProperty(key, value);
		}
	}

	public static final class UnauthorizedEndRecord
	{
		private final String eventId;
		private final String controllerId;
		private final long occurredAtMillis;
		private final String lockId;

		private UnauthorizedEndRecord(String eventId, String controllerId, long occurredAtMillis, String lockId)
		{
			this.eventId = eventId;
			this.controllerId = controllerId;
			this.occurredAtMillis = occurredAtMillis;
			this.lockId = lockId;
		}

		public String getLockId() { return lockId; }
		public String getEventId() { return eventId; }
		public String getControllerId() { return controllerId; }
		public long getOccurredAtMillis() { return occurredAtMillis; }
	}
}
