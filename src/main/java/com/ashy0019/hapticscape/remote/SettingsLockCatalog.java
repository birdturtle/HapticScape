package com.ashy0019.hapticscape.remote;

import com.ashy0019.hapticscape.AlertCategory;
import com.ashy0019.hapticscape.SkillDescriptor;
import com.ashy0019.hapticscape.SkillIds;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Authoritative registry of setting identifiers allowed in lock proposals. */
public final class SettingsLockCatalog
{
	private static final String PHRASE_RULE_PREFIX = "clicker.phrase.";
	private static final Map<String, SettingsLockTarget> TARGETS = new LinkedHashMap<>();
	private static final Map<String, String> PARENT_IDS = new LinkedHashMap<>();

	public static final SettingsLockTarget FEEDBACK_BLOCK = register(
		"block.feedback",
		"Sections",
		"Feedback settings"
	);
	public static final SettingsLockTarget GENERIC_ALERTS_BLOCK = register(
		"block.notifications.generic",
		"Sections",
		"Generic alert settings"
	);
	public static final SettingsLockTarget CLICK_SETTINGS_BLOCK = register(
		"block.clicker.settings",
		"Sections",
		"XP click settings"
	);
	public static final SettingsLockTarget PROTECTED_EXIT = register(
		"application.protected-exit",
		"Application",
		"Protected application exit"
	);
	public static final SettingsLockTarget STARTUP_BEHAVIOR = register(
		"application.startup",
		"Application",
		"Start at sign-in"
	);

	public static final SettingsLockTarget LEVEL_UP_HAPTICS = register(
		"feature.level-ups.haptics",
		"Feedback",
		"Level-up haptics"
	);
	public static final SettingsLockTarget MILESTONE_HAPTICS = register(
		"feature.milestones.haptics",
		"Feedback",
		"Milestone haptics"
	);
	public static final SettingsLockTarget LEVEL_99_HAPTICS = register(
		"feature.level-99.haptics",
		"Feedback",
		"Level 99 celebration"
	);
	public static final SettingsLockTarget CLICKER_ENABLED = register(
		"clicker.enabled",
		"Clicker",
		"Clicker enabled"
	);
	public static final SettingsLockTarget CLICKER_LEVEL_UP = register(
		"clicker.level-ups",
		"Clicker",
		"Level-up clicks"
	);
	public static final SettingsLockTarget CLICKER_MILESTONE = register(
		"clicker.milestones",
		"Clicker",
		"Milestone clicks"
	);
	public static final SettingsLockTarget CLICKER_LEVEL_99 = register(
		"clicker.level-99",
		"Clicker",
		"Level 99 clicks"
	);
	public static final SettingsLockTarget GENERIC_NOTIFICATION_HAPTICS = register(
		"notifications.generic.haptics",
		"Notifications",
		"Generic notification haptics"
	);
	public static final SettingsLockTarget GENERIC_NOTIFICATION_CLICKS = register(
		"notifications.generic.clicks",
		"Notifications",
		"Generic notification clicks"
	);
	public static final SettingsLockTarget NOTIFICATION_RESPECT_FOCUS = register(
		"notifications.respect-focus",
		"Notifications",
		"Respect source focus"
	);

	private static final Map<String, SettingsLockTarget> HAPTIC_SKILLS =
		new LinkedHashMap<>();
	private static final Map<String, SettingsLockTarget> CLICK_SKILLS =
		new LinkedHashMap<>();
	private static final Map<String, SettingsLockTarget> PROFILE_OVERRIDES =
		new LinkedHashMap<>();
	private static final Map<String, SettingsLockTarget> PROFILE_BLOCKS =
		new LinkedHashMap<>();
	private static final Map<AlertCategory, SettingsLockTarget> ALERT_CLICKS =
		new LinkedHashMap<>();
	private static final Map<AlertCategory, SettingsLockTarget> ALERT_BLOCKS =
		new LinkedHashMap<>();

	static
	{
		for (AlertCategory category : AlertCategory.values())
		{
			String slug = category.name().toLowerCase(Locale.ROOT).replace('_', '-');
			SettingsLockTarget alertBlock = register(
				"block.alert." + slug,
				"Specific alerts",
				category.getDisplayName() + " settings"
			);
			ALERT_BLOCKS.put(category, alertBlock);
			ALERT_CLICKS.put(category, registerChild(
				"alert." + slug + ".clicks",
				"Alert clicks",
				category.getDisplayName() + " clicks",
				alertBlock
			));
		}
		registerParent(LEVEL_UP_HAPTICS, FEEDBACK_BLOCK);
		registerParent(MILESTONE_HAPTICS, FEEDBACK_BLOCK);
		registerParent(LEVEL_99_HAPTICS, FEEDBACK_BLOCK);
		registerParent(GENERIC_NOTIFICATION_HAPTICS, GENERIC_ALERTS_BLOCK);
		registerParent(GENERIC_NOTIFICATION_CLICKS, GENERIC_ALERTS_BLOCK);
		registerParent(NOTIFICATION_RESPECT_FOCUS, GENERIC_ALERTS_BLOCK);
		registerParent(CLICKER_LEVEL_UP, CLICK_SETTINGS_BLOCK);
		registerParent(CLICKER_MILESTONE, CLICK_SETTINGS_BLOCK);
		registerParent(CLICKER_LEVEL_99, CLICK_SETTINGS_BLOCK);
	}

	private SettingsLockCatalog()
	{
	}

	public static synchronized void registerSkills(Collection<SkillDescriptor> skills)
	{
		for (SkillDescriptor skill : Objects.requireNonNull(skills, "skills"))
		{
			registerSkill(Objects.requireNonNull(skill, "skill"));
		}
	}

	public static SettingsLockTarget skillHaptics(String skillId)
	{
		return ensureSkill(skillId).haptics;
	}

	public static SettingsLockTarget skillClicks(String skillId)
	{
		return ensureSkill(skillId).clicks;
	}

	public static SettingsLockTarget profileUsesGlobal(String skillId)
	{
		return ensureSkill(skillId).usesGlobal;
	}

	public static SettingsLockTarget profileBlock(String skillId)
	{
		return ensureSkill(skillId).profileBlock;
	}

	private static synchronized SkillTargets ensureSkill(String skillId)
	{
		String canonical = SkillIds.canonical(skillId);
		SettingsLockTarget haptics = HAPTIC_SKILLS.get(canonical);
		if (haptics == null)
		{
			registerSkill(new SkillDescriptor(canonical, displayName(canonical)));
		}
		return new SkillTargets(
			HAPTIC_SKILLS.get(canonical),
			CLICK_SKILLS.get(canonical),
			PROFILE_OVERRIDES.get(canonical),
			PROFILE_BLOCKS.get(canonical)
		);
	}

	private static void registerSkill(SkillDescriptor skill)
	{
		String skillId = skill.getId();
		if (HAPTIC_SKILLS.containsKey(skillId))
		{
			return;
		}
		String slug = skillId.replace('_', '-');
		String displayName = skill.getDisplayName();
		SettingsLockTarget profileBlock = register(
			"block.profile." + slug,
			"Skill profiles",
			displayName + " XP settings"
		);
		PROFILE_BLOCKS.put(skillId, profileBlock);
		HAPTIC_SKILLS.put(skillId, register(
			"skill." + slug + ".haptics",
			"Skill haptics",
			displayName + " haptics"
		));
		CLICK_SKILLS.put(skillId, register(
			"skill." + slug + ".clicks",
			"Skill clicks",
			displayName + " clicks"
		));
		PROFILE_OVERRIDES.put(skillId, registerChild(
			"profile." + slug + ".use-global",
			"Skill profiles",
			displayName + " uses global XP settings",
			profileBlock
		));
	}

	private static String displayName(String skillId)
	{
		String[] words = skillId.replace('-', '_').split("_");
		StringBuilder display = new StringBuilder();
		for (String word : words)
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (display.length() > 0)
			{
				display.append(' ');
			}
			display.append(Character.toUpperCase(word.charAt(0)));
			display.append(word.substring(1));
		}
		return display.toString();
	}

	private static final class SkillTargets
	{
		private final SettingsLockTarget haptics;
		private final SettingsLockTarget clicks;
		private final SettingsLockTarget usesGlobal;
		private final SettingsLockTarget profileBlock;

		private SkillTargets(
			SettingsLockTarget haptics,
			SettingsLockTarget clicks,
			SettingsLockTarget usesGlobal,
			SettingsLockTarget profileBlock)
		{
			this.haptics = haptics;
			this.clicks = clicks;
			this.usesGlobal = usesGlobal;
			this.profileBlock = profileBlock;
		}
	}

	public static SettingsLockTarget alertClicks(AlertCategory category)
	{
		return Objects.requireNonNull(ALERT_CLICKS.get(category), "Unsupported alert");
	}

	public static SettingsLockTarget alertBlock(AlertCategory category)
	{
		return Objects.requireNonNull(ALERT_BLOCKS.get(category), "Unsupported alert");
	}

	public static SettingsLockTarget phraseRule(String ruleId)
	{
		String canonicalId = canonicalUuid(ruleId, "phrase-rule ID");
		return require(PHRASE_RULE_PREFIX + canonicalId);
	}

	public static boolean isPhraseRule(SettingsLockTarget target)
	{
		return Objects.requireNonNull(target, "target").getId().startsWith(PHRASE_RULE_PREFIX);
	}

	public static String phraseRuleId(SettingsLockTarget target)
	{
		if (!isPhraseRule(target))
		{
			throw new IllegalArgumentException("Target is not a phrase rule");
		}
		return target.getId().substring(PHRASE_RULE_PREFIX.length());
	}

	public static SettingsLockTarget require(String id)
	{
		SettingsLockTarget target = TARGETS.get(id);
		if (target == null && id != null && id.startsWith(PHRASE_RULE_PREFIX))
		{
			String ruleId = canonicalUuid(
				id.substring(PHRASE_RULE_PREFIX.length()),
				"phrase-rule ID"
			);
			target = new SettingsLockTarget(
				PHRASE_RULE_PREFIX + ruleId,
				"Phrase clicks",
				"Phrase click " + ruleId.substring(0, 8)
			);
		}
		if (target == null)
		{
			throw new IllegalArgumentException("Unknown settings-lock target: " + id);
		}
		return target;
	}

	/** Returns true when {@code scope} directly or transitively governs {@code target}. */
	public static boolean covers(SettingsLockTarget scope, SettingsLockTarget target)
	{
		String scopeId = require(Objects.requireNonNull(scope, "scope").getId()).getId();
		String targetId = require(Objects.requireNonNull(target, "target").getId()).getId();
		while (targetId != null)
		{
			if (scopeId.equals(targetId))
			{
				return true;
			}
			targetId = PARENT_IDS.get(targetId);
		}
		return false;
	}

	public static boolean isCoveredBy(
		Collection<SettingsLockTarget> scopes,
		SettingsLockTarget target)
	{
		for (SettingsLockTarget scope : Objects.requireNonNull(scopes, "scopes"))
		{
			if (covers(scope, target))
			{
				return true;
			}
		}
		return false;
	}

	public static boolean conflicts(SettingsLockTarget first, SettingsLockTarget second)
	{
		return covers(first, second) || covers(second, first);
	}

	public static void validateNonOverlapping(Collection<SettingsLockTarget> targets)
	{
		List<SettingsLockTarget> ordered = new ArrayList<>(
			Objects.requireNonNull(targets, "targets")
		);
		for (int first = 0; first < ordered.size(); first++)
		{
			for (int second = first + 1; second < ordered.size(); second++)
			{
				if (conflicts(ordered.get(first), ordered.get(second)))
				{
					throw new IllegalArgumentException(
						"Overlapping settings-lock targets: "
							+ ordered.get(first).getDisplayName() + " and "
							+ ordered.get(second).getDisplayName()
					);
				}
			}
		}
	}

	public static Set<SettingsLockTarget> resolve(Collection<String> ids)
	{
		Objects.requireNonNull(ids, "ids");
		Set<SettingsLockTarget> resolved = new LinkedHashSet<>();
		for (String id : ids)
		{
			SettingsLockTarget target = require(Objects.requireNonNull(id, "target ID"));
			if (!resolved.add(target))
			{
				throw new IllegalArgumentException("Duplicate settings-lock target: " + id);
			}
		}
		return Collections.unmodifiableSet(resolved);
	}

	public static List<String> ids(Collection<SettingsLockTarget> targets)
	{
		Objects.requireNonNull(targets, "targets");
		List<String> ids = new ArrayList<>();
		for (SettingsLockTarget target : targets)
		{
			SettingsLockTarget canonical = require(
				Objects.requireNonNull(target, "target").getId()
			);
			if (!ids.contains(canonical.getId()))
			{
				ids.add(canonical.getId());
			}
		}
		return Collections.unmodifiableList(ids);
	}

	public static Set<SettingsLockTarget> allTargets()
	{
		return Collections.unmodifiableSet(new LinkedHashSet<>(TARGETS.values()));
	}

	/** Targets governed by the pre-targeted legacy lock format. */
	public static Set<SettingsLockTarget> legacyTargets()
	{
		Set<SettingsLockTarget> targets = new LinkedHashSet<>(allTargets());
		// Protected exit and local click-output authority never belong to a
		// legacy broad settings lock. Level-99 clicks are a retired setting.
		targets.remove(PROTECTED_EXIT);
		targets.remove(STARTUP_BEHAVIOR);
		targets.remove(CLICKER_ENABLED);
		targets.remove(CLICKER_LEVEL_99);
		return Collections.unmodifiableSet(targets);
	}

	private static SettingsLockTarget register(String id, String group, String displayName)
	{
		SettingsLockTarget target = new SettingsLockTarget(id, group, displayName);
		if (TARGETS.put(id, target) != null)
		{
			throw new IllegalStateException("Duplicate settings-lock target: " + id);
		}
		return target;
	}

	private static SettingsLockTarget registerChild(
		String id,
		String group,
		String displayName,
		SettingsLockTarget parent)
	{
		SettingsLockTarget target = register(id, group, displayName);
		registerParent(target, parent);
		return target;
	}

	private static void registerParent(SettingsLockTarget child, SettingsLockTarget parent)
	{
		if (PARENT_IDS.put(child.getId(), parent.getId()) != null)
		{
			throw new IllegalStateException("Duplicate settings-lock parent: " + child.getId());
		}
	}

	private static String canonicalUuid(String value, String name)
	{
		try
		{
			String canonical = UUID.fromString(Objects.requireNonNull(value, name)).toString();
			if (!canonical.equals(value.toLowerCase(Locale.ROOT)))
			{
				throw new IllegalArgumentException("Invalid " + name);
			}
			return canonical;
		}
		catch (IllegalArgumentException | NullPointerException exception)
		{
			throw new IllegalArgumentException("Invalid " + name, exception);
		}
	}
}
