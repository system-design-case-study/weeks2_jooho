#!/bin/bash

##############################################################################
# 로그 분석 유틸리티
#
# 목적: 장애 시나리오 테스트 중 에러 및 경고를 신속하게 분석
#
# 사용법:
#   chmod +x tests/analyze-logs.sh
#   ./tests/analyze-logs.sh [service|all] [pattern]
#
# 예시:
#   ./tests/analyze-logs.sh ws-server-1 error
#   ./tests/analyze-logs.sh redis-cache connection
#   ./tests/analyze-logs.sh all redis
#
##############################################################################

set -o pipefail

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
GRAY='\033[0;37m'
NC='\033[0m'

# 로그 함수
log_header() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}$*${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

log_info() {
    echo -e "${BLUE}ℹ${NC} $*"
}

count_matches() {
    echo "$1" | wc -l
}

# 서비스별 로그 분석
analyze_service_logs() {
    local service=$1
    local pattern=${2:-"error\|ERROR\|exception\|Exception\|WARN\|warning"}

    log_header "$service 로그 분석"

    local logs=$(docker-compose logs --tail=200 "$service" 2>/dev/null)

    if [[ -z "$logs" ]]; then
        echo "로그를 가져올 수 없습니다"
        return 1
    fi

    # 에러 로그 추출
    local errors=$(echo "$logs" | grep -iE "$pattern" | head -20)

    if [[ -z "$errors" ]]; then
        echo -e "${GREEN}✓${NC} $service에서 주목할 만한 에러/경고를 찾지 못했습니다"
        return 0
    fi

    echo -e "${RED}발견된 에러/경고:${NC} (상위 20개)"
    echo ""

    local count=0
    while IFS= read -r line; do
        ((count++))

        # 라인에서 타임스탬프 제거 (docker-compose logs 형식)
        local clean_line=$(echo "$line" | sed 's/^[^ ]* //')

        if echo "$line" | grep -qi "error\|exception"; then
            echo -e "${RED}[$count]${NC} $clean_line"
        elif echo "$line" | grep -qi "warn"; then
            echo -e "${YELLOW}[$count]${NC} $clean_line"
        else
            echo -e "${GRAY}[$count]${NC} $clean_line"
        fi
    done <<< "$errors"

    echo ""
    local total=$(echo "$logs" | grep -iE "$pattern" | wc -l)
    log_info "총 $total개의 매칭 라인 발견"

    return 0
}

# 특정 패턴의 로그 추출
extract_pattern() {
    local service=$1
    local pattern=$2
    local lines=${3:-50}

    log_header "$service에서 패턴 '$pattern' 검색"

    local logs=$(docker-compose logs --tail=500 "$service" 2>/dev/null)
    local matches=$(echo "$logs" | grep -i "$pattern" | head -"$lines")

    if [[ -z "$matches" ]]; then
        echo "패턴과 일치하는 로그를 찾을 수 없습니다"
        return 1
    fi

    echo "$matches"
    echo ""
    log_info "총 $(echo "$matches" | wc -l)개의 로그 라인"

    return 0
}

# Redis 연결 에러 추적
analyze_redis_errors() {
    log_header "Redis 연결 에러 분석"

    local patterns=(
        "redis.*connection"
        "lettuce.*fail"
        "NOAUTH\|Invalid password"
        "timed out"
        "Connection refused"
        "redis.*unreachable"
    )

    local found=0

    for pattern in "${patterns[@]}"; do
        log_info "패턴 검색: $pattern"

        for server in ws-server-1 ws-server-2; do
            local matches=$(docker-compose logs --tail=300 "$server" 2>/dev/null | grep -iE "$pattern")

            if [[ -n "$matches" ]]; then
                echo "  $server:"
                echo "$matches" | sed 's/^/    /'
                ((found++))
            fi
        done
    done

    if [ $found -eq 0 ]; then
        echo -e "${GREEN}✓${NC} Redis 연결 에러를 찾을 수 없습니다"
    fi

    return 0
}

# Cassandra 연결 에러 추적
analyze_cassandra_errors() {
    log_header "Cassandra 연결 에러 분석"

    local patterns=(
        "cassandra.*connection"
        "cassandra.*host"
        "cassandra.*timeout"
        "All host\(s\) tried for query failed"
        "NoHostAvailableException"
    )

    local found=0

    for pattern in "${patterns[@]}"; do
        log_info "패턴 검색: $pattern"

        for server in ws-server-1 ws-server-2; do
            local matches=$(docker-compose logs --tail=300 "$server" 2>/dev/null | grep -iE "$pattern")

            if [[ -n "$matches" ]]; then
                echo "  $server:"
                echo "$matches" | sed 's/^/    /'
                ((found++))
            fi
        done
    done

    if [ $found -eq 0 ]; then
        echo -e "${GREEN}✓${NC} Cassandra 연결 에러를 찾을 수 없습니다"
    fi

    return 0
}

# 서비스 재시작/장애 추적
analyze_service_restarts() {
    log_header "서비스 재시작/장애 추적"

    local patterns=(
        "Starting\|shutdown\|Stopping"
        "HealthCheck failed\|unhealthy"
        "restarting\|exited\|died"
        "Cannot connect\|connection refused"
    )

    for service in redis-cache redis-pubsub-1 redis-pubsub-2 postgresql cassandra ws-server-1 ws-server-2; do
        log_info "$service의 상태 변화:"

        docker-compose logs --tail=200 "$service" 2>/dev/null | grep -iE "starting|shutdown|failed|restarted|exited" | tail -5 || echo "  (상태 변화 없음)"
    done

    return 0
}

# 메시지 전파 추적
analyze_message_propagation() {
    log_header "메시지 전파 추적 (Pub/Sub)"

    log_info "Redis Pub/Sub 메시지 발행 기록:"
    for server in ws-server-1 ws-server-2; do
        echo "  $server:"
        docker-compose logs --tail=300 "$server" 2>/dev/null | grep -i "publish\|subscription\|topic\|channel" | head -5 || echo "    (발행 기록 없음)"
    done

    echo ""
    log_info "Redis Pub/Sub 구독자 상태:"
    for port in 1 2; do
        echo "  redis-pubsub-$port:"
        docker exec -i "redis-pubsub-$port" redis-cli -p "638$port" INFO stats 2>/dev/null | grep "pubsub" || echo "    (구독자 정보 없음)"
    done

    return 0
}

# 위치 업데이트 추적
analyze_location_updates() {
    log_header "위치 업데이트 (Location) 추적"

    log_info "위치 관련 로그 (최근 30개):"
    for server in ws-server-1 ws-server-2; do
        echo "  $server:"
        docker-compose logs --tail=200 "$server" 2>/dev/null | grep -i "location\|position\|coordinate" | head -10 || echo "    (로그 없음)"
    done

    return 0
}

# WebSocket 연결 추적
analyze_websocket_connections() {
    log_header "WebSocket 연결 추적"

    log_info "WebSocket 연결/해제 이벤트:"
    for server in ws-server-1 ws-server-2; do
        echo "  $server:"
        docker-compose logs --tail=200 "$server" 2>/dev/null | grep -i "websocket\|ws\|connect\|disconnect\|session" | head -10 || echo "    (이벤트 없음)"
    done

    return 0
}

# 전체 분석 리포트
analyze_all() {
    log_header "전체 로그 분석 리포트"

    analyze_service_logs ws-server-1
    analyze_service_logs ws-server-2
    analyze_redis_errors
    analyze_cassandra_errors
    analyze_service_restarts

    return 0
}

##############################################################################
# 메인
##############################################################################
main() {
    local service="${1:-all}"
    local pattern="${2:-error}"

    case "$service" in
        all)
            analyze_all
            ;;
        redis)
            analyze_redis_errors
            ;;
        cassandra)
            analyze_cassandra_errors
            ;;
        ws-server-1 | ws-server-2)
            analyze_service_logs "$service" "$pattern"
            ;;
        redis-cache | redis-pubsub-1 | redis-pubsub-2 | postgresql | cassandra)
            analyze_service_logs "$service" "$pattern"
            ;;
        restarts)
            analyze_service_restarts
            ;;
        propagation)
            analyze_message_propagation
            ;;
        location)
            analyze_location_updates
            ;;
        websocket | ws)
            analyze_websocket_connections
            ;;
        extract)
            if [[ -z "$pattern" ]]; then
                echo "사용법: $0 extract <service> <pattern>"
                exit 1
            fi
            extract_pattern "$service" "$pattern"
            ;;
        *)
            echo "사용법: $0 [service|all|redis|cassandra|restarts|propagation|location|websocket|extract]"
            echo ""
            echo "옵션:"
            echo "  all              - 전체 서비스 로그 분석"
            echo "  redis            - Redis 연결 에러 분석"
            echo "  cassandra        - Cassandra 연결 에러 분석"
            echo "  ws-server-1      - WS서버 1 로그 분석"
            echo "  ws-server-2      - WS서버 2 로그 분석"
            echo "  restarts         - 서비스 재시작/장애 추적"
            echo "  propagation      - 메시지 전파 추적"
            echo "  location         - 위치 업데이트 추적"
            echo "  websocket        - WebSocket 연결 추적"
            echo "  extract <svc> <pat> - 패턴으로 로그 추출"
            echo ""
            echo "예시:"
            echo "  $0 ws-server-1          # WS서버 1의 에러 분석"
            echo "  $0 redis                # Redis 연결 에러 분석"
            echo "  $0 extract ws-server-1 error"
            exit 1
            ;;
    esac
}

main "$@"
