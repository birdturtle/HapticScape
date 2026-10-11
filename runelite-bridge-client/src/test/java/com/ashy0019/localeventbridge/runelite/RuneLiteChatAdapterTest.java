package com.ashy0019.localeventbridge.runelite;

import com.ashy0019.localeventbridge.event.ChatMessageEvent;
import net.runelite.api.ChatMessageType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RuneLiteChatAdapterTest
{
	private final RuneLiteChatAdapter adapter = new RuneLiteChatAdapter();

	@Test
	public void onlyIncomingPlayerChatQualifies()
	{
		org.junit.Assert.assertTrue(RuneLiteChatAdapter.receivedFromPlayer(ChatMessageType.PUBLICCHAT, "Other", "Me"));
		org.junit.Assert.assertFalse(RuneLiteChatAdapter.receivedFromPlayer(ChatMessageType.PUBLICCHAT, "<img=1>MY_Name", "my name"));
		org.junit.Assert.assertFalse(RuneLiteChatAdapter.receivedFromPlayer(ChatMessageType.PUBLICCHAT, "Other", null));
		org.junit.Assert.assertFalse(RuneLiteChatAdapter.receivedFromPlayer(ChatMessageType.GAMEMESSAGE, "Other", "Me"));
		org.junit.Assert.assertFalse(RuneLiteChatAdapter.receivedFromPlayer(ChatMessageType.PRIVATECHATOUT, "Other", "Me"));
		org.junit.Assert.assertTrue(RuneLiteChatAdapter.receivedFromPlayer(ChatMessageType.PRIVATECHAT, "Other", null));
	}

	@Test
	public void classifiesPrivateMessages()
	{
		assertEquals(
			ChatMessageEvent.Kind.DIRECT_MESSAGE,
			adapter.adapt(ChatMessageType.PRIVATECHAT, "hello").getKind()
		);
		assertEquals(
			ChatMessageEvent.Kind.DIRECT_MESSAGE,
			adapter.adapt(ChatMessageType.MODPRIVATECHAT, "hello").getKind()
		);
	}

	@Test
	public void classifiesOnlyActualTradeRequests()
	{
		assertEquals(
			ChatMessageEvent.Kind.TRADE_REQUEST,
			adapter.adapt(
				ChatMessageType.TRADEREQ,
				"Someone wishes to trade with you."
			).getKind()
		);
		assertEquals(
			ChatMessageEvent.Kind.OTHER,
			adapter.adapt(ChatMessageType.TRADEREQ, "Trade message changed").getKind()
		);
	}

	@Test
	public void preservesRawTextAndNormalizesForCorePolicy()
	{
		String raw = "  <col=ffffff>hello<at>world<nbh>x</col>\u00A0  ";
		ChatMessageEvent event = adapter.adapt(ChatMessageType.GAMEMESSAGE, raw);

		assertEquals("runelite", event.getSource());
		assertEquals(ChatMessageEvent.TYPE, event.getType());
		assertEquals(ChatMessageEvent.Kind.OTHER, event.getKind());
		assertEquals(raw, event.getRawMessage());
		assertEquals("hello@world-x", event.getNormalizedMessage());
	}

	@Test
	public void normalizerHandlesJagexPrintableAndFormattingTags()
	{
		assertEquals(
			"hello <world> @me-x\nnext",
			RuneLiteChatAdapter.normalizeMessage(
				"<col=ff0000>hello <lt>world<gt> <at>me<nbh>x<br>next</col>"
			)
		);
	}
}
