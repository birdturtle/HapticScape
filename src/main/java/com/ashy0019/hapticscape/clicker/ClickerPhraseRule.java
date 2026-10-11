package com.ashy0019.hapticscape.clicker;

import com.ashy0019.hapticscape.HapticPatternSelection;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public final class ClickerPhraseRule
{
	public static final int MAXIMUM_EXPRESSION_LENGTH = 500;

	private final String id;
	private final boolean enabled;
	private final ClickSequence sequence;
	private final ClickerPhraseMatchMode mode;
	private final String expression;
	private final String normalizedLiteral;
	private final Pattern regexPattern;
	private final boolean hapticEnabled;
	private final HapticPatternSelection hapticPattern;
	private final int intensityPercent;
	private final int durationMillis;

	public ClickerPhraseRule(
		boolean enabled,
		ClickerPhraseMatchMode mode,
		String expression)
	{
		this(UUID.randomUUID().toString(), enabled, ClickSequence.ONE, mode, expression);
	}

	public ClickerPhraseRule(
		boolean enabled,
		ClickSequence sequence,
		ClickerPhraseMatchMode mode,
		String expression)
	{
		this(UUID.randomUUID().toString(), enabled, sequence, mode, expression);
	}

	ClickerPhraseRule(
		String id,
		boolean enabled,
		ClickerPhraseMatchMode mode,
		String expression)
	{
		this(id, enabled, ClickSequence.ONE, mode, expression);
	}

	ClickerPhraseRule(
		String id,
		boolean enabled,
		ClickSequence sequence,
		ClickerPhraseMatchMode mode,
		String expression)
	{
		this(id, enabled, sequence, mode, expression, false, HapticPatternSelection.SINGLE, 10, 500);
	}

	ClickerPhraseRule(String id, boolean enabled, ClickSequence sequence, ClickerPhraseMatchMode mode,
		String expression, boolean hapticEnabled, HapticPatternSelection hapticPattern, int intensityPercent, int durationMillis)
	{
		this.hapticEnabled = hapticEnabled;
		this.hapticPattern = Objects.requireNonNull(hapticPattern, "hapticPattern");
		if (intensityPercent < 0 || intensityPercent > 100 || durationMillis < 50 || durationMillis > 10000)
			throw new IllegalArgumentException("Haptic intensity must be 0–100% and duration 50–10000 ms");
		this.intensityPercent = intensityPercent;
		this.durationMillis = durationMillis;
		this.id = canonicalUuid(id);
		this.enabled = enabled;
		this.sequence = Objects.requireNonNull(sequence, "sequence");
		if (!sequence.isEnabled() && !hapticEnabled)
		{
			throw new IllegalArgumentException("Choose clicks, haptics, or both");
		}
		this.mode = Objects.requireNonNull(mode, "mode");
		this.expression = Objects.requireNonNull(expression, "expression");

		if (expression.trim().isEmpty())
		{
			throw new IllegalArgumentException("Phrase cannot be blank");
		}

		if (expression.length() > MAXIMUM_EXPRESSION_LENGTH)
		{
			throw new IllegalArgumentException(
				"Phrase cannot exceed "
					+ MAXIMUM_EXPRESSION_LENGTH
					+ " characters"
			);
		}

		normalizedLiteral = mode == ClickerPhraseMatchMode.REGEX
			? null
			: expression.toLowerCase(Locale.ROOT);

		regexPattern = mode == ClickerPhraseMatchMode.REGEX
			? Pattern.compile(expression)
			: null;
	}

	public String getId()
	{
		return id;
	}

	public ClickerPhraseRule withValues(
		boolean enabled,
		ClickerPhraseMatchMode mode,
		String expression)
	{
		return new ClickerPhraseRule(id, enabled, sequence, mode, expression,
			hapticEnabled, hapticPattern, intensityPercent, durationMillis);
	}

	public ClickerPhraseRule withValues(
		boolean enabled,
		ClickSequence sequence,
		ClickerPhraseMatchMode mode,
		String expression)
	{
		return new ClickerPhraseRule(id, enabled, sequence, mode, expression,
			hapticEnabled, hapticPattern, intensityPercent, durationMillis);
	}

	public ClickerPhraseRule withFeedback(ClickSequence clicks, boolean haptics, HapticPatternSelection pattern, int intensity, int duration)
	{
		return new ClickerPhraseRule(id, enabled, clicks, mode, expression, haptics, pattern, intensity, duration);
	}

	public boolean isHapticEnabled() { return hapticEnabled; }
	public HapticPatternSelection getHapticPattern() { return hapticPattern; }
	public int getIntensityPercent() { return intensityPercent; }
	public int getDurationMillis() { return durationMillis; }

	public boolean isEnabled()
	{
		return enabled;
	}

	public ClickSequence getSequence()
	{
		return sequence;
	}

	public ClickerPhraseMatchMode getMode()
	{
		return mode;
	}

	public String getExpression()
	{
		return expression;
	}

	public boolean matches(String message)
	{
		if (!enabled || message == null)
		{
			return false;
		}

		switch (mode)
		{
			case PHRASE:
				String text = message.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
				String phrase = normalizedLiteral.trim().replaceAll("\\s+", " ");
				return Pattern.compile("(?<![\\p{L}\\p{N}_])" + Pattern.quote(phrase)
					+ "(?![\\p{L}\\p{N}_])").matcher(text).find();
			case CONTAINS:
				return message.toLowerCase(Locale.ROOT).contains(normalizedLiteral);
			case EXACT:
				return message.toLowerCase(Locale.ROOT).equals(normalizedLiteral);
			case REGEX:
				return regexPattern.matcher(message).find();
			default:
				return false;
		}
	}

	@Override
	public boolean equals(Object other)
	{
		if (this == other)
		{
			return true;
		}

		if (!(other instanceof ClickerPhraseRule))
		{
			return false;
		}

		ClickerPhraseRule that = (ClickerPhraseRule) other;
		return id.equals(that.id)
			&& enabled == that.enabled
			&& sequence == that.sequence
			&& mode == that.mode
			&& expression.equals(that.expression)
			&& hapticEnabled == that.hapticEnabled && hapticPattern.equals(that.hapticPattern)
			&& intensityPercent == that.intensityPercent && durationMillis == that.durationMillis;
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(id, enabled, sequence, mode, expression, hapticEnabled, hapticPattern, intensityPercent, durationMillis);
	}

	@Override
	public String toString()
	{
		String preview = expression
			.replace('\n', ' ')
			.replace('\r', ' ');

		if (preview.length() > 60)
		{
			preview = preview.substring(0, 57) + "...";
		}

		String feedback = sequence.isEnabled() ? sequence.toString() : "";
		if (hapticEnabled) feedback += (feedback.isEmpty() ? "" : " + ") + "Haptics";
		return (enabled ? "" : "(off) ") + preview + " · " + feedback;
	}

	private static String canonicalUuid(String value)
	{
		try
		{
			String canonical = UUID.fromString(Objects.requireNonNull(value, "id")).toString();
			if (!canonical.equals(value.toLowerCase(Locale.ROOT)))
			{
				throw new IllegalArgumentException("Invalid phrase-rule ID");
			}
			return canonical;
		}
		catch (IllegalArgumentException | NullPointerException exception)
		{
			throw new IllegalArgumentException("Invalid phrase-rule ID", exception);
		}
	}
}
