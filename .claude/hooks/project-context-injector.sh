#!/bin/bash
# Claude Flow - Project Context Injector Hook
# UserPromptSubmit hook that automatically detects and injects project context
#
# Configuration: .claude/config/project-aliases.json
# - Define project aliases (Korean/English patterns → actual project names)
# - Set workspace root path
# - Customize suffix patterns

set -e

# =============================================================================
# Configuration
# =============================================================================
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_DIR="${SCRIPT_DIR}/../config"
CONFIG_FILE="${CONFIG_DIR}/project-aliases.json"
CACHE_DIR="/tmp/claude-flow-project-cache"
CACHE_TTL=300  # 5 minutes

mkdir -p "$CACHE_DIR"

# Default workspace (can be overridden by config)
DEFAULT_WORKSPACE="${WORKSPACE_PATH:-$HOME/workspace}"

# =============================================================================
# Configuration Loading
# =============================================================================

# Load configuration from JSON file (without jq dependency)
load_config() {
    if [ ! -f "$CONFIG_FILE" ]; then
        # Use example config or defaults
        if [ -f "${CONFIG_DIR}/project-aliases.example.json" ]; then
            echo "[project-context-injector] Using example config. Create project-aliases.json for custom settings." >&2
        fi
        return 1
    fi
    return 0
}

# Extract workspace root from config
get_workspace_root() {
    if [ -f "$CONFIG_FILE" ]; then
        local root=$(grep -o '"workspaceRoot"[[:space:]]*:[[:space:]]*"[^"]*"' "$CONFIG_FILE" | sed 's/.*: *"//; s/"$//')
        # Expand environment variables
        root=$(eval echo "$root")
        if [ -n "$root" ] && [ -d "$root" ]; then
            echo "$root"
            return 0
        fi
    fi
    echo "$DEFAULT_WORKSPACE"
}

# Get all alias patterns from config
# Returns: "pattern1|project1\npattern2|project1\npattern3|project2\n..."
get_alias_mappings() {
    if [ ! -f "$CONFIG_FILE" ]; then
        return
    fi

    # Parse JSON aliases section (simplified parsing without jq)
    # Extract project names and their patterns
    local in_aliases=false
    local current_project=""

    while IFS= read -r line; do
        # Detect "aliases": { section
        if [[ "$line" =~ \"aliases\"[[:space:]]*:[[:space:]]*\{ ]]; then
            in_aliases=true
            continue
        fi

        # Exit aliases section
        if $in_aliases && [[ "$line" =~ ^\}[[:space:]]*,?[[:space:]]*$ ]]; then
            in_aliases=false
            continue
        fi

        if $in_aliases; then
            # Detect project name: "project-name": {
            if [[ "$line" =~ \"([a-zA-Z0-9_-]+)\"[[:space:]]*:[[:space:]]*\{ ]]; then
                current_project="${BASH_REMATCH[1]}"
            fi

            # Detect patterns array
            if [[ "$line" =~ \"patterns\"[[:space:]]*:[[:space:]]*\[([^\]]+)\] ]]; then
                local patterns_str="${BASH_REMATCH[1]}"
                # Extract each pattern
                while [[ "$patterns_str" =~ \"([^\"]+)\" ]]; do
                    echo "${BASH_REMATCH[1]}|$current_project"
                    patterns_str="${patterns_str#*\"${BASH_REMATCH[1]}\"}"
                done
            fi
        fi
    done < "$CONFIG_FILE"
}

# =============================================================================
# Read hook input
# =============================================================================
INPUT=$(cat)

# Extract prompt from JSON input (simple pattern matching)
if [[ "$INPUT" =~ \"prompt\"[[:space:]]*:[[:space:]]*\"([^\"]+)\" ]]; then
    PROMPT="${BASH_REMATCH[1]}"
else
    PROMPT="$INPUT"
fi

# Exit if no prompt
[ -z "$PROMPT" ] && exit 0

# Load configuration
load_config
WORKSPACE_ROOT=$(get_workspace_root)

# =============================================================================
# Project Detection Functions
# =============================================================================

# Find actual project directory
find_project_dir() {
    local name="$1"
    local cache_file="$CACHE_DIR/project-$name.cache"

    # Check cache first
    if [ -f "$cache_file" ]; then
        local cache_age=$(($(date +%s) - $(stat -f %m "$cache_file" 2>/dev/null || stat -c %Y "$cache_file" 2>/dev/null || echo 0)))
        if [ $cache_age -lt $CACHE_TTL ]; then
            cat "$cache_file"
            return 0
        fi
    fi

    # Search for project (case-insensitive, max depth 3)
    local found=$(find "$WORKSPACE_ROOT" -maxdepth 3 -type d -iname "$name" 2>/dev/null | head -1)

    if [ -n "$found" ] && [ -d "$found" ]; then
        echo "$found" > "$cache_file"
        echo "$found"
        return 0
    fi

    # Try partial match
    found=$(find "$WORKSPACE_ROOT" -maxdepth 3 -type d -iname "*$name*" 2>/dev/null | head -1)

    if [ -n "$found" ] && [ -d "$found" ]; then
        echo "$found" > "$cache_file"
        echo "$found"
        return 0
    fi

    return 1
}

# Get project summary
get_project_summary() {
    local project_dir="$1"
    local summary=""

    # Check for README
    if [ -f "$project_dir/README.md" ]; then
        summary=$(head -50 "$project_dir/README.md" | head -c 1000)
    fi

    # Check for CLAUDE.md (project instructions)
    if [ -f "$project_dir/CLAUDE.md" ]; then
        summary="$summary\n\n--- Project Instructions ---\n$(head -100 "$project_dir/CLAUDE.md" | head -c 2000)"
    fi

    # Get project structure
    if [ -d "$project_dir" ]; then
        local structure=$(ls -1 "$project_dir" 2>/dev/null | head -20 | tr '\n' ', ')
        summary="$summary\n\n--- Project Structure ---\n$structure"
    fi

    # Check for build files to determine tech stack
    local tech_stack=""
    [ -f "$project_dir/build.gradle.kts" ] && tech_stack="$tech_stack Kotlin/Gradle"
    [ -f "$project_dir/build.gradle" ] && tech_stack="$tech_stack Groovy/Gradle"
    [ -f "$project_dir/pom.xml" ] && tech_stack="$tech_stack Java/Maven"
    [ -f "$project_dir/package.json" ] && tech_stack="$tech_stack Node.js"
    [ -f "$project_dir/requirements.txt" ] && tech_stack="$tech_stack Python"
    [ -f "$project_dir/go.mod" ] && tech_stack="$tech_stack Go"
    [ -f "$project_dir/Cargo.toml" ] && tech_stack="$tech_stack Rust"

    if [ -n "$tech_stack" ]; then
        summary="$summary\n\n--- Tech Stack ---\n$tech_stack"
    fi

    echo -e "$summary"
}

# Extract potential project names from prompt using config
extract_project_candidates() {
    local prompt="$1"
    local candidates=""

    # 1. Match patterns from config file
    while IFS='|' read -r pattern project; do
        [ -z "$pattern" ] && continue
        if [[ "$prompt" == *"$pattern"* ]]; then
            candidates="$candidates $project"
        fi
    done < <(get_alias_mappings)

    # 2. Hyphenated names (e.g., my-project, some-service)
    while IFS= read -r match; do
        [ -z "$match" ] && continue
        candidates="$candidates $match"
    done < <(echo "$prompt" | grep -oE '[a-zA-Z][a-zA-Z0-9]*-[a-zA-Z0-9-]+' 2>/dev/null || true)

    # 3. CamelCase names (e.g., MyProject, SomeService)
    while IFS= read -r match; do
        [ -z "$match" ] && continue
        candidates="$candidates $match"
    done < <(echo "$prompt" | grep -oE '[A-Z][a-z]+[A-Z][a-zA-Z]+' 2>/dev/null || true)

    # Remove duplicates and empty entries
    echo "$candidates" | tr ' ' '\n' | sort -u | grep -v '^$' | tr '\n' ' '
}

# =============================================================================
# Main Logic
# =============================================================================

# Extract project candidates from prompt
CANDIDATES=$(extract_project_candidates "$PROMPT")

# Try to find matching projects
FOUND_PROJECTS=""
INJECTED_CONTEXT=""

for candidate in $CANDIDATES; do
    project_dir=$(find_project_dir "$candidate")

    if [ -n "$project_dir" ] && [ -d "$project_dir" ]; then
        project_name=$(basename "$project_dir")

        # Avoid duplicates
        if [[ ! "$FOUND_PROJECTS" =~ "$project_name" ]]; then
            FOUND_PROJECTS="$FOUND_PROJECTS $project_name"

            # Get project summary
            summary=$(get_project_summary "$project_dir")

            if [ -n "$summary" ]; then
                INJECTED_CONTEXT="$INJECTED_CONTEXT
================================================================================
[Detected Project: $project_name]
Path: $project_dir
$summary
================================================================================
"
            fi
        fi
    fi
done

# Output context injection if projects were found
if [ -n "$INJECTED_CONTEXT" ]; then
    # Escape special characters for JSON output
    ESCAPED_CONTEXT=$(echo "$INJECTED_CONTEXT" | sed 's/\\/\\\\/g; s/"/\\"/g; s/	/\\t/g' | tr '\n' ' ')

    # Output as JSON for structured injection
    cat <<EOF
{
    "hookSpecificOutput": {
        "hookEventName": "UserPromptSubmit",
        "additionalContext": "$ESCAPED_CONTEXT"
    }
}
EOF
else
    # No projects detected - pass through
    echo "{}"
fi

exit 0
