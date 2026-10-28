package com.linhua.helloworld;

import ij.IJ;
import ij.plugin.PlugIn;

/**
 * Hello World —— linhua 插件组的示例子命令。
 *
 * <p>位于 {@code com.linhua.helloworld} 子包，对应菜单
 * {@code Plugins > linhua > Hello World}。</p>
 *
 * <p>新增工具时，在 {@code com.linhua.<yourtool>} 下仿照本类写一个命令类
 * （类名以 {@code _} 结尾、实现 {@link ij.plugin.PlugIn}），
 * 并在 {@code src/main/resources/plugins.config} 中加一行：
 * <pre>Plugins > linhua > &lt;yourtool&gt;, "Label", com.linhua.&lt;yourtool&gt;.Xxx_</pre></p>
 *
 * @author Hua Lin
 * @since 0.1.0
 */
public class HelloWorld_ implements PlugIn {

    /** 独立运行入口，便于在 ImageJ 外部直接测试。 */
    public static void main(String[] args) {
        new HelloWorld_().run("");
    }

    /** ImageJ 插件入口。 */
    @Override
    public void run(String arg) {
        IJ.showMessage("linhua / Hello World", "Hello World!");
    }
}
