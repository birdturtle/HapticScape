package com.ashy0019.hapticscape.clicker;

import java.util.regex.PatternSyntaxException;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ClickerPhraseRuleTest
{
	@Test
	public void phraseMatchesWholeWordsAndMultipleWords()
	{
		ClickerPhraseRule rule = new ClickerPhraseRule(true, ClickerPhraseMatchMode.PHRASE, "good job");
		assertTrue(rule.matches("Hey, GOOD   JOB!"));
		assertFalse(rule.matches("good jobs"));
		assertFalse(rule.matches("ungood job"));
		ClickerPhraseRule word = new ClickerPhraseRule(true, ClickerPhraseMatchMode.PHRASE, "good");
		assertTrue(word.matches("good!"));
		assertFalse(word.matches("goodbye"));
		assertFalse(word.matches("égood"));
	}

	@Test
	public void containsIsCaseInsensitiveAndPartial()
	{
		ClickerPhraseRule rule = new ClickerPhraseRule(
			true,
			ClickerPhraseMatchMode.CONTAINS,
			"bird nest"
		);

		assertTrue(rule.matches("A BIRD NEST falls out of the tree."));
		assertFalse(rule.matches("Nothing interesting happens."));
	}

	@Test
	public void exactIsCaseInsensitiveButRequiresWholeMessage()
	{
		ClickerPhraseRule rule = new ClickerPhraseRule(
			true,
			ClickerPhraseMatchMode.EXACT,
			"Your inventory is full."
		);

		assertTrue(rule.matches("YOUR INVENTORY IS FULL."));
		assertFalse(rule.matches("Warning: Your inventory is full."));
	}

	@Test
	public void regexUsesFindAndIsCaseSensitiveByDefault()
	{
		ClickerPhraseRule rule = new ClickerPhraseRule(
			true,
			ClickerPhraseMatchMode.REGEX,
			"\\d+ coins"
		);

		assertTrue(rule.matches("You receive 500 coins."));
		assertFalse(rule.matches("You receive 500 COINS."));
	}

	@Test
	public void regexSupportsInlineFlagsAndAnchors()
	{
		ClickerPhraseRule rule = new ClickerPhraseRule(
			true,
			ClickerPhraseMatchMode.REGEX,
			"(?i)^you receive \\d+ coins\\.$"
		);

		assertTrue(rule.matches("You receive 500 COINS."));
		assertFalse(rule.matches("Suddenly, you receive 500 coins."));
	}

	@Test(expected = PatternSyntaxException.class)
	public void invalidRegexIsRejected()
	{
		new ClickerPhraseRule(
			true,
			ClickerPhraseMatchMode.REGEX,
			"(["
		);
	}

	@Test
	public void disabledRuleNeverMatches()
	{
		ClickerPhraseRule rule = new ClickerPhraseRule(
			false,
			ClickerPhraseMatchMode.CONTAINS,
			"hello"
		);

		assertFalse(rule.matches("hello"));
	}
}