// n8n 워크플로우 동기화 스크립트
// JSON 파일과 N8N 워크플로우를 동기화 (생성/업데이트/삭제)
import http from 'http';
import fs from 'fs';
import path from 'path';

const BASE_URL = process.env.N8N_URL || 'http://127.0.0.1:5678';
const WORKFLOWS_DIR = process.env.WORKFLOWS_DIR || '/home/node/workflows';
const N8N_EMAIL = process.env.N8N_DEFAULT_EMAIL || 'admin@local.dev';
const N8N_PASSWORD = process.env.N8N_DEFAULT_PASSWORD || 'Localdev123';
let sessionCookie = '';

// Credential ID 캐시
const credentialIds = {
    gitlab: null
};

// Credential IDs 로드
async function loadCredentialIds() {
    const result = await request('GET', '/rest/credentials');
    const credentials = result.data?.data || [];

    for (const cred of credentials) {
        if (cred.name === 'GitLab Token') {
            credentialIds.gitlab = cred.id;
            console.log(`Found GitLab credential (ID: ${cred.id})`);
        }
    }
}

// 워크플로우 JSON에서 플레이스홀더 치환
function replaceCredentialPlaceholders(workflowContent) {
    let content = workflowContent;

    if (credentialIds.gitlab) {
        content = content.replace(/\{\{GITLAB_CREDENTIAL_ID\}\}/g, credentialIds.gitlab);
    }

    return content;
}

function request(method, urlPath, data = null) {
    return new Promise((resolve, reject) => {
        const url = new URL(urlPath, BASE_URL);
        const options = {
            hostname: url.hostname,
            port: url.port,
            path: url.pathname + url.search,
            method: method,
            headers: {
                'Content-Type': 'application/json',
            }
        };

        if (sessionCookie) {
            options.headers['Cookie'] = sessionCookie;
        }

        const req = http.request(options, (res) => {
            let body = '';

            const setCookie = res.headers['set-cookie'];
            if (setCookie) {
                sessionCookie = setCookie.map(c => c.split(';')[0]).join('; ');
            }

            res.on('data', chunk => body += chunk);
            res.on('end', () => {
                try {
                    resolve({ status: res.statusCode, data: JSON.parse(body) });
                } catch {
                    resolve({ status: res.statusCode, data: body });
                }
            });
        });

        req.on('error', reject);
        if (data) req.write(JSON.stringify(data));
        req.end();
    });
}

async function syncWorkflows() {
    console.log('=== n8n Workflow Sync ===');

    // 1. 로그인
    console.log('Logging in...');
    const loginResult = await request('POST', '/rest/login', {
        emailOrLdapLoginId: N8N_EMAIL,
        password: N8N_PASSWORD
    });

    if (loginResult.status !== 200) {
        console.log('Login failed:', loginResult.status);
        return false;
    }
    console.log('Login successful');

    // 2. Credential IDs 조회
    await loadCredentialIds();

    // 3. 기존 워크플로우 조회
    const existingResult = await request('GET', '/rest/workflows');
    const existingWorkflows = existingResult.data?.data || [];
    const workflowMap = new Map(existingWorkflows.map(w => [w.name, w]));
    console.log(`Found ${existingWorkflows.length} existing workflows`);

    // 4. JSON 파일 동기화
    if (!fs.existsSync(WORKFLOWS_DIR)) {
        console.log('Workflows directory not found');
        return false;
    }

    const files = fs.readdirSync(WORKFLOWS_DIR).filter(f => f.endsWith('.json'));
    console.log(`Found ${files.length} workflow files`);

    let updated = 0, created = 0, deleted = 0;

    // JSON 파일에서 워크플로우 이름 목록 수집
    const jsonWorkflowNames = new Set();
    for (const file of files) {
        const filePath = path.join(WORKFLOWS_DIR, file);
        try {
            const content = fs.readFileSync(filePath, 'utf8');
            const workflow = JSON.parse(content);
            jsonWorkflowNames.add(workflow.name);
        } catch (err) {
            console.log(`  Warning: Failed to parse ${file}:`, err.message);
        }
    }

    for (const file of files) {
        const filePath = path.join(WORKFLOWS_DIR, file);
        let content = fs.readFileSync(filePath, 'utf8');

        // Credential 플레이스홀더 치환
        content = replaceCredentialPlaceholders(content);
        const workflow = JSON.parse(content);
        const { versionId, id, ...workflowData } = workflow;

        const existing = workflowMap.get(workflow.name);

        if (existing) {
            // 기존 워크플로우 업데이트 (n8n API는 PATCH만 지원)
            const updateResult = await request('PATCH', `/rest/workflows/${existing.id}`, {
                ...workflowData,
                id: existing.id
            });

            if (updateResult.status === 200) {
                console.log(`  Updated: ${workflow.name}`);
                updated++;
            } else {
                console.log(`  Update failed: ${workflow.name}`, updateResult.status);
            }
        } else {
            // 새 워크플로우 생성
            const createResult = await request('POST', '/rest/workflows', workflowData);

            if (createResult.status === 200 || createResult.status === 201) {
                const newWorkflow = createResult.data?.data || createResult.data;
                console.log(`  Created: ${workflow.name} (ID: ${newWorkflow.id})`);
                created++;

                if (workflow.active) {
                    await request('PATCH', `/rest/workflows/${newWorkflow.id}`, {
                        active: true
                    });
                    console.log(`    Activated`);
                }
            } else {
                console.log(`  Create failed: ${workflow.name}`, createResult.status);
            }
        }
    }

    // 5. JSON 파일에 없는 워크플로우 삭제
    console.log('\nChecking for workflows to delete...');
    for (const existing of existingWorkflows) {
        if (!jsonWorkflowNames.has(existing.name)) {
            console.log(`  Deleting: ${existing.name} (ID: ${existing.id})`);
            const deleteResult = await request('DELETE', `/rest/workflows/${existing.id}`);
            if (deleteResult.status === 200 || deleteResult.status === 204) {
                console.log(`    Deleted successfully`);
                deleted++;
            } else {
                console.log(`    Delete failed:`, deleteResult.status);
            }
        }
    }

    console.log(`\n=== Sync Complete ===`);
    console.log(`Created: ${created}, Updated: ${updated}, Deleted: ${deleted}`);
    return true;
}

// 재시도 로직
async function syncWithRetry(maxRetries = 5, delay = 5000) {
    for (let i = 0; i < maxRetries; i++) {
        try {
            const success = await syncWorkflows();
            if (success) return;
        } catch (err) {
            console.log(`Sync attempt ${i + 1} failed:`, err.message);
        }
        if (i < maxRetries - 1) {
            console.log(`Retrying in ${delay/1000}s...`);
            await new Promise(r => setTimeout(r, delay));
        }
    }
    console.log('Sync failed after all retries');
}

syncWithRetry().catch(err => {
    console.error('Sync error:', err);
    process.exit(1);
});
