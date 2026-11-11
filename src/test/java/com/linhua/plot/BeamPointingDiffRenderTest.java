package com.linhua.plot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * 差分共享计算 {@link BeamPointingDiffRender#compute} 的纯逻辑自验证测试（合成数据，无需真实 CSV）。
 * 覆盖：恒定偏置+变化的分差正确性、共模抑制残余等于测量臂 RMS、无重叠时抛异常。
 * 共模抑制核心 {@code differential} 本身由 BeamPointingTest 单独断言。
 */
public class BeamPointingDiffRenderTest {

    private static BeamPointingCore.Data data(List<BeamPointingCore.Sample> s, double rms, double absStart) {
        return new BeamPointingCore.Data(s, new ArrayList<>(), rms, absStart);
    }

    @Test
    public void computeConstantOffsetWithVariation() throws Exception {
        // 参考臂恒为 0；测量臂 x=i(0..4)、y=2(常数)，随时间变化
        List<BeamPointingCore.Sample> ref = new ArrayList<>();
        List<BeamPointingCore.Sample> meas = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            BeamPointingCore.Sample r = new BeamPointingCore.Sample();
            r.t = i;
            ref.add(r);
            BeamPointingCore.Sample m = new BeamPointingCore.Sample();
            m.t = i;
            m.x = i;
            m.y = 2;
            m.r = Math.hypot(i, 2);
            meas.add(m);
        }
        BeamPointingCore.Data dref = data(ref, 0, 0);
        BeamPointingCore.Data dmeas = data(meas, Math.sqrt(2.0), 0);

        BeamPointingDiffRender.DiffResult res =
                BeamPointingDiffRender.compute(dref, dmeas, "ref", "meas");
        assertNotNull(res);
        assertEquals(5, res.validSamples.size());
        for (int i = 0; i < 5; i++) {
            BeamPointingCore.Sample s = res.validSamples.get(i);
            assertEquals((double) i, s.x, 1e-9);     // Dx = meas.x - 0
            assertEquals(2.0, s.y, 1e-9);            // Dy = meas.y - 0
            assertEquals(Math.hypot(i, 2), s.r, 1e-9);
        }
        // 共模抑制：参考臂为零 → 残余 RMS 应等于测量臂自身 RMS（绕质心）
        double rmsMeas = BeamPointingCore.centroidRmsUm(meas);
        double rmsD = BeamPointingCore.centroidRmsUm(res.validSamples);
        assertEquals(Math.sqrt(2.0), rmsMeas, 1e-9);
        assertEquals(rmsMeas, rmsD, 1e-9);
    }

    @Test(expected = Exception.class)
    public void computeNoOverlapThrows() throws Exception {
        List<BeamPointingCore.Sample> ref = new ArrayList<>();
        List<BeamPointingCore.Sample> meas = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            BeamPointingCore.Sample r = new BeamPointingCore.Sample();
            r.t = i;
            ref.add(r);
            BeamPointingCore.Sample m = new BeamPointingCore.Sample();
            m.t = 10 + i;
            meas.add(m);
        }
        BeamPointingDiffRender.compute(data(ref, 0, 0), data(meas, 0, 0), "ref", "meas");
    }
}
