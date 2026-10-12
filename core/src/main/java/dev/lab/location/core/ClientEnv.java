package dev.lab.location.core;

import java.util.Collections;
import java.util.List;

/**
 * 客户端环境取证结果。
 *
 * <p><b>可信度警告</b>：这里每一项都由客户端本地读取，理论上都可被 hook 篡改。
 * 它们只应作为加权项。真正难伪造的信号是服务端统计（见 {@link CheckinContext}）和硬件证明。
 */
public final class ClientEnv {

    /** 被系统允许使用模拟位置的应用包名（通过 AppOps 枚举，不依赖目标 App 自己的声明）。 */
    public final List<String> mockLocationPackages;

    /** 是否开启了开发者选项。注意：极客用户、开发机、部分定制 ROM 会误伤。 */
    public final boolean developmentSettingsEnabled;

    /** 是否疑似已 root / 解锁。误伤率偏高，权重应保持低位。 */
    public final boolean rootSuspected;

    public ClientEnv(List<String> mockLocationPackages,
                     boolean developmentSettingsEnabled,
                     boolean rootSuspected) {
        this.mockLocationPackages = mockLocationPackages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(mockLocationPackages);
        this.developmentSettingsEnabled = developmentSettingsEnabled;
        this.rootSuspected = rootSuspected;
    }

    /** 全部为「无信号」：所有客户端取证项都没有命中。 */
    public static ClientEnv none() {
        return new ClientEnv(Collections.emptyList(), false, false);
    }
}
