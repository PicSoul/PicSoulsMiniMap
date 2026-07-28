package net.picsoul.rw.minimap.render;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

/**
 * Generates the player position marker: a teardrop/pointer whose pointed tip
 * indicates the facing direction. Drawn pointing "up" (north); the HUD rotates
 * it to the player's heading. Generated once and reused.
 */
public final class MarkerTexture {

    private MarkerTexture() {
    }

    /** @return PNG bytes of a teardrop pointer (tip up), or null on failure. */
    public static byte[] teardrop(int size) {
        try {
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            float s = size;
            float cx = s * 0.5f;
            GeneralPath p = new GeneralPath();
            p.moveTo(cx, s * 0.06f);                       // sharp tip (points up = facing)
            p.quadTo(s * 0.94f, s * 0.46f, s * 0.70f, s * 0.80f);
            p.quadTo(cx, s * 0.99f, s * 0.30f, s * 0.80f);
            p.quadTo(s * 0.06f, s * 0.46f, cx, s * 0.06f);
            p.closePath();

            g.setColor(new Color(255, 216, 51, 255)); // bright amber fill
            g.fill(p);
            g.setStroke(new BasicStroke(Math.max(1.5f, s * 0.06f)));
            g.setColor(new Color(20, 20, 20, 230));    // dark outline
            g.draw(p);

            float r = s * 0.13f;
            g.setColor(new Color(30, 30, 30, 200));
            g.fillOval((int) (cx - r), (int) (s * 0.55f - r), (int) (2 * r), (int) (2 * r));

            g.dispose();

            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            System.out.println("[PicSoulsMiniMap] marker texture failed: " + e.getMessage());
            return null;
        }
    }
}
