package com.ashy0019.hapticscape;

import com.ashy0019.hapticscape.clicker.*;
import com.ashy0019.hapticscape.event.*;
import com.ashy0019.hapticscape.remote.RemoteSettingsSnapshot;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameplayChatFeedbackTest
{
	@Test
	public void phraseHapticsReplaceDirectAlertAndCooldownSuppressesDuplicates()
	{
		ClickerPhraseRule rule = new ClickerPhraseRule(true, ClickerPhraseMatchMode.PHRASE, "good job")
			.withFeedback(ClickSequence.TWO, true, HapticPatternSelection.SINGLE, 30, 500);
		RemoteSettingsSnapshot settings = RemoteSettingsSnapshot.capture(new TestHapticScapeSettings()
		{
			@Override public String clickerPhraseRules() { return ClickerPhraseRules.empty().withAdded(rule).toConfigValue(); }
		});
		RecordingFeedback feedback = new RecordingFeedback();
		try (GameplayEventCoordinator coordinator = new GameplayEventCoordinator(() -> settings, feedback))
		{
			ChatEvent message = new ChatEvent("test", ChatEvent.Kind.DIRECT_MESSAGE, "good job", "good job", true);
			coordinator.onChatEvent(message);
			coordinator.onChatEvent(message);
			assertEquals(1, feedback.haptics);
			assertEquals(1, feedback.clicks);
			assertEquals(0, feedback.alerts);
			assertEquals(ClickSequence.TWO, feedback.sequence);
		}
	}

	private static class RecordingFeedback implements GameplayEventCoordinator.FeedbackSink
	{
		int haptics, clicks, alerts;
		ClickSequence sequence;
		public void handleXp(XpEvent event, XpOutputDecision decision, RemoteSettingsSnapshot settings, XpFeedbackSettings skillSettings) { }
		public void dispatchSpecificAlert(AlertCategory category, boolean allowClick) { alerts++; }
		public void dispatchGenericAlert() { alerts++; }
		public void playClick(ClickSequence value) { clicks++; sequence = value; }
		public void playPhraseHaptics(ClickerPhraseRule rule) { haptics++; }
	}
}
