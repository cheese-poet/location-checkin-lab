package dev.lab.location.core;

/**
 * 一次位置采样。
 *
 * <p>字段与 Android {@code android.location.Location} 一一对应，但刻意不使用 Android 类型：
 * 检测规则必须能在普通 JVM 上测试，也必须能在服务端复用。
 *
 * <p><b>关于可信度</b>：本对象的全部字段都由客户端产生，因此全部可被伪造或被 hook 篡改。
 * 它们只应作为加权项，绝不能作为唯一判据。
 */
public final class PoseSample {

    public final double lat;
    public final double lng;

    /** 水平精度，单位米。真机通常在 3~30 米之间波动。 */
    public final float accuracyM;

    /** 海拔，单位米；{@link Double#NaN} 表示未知。 */
    public final double altitudeM;

    /** 位置来源，如 {@code gps} / {@code network} / {@code fused}。 */
    public final String provider;

    /** 墙上时钟（毫秒）。用户可以修改系统时间，所以这个字段本身不可信。 */
    public final long timeMs;

    /** 开机以来的单调时钟（纳秒）。用户改不了，是校验时钟一致性的关键字段。 */
    public final long elapsedRealtimeNanos;

    public final float speedMps;
    public final float bearingDeg;

    /** 系统标记该位置来自模拟位置应用。 */
    public final boolean mock;

    /** 可见卫星数；-1 表示未知（例如没有定位权限或未注册 GNSS 回调）。 */
    public final int satelliteCount;

    /** 参与定位解算的卫星数；-1 表示未知。 */
    public final int usedInFixCount;

    /** 气压计推算海拔，单位米；{@link Double#NaN} 表示未知。 */
    public final double barometerAltitudeM;

    private PoseSample(Builder b) {
        this.lat = b.lat;
        this.lng = b.lng;
        this.accuracyM = b.accuracyM;
        this.altitudeM = b.altitudeM;
        this.provider = b.provider;
        this.timeMs = b.timeMs;
        this.elapsedRealtimeNanos = b.elapsedRealtimeNanos;
        this.speedMps = b.speedMps;
        this.bearingDeg = b.bearingDeg;
        this.mock = b.mock;
        this.satelliteCount = b.satelliteCount;
        this.usedInFixCount = b.usedInFixCount;
        this.barometerAltitudeM = b.barometerAltitudeM;
    }

    public boolean hasAltitude() {
        return !Double.isNaN(altitudeM);
    }

    public boolean hasBarometer() {
        return !Double.isNaN(barometerAltitudeM);
    }

    public boolean isGpsProvider() {
        return "gps".equalsIgnoreCase(provider);
    }

    public static Builder builder(double lat, double lng) {
        return new Builder(lat, lng);
    }

    @Override
    public String toString() {
        return "PoseSample{"
                + "lat=" + lat
                + ", lng=" + lng
                + ", accuracyM=" + accuracyM
                + ", provider='" + provider + '\''
                + ", timeMs=" + timeMs
                + ", elapsedRealtimeNanos=" + elapsedRealtimeNanos
                + ", speedMps=" + speedMps
                + ", bearingDeg=" + bearingDeg
                + ", mock=" + mock
                + ", satelliteCount=" + satelliteCount
                + ", usedInFixCount=" + usedInFixCount
                + '}';
    }

    /** 构造器。默认值刻意选成「未知」，避免忘记设置时被误判成异常。 */
    public static final class Builder {
        private final double lat;
        private final double lng;
        private float accuracyM = 10f;
        private double altitudeM = Double.NaN;
        private String provider = "gps";
        private long timeMs;
        private long elapsedRealtimeNanos;
        private float speedMps;
        private float bearingDeg;
        private boolean mock;
        private int satelliteCount = -1;
        private int usedInFixCount = -1;
        private double barometerAltitudeM = Double.NaN;

        private Builder(double lat, double lng) {
            this.lat = lat;
            this.lng = lng;
        }

        public Builder accuracy(float v) {
            this.accuracyM = v;
            return this;
        }

        public Builder altitude(double v) {
            this.altitudeM = v;
            return this;
        }

        public Builder provider(String v) {
            this.provider = v;
            return this;
        }

        public Builder timeMs(long v) {
            this.timeMs = v;
            return this;
        }

        public Builder elapsedRealtimeNanos(long v) {
            this.elapsedRealtimeNanos = v;
            return this;
        }

        public Builder speed(float v) {
            this.speedMps = v;
            return this;
        }

        public Builder bearing(float v) {
            this.bearingDeg = v;
            return this;
        }

        public Builder mock(boolean v) {
            this.mock = v;
            return this;
        }

        public Builder satellites(int count, int usedInFix) {
            this.satelliteCount = count;
            this.usedInFixCount = usedInFix;
            return this;
        }

        public Builder barometerAltitude(double v) {
            this.barometerAltitudeM = v;
            return this;
        }

        public PoseSample build() {
            return new PoseSample(this);
        }
    }
}
