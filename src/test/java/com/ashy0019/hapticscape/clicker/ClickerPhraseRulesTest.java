package com.ashy0019.hapticscape.clicker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClickerPhraseRulesTest
{
	@Test
	public void strongestMatchingRuleWinsWithoutStackingCounts()
	{
		ClickerPhraseRules rules = ClickerPhraseRules.empty()
			.withAdded(new ClickerPhraseRule(
				true,
				ClickSequence.ONE,
				ClickerPhraseMatchMode.CONTAINS,
				"bird"
			))
			.withAdded(new ClickerPhraseRule(
				true,
				ClickSequence.THREE,
				ClickerPhraseMatchMode.CONTAINS,
				"bird nest"
			));

		assertEquals(ClickSequence.THREE, rules.strongestMatch("You receive a bird nest."));
		assertEquals(ClickSequence.ONE, rules.strongestMatch("A bird appears."));
		assertEquals(ClickSequence.NONE, rules.strongestMatch("Nothing happened."));
	}

	@Test
	public void configurationRoundTripPreservesRegexCharactersAndSequence()
	{
		ClickerPhraseRules original = ClickerPhraseRules.empty()
			.withAdded(new ClickerPhraseRule(
				true,
				ClickSequence.THREE,
				ClickerPhraseMatchMode.REGEX,
				"(?i)^You receive \\d+ x (rune|coin); nice,$"
			))
			.withAdded(new ClickerPhraseRule(
				false,
				ClickSequence.TWO,
				ClickerPhraseMatchMode.CONTAINS,
				"こんにちは"
			));

		ClickerPhraseRules restored =
			ClickerPhraseRules.fromConfigValue(
				original.toConfigValue()
			);

		assertEquals(original, restored);
		assertTrue(original.toConfigValue().startsWith("v4;"));
	}

	@Test
	public void hapticOnlyRulesRoundTripAndMatch()
	{
		ClickerPhraseRule rule = new ClickerPhraseRule(true, ClickerPhraseMatchMode.PHRASE, "good job")
			.withFeedback(ClickSequence.NONE, true, com.ashy0019.hapticscape.HapticPatternSelection.SINGLE, 35, 750);
		ClickerPhraseRules rules = ClickerPhraseRules.empty().withAdded(rule);
		assertEquals(rules, ClickerPhraseRules.fromConfigValue(rules.toConfigValue()));
		assertTrue(rules.matches("Good job!"));
	}

	@Test
	public void versionThreePreservesIdentityModeAndClicks()
	{
		String id = java.util.UUID.randomUUID().toString();
		String saved = "v3;" + id + ",1,THREE,CONTAINS,aGVsbG8";
		ClickerPhraseRule rule = ClickerPhraseRules.fromConfigValue(saved).getRules().get(0);
		assertEquals(id, rule.getId());
		assertEquals(ClickSequence.THREE, rule.getSequence());
		assertEquals(ClickerPhraseMatchMode.CONTAINS, rule.getMode());
		assertFalse(rule.isHapticEnabled());
		assertTrue(ClickerPhraseRules.requiresMigration(saved));
	}

	@Test
	public void malformedEntriesAreIgnored()
	{
		ClickerPhraseRules restored =
			ClickerPhraseRules.fromConfigValue(
				"v1;1,CONTAINS,aGVsbG8;"
					+ "1,REGEX,%%%BADBASE64%%%;"
					+ "x,EXACT,aGVsbG8"
			);

		assertEquals(1, restored.getRules().size());
		assertTrue(restored.matches("well hello there"));
	}

	@Test
	public void unknownStorageVersionFailsSafe()
	{
		ClickerPhraseRules restored =
			ClickerPhraseRules.fromConfigValue(
				"v999;1,CONTAINS,aGVsbG8"
			);

		assertTrue(restored.getRules().isEmpty());
	}

	@Test
	public void versionOneAndTwoRulesMigrateWithOneClick()
	{
		String legacy = "v1;1,CONTAINS,aGVsbG8";
		ClickerPhraseRules v1 = ClickerPhraseRules.fromConfigValue(legacy);
		String v2 = "v2;" + v1.getRules().get(0).getId() + ",1,CONTAINS,aGVsbG8";
		ClickerPhraseRules restoredV2 = ClickerPhraseRules.fromConfigValue(v2);

		assertTrue(ClickerPhraseRules.requiresMigration(legacy));
		assertTrue(ClickerPhraseRules.requiresMigration(v2));
		assertEquals(ClickSequence.ONE, v1.getRules().get(0).getSequence());
		assertEquals(ClickSequence.ONE, restoredV2.getRules().get(0).getSequence());
		assertTrue(v1.toConfigValue().startsWith("v4;"));
		assertTrue(restoredV2.toConfigValue().startsWith("v4;"));
	}

	@Test
	public void editingRulePreservesIdentityAndExistingSequence()
	{
		ClickerPhraseRule original = new ClickerPhraseRule(
			true,
			ClickSequence.THREE,
			ClickerPhraseMatchMode.CONTAINS,
			"hello"
		);
		ClickerPhraseRule edited = original.withValues(
			false,
			ClickerPhraseMatchMode.EXACT,
			"goodbye"
		);

		assertEquals(original.getId(), edited.getId());
		assertEquals(ClickSequence.THREE, edited.getSequence());
		assertFalse(original.equals(edited));
	}
}
