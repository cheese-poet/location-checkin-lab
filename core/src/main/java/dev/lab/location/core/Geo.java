package dev.lab.location.core;

/**
 * 地理计算工具。
 *
 * <p>纯 JVM 实现，不依赖 Android，因此既能在客户端使用，也能被服务端复用同一份代码
 * （避免两端算距离的方式不一致导致的判定分歧）。
 */
public final class Geo {

    /** IUGG 平均地球半径（米）。 */
    private static final double EARTH_RADIUS_M = 6_371_008.8;

    private Geo() {
    }

    /**
     * 两点间大圆距离。
     *
     * @return 距离，单位米
     */
    public static double haversineMeters(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /**
     * 起点指向终点的初始方位角。
     *
     * @return 0~360 度
     */
    public static double initialBearingDeg(double lat1, double lng1, double lat2, double lng2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dLng = Math.toRadians(lng2 - lng1);
        double y = Math.sin(dLng) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dLng);
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
    }

    /**
     * 经纬度线性插值。仅用于把一条路线打散成密集采样点，小范围内精度足够。
     *
     * @param fraction 0~1
     * @return {@code [lat, lng]}
     */
    public static double[] lerp(double lat1, double lng1, double lat2, double lng2, double fraction) {
        return new double[]{
                lat1 + (lat2 - lat1) * fraction,
                lng1 + (lng2 - lng1) * fraction
        };
    }
}
