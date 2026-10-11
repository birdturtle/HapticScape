package com.ashy0019.hapticscape.ui;

import com.ashy0019.hapticscape.Level99CelebrationController;
import com.ashy0019.hapticscape.Level99Ceremony;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Random;

public final class Level99CelebrationRenderer
{
	private static final int WIDTH = 520;
	private static final int HEIGHT = 250;
	private static final int CARD_X = 80;
	private static final int CARD_Y = 18;
	private static final int CARD_WIDTH = 360;
	private static final int CARD_HEIGHT = 188;
	private static final int CARD_CENTER_X = CARD_X + CARD_WIDTH / 2;
	private static final int CONFETTI_COUNT = 99;
	private static final Color GOLD = new Color(255, 174, 0);
	private static final Color LIGHT_GOLD = new Color(255, 225, 128);
	private static final Color[] MARQUEE_COLORS =
{
    new Color(255, 70, 70),    // red
    new Color(255, 170, 50),   // orange
    new Color(255, 235, 70),   // yellow
    new Color(80, 225, 110),   // green
    new Color(65, 180, 255),   // blue
    new Color(175, 90, 255),   // purple
    new Color(255, 90, 190)    // pink
};

private static final long MARQUEE_STEP_NANOS = 90_000_000L;
private static final int MARQUEE_OFFSET = 11;
private static final int MARQUEE_SPACING = 22;
	private static final Color[] CONFETTI_COLORS =
	{
		new Color(255, 174, 0),
		new Color(255, 225, 128),
		new Color(236, 64, 122),
		new Color(41, 182, 246),
		new Color(102, 187, 106),
		new Color(171, 71, 188)
	};
	private static final ConfettiParticle[] CONFETTI = createConfetti();

	private final Level99CelebrationController controller;

	public Level99CelebrationRenderer(Level99CelebrationController controller)
	{
		this.controller = controller;
	}

	public static Dimension getRenderSize()
	{
		return new Dimension(WIDTH, HEIGHT);
	}

	public Dimension render(Graphics2D graphics)
	{
		Level99CelebrationController.Snapshot snapshot = controller.snapshot();
		if (!snapshot.isActive())
		{
			return null;
		}

		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setComposite(AlphaComposite.SrcOver);

			float pulse = (float) snapshot.getPulseIntensity();

			drawMarqueeLights(
					g,
					snapshot.getElapsedNanos(),
					pulse
			);

			g.setColor(new Color(20, 14, 8));
			g.fillRoundRect(CARD_X, CARD_Y, CARD_WIDTH, CARD_HEIGHT, 22, 22);

			g.setStroke(new BasicStroke(3.0f));
			g.setColor(GOLD);
			g.drawRoundRect(
				CARD_X + 2,
				CARD_Y + 2,
				CARD_WIDTH - 5,
				CARD_HEIGHT - 5,
				20,
				20
			);
			drawConfetti(g, snapshot.getElapsedNanos());

			Font base = graphics.getFont();

			drawCentered(g, "LEVEL 99", base.deriveFont(Font.BOLD, 32f), LIGHT_GOLD, 60);
			drawCentered(
				g,
				snapshot.getSkill().getDisplayName().toUpperCase(),
				base.deriveFont(Font.BOLD, 24f),
				Color.WHITE,
				94
			);
			drawCentered(g, "MASTERY ACHIEVED", base.deriveFont(Font.BOLD, 18f), GOLD, 126);
			drawCentered(g, "CONGRATULATIONS,", base.deriveFont(Font.PLAIN, 15f), Color.WHITE, 158);
			drawCentered(
				g,
				"YOUR BUTT HAS ACHIEVED MASTERY.",
				base.deriveFont(Font.BOLD, 15f),
				LIGHT_GOLD,
				180
			);

			int progressWidth = (int) Math.round((CARD_WIDTH - 36) * snapshot.getProgress());
			g.setColor(new Color(255, 174, 0, 90));
			g.fillRoundRect(CARD_X + 18, CARD_Y + CARD_HEIGHT - 12, progressWidth, 4, 4, 4);
		}
		finally
		{
			g.dispose();
		}
		return new Dimension(WIDTH, HEIGHT);
	}

	private static void drawMarqueeLights(
		Graphics2D graphics,
		long elapsedNanos,
		float pulse)
	{
		int left = CARD_X - MARQUEE_OFFSET;
		int right = CARD_X + CARD_WIDTH + MARQUEE_OFFSET;
		int top = CARD_Y - MARQUEE_OFFSET;
		int bottom = CARD_Y + CARD_HEIGHT + MARQUEE_OFFSET;

		int colorPhase = (int) (
			(elapsedNanos / MARQUEE_STEP_NANOS)
				% MARQUEE_COLORS.length
		);

		int chasePhase = (int) (
			(elapsedNanos / MARQUEE_STEP_NANOS)
				% 5
		);

		int index = 0;

		// Top edge: left to right.
		for (int x = left + 14; x <= right - 14; x += MARQUEE_SPACING)
		{
			drawMarqueeBulb(
				graphics,
				x,
				top,
				index++,
				colorPhase,
				chasePhase,
				pulse
			);
		}

		// Right edge: top to bottom.
		for (int y = top + MARQUEE_SPACING;
			y <= bottom - MARQUEE_SPACING;
			y += MARQUEE_SPACING)
		{
			drawMarqueeBulb(
				graphics,
				right,
				y,
				index++,
				colorPhase,
				chasePhase,
				pulse
			);
		}

		// Bottom edge: right to left.
		for (int x = right - 14; x >= left + 14; x -= MARQUEE_SPACING)
		{
			drawMarqueeBulb(
				graphics,
				x,
				bottom,
				index++,
				colorPhase,
				chasePhase,
				pulse
			);
		}

		// Left edge: bottom to top.
		for (int y = bottom - MARQUEE_SPACING;
			y >= top + MARQUEE_SPACING;
			y -= MARQUEE_SPACING)
		{
			drawMarqueeBulb(
				graphics,
				left,
				y,
				index++,
				colorPhase,
				chasePhase,
				pulse
			);
		}
	}

	private static void drawMarqueeBulb(
		Graphics2D graphics,
		int x,
		int y,
		int index,
		int colorPhase,
		int chasePhase,
		float pulse)
	{
		Color color = MARQUEE_COLORS[
			(index + colorPhase) % MARQUEE_COLORS.length
		];

		boolean hot = ((index - chasePhase + 100) % 5) == 0;
		float boundedPulse = Math.max(0.0f, Math.min(1.0f, pulse));

		int glowAlpha =
			(hot ? 105 : 50)
				+ Math.round(65.0f * boundedPulse);
		glowAlpha = Math.min(200, glowAlpha);

		int glowSize =
			(hot ? 22 : 18)
				+ Math.round(4.0f * boundedPulse);
		int glowRadius = glowSize / 2;

		// Colored halo.
		graphics.setColor(new Color(
			color.getRed(),
			color.getGreen(),
			color.getBlue(),
			glowAlpha
		));
		graphics.fillOval(
			x - glowRadius,
			y - glowRadius,
			glowSize,
			glowSize
		);

		// Dark socket.
		graphics.setColor(new Color(24, 17, 11, 235));
		graphics.fillOval(x - 7, y - 7, 14, 14);

		// Main colored bulb.
		int bulbAlpha = hot ? 255 : 215;
		graphics.setColor(new Color(
			color.getRed(),
			color.getGreen(),
			color.getBlue(),
			bulbAlpha
		));
		graphics.fillOval(x - 5, y - 5, 10, 10);

		// Small white glass highlight.
		graphics.setColor(new Color(
			255,
			255,
			255,
			hot ? 235 : 155
		));
		graphics.fillOval(x - 3, y - 4, 4, 4);
	}

	private static void drawCentered(
		Graphics2D graphics,
		String text,
		Font font,
		Color color,
		int baseline)
	{
		graphics.setFont(font);
		graphics.setColor(color);
		FontMetrics metrics = graphics.getFontMetrics(font);
		graphics.drawString(text, CARD_CENTER_X - metrics.stringWidth(text) / 2, baseline);
	}

	private static void drawConfetti(Graphics2D graphics, long elapsedNanos)
	{
		long phraseElapsedNanos = elapsedNanos % Level99Ceremony.phraseDurationNanos();
		double elapsedSeconds = phraseElapsedNanos / 1_000_000_000.0;
		for (ConfettiParticle particle : CONFETTI)
		{
			double age = elapsedSeconds - particle.delaySeconds;
			if (age < 0.0 || age > particle.lifetimeSeconds)
			{
				continue;
			}

			double x = particle.startX + particle.direction * particle.horizontalSpeed * age;
			double y = particle.startY
				- particle.verticalSpeed * age
				+ 0.5 * particle.gravity * age * age;
			double fade = Math.min(1.0, (particle.lifetimeSeconds - age) / 0.45);
			int alpha = (int) Math.round(235 * Math.max(0.0, fade));

			Graphics2D piece = (Graphics2D) graphics.create();
			try
			{
				piece.translate(x, y);
				piece.rotate(particle.startAngle + particle.angularSpeed * age);
				Color color = particle.color;
				piece.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
				piece.fillRect(
					-particle.width / 2,
					-particle.height / 2,
					particle.width,
					particle.height
				);
			}
			finally
			{
				piece.dispose();
			}
		}
	}

	private static ConfettiParticle[] createConfetti()
	{
		Random random = new Random(99L);
		ConfettiParticle[] particles = new ConfettiParticle[CONFETTI_COUNT];
		for (int index = 0; index < particles.length; index++)
		{
			boolean fromLeft = index % 2 == 0;
			particles[index] = new ConfettiParticle(
				fromLeft ? CARD_X - 3 : CARD_X + CARD_WIDTH + 3,
				CARD_Y + CARD_HEIGHT - 24 + random.nextInt(21) - 10,
				fromLeft ? 1.0 : -1.0,
				75.0 + random.nextDouble() * 125.0,
				170.0 + random.nextDouble() * 150.0,
				235.0 + random.nextDouble() * 100.0,
				random.nextDouble() * 0.18,
				0.90 + random.nextDouble() * 0.18,
				random.nextDouble() * Math.PI,
				(random.nextDouble() - 0.5) * 11.0,
				4 + random.nextInt(5),
				3 + random.nextInt(6),
				CONFETTI_COLORS[random.nextInt(CONFETTI_COLORS.length)]
			);
		}
		return particles;
	}

	private static Color blend(Color from, Color to, float amount)
	{
		float bounded = Math.max(0.0f, Math.min(1.0f, amount));
		return new Color(
			Math.round(from.getRed() + (to.getRed() - from.getRed()) * bounded),
			Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * bounded),
			Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * bounded)
		);
	}

	private static final class ConfettiParticle
	{
		private final double startX;
		private final double startY;
		private final double direction;
		private final double horizontalSpeed;
		private final double verticalSpeed;
		private final double gravity;
		private final double delaySeconds;
		private final double lifetimeSeconds;
		private final double startAngle;
		private final double angularSpeed;
		private final int width;
		private final int height;
		private final Color color;

		private ConfettiParticle(
			double startX,
			double startY,
			double direction,
			double horizontalSpeed,
			double verticalSpeed,
			double gravity,
			double delaySeconds,
			double lifetimeSeconds,
			double startAngle,
			double angularSpeed,
			int width,
			int height,
			Color color)
		{
			this.startX = startX;
			this.startY = startY;
			this.direction = direction;
			this.horizontalSpeed = horizontalSpeed;
			this.verticalSpeed = verticalSpeed;
			this.gravity = gravity;
			this.delaySeconds = delaySeconds;
			this.lifetimeSeconds = lifetimeSeconds;
			this.startAngle = startAngle;
			this.angularSpeed = angularSpeed;
			this.width = width;
			this.height = height;
			this.color = color;
		}
	}
}
