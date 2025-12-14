#!/bin/bash
# Claude Flow - Bash Command Validation Hook
# PreToolUse hook for validating bash commands before execution

set -e

# Read hook input from stdin
INPUT=$(cat)
TOOL_NAME=$(echo "$INPUT" | jq -r '.tool_name // empty')
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty')

# Only process Bash tool
if [ "$TOOL_NAME" != "Bash" ]; then
    exit 0
fi

# Dangerous patterns to block
DANGEROUS_PATTERNS=(
    "rm -rf /"
    "rm -rf ~"
    "rm -rf \$HOME"
    "> /dev/sd"
    "mkfs\."
    "dd if=/dev/zero"
    ":(){:|:&};:"  # Fork bomb
    "chmod -R 777 /"
    "curl.*| *sh"
    "wget.*| *sh"
)

# Check for dangerous patterns
for pattern in "${DANGEROUS_PATTERNS[@]}"; do
    if echo "$COMMAND" | grep -qE "$pattern"; then
        echo "BLOCKED: Dangerous command pattern detected: $pattern" >&2
        exit 2  # Exit code 2 = block execution
    fi
done

# Sensitive paths to protect
SENSITIVE_PATHS=(
    "/etc/passwd"
    "/etc/shadow"
    "~/.ssh"
    "~/.aws"
    "~/.gnupg"
    ".env"
    "credentials"
    "secrets"
)

# Check for sensitive path access (warn but allow)
for path in "${SENSITIVE_PATHS[@]}"; do
    if echo "$COMMAND" | grep -q "$path"; then
        echo "WARNING: Command accesses sensitive path: $path" >&2
        # Log to audit file
        echo "[$(date -Iseconds)] SENSITIVE_ACCESS: $COMMAND" >> /tmp/claude-flow-audit.log
    fi
done

# Log all commands for audit
echo "[$(date -Iseconds)] BASH: $COMMAND" >> /tmp/claude-flow-audit.log

exit 0
