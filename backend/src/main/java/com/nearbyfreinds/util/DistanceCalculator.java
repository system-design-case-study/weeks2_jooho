package com.nearbyfreinds.util;

/**
 * XY 좌표 평면(0~1000)에서 Euclidean 거리 계산 및 반경 판정을 수행하는 유틸리티 클래스.
 */
public final class DistanceCalculator {

    public static final double DEFAULT_SEARCH_RADIUS = 200.0;

    private DistanceCalculator() {
    }

    /**
     * 두 좌표 간 Euclidean 거리를 계산한다.
     *
     * @param x1 첫 번째 점의 x 좌표
     * @param y1 첫 번째 점의 y 좌표
     * @param x2 두 번째 점의 x 좌표
     * @param y2 두 번째 점의 y 좌표
     * @return 두 점 사이의 Euclidean 거리
     */
    public static double calculate(int x1, int y1, int x2, int y2) {
        return Math.sqrt(Math.pow(x2 - x1, 2) + Math.pow(y2 - y1, 2));
    }

    /**
     * 두 좌표가 지정된 검색 반경 내에 있는지 판정한다.
     * sqrt 연산을 생략하고 제곱 비교로 최적화한다.
     *
     * @param x1 첫 번째 점의 x 좌표
     * @param y1 첫 번째 점의 y 좌표
     * @param x2 두 번째 점의 x 좌표
     * @param y2 두 번째 점의 y 좌표
     * @param searchRadius 검색 반경
     * @return 두 점 사이의 거리가 searchRadius 이하이면 true
     */
    public static boolean isInRange(int x1, int y1, int x2, int y2, double searchRadius) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        return dx * dx + dy * dy <= searchRadius * searchRadius;
    }
}
