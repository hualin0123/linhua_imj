package com.linhua.plot;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.Paint;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ImageObserver;
import java.awt.image.RenderedImage;
import java.awt.image.renderable.RenderableImage;
import java.text.AttributedCharacterIterator;
import java.util.HashMap;
import java.util.Map;

/**
 * 极简的 {@link Graphics2D} 实现，将我们实际用到的绘制调用记录为 SVG 文本。
 * 仅用于把图表导出为矢量图（SVG），不追求完整覆盖所有 Graphics2D 语义。
 */
final class SvgGraphics extends Graphics2D {

    private final StringBuilder sb = new StringBuilder();
    private final int W, H;
    private Color color = Color.BLACK;
    private float stroke = 1f;
    private Font font = new Font(PlotUtil.cjkFontFamily(), Font.PLAIN, 12);
    private AffineTransform tx = new AffineTransform();
    private Shape clip = null;
    private int clipDepth = 0;
    private int clipId = 0;

    // 折线合并缓冲：把连续、同款式的 drawLine 合并为一条 <path>，省掉每段重复的样式属性。
    private StringBuilder pathD = null;
    private String pathColor = "";
    private float pathStrokeW = 1f;
    private String pathOpacity = "";
    private double pathLastX = 0, pathLastY = 0;
    private boolean pathOpen = false;

    // 散点分组缓冲：连续、同款式的 ellipse 并入同一个 <g>，子元素只保留坐标，去掉每点的样式属性。
    private StringBuilder ellipseGrp = null;
    private String egKey = "";
    private boolean egOpen = false;

    // 标记(三角/方/菱)分组缓冲：连续、同款式的 polygon 并入同一个 <g>，子元素只保留 points，
    // 透明度逐元素下放（抽稀补偿使每格 alpha 不同）。
    private StringBuilder polyGrp = null;
    private String pgKey = "";
    private boolean pgOpen = false;

    SvgGraphics(int w, int h) {
        W = w;
        H = h;
    }

    String toSvg() {
        flushPath();
        flushEllipseGrp();
        flushPolyGrp();
        StringBuilder out = new StringBuilder();
        out.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        out.append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"").append(W)
           .append("\" height=\"").append(H).append("\" viewBox=\"0 0 ").append(W).append(' ').append(H).append("\">\n");
        out.append("<rect x=\"0\" y=\"0\" width=\"").append(W).append("\" height=\"").append(H).append("\" fill=\"white\"/>\n");
        out.append(sb);
        while (clipDepth-- > 0) {
            out.append("</g>\n");
        }
        out.append("</svg>\n");
        return out.toString();
    }

    private String rgb(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** 颜色自带的 Alpha（透明度）。仅当非完全不透明时输出 opacity 属性，避免污染普通图元。 */
    private String opacityAttr() {
        int a = color.getAlpha();
        return a >= 255 ? "" : String.format(" opacity=\"%.3f\"", a / 255.0);
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }

    private double[] t(double x, double y) {
        Point2D p = tx.transform(new Point2D.Double(x, y), null);
        return new double[]{p.getX(), p.getY()};
    }

    private double rotDeg() {
        return Math.toDegrees(Math.atan2(tx.getShearY(), tx.getScaleX()));
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---- 基础状态 ----
    @Override public void setColor(Color c) { color = c; }
    @Override public Color getColor() { return color; }
    @Override public void setFont(Font f) { font = f; }
    @Override public Font getFont() { return font; }
    @Override public void setPaint(Paint p) { if (p instanceof Color) color = (Color) p; }
    @Override public Paint getPaint() { return color; }
    @Override public void setStroke(Stroke s) { if (s instanceof BasicStroke) stroke = ((BasicStroke) s).getLineWidth(); }
    @Override public Stroke getStroke() { return new BasicStroke(stroke); }
    @Override public void setComposite(Composite c) { }
    @Override public Composite getComposite() { return AlphaComposite.SrcOver; }
    @Override public void setBackground(Color c) { }
    @Override public Color getBackground() { return Color.WHITE; }
    @Override public void setPaintMode() { }
    @Override public void setXORMode(Color c) { }
    @Override public void setRenderingHint(RenderingHints.Key k, Object v) { }
    @Override public Object getRenderingHint(RenderingHints.Key k) { return null; }
    @Override public void setRenderingHints(Map<?, ?> hints) { }
    @Override public void addRenderingHints(Map<?, ?> hints) { }
    @Override public FontRenderContext getFontRenderContext() {
        return new FontRenderContext(new AffineTransform(), false, false);
    }
    @Override public RenderingHints getRenderingHints() { return new RenderingHints(new HashMap<>()); }
    @Override public GraphicsConfiguration getDeviceConfiguration() { return null; }

    @Override public FontMetrics getFontMetrics(Font f) { return new SvgFontMetrics(f); }

    // ---- 坐标变换 ----
    @Override public void translate(int x, int y) { tx.translate(x, y); }
    @Override public void translate(double x, double y) { tx.translate(x, y); }
    @Override public void rotate(double a) { tx.rotate(a); }
    @Override public void rotate(double a, double x, double y) { tx.rotate(a, x, y); }
    @Override public void scale(double sx, double sy) { tx.scale(sx, sy); }
    @Override public void shear(double sx, double sy) { tx.shear(sx, sy); }
    @Override public void transform(AffineTransform a) { tx.concatenate(a); }
    @Override public void setTransform(AffineTransform a) { tx = new AffineTransform(a); }
    @Override public AffineTransform getTransform() { return new AffineTransform(tx); }

    // ---- 裁剪 ----
    @Override public void clip(Shape s) { setClip(s); }
    @Override public void clipRect(int x, int y, int w, int h) { setClip(new Rectangle(x, y, w, h)); }
    @Override public Shape getClip() { return clip; }
    @Override public Rectangle getClipBounds() { return clip == null ? null : clip.getBounds(); }
    @Override public void setClip(int x, int y, int w, int h) { setClip(new Rectangle(x, y, w, h)); }
    @Override public void setClip(Shape s) {
        flushPath();
        flushEllipseGrp();
        flushPolyGrp();
        if (s == null) {
            while (clipDepth > 0) { sb.append("</g>\n"); clipDepth--; }
            clip = null;
            return;
        }
        Rectangle2D r = s.getBounds2D();
        double[] a = t(r.getX(), r.getY());
        double[] b = t(r.getX() + r.getWidth(), r.getY() + r.getHeight());
        double xa = Math.min(a[0], b[0]), ya = Math.min(a[1], b[1]);
        double xb = Math.max(a[0], b[0]), yb = Math.max(a[1], b[1]);
        String id = "clip" + (clipId++);
        sb.append("<defs><clipPath id=\"").append(id).append("\"><rect x=\"").append(fmt(xa))
          .append("\" y=\"").append(fmt(ya)).append("\" width=\"").append(fmt(xb - xa))
          .append("\" height=\"").append(fmt(yb - ya)).append("\"/></clipPath></defs>\n");
        sb.append("<g clip-path=\"url(#").append(id).append(")\">\n");
        clipDepth++;
        clip = s;
    }

    // ---- 图元 ----
    @Override public void drawLine(int x1, int y1, int x2, int y2) {
        if (egOpen) flushEllipseGrp();
        if (pgOpen) flushPolyGrp();
        double[] a = t(x1, y1), b = t(x2, y2);
        String col = rgb(color);
        String op = opacityAttr();
        if (pathOpen) {
            boolean sameStyle = col.equals(pathColor) && pathStrokeW == stroke && op.equals(pathOpacity);
            if (sameStyle) {
                boolean continuous = Math.abs(a[0] - pathLastX) < 0.01 && Math.abs(a[1] - pathLastY) < 0.01;
                if (continuous) {
                    pathD.append('L').append(fmt(b[0])).append(' ').append(fmt(b[1])).append(' ');
                } else {
                    pathD.append('M').append(fmt(a[0])).append(' ').append(fmt(a[1]))
                        .append(' ').append('L').append(fmt(b[0])).append(' ').append(fmt(b[1])).append(' ');
                }
                pathLastX = b[0]; pathLastY = b[1];
                return;
            }
            flushPath();
        }
        pathD = new StringBuilder();
        pathD.append('M').append(fmt(a[0])).append(' ').append(fmt(a[1]))
            .append(' ').append('L').append(fmt(b[0])).append(' ').append(fmt(b[1])).append(' ');
        pathColor = col; pathStrokeW = stroke; pathOpacity = op;
        pathLastX = b[0]; pathLastY = b[1]; pathOpen = true;
    }

    private void flushPath() {
        if (!pathOpen) return;
        sb.append("<path d=\"").append(pathD)
          .append("\" fill=\"none\" stroke=\"").append(pathColor)
          .append("\" stroke-width=\"").append(fmt(pathStrokeW)).append("\"")
          .append(pathOpacity).append("/>\n");
        pathOpen = false;
    }

    @Override public void fillRect(int x, int y, int w, int h) { rect(x, y, w, h, true); }
    @Override public void drawRect(int x, int y, int w, int h) { rect(x, y, w, h, false); }

    @Override public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) { rect(x, y, w, h, true); }
    @Override public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) { rect(x, y, w, h, false); }

    @Override public void drawOval(int x, int y, int w, int h) { ellipse(x, y, w, h, false); }
    @Override public void fillOval(int x, int y, int w, int h) { ellipse(x, y, w, h, true); }
    @Override public void drawArc(int x, int y, int w, int h, int sa, int ea) { }
    @Override public void fillArc(int x, int y, int w, int h, int sa, int ea) { }

    @Override public void fillPolygon(int[] x, int[] y, int n) { poly(x, y, n, true); }
    @Override public void drawPolygon(int[] x, int[] y, int n) { poly(x, y, n, false); }
    @Override public void drawPolyline(int[] x, int[] y, int n) {
        for (int i = 1; i < n; i++) drawLine(x[i - 1], y[i - 1], x[i], y[i]);
    }

    @Override public void drawString(String s, int x, int y) { text(s, x, y); }
    @Override public void drawString(String s, float x, float y) { text(s, x, y); }
    @Override public void drawString(AttributedCharacterIterator iterator, int x, int y) { }
    @Override public void drawString(AttributedCharacterIterator iterator, float x, float y) { }
    @Override public void drawGlyphVector(GlyphVector g, float x, float y) { }

    @Override public void fill(Shape s) {
        if (s instanceof Rectangle2D) {
            Rectangle2D r = (Rectangle2D) s;
            rect(r.getX(), r.getY(), r.getWidth(), r.getHeight(), true);
        } else if (s instanceof Polygon) {
            Polygon p = (Polygon) s;
            poly(p.xpoints, p.ypoints, p.npoints, true);
        }
    }

    @Override public void draw(Shape s) {
        if (s instanceof Line2D) {
            Line2D l = (Line2D) s;
            drawLine((int) l.getX1(), (int) l.getY1(), (int) l.getX2(), (int) l.getY2());
        } else if (s instanceof Rectangle2D) {
            Rectangle2D r = (Rectangle2D) s;
            rect(r.getX(), r.getY(), r.getWidth(), r.getHeight(), false);
        } else if (s instanceof Polygon) {
            Polygon p = (Polygon) s;
            poly(p.xpoints, p.ypoints, p.npoints, false);
        }
    }

    // ---- 未用到的光栅/高级接口：置空 ----
    @Override public boolean drawImage(Image img, int x, int y, ImageObserver o) { return false; }
    @Override public boolean drawImage(Image img, int x, int y, int w, int h, ImageObserver o) { return false; }
    @Override public boolean drawImage(Image img, int x, int y, Color bg, ImageObserver o) { return false; }
    @Override public boolean drawImage(Image img, int x, int y, int w, int h, Color bg, ImageObserver o) { return false; }
    @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver o) { return false; }
    @Override public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, Color bg, ImageObserver o) { return false; }
    @Override public void drawRenderedImage(RenderedImage img, AffineTransform xform) { }
    @Override public void drawRenderableImage(RenderableImage img, AffineTransform xform) { }
    @Override public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) { }
    @Override public boolean drawImage(Image img, AffineTransform xform, ImageObserver obs) { return false; }
    @Override public boolean hit(Rectangle rect, Shape s, boolean onStroke) { return false; }
    @Override public void clearRect(int x, int y, int w, int h) { }
    @Override public void copyArea(int x, int y, int w, int h, int dx, int dy) { }
    @Override public void dispose() { }
    @Override public Graphics create() {
        SvgGraphics c = new SvgGraphics(W, H);
        c.color = color; c.stroke = stroke; c.font = font;
        c.tx = new AffineTransform(tx); c.clip = clip; c.clipDepth = clipDepth; c.clipId = clipId;
        c.pathD = pathD; c.pathColor = pathColor; c.pathStrokeW = pathStrokeW;
        c.pathOpacity = pathOpacity; c.pathLastX = pathLastX; c.pathLastY = pathLastY; c.pathOpen = pathOpen;
        c.ellipseGrp = ellipseGrp; c.egKey = egKey; c.egOpen = egOpen;
        c.polyGrp = polyGrp; c.pgKey = pgKey; c.pgOpen = pgOpen;
        return c;
    }

    // ---- 内部图元输出 ----
    private void rect(double x, double y, double w, double h, boolean fill) {
        flushPath();
        flushEllipseGrp();
        flushPolyGrp();
        double[] p = t(x, y), q = t(x + w, y + h);
        double xa = Math.min(p[0], q[0]), ya = Math.min(p[1], q[1]);
        double xb = Math.max(p[0], q[0]), yb = Math.max(p[1], q[1]);
        if (fill) {
            sb.append("<rect x=\"").append(fmt(xa)).append("\" y=\"").append(fmt(ya))
              .append("\" width=\"").append(fmt(xb - xa)).append("\" height=\"").append(fmt(yb - ya))
              .append("\" fill=\"").append(rgb(color)).append("\"")
              .append(opacityAttr()).append("/>\n");
        } else {
            sb.append("<rect x=\"").append(fmt(xa)).append("\" y=\"").append(fmt(ya))
              .append("\" width=\"").append(fmt(xb - xa)).append("\" height=\"").append(fmt(yb - ya))
              .append("\" fill=\"none\" stroke=\"").append(rgb(color))
              .append("\" stroke-width=\"").append(fmt(stroke)).append("\"")
              .append(opacityAttr()).append("/>\n");
        }
    }

    private void ellipse(double x, double y, double w, double h, boolean fill) {
        if (pathOpen) flushPath();
        if (pgOpen) flushPolyGrp();
        double[] c = t(x + w / 2, y + h / 2);
        double sx = Math.hypot(tx.getScaleX(), tx.getShearY());
        double sy = Math.hypot(tx.getShearX(), tx.getScaleY());
        double rx = Math.abs(w / 2) * sx, ry = Math.abs(h / 2) * sy;
        String op = opacityAttr();
        // 注意：opacity 不能放在组级（组级 opacity 会先把组内元素合成到离屏缓冲、再整体乘一次，
        // 破坏半透明重叠的"叠加累计"）。因此组只持有 fill/stroke 去做样式去重，
        // 透明度下放到每个 <ellipse>，使其各自合成、正确叠加。
        String key = (fill ? "F|" : "S|") + rgb(color) + "|" + fmt(stroke)
                   + "|" + fmt(rx) + "|" + fmt(ry);
        if (egOpen && key.equals(egKey)) {
            ellipseGrp.append("<ellipse cx=\"").append(fmt(c[0])).append("\" cy=\"").append(fmt(c[1]))
                      .append("\" rx=\"").append(fmt(rx)).append("\" ry=\"").append(fmt(ry)).append("\"")
                      .append(op).append("/>\n");
            return;
        }
        flushEllipseGrp();
        sb.append("<g ");
        if (fill) sb.append("fill=\"").append(rgb(color)).append("\"");
        else sb.append("fill=\"none\" stroke=\"").append(rgb(color)).append("\" stroke-width=\"").append(fmt(stroke)).append("\"");
        sb.append(">\n");
        ellipseGrp = new StringBuilder();
        ellipseGrp.append("<ellipse cx=\"").append(fmt(c[0])).append("\" cy=\"").append(fmt(c[1]))
                  .append("\" rx=\"").append(fmt(rx)).append("\" ry=\"").append(fmt(ry)).append("\"")
                  .append(op).append("/>\n");
        egKey = key; egOpen = true;
    }

    private void flushEllipseGrp() {
        if (!egOpen) return;
        sb.append(ellipseGrp).append("</g>\n");
        egOpen = false;
    }

    private void poly(int[] x, int[] y, int n, boolean fill) {
        if (pathOpen) flushPath();
        if (egOpen) flushEllipseGrp();
        StringBuilder pts = new StringBuilder();
        for (int i = 0; i < n; i++) {
            double[] p = t(x[i], y[i]);
            pts.append(fmt(p[0])).append(',').append(fmt(p[1])).append(' ');
        }
        String op = opacityAttr();
        // 透明度因抽稀补偿逐格不同，下放每个 <polygon>；组只持有 fill/stroke 去重
        String key = (fill ? "F|" : "S|") + rgb(color) + "|" + fmt(stroke);
        if (pgOpen && key.equals(pgKey)) {
            polyGrp.append("<polygon points=\"").append(pts).append("\"")
                   .append(op).append("/>\n");
            return;
        }
        flushPolyGrp();
        sb.append("<g ");
        if (fill) sb.append("fill=\"").append(rgb(color)).append("\"");
        else sb.append("fill=\"none\" stroke=\"").append(rgb(color)).append("\" stroke-width=\"").append(fmt(stroke)).append("\"");
        sb.append(">\n");
        polyGrp = new StringBuilder();
        polyGrp.append("<polygon points=\"").append(pts).append("\"")
               .append(op).append("/>\n");
        pgKey = key; pgOpen = true;
    }

    private void flushPolyGrp() {
        if (!pgOpen) return;
        sb.append(polyGrp).append("</g>\n");
        pgOpen = false;
    }

    private void text(String s, double x, double y) {
        flushPath();
        flushEllipseGrp();
        flushPolyGrp();
        double[] p = t(x, y);
        double deg = rotDeg();
        if (Math.abs(deg) > 0.01) {
            sb.append("<text x=\"").append(fmt(p[0])).append("\" y=\"").append(fmt(p[1]))
              .append("\" font-size=\"").append(font.getSize())
              .append("\" fill=\"").append(rgb(color)).append("\"")
              .append(opacityAttr())
              .append(" transform=\"rotate(").append(fmt(deg)).append(' ').append(fmt(p[0])).append(' ').append(fmt(p[1]))
              .append(")\">").append(esc(s)).append("</text>\n");
        } else {
            sb.append("<text x=\"").append(fmt(p[0])).append("\" y=\"").append(fmt(p[1]))
              .append("\" font-size=\"").append(font.getSize())
              .append("\" fill=\"").append(rgb(color)).append("\"")
              .append(opacityAttr()).append(">").append(esc(s)).append("</text>\n");
        }
    }

    /** SVG 用的近似字体度量（仅用于文字居中定位）。 */
    private static final class SvgFontMetrics extends FontMetrics {
        SvgFontMetrics(Font f) { super(f); }
        @Override public int stringWidth(String s) { return (int) (s.length() * font.getSize() * 0.55); }
        @Override public int getAscent() { return (int) (font.getSize() * 0.8); }
        @Override public int getDescent() { return (int) (font.getSize() * 0.2); }
        @Override public int getHeight() { return font.getSize(); }
    }
}
