package com.codex.ledger.overlay;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Paint;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;

/**
 * RuneLite draws every overlay with one shared graphics: an overlay that clears the clip, or leaves
 * its font, paint, stroke, transform or hints behind, breaks the overlays drawn after it. The
 * plaque must leave the graphics exactly as it found it.
 */
public class WorthPlaqueTest
{
	@Test
	public void thePlaqueLeavesTheGraphicsAsItFoundThem()
	{
		WorthPlaque plaque = new WorthPlaque();
		plaque.setEnabled(true);
		plaque.show(1_234_567_890L, -3_400_000L);
		BufferedImage image = new BufferedImage(400, 100, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setClip(new Rectangle(5, 5, 300, 60));
		g.setFont(new Font(Font.DIALOG, Font.PLAIN, 11));
		Shape clip = g.getClip();
		Font font = g.getFont();
		Paint paint = g.getPaint();
		Stroke stroke = g.getStroke();
		AffineTransform transform = g.getTransform();
		RenderingHints hints = (RenderingHints) g.getRenderingHints().clone();

		assertNotNull(plaque.render(g));

		assertEquals(clip, g.getClip());
		assertEquals(font, g.getFont());
		assertEquals(paint, g.getPaint());
		assertEquals(stroke, g.getStroke());
		assertEquals(transform, g.getTransform());
		assertEquals(hints, g.getRenderingHints());
		g.dispose();
	}
}
