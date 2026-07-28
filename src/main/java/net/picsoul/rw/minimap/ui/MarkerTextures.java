package net.picsoul.rw.minimap.ui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;

/**
 * Generates the 8 waypoint marker icons (and the spawn diamond) as PNG textures,
 * drawn once with Java2D. Each icon is a <b>white</b> shape over a <b>dark
 * halo</b> on a transparent background, so it can be tinted to the marker's color
 * at runtime via {@code backgroundImageTintColor} (white*tint = tint color; the
 * dark halo stays dark). Rendering markers as textured child elements — instead
 * of redrawing a {@code UIPainter2D} every frame — avoids the per-frame vector
 * re-tessellation that caused marker flicker.
 *
 * <p>iconId order matches the game's marker picker:
 * 0=cross 1=ring 2=down-arrow 3=house 4=exclamation 5=question 6=dot 7=hatched-square.
 */
public final class MarkerTextures {

    public static final int COUNT = 8;
    private static final int SZ = 64;
    private static final float C = 32f;   // center
    private static final float S = 20f;   // shape radius
    private static final float HALO = 9f; // dark halo stroke width
    private static final float LINE = 4.5f; // white line width
    private static final Color DARK = new Color(0, 0, 0, 205);
    private static final Color WHITE = Color.WHITE;

    private MarkerTextures() {
    }

    /** PNG bytes for waypoint icon {@code id} (0..7); falls back to a dot. */
    public static byte[] icon(int id) {
        return switch (id) {
            case 0 -> cross();
            case 1 -> ring();
            case 2 -> downArrow();
            case 3 -> house();
            case 4 -> exclamation();
            case 5 -> question();
            case 6 -> dot();
            case 7 -> hatchedSquare();
            default -> dot();
        };
    }

    private static Graphics2D start(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        return g;
    }

    private static byte[] finish(BufferedImage img, Graphics2D g) {
        g.dispose();
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream(2048);
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            System.out.println("[PicSoulsMiniMap] marker texture encode failed: " + e.getMessage());
            return null;
        }
    }

    /** Stroke a shape as dark halo then white line. */
    private static void strokeShape(Graphics2D g, java.awt.Shape shape) {
        g.setStroke(new BasicStroke(HALO, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(DARK);
        g.draw(shape);
        g.setStroke(new BasicStroke(LINE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(WHITE);
        g.draw(shape);
    }

    private static void fillDot(Graphics2D g, float cx, float cy, float rad) {
        g.setColor(DARK);
        g.fill(new Ellipse2D.Float(cx - rad - 2.5f, cy - rad - 2.5f, 2 * (rad + 2.5f), 2 * (rad + 2.5f)));
        g.setColor(WHITE);
        g.fill(new Ellipse2D.Float(cx - rad, cy - rad, 2 * rad, 2 * rad));
    }

    private static BufferedImage blank() {
        return new BufferedImage(SZ, SZ, BufferedImage.TYPE_INT_ARGB);
    }

    private static byte[] cross() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        GeneralPath p = new GeneralPath();
        p.moveTo(C - S, C - S); p.lineTo(C + S, C + S);
        p.moveTo(C - S, C + S); p.lineTo(C + S, C - S);
        strokeShape(g, p);
        return finish(img, g);
    }

    private static byte[] ring() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        strokeShape(g, new Ellipse2D.Float(C - S * 0.9f, C - S * 0.9f, 2 * S * 0.9f, 2 * S * 0.9f));
        return finish(img, g);
    }

    private static byte[] downArrow() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        float hw = S * 0.62f, hh = S * 0.72f;
        GeneralPath p = new GeneralPath();
        p.moveTo(C, C - S); p.lineTo(C, C + S);
        p.moveTo(C - hw, C + S - hh); p.lineTo(C, C + S); p.lineTo(C + hw, C + S - hh);
        strokeShape(g, p);
        return finish(img, g);
    }

    private static byte[] house() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        float wall = S * 0.72f, eave = S * 0.95f, roofY = C - S * 0.15f, botY = C + S;
        GeneralPath p = new GeneralPath();
        p.moveTo(C, C - S);
        p.lineTo(C + eave, roofY);
        p.lineTo(C + wall, roofY);
        p.lineTo(C + wall, botY);
        p.lineTo(C - wall, botY);
        p.lineTo(C - wall, roofY);
        p.lineTo(C - eave, roofY);
        p.closePath();
        strokeShape(g, p);
        return finish(img, g);
    }

    private static byte[] exclamation() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        GeneralPath p = new GeneralPath();
        p.moveTo(C, C - S); p.lineTo(C, C + S * 0.35f);
        strokeShape(g, p);
        fillDot(g, C, C + S * 0.82f, 3.0f);
        return finish(img, g);
    }

    private static byte[] question() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        // Top hook: an open arc over the top, sweeping from lower-left around to the
        // right and down (Java2D angles are CCW with y-up, so negative extent = CW
        // on screen). Then a short stem to the centre, then the dot.
        float r = S * 0.5f, ax = C - r, ay = C - S * 0.35f - r;
        Arc2D arc = new Arc2D.Float(ax, ay, 2 * r, 2 * r, 160f, -250f, Arc2D.OPEN);
        GeneralPath p = new GeneralPath();
        p.append(arc, false);
        p.lineTo(C, C + S * 0.15f);
        strokeShape(g, p);
        fillDot(g, C, C + S * 0.78f, 3.0f);
        return finish(img, g);
    }

    private static byte[] dot() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        fillDot(g, C, C, S * 0.72f);
        return finish(img, g);
    }

    private static byte[] hatchedSquare() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        GeneralPath p = new GeneralPath();
        p.moveTo(C - S, C - S); p.lineTo(C + S, C - S);
        p.lineTo(C + S, C + S); p.lineTo(C - S, C + S); p.closePath();
        p.moveTo(C - S, C); p.lineTo(C, C - S);
        p.moveTo(C - S, C + S); p.lineTo(C + S, C - S);
        p.moveTo(C, C + S); p.lineTo(C + S, C);
        strokeShape(g, p);
        return finish(img, g);
    }

    /** PNG bytes for the spawn marker (a diamond). */
    public static byte[] spawnDiamond() {
        BufferedImage img = blank();
        Graphics2D g = start(img);
        float s = S * 0.85f;
        GeneralPath p = new GeneralPath();
        p.moveTo(C, C - s); p.lineTo(C + s, C); p.lineTo(C, C + s); p.lineTo(C - s, C); p.closePath();
        g.setColor(DARK);
        g.setStroke(new BasicStroke(HALO, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
        g.setColor(WHITE);
        g.fill(p);
        return finish(img, g);
    }
}
