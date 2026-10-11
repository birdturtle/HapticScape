package com.ashy0019.hapticscape.clicker;

public enum ClickerPhraseMatchMode
{
	PHRASE("Word or phrase"),
	CONTAINS("Contains (legacy)"),
	EXACT("Exact"),
	REGEX("Regex");

	private final String displayName;

	ClickerPhraseMatchMode(String displayName)
	{
		this.displayName = displayName;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}