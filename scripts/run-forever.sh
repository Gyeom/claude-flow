#!/bin/bash
#
# 프로세스가 죽어도 자동으로 재시작하는 스크립트
#

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR/.."

# 색상
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 설정
MAX_RAPID_RESTARTS=5      # 빠른 재시작 최대 횟수
RAPID_RESTART_WINDOW=60   # 초 단위 (이 시간 내에 MAX_RAPID_RESTARTS 초과하면 대기)
COOLDOWN_PERIOD=300       # 쿨다운 대기 시간 (초)

rapid_restart_count=0
last_restart_time=0

log() {
    echo -e "[$(date '+%Y-%m-%d %H:%M:%S')] $1"
}

cleanup() {
    log "${YELLOW}Shutting down...${NC}"
    exit 0
}

trap cleanup SIGINT SIGTERM

log "${GREEN}Starting claude-flow with auto-restart...${NC}"
log "Press Ctrl+C to stop"

while true; do
    current_time=$(date +%s)

    # 빠른 재시작 감지
    if [ $last_restart_time -ne 0 ]; then
        time_since_last=$((current_time - last_restart_time))

        if [ $time_since_last -lt $RAPID_RESTART_WINDOW ]; then
            rapid_restart_count=$((rapid_restart_count + 1))

            if [ $rapid_restart_count -ge $MAX_RAPID_RESTARTS ]; then
                log "${RED}Too many rapid restarts ($rapid_restart_count in ${RAPID_RESTART_WINDOW}s)${NC}"
                log "${YELLOW}Waiting ${COOLDOWN_PERIOD}s before retry...${NC}"
                sleep $COOLDOWN_PERIOD
                rapid_restart_count=0
            fi
        else
            rapid_restart_count=0
        fi
    fi

    last_restart_time=$current_time

    log "${GREEN}Starting application...${NC}"

    cd "$PROJECT_DIR"
    SPRING_PROFILES_ACTIVE=local ./gradlew :claude-flow-app:bootRun

    exit_code=$?
    log "${RED}Process exited with code: $exit_code${NC}"

    log "${YELLOW}Restarting in 5 seconds...${NC}"
    sleep 5
done
