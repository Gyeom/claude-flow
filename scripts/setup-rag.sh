#!/bin/bash
#
# RAG 시스템 초기화 스크립트
#
# 사용법:
#   ./scripts/setup-rag.sh
#
# 환경 변수:
#   QDRANT_URL: Qdrant 서버 URL (기본: http://localhost:6333)
#   OLLAMA_URL: Ollama 서버 URL (기본: http://localhost:11434)
#

set -e

# 색상 정의
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# 기본 설정
QDRANT_URL=${QDRANT_URL:-http://localhost:6333}
OLLAMA_URL=${OLLAMA_URL:-http://localhost:11434}

echo -e "${BLUE}========================================${NC}"
echo -e "${BLUE}   Claude Flow RAG 시스템 초기화${NC}"
echo -e "${BLUE}========================================${NC}"
echo ""
echo "Qdrant URL: $QDRANT_URL"
echo "Ollama URL: $OLLAMA_URL"
echo ""

# 1. Qdrant 연결 확인
echo -e "${YELLOW}[1/5] Qdrant 연결 확인...${NC}"
if curl -s "$QDRANT_URL/collections" > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Qdrant 연결 성공${NC}"
else
    echo -e "${RED}✗ Qdrant 연결 실패${NC}"
    echo "  Qdrant가 실행 중인지 확인하세요: docker-compose up -d qdrant"
    exit 1
fi

# 2. Ollama 연결 확인
echo -e "${YELLOW}[2/5] Ollama 연결 확인...${NC}"
if curl -s "$OLLAMA_URL/api/tags" > /dev/null 2>&1; then
    echo -e "${GREEN}✓ Ollama 연결 성공${NC}"
else
    echo -e "${RED}✗ Ollama 연결 실패${NC}"
    echo "  Ollama가 실행 중인지 확인하세요: docker-compose up -d ollama"
    exit 1
fi

# 3. 임베딩 모델 확인/다운로드
echo -e "${YELLOW}[3/5] 임베딩 모델 확인...${NC}"
MODELS=$(curl -s "$OLLAMA_URL/api/tags" | grep -o '"name":"[^"]*"' | grep -c "nomic-embed-text" || echo "0")
if [ "$MODELS" -gt 0 ]; then
    echo -e "${GREEN}✓ nomic-embed-text 모델 존재${NC}"
else
    echo -e "${YELLOW}→ nomic-embed-text 모델 다운로드 중...${NC}"
    curl -s -X POST "$OLLAMA_URL/api/pull" -d '{"name": "nomic-embed-text"}' | while read line; do
        STATUS=$(echo "$line" | grep -o '"status":"[^"]*"' | cut -d'"' -f4)
        if [ -n "$STATUS" ]; then
            echo "  $STATUS"
        fi
    done
    echo -e "${GREEN}✓ 모델 다운로드 완료${NC}"
fi

# 4. Conversations 컬렉션 생성
echo -e "${YELLOW}[4/5] Conversations 컬렉션 생성...${NC}"
COLLECTION_EXISTS=$(curl -s "$QDRANT_URL/collections/claude-flow-conversations" | grep -c '"status":"ok"' || echo "0")
if [ "$COLLECTION_EXISTS" -gt 0 ]; then
    echo -e "${GREEN}✓ claude-flow-conversations 컬렉션 이미 존재${NC}"
else
    curl -s -X PUT "$QDRANT_URL/collections/claude-flow-conversations" \
        -H "Content-Type: application/json" \
        -d '{
            "vectors": {
                "size": 768,
                "distance": "Cosine"
            },
            "optimizers_config": {
                "default_segment_number": 2
            }
        }' > /dev/null

    # 인덱스 생성
    curl -s -X PUT "$QDRANT_URL/collections/claude-flow-conversations/index" \
        -H "Content-Type: application/json" \
        -d '{"field_name": "user_id", "field_schema": "keyword"}' > /dev/null

    curl -s -X PUT "$QDRANT_URL/collections/claude-flow-conversations/index" \
        -H "Content-Type: application/json" \
        -d '{"field_name": "agent_id", "field_schema": "keyword"}' > /dev/null

    echo -e "${GREEN}✓ claude-flow-conversations 컬렉션 생성 완료${NC}"
fi

# 5. Knowledge 컬렉션 생성
echo -e "${YELLOW}[5/5] Knowledge 컬렉션 생성...${NC}"
KNOWLEDGE_EXISTS=$(curl -s "$QDRANT_URL/collections/claude-flow-knowledge" | grep -c '"status":"ok"' || echo "0")
if [ "$KNOWLEDGE_EXISTS" -gt 0 ]; then
    echo -e "${GREEN}✓ claude-flow-knowledge 컬렉션 이미 존재${NC}"
else
    curl -s -X PUT "$QDRANT_URL/collections/claude-flow-knowledge" \
        -H "Content-Type: application/json" \
        -d '{
            "vectors": {
                "size": 768,
                "distance": "Cosine"
            },
            "optimizers_config": {
                "default_segment_number": 4
            }
        }' > /dev/null

    # 인덱스 생성
    curl -s -X PUT "$QDRANT_URL/collections/claude-flow-knowledge/index" \
        -H "Content-Type: application/json" \
        -d '{"field_name": "project_id", "field_schema": "keyword"}' > /dev/null

    curl -s -X PUT "$QDRANT_URL/collections/claude-flow-knowledge/index" \
        -H "Content-Type: application/json" \
        -d '{"field_name": "language", "field_schema": "keyword"}' > /dev/null

    echo -e "${GREEN}✓ claude-flow-knowledge 컬렉션 생성 완료${NC}"
fi

echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}   RAG 시스템 초기화 완료!${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
echo "다음 단계:"
echo "  1. 환경 변수 설정:"
echo "     export RAG_ENABLED=true"
echo "     export QDRANT_URL=$QDRANT_URL"
echo "     export OLLAMA_URL=$OLLAMA_URL"
echo ""
echo "  2. 기존 대화 인덱싱 (선택사항):"
echo "     curl -X POST http://localhost:8080/api/v1/rag/index/batch -d '{\"limit\": 1000}'"
echo ""
echo "  3. 코드베이스 인덱싱 (선택사항):"
echo "     curl -X POST http://localhost:8080/api/v1/rag/knowledge/index \\"
echo "       -H 'Content-Type: application/json' \\"
echo "       -d '{\"projectId\": \"my-project\", \"directory\": \"/path/to/project\"}'"
echo ""
