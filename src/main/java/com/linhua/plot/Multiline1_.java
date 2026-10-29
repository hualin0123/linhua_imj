package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import com.linhua.io.Table;

/**
 * 多线图（同一 X）：每列“y1,y2,…”共享第 0 列 X，画“线 + 空心标记”。
 * 复刻 Python _240830_plot_multiline1.py。多选对话框：对每个选中的 xlsx 出一张图。
 */
public class Multiline1_ implements PlugIn {

    private static final String X_LABEL = "input power (w)";
    private static final String Y_LABEL = "output power (w)";

    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择多线数据 (xlsx/csv)", "csv", "xlsx");
        if (files.length == 0) {
            return;
        }
        for (File f : files) {
            try {
                plotFile(f);
            } catch (Exception e) {
                IJ.log("处理失败: " + f.getName() + " - " + e.getMessage());
            }
        }
    }

    private static void plotFile(File f) throws Exception {
        Table t = Table.read(f);
        int ncol = t.colCount();
        int nSeries = ncol - 1;
        if (nSeries < 1) {
            IJ.log(f.getName() + ": 至少需要两列（x + 一个或多个 y）。");
            return;
        }
        Color[] colors = PlotUtil.assignColors(nSeries);
        int[] markers = PlotUtil.assignMarkers(nSeries);
        float mr = PlotUtil.markerRadius(t.rowCount() - 1);

        Plot plot = new Plot(f.getName(), X_LABEL, Y_LABEL);
        PlotUtil.applyStyle(plot, X_LABEL, Y_LABEL, 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        for (int i = 1; i < ncol; i++) {
            ArrayList<Double> X = new ArrayList<>(), Y = new ArrayList<>();
            for (int r = 1; r < t.rowCount(); r++) {
                double xv = t.num(r, 0), yv = t.num(r, i);
                if (!Double.isNaN(xv) && !Double.isNaN(yv)) {
                    X.add(xv);
                    Y.add(yv);
                }
            }
            double[] xx = toArr(X), yy = toArr(Y);
            Color c = colors[i - 1];
            int m = markers[i - 1];
            plot.addLine(xx, yy, c, null, 2.4f);
            plot.addScatter(xx, yy, c, t.header(i), m, true, mr, 2.0f);
        }
        plot.showPlot();
    }

    private static double[] toArr(ArrayList<Double> l) {
        double[] a = new double[l.size()];
        for (int i = 0; i < a.length; i++) {
            a[i] = l.get(i);
        }
        return a;
    }
}
