#!/bin/bash

##############################################################################
# P6-05: E2E 시나리오 테스트 스크립트
#
# 목적: 브라우저 탭 2개로 접속하여 전체 사용자 시나리오 수동 검증
#
# 사전 준비:
# - Docker Compose 전체 서비스 실행 중 (docker compose up -d)
# - 브라우저 탭 2개 준비 (http://localhost)
#
# 사용법:
#   chmod +x tests/e2e-scenario-test.sh
#   ./tests/e2e-scenario-test.sh
#
##############################################################################

set -o pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

PASS_COUNT=0
FAIL_COUNT=0
SKIP_COUNT=0
TOTAL_CHECKS=0

log_info() { echo -e "${BLUE}[INFO]${NC} $*"; }
log_success() { echo -e "${GREEN}[✓]${NC} $*"; }
log_error() { echo -e "${RED}[✗]${NC} $*"; }
log_warning() { echo -e "${YELLOW}[!]${NC} $*"; }
log_step() { echo -e "${CYAN}  ➜${NC} $*"; }

log_section() {
    echo ""
    echo -e "${BLUE}╔═══════════════════════════════════════════════════════════════╗${NC}"
    echo -e "${BLUE}║${NC} $*"
    echo -e "${BLUE}╚═══════════════════════════════════════════════════════════════╝${NC}"
    echo ""
}

prompt_check() {
    TOTAL_CHECKS=$((TOTAL_CHECKS + 1))
    local description="$1"
    echo ""
    echo -e "  ${YELLOW}체크 #${TOTAL_CHECKS}:${NC} ${description}"
    echo -n "  결과 [p=통과 / f=실패 / s=건너뛰기]: "
    read -r result
    case "$result" in
        p|P)
            PASS_COUNT=$((PASS_COUNT + 1))
            log_success "통과: ${description}"
            ;;
        f|F)
            FAIL_COUNT=$((FAIL_COUNT + 1))
            log_error "실패: ${description}"
            echo -n "  실패 사유 (선택): "
            read -r reason
            if [ -n "$reason" ]; then
                echo "  → ${reason}" >> /tmp/e2e-failures.log
            fi
            ;;
        *)
            SKIP_COUNT=$((SKIP_COUNT + 1))
            log_warning "건너뛰기: ${description}"
            ;;
    esac
}

wait_for_user() {
    echo ""
    echo -n "  준비되면 Enter를 누르세요..."
    read -r
}

check_services() {
    log_info "서비스 상태 확인 중..."

    local all_running=true
    for service in ws-server-1 ws-server-2 redis-pubsub-1 redis-pubsub-2 redis-cache postgres cassandra nginx frontend; do
        if docker compose ps --format '{{.Name}} {{.Status}}' 2>/dev/null | grep -q "$service.*Up"; then
            log_success "${service} 실행 중"
        else
            log_error "${service} 미실행"
            all_running=false
        fi
    done

    if [ "$all_running" = false ]; then
        log_error "일부 서비스가 실행되지 않고 있습니다."
        log_info "docker compose up -d 로 서비스를 시작하세요."
        exit 1
    fi

    echo ""
    log_info "WebSocket 서버 health 확인..."
    for port in 8080 8081; do
        if curl -s "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q "UP"; then
            log_success "WS서버 (port ${port}) healthy"
        else
            log_warning "WS서버 (port ${port}) health 확인 불가 (Nginx 뒤에 있을 수 있음)"
        fi
    done
}

print_summary() {
    log_section "테스트 결과 요약"

    echo -e "  통과: ${GREEN}${PASS_COUNT}${NC}개"
    echo -e "  실패: ${RED}${FAIL_COUNT}${NC}개"
    echo -e "  건너뛰기: ${YELLOW}${SKIP_COUNT}${NC}개"
    echo -e "  전체: ${TOTAL_CHECKS}개"
    echo ""

    if [ "$FAIL_COUNT" -eq 0 ]; then
        log_success "모든 체크를 통과했습니다!"
    else
        log_error "${FAIL_COUNT}개 체크가 실패했습니다."
        if [ -f /tmp/e2e-failures.log ]; then
            echo ""
            log_info "실패 사유:"
            cat /tmp/e2e-failures.log
            rm -f /tmp/e2e-failures.log
        fi
    fi
}

##############################################################################
# 메인 실행
##############################################################################

rm -f /tmp/e2e-failures.log

log_section "P6-05: E2E 시나리오 테스트"

log_info "이 테스트는 브라우저 탭 2개를 사용하여 수동으로 진행합니다."
log_info "각 단계의 지시를 따르고, 확인 결과를 입력하세요."
echo ""

check_services

##############################################################################
# 시나리오 1: 접속 및 초기화
##############################################################################

log_section "시나리오 1: 접속 및 초기화"

log_step "브라우저에서 탭 A를 열어 http://localhost 에 접속하세요."
wait_for_user

prompt_check "탭 A에 자동으로 userId가 부여됨 (예: user-1)"
prompt_check "XY 좌표 캔버스에 도트 캐릭터가 랜덤 위치에 배치됨"
prompt_check "도트 캐릭터 색상이 userId 기반 HSL 색상으로 자동 생성됨"
prompt_check "자기 캐릭터가 다른 캐릭터보다 1.5배 크게 표시됨"

log_step "브라우저에서 탭 B를 열어 http://localhost 에 접속하세요."
wait_for_user

prompt_check "탭 B에 별도의 userId가 부여됨 (예: user-2)"
prompt_check "탭 A의 접속자 목록에 탭 B의 사용자가 표시됨"
prompt_check "탭 B의 접속자 목록에 탭 A의 사용자가 표시됨"

log_info "시스템 상태 API 확인:"
echo -e "  ${CYAN}curl -s http://localhost/api/system/connections | jq .${NC}"
curl -s http://localhost/api/system/connections 2>/dev/null | python3 -m json.tool 2>/dev/null || log_warning "API 응답 확인 불가"

##############################################################################
# 시나리오 2: 친구 추가
##############################################################################

log_section "시나리오 2: 친구 추가"

log_step "탭 A에서 탭 B의 userId를 친구로 추가하세요."
log_step "탭 B에서 탭 A의 userId를 친구로 추가하세요."
wait_for_user

prompt_check "탭 A의 친구 목록에 탭 B 사용자가 표시됨"
prompt_check "탭 B의 친구 목록에 탭 A 사용자가 표시됨"
prompt_check "탭 A의 캔버스에 탭 B의 도트 캐릭터가 표시됨 (원래 색상, 비투명)"
prompt_check "탭 B의 캔버스에 탭 A의 도트 캐릭터가 표시됨 (원래 색상, 비투명)"

##############################################################################
# 시나리오 3: 위치 이동 → 실시간 반영
##############################################################################

log_section "시나리오 3: 위치 이동 → 실시간 반영"

log_step "탭 A에서 도트 캐릭터를 드래그하여 위치를 이동하세요."
log_step "동시에 탭 B를 관찰하세요."
wait_for_user

prompt_check "탭 A에서 위치 이동 → 탭 B의 캔버스에서 탭 A의 캐릭터 위치가 실시간으로 변경됨"
prompt_check "체감 지연이 200ms 이내 (거의 즉시 반영)"

log_step "이번에는 반대로 탭 B에서 드래그하여 이동하고, 탭 A를 관찰하세요."
wait_for_user

prompt_check "탭 B에서 위치 이동 → 탭 A의 캔버스에서 탭 B의 캐릭터 위치가 실시간으로 변경됨"

##############################################################################
# 시나리오 4: 반경 내/외 필터링 동작
##############################################################################

log_section "시나리오 4: 반경 내/외 필터링 동작"

log_step "탭 A의 캐릭터를 캔버스 좌상단 영역(100, 100 근처)으로 드래그하세요."
log_step "탭 B의 캐릭터를 탭 A 캐릭터 근처(200, 200 근처)로 드래그하세요."
log_step "거리 약 141 → 기본 반경 200 이내여야 합니다."
wait_for_user

prompt_check "탭 A에서 탭 B의 캐릭터가 원래 색상(반경 내)으로 표시됨"
prompt_check "탭 A에서 탭 B 캐릭터 근처에 거리 값이 표시됨 (예: 141)"

log_step "이제 탭 B의 캐릭터를 캔버스 우하단(900, 900 근처)으로 드래그하세요."
log_step "거리 약 1131 → 기본 반경 200 밖이어야 합니다."
wait_for_user

prompt_check "탭 A에서 탭 B의 캐릭터가 회색(반경 밖)으로 변경됨"
prompt_check "탭 A에서 탭 B 캐릭터 근처의 거리 값이 사라짐 (반경 밖이므로)"

log_step "탭 A에서 검색 반경 슬라이더를 최대(500)로 올려보세요."
wait_for_user

prompt_check "반경 원 크기가 즉시 커짐"

log_step "탭 A에서 검색 반경 슬라이더를 최소(50)로 줄여보세요."
wait_for_user

prompt_check "반경 원 크기가 즉시 작아짐"

##############################################################################
# 시나리오 5: 전파 경로 로그 확인
##############################################################################

log_section "시나리오 5: 전파 경로 로그 확인"

log_step "탭 A에서 도트 캐릭터를 드래그하여 위치를 여러 번 이동하세요."
log_step "전파 경로 로그 패널(PropagationLogPanel)을 확인하세요."
wait_for_user

prompt_check "PROPAGATION_LOG 수신 시 로그 패널에 새 항목이 추가됨"
prompt_check "전파 경로에 소스 사용자(user-X) → WS서버(ws-X) → Redis 노드(redis-pubsub-X) 가 표시됨"
prompt_check "수신 측에 WS서버(ws-X) → 대상 사용자(user-X) 경로가 표시됨"
prompt_check "노드 타입별 색상 구분: 사용자=파랑, WS서버=초록, Redis=주황"
prompt_check "거리 계산 결과 (distance)와 inRange 여부가 표시됨"
prompt_check "시간순으로 정렬되어 최신 로그가 위에 표시됨"

##############################################################################
# 시나리오 6: 시스템 시각화 패널 확인
##############################################################################

log_section "시나리오 6: 시스템 시각화 패널 확인"

log_step "Hash Ring 다이어그램을 확인하세요."
wait_for_user

prompt_check "Hash Ring 원형 다이어그램에 Redis 노드(물리/가상)가 표시됨"
prompt_check "채널이 Hash Ring에 매핑되어 표시됨"

log_step "인프라 상태 패널을 확인하세요."
wait_for_user

prompt_check "WS서버별 연결 사용자 목록이 표시됨"
prompt_check "Redis 노드별 구독 채널 목록이 표시됨"
prompt_check "Redis 캐시 위치 목록이 표시됨"

##############################################################################
# 시나리오 7: 비정상 시나리오
##############################################################################

log_section "시나리오 7: 비정상 시나리오"

log_step "탭 A를 닫으세요 (브라우저 탭 X 클릭)."
log_step "탭 B에서 변화를 관찰하세요."
wait_for_user

prompt_check "탭 B의 접속자 목록에서 탭 A 사용자가 사라지거나 비활성 표시됨"
prompt_check "탭 B의 캔버스에서 탭 A의 캐릭터가 사라짐"

log_step "탭 A를 다시 열어 http://localhost 에 접속하세요."
wait_for_user

prompt_check "탭 A에 새로운 userId가 부여됨 (이전과 다른 ID)"

log_step "브라우저 개발자 도구에서 Network 탭의 Offline 모드를 켜세요 (탭 A)."
log_step "잠시 후 Offline 모드를 해제하세요."
wait_for_user

prompt_check "네트워크 끊김 후 자동 재연결이 동작함 (콘솔 로그 또는 UI 상태로 확인)"

##############################################################################
# 결과 요약
##############################################################################

print_summary
