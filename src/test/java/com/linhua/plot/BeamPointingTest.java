package com.linhua.plot;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

/**
 * Beam Pointing 核心数学的自验证测试（合成数据，无需真实实验 CSV）。
 *
 * <p>覆盖 {@link BeamPointingCore} 与 {@link BeamPointingDiff_} 中的纯数学/逻辑：
 * 跨午夜时间戳解析、质心 RMS 不变式、Pearson 相关性、共模抑制（差分核心）、
 * 重采样插值/缺口、名义步长。详见仓库根目录 {@code VERIFICATION.md}。</p>
 *
 * <p>纯私有方法（parseSample / corrValid / resample / nominalStep）通过反射调用真实代码，
 * 避免为测试改动生产代码可见性。</p>
 */
public class BeamPointingTest {

    // ---- 反射调用私有静态方法的辅助 ----
    private static Object invoke(Class<?> cls, String name, Class<?>[] pt, Object... args) throws Exception {
        Method m = cls.getDeclaredMethod(name, pt);
        m.setAccessible(true);
        return m.invoke(null, args);
    }

    private static BeamPointingCore.Sample parseSample(String line) throws Exception {
        return (BeamPointingCore.Sample) invoke(BeamPointingCore.class, "parseSample",
                new Class[]{String.class}, line);
    }

    private static double corrValid(double[] a, double[] b) throws Exception {
        return (double) invoke(BeamPointingDiff_.class, "corrValid",
                new Class[]{double[].class, double[].class}, a, b);
    }

    private static double[] resample(double[] t, double[] v, double[] tg, double maxStep) throws Exception {
        return (double[]) invoke(BeamPointingDiff_.class, "resample",
                new Class[]{double[].class, double[].class, double[].class, double.class}, t, v, tg, maxStep);
    }

    private static double nominalStep(double[] t, double gapFactor) throws Exception {
        return (double) invoke(BeamPointingDiff_.class, "nominalStep",
                new Class[]{double[].class, double.class}, t, gapFactor);
    }

    // ---- 1. 跨午夜时间戳解析（ guarding 回绕 bug） ----
    @Test
    public void parseSampleCrossMidnightIsMonotonic() throws Exception {
        BeamPointingCore.Sample a = parseSample("\"2026-11-05, 23:59:59.000\",0,0,0");
        BeamPointingCore.Sample b = parseSample("\"2026-11-06, 00:00:01.000\",0,0,0");
        assertTrue("跨午夜后时间应仍单调递增", b.t > a.t);
        assertTrue("绝对秒应为正", a.t > 0);
        assertEquals("跨午夜 2 秒间隔应精确", 2.0, b.t - a.t, 1e-6);

        BeamPointingCore.Sample c = parseSample("\"2026-11-05, 10:00:00.000\",0,0,0");
        BeamPointingCore.Sample d = parseSample("\"2026-11-05, 10:00:05.000\",0,0,0");
        assertEquals("同日 5 秒间隔应精确", 5.0, d.t - c.t, 1e-6);
    }

    // ---- 2. 质心 RMS 不变式（去不去直流 RMS 不变） ----
    @Test
    public void centroidRmsInvariantUnderOffset() {
        List<BeamPointingCore.Sample> s = new ArrayList<>();
        add(s, 0, 0);
        add(s, 1, 0);
        add(s, -1, 0);
        add(s, 0, 1);
        add(s, 0, -1);
        double rms = BeamPointingCore.centroidRmsUm(s);
        assertEquals("五点 RMS = sqrt(4/5)", Math.sqrt(4.0 / 5.0), rms, 1e-9);

        // 整体平移一个常数偏移，RMS 必须不变（印证 demean 不影响稳定性指标）
        for (BeamPointingCore.Sample x : s) {
            x.x += 100;
            x.y += 200;
        }
        double rms2 = BeamPointingCore.centroidRmsUm(s);
        assertEquals("平移后 RMS 不变", rms, rms2, 1e-9);
    }

    // ---- 3. Pearson 相关性（ identical=1, 反相=-1, 正交=0, 零方差=NaN） ----
    @Test
    public void corrValidIdentities() throws Exception {
        double[] x = {1, 2, 3, 4};
        assertEquals(1.0, corrValid(x, x), 1e-9);
        assertEquals(-1.0, corrValid(x, new double[]{4, 3, 2, 1}), 1e-9);
        assertEquals(0.0, corrValid(new double[]{1, -1, 1, -1}, new double[]{1, 1, -1, -1}), 1e-9);
        assertTrue("零方差应返回 NaN", Double.isNaN(corrValid(new double[]{1, 2, 3, 4}, new double[]{0, 0, 0, 0})));
    }

    // ---- 4. 共模抑制核心（差分必须用真实代码去掉共模）—— guarding 增益 bug ----
    @Test
    public void differentialRemovesCommonMode() {
        int N = 200;
        double[] common = new double[N], dut = new double[N];
        double[] rgx = new double[N], rgy = new double[N];
        double[] mgx = new double[N], mgy = new double[N];
        for (int i = 0; i < N; i++) {
            common[i] = Math.sin(i * 0.1);
            dut[i] = Math.cos(i * 0.07);            // 与 common 独立的 DUT 效应
            rgx[i] = common[i];
            rgy[i] = common[i];
            mgx[i] = common[i] + dut[i];            // meas = common + DUT
            mgy[i] = common[i] + dut[i];
        }
        double[] Dx = new double[N], Dy = new double[N], D = new double[N];
        BeamPointingDiff_.differential(rgx, rgy, mgx, mgy, Dx, Dy, D);

        // 共模必须被精确减掉：差分输出应精确等于 DUT（k=1 时 D = meas - ref = DUT）。
        // 若有人把增益改成 ≠1，这里会直接失败——这正是昨天增益 bug 的护栏。
        for (int i = 0; i < N; i++) {
            assertEquals("X 共模应被精确减掉，差分=DUT", dut[i], Dx[i], 1e-12);
            assertEquals("Y 共模应被精确减掉，差分=DUT", dut[i], Dy[i], 1e-12);
        }
    }

    @Test
    public void differentialMarksGapAsNaN() {
        int N = 20;
        double[] rgx = new double[N], rgy = new double[N];
        double[] mgx = new double[N], mgy = new double[N];
        for (int i = 0; i < N; i++) {
            rgx[i] = rgy[i] = mgx[i] = mgy[i] = i;
        }
        rgx[10] = Double.NaN;                        // 模拟缺口
        double[] Dx = new double[N], Dy = new double[N], D = new double[N];
        BeamPointingDiff_.differential(rgx, rgy, mgx, mgy, Dx, Dy, D);
        assertTrue("缺口处差分应置 NaN", Double.isNaN(Dx[10]));
        assertEquals("非缺口处差分 = meas - ref", 0.0, Dx[5], 1e-9);
    }

    // ---- 5. 重采样插值 + 缺口 ----
    @Test
    public void resampleInterpolatesLinearly() throws Exception {
        double[] t = {0, 1, 2, 3};
        double[] v = {0, 2, 4, 6};                   // v = 2t
        double[] tg = {0, 0.5, 1, 1.5, 2, 2.5, 3};
        double[] out = resample(t, v, tg, 1e9);       // maxStep 足够大，无缺口
        assertArrayEquals(new double[]{0, 1, 2, 3, 4, 5, 6}, out, 1e-9);
    }

    @Test
    public void resampleMarksGapAsNaN() throws Exception {
        double[] t = {0, 1, 2, 10, 11};
        double[] v = {0, 1, 2, 3, 4};
        double[] tg = {0, 1, 2, 5, 11};              // tg=5 落在 2->10 缺口内
        double[] out = resample(t, v, tg, 3.0);        // 缺口阈值 3s
        assertEquals(0, out[0], 1e-9);
        assertEquals(1, out[1], 1e-9);
        assertEquals(2, out[2], 1e-9);
        assertTrue("缺口内应置 NaN（不插值伪造）", Double.isNaN(out[3]));
        assertEquals(4, out[4], 1e-9);
    }

    // ---- 6. 名义步长排除缺口 ----
    @Test
    public void nominalStepExcludesGap() throws Exception {
        assertEquals(1.0, nominalStep(new double[]{0, 1, 2, 3, 4}, 3.0), 1e-9);
        // 中间 98s 大缺口应被排除，仅剩 1s 步长
        assertEquals(1.0, nominalStep(new double[]{0, 1, 2, 100, 101}, 3.0), 1e-9);
    }

    // ---- 工具 ----
    private static void add(List<BeamPointingCore.Sample> list, double x, double y) {
        BeamPointingCore.Sample s = new BeamPointingCore.Sample();
        s.x = x;
        s.y = y;
        list.add(s);
    }
}
