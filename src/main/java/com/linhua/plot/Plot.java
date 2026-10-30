package com.linhua.plot;

import ij.IJ;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.Box;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;

/**
 * 轻量自绘图表组件，模仿 Matplotlib 的工具栏体验：
 * <ul>
 *   <li>复原 (Home)：恢复到初始坐标轴范围；</li>
 *   <li>缩放 (Zoom)：拖拽框选放大；</li>
 *   <li>平移 (Pan)：拖拽平移；</li>
 *   <li>存储图形 (Save)：导出高分辨率 PNG 或矢量图 SVG。</li>
 * </ul>
 * 直接使用 AWT/Swing 自绘，<b>不依赖</b> {@code ij.gui.Plot}，因此 ImageJ 原 Plot 窗口
 * 底部的 {@code List}/{@code Data}/{@code More} 选项卡不会出现。
 *
 * <p>样式可通过 setter 覆盖（坐标轴字体、刻度小数位、刻度朝向、网格、边框、图例位置/字号等），
 * 默认观感与原有 Beam Pointing 一致；M2 等模块可借此完整复刻 Matplotlib 出图格式。</p>
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public class Plot extends JPanel {

    /** 线型序列。 */
    public static final int LINE = 0;
    /** 散点序列。 */
    public static final int SCATTER = 1;
    /** 数据坐标系下的填充矩形（用于密度热力图）。 */
    public static final int RECT = 2;
    /** 数据坐标系下的空心圆（用于 RMS 偏移圈等参考圈）。 */
    public static final int CIRCLE = 3;
    /** 散点标记：圆形。 */
    public static final int MARKER_CIRCLE = 0;
    /** 散点标记：上三角。 */
    public static final int MARKER_TRIANGLE = 1;
    /** 散点标记：方形。 */
    public static final int MARKER_SQUARE = 2;
    /** 散点标记：菱形。 */
    public static final int MARKER_DIAMOND = 3;

    /** 图例位置。 */
    public static final int LEGEND_UPPER_LEFT = 0;
    public static final int LEGEND_UPPER_RIGHT = 1;
    public static final int LEGEND_LOWER_LEFT = 2;
    public static final int LEGEND_LOWER_RIGHT = 3;

    /** 工具栏图标：复原（home）。 */
    private static final int ICON_HOME = 0;
    /** 工具栏图标：缩放。 */
    private static final int ICON_ZOOM = 1;
    /** 工具栏图标：平移（手形）。 */
    private static final int ICON_PAN = 2;
    /** 工具栏图标：存储图形（软盘）。 */
    private static final int ICON_SAVE = 3;

    private static final int NONE = 0;
    private static final int ZOOM = 1;
    private static final int PAN = 2;

    /** 单条数据序列。 */
    private static class Series {
        final double[] x;
        final double[] y;
        final Color color;
        final String label;
        final int type;
        final float lineWidth;
        final int marker;
        final boolean hollow;
        final float markerSize;
        final float markerEdge;
        double rw = 0, rh = 0; // RECT 类型的宽高（数据坐标）

        Series(double[] x, double[] y, Color color, String label, int type) {
            this(x, y, color, label, type, 1.6f, MARKER_CIRCLE, false, 3f, 1.2f);
        }

        /** RECT 专用构造：以 (x,y) 为左下角、宽 rw 高 rh（数据坐标）。 */
        Series(double x, double y, double w, double h, Color color, int type) {
            this.x = new double[]{x};
            this.y = new double[]{y};
            this.color = color;
            this.label = null;
            this.type = type;
            this.lineWidth = 1.6f;
            this.marker = MARKER_CIRCLE;
            this.hollow = false;
            this.markerSize = 3f;
            this.markerEdge = 1.2f;
            this.rw = w;
            this.rh = h;
        }

        Series(double[] x, double[] y, Color color, String label, int type,
               float lineWidth, int marker, boolean hollow, float markerSize, float markerEdge) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.label = label;
            this.type = type;
            this.lineWidth = lineWidth;
            this.marker = marker;
            this.hollow = hollow;
            this.markerSize = markerSize;
            this.markerEdge = markerEdge;
        }
    }

    private final List<Series> series = new ArrayList<>();
    private final String title;
    private String xLabel;
    private String yLabel;

    private boolean legend = false;
    private boolean originCross = false;
    private String annotation = null;
    private String northAnnotation = null;

    // 坐标轴/字体样式（默认保持原有观感；M2 等可按需覆盖以复刻 matplotlib）
    private Font labelFont = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
    private Font tickFont = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    private boolean tickBold = false;
    private Integer xTicksDecimals = null;
    private Integer yTicksDecimals = null;
    private boolean ticksInward = false;
    private boolean grid = true;
    private boolean box = false;
    private boolean drawTitle = true;
    private int legendLoc = LEGEND_UPPER_LEFT;
    private boolean legendFrame = false;
    private float legendFontPt = 11f;
    private Double userXMin = null, userXMax = null, userYMin = null, userYMax = null;

    // 当前坐标轴范围
    private double xMin, xMax, yMin, yMax;
    // 初始（复原用）坐标轴范围
    private double hxMin, hxMax, hyMin, hyMax;
    private boolean rangeSet = false;

    // 边距
    private int leftMargin = 70;
    private int rightMargin = 18;
    private int topMargin = 18;
    private int bottomMargin = 56;

    private int mode = NONE;
    private Point dragStart = null;
    private Point dragPrev = null;
    private Rectangle zoomRect = null;

    private final Canvas canvas = new Canvas();

    /**
     * @param title  图形标题（可为 null）
     * @param xLabel X 轴标签（可为 null）
     * @param yLabel Y 轴标签（可为 null）
     */
    public Plot(String title, String xLabel, String yLabel) {
        this.title = title;
        this.xLabel = xLabel;
        this.yLabel = yLabel;
        setLayout(new BorderLayout());
        add(buildToolBar(), BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);
    }

    /** 添加一条线型序列。 */
    public void addLine(double[] x, double[] y, Color color, String label) {
        series.add(new Series(x, y, color, label, LINE));
    }

    /** 添加一条线型序列（可指定线宽）。 */
    public void addLine(double[] x, double[] y, Color color, String label, float lineWidth) {
        series.add(new Series(x, y, color, label, LINE, lineWidth, MARKER_CIRCLE, false, 3f, 1.2f));
    }

    /** 添加一组散点。 */
    public void addScatter(double[] x, double[] y, Color color, String label) {
        series.add(new Series(x, y, color, label, SCATTER));
    }

    /** 添加一组散点（可指定标记形状、空心/实心、大小与边宽）。 */
    public void addScatter(double[] x, double[] y, Color color, String label,
                           int marker, boolean hollow, float markerSize, float markerEdge) {
        series.add(new Series(x, y, color, label, SCATTER, 1.6f, marker, hollow, markerSize, markerEdge));
    }

    /** 添加一个数据坐标系下的填充矩形（用于密度热力图）。(x,y)=左下角，w/h 为宽高。 */
    public void addRect(double x, double y, double w, double h, Color c) {
        series.add(new Series(x, y, w, h, c, RECT));
    }

    /** 添加一个数据坐标系下的空心圆（参考圈，如 RMS 偏移）。(cx,cy)=圆心，r=半径。 */
    public void addCircle(double cx, double cy, double r, Color c) {
        series.add(new Series(cx, cy, r, 0, c, CIRCLE));
    }

    /** 是否绘制图例。 */
    public void setLegend(boolean b) {
        this.legend = b;
    }

    /** 是否在 (0,0) 处绘制参考十字（散点图常用）。 */
    public void setOriginCross(boolean b) {
        this.originCross = b;
    }

    /** 在图形左上角添加一行注释文字（如 RMS 偏移量）。 */
    public void setAnnotation(String text) {
        this.annotation = text;
    }

    /** 在图形顶部中央添加一行注释（如波长 λ，对应 Python 的第二图例）。 */
    public void setNorthAnnotation(String text) {
        this.northAnnotation = text;
    }

    public void setFontFamily(String family) {
        labelFont = new Font(family, labelFont.getStyle(), labelFont.getSize());
        tickFont = new Font(family, tickFont.getStyle(), tickFont.getSize());
    }

    public void setLabelFontSize(float pt) { labelFont = labelFont.deriveFont(pt); }
    public void setTickFontSize(float pt) { tickFont = tickFont.deriveFont(pt); }
    public void setTickBold(boolean b) { tickBold = b; tickFont = tickFont.deriveFont(b ? Font.BOLD : Font.PLAIN); }
    public void setXTicksDecimals(int n) { xTicksDecimals = n; }
    public void setYTicksDecimals(int n) { yTicksDecimals = n; }
    public void setTicksInward(boolean b) { ticksInward = b; }
    public void setGrid(boolean b) { grid = b; }
    public void setBox(boolean b) { box = b; }
    public void setDrawTitle(boolean b) { drawTitle = b; }
    public void setLegendLocation(int loc) { legendLoc = loc; }
    public void setLegendFrame(boolean b) { legendFrame = b; }
    public void setLegendFontSize(float pt) { legendFontPt = pt; }
    public void setXLim(Double min, Double max) { userXMin = min; userXMax = max; }
    public void setYLim(Double min, Double max) { userYMin = min; userYMax = max; }

    /** 运行时修改 X 轴标签（如单位从 μm 切换为 μrad）。 */
    public void setXLabel(String s) { xLabel = s; }
    /** 运行时修改 Y 轴标签（如单位从 μm 切换为 μrad）。 */
    public void setYLabel(String s) { yLabel = s; }

    /** 构建 Matplotlib/Matlab 风格的工具栏（仅图标：复原 / 缩放 / 平移 / 存储图形）。 */
    private JToolBar buildToolBar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);

        JButton home = new JButton(makeIcon(ICON_HOME));
        home.setToolTipText("复原：恢复到初始坐标轴范围");
        home.addActionListener(e -> {
            xMin = hxMin; xMax = hxMax; yMin = hyMin; yMax = hyMax;
            mode = NONE;
            canvas.repaint();
        });

        JButton zoom = new JButton(makeIcon(ICON_ZOOM));
        zoom.setToolTipText("缩放：拖拽框选放大");
        zoom.addActionListener(e -> {
            mode = (mode == ZOOM) ? NONE : ZOOM;
            setButtonSelected(zoom, mode == ZOOM);
            setButtonSelected(pan, false);
        });

        JButton panB = new JButton(makeIcon(ICON_PAN));
        this.pan = panB;
        panB.setToolTipText("平移：拖拽平移图形");
        panB.addActionListener(e -> {
            mode = (mode == PAN) ? NONE : PAN;
            setButtonSelected(pan, mode == PAN);
            setButtonSelected(zoom, false);
        });

        JButton save = new JButton(makeIcon(ICON_SAVE));
        save.setToolTipText("存储图形：导出 PNG / SVG");
        save.addActionListener(e -> savePlot());

        bar.add(home);
        bar.add(zoom);
        bar.add(panB);
        bar.addSeparator();
        bar.add(save);
        this.toolbar = bar;
        return bar;
    }

    // ------------------------------------------------------------------
    // 工具栏图标（自绘，模仿 Matlab 图形窗口常用按钮）
    // ------------------------------------------------------------------

    private Icon makeIcon(int kind) {
        // 以 16px 矢量路径绘制，再放大到 24px，保证高分屏下清晰不模糊
        int s = 24;
        double scale = 1.5;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(scale, scale);
        g.setColor(new Color(0x33, 0x33, 0x33));
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case ICON_HOME: drawHome(g); break;
            case ICON_ZOOM: drawZoom(g); break;
            case ICON_PAN:  drawPan(g);  break;
            case ICON_SAVE: drawSave(g); break;
            default: break;
        }
        g.dispose();
        return new ImageIcon(img);
    }

    private void drawHome(Graphics2D g) {
        g.fillPolygon(new int[]{8, 3, 13}, new int[]{3, 8, 8}, 3);
        g.fillRect(4, 8, 8, 5);
    }

    private void drawZoom(Graphics2D g) {
        g.drawOval(3, 3, 9, 9);
        g.drawLine(10, 10, 14, 14);
    }

    private void drawPan(Graphics2D g) {
        // 四向箭头（平移/移动光标）
        g.drawLine(2, 8, 14, 8);   // 水平主轴
        g.drawLine(2, 8, 5, 5);
        g.drawLine(2, 8, 5, 11);
        g.drawLine(14, 8, 11, 5);
        g.drawLine(14, 8, 11, 11);
        g.drawLine(8, 2, 8, 14);   // 垂直主轴
        g.drawLine(8, 2, 5, 5);
        g.drawLine(8, 2, 11, 5);
        g.drawLine(8, 14, 5, 11);
        g.drawLine(8, 14, 11, 11);
        g.fillRect(7, 7, 2, 2);    // 中心
    }

    private void drawSave(Graphics2D g) {
        g.fillRect(2, 2, 12, 12);
        g.setColor(Color.WHITE);
        g.fillRect(4, 7, 8, 6);
        g.setColor(new Color(0x33, 0x33, 0x33));
        g.fillRect(4, 2, 7, 4);
    }

    private JButton pan; // 占位，供 buildToolBar 内部引用
    private JToolBar toolbar; // 构建时记住，便于追加右上角控件

    private void setButtonSelected(JButton b, boolean sel) {
        b.setBackground(sel ? new Color(0xCF, 0xE8, 0xFF) : null);
    }

    /** 在工具栏右端追加一个自定义控件（如“对齐中心”开关）。 */
    public void addTopRightControl(JComponent c) {
        if (toolbar != null) {
            toolbar.add(Box.createHorizontalGlue());
            toolbar.add(c);
        }
    }

    /** 清空所有数据序列并重置坐标轴范围（供切换参数后重绘）。 */
    public void clearSeriesData() {
        series.clear();
        rangeSet = false;
        userXMin = userXMax = userYMin = userYMax = null;
    }

    /** 依据当前数据重新计算坐标轴范围并重绘（配合 {@link #clearSeriesData()} 使用）。 */
    public void refresh() {
        computeRangeIfNeeded();
        canvas.repaint();
    }

    /** 创建并显示图形窗口（在 EDT 上构建 GUI）。 */
    public void showPlot() {
        computeRangeIfNeeded();
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame(title != null ? title : "Plot");
            frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
            frame.getContentPane().add(this, BorderLayout.CENTER);
            frame.setSize(720, 540);
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    // ------------------------------------------------------------------
    // 坐标轴与数据范围
    // ------------------------------------------------------------------

    private void computeRangeIfNeeded() {
        if (rangeSet) {
            return;
        }
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (Series s : series) {
            for (int i = 0; i < s.x.length; i++) {
                if (s.x[i] < minX) minX = s.x[i];
                if (s.x[i] > maxX) maxX = s.x[i];
                if (s.y[i] < minY) minY = s.y[i];
                if (s.y[i] > maxY) maxY = s.y[i];
            }
        }
        if (!Double.isFinite(minX)) {
            minX = 0; maxX = 1; minY = 0; maxY = 1;
        }
        // 留 5% 边距
        double dx = (maxX - minX) * 0.05;
        double dy = (maxY - minY) * 0.05;
        if (dx == 0) dx = Math.abs(maxX) * 0.05 + 1e-6;
        if (dy == 0) dy = Math.abs(maxY) * 0.05 + 1e-6;
        xMin = hxMin = minX - dx;
        xMax = hxMax = maxX + dx;
        yMin = hyMin = minY - dy;
        yMax = hyMax = maxY + dy;
        if (userXMin != null) { xMin = hxMin = userXMin; }
        if (userXMax != null) { xMax = hxMax = userXMax; }
        if (userYMin != null) { yMin = hyMin = userYMin; }
        if (userYMax != null) { yMax = hyMax = userYMax; }
        rangeSet = true;
    }

    // ------------------------------------------------------------------
    // 坐标变换
    // ------------------------------------------------------------------

    private int toPxX(double xv, int x0, int x1) {
        return (int) Math.round(x0 + (xv - xMin) / (xMax - xMin) * (x1 - x0));
    }

    private int toPxY(double yv, int y0, int y1) {
        return (int) Math.round(y1 - (yv - yMin) / (yMax - yMin) * (y1 - y0));
    }

    private double fromPxX(int px, int x0, int x1) {
        return xMin + (double) (px - x0) / (x1 - x0) * (xMax - xMin);
    }

    private double fromPxY(int py, int y0, int y1) {
        return yMin + (double) (y1 - py) / (y1 - y0) * (yMax - yMin);
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    private class Canvas extends JPanel {
        Canvas() {
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (mode == NONE) {
                        return;
                    }
                    dragStart = e.getPoint();
                    dragPrev = e.getPoint();
                    if (mode == ZOOM) {
                        zoomRect = new Rectangle(e.getX(), e.getY(), 0, 0);
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (mode == PAN && dragPrev != null) {
                        int x0 = leftMargin, x1 = getWidth() - rightMargin;
                        int y0 = topMargin, y1 = getHeight() - bottomMargin;
                        int dx = e.getX() - dragPrev.x;
                        int dy = e.getY() - dragPrev.y;
                        double ddx = (double) dx / (x1 - x0) * (xMax - xMin);
                        double ddy = (double) dy / (y1 - y0) * (yMax - yMin);
                        xMin -= ddx; xMax -= ddx;
                        yMin += ddy; yMax += ddy;
                        dragPrev = e.getPoint();
                        repaint();
                    } else if (mode == ZOOM && zoomRect != null) {
                        zoomRect.setSize(e.getX() - zoomRect.x, e.getY() - zoomRect.y);
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (mode == ZOOM && zoomRect != null) {
                        int x0 = leftMargin, x1 = getWidth() - rightMargin;
                        int y0 = topMargin, y1 = getHeight() - bottomMargin;
                        int rx0 = Math.min(zoomRect.x, zoomRect.x + zoomRect.width);
                        int rx1 = Math.max(zoomRect.x, zoomRect.x + zoomRect.width);
                        int ry0 = Math.min(zoomRect.y, zoomRect.y + zoomRect.height);
                        int ry1 = Math.max(zoomRect.y, zoomRect.y + zoomRect.height);
                        if (rx1 - rx0 > 4 && ry1 - ry0 > 4) {
                            double nx0 = fromPxX(rx0, x0, x1);
                            double nx1 = fromPxX(rx1, x0, x1);
                            double ny0 = fromPxY(ry1, y0, y1);
                            double ny1 = fromPxY(ry0, y0, y1);
                            xMin = nx0; xMax = nx1; yMin = ny0; yMax = ny1;
                        }
                        zoomRect = null;
                        repaint();
                    }
                    dragStart = null;
                    dragPrev = null;
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
            addMouseWheelListener((MouseWheelEvent e) -> {
                double factor = (e.getWheelRotation() < 0) ? 0.9 : 1.1;
                int x0 = leftMargin, x1 = getWidth() - rightMargin;
                int y0 = topMargin, y1 = getHeight() - bottomMargin;
                double cx = fromPxX(e.getX(), x0, x1);
                double cy = fromPxY(e.getY(), y0, y1);
                xMin = cx + (xMin - cx) * factor;
                xMax = cx + (xMax - cx) * factor;
                yMin = cy + (yMin - cy) * factor;
                yMax = cy + (yMax - cy) * factor;
                repaint();
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            drawPlot((Graphics2D) g, getWidth(), getHeight());
        }

        /** 统一的绘制入口：屏幕、高清 PNG、SVG 导出共用。 */
        void drawPlot(Graphics2D g2, int w, int h) {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int x0 = leftMargin, y0 = topMargin, x1 = w - rightMargin, y1 = h - bottomMargin;

            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w, h);
            g2.fillRect(x0, y0, x1 - x0, y1 - y0);

            drawAxes(g2, x0, y0, x1, y1);
            // 将数据绘制裁剪在绘图区内，避免放大时线条溢出覆盖坐标轴与刻度
            Shape oldClip = g2.getClip();
            g2.setClip(x0, y0, x1 - x0, y1 - y0);
            drawSeries(g2, x0, y0, x1, y1);
            if (originCross) {
                drawOriginCross(g2, x0, y0, x1, y1);
            }
            g2.setClip(oldClip);
            if (legend) {
                drawLegend(g2, x0, y0, x1, y1);
            }
            if (northAnnotation != null) {
                g2.setFont(new Font(labelFont.getFamily(), Font.PLAIN, (int) legendFontPt));
                g2.setColor(Color.BLACK);
                int tw = g2.getFontMetrics().stringWidth(northAnnotation);
                g2.drawString(northAnnotation, (x0 + x1) / 2 - tw / 2, y0 + 4 + g2.getFontMetrics().getAscent());
            }
            if (annotation != null) {
                g2.setColor(Color.BLACK);
                g2.setFont(g2.getFont().deriveFont(Font.PLAIN, 12f));
                g2.drawString(annotation, x0 + 8, y0 + 16);
            }
            if (zoomRect != null) {
                g2.setColor(new Color(0x00, 0x78, 0xFF, 120));
                g2.fill(zoomRect);
                g2.setColor(new Color(0x00, 0x78, 0xFF));
                g2.draw(zoomRect);
            }
        }
    }

    private void drawAxes(Graphics2D g2, int x0, int y0, int x1, int y1) {
        int tl = 5; // 刻度线长度

        // 网格
        if (grid) {
            g2.setFont(tickFont);
            g2.setColor(new Color(0xDD, 0xDD, 0xDD));
            for (double v : niceTicks(xMin, xMax, 6)) {
                int px = toPxX(v, x0, x1);
                g2.drawLine(px, y0, px, y1);
            }
            for (double v : niceTicks(yMin, yMax, 6)) {
                int py = toPxY(v, y0, y1);
                g2.drawLine(x0, py, x1, py);
            }
        }

        // 轴线（box: 四边；否则左+下）
        g2.setColor(Color.BLACK);
        g2.setStroke(new BasicStroke(1f));
        if (box) {
            g2.drawRect(x0, y0, x1 - x0, y1 - y0);
        } else {
            g2.drawLine(x0, y0, x0, y1);
            g2.drawLine(x0, y1, x1, y1);
        }

        // 刻度线 + 数字
        g2.setFont(tickFont);
        g2.setColor(Color.BLACK);
        double xStep = niceStep(xMin, xMax, 6);
        double yStep = niceStep(yMin, yMax, 6);
        for (double v : niceTicks(xMin, xMax, 6)) {
            int px = toPxX(v, x0, x1);
            if (ticksInward) g2.drawLine(px, y1, px, y1 - tl);
            else g2.drawLine(px, y1, px, y1 + tl);
            String s = formatTick(v, xStep, xTicksDecimals);
            int tw = g2.getFontMetrics().stringWidth(s);
            g2.drawString(s, px - tw / 2, y1 + tl + 14);
        }
        for (double v : niceTicks(yMin, yMax, 6)) {
            int py = toPxY(v, y0, y1);
            if (ticksInward) g2.drawLine(x0, py, x0 + tl, py);
            else g2.drawLine(x0, py, x0 - tl, py);
            String s = formatTick(v, yStep, yTicksDecimals);
            int tw = g2.getFontMetrics().stringWidth(s);
            g2.drawString(s, x0 - tl - tw - 4, py + g2.getFontMetrics().getAscent() / 2);
        }

        // 轴标签
        if (xLabel != null) {
            g2.setFont(labelFont);
            int tw = g2.getFontMetrics().stringWidth(xLabel);
            g2.drawString(xLabel, (x0 + x1) / 2 - tw / 2,
                    y1 + tl + 14 + 8 + (int) (labelFont.getSize() * 0.8));
        }
        if (yLabel != null) {
            Graphics2D g3 = (Graphics2D) g2.create();
            g3.setFont(labelFont);
            g3.rotate(-Math.PI / 2);
            int tw = g2.getFontMetrics().stringWidth(yLabel);
            g3.drawString(yLabel, -(y0 + y1) / 2 - tw / 2, 16);
            g3.dispose();
        }
        if (drawTitle && title != null) {
            g2.setFont(g2.getFont().deriveFont(Font.BOLD, 13f));
            g2.drawString(title, (x0 + x1) / 2 - g2.getFontMetrics().stringWidth(title) / 2, y0 - 4);
        }
    }

    private void drawSeries(Graphics2D g2, int x0, int y0, int x1, int y1) {
        for (Series s : series) {
            g2.setColor(s.color);
            if (s.type == LINE) {
                g2.setStroke(new BasicStroke(s.lineWidth));
                for (int i = 1; i < s.x.length; i++) {
                    int px0 = toPxX(s.x[i - 1], x0, x1);
                    int py0 = toPxY(s.y[i - 1], y0, y1);
                    int px1 = toPxX(s.x[i], x0, x1);
                    int py1 = toPxY(s.y[i], y0, y1);
                    g2.drawLine(px0, py0, px1, py1);
                }
            } else if (s.type == RECT) {
                int px0 = toPxX(s.x[0], x0, x1);
                int py1 = toPxY(s.y[0], y0, y1);
                int px1 = toPxX(s.x[0] + s.rw, x0, x1);
                int py0 = toPxY(s.y[0] + s.rh, y0, y1);
                g2.setColor(s.color);
                g2.fillRect(px0, py0, px1 - px0, py1 - py0);
            } else if (s.type == CIRCLE) {
                int pcx = toPxX(s.x[0], x0, x1);
                int pcy = toPxY(s.y[0], y0, y1);
                int prx = Math.abs(toPxX(s.x[0] + s.rw, x0, x1) - pcx);
                int pry = Math.abs(toPxY(s.y[0] + s.rw, y0, y1) - pcy);
                g2.setColor(s.color);
                g2.drawOval(pcx - prx, pcy - pry, prx * 2, pry * 2);
            } else { // SCATTER
                for (int i = 0; i < s.x.length; i++) {
                    int px = toPxX(s.x[i], x0, x1);
                    int py = toPxY(s.y[i], y0, y1);
                    drawMarker(g2, px, py, s.marker, s.hollow, s.markerSize, s.color, s.markerEdge);
                }
            }
        }
    }

    private void drawMarker(Graphics2D g2, int px, int py, int shape, boolean hollow,
                            float r, Color color, float edge) {
        int ir = (int) Math.round(r);
        if (shape == MARKER_TRIANGLE) {
            int[] xs = {px, px - ir, px + ir};
            int[] ys = {py - ir, py + (int) (ir * 0.8), py + (int) (ir * 0.8)};
            if (hollow) {
                g2.setStroke(new BasicStroke(edge));
                g2.setColor(color);
                g2.drawPolygon(xs, ys, 3);
            } else {
                g2.setColor(color);
                g2.fillPolygon(xs, ys, 3);
            }
        } else if (shape == MARKER_SQUARE) {
            if (hollow) {
                g2.setStroke(new BasicStroke(edge));
                g2.setColor(color);
                g2.drawRect(px - ir, py - ir, ir * 2, ir * 2);
            } else {
                g2.setColor(color);
                g2.fillRect(px - ir, py - ir, ir * 2, ir * 2);
            }
        } else if (shape == MARKER_DIAMOND) {
            int[] xs = {px, px + ir, px, px - ir};
            int[] ys = {py - ir, py, py + ir, py};
            if (hollow) {
                g2.setStroke(new BasicStroke(edge));
                g2.setColor(color);
                g2.drawPolygon(xs, ys, 4);
            } else {
                g2.setColor(color);
                g2.fillPolygon(xs, ys, 4);
            }
        } else { // 圆
            if (hollow) {
                g2.setStroke(new BasicStroke(edge));
                g2.setColor(color);
                g2.drawOval(px - ir, py - ir, ir * 2, ir * 2);
            } else {
                g2.setColor(color);
                g2.fillOval(px - ir, py - ir, ir * 2, ir * 2);
            }
        }
    }

    private void drawOriginCross(Graphics2D g2, int x0, int y0, int x1, int y1) {
        if (xMin > 0 || xMax < 0 || yMin > 0 || yMax < 0) {
            return; // 原点不在可视范围内
        }
        int px = toPxX(0, x0, x1);
        int py = toPxY(0, y0, y1);
        double d = Math.min(x1 - x0, y1 - y0) / 25.0;
        double dxData = d / (x1 - x0) * (xMax - xMin);
        double dyData = d / (y1 - y0) * (yMax - yMin);
        g2.setColor(Color.GRAY);
        g2.drawLine(toPxX(-dxData, x0, x1), py, toPxX(dxData, x0, x1), py);
        g2.drawLine(px, toPxY(-dyData, y0, y1), px, toPxY(dyData, y0, y1));
    }

    private void drawLegend(Graphics2D g2, int x0, int y0, int x1, int y1) {
        Font lf = new Font(labelFont.getFamily(), Font.PLAIN, (int) legendFontPt);
        g2.setFont(lf);
        int lh = (int) (legendFontPt + 6);
        int n = 0, boxW = 0;
        for (Series s : series) {
            if (s.label == null) {
                continue;
            }
            n++;
            int tw = g2.getFontMetrics().stringWidth(s.label) + 30;
            if (tw > boxW) {
                boxW = tw;
            }
        }
        int boxH = n * lh;
        int ax, ay;
        switch (legendLoc) {
            case LEGEND_UPPER_RIGHT: ax = x1 - boxW - 10; ay = y0 + 10; break;
            case LEGEND_LOWER_LEFT:  ax = x0 + 10; ay = y1 - boxH - 10; break;
            case LEGEND_LOWER_RIGHT: ax = x1 - boxW - 10; ay = y1 - boxH - 10; break;
            default:                 ax = x0 + 10; ay = y0 + 10;
        }
        if (legendFrame) {
            g2.setColor(Color.WHITE);
            g2.fillRect(ax - 4, ay - 4, boxW + 8, boxH + 8);
            g2.setColor(Color.GRAY);
            g2.drawRect(ax - 4, ay - 4, boxW + 8, boxH + 8);
        }
        int i = 0;
        for (Series s : series) {
            if (s.label == null) {
                continue;
            }
            int cy = ay + i * lh + lh / 2;
            if (s.type == LINE) {
                g2.setColor(s.color);
                g2.setStroke(new BasicStroke(1.6f));
                g2.drawLine(ax, cy, ax + 20, cy);
            } else {
                drawMarker(g2, ax + 10, cy, s.marker, s.hollow, s.markerSize, s.color, s.markerEdge);
            }
            g2.setColor(Color.BLACK);
            g2.drawString(s.label, ax + 26, ay + i * lh + (int) (lh * 0.7));
            i++;
        }
    }

    // ------------------------------------------------------------------
    // 刻度辅助
    // ------------------------------------------------------------------

    private static double niceStep(double min, double max, int maxTicks) {
        double range = max - min;
        if (range <= 0) {
            return 1.0;
        }
        double raw = range / maxTicks;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double step;
        if (norm < 1.5) step = 1;
        else if (norm < 3) step = 2;
        else if (norm < 7) step = 5;
        else step = 10;
        return step * mag;
    }

    private static List<Double> niceTicks(double min, double max, int maxTicks) {
        double step = niceStep(min, max, maxTicks);
        List<Double> out = new ArrayList<>();
        double start = Math.ceil(min / step) * step;
        for (double v = start; v <= max + step * 1e-9; v += step) {
            out.add(v);
        }
        return out;
    }

    private static String formatTick(double v, double step, Integer decimals) {
        int d;
        if (decimals != null) {
            d = decimals;
        } else {
            d = (int) Math.max(0, -Math.floor(Math.log10(step)));
            d = Math.min(d, 6);
        }
        return String.format("%." + d + "f", v);
    }

    // ------------------------------------------------------------------
    // 存储图形
    // ------------------------------------------------------------------

    /** 根据文件扩展名导出：.svg 为矢量图，其余按 2× 高分辨率 PNG 保存。 */
    private void savePlot() {
        JFileChooser fc = new JFileChooser();
        File desktop = new File(System.getProperty("user.home"), "Desktop");
        if (desktop.isDirectory()) {
            fc.setCurrentDirectory(desktop);
        }
        FileNameExtensionFilter pngFilter = new FileNameExtensionFilter("PNG 图像 (*.png)", "png");
        FileNameExtensionFilter svgFilter = new FileNameExtensionFilter("SVG 矢量图 (*.svg)", "svg");
        fc.addChoosableFileFilter(pngFilter);
        fc.addChoosableFileFilter(svgFilter);
        fc.setFileFilter(pngFilter);
        fc.setAcceptAllFileFilterUsed(false);
        fc.setSelectedFile(new File("BeamPointing.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File f = fc.getSelectedFile();
        String name = f.getName().toLowerCase();
        // 未带扩展名时，按当前选中的过滤器自动补全
        if (!name.endsWith(".png") && !name.endsWith(".svg")) {
            FileNameExtensionFilter sel = (FileNameExtensionFilter) fc.getFileFilter();
            f = new File(f.getAbsolutePath() + "." + sel.getExtensions()[0]);
            name = f.getName().toLowerCase();
        }
        try {
            if (name.endsWith(".svg")) {
                SvgGraphics sg = new SvgGraphics(canvas.getWidth(), canvas.getHeight());
                canvas.drawPlot(sg, canvas.getWidth(), canvas.getHeight());
                Files.write(f.toPath(), sg.toSvg().getBytes("UTF-8"));
            } else {
                if (!name.endsWith(".png")) {
                    f = new File(f.getAbsolutePath() + ".png");
                }
                int scale = 2; // 高分辨率：屏幕尺寸的 2 倍
                int w = canvas.getWidth() * scale;
                int h = canvas.getHeight() * scale;
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                Graphics2D g2 = img.createGraphics();
                g2.scale(scale, scale);
                canvas.drawPlot(g2, canvas.getWidth(), canvas.getHeight());
                g2.dispose();
                ImageIO.write(img, "png", f);
            }
            IJ.showStatus("图形已保存: " + f.getAbsolutePath());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "保存失败: " + ex.getMessage(),
                    "存储图形", JOptionPane.ERROR_MESSAGE);
        }
    }
}
