package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import com.linhua.io.Table;

/**
 * 多线图（各自 X）：列按 x1,y1,x2,y2,… 交错，每条线用自己的 X。
 * 复刻 Python _240830_plot_multiline2.py。多选对话框：对每个选中的 xlsx 出一张图。
 */
public class Multiline2_ implements PlugIn {

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
        int nSeries = ncol / 2;
        if (nSeries < 1) {
            IJ.log(f.getName() + ": 至少需要两列（x1, y1）。");
            return;
        }
        Color[] colors = PlotUtil.assignColors(nSeries);
        int[] markers = PlotUtil.assignMarkers(nSeries);
        float mr = PlotUtil.markerRadius(t.rowCount() - 1);

        Plot plot = new Plot(f.getName(), X_LABEL, Y_LABEL);
        PlotUtil.applyStyle(plot, X_LABEL, Y_LABEL, 0, 1, Plot.LEGEND_UPPER_LEFT, true, false, true);
        for (int i = 0; i < nSeries; i++) {
            int xc = 2 * i, yc = 2 * i + 1;
            ArrayList<Double> X = new ArrayList<>(), Y = new ArrayList<>();
            for (int r = 1; r < t.rowCount(); r++) {
                double xv = t.num(r, xc), yv = t.num(r, yc);
                if (!Double.isNaN(xv) && !Double.isNaN(yv)) {
                    X.add(xv);
                    Y.add(yv);
                }
            }
            double[] xx = toArr(X), yy = toArr(Y);
            Color c = colors[i];
            int m = markers[i];
            plot.addLine(xx, yy, c, null, 2.4f);
            plot.addScatter(xx, yy, c, t.header(yc), m, true, mr, 2.0f);
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
