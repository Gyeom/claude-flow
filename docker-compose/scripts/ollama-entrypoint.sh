#!/bin/bash
# Ollama 시작 및 임베딩 모델 자동 설치

set -e

MODEL=${OLLAMA_EMBEDDING_MODEL:-qwen3-embedding:0.6b}

echo "========================================"
echo "Starting Ollama with model: $MODEL"
echo "========================================"

# Ollama 서버 시작 (백그라운드)
ollama serve &
OLLAMA_PID=$!

# 서버 준비 대기
echo "Waiting for Ollama server to be ready..."
max_attempts=30
attempt=0
while ! curl -s http://localhost:11434/api/tags > /dev/null 2>&1; do
    attempt=$((attempt + 1))
    if [ $attempt -ge $max_attempts ]; then
        echo "ERROR: Ollama server failed to start"
        exit 1
    fi
    echo "  Attempt $attempt/$max_attempts..."
    sleep 2
done
echo "Ollama server is ready!"

# 모델이 이미 있는지 확인
if ollama list | grep -q "$MODEL"; then
    echo "Model $MODEL already installed"
else
    echo "Downloading model: $MODEL"
    echo "This may take a few minutes on first run..."
    ollama pull "$MODEL"
    echo "Model $MODEL installed successfully!"
fi

# 모델 정보 출력
echo ""
echo "========================================"
echo "Available models:"
ollama list
echo "========================================"
echo ""

# 포그라운드에서 Ollama 실행 유지
wait $OLLAMA_PID
