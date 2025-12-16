// n8n 자동 설정 스크립트
import http from 'http';
import fs from 'fs';
import path from 'path';

const BASE_URL = process.env.N8N_URL || 'http://127.0.0.1:5678';
const WORKFLOWS_DIR = process.env.WORKFLOWS_DIR || '/home/node/workflows';
const N8N_EMAIL = process.env.N8N_DEFAULT_EMAIL || 'admin@local.dev';
const N8N_PASSWORD = process.env.N8N_DEFAULT_PASSWORD || 'Localdev123';
let sessionCookie = '';

// Credential ID 저장
const credentialIds = {
    gitlab: null
};

// Credentials 생성 함수
async function setupCredentials() {
    console.log('\n=== Setting up Credentials ===');

    // GitLab Token (환경변수에서 읽기)
    const gitlabToken = process.env.GITLAB_TOKEN;
    if (gitlabToken) {
        console.log('Creating GitLab credential...');

        // 기존 credential 확인
        const existingCreds = await request('GET', '/rest/credentials');
        const gitlabCred = (existingCreds.data?.data || []).find(c => c.name === 'GitLab Token');

        if (gitlabCred) {
            console.log(`  GitLab credential already exists (ID: ${gitlabCred.id})`);
            credentialIds.gitlab = gitlabCred.id;
        } else {
            const credResult = await request('POST', '/rest/credentials', {
                name: 'GitLab Token',
                type: 'httpHeaderAuth',
                data: {
                    name: 'PRIVATE-TOKEN',
                    value: gitlabToken
                }
            });

            if (credResult.status === 200 || credResult.status === 201) {
                const cred = credResult.data?.data || credResult.data;
                credentialIds.gitlab = cred.id;
                console.log(`  Created GitLab credential (ID: ${cred.id})`);
            } else {
                console.log(`  Failed to create GitLab credential:`, credResult.status);
            }
        }
    } else {
        console.log('GITLAB_TOKEN not set, skipping GitLab credential creation');
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

            // 쿠키 저장
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

async function setup() {
    console.log('=== n8n Auto Setup ===');

    // 1. Owner 생성
    console.log('Creating owner account...');
    const ownerResult = await request('POST', '/rest/owner/setup', {
        email: N8N_EMAIL,
        firstName: 'Admin',
        lastName: 'User',
        password: N8N_PASSWORD
    });
    if (ownerResult.status === 200) {
        console.log('Owner setup: Success');
    } else {
        console.log('Owner setup:', ownerResult.status, JSON.stringify(ownerResult.data).substring(0, 100));
    }

    // 2. 로그인
    console.log('Logging in...');
    const loginResult = await request('POST', '/rest/login', {
        emailOrLdapLoginId: N8N_EMAIL,
        password: N8N_PASSWORD
    });

    if (loginResult.status !== 200) {
        console.log('Login failed:', loginResult.status, JSON.stringify(loginResult.data).substring(0, 100));
        return;
    }
    console.log('Login successful!');

    // 3. Credentials 생성 (환경변수가 설정된 경우)
    await setupCredentials();

    // 4. 기존 워크플로우 확인
    console.log('Checking existing workflows...');
    const existingResult = await request('GET', '/rest/workflows');
    const existingWorkflows = existingResult.data?.data || [];
    const existingNames = new Set(existingWorkflows.map(w => w.name));
    console.log(`Found ${existingWorkflows.length} existing workflows`);

    // 4. 워크플로우 JSON 파일들 읽기 및 생성
    if (fs.existsSync(WORKFLOWS_DIR)) {
        const files = fs.readdirSync(WORKFLOWS_DIR).filter(f => f.endsWith('.json'));
        console.log(`Found ${files.length} workflow files to import`);

        for (const file of files) {
            const filePath = path.join(WORKFLOWS_DIR, file);
            let content = fs.readFileSync(filePath, 'utf8');

            // Credential 플레이스홀더 치환
            content = replaceCredentialPlaceholders(content);
            const workflow = JSON.parse(content);

            if (existingNames.has(workflow.name)) {
                console.log(`Skipping ${workflow.name} (already exists)`);
                continue;
            }

            console.log(`Creating workflow: ${workflow.name}`);

            // versionId는 API에서 자동 생성되므로 제거
            const { versionId, id, ...workflowData } = workflow;

            const createResult = await request('POST', '/rest/workflows', workflowData);

            if (createResult.status === 200 || createResult.status === 201) {
                const newWorkflow = createResult.data?.data || createResult.data;
                console.log(`  Created: ${newWorkflow.name} (ID: ${newWorkflow.id})`);

                // 워크플로우 활성화
                if (workflow.active) {
                    console.log(`  Activating: ${newWorkflow.name}`);
                    const activateResult = await request('PATCH', `/rest/workflows/${newWorkflow.id}`, {
                        active: true
                    });

                    if (activateResult.status === 200) {
                        console.log(`  Activated: ${newWorkflow.name}`);
                    } else {
                        console.log(`  Activation failed:`, activateResult.status, JSON.stringify(activateResult.data).substring(0, 200));
                    }
                }
            } else {
                console.log(`  Failed to create: ${createResult.status}`, JSON.stringify(createResult.data).substring(0, 200));
            }
        }
    }

    // 5. 최종 상태 확인
    console.log('\n=== Final Workflow Status ===');
    const finalResult = await request('GET', '/rest/workflows');
    const workflows = finalResult.data?.data || [];
    for (const wf of workflows) {
        console.log(`  ${wf.name}: active=${wf.active}`);
    }

    console.log('\n=== Setup Complete! ===');
    console.log(`Login: ${N8N_EMAIL} / ${N8N_PASSWORD}`);
}

setup().catch(err => {
    console.error('Setup error:', err);
    process.exit(1);
});
