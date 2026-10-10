package com.ashy0019.hapticscape.remote;

import com.ashy0019.hapticscape.storage.HapticScapeStoragePaths;
import com.google.gson.Gson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Persistent controller vault containing only platform-protected unlock keys. */
public final class SavedUnlockKeyStore
{
	private static final Logger LOG = Logger.getLogger(SavedUnlockKeyStore.class.getName());
	private static final int LEGACY_SCHEMA_VERSION = 1;
	private static final int SCHEMA_VERSION = 2;
	private static final int MAXIMUM_ENTRIES = 1_000;
	private static final long MAXIMUM_VAULT_BYTES = 5L * 1024L * 1024L;
	private static final DateTimeFormatter DEFAULT_LABEL_FORMAT = DateTimeFormatter.ofPattern(
		"'Session' MMM d, h:mm a",
		Locale.ENGLISH
	);

	private final Gson gson;
	private final Path path;
	private final UnlockKeyProtector protector;
	private final Clock clock;
	private List<SavedUnlockKey> entries = Collections.emptyList();
	private final Set<String> forgottenLockIds = new HashSet<>();
	private String loadFailure;

	public SavedUnlockKeyStore(
		Gson gson,
		HapticScapeStoragePaths storagePaths,
		UnlockKeyProtector protector)
	{
		this(
			gson,
			Objects.requireNonNull(storagePaths, "storagePaths").getSavedUnlockKeysPath(),
			Objects.requireNonNull(protector, "protector"),
			Clock.systemDefaultZone()
		);
	}

	SavedUnlockKeyStore(
		Gson gson,
		Path path,
		UnlockKeyProtector protector,
		Clock clock)
	{
		this.gson = Objects.requireNonNull(gson, "gson");
		this.protector = Objects.requireNonNull(protector, "protector");
		this.path = protector.isAvailable()
			? Objects.requireNonNull(path, "path")
			: path;
		this.clock = Objects.requireNonNull(clock, "clock");
		// Metadata and envelope validation never unlock the wallet. Always inspect an
		// existing file, even if secure storage is temporarily unavailable.
		if (path != null) load();
	}

	static SavedUnlockKeyStore disabled(Gson gson)
	{
		return new SavedUnlockKeyStore(
			gson,
			null,
			new UnavailableProtector(),
			Clock.systemUTC()
		);
	}

	boolean requiresBackgroundThread() { return protector.requiresBackgroundThread(); }

	public boolean isAvailable()
	{
		return protector.isAvailable() && loadFailure == null;
	}

	public String getUnavailableMessage()
	{
		if (!protector.isAvailable())
		{
			return protector.getUnavailableMessage();
		}
		return loadFailure == null ? "" : loadFailure;
	}

	public synchronized List<SavedUnlockKey> list()
	{
		return Collections.unmodifiableList(new ArrayList<>(entries));
	}

	public synchronized Optional<SavedUnlockKey> findByLockId(String lockId)
	{
		return entries.stream()
			.filter(entry -> entry.getLockId().equals(lockId))
			.findFirst();
	}

	public SavedUnlockKey saveAcceptedKey(String lockId, char[] unlockKey)
	{
		ensureAvailable();
		Objects.requireNonNull(lockId, "lockId");
		Objects.requireNonNull(unlockKey, "unlockKey");
		Optional<SavedUnlockKey> existing = findByLockId(lockId);
		if (existing.isPresent())
		{
			return existing.get();
		}

		byte[] plaintext = toAscii(unlockKey);
		byte[] protectedBytes = null;
		try
		{
			protectedBytes = protector.protect(plaintext);
			Instant now = clock.instant();
			String label = DEFAULT_LABEL_FORMAT
				.withZone(clock.getZone())
				.format(now);
			SavedUnlockKey entry = new SavedUnlockKey(
				UUID.randomUUID().toString(),
				label,
				lockId,
				null,
				now.toEpochMilli(),
				0,
				"",
				Base64.getEncoder().encodeToString(protectedBytes)
			);
			entry.validate();
			synchronized (this)
			{
				requireNotForgotten(lockId);
				Optional<SavedUnlockKey> concurrent = findByLockId(lockId);
				if (concurrent.isPresent()) return concurrent.get();
				List<SavedUnlockKey> updated = new ArrayList<>(entries);
				updated.add(0, entry);
				persist(updated);
				entries = Collections.unmodifiableList(updated);
				return entry;
			}
		}
		finally
		{
			Arrays.fill(plaintext, (byte) 0);
			if (protectedBytes != null)
			{
				Arrays.fill(protectedBytes, (byte) 0);
			}
		}
	}

	public SavedUnlockKey saveAcceptedProfileKey(
		String lockId,
		String subjectId,
		String profileName,
		char[] unlockKey)
	{
		ensureAvailable();
		Objects.requireNonNull(lockId, "lockId");
		String requiredSubjectId = Objects.requireNonNull(subjectId, "subjectId").trim();
		UUID.fromString(requiredSubjectId);
		String label = SettingsLockProposal.normalizeProfileName(profileName);
		Objects.requireNonNull(unlockKey, "unlockKey");

		byte[] plaintext = toAscii(unlockKey);
		byte[] protectedBytes = null;
		try
		{
			protectedBytes = protector.protect(plaintext);
			Instant now = clock.instant();
			SavedUnlockKey entry = new SavedUnlockKey(
				UUID.randomUUID().toString(),
				label,
				lockId,
				requiredSubjectId,
				now.toEpochMilli(),
				0,
				"",
				Base64.getEncoder().encodeToString(protectedBytes)
			);
			entry.validate();
			synchronized (this)
			{
				requireNotForgotten(lockId);
				List<SavedUnlockKey> updated = new ArrayList<>(entries);
				for (SavedUnlockKey existing : entries)
				{
					if (!lockId.equals(existing.getLockId())) continue;
					for (SavedUnlockKey.ExitEvent event : existing.getUnauthorizedEnds())
						entry = entry.withUnauthorizedEnd(event.getEventId(), event.getOccurredAt().toEpochMilli());
				}
				updated.removeIf(existing ->
					requiredSubjectId.equals(existing.getSubjectId())
						|| lockId.equals(existing.getLockId())
				);
				updated.add(0, entry);
				persist(updated);
				entries = Collections.unmodifiableList(updated);
				return entry;
			}
		}
		finally
		{
			Arrays.fill(plaintext, (byte) 0);
			if (protectedBytes != null)
			{
				Arrays.fill(protectedBytes, (byte) 0);
			}
		}
	}

	/** Records authenticated exit notices without opening or changing the protected key. */
	public synchronized boolean recordUnauthorizedEnd(String subjectId, long occurredAtMillis)
	{
		return recordUnauthorizedEnd(subjectId, null, "legacy-" + occurredAtMillis, occurredAtMillis);
	}

	public synchronized boolean recordUnauthorizedEnd(
		String subjectId, String lockId, String eventId, long occurredAtMillis)
	{
		if (subjectId == null || path == null || loadFailure != null) return false;
		if (occurredAtMillis <= 0 || eventId == null || eventId.isEmpty() || eventId.length() > 80)
			throw new IllegalArgumentException("Invalid exit event");
		for (int index = 0; index < entries.size(); index++)
		{
			SavedUnlockKey entry = entries.get(index);
			if (!subjectId.equals(entry.getSubjectId())
				|| (lockId != null && !lockId.equals(entry.getLockId()))) continue;
			if (entry.getUnauthorizedEnds().stream().anyMatch(event -> eventId.equals(event.getEventId())))
				return true;
			List<SavedUnlockKey> updated = new ArrayList<>(entries);
			updated.set(index, entry.withUnauthorizedEnd(eventId, occurredAtMillis));
			persist(updated);
			entries = Collections.unmodifiableList(updated);
			return true;
		}
		return false;
	}

	public synchronized SavedUnlockKey updateDetails(
		String id,
		String label,
		String note)
	{
		ensureAvailable();
		int index = indexOf(id);
		SavedUnlockKey updatedEntry = entries.get(index).withDetails(label, note);
		List<SavedUnlockKey> updated = new ArrayList<>(entries);
		updated.set(index, updatedEntry);
		persist(updated);
		entries = Collections.unmodifiableList(updated);
		return updatedEntry;
	}

	/** Returns a caller-owned key array and records successful access. */
	public char[] reveal(String id)
	{
		ensureAvailable();
		SavedUnlockKey entry;
		synchronized (this) { entry = entries.get(indexOf(id)); }
		byte[] protectedBytes;
		try
		{
			protectedBytes = Base64.getDecoder().decode(entry.getProtectedKey());
		}
		catch (IllegalArgumentException e)
		{
			throw new IllegalStateException("The saved unlock key is damaged", e);
		}
		byte[] plaintext = null;
		char[] key = null;
		try
		{
			plaintext = protector.unprotect(protectedBytes);
			key = fromAscii(plaintext);
			synchronized (this)
			{
				int index = indexOf(id);
				// A concurrent rename must survive the access timestamp update.
				SavedUnlockKey accessed = entries.get(index).withLastUsedAt(clock.millis());
				List<SavedUnlockKey> updated = new ArrayList<>(entries);
				updated.set(index, accessed);
				persist(updated);
				entries = Collections.unmodifiableList(updated);
				return key;
			}
		}
		catch (RuntimeException e)
		{
			if (key != null)
			{
				Arrays.fill(key, '\0');
			}
			throw e;
		}
		finally
		{
			Arrays.fill(protectedBytes, (byte) 0);
			if (plaintext != null)
			{
				Arrays.fill(plaintext, (byte) 0);
			}
		}
	}

	public synchronized boolean forget(String id)
	{
		ensureAvailable();
		int index = findIndex(id);
		if (index < 0)
		{
			return false;
		}
		String lockId = entries.get(index).getLockId();
		List<SavedUnlockKey> updated = new ArrayList<>(entries);
		updated.remove(index);
		persist(updated);
		forgottenLockIds.add(lockId);
		entries = Collections.unmodifiableList(updated);
		return true;
	}

	synchronized boolean forgetByLockId(String lockId)
	{
		// A cancelled lock must not reappear when an outstanding wallet prompt finishes.
		forgottenLockIds.add(lockId);
		if (!isAvailable())
		{
			return false;
		}
		List<SavedUnlockKey> updated = new ArrayList<>(entries);
		boolean removed = updated.removeIf(entry -> entry.getLockId().equals(lockId));
		if (removed)
		{
			persist(updated);
			entries = Collections.unmodifiableList(updated);
		}
		return removed;
	}

	private void requireNotForgotten(String lockId)
	{
		if (forgottenLockIds.contains(lockId))
			throw new IllegalStateException("The lock was cancelled or forgotten before its key could be saved");
	}

	private void load()
	{
		if (!Files.isRegularFile(path))
		{
			return;
		}
		try
		{
			if (Files.size(path) > MAXIMUM_VAULT_BYTES)
			{
				throw new IllegalArgumentException("Saved-key vault is unexpectedly large");
			}
			String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
			VaultFile vault = gson.fromJson(json, VaultFile.class);
			if (vault == null
				|| (vault.schemaVersion != LEGACY_SCHEMA_VERSION
					&& vault.schemaVersion != SCHEMA_VERSION))
			{
				throw new IllegalArgumentException("Unsupported saved-key vault format");
			}
			List<SavedUnlockKey> loaded = vault.entries == null
				? Collections.emptyList()
				: new ArrayList<>(vault.entries);
			if (loaded.size() > MAXIMUM_ENTRIES)
			{
				throw new IllegalArgumentException("Saved-key vault contains too many entries");
			}
			Set<String> entryIds = new HashSet<>();
			Set<String> lockIds = new HashSet<>();
			Set<String> subjectIds = new HashSet<>();
			for (SavedUnlockKey entry : loaded)
			{
				entry.validate();
				byte[] payload = Base64.getDecoder().decode(entry.getProtectedKey());
				try
				{
					protector.validateCiphertext(payload);
				}
				finally
				{
					Arrays.fill(payload, (byte) 0);
				}
				if (!entryIds.add(entry.getId()) || !lockIds.add(entry.getLockId()))
				{
					throw new IllegalArgumentException("Saved-key vault contains duplicates");
				}
				if (entry.getSubjectId() != null && !subjectIds.add(entry.getSubjectId()))
				{
					throw new IllegalArgumentException("Saved-key vault contains duplicate subject profiles");
				}
			}
			entries = Collections.unmodifiableList(loaded);
		}
		catch (Exception e)
		{
			loadFailure = "Saved Unlock Keys could not read the existing vault";
			LOG.log(Level.WARNING, loadFailure, e);
		}
	}

	private void persist(List<SavedUnlockKey> updated)
	{
		Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
		try
		{
			Files.createDirectories(path.getParent());
			if (updated.isEmpty())
			{
				Files.deleteIfExists(path);
				Files.deleteIfExists(temporary);
				return;
			}
			Files.write(
				temporary,
				gson.toJson(new VaultFile(SCHEMA_VERSION, updated))
					.getBytes(StandardCharsets.UTF_8)
			);
			try
			{
				Files.move(
					temporary,
					path,
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING
				);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		catch (IOException e)
		{
			try
			{
				Files.deleteIfExists(temporary);
			}
			catch (IOException ignored)
			{
				// Preserve the original failure.
			}
			throw new IllegalStateException("Unable to save the unlock-key vault", e);
		}
	}

	private void ensureAvailable()
	{
		if (!isAvailable())
		{
			throw new IllegalStateException(getUnavailableMessage());
		}
	}

	private int indexOf(String id)
	{
		int index = findIndex(id);
		if (index < 0)
		{
			throw new IllegalArgumentException("Saved unlock key no longer exists");
		}
		return index;
	}

	private int findIndex(String id)
	{
		Objects.requireNonNull(id, "id");
		for (int index = 0; index < entries.size(); index++)
		{
			if (entries.get(index).getId().equals(id))
			{
				return index;
			}
		}
		return -1;
	}

	private static byte[] toAscii(char[] key)
	{
		byte[] bytes = new byte[key.length];
		for (int index = 0; index < key.length; index++)
		{
			char character = key[index];
			if (character == 0 || character > 0x7f)
			{
				Arrays.fill(bytes, (byte) 0);
				throw new IllegalArgumentException("Unlock key contains invalid characters");
			}
			bytes[index] = (byte) character;
		}
		return bytes;
	}

	private static char[] fromAscii(byte[] bytes)
	{
		char[] key = new char[bytes.length];
		for (int index = 0; index < bytes.length; index++)
		{
			int value = bytes[index] & 0xff;
			if (value == 0 || value > 0x7f)
			{
				Arrays.fill(key, '\0');
				throw new IllegalStateException("The saved unlock key is damaged");
			}
			key[index] = (char) value;
		}
		return key;
	}

	private static final class VaultFile
	{
		private final int schemaVersion;
		private final List<SavedUnlockKey> entries;

		private VaultFile(int schemaVersion, List<SavedUnlockKey> entries)
		{
			this.schemaVersion = schemaVersion;
			this.entries = new ArrayList<>(entries);
		}
	}

	private static final class UnavailableProtector implements UnlockKeyProtector
	{
		@Override
		public boolean isAvailable()
		{
			return false;
		}

		@Override
		public String getUnavailableMessage()
		{
			return "Saved Unlock Keys are disabled in this context";
		}

		@Override
		public byte[] protect(byte[] plaintext)
		{
			throw new IllegalStateException(getUnavailableMessage());
		}

		@Override
		public byte[] unprotect(byte[] ciphertext)
		{
			throw new IllegalStateException(getUnavailableMessage());
		}
	}
}
