#!/bin/bash
#
# Claude Flow - 원클릭 시작 스크립트
#
# 사용법:
#   ./start.sh              # 기본 실행 (인터랙티브 설정)
#   ./start.sh --quick      # 기존 설정으로 빠른 시작
#   ./start.sh --with-rag   # RAG 기능 포함 실행
#   ./start.sh --stop       # 서비스 중지
#   ./start.sh --status     # 상태 확인
#   ./start.sh --backup     # n8n 워크플로우 백업
#   ./start.sh --logs       # 로그 확인
#

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
NC='\033[0m'

# 프로젝트 루트
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCKER_DIR="$SCRIPT_DIR/docker-compose"
ENV_FILE="$DOCKER_DIR/.env"
ENV_EXAMPLE="$DOCKER_DIR/.env.example"

# 배너 출력
print_banner() {
    echo -e "${CYAN}"
    echo "╔══════════════════════════════════════════════════════════╗"
    echo "║                                                          ║"
    echo "║     ██████╗██╗      █████╗ ██╗   ██╗██████╗ ███████╗     ║"
    echo "║    ██╔════╝██║     ██╔══██╗██║   ██║██╔══██╗██╔════╝     ║"
    echo "║    ██║     ██║     ███████║██║   ██║██║  ██║█████╗       ║"
    echo "║    ██║     ██║     ██╔══██║██║   ██║██║  ██║██╔══╝       ║"
    echo "║    ╚██████╗███████╗██║  ██║╚██████╔╝██████╔╝███████╗     ║"
    echo "║     ╚═════╝╚══════╝╚═╝  ╚═╝ ╚═════╝ ╚═════╝ ╚══════╝     ║"
    echo "║                     F L O W                              ║"
    echo "║                                                          ║"
    echo "║          AI Agent Platform for Slack & GitLab            ║"
    echo "╚══════════════════════════════════════════════════════════╝"
    echo -e "${NC}"
}

# 도움말
print_help() {
    echo "Usage: ./start.sh [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  (없음)        인터랙티브 설정 후 시작"
    echo "  --quick       기존 설정으로 빠른 시작"
    echo "  --with-rag    RAG 기능 포함 시작 (Qdrant + Ollama)"
    echo "  --stop        모든 서비스 중지"
    echo "  --status      서비스 상태 확인"
    echo "  --backup      n8n 워크플로우 백업"
    echo "  --logs        실시간 로그 확인"
    echo "  --reset       모든 데이터 초기화 (주의!)"
    echo "  --help        이 도움말 출력"
}

# 의존성 확인
check_dependencies() {
    echo -e "${YELLOW}[1/5] 의존성 확인...${NC}"

    if ! command -v docker &> /dev/null; then
        echo -e "${RED}✗ Docker가 설치되어 있지 않습니다.${NC}"
        echo "  설치: https://docs.docker.com/get-docker/"
        exit 1
    fi
    echo -e "${GREEN}✓ Docker 설치됨${NC}"

    if ! command -v docker-compose &> /dev/null && ! docker compose version &> /dev/null; then
        echo -e "${RED}✗ Docker Compose가 설치되어 있지 않습니다.${NC}"
        exit 1
    fi
    echo -e "${GREEN}✓ Docker Compose 설치됨${NC}"

    # Docker 실행 확인
    if ! docker info &> /dev/null; then
        echo -e "${RED}✗ Docker가 실행되고 있지 않습니다.${NC}"
        echo "  Docker Desktop을 실행해주세요."
        exit 1
    fi
    echo -e "${GREEN}✓ Docker 실행 중${NC}"
}

# 환경 설정
setup_environment() {
    echo -e "${YELLOW}[2/5] 환경 설정...${NC}"

    if [ ! -f "$ENV_FILE" ]; then
        if [ -f "$ENV_EXAMPLE" ]; then
            cp "$ENV_EXAMPLE" "$ENV_FILE"
            echo -e "${GREEN}✓ .env 파일 생성됨${NC}"
        else
            echo -e "${RED}✗ .env.example 파일을 찾을 수 없습니다.${NC}"
            exit 1
        fi
    fi

    # 필수 환경변수 확인
    source "$ENV_FILE" 2>/dev/null || true

    if [ -z "$SLACK_BOT_TOKEN" ] || [ "$SLACK_BOT_TOKEN" == "xoxb-xxx" ]; then
        echo ""
        echo -e "${YELLOW}Slack 설정이 필요합니다.${NC}"
        echo "1. https://api.slack.com/apps 에서 앱 생성"
        echo "2. Socket Mode 활성화 → App Token 생성 (xapp-xxx)"
        echo "3. Bot Token Scopes: app_mentions:read, chat:write, reactions:read, im:history"
        echo "4. OAuth & Permissions → Bot Token 복사 (xoxb-xxx)"
        echo ""

        read -p "Slack App Token (xapp-xxx): " SLACK_APP_TOKEN
        read -p "Slack Bot Token (xoxb-xxx): " SLACK_BOT_TOKEN
        read -p "Slack Signing Secret: " SLACK_SIGNING_SECRET

        # .env 파일 업데이트
        sed -i.bak "s|SLACK_APP_TOKEN=.*|SLACK_APP_TOKEN=$SLACK_APP_TOKEN|" "$ENV_FILE"
        sed -i.bak "s|SLACK_BOT_TOKEN=.*|SLACK_BOT_TOKEN=$SLACK_BOT_TOKEN|" "$ENV_FILE"
        sed -i.bak "s|SLACK_SIGNING_SECRET=.*|SLACK_SIGNING_SECRET=$SLACK_SIGNING_SECRET|" "$ENV_FILE"
        rm -f "$ENV_FILE.bak"

        echo -e "${GREEN}✓ Slack 설정 완료${NC}"
    else
        echo -e "${GREEN}✓ 기존 환경 설정 사용${NC}"
    fi
}

# RAG 설정
setup_rag() {
    echo -e "${YELLOW}[추가] RAG 서비스 설정...${NC}"

    # docker-compose.rag.yml 생성
    cat > "$DOCKER_DIR/docker-compose.rag.yml" << 'EOF'
version: '3.8'

services:
  qdrant:
    image: qdrant/qdrant:latest
    container_name: claude-flow-qdrant
    ports:
      - "6333:6333"
    volumes:
      - qdrant_data:/qdrant/storage
    networks:
      - claude-flow-network
    restart: unless-stopped

  ollama:
    image: ollama/ollama:latest
    container_name: claude-flow-ollama
    ports:
      - "11434:11434"
    volumes:
      - ollama_data:/root/.ollama
    networks:
      - claude-flow-network
    restart: unless-stopped
    entrypoint: ["/bin/sh", "-c"]
    command: ["ollama serve & sleep 10 && ollama pull nomic-embed-text && wait"]

volumes:
  qdrant_data:
  ollama_data:

networks:
  claude-flow-network:
    external: true
EOF

    # .env에 RAG 설정 추가
    if ! grep -q "RAG_ENABLED" "$ENV_FILE"; then
        cat >> "$ENV_FILE" << EOF

# RAG 설정
RAG_ENABLED=true
QDRANT_URL=http://qdrant:6333
OLLAMA_URL=http://ollama:11434
EOF
    fi

    echo -e "${GREEN}✓ RAG 설정 완료${NC}"
}

# 서비스 시작
start_services() {
    local with_rag=$1
    echo -e "${YELLOW}[3/5] 서비스 시작...${NC}"

    cd "$DOCKER_DIR"

    # 네트워크 생성 (없으면)
    docker network create claude-flow-network 2>/dev/null || true

    if [ "$with_rag" == "true" ]; then
        echo "RAG 서비스와 함께 시작합니다..."
        docker compose -f docker-compose.yml -f docker-compose.rag.yml up -d
    else
        docker compose up -d
    fi

    echo -e "${GREEN}✓ 컨테이너 시작됨${NC}"
}

# 상태 확인
check_status() {
    echo -e "${YELLOW}[4/5] 서비스 상태 확인...${NC}"

    cd "$DOCKER_DIR"

    echo ""
    docker compose ps
    echo ""

    # 헬스체크
    echo "서비스 연결 확인 중..."
    sleep 5

    # Claude Flow API
    if curl -s http://localhost:8080/api/v1/health > /dev/null 2>&1; then
        echo -e "${GREEN}✓ Claude Flow API: http://localhost:8080${NC}"
    else
        echo -e "${YELLOW}⏳ Claude Flow API 시작 중... (최대 60초 소요)${NC}"
    fi

    # n8n
    if curl -s http://localhost:5678 > /dev/null 2>&1; then
        echo -e "${GREEN}✓ n8n Dashboard: http://localhost:5678${NC}"
    else
        echo -e "${YELLOW}⏳ n8n 시작 중...${NC}"
    fi

    # RAG 서비스 (있으면)
    if docker ps | grep -q "qdrant"; then
        if curl -s http://localhost:6333/collections > /dev/null 2>&1; then
            echo -e "${GREEN}✓ Qdrant: http://localhost:6333${NC}"
        fi
    fi

    if docker ps | grep -q "ollama"; then
        if curl -s http://localhost:11434/api/tags > /dev/null 2>&1; then
            echo -e "${GREEN}✓ Ollama: http://localhost:11434${NC}"
        fi
    fi
}

# 완료 메시지
print_success() {
    # .env에서 n8n 계정 정보 읽기
    source "$ENV_FILE" 2>/dev/null || true
    local N8N_EMAIL="${N8N_DEFAULT_EMAIL:-admin@local.dev}"
    local N8N_PASS="${N8N_DEFAULT_PASSWORD:-Localdev123}"

    echo ""
    echo -e "${GREEN}══════════════════════════════════════════════════════════${NC}"
    echo -e "${GREEN}                 Claude Flow 시작 완료!                    ${NC}"
    echo -e "${GREEN}══════════════════════════════════════════════════════════${NC}"
    echo ""
    echo "📍 접속 정보:"
    echo "   • API:       http://localhost:8080"
    echo "   • Dashboard: http://localhost:8080/dashboard"
    echo "   • n8n:       http://localhost:5678 ($N8N_EMAIL / $N8N_PASS)"
    echo ""
    echo "📌 다음 단계:"
    echo "   1. Slack에서 @claude-flow 멘션하여 테스트"
    echo "   2. n8n에서 워크플로우 확인 및 활성화"
    echo "   3. GitLab 연동: docker-compose/.env 에서 GITLAB_* 설정"
    echo ""
    echo "🛠️  유용한 명령어:"
    echo "   ./start.sh --logs     로그 확인"
    echo "   ./start.sh --backup   워크플로우 백업"
    echo "   ./start.sh --stop     서비스 중지"
    echo ""
}

# 서비스 중지
stop_services() {
    echo -e "${YELLOW}서비스 중지 중...${NC}"
    cd "$DOCKER_DIR"

    # RAG 포함 여부 확인
    if [ -f "docker-compose.rag.yml" ] && docker ps | grep -q "qdrant\|ollama"; then
        docker compose -f docker-compose.yml -f docker-compose.rag.yml down
    else
        docker compose down
    fi

    echo -e "${GREEN}✓ 모든 서비스 중지됨${NC}"
}

# 워크플로우 백업
backup_workflows() {
    echo -e "${YELLOW}n8n 워크플로우 백업 중...${NC}"

    BACKUP_DIR="$DOCKER_DIR/n8n-backup/$(date +%Y%m%d_%H%M%S)"
    mkdir -p "$BACKUP_DIR"

    docker exec claude-flow-n8n node /home/node/scripts/backup-workflows.mjs --output-dir /home/node/.n8n/backup 2>/dev/null || {
        echo -e "${YELLOW}컨테이너 내 백업 실패, 직접 API 호출 시도...${NC}"
        cd "$DOCKER_DIR/scripts"
        N8N_URL=http://localhost:5678 BACKUP_DIR="$BACKUP_DIR" node backup-workflows.mjs
    }

    # 백업 파일 복사
    docker cp claude-flow-n8n:/home/node/.n8n/backup/. "$BACKUP_DIR/" 2>/dev/null || true

    echo -e "${GREEN}✓ 백업 완료: $BACKUP_DIR${NC}"
}

# 로그 확인
show_logs() {
    cd "$DOCKER_DIR"
    docker compose logs -f
}

# 데이터 초기화
reset_data() {
    echo -e "${RED}⚠️  경고: 모든 데이터가 삭제됩니다!${NC}"
    read -p "정말 초기화하시겠습니까? (yes/no): " confirm

    if [ "$confirm" == "yes" ]; then
        cd "$DOCKER_DIR"
        docker compose down -v
        rm -f .env
        echo -e "${GREEN}✓ 초기화 완료${NC}"
    else
        echo "취소됨"
    fi
}

# 메인 로직
main() {
    case "$1" in
        --help|-h)
            print_help
            exit 0
            ;;
        --stop)
            stop_services
            exit 0
            ;;
        --status)
            cd "$DOCKER_DIR"
            docker compose ps
            exit 0
            ;;
        --backup)
            backup_workflows
            exit 0
            ;;
        --logs)
            show_logs
            exit 0
            ;;
        --reset)
            reset_data
            exit 0
            ;;
        --quick)
            print_banner
            check_dependencies
            start_services "false"
            check_status
            print_success
            exit 0
            ;;
        --with-rag)
            print_banner
            check_dependencies
            setup_environment
            setup_rag
            start_services "true"
            check_status
            print_success
            exit 0
            ;;
        *)
            print_banner
            check_dependencies
            setup_environment
            start_services "false"
            check_status
            print_success
            exit 0
            ;;
    esac
}

main "$@"
