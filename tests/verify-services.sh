#!/bin/bash

##############################################################################
# 서비스 상태 검증 유틸리티
#
# 목적: 각 서비스의 상태, 헬스체크, 연결성을 신속하게 확인
#
# 사용법:
#   chmod +x tests/verify-services.sh
#   ./tests/verify-services.sh [all|docker|redis|postgres|cassandra|websocket]
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
log_success() {
    echo -e "${GREEN}✓${NC} $*"
}

log_error() {
    echo -e "${RED}✗${NC} $*"
}

log_info() {
    echo -e "${BLUE}ℹ${NC} $*"
}

log_warn() {
    echo -e "${YELLOW}!${NC} $*"
}

log_header() {
    echo ""
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
    echo -e "${BLUE}$*${NC}"
    echo -e "${BLUE}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
}

# 헬퍼 함수들
docker_service_status() {
    local service=$1
    docker-compose ps "$service" 2>/dev/null | tail -1
}

is_service_running() {
    local service=$1
    docker-compose ps "$service" 2>/dev/null | grep -q "Up"
    return $?
}

is_service_healthy() {
    local service=$1
    local health=$(docker inspect "$service" 2>/dev/null | grep -A 3 '"Health"' | grep '"Status"' | cut -d'"' -f4)
    [[ "$health" == "healthy" ]]
    return $?
}

##############################################################################
# 각 서비스별 검증 함수
##############################################################################

verify_docker() {
    log_header "Docker Compose 서비스 상태"

    local services=("frontend" "nginx" "ws-server-1" "ws-server-2" "redis-cache" "redis-pubsub-1" "redis-pubsub-2" "postgresql" "cassandra")
    local running=0
    local healthy=0
    local total=${#services[@]}

    for service in "${services[@]}"; do
        if is_service_running "$service"; then
            ((running++))
            if is_service_healthy "$service" 2>/dev/null; then
                ((healthy++))
                log_success "$service: running (healthy)"
            else
                log_warn "$service: running (but health unknown or starting)"
            fi
        else
            log_error "$service: NOT running"
        fi
    done

    echo ""
    log_info "요약: $running/$total running, $healthy/$total healthy"

    if [ $running -eq $total ]; then
        log_success "모든 서비스가 실행 중입니다"
        return 0
    else
        log_error "일부 서비스가 실행 중이 아닙니다"
        return 1
    fi
}

verify_redis_cache() {
    log_header "Redis Cache (6379)"

    if ! is_service_running "redis-cache"; then
        log_error "redis-cache is not running"
        return 1
    fi

    local ping=$(docker exec -i redis-cache redis-cli PING 2>/dev/null)
    if [[ "$ping" == "PONG" ]]; then
        log_success "redis-cache responds to PING"
    else
        log_error "redis-cache does not respond to PING"
        return 1
    fi

    local info=$(docker exec -i redis-cache redis-cli INFO server 2>/dev/null | grep "redis_version")
    log_info "$info"

    local memory=$(docker exec -i redis-cache redis-cli INFO memory 2>/dev/null | grep "used_memory_human")
    log_info "$memory"

    local keys=$(docker exec -i redis-cache redis-cli DBSIZE 2>/dev/null | grep "keys")
    log_info "$keys"

    return 0
}

verify_redis_pubsub() {
    log_header "Redis Pub/Sub Nodes"

    local ports=(1 2)
    local healthy=0

    for port in "${ports[@]}"; do
        local service="redis-pubsub-$port"
        local redis_port=$((6379 + port))

        if ! is_service_running "$service"; then
            log_error "$service (port $redis_port) is not running"
            continue
        fi

        local ping=$(docker exec -i "$service" redis-cli -p "$redis_port" PING 2>/dev/null)
        if [[ "$ping" == "PONG" ]]; then
            log_success "$service (port $redis_port) responds to PING"
            ((healthy++))
        else
            log_error "$service (port $redis_port) does not respond to PING"
        fi

        local info=$(docker exec -i "$service" redis-cli -p "$redis_port" INFO server 2>/dev/null | grep "tcp_port")
        log_info "  $info"
    done

    echo ""
    if [ $healthy -eq 2 ]; then
        log_success "Redis Pub/Sub: both nodes healthy"
        return 0
    else
        log_error "Redis Pub/Sub: $healthy/2 nodes healthy"
        return 1
    fi
}

verify_postgresql() {
    log_header "PostgreSQL (5432)"

    if ! is_service_running "postgresql"; then
        log_error "postgresql is not running"
        return 1
    fi

    if is_service_healthy "postgresql"; then
        log_success "postgresql is healthy"
    else
        log_warn "postgresql is running but health check pending"
    fi

    # pg_isready 실행
    local ready=$(docker exec -i postgresql pg_isready -U nearbyfreinds 2>/dev/null)
    if echo "$ready" | grep -q "accepting"; then
        log_success "postgresql is accepting connections"
    else
        log_warn "postgresql: $ready"
    fi

    # 간단한 쿼리 실행
    local query_result=$(docker exec -i postgresql psql -U nearbyfreinds -d nearbyfreinds -c "SELECT 1" 2>/dev/null | tail -2)
    if echo "$query_result" | grep -q "1 row"; then
        log_success "postgresql query execution works"
    else
        log_warn "postgresql query result unclear"
    fi

    return 0
}

verify_cassandra() {
    log_header "Cassandra (9042)"

    if ! is_service_running "cassandra"; then
        log_error "cassandra is not running"
        return 1
    fi

    if is_service_healthy "cassandra"; then
        log_success "cassandra is healthy"
    else
        log_warn "cassandra is running but health check pending (Cassandra takes 1-2 minutes to start)"
        return 1
    fi

    # Cassandra 클러스터 확인
    local cluster=$(docker exec -i cassandra cqlsh -e "describe cluster" 2>/dev/null | head -5)
    if [[ -n "$cluster" ]]; then
        log_success "cassandra cluster status retrieved"
        echo "$cluster" | head -3 | sed 's/^/  /'
    else
        log_warn "cassandra cqlsh failed (still starting?)"
    fi

    return 0
}

verify_websocket() {
    log_header "WebSocket Servers"

    local servers=("ws-server-1" "ws-server-2")
    local healthy=0

    for server in "${servers[@]}"; do
        if ! is_service_running "$server"; then
            log_error "$server is not running"
            continue
        fi

        if is_service_healthy "$server"; then
            log_success "$server is healthy"
            ((healthy++))
        else
            log_warn "$server is running but health check pending"
        fi

        # 간단한 health check endpoint 확인
        local port=$((8080 + ${server: -1}))
        local health=$(curl -s "http://localhost:$port/actuator/health" 2>/dev/null | grep -o '"status":"[^"]*"' | head -1)
        if [[ -n "$health" ]]; then
            log_info "$server actuator/health: $health"
        fi
    done

    echo ""
    if [ $healthy -eq 2 ]; then
        log_success "WebSocket servers: both healthy"
        return 0
    else
        log_warn "WebSocket servers: $healthy/2 healthy"
        return 1
    fi
}

verify_nginx() {
    log_header "Nginx (80)"

    if ! is_service_running "nginx"; then
        log_error "nginx is not running"
        return 1
    fi

    if is_service_healthy "nginx"; then
        log_success "nginx is healthy"
    else
        log_warn "nginx is running but health check pending"
    fi

    # Nginx 설정 테스트
    local test=$(docker exec -i nginx nginx -t 2>&1)
    if echo "$test" | grep -q "successful"; then
        log_success "nginx configuration is valid"
    else
        log_error "nginx configuration test failed: $test"
        return 1
    fi

    return 0
}

verify_frontend() {
    log_header "Frontend (3000)"

    if ! is_service_running "frontend"; then
        log_error "frontend is not running"
        return 1
    fi

    log_success "frontend is running"

    # 간단한 HTTP 요청 확인
    local response=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:3000" 2>/dev/null)
    if [[ "$response" == "200" ]]; then
        log_success "frontend is responding to HTTP requests"
    else
        log_warn "frontend HTTP response code: $response"
    fi

    return 0
}

verify_network() {
    log_header "네트워크 연결성"

    log_info "WS서버에서 Redis Cache로의 연결:"
    docker exec -i ws-server-1 timeout 5 bash -c 'echo PING | nc -w 1 redis-cache 6379' 2>/dev/null && log_success "  Connected" || log_error "  Connection failed"

    log_info "WS서버에서 Redis Pub/Sub 1로의 연결:"
    docker exec -i ws-server-1 timeout 5 bash -c 'echo PING | nc -w 1 redis-pubsub-1 6380' 2>/dev/null && log_success "  Connected" || log_error "  Connection failed"

    log_info "WS서버에서 Redis Pub/Sub 2로의 연결:"
    docker exec -i ws-server-1 timeout 5 bash -c 'echo PING | nc -w 1 redis-pubsub-2 6381' 2>/dev/null && log_success "  Connected" || log_error "  Connection failed"

    log_info "WS서버에서 PostgreSQL로의 연결:"
    docker exec -i ws-server-1 timeout 5 bash -c 'echo "" | nc -w 1 postgresql 5432' 2>/dev/null && log_success "  Connected" || log_error "  Connection failed"

    log_info "WS서버에서 Cassandra로의 연결:"
    docker exec -i ws-server-1 timeout 5 bash -c 'echo "" | nc -w 1 cassandra 9042' 2>/dev/null && log_success "  Connected" || log_error "  Connection failed"
}

##############################################################################
# 상세 검증 리포트
##############################################################################
verify_all() {
    log_header "P6-06 서비스 검증 (전체)"

    verify_docker
    local docker_status=$?

    verify_nginx
    verify_frontend
    verify_websocket
    verify_redis_cache
    verify_redis_pubsub
    verify_postgresql
    verify_cassandra
    verify_network

    log_header "검증 완료"

    if [ $docker_status -eq 0 ]; then
        log_success "모든 서비스가 정상입니다"
        return 0
    else
        log_error "일부 서비스에 문제가 있습니다"
        return 1
    fi
}

##############################################################################
# 메인
##############################################################################
main() {
    local target="${1:-all}"

    case "$target" in
        all)
            verify_all
            ;;
        docker)
            verify_docker
            ;;
        redis)
            verify_redis_cache
            verify_redis_pubsub
            ;;
        postgres)
            verify_postgresql
            ;;
        cassandra)
            verify_cassandra
            ;;
        websocket)
            verify_websocket
            ;;
        nginx)
            verify_nginx
            ;;
        network)
            verify_network
            ;;
        frontend)
            verify_frontend
            ;;
        *)
            echo "사용법: $0 [all|docker|redis|postgres|cassandra|websocket|nginx|network|frontend]"
            echo ""
            echo "예시:"
            echo "  $0 all           # 전체 서비스 검증"
            echo "  $0 redis         # Redis Cache와 Pub/Sub 검증"
            echo "  $0 websocket     # WebSocket 서버 검증"
            exit 1
            ;;
    esac
}

main "$@"
