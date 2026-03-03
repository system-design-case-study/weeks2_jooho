package com.nearbyfreinds.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DistanceCalculatorTest {

    @Test
    @DisplayName("같은 좌표 입력 시 거리 0 반환")
    void sameCoordinatesReturnsZero() {
        // given
        int x = 500;
        int y = 300;

        // when
        double distance = DistanceCalculator.calculate(x, y, x, y);

        // then
        assertThat(distance).isEqualTo(0.0);
    }

    @Test
    @DisplayName("(0,0)-(3,4) 거리는 5.0")
    void knownDistanceThreeFourFive() {
        // when
        double distance = DistanceCalculator.calculate(0, 0, 3, 4);

        // then
        assertThat(distance).isEqualTo(5.0);
    }

    @Test
    @DisplayName("거리가 정확히 반경과 같을 때 true 반환")
    void exactlyAtRadiusBoundaryReturnsTrue() {
        // given
        // (0,0)-(3,4) = 5.0
        double radius = 5.0;

        // when
        boolean result = DistanceCalculator.isInRange(0, 0, 3, 4, radius);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("거리 < 반경일 때 true 반환")
    void withinRadiusReturnsTrue() {
        // given
        // (0,0)-(3,4) = 5.0
        double radius = 10.0;

        // when
        boolean result = DistanceCalculator.isInRange(0, 0, 3, 4, radius);

        // then
        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("거리 > 반경일 때 false 반환")
    void outsideRadiusReturnsFalse() {
        // given
        // (0,0)-(3,4) = 5.0
        double radius = 4.0;

        // when
        boolean result = DistanceCalculator.isInRange(0, 0, 3, 4, radius);

        // then
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("좌표 극값 (0,0)-(1000,1000) 거리 계산 정확성")
    void extremeCoordinatesDistance() {
        // when
        double distance = DistanceCalculator.calculate(0, 0, 1000, 1000);

        // then
        assertThat(distance).isCloseTo(1414.21, within(0.01));
    }

    @Test
    @DisplayName("기본 검색 반경 상수 값 확인")
    void defaultSearchRadiusValue() {
        assertThat(DistanceCalculator.DEFAULT_SEARCH_RADIUS).isEqualTo(200.0);
    }

    @Test
    @DisplayName("기본 검색 반경으로 isInRange 동작 확인")
    void isInRangeWithDefaultRadius() {
        // given
        // (100,100)-(200,200) = sqrt(20000) ≈ 141.42 < 200
        // (0,0)-(500,500) = sqrt(500000) ≈ 707.10 > 200

        // when / then
        assertThat(DistanceCalculator.isInRange(100, 100, 200, 200, DistanceCalculator.DEFAULT_SEARCH_RADIUS)).isTrue();
        assertThat(DistanceCalculator.isInRange(0, 0, 500, 500, DistanceCalculator.DEFAULT_SEARCH_RADIUS)).isFalse();
    }

    @Test
    @DisplayName("음수 좌표 입력 시에도 거리 계산 정상 동작")
    void negativeCoordinatesWork() {
        // when
        double distance = DistanceCalculator.calculate(-3, -4, 0, 0);

        // then
        assertThat(distance).isEqualTo(5.0);
    }

    @Test
    @DisplayName("범위 초과 좌표 입력 시에도 거리 계산 정상 동작")
    void outOfBoundsCoordinatesWork() {
        // when
        double distance = DistanceCalculator.calculate(1500, 2000, 0, 0);

        // then
        assertThat(distance).isEqualTo(2500.0);
    }
}
