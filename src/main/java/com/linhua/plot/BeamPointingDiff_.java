package com.linhua.plot;

import ij.IJ;
import ij.plugin.PlugIn;
import java.io.File;

/**
 * 透射器件指向稳定性差分插件（输出样式对齐 {@code BeamPointing_}：时间轨迹图 + X-Y 散点图 + 一致 Log）。
 *
 * <p>取参考臂（无器件）与测量臂（含被测透射元件）两组 *同步采集* 的 Beam Pointing CSV，做共模抑制差分
 * {@code D(t) = meas(t) - ref(t)}（增益恒为 1），把激光器抖动/环境振动/湍流/光路等共模从测量臂扣除，
 * 仅留透射元器件自身动态指向起伏。</p>
 *
 * <p>差分计算与渲染统一委托 {@link BeamPointingDiffRender}：本类负责选文件、解析与编排，
 * 分阶段差分见 {@link BeamPointingPhaseDiff_}。共模抑制核心 {@link #differential} 保持为可单测的纯方法。</p>
 */
public class BeamPointingDiff_ implements PlugIn {

    @Override
    public void run(String arg) {
        File[] refFiles = PlotUtil.chooseFiles("选择参考臂 CSV（无器件）", "csv");
        if (refFiles.length == 0) {
            return;
        }
        File[] measFiles = PlotUtil.chooseFiles("选择测量臂 CSV（含被测器件）", "csv");
        if (measFiles.length == 0) {
            return;
        }
        try {
            BeamPointingCore.Data ref = BeamPointingCore.load(refFiles[0].getAbsolutePath());
            BeamPointingCore.Data meas = BeamPointingCore.load(measFiles[0].getAbsolutePath());
            String refName = stripExt(refFiles[0].getName());
            String measName = stripExt(measFiles[0].getName());
            BeamPointingDiffRender.DiffResult r = BeamPointingDiffRender.compute(ref, meas, refName, measName);
            BeamPointingDiffRender render = new BeamPointingDiffRender(r);
            File outDir = measFiles[0].getParentFile();
            String base = measFiles[0].getName();
            int di = base.lastIndexOf('.');
            render.setExport(outDir, di > 0 ? base.substring(0, di) : base);
            render.show(null);
        } catch (Exception e) {
            IJ.error("处理失败", e.getMessage());
            e.printStackTrace();
        }
    }

    private static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        if (d > 0 && n.substring(d).equalsIgnoreCase(".csv")) {
            return n.substring(0, d);
        }
        return n;
    }

    /**
     * 逐点差分（共模抑制核心）：D = meas − ref，增益恒为 1。
     * 任一臂在时刻 i 为 NaN（缺口）则该点整体置 NaN，供下游绘图/统计自然跳过。
     * 抽成纯方法以便单测直接调用真实代码断言「共模必被抵消」。
     */
    static void differential(double[] rgx, double[] rgy, double[] mgx, double[] mgy,
                             double[] Dx, double[] Dy, double[] D) {
        int n = rgx.length;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(rgx[i]) || Double.isNaN(rgy[i])
                    || Double.isNaN(mgx[i]) || Double.isNaN(mgy[i])) {
                Dx[i] = Dy[i] = D[i] = Double.NaN;
            } else {
                Dx[i] = mgx[i] - rgx[i];
                Dy[i] = mgy[i] - rgy[i];
                D[i] = Math.hypot(Dx[i], Dy[i]);
            }
        }
    }
}
