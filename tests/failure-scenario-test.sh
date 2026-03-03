#!/bin/bash

##############################################################################
# P6-06: 장애 시나리오 테스트 스크립트
#
# 목적: Redis Pub/Sub, WS서버, Redis Cache, Cassandra 장애 시나리오 검증
#
# 주의사항:
# - 이 스크립트는 테스트 케이스를 정의하고, 각 단계별로 검증 명령어를 제공합니다.
# - 실제 docker stop/start 명령은 사용자가 수동으로 실행해야 합니다.
# - 각 시나리오 후 반드시 서비스를 복구(docker start)하여 다음 테스트에 영향을 주지 않도록 합니다.
#
# 사용법:
#   chmod +x tests/failure-scenario-test.sh
#   ./tests/failure-scenario-test.sh
#
##############################################################################

set -o pipefail

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 로그 함수
log_info() {
    echo -e "${BLUE}[INFO]${NC} $*"
}

log_success() {
    echo -e "${GREEN}[✓]${NC} $*"
}

log_error() {
    echo -e "${RED}[✗]${NC} $*"
}

log_warning() {
    echo -e "${YELLOW}[!]${NC} $*"
}

log_section() {
    echo ""
    echo -e "${BLUE}╔═══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC} $*"
    echo -e "${BLUE}╚═══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

# 헬퍼 함수
check_docker_service() {
    local service=$1
    local expected_state=$2

    local state=$(docker-compose ps "$service" 2>/dev/null | tail -1 | awk '{print $NF}')

    if [[ "$state" == "$expected_state" ]]; then
        return 0
    else
        return 1
    fi
}

wait_service_healthy() {
    local service=$1
    local timeout=30
    local elapsed=0

    log_info "Waiting for $service to become healthy (max ${timeout}s)..."

    while [ $elapsed -lt $timeout ]; do
        local health=$(docker inspect "$service" 2>/dev/null | grep -A 3 '"Health"' | grep '"Status"' | cut -d'"' -f4)

        if [[ "$health" == "healthy" ]]; then
            log_success "$service is healthy"
            return 0
        fi

        sleep 2
        elapsed=$((elapsed + 2))
    done

    log_error "$service did not become healthy within ${timeout}s"
    return 1
}

get_ws_server_logs() {
    local server=$1
    local lines=${2:-50}
    docker-compose logs --tail="$lines" "$server" 2>/dev/null
}

redis_cli_cache() {
    docker exec -i redis-cache redis-cli "$@" 2>/dev/null
}

redis_cli_pubsub() {
    local port=$1
    shift
    docker exec -i "redis-pubsub-${port}" redis-cli -p "638${port}" "$@" 2>/dev/null
}

check_ws_server_errors() {
    local server=$1
    local pattern=$2

    get_ws_server_logs "$server" 100 | grep -i "$pattern" | head -5
}

##############################################################################
# 시나리오 1: Redis Pub/Sub 노드 1개 중지 → 부분 서비스 유지
##############################################################################
scenario_1_redis_pubsub_node_down() {
    log_section "시나리오 1: Redis Pub/Sub 노드 1개 중지 → 부분 서비스 유지"

    log_info "단계 1: 정상 상태 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "Redis Pub/Sub 노드 상태 확인:"
    redis_cli_pubsub 1 "PING" && log_success "redis-pubsub-1 is responding" || log_error "redis-pubsub-1 is down"
    redis_cli_pubsub 2 "PING" && log_success "redis-pubsub-2 is responding" || log_error "redis-pubsub-2 is down"

    log_info "WS서버 상태 확인:"
    docker-compose ps ws-server-1 ws-server-2 | grep -E "(ws-server|Up|Exit)" || log_error "Cannot check WS server status"

    log_info ""
    log_warning "### 사용자 작업: Redis Pub/Sub 노드 1 중지 ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker stop redis-pubsub-1${NC}"
    echo ""
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 2: 장애 상태 검증"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "redis-pubsub-1 상태 확인 (응답 없어야 함):"
    if redis_cli_pubsub 1 "PING" 2>&1 | grep -q "PONG"; then
        log_error "redis-pubsub-1 is still responding (장애 유발 실패)"
    else
        log_success "redis-pubsub-1은 응답하지 않음 (장애 정상 유발됨)"
    fi

    log_info "redis-pubsub-2 상태 확인 (정상 작동해야 함):"
    if redis_cli_pubsub 2 "PING" 2>&1 | grep -q "PONG"; then
        log_success "redis-pubsub-2는 정상 작동함"
    else
        log_error "redis-pubsub-2도 응답하지 않음 (예상 밖)"
    fi

    log_info "WS서버의 Redis 연결 에러 로그 확인:"
    log_info "ws-server-1의 최근 에러:"
    check_ws_server_errors "ws-server-1" "redis\|connection\|io.lettu" || log_info "(에러 로그 없음)"

    log_info "ws-server-2의 최근 에러:"
    check_ws_server_errors "ws-server-2" "redis\|connection\|io.lettu" || log_info "(에러 로그 없음)"

    log_info ""
    log_info "단계 3: Hash Ring 기반 메시지 유실 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
    log_info "예상 동작:"
    log_info "  - redis-pubsub-1에 매핑된 채널의 메시지는 영구 소실됨 (Pub/Sub fire-and-forget)"
    log_info "  - redis-pubsub-2에 매핑된 채널의 메시지는 정상 전파됨"
    log_info "  - 사용자가 친구를 추가했을 때, 일부 친구의 위치는 업데이트되지 않을 수 있음"

    log_info ""
    log_warning "### 사용자 작업: Redis Pub/Sub 노드 1 복구 ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker start redis-pubsub-1${NC}"
    echo ""
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 4: 복구 후 정상 동작 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    if wait_service_healthy "redis-pubsub-1"; then
        log_success "redis-pubsub-1 복구됨"
    else
        log_error "redis-pubsub-1 복구 실패"
        return 1
    fi

    log_info "redis-pubsub-1 상태 확인:"
    if redis_cli_pubsub 1 "PING" 2>&1 | grep -q "PONG"; then
        log_success "redis-pubsub-1은 정상 작동함"
    else
        log_error "redis-pubsub-1 여전히 응답 없음"
    fi

    log_success "시나리오 1 완료"
    return 0
}

##############################################################################
# 시나리오 2: WS서버 1개 중지 → 다른 WS서버 사용자 영향 없음
##############################################################################
scenario_2_ws_server_down() {
    log_section "시나리오 2: WS서버 1개 중지 → 다른 WS서버 사용자 영향 없음"

    log_info "단계 1: 정상 상태 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "WS서버 상태:"
    docker-compose ps ws-server-1 ws-server-2 | tail -3

    log_info "Nginx 상태:"
    docker-compose ps nginx | tail -1

    log_info ""
    log_info "테스트 설정:"
    log_info "  - 브라우저 탭 A: ws://localhost/ws/location (Nginx round-robin → ws-server-1)"
    log_info "  - 브라우저 탭 B: ws://localhost/ws/location (Nginx round-robin → ws-server-2)"
    log_info "  - 각 탭에서 위치 업데이트를 시뮬레이션하고 메시지 전파 확인"

    log_info ""
    log_warning "### 사용자 작업: 브라우저 탭 A와 B 각각 ws://localhost/ws/location에 연결 ###"
    log_info "엔터를 누르세요..."
    read -r

    log_info ""
    log_warning "### 사용자 작업: WS서버 1 중지 ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker stop ws-server-1${NC}"
    echo ""
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 2: 장애 상태 검증"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "ws-server-1 상태 확인:"
    if docker-compose ps ws-server-1 2>/dev/null | grep -q "Exit"; then
        log_success "ws-server-1은 중지됨"
    else
        log_error "ws-server-1이 여전히 실행 중"
    fi

    log_info ""
    log_info "예상 동작:"
    log_info "  - 탭 A의 WebSocket 연결이 끊어짐 (Connection closed)"
    log_info "  - 탭 B의 WebSocket 연결은 유지됨 (ws-server-2에서 처리)"
    log_info "  - 탭 B에서 위치 업데이트는 정상 동작"

    log_info ""
    log_warning "### 사용자 작업: 탭 A의 연결 상태 확인 및 탭 B의 정상 동작 확인 ###"
    log_info "엔터를 누르세요..."
    read -r

    log_info ""
    log_warning "### 사용자 작업: 탭 A 새로고침 (ws-server-2로 재연결) ###"
    log_info "F5 또는 Ctrl+R을 누르세요. 완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 3: WS서버 1 복구"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_warning "### 사용자 작업: WS서버 1 복구 ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker start ws-server-1${NC}"
    echo ""
    log_info "완료 후 엔터를 누르세요..."
    read -r

    if wait_service_healthy "ws-server-1"; then
        log_success "ws-server-1 복구됨"
    else
        log_error "ws-server-1 복구 실패"
        return 1
    fi

    log_info ""
    log_info "단계 4: 복구 후 정상 동작 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "ws-server-1 상태:"
    docker-compose ps ws-server-1 | tail -1

    log_info ""
    log_info "예상 동작:"
    log_info "  - 새로 접속하는 사용자가 Nginx round-robin으로 ws-server-1에도 연결될 수 있음"
    log_info "  - ws-server-1과 ws-server-2의 Pub/Sub 채널 구독이 동시에 활성화됨"

    log_info ""
    log_warning "### 사용자 작업: 새 브라우저 탭 C에서 ws://localhost/ws/location에 연결 ###"
    log_info "엔터를 누르세요..."
    read -r

    log_success "시나리오 2 완료"
    return 0
}

##############################################################################
# 시나리오 3: Redis Cache 중지 → 위치 갱신 실패 시 에러 처리
##############################################################################
scenario_3_redis_cache_down() {
    log_section "시나리오 3: Redis Cache 중지 → 위치 갱신 실패 시 에러 처리"

    log_info "단계 1: 정상 상태 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "Redis Cache 상태:"
    if redis_cli_cache "PING" 2>&1 | grep -q "PONG"; then
        log_success "redis-cache is responding"
    else
        log_error "redis-cache는 응답하지 않음"
    fi

    log_info "Redis Cache의 캐시된 위치 데이터 확인:"
    local cache_keys=$(redis_cli_cache "KEYS" "location:*" 2>/dev/null | wc -l)
    log_info "캐시된 위치 키 개수: $cache_keys"

    log_info ""
    log_warning "### 사용자 작업: Redis Cache 중지 ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker stop redis-cache${NC}"
    echo ""
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 2: 장애 상태 검증"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "redis-cache 상태:"
    if redis_cli_cache "PING" 2>&1 | grep -q "PONG"; then
        log_error "redis-cache는 여전히 응답 중"
    else
        log_success "redis-cache는 응답하지 않음 (장애 정상 유발됨)"
    fi

    log_info ""
    log_info "예상 동작:"
    log_info "  - 위치 업데이트 시도 시 WS서버에서 에러 발생"
    log_info "  - Redis Cache 연결 실패 에러가 로그에 기록됨"
    log_info "  - Cassandra 이력 저장은 계속 동작 (독립적인 경로)"
    log_info "  - Redis Pub/Sub 발행은 계속 동작 (독립적인 경로)"
    log_info "  - 초기 접속 시 캐시에서 친구 위치 조회 실패"

    log_info ""
    log_warning "### 사용자 작업: 브라우저에서 위치 업데이트 시뮬레이션 ###"
    log_info "연결된 WebSocket에서 위치 업데이트 메시지를 전송하세요."
    log_info "콘솔에서 에러를 확인한 후 엔터를 누르세요..."
    read -r

    log_info "WS서버 로그에서 Redis 연결 에러 확인:"
    check_ws_server_errors "ws-server-1" "redis\|cache\|connection" || log_info "(에러 로그 없음)"

    log_info ""
    log_warning "### 사용자 작업: Redis Cache 복구 ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker start redis-cache${NC}"
    echo ""
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 3: 복구 후 정상 동작 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    if wait_service_healthy "redis-cache"; then
        log_success "redis-cache 복구됨"
    else
        log_error "redis-cache 복구 실패"
        return 1
    fi

    log_info "redis-cache 정상 작동 확인:"
    if redis_cli_cache "PING" 2>&1 | grep -q "PONG"; then
        log_success "redis-cache는 정상 작동함"
    else
        log_error "redis-cache 여전히 응답 없음"
    fi

    log_info ""
    log_warning "### 사용자 작업: 위치 업데이트 재시도 ###"
    log_info "브라우저에서 위치 업데이트를 다시 시도하고 정상 동작 확인"
    log_info "엔터를 누르세요..."
    read -r

    log_success "시나리오 3 완료"
    return 0
}

##############################################################################
# 시나리오 4: Docker restart 후 재연결 확인
##############################################################################
scenario_4_docker_restart() {
    log_section "시나리오 4: Docker restart 후 재연결 확인"

    log_info "단계 1: 정상 상태 설정"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "예상 설정:"
    log_info "  - 브라우저 탭 A: ws://localhost/ws/location 연결"
    log_info "  - 브라우저 탭 B: ws://localhost/ws/location 연결"
    log_info "  - 탭 A와 B에서 서로 친구 관계 설정"
    log_info "  - 탭 A의 위치를 업데이트하고 탭 B에서 수신 확인"

    log_info ""
    log_warning "### 사용자 작업: 브라우저 탭 A, B 설정 및 친구 관계 추가 ###"
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info ""
    log_warning "### 사용자 작업: WS서버 1 restart ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker restart ws-server-1${NC}"
    echo ""
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 2: 재시작 중 연결 상태 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "예상 동작:"
    log_info "  - ws-server-1에 연결된 클라이언트의 WebSocket 연결이 끊어짐"
    log_info "  - 브라우저 콘솔에서 WebSocket 'close' 이벤트 기록 (정상)"

    log_info ""
    log_warning "### 사용자 작업: 브라우저 콘솔 확인 ###"
    log_info "WebSocket close 이벤트를 확인한 후 엔터를 누르세요..."
    read -r

    log_info ""
    log_info "단계 3: 자동 재연결 및 상태 복구 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    if wait_service_healthy "ws-server-1"; then
        log_success "ws-server-1 재시작 완료"
    else
        log_error "ws-server-1 재시작 실패"
        return 1
    fi

    log_info ""
    log_info "예상 동작:"
    log_info "  - 브라우저가 지수 백오프를 사용하여 자동 재연결 시도"
    log_info "  - 5-10초 내에 재연결 성공 (초기 간격 1s, 최대 10s)"
    log_info "  - 재연결 후 INIT 메시지 수신"
    log_info "  - PostgreSQL에서 친구 관계 재로드"
    log_info "  - Redis Cache에서 위치 재로드 (캐시가 살아있는 경우)"
    log_info "  - Redis Pub/Sub 채널 재구독"

    log_warning "### 사용자 작업: 브라우저 자동 재연결 관찰 ###"
    log_info "Network 탭에서 새로운 WebSocket 연결을 확인한 후 엔터를 누르세요..."
    read -r

    log_info ""
    log_info "단계 4: 상태 복구 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "예상 동작:"
    log_info "  - 친구 관계가 복구됨 (PostgreSQL 재로드)"
    log_info "  - 친구의 위치가 표시됨 (Redis Cache 또는 Pub/Sub에서 로드)"
    log_info "  - 탭 A의 위치 업데이트가 다시 작동"
    log_info "  - 탭 B에서 탭 A의 위치 업데이트 수신"

    log_warning "### 사용자 작업: 상태 복구 확인 ###"
    log_info "브라우저에서 친구 관계와 위치가 정상 표시되는지 확인 후 엔터를 누르세요..."
    read -r

    log_success "시나리오 4 완료"
    return 0
}

##############################################################################
# 시나리오 5: Cassandra 중지 → 이력 저장 실패 시 동작
##############################################################################
scenario_5_cassandra_down() {
    log_section "시나리오 5: Cassandra 중지 → 이력 저장 실패 시 동작"

    log_info "단계 1: 정상 상태 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "Cassandra 상태:"
    docker-compose ps cassandra | tail -1

    log_info ""
    log_info "예상 설정:"
    log_info "  - 브라우저에서 WebSocket에 연결된 상태"
    log_info "  - 친구 관계가 설정된 상태"

    log_info ""
    log_warning "### 사용자 작업: Cassandra 중지 ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker stop cassandra${NC}"
    echo ""
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 2: 장애 상태 검증"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "Cassandra 상태:"
    if docker-compose ps cassandra 2>/dev/null | grep -q "Exit"; then
        log_success "Cassandra는 중지됨"
    else
        log_error "Cassandra가 여전히 실행 중"
    fi

    log_info ""
    log_info "WS서버 로그에서 Cassandra 연결 에러 확인:"
    check_ws_server_errors "ws-server-1" "cassandra\|connection" || log_info "(에러 로그 없음)"

    log_info ""
    log_warning "### 사용자 작업: 브라우저에서 위치 업데이트 시뮬레이션 ###"
    log_info "위치 업데이트 메시지를 전송하세요."
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 3: 실시간 위치 전파 검증"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    log_info "예상 동작:"
    log_info "  - Cassandra 이력 저장은 실패함"
    log_info "  - WS서버 로그에 Cassandra 연결 실패 에러 기록"
    log_info "  - Redis Cache 갱신은 정상 동작 (독립적인 경로)"
    log_info "  - Redis Pub/Sub 발행은 정상 동작 (독립적인 경로)"
    log_info "  - 실시간 위치 전파에 영향 없음 (친구가 위치 업데이트를 수신함)"
    log_info ""
    log_info "즉, 사용자 관점에서는 위치가 실시간으로 전파되지만,"
    log_info "백엔드는 이력 저장에 실패하는 상황입니다."

    log_warning "### 사용자 작업: Redis Cache에서 위치 확인 ###"
    log_info "다른 브라우저 탭에서 위치 업데이트가 정상적으로 수신되는지 확인"
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info ""
    log_warning "### 사용자 작업: Cassandra 복구 ###"
    log_info "다음 명령을 실행하세요:"
    echo -e "${YELLOW}docker start cassandra${NC}"
    echo ""
    log_info "주의: Cassandra는 시작 시간이 오래 걸릴 수 있습니다 (1-2분)"
    log_info "완료 후 엔터를 누르세요..."
    read -r

    log_info "단계 4: 복구 후 정상 동작 확인"
    log_info "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

    if wait_service_healthy "cassandra"; then
        log_success "Cassandra 복구됨"
    else
        log_error "Cassandra 복구 실패 (최대 2분까지 걸릴 수 있습니다)"
        log_info "docker-compose logs cassandra를 확인하세요"
        return 1
    fi

    log_info "Cassandra 상태:"
    docker-compose ps cassandra | tail -1

    log_info ""
    log_warning "### 사용자 작업: 위치 업데이트 재시도 ###"
    log_info "이력이 정상적으로 저장되는지 확인 후 엔터를 누르세요..."
    read -r

    log_success "시나리오 5 완료"
    return 0
}

##############################################################################
# 종합 테스트 결과 보고
##############################################################################
print_summary() {
    log_section "장애 시나리오 테스트 완료"

    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║              테스트 기준 체크리스트                              ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "아래 항목들을 수동으로 검증하고 체크하세요:"
    echo ""
    echo "□ Redis Pub/Sub 1개 중지 시 나머지 노드의 채널은 정상 전파"
    echo "□ Redis Pub/Sub 1개 중지 시 해당 노드의 채널 메시지 유실 확인 및 에러 로깅"
    echo "□ WS서버 1개 중지 시 다른 WS서버 사용자 영향 없음"
    echo "□ WS서버 중지된 서버에 연결된 사용자의 연결 끊김 + 재연결 가능"
    echo "□ Redis Cache 중지 시 위치 갱신 실패하지만 Pub/Sub 전파는 유지"
    echo "□ Docker restart 후 클라이언트 자동 재연결 + 상태 복구"
    echo "□ Cassandra 중지 시 실시간 위치 전파에 영향 없음"
    echo "□ 장애 시나리오별 기대 동작 확인 완료"
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                  주의사항 및 참고사항                            ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "1. WebSocket 서버는 Stateful이므로 컨테이너 재시작 = 모든 연결 손실"
    echo "2. Redis Pub/Sub는 fire-and-forget: 구독자 없는 채널의 메시지는 영구 소실"
    echo "3. Lettuce 클라이언트는 끊긴 TCP 연결에서 수 시간 대기할 수 있음"
    echo "4. TCP keepalive 및 Lettuce reconnect 옵션이 올바르게 구성되었는지 확인"
    echo "5. Pub/Sub 콜백에서 블로킹 호출이 있으면 장애 시 파이프라인이 중단됨"
    echo ""
    echo "╔════════════════════════════════════════════════════════════════╗"
    echo "║                       로그 확인 명령어                          ║"
    echo "╚════════════════════════════════════════════════════════════════╝"
    echo ""
    echo "docker-compose logs ws-server-1"
    echo "docker-compose logs ws-server-2"
    echo "docker-compose logs redis-cache"
    echo "docker-compose logs redis-pubsub-1"
    echo "docker-compose logs redis-pubsub-2"
    echo "docker-compose logs cassandra"
    echo ""
}

##############################################################################
# 메인 실행
##############################################################################
main() {
    log_section "P6-06 장애 시나리오 테스트"

    log_info "이 스크립트는 5가지 장애 시나리오를 검증합니다:"
    log_info "  1. Redis Pub/Sub 노드 1개 중지"
    log_info "  2. WS서버 1개 중지"
    log_info "  3. Redis Cache 중지"
    log_info "  4. Docker restart 후 재연결"
    log_info "  5. Cassandra 중지"
    echo ""
    log_warning "주의: 이 스크립트는 각 단계에서 대기하며, 사용자가 docker stop/start 명령을 수동으로 실행해야 합니다."
    echo ""

    local failed=0

    scenario_1_redis_pubsub_node_down || ((failed++))
    scenario_2_ws_server_down || ((failed++))
    scenario_3_redis_cache_down || ((failed++))
    scenario_4_docker_restart || ((failed++))
    scenario_5_cassandra_down || ((failed++))

    print_summary

    if [ $failed -eq 0 ]; then
        log_success "모든 시나리오가 완료되었습니다!"
        return 0
    else
        log_error "$failed개의 시나리오에서 문제가 발생했습니다"
        return 1
    fi
}

main "$@"
