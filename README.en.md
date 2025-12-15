# Claude Flow

**Enterprise-grade AI Agent Orchestration Platform**

A Slack-integrated AI agent platform that orchestrates Claude for intelligent conversations and automated GitLab MR reviews. Get started in 5 minutes and boost your team's productivity by 10x.

## Why Claude Flow?

- **Instant Setup**: One-line Docker install, ready to use in Slack
- **Smart Routing**: Automatic classification via keyword → semantic → LLM fallback
- **Context Memory**: User-specific conversation summaries and rule application
- **Real-time Analytics**: P50/P90/P95/P99 latency tracking, cost monitoring
- **Extensible**: Unlimited customization with n8n workflows

## Key Features

### Core
- **Slack Integration**: Instant conversation via `@claude-flow` mention
- **Code Review**: Automatic GitLab MR review with `@claude-flow !123 review`
- **Socket Mode**: Works perfectly in local dev environment without ngrok
- **Workflow Engine**: Flexible event handling with n8n

### Intelligence
- **Smart Routing**: Multi-agent automatic classification (with caching)
- **User Context**: AI-powered conversation summaries, per-user rules
- **Structured Output**: Enforce response format with JSON Schema

### Analytics
- **Real-time Dashboard**: Performance metrics, usage, and costs at a glance
- **Percentile Stats**: P50/P90/P95/P99 response time tracking
- **Feedback Analysis**: Automatic user satisfaction measurement

## Quick Start (5 minutes)

### 1. Installation

```bash
git clone https://github.com/your-org/claude-flow.git
cd claude-flow/docker-compose
cp .env.example .env
```

### 2. Configuration

Edit `.env` file:
```bash
SLACK_APP_TOKEN=xapp-xxx      # Slack App Token
SLACK_BOT_TOKEN=xoxb-xxx      # Slack Bot Token
SLACK_SIGNING_SECRET=xxx      # Slack Signing Secret

# Optional: GitLab Integration
GITLAB_URL=https://gitlab.example.com
GITLAB_TOKEN=glpat-xxx
```

### 3. Run

```bash
docker-compose up -d
```

### 4. Usage

In Slack:
```
@claude-flow Hello!
@claude-flow authorization-server !42 review please
```

> Claude CLI authentication only needs to be done once with `claude login`.

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Slack                                    │
│                  (@mention, messages, reactions)                 │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Socket Mode (WebSocket)
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Claude Flow (Kotlin)                          │
│  ┌─────────────────┐  ┌──────────────┐  ┌───────────────────┐   │
│  │ SlackSocketMode │→ │ AgentRouter  │→ │  ClaudeExecutor   │   │
│  │     Bridge      │  │  (Smart)     │  │  (Claude CLI)     │   │
│  └─────────────────┘  └──────────────┘  └───────────────────┘   │
│                            │                                     │
│  ┌─────────────────────────┴──────────────────────────────────┐ │
│  │  Storage (SQLite/WAL) │ UserContext │ Analytics │ Feedback │ │
│  └────────────────────────────────────────────────────────────┘ │
└───────────────────────────┬─────────────────────────────────────┘
                            │ Webhook
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                      n8n Workflows                               │
│  • slack-mention-handler   • gitlab-mr-review                   │
│  • slack-feedback-handler  • daily-report                       │
│  • user-context-handler    • slack-action-handler               │
└─────────────────────────────────────────────────────────────────┘
```

## API Reference

### Execute API
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/execute` | Execute Claude (single turn) |
| POST | `/api/v1/execute-with-routing` | Smart routing + execution |

### Agents API (v2)
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v2/agents` | List all agents |
| POST | `/api/v2/agents` | Create agent |
| PATCH | `/api/v2/agents/{id}` | Update agent |
| DELETE | `/api/v2/agents/{id}` | Delete agent |

### Analytics API
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/analytics/dashboard` | Dashboard stats |
| GET | `/api/v1/analytics/overview` | P50/P90/P95/P99 stats |
| GET | `/api/v1/analytics/timeseries` | Time series data |
| GET | `/api/v1/analytics/feedback` | Feedback analysis |
| GET | `/api/v1/analytics/tokens` | Token usage |
| GET | `/api/v1/analytics/errors` | Error statistics |

### User Context API
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/users` | List all users |
| GET | `/api/v1/users/{userId}` | Get user details |
| GET | `/api/v1/users/{userId}/context` | Get user context |
| PUT | `/api/v1/users/{userId}/context` | Save user summary |
| GET | `/api/v1/users/{userId}/rules` | Get user rules |
| POST | `/api/v1/users/{userId}/rules` | Add user rule |
| DELETE | `/api/v1/users/{userId}/rules` | Delete user rule |

## Configuration

### TOML Configuration (Optional)

Claude Flow supports TOML configuration files for advanced customization:

```bash
# Copy example files
cp config/config.example.toml config/config.toml
cp config/agents.example.toml config/agents.toml
```

See `config/config.example.toml` for server, Slack, Claude, and integration settings.
See `config/agents.example.toml` for agent definitions with keywords, examples, and system prompts.

## Project Structure

```
claude-flow/
├── claude-flow-core/          # Domain models, routing, storage
├── claude-flow-executor/      # Claude CLI wrapper
├── claude-flow-api/           # REST API, Slack integration
├── claude-flow-app/           # Spring Boot application
├── docker-compose/            # Docker config, n8n workflows
├── dashboard/                 # React dashboard
└── config/                    # TOML configuration examples
```

## Tech Stack

- **Backend**: Kotlin 2.1, Spring Boot 3.4, Kotlin Coroutines
- **AI**: Claude CLI (self-authenticated)
- **Slack**: Bolt for Java (Socket Mode)
- **Workflow**: n8n
- **Storage**: SQLite (WAL mode)
- **Dashboard**: React, Vite, TailwindCSS, Recharts

## Dashboard

The web dashboard provides real-time monitoring and management:

```bash
cd dashboard
npm install && npm run dev
# http://localhost:5173
```

### Pages
- **Dashboard**: Overview with key metrics and charts
- **Executions**: Real-time execution monitoring
- **History**: Detailed execution logs with search
- **Agents**: Agent management and configuration
- **Classify**: Test agent routing
- **Analytics**: Detailed performance analytics
- **Feedback**: User feedback analysis
- **Models**: Model usage statistics
- **Errors**: Error tracking and analysis
- **Plugins**: Plugin management
- **Users**: User context and rules management
- **Workflows**: n8n workflow management

## Development

### Prerequisites
- JDK 21+
- Node.js 18+
- Docker & Docker Compose
- Claude CLI (`npm install -g @anthropic-ai/claude-cli`)

### Build

```bash
# Backend
./gradlew build

# Dashboard
cd dashboard && npm install && npm run build
```

### Run Locally

```bash
# Start n8n
cd docker-compose && docker-compose up -d

# Start backend
./gradlew :claude-flow-app:bootRun

# Start dashboard
cd dashboard && npm run dev
```

## License

MIT
