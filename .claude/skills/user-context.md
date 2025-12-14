---
name: user-context
description: Manage user context, rules, and conversation memory
---

# User Context Skill

This skill manages user-specific context, rules, and conversation memory for personalized interactions.

## API Endpoints

### 1. Get User Context

```bash
# Get full user context with rules and summary
user_get_context() {
  local user_id="$1"
  curl -s "http://localhost:8080/api/v1/users/${user_id}/context"
}
```

### 2. Get Formatted Context (Markdown)

```bash
# Get context formatted for injection into prompts
user_get_formatted() {
  local user_id="$1"
  curl -s "http://localhost:8080/api/v1/users/${user_id}/context/formatted"
}
```

### 3. Add User Rule

```bash
# Add a persistent rule for the user
user_add_rule() {
  local user_id="$1"
  local rule="$2"
  curl -s -X POST "http://localhost:8080/api/v1/users/${user_id}/rules" \
    -H "Content-Type: application/json" \
    -d "{\"rule\": \"$rule\"}"
}
```

### 4. Delete User Rule

```bash
# Remove a user rule
user_delete_rule() {
  local user_id="$1"
  local rule="$2"
  curl -s -X DELETE "http://localhost:8080/api/v1/users/${user_id}/rules" \
    -H "Content-Type: application/json" \
    -d "{\"rule\": \"$rule\"}"
}
```

### 5. Save User Summary

```bash
# Save AI-generated conversation summary
user_save_summary() {
  local user_id="$1"
  local summary="$2"
  curl -s -X PUT "http://localhost:8080/api/v1/users/${user_id}/context" \
    -H "Content-Type: application/json" \
    -d "{\"summary\": \"$summary\"}"
}
```

## User Context Structure

```json
{
  "rules": ["Always respond in Korean", "Prefer concise answers"],
  "summary": "User is a backend developer working on authentication services...",
  "recentConversations": [
    {
      "id": "exec-123",
      "userMessage": "How do I implement OAuth2?",
      "response": "Here's how to implement OAuth2...",
      "createdAt": "2025-01-15T10:30:00Z",
      "hasReactions": true
    }
  ],
  "totalConversationCount": 42,
  "needsSummary": false,
  "summaryLocked": false
}
```

## Rule Types

### Instruction Rules
- "Always respond in Korean"
- "Use formal language"
- "Include code examples"

### Preference Rules
- "Prefer TypeScript over JavaScript"
- "Use functional programming style"
- "Explain like I'm a senior developer"

### Context Rules
- "I work on the authorization-server project"
- "Our team uses GitLab for code review"
- "We follow Google's code style guide"

## Summary Generation

When `needsSummary` is true:
1. Acquire lock with `acquire_lock=true`
2. Generate summary from recent conversations
3. Save summary with `user_save_summary`
4. Lock is automatically released

Summary should include:
- User's primary domain/focus
- Common request patterns
- Technical preferences
- Recent project context

## Integration Example

```python
# Before executing a prompt, inject user context
context = user_get_formatted(user_id)
enhanced_prompt = f"""
{context}

---
User Request: {original_prompt}
"""
```

## Best Practices

1. **Rule Management**
   - Keep rules concise (< 100 chars)
   - Max 10 rules per user
   - Review rules periodically

2. **Summary Updates**
   - Trigger when totalChars > 8000
   - Or conversationCount > 5
   - Minimum 5 minute interval

3. **Context Injection**
   - Only inject relevant rules
   - Truncate old conversations
   - Prioritize recent context
