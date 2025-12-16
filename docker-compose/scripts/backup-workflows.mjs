#!/usr/bin/env node
/**
 * n8n 워크플로우 백업 스크립트
 *
 * n8n에서 수정된 워크플로우를 JSON 파일로 백업
 * 양방향 동기화를 위한 역방향 백업
 *
 * 사용법:
 *   node backup-workflows.mjs [--output-dir <dir>]
 */
import http from 'http';
import fs from 'fs';
import path from 'path';

const BASE_URL = process.env.N8N_URL || 'http://127.0.0.1:5678';
const DEFAULT_OUTPUT_DIR = process.env.BACKUP_DIR || '/home/node/workflows';
let sessionCookie = '';

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

function sanitizeFilename(name) {
    return name
        .toLowerCase()
        .replace(/[^a-z0-9가-힣]+/g, '-')
        .replace(/^-+|-+$/g, '')
        .substring(0, 50);
}

async function backupWorkflows(outputDir) {
    console.log('=== n8n Workflow Backup ===');
    console.log(`Output directory: ${outputDir}`);

    // 1. 로그인
    console.log('Logging in...');
    const loginResult = await request('POST', '/rest/login', {
        emailOrLdapLoginId: process.env.N8N_USER || 'admin@local.dev',
        password: process.env.N8N_PASSWORD || 'Localdev123'
    });

    if (loginResult.status !== 200) {
        throw new Error(`Login failed: ${loginResult.status}`);
    }
    console.log('Login successful');

    // 2. 워크플로우 목록 조회
    const listResult = await request('GET', '/rest/workflows');
    const workflows = listResult.data?.data || [];
    console.log(`Found ${workflows.length} workflows`);

    // 3. 출력 디렉토리 생성
    if (!fs.existsSync(outputDir)) {
        fs.mkdirSync(outputDir, { recursive: true });
    }

    // 4. 각 워크플로우 백업
    let backed = 0;
    const timestamp = new Date().toISOString().replace(/[:.]/g, '-');

    for (const workflow of workflows) {
        // 상세 정보 조회
        const detailResult = await request('GET', `/rest/workflows/${workflow.id}`);
        if (detailResult.status !== 200) {
            console.log(`  Failed to get: ${workflow.name}`);
            continue;
        }

        const workflowData = detailResult.data?.data || detailResult.data;

        // 민감 정보 제거 (credential values)
        const sanitizedData = sanitizeWorkflow(workflowData);

        // 파일명 생성
        const filename = `${sanitizeFilename(workflow.name)}.json`;
        const filepath = path.join(outputDir, filename);

        // 저장
        fs.writeFileSync(filepath, JSON.stringify(sanitizedData, null, 2));
        console.log(`  Backed up: ${workflow.name} -> ${filename}`);
        backed++;
    }

    console.log(`\n=== Backup Complete ===`);
    console.log(`Total: ${backed} workflows backed up`);

    // 5. 백업 메타데이터 저장
    const metadata = {
        timestamp: new Date().toISOString(),
        totalWorkflows: backed,
        n8nUrl: BASE_URL
    };
    fs.writeFileSync(
        path.join(outputDir, '.backup-metadata.json'),
        JSON.stringify(metadata, null, 2)
    );

    return backed;
}

function sanitizeWorkflow(workflow) {
    const sanitized = { ...workflow };

    // 시스템 생성 필드 제거 (복원 시 새로 생성됨)
    delete sanitized.id;
    delete sanitized.versionId;
    delete sanitized.createdAt;
    delete sanitized.updatedAt;

    // credential 값 플레이스홀더로 치환
    if (sanitized.nodes) {
        sanitized.nodes = sanitized.nodes.map(node => {
            if (node.credentials) {
                const newCreds = {};
                for (const [key, value] of Object.entries(node.credentials)) {
                    if (typeof value === 'object' && value.id) {
                        // credential ID를 플레이스홀더로 변환
                        const credType = key.replace(/Api$|Token$|Auth$/, '').toUpperCase();
                        newCreds[key] = {
                            ...value,
                            id: `{{${credType}_CREDENTIAL_ID}}`
                        };
                    } else {
                        newCreds[key] = value;
                    }
                }
                node.credentials = newCreds;
            }
            return node;
        });
    }

    return sanitized;
}

// CLI 처리
const args = process.argv.slice(2);
let outputDir = DEFAULT_OUTPUT_DIR;

for (let i = 0; i < args.length; i++) {
    if (args[i] === '--output-dir' && args[i + 1]) {
        outputDir = args[i + 1];
        i++;
    }
}

backupWorkflows(outputDir).catch(err => {
    console.error('Backup error:', err.message);
    process.exit(1);
});
