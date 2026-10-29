package com.linhua.plot;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;

/**
 * 复刻 Python 端绘图辅助的纯 Java 等价物：
 * <ul>
 *   <li>{@code _assign_colors_markers}：≤4 条用 k/r/b/g + o/^/s/d；更多则用渐变色 + 圆点；</li>
 *   <li>{@code _format_number}：按数量级选科学计数法或三位小数；</li>
 *   <li>{@code _calculate_markersize}：按数据点数量给标记半径。</li>
 * </ul>
 */
public final class PlotUtil {

    private PlotUtil() { }

    /**
     * 统一的「多选文件对话框」：默认打开桌面，开启多选，按扩展名过滤。
     * 所有画图插件共用此方法，保持 GUI 交互一致（对应 Python 端的文件夹+关键词批处理）。
     *
     * @param title  对话框标题
     * @param exts   允许的扩展名（如 "csv", "xlsx"）；为空则不限制
     * @return 选中的文件数组（取消或未选则返回空数组）
     */
    public static File[] chooseFiles(String title, String... exts) {
        String desktop = new File(System.getProperty("user.home"), "Desktop").getPath();
        JFileChooser fc = new JFileChooser(desktop);
        fc.setMultiSelectionEnabled(true);
        fc.setDialogTitle(title);
        if (exts != null && exts.length > 0) {
            StringBuilder desc = new StringBuilder();
            for (int i = 0; i < exts.length; i++) {
                if (i > 0) desc.append(", ");
                desc.append("*.").append(exts[i]);
            }
            fc.setFileFilter(new FileNameExtensionFilter(desc.toString(), exts));
        }
        if (fc.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            return new File[0];
        }
        File[] sel = fc.getSelectedFiles();
        if (sel == null) {
            return new File[0];
        }
        // 仅保留允许的扩展名（部分平台会忽略文件过滤器）
        if (exts == null || exts.length == 0) {
            return sel;
        }
        List<File> keep = new ArrayList<>();
        for (File f : sel) {
            String n = f.getName().toLowerCase();
            for (String e : exts) {
                if (n.endsWith("." + e.toLowerCase())) {
                    keep.add(f);
                    break;
                }
            }
        }
        return keep.toArray(new File[0]);
    }

    /**
     * 按系列数分配颜色与标记。
     *
     * @return 长度 = num 的数组，{@code colors[i]} 与 {@code markers[i]} 配对。
     */
    public static Color[] assignColors(int num) {
        Color[] colors = new Color[num];
        if (num <= 4) {
            Color[] base = {Color.BLACK, Color.RED, Color.BLUE, Color.GREEN};
            for (int i = 0; i < num; i++) {
                colors[i] = base[i];
            }
        } else {
            for (int i = 0; i < num; i++) {
                colors[i] = viridis((double) i / (num - 1));
            }
        }
        return colors;
    }

    public static int[] assignMarkers(int num) {
        int[] markers = new int[num];
        if (num <= 4) {
            int[] base = {Plot.MARKER_CIRCLE, Plot.MARKER_TRIANGLE, Plot.MARKER_SQUARE, Plot.MARKER_DIAMOND};
            for (int i = 0; i < num; i++) {
                markers[i] = base[i];
            }
        } else {
            for (int i = 0; i < num; i++) {
                markers[i] = Plot.MARKER_CIRCLE;
            }
        }
        return markers;
    }

    /** 复刻 _format_number：|x|≥100 或 |x|<0.01 用科学计数法(2 位)，否则 2 位小数。 */
    public static String formatNumber(double num) {
        if (Math.abs(num) >= 100 || Math.abs(num) < 0.01) {
            return String.format("%.2e", num);
        }
        return String.format("%.2f", num);
    }

    /** 复刻 _calculate_markersize(ax_width=8)：返回标记半径(px)。 */
    public static float markerRadius(int numPoints) {
        double size;
        if (numPoints > 300) {
            size = 5.6;
        } else if (numPoints > 100) {
            size = 7.2;
        } else if (numPoints > 20) {
            size = 9.6;
        } else {
            size = 11.2;
        }
        return (float) (size / 2.0);
    }

    /**
     * 统一套用 matplotlib 风格到 Plot（字体 Arial、刻度加粗、向内刻度、四边框、无网格、图例无框）。
     * 各插件画完后再按需设置波长注释等。
     */
    public static void applyStyle(Plot p, String xLabel, String yLabel,
                                  int xDec, int yDec, int legendLoc,
                                  boolean inward, boolean grid, boolean box) {
        p.setDrawTitle(false);
        p.setFontFamily("Arial");
        p.setLabelFontSize(18f);
        p.setTickFontSize(16f);
        p.setTickBold(true);
        p.setXTicksDecimals(xDec);
        p.setYTicksDecimals(yDec);
        p.setTicksInward(inward);
        p.setGrid(grid);
        p.setBox(box);
        p.setLegend(true);
        p.setLegendLocation(legendLoc);
        p.setLegendFrame(false);
        p.setLegendFontSize(15f);
    }

    /** 把 Python 的 'label loc' 字符串映射到 Plot 图例位置常量。 */
    public static int legendLoc(String s) {
        if (s == null) {
            return Plot.LEGEND_UPPER_LEFT;
        }
        String t = s.toLowerCase();
        if (t.contains("upper") && t.contains("right")) {
            return Plot.LEGEND_UPPER_RIGHT;
        }
        if (t.contains("lower") && t.contains("left")) {
            return Plot.LEGEND_LOWER_LEFT;
        }
        if (t.contains("lower")) {
            return Plot.LEGEND_LOWER_RIGHT;
        }
        return Plot.LEGEND_UPPER_LEFT;
    }

    /** viridis 近似（三色段线性插值）：t∈[0,1]。 */
    private static Color viridis(double t) {
        t = Math.max(0, Math.min(1, t));
        // 暗紫 -> 蓝绿 -> 黄
        double[] c0 = {0.267, 0.005, 0.329};
        double[] c1 = {0.128, 0.567, 0.551};
        double[] c2 = {0.993, 0.906, 0.144};
        double[] c;
        if (t < 0.5) {
            double u = t / 0.5;
            c = lerp(c0, c1, u);
        } else {
            double u = (t - 0.5) / 0.5;
            c = lerp(c1, c2, u);
        }
        return new Color((int) (c[0] * 255 + 0.5), (int) (c[1] * 255 + 0.5), (int) (c[2] * 255 + 0.5));
    }

    /** 密度热力图配色（inferno 近似，t∈[0,1]：低=暗、高=亮黄）。 */
    public static Color densityColor(double t) {
        t = Math.max(0, Math.min(1, t));
        int[][] stops = {
            {0, 0, 4}, {20, 11, 52}, {63, 16, 90}, {116, 33, 99},
            {163, 50, 92}, {204, 71, 75}, {237, 105, 52}, {252, 255, 164}
        };
        double u = t * (stops.length - 1);
        int i = (int) Math.floor(u);
        int j = Math.min(i + 1, stops.length - 1);
        double f = u - i;
        int r = (int) Math.round(stops[i][0] + (stops[j][0] - stops[i][0]) * f);
        int g = (int) Math.round(stops[i][1] + (stops[j][1] - stops[i][1]) * f);
        int b = (int) Math.round(stops[i][2] + (stops[j][2] - stops[i][2]) * f);
        return new Color(r, g, b);
    }

    private static double[] lerp(double[] a, double[] b, double u) {
        return new double[]{a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u, a[2] + (b[2] - a[2]) * u};
    }
}
