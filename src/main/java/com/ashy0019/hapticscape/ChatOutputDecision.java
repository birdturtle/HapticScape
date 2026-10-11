package com.ashy0019.hapticscape;

import com.ashy0019.hapticscape.clicker.ClickSequence;
import com.ashy0019.hapticscape.clicker.ClickerAlertSettings;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRules;
import com.ashy0019.hapticscape.clicker.ClickerPhraseRule;
import com.ashy0019.hapticscape.event.ChatEvent;
import java.util.Objects;

/** Source-neutral policy decision for a chat observation. */
final class ChatOutputDecision
{
	private final AlertCategory specificAlert;
	private final ClickSequence clickSequence;
	private final ClickerPhraseRule phraseRule;

	private ChatOutputDecision(
		AlertCategory specificAlert,
		ClickSequence clickSequence, ClickerPhraseRule phraseRule)
	{
		this.phraseRule = phraseRule;
		this.specificAlert = specificAlert;
		this.clickSequence = Objects.requireNonNull(clickSequence, "clickSequence");
	}

	static ChatOutputDecision classify(
		ChatEvent event,
		ClickerPhraseRules phraseRules,
		ClickerAlertSettings alertSettings)
	{
		Objects.requireNonNull(event, "event");
		Objects.requireNonNull(phraseRules, "phraseRules");
		Objects.requireNonNull(alertSettings, "alertSettings");

		String message = event.getNormalizedMessage();
		ClickerPhraseRule phraseRule = null;
		if (event.isReceivedPlayerMessage() && !message.isEmpty())
		{
			for (ClickerPhraseRule candidate : phraseRules.getRules())
				if (candidate.matches(message)) { phraseRule = candidate; break; }
		}

		AlertCategory specificAlert;
		switch (event.getKind())
		{
			case DIRECT_MESSAGE:
				specificAlert = AlertCategory.DIRECT_MESSAGE;
				break;
			case TRADE_REQUEST:
				specificAlert = AlertCategory.TRADE_REQUEST;
				break;
			case OTHER:
			default:
				specificAlert = null;
				break;
		}

		ClickSequence alertSequence = specificAlert == null
			? ClickSequence.NONE
			: alertSettings.getSequence(specificAlert);
		return new ChatOutputDecision(
			specificAlert,
			phraseRule != null && phraseRule.getSequence().isEnabled() ? phraseRule.getSequence() : alertSequence,
			phraseRule
		);
	}

	ClickerPhraseRule getPhraseRule() { return phraseRule; }

	boolean hasSpecificAlert()
	{
		return specificAlert != null;
	}

	AlertCategory getSpecificAlert()
	{
		if (specificAlert == null)
		{
			throw new IllegalStateException("No specific alert for this chat event");
		}
		return specificAlert;
	}

	ClickSequence getClickSequence()
	{
		return clickSequence;
	}

	boolean shouldClick()
	{
		return clickSequence.isEnabled();
	}
}
