package com.ashy0019.hapticscape;

import com.ashy0019.hapticscape.clicker.ClickSequence;
import com.ashy0019.hapticscape.clicker.ClickerAlertSettings;
import com.ashy0019.hapticscape.clicker.ClickerPhraseMatchMode;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRule;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRules;
import com.ashy0019.hapticscape.event.ChatEvent;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChatOutputDecisionTest
{
	@Test
	public void ordinaryChatCanTriggerPhraseSequence()
	{
		ChatOutputDecision decision = ChatOutputDecision.classify(
			new ChatEvent(
				"test-source",
				ChatEvent.Kind.OTHER,
				"<col=ffffff>Hello world</col>",
				"Hello world", true
			),
			phraseRules("hello", ClickSequence.TWO),
			ClickerAlertSettings.noneEnabled()
		);

		assertEquals(ClickSequence.TWO, decision.getClickSequence());
		assertTrue(decision.shouldClick());
		assertFalse(decision.hasSpecificAlert());
	}

	@Test
	public void phraseClicksTakePrecedenceOverDirectMessageClicks()
	{
		ClickerAlertSettings alerts = ClickerAlertSettings.noneEnabled()
			.withSequence(AlertCategory.DIRECT_MESSAGE, ClickSequence.TWO);
		ChatOutputDecision decision = ChatOutputDecision.classify(
			new ChatEvent(
				"test-source",
				ChatEvent.Kind.DIRECT_MESSAGE,
				"Hello there",
				"Hello there"
			),
			phraseRules("hello", ClickSequence.ONE),
			alerts
		);

		assertEquals(ClickSequence.ONE, decision.getClickSequence());
		assertTrue(decision.hasSpecificAlert());
		assertEquals(AlertCategory.DIRECT_MESSAGE, decision.getSpecificAlert());
	}

	@Test
	public void tradeRequestWithoutPhraseUsesAlertSequence()
	{
		ClickerAlertSettings alerts = ClickerAlertSettings.noneEnabled()
			.withSequence(AlertCategory.TRADE_REQUEST, ClickSequence.TWO);
		ChatOutputDecision decision = ChatOutputDecision.classify(
			new ChatEvent(
				"test-source",
				ChatEvent.Kind.TRADE_REQUEST,
				"Someone wishes to trade with you.",
				"Someone wishes to trade with you."
			),
			phraseRules("unrelated", ClickSequence.THREE),
			alerts
		);

		assertEquals(ClickSequence.TWO, decision.getClickSequence());
		assertTrue(decision.shouldClick());
		assertTrue(decision.hasSpecificAlert());
		assertEquals(AlertCategory.TRADE_REQUEST, decision.getSpecificAlert());
	}

	@Test
	public void ownAndUnknownMessagesCannotTriggerPhrases()
	{
		for (boolean incoming : new boolean[] {false, true})
		{
			ChatOutputDecision decision = ChatOutputDecision.classify(
				new ChatEvent("test", ChatEvent.Kind.OTHER, "hello", "hello", incoming),
				phraseRules("hello", ClickSequence.TWO), ClickerAlertSettings.noneEnabled());
			assertEquals(incoming, decision.shouldClick());
		}
		assertFalse(ChatOutputDecision.classify(
			new ChatEvent("test", ChatEvent.Kind.OTHER, "hello", "hello"),
			phraseRules("hello", ClickSequence.TWO), ClickerAlertSettings.noneEnabled()).shouldClick());
	}

	@Test
	public void firstMatchingRuleWins()
	{
		ClickerPhraseRules rules = phraseRules("hello", ClickSequence.ONE)
			.withAdded(new ClickerPhraseRule(true, ClickSequence.THREE, ClickerPhraseMatchMode.PHRASE, "hello world"));
		assertEquals(ClickSequence.ONE, ChatOutputDecision.classify(
			new ChatEvent("test", ChatEvent.Kind.OTHER, "hello world", "hello world", true),
			rules, ClickerAlertSettings.noneEnabled()).getClickSequence());
	}

	private static ClickerPhraseRules phraseRules(String phrase, ClickSequence sequence)
	{
		return ClickerPhraseRules.empty().withAdded(
			new ClickerPhraseRule(
				true,
				sequence,
				ClickerPhraseMatchMode.CONTAINS,
				phrase
			)
		);
	}
}
