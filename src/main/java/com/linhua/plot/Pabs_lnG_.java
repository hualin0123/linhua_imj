package com.linhua.plot;

import ij.IJ;
import ij.gui.GenericDialog;
import ij.plugin.PlugIn;
import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import com.linhua.fit.CurveFitter;
import com.linhua.fit.Models;
import com.linhua.io.Table;
import com.linhua.io.XlsxWriter;

/**
 * 吸收截面与荧光寿命：读 Pin/Pout/Punabs/Psl，拟合 lnG(P_abs) 得 lnG_max、P_tf，
 * 再换算吸收截面 σ 与荧光寿命 tf。图例标注物理参数，并写回计算列。
 * 复刻 Python _241119_Pabs_lnG.py（单文件）。
 */
public class Pabs_lnG_ implements PlugIn {

    @Override
    public void run(String arg) {
        File[] files = PlotUtil.chooseFiles("选择 Pabs_lnG 数据 (xlsx/csv)", "csv", "xlsx");
        if (files.length == 0) {
            return;
        }
        GenericDialog gd = new GenericDialog("Pabs_lnG 参数");
        gd.addNumericField("beam_diameter (mm)", 0.35, 3);
        gd.addNumericField("crystal_length (mm)", 50, 1);
        gd.addNumericField("doping_level (1/cm3)", 0.02, 4);
        gd.showDialog();
        if (gd.wasCanceled()) {
            return;
        }
        double beamDiameter = gd.getNextNumber();
        double crystalLength = gd.getNextNumber();
        double dopingLevel = gd.getNextNumber();
        for (File f : files) {
            try {
                plotFile(f, beamDiameter, crystalLength, dopingLevel);
            } catch (Exception e) {
                IJ.error("解析失败: " + f.getName(), e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static int findCol(Table t, String sub) {
        String s = sub.toLowerCase();
        for (int c = 0; c < t.colCount(); c++) {
            if (t.header(c).toLowerCase().contains(s)) {
                return c;
            }
        }
        return -1;
    }

    private static void plotFile(File f, double beamDiameter, double crystalLength, double dopingLevel)
            throws Exception {
        Table t = Table.read(f);
        int cPin = findCol(t, "pin");
        int cPout = findCol(t, "pout");
        int cPun = findCol(t, "punabs");
        int cPsl = findCol(t, "psl");
        if (cPin < 0 || cPout < 0 || cPun < 0 || cPsl < 0) {
            IJ.error("未找到 Pin/Pout/Punabs/Psl 列。");
            return;
        }
        double[] Pin = t.column(cPin);
        double[] Pout = t.column(cPout);
        double[] Pun = t.column(cPun);
        double[] Psl = t.column(cPsl);
        int n = Math.min(Pin.length, Math.min(Pout.length, Math.min(Pun.length, Psl.length)));
        if (n == 0) {
            IJ.error("没有可用的数据行。");
            return;
        }

        double[] Pabs = new double[n];
        double[] G = new double[n];
        double[] lnG = new double[n];
        double ref = Pout[0];
        for (int i = 0; i < n; i++) {
            Pabs[i] = Pin[i] - Pun[i];
            G[i] = (Pout[i] - Psl[i]) / ref;
            lnG[i] = Math.log(G[i]);
        }

        CurveFitter.Model model = Models.pabsLnG();
        double lnGmax0 = 0;
        for (double v : lnG) {
            if (v > lnGmax0) lnGmax0 = v;
        }
        double pmean = 0;
        for (double v : Pabs) pmean += v;
        pmean /= n;
        double[] p0 = {lnGmax0 * 0.9, pmean > 0 ? pmean : 1.0};
        double[] pf = null;
        try {
            pf = CurveFitter.fit(Pabs, lnG, model, p0);
        } catch (Exception e) {
            IJ.log("拟合失败: " + e.getMessage());
        }

        double lnGMax = (pf != null) ? pf[0] : lnGmax0;
        double PtF = (pf != null) ? pf[1] : pmean;

        final double H = 6.626e-34;
        final double C = 2.9979e8;
        final double WAVEL = 1940e-9;
        double phi = 0.1 * beamDiameter;
        double d = 0.1 * crystalLength;
        double n0 = 1.4e22 * dopingLevel;
        double sigma = lnGMax / (n0 * d);
        double tf = (n0 * Math.PI * Math.pow(phi / 2.0, 2) * d * H * C) / (WAVEL * PtF * 5.0);

        Plot plot = new Plot(f.getName(), "P_abs (W)", "lnG");
        // 统一格式（以 M2 的 matplotlib 风格为模板）
        PlotUtil.applyStyle(plot, "P_abs (W)", "lnG", 0, 2, Plot.LEGEND_UPPER_LEFT, true, false, true);

        double[] fitted = new double[n];
        if (pf != null) {
            for (int i = 0; i < n; i++) {
                fitted[i] = model.value(Pabs[i], pf);
            }
        } else {
            System.arraycopy(lnG, 0, fitted, 0, n);
        }
        plot.addLine(Pabs, fitted, Color.BLACK, null, 2.4f);
        plot.addScatter(Pabs, lnG, Color.BLACK, null, Plot.MARKER_CIRCLE, true, 5f, 1.5f);
        String leg = String.format(
                "\u03c3 = %s (cm\u00b2), tf = %s (s), lnG_max = %s, P_tf = %s",
                PlotUtil.formatNumber(sigma), PlotUtil.formatNumber(tf),
                PlotUtil.formatNumber(lnGMax), PlotUtil.formatNumber(PtF));
        plot.setNorthAnnotation(leg);
        plot.showPlot();

        // 写回计算列
        writeBack(f, t, Pabs, G, lnG);
    }

    private static void writeBack(File f, Table t, double[] Pabs, double[] G, double[] lnG) {
        try {
            List<List<String>> orig = t.rows();
            int nrows = orig.size();
            List<List<Object>> out = new ArrayList<>(nrows);
            for (int r = 0; r < nrows; r++) {
                List<Object> row = new ArrayList<>(orig.get(r));
                if (r == 0) {
                    row.add("Pabs (W)");
                    row.add("G");
                    row.add("lnG");
                } else {
                    int idx = r - 1;
                    row.add(idx < Pabs.length ? Pabs[idx] : "");
                    row.add(idx < G.length ? G[idx] : "");
                    row.add(idx < lnG.length ? lnG[idx] : "");
                }
                out.add(row);
            }
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            String base = (dot > 0) ? name.substring(0, dot) : name;
            File outFile = new File(f.getParent(), base + "(update).xlsx");
            XlsxWriter.write(outFile, out);
            IJ.log("已写回: " + outFile.getAbsolutePath());
        } catch (Exception e) {
            IJ.log("写回失败: " + e.getMessage());
        }
    }
}
