#!/bin/bash

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DASHBOARD_DIR="$SCRIPT_DIR/../dashboard"

case "$1" in
  install)
    echo "Installing dashboard dependencies..."
    cd "$DASHBOARD_DIR" && npm install
    ;;
  dev)
    echo "Starting dashboard in development mode..."
    cd "$DASHBOARD_DIR" && npm run dev
    ;;
  build)
    echo "Building dashboard for production..."
    cd "$DASHBOARD_DIR" && npm run build
    ;;
  preview)
    echo "Previewing production build..."
    cd "$DASHBOARD_DIR" && npm run preview
    ;;
  *)
    echo "Usage: $0 {install|dev|build|preview}"
    echo ""
    echo "Commands:"
    echo "  install  - Install npm dependencies"
    echo "  dev      - Start development server (http://localhost:3000)"
    echo "  build    - Build for production"
    echo "  preview  - Preview production build"
    exit 1
    ;;
esac
