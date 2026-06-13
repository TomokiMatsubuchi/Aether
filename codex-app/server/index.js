import { Hono } from 'hono'
import { cors } from 'hono/cors'
import { streamSSE } from 'hono/streaming'
import { spawn } from 'child_process'
import fs from 'fs'
import path from 'path'
import crypto from 'crypto'
import os from 'os'
import { fileURLToPath } from 'url'
import { serve } from '@hono/node-server'
import http from 'http'
import https from 'https'

// Compatible with Node.js 14+
const __filename = fileURLToPath(import.meta.url)
const __dirname = path.dirname(__filename)
const SERVER_DIR = __dirname

const LOG_FILE = path.join(SERVER_DIR, 'server.log')

function log(msg) {
  const line = `[${new Date().toISOString()}] ${msg}`
  console.log(line)
  try { fs.appendFileSync(LOG_FILE, line + '\n') } catch (_) {}
}

// Auto-install dependencies on first run
const NODE_MODULES = path.join(SERVER_DIR, 'node_modules')
const PACKAGE_JSON = path.join(SERVER_DIR, 'package.json')

if (!fs.existsSync(NODE_MODULES)) {
  log('node_modules not found, running npm install...')
  try {
    const install = spawn('npm', ['install', '--silent', '--no-audit', '--no-fund'], {
      cwd: SERVER_DIR,
      stdio: 'pipe'
    })
    let out = '', err = ''
    install.stdout.on('data', d => out += d.toString())
    install.stderr.on('data', d => err += d.toString())
    await new Promise((resolve, reject) => {
      install.on('close', code => {
        if (code === 0) resolve()
        else reject(new Error(`npm install exit ${code}: ${err || out}`))
      })
      install.on('error', reject)
    })
    log('npm install complete')
  } catch (e) {
    log('npm install failed: ' + e.message)
    log('Continuing without dependencies - some features may not work')
  }
}

const app = new Hono()
app.use('/*', cors())
app.use('*', async (c, next) => {
  const start = Date.now()
  await next()
  log(`${c.req.method} ${c.req.url} ${c.res.status} (${Date.now() - start}ms)`)
})

const PORT = process.env.PORT || 3000
const SESSIONS_DIR = path.join(SERVER_DIR, '..', 'sessions')
const SKILLS_DIR = path.join(SERVER_DIR, '..', 'skills')
const HOME = '/root/workspace'

if (!fs.existsSync(SESSIONS_DIR)) {
  fs.mkdirSync(SESSIONS_DIR, { recursive: true })
}
if (!fs.existsSync(SKILLS_DIR)) {
  fs.mkdirSync(SKILLS_DIR, { recursive: true })
}

const AGENT_CONFIG_FILE = path.join(SERVER_DIR, 'agent_config.json')
const PERMISSIONS_FILE = path.join(SERVER_DIR, 'permissions.json')
let agentConfigStore = null
let alwaysPermissions = [] // Persisted "always allow / always deny" rules

function loadAgentConfig() {
  try {
    if (fs.existsSync(AGENT_CONFIG_FILE)) {
      agentConfigStore = JSON.parse(fs.readFileSync(AGENT_CONFIG_FILE, 'utf-8'))
      return
    }
  } catch (e) {
    log('Failed to load agent config: ' + e.message)
  }
  agentConfigStore = {
    engine: 'codex',
    model: 'codex-ollama',
    temperature: 0.7,
    maxTokens: 4096,
    systemPrompt: '',
    enabledTools: ['read', 'write', 'bash', 'grep', 'glob', 'task'],
    approvalMode: 'ASK',
    autoApprove: false
  }
}

function saveAgentConfig() {
  try {
    if (agentConfigStore) {
      fs.writeFileSync(AGENT_CONFIG_FILE, JSON.stringify(agentConfigStore, null, 2))
    }
  } catch (e) {
    log('Failed to save agent config: ' + e.message)
  }
}

function loadPermissions() {
  try {
    if (fs.existsSync(PERMISSIONS_FILE)) {
      alwaysPermissions = JSON.parse(fs.readFileSync(PERMISSIONS_FILE, 'utf-8'))
    }
  } catch (e) {
    log('Failed to load permissions: ' + e.message)
    alwaysPermissions = []
  }
}

function savePermissions() {
  try {
    fs.writeFileSync(PERMISSIONS_FILE, JSON.stringify(alwaysPermissions, null, 2))
  } catch (e) {
    log('Failed to save permissions: ' + e.message)
  }
}

loadAgentConfig()
loadPermissions()

const sessions = new Map()
const pendingPermissions = new Map() // permissionId -> { sessionId, type, title, description, details, status, remember, resolve }

function loadSessions() {
  try {
    for (const file of fs.readdirSync(SESSIONS_DIR)) {
      if (file.endsWith('.json')) {
        const id = file.replace('.json', '')
        const data = JSON.parse(fs.readFileSync(path.join(SESSIONS_DIR, file), 'utf-8'))
        sessions.set(id, data)
      }
    }
  } catch (e) {
    log('Failed to load sessions: ' + e.message)
  }
}

function saveSession(id) {
  const session = sessions.get(id)
  if (session) {
    fs.writeFileSync(path.join(SESSIONS_DIR, `${id}.json`), JSON.stringify(session, null, 2))
  }
}

function initDefault() {
  if (!sessions.has('default')) {
    sessions.set('default', {
      id: 'default', title: 'New Session', workdir: HOME,
      messages: [], createdAt: Date.now(), lastMessage: '', engine: 'codex'
    })
    saveSession('default')
  }
}



function callCodex(messages, workdir, engine = 'codex', model = null, tempImageFile = null) {
  return new Promise((resolve, reject) => {
    const last = messages.filter(m => m.role === 'user').pop()
    if (!last) return reject(new Error('No user message'))

    const cwd = workdir && fs.existsSync(workdir) ? workdir : HOME

    let codex;
    if (engine === 'antigravity') {
      const args = []
      if (model) {
        args.push('--model', model)
      }
      if (agentConfigStore && agentConfigStore.approvalMode === 'AUTO_APPROVE') {
        args.push('--dangerously-skip-permissions')
      }
      args.push('--print', last.content)
      codex = spawn('agy', args, {
        cwd,
        env: { ...process.env, HOME: '/root' },
        stdio: ['ignore', 'pipe', 'pipe'],
        timeout: 120000
      })
    } else {
      const args = []
      if (model) {
        args.push('--model', model)
      }
      if (agentConfigStore && agentConfigStore.approvalMode === 'AUTO_APPROVE') {
        args.push('--dangerously-bypass-approvals-and-sandbox')
        args.push('exec', '--skip-git-repo-check')
      } else {
        args.push('--sandbox', 'danger-full-access', '--ask-for-approval', 'untrusted')
        args.push('exec', '--skip-git-repo-check')
      }
      if (tempImageFile) {
        args.push('-i', tempImageFile)
      }
      args.push('--', last.content)

      codex = spawn('codex-ollama', args, {
        cwd,
        env: { ...process.env, HOME: '/root' },
        stdio: ['ignore', 'pipe', 'pipe'],
        timeout: 120000
      })
    }

    let stdout = '', stderr = ''
    codex.stdout.on('data', d => stdout += d.toString())
    codex.stderr.on('data', d => stderr += d.toString())
    codex.on('close', code => {
      if (code !== 0) {
        reject(new Error(stderr || stdout || `exit ${code}`))
      } else {
        const response = stdout.trim()
        resolve(response || '(no output)')
      }
    })
    codex.on('error', err => reject(new Error(`Engine error: ${err.message}`)))
    setTimeout(() => { codex.kill(); if (!stdout) reject(new Error('timeout')) }, 120000)
  })
}

// === Routes ===

app.get('/api/health', c => c.json({ status: 'ok', timestamp: Date.now() }))

app.get('/api/workspaces', c => {
  const targetDir = c.req.query('dir') || HOME
  const result = { path: targetDir, name: path.basename(targetDir), subdirs: [] }
  try {
    if (fs.existsSync(targetDir) && fs.statSync(targetDir).isDirectory()) {
      const entries = fs.readdirSync(targetDir, { withFileTypes: true })
      for (const entry of entries) {
        if (!entry.isDirectory()) continue
        if (entry.name.startsWith('.') || entry.name === 'node_modules' || entry.name === 'lost+found') continue
        const full = path.join(targetDir, entry.name)
        result.subdirs.push({
          path: full,
          name: entry.name,
          isProject: !fs.readdirSync(full).some(f => {
            try { return fs.statSync(path.join(full, f)).isDirectory() && !f.startsWith('.') && f !== 'node_modules' } catch (_) { return false }
          })
        })
      }
    }
  } catch (_) {}
  return c.json(result)
})

app.get('/api/sessions', c => {
  const list = [...sessions.values()].map(s => ({
    id: s.id, title: s.title, workdir: s.workdir || HOME,
    lastMessage: s.lastMessage || '',
    timestamp: s.createdAt || s.updatedAt || Date.now()
  }))
  list.sort((a, b) => b.timestamp - a.timestamp)
  return c.json(list)
})

app.get('/api/sessions/:id', c => {
  const s = sessions.get(c.req.param('id'))
  return s ? c.json(s) : c.json({ error: 'Not found' }, 404)
})

app.post('/api/sessions', async c => {
  const body = await c.req.json()
  const id = crypto.randomUUID()
  const workdir = body.workdir || HOME
  const session = {
    id, title: body.title || 'New Session', workdir,
    messages: [], createdAt: Date.now(), updatedAt: Date.now(), lastMessage: '',
    engine: body.engine || 'codex'
  }
  sessions.set(id, session)
  saveSession(id)
  return c.json(session, 201)
})

app.delete('/api/sessions/:id', c => {
  const id = c.req.param('id')
  if (sessions.has(id)) {
    sessions.delete(id)
    try { fs.unlinkSync(path.join(SESSIONS_DIR, `${id}.json`)) } catch (_) {}
    return c.json({ deleted: true })
  }
  return c.json({ error: 'Not found' }, 404)
})

app.post('/api/sessions/:id/engine', async c => {
  const id = c.req.param('id')
  const { engine } = await c.req.json()
  if (sessions.has(id)) {
    const session = sessions.get(id)
    session.engine = engine
    saveSession(id)
    return c.json({ success: true, engine })
  }
  return c.json({ error: 'Not found' }, 404)
})

function runCmd(cmd, args, cwd) {
  return new Promise((resolve) => {
    const p = spawn(cmd, args, { cwd, env: { ...process.env, HOME: '/root' } })
    let stdout = '', stderr = ''
    p.stdout.on('data', d => stdout += d.toString())
    p.stderr.on('data', d => stderr += d.toString())
    p.on('close', code => {
      resolve({ code, stdout: stdout.trim(), stderr: stderr.trim() })
    })
    p.on('error', err => {
      resolve({ code: -1, stdout: '', stderr: err.message })
    })
  })
}

app.get('/api/git/status', async c => {
  const workdir = c.req.query('dir') || HOME
  if (!fs.existsSync(workdir)) {
    return c.json({ error: 'Directory does not exist' }, 404)
  }
  const checkGit = await runCmd('git', ['rev-parse', '--is-inside-work-tree'], workdir)
  if (checkGit.code !== 0) {
    return c.json({ isGit: false, files: [] })
  }
  const statusRes = await runCmd('git', ['status', '--porcelain'], workdir)
  const files = []
  if (statusRes.code === 0 && statusRes.stdout) {
    const lines = statusRes.stdout.split('\n')
    for (const line of lines) {
      if (line.length < 4) continue
      const status = line.substring(0, 2).trim()
      const filePath = line.substring(3).trim()
      let cleanPath = filePath
      if (cleanPath.startsWith('"') && cleanPath.endsWith('"')) {
        cleanPath = cleanPath.slice(1, -1)
      }
      files.push({ path: cleanPath, status })
    }
  }
  return c.json({ isGit: true, files })
})

app.post('/api/git/init', async c => {
  const { dir } = await c.req.json()
  const workdir = dir || HOME
  if (!fs.existsSync(workdir)) {
    return c.json({ error: 'Directory does not exist' }, 404)
  }
  const initRes = await runCmd('git', ['init'], workdir)
  if (initRes.code !== 0) {
    return c.json({ error: 'git init failed: ' + initRes.stderr }, 500)
  }
  await runCmd('git', ['add', '.'], workdir)
  await runCmd('git', ['commit', '-m', 'Initial commit by Aether'], workdir)
  return c.json({ success: true })
})

app.get('/api/git/diff', async c => {
  const workdir = c.req.query('dir') || HOME
  const filePath = c.req.query('file')
  if (!fs.existsSync(workdir)) {
    return c.json({ error: 'Directory does not exist' }, 404)
  }
  if (!filePath) {
    const diffRes = await runCmd('git', ['diff', '--no-color'], workdir)
    return c.json({ diff: diffRes.stdout || diffRes.stderr })
  }
  const statusRes = await runCmd('git', ['status', '--porcelain', filePath], workdir)
  const isUntracked = statusRes.stdout.startsWith('??')
  let diffRes
  if (isUntracked) {
    diffRes = await runCmd('git', ['diff', '--no-color', '--no-index', '/dev/null', filePath], workdir)
  } else {
    diffRes = await runCmd('git', ['diff', '--no-color', filePath], workdir)
  }
  return c.json({ diff: diffRes.stdout || diffRes.stderr })
})

app.post('/api/chat', async c => {
  const { message, sessionId, imageBase64, mimeType } = await c.req.json()
  if (!message) return c.json({ error: 'Message required' }, 400)

  const sid = sessionId || 'default'
  if (!sessions.has(sid)) {
    sessions.set(sid, {
      id: sid,
      title: 'New Session',
      workdir: HOME,
      messages: [],
      createdAt: Date.now(),
      lastMessage: '',
      engine: 'codex'
    })
    saveSession(sid)
  }
  const session = sessions.get(sid)

  session.messages.push({ role: 'user', content: message, timestamp: Date.now() })
  session.lastMessage = message
  session.updatedAt = Date.now()

  if (session.title === 'New Session' && session.messages.filter(m => m.role === 'user').length === 1) {
    session.title = message.substring(0, 50)
  }

  let tempImageFile = null
  if (imageBase64) {
    try {
      const buffer = Buffer.from(imageBase64, 'base64')
      const ext = (mimeType && mimeType.split('/')[1]) || 'png'
      tempImageFile = path.join(os.tmpdir(), `codex-image-${crypto.randomUUID()}.${ext}`)
      fs.writeFileSync(tempImageFile, buffer)
      log(`Saved temporary image for chat: ${tempImageFile}`)
    } catch (e) {
      log(`Failed to save temporary image: ${e.message}`)
    }
  }

  const cleanup = () => {
    if (tempImageFile && fs.existsSync(tempImageFile)) {
      try {
        fs.unlinkSync(tempImageFile)
        log(`Cleaned up temporary image: ${tempImageFile}`)
      } catch (_) {}
    }
  }

  try {
    let model = null
    if (session.engine === agentConfigStore.engine) {
      model = agentConfigStore.model
    } else {
      model = session.engine === 'antigravity' ? 'gemini-2.5-flash' : 'codex-ollama'
    }
    const response = await callCodex(session.messages, session.workdir, session.engine, model, tempImageFile)
    session.messages.push({ role: 'assistant', content: response, timestamp: Date.now() })
    session.updatedAt = Date.now()
    saveSession(sid)
    cleanup()
    return c.json({ response, sessionId: sid })
  } catch (err) {
    log('Codex error: ' + err.message)
    session.messages.push({ role: 'system', content: `Error: ${err.message}`, timestamp: Date.now() })
    saveSession(sid)
    cleanup()
    return c.json({ error: err.message }, 500)
  }
})

// === SSE Streaming Chat ===

// === Permissions (with persistence for "always" decisions) ===

app.get('/api/permissions/always', c => {
  return c.json({ always: alwaysPermissions })
})

app.delete('/api/permissions/always', c => {
  alwaysPermissions = []
  savePermissions()
  return c.json({ success: true })
})

// Generate a permission request, returning a remembered decision if "always" was previously chosen
function createPermissionRequest(sessionId, type, title, description, details = {}) {
  const remembered = alwaysPermissions.find(p => p.type === type)
  if (remembered) {
    return {
      id: crypto.randomUUID(),
      sessionId, type, title, description, details,
      status: remembered.granted ? 'GRANTED_ALWAYS' : 'DENIED_ALWAYS',
      remember: true,
      autoResolved: true,
      granted: remembered.granted,
      createdAt: Date.now()
    }
  }
  const id = crypto.randomUUID()
  const request = {
    id, sessionId, type, title, description, details,
    status: 'PENDING', remember: false, createdAt: Date.now()
  }
  pendingPermissions.set(id, request)
  return request
}

function resolvePermissionRequest(id, granted, remember) {
  const req = pendingPermissions.get(id)
  if (!req) return false
  req.status = remember ? (granted ? 'GRANTED_ALWAYS' : 'DENIED_ALWAYS') : (granted ? 'GRANTED' : 'DENIED')
  if (req.resolve) {
    req.resolve({ granted, remember, status: req.status })
  }
  if (remember) {
    // Persist the "always" decision keyed by type for future auto-resolve
    alwaysPermissions = alwaysPermissions.filter(p => p.type !== req.type)
    alwaysPermissions.push({
      type: req.type,
      granted,
      createdAt: Date.now()
    })
    savePermissions()
  } else {
    // Only forget one-off decisions
    setTimeout(() => pendingPermissions.delete(id), 1000)
  }
  return true
}

app.get('/api/permissions', c => {
  const list = [...pendingPermissions.values()].map(p => ({
    id: p.id, sessionId: p.sessionId, type: p.type,
    title: p.title, description: p.description,
    details: p.details, status: p.status,
    timestamp: p.createdAt
  }))
  return c.json(list)
})

app.post('/api/permissions/:id/respond', async c => {
  const id = c.req.param('id')
  const { granted, remember } = await c.req.json()
  const ok = resolvePermissionRequest(id, !!granted, !!remember)
  if (!ok) return c.json({ error: 'Permission not found or already resolved' }, 404)
  return c.json({ success: true })
})

// === Skills (server-side persistence; the client mirrors these) ===

function parseSkillMd(content) {
  const frontmatterRegex = /^---\r?\n([\s\S]*?)\r?\n---/;
  const match = content.match(frontmatterRegex);
  const metadata = {
    name: "Unnamed Skill",
    description: "",
    version: "1.0.0",
    author: "",
    tags: []
  };
  
  if (match) {
    const yamlText = match[1];
    const lines = yamlText.split('\n');
    for (const line of lines) {
      const parts = line.split(':');
      if (parts.length >= 2) {
        const key = parts[0].trim();
        const value = parts.slice(1).join(':').trim().replace(/^['"]|['"]$/g, ''); // Remove quotes
        if (key === 'name') metadata.name = value;
        if (key === 'description') metadata.description = value;
        if (key === 'version') metadata.version = value;
        if (key === 'author') metadata.author = value;
        if (key === 'tags') {
          const cleanVal = value.replace(/[\[\]]/g, '');
          metadata.tags = cleanVal.split(',').map(t => t.trim()).filter(t => t.length > 0);
        }
      }
    }
  }
  return metadata;
}

app.get('/api/skills', c => {
  const skills = []
  try {
    if (fs.existsSync(SKILLS_DIR)) {
      const files = fs.readdirSync(SKILLS_DIR)
      for (const file of files) {
        const dirPath = path.join(SKILLS_DIR, file)
        if (fs.statSync(dirPath).isDirectory()) {
          const skillMdPath = path.join(dirPath, 'SKILL.md')
          if (fs.existsSync(skillMdPath)) {
            try {
              const content = fs.readFileSync(skillMdPath, 'utf-8')
              const metadata = parseSkillMd(content)
              
              let isEnabled = true
              const configPath = path.join(dirPath, 'config.json')
              if (fs.existsSync(configPath)) {
                try {
                  const config = JSON.parse(fs.readFileSync(configPath, 'utf-8'))
                  if (config && typeof config.isEnabled === 'boolean') {
                    isEnabled = config.isEnabled
                  }
                } catch (_) {}
              }
              
              const stats = fs.statSync(skillMdPath)
              skills.push({
                id: file,
                name: metadata.name,
                description: metadata.description,
                version: metadata.version,
                author: metadata.author,
                tags: metadata.tags,
                content: content,
                isEnabled: isEnabled,
                installedAt: stats.birthtimeMs || stats.ctimeMs,
                updatedAt: stats.mtimeMs
              })
            } catch (_) {}
          }
        }
      }
    }
  } catch (e) {
    log('Failed to read skills directory: ' + e.message)
  }
  return c.json({ skills })
})

app.post('/api/skills', async c => {
  const body = await c.req.json()
  if (!body || !body.id || !body.content) return c.json({ error: 'id and content required' }, 400)
  try {
    const skillPath = path.join(SKILLS_DIR, body.id)
    if (!fs.existsSync(skillPath)) {
      fs.mkdirSync(skillPath, { recursive: true })
    }
    
    // Write SKILL.md
    fs.writeFileSync(path.join(skillPath, 'SKILL.md'), body.content, 'utf-8')
    
    // Write config.json
    const configPath = path.join(skillPath, 'config.json')
    const configData = { isEnabled: body.isEnabled !== false }
    fs.writeFileSync(configPath, JSON.stringify(configData, null, 2), 'utf-8')
    
    return c.json({ success: true, skill: body })
  } catch (e) {
    return c.json({ error: e.message }, 500)
  }
})

app.delete('/api/skills/:id', c => {
  const id = c.req.param('id')
  const skillPath = path.join(SKILLS_DIR, id)
  if (fs.existsSync(skillPath)) {
    try {
      fs.rmSync(skillPath, { recursive: true, force: true })
      return c.json({ success: true })
    } catch (e) {
      return c.json({ error: e.message }, 500)
    }
  }
  return c.json({ error: 'not found' }, 404)
})

// Cache for marketplace skills fetched from GitHub
let marketplaceCache = null
let marketplaceCacheTime = 0
const MARKETPLACE_CACHE_TTL = 5 * 60 * 1000 // 5 minutes

async function fetchMarketplaceSkillsFromGitHub() {
  const now = Date.now()
  if (marketplaceCache && (now - marketplaceCacheTime) < MARKETPLACE_CACHE_TTL) {
    return marketplaceCache
  }

  try {
    const fetchWithHttps = (url) => new Promise((resolve, reject) => {
      https.get(url, { headers: { 'User-Agent': 'AetherApp/1.0', 'Accept': 'application/vnd.github.v3+json' } }, (res) => {
        let data = ''
        if (res.statusCode === 301 || res.statusCode === 302) {
          return fetchWithHttps(res.headers.location).then(resolve).catch(reject)
        }
        res.on('data', chunk => data += chunk)
        res.on('end', () => {
          try { resolve({ status: res.statusCode, body: data }) } catch (e) { reject(e) }
        })
      }).on('error', reject)
    })

    // 1. List all curated skill directories
    const listRes = await fetchWithHttps('https://api.github.com/repos/openai/skills/contents/skills/.curated')
    if (listRes.status !== 200) throw new Error(`GitHub API returned ${listRes.status}`)
    const dirs = JSON.parse(listRes.body).filter(e => e.type === 'dir')

    // 2. Fetch each SKILL.md in parallel (limit concurrency to 5)
    const skills = []
    const chunkSize = 5
    for (let i = 0; i < dirs.length; i += chunkSize) {
      const chunk = dirs.slice(i, i + chunkSize)
      const results = await Promise.allSettled(chunk.map(async (dir) => {
        const rawUrl = `https://raw.githubusercontent.com/openai/skills/main/${dir.path}/SKILL.md`
        const mdRes = await fetchWithHttps(rawUrl)
        if (mdRes.status !== 200) throw new Error(`${rawUrl} returned ${mdRes.status}`)
        const content = mdRes.body
        const metadata = parseSkillMd(content)
        return {
          id: dir.name,
          name: metadata.name || dir.name,
          description: metadata.description || '',
          version: metadata.version || '1.0.0',
          author: metadata.author || 'OpenAI',
          tags: metadata.tags || [],
          content,
          rawUrl
        }
      }))
      for (const r of results) {
        if (r.status === 'fulfilled') skills.push(r.value)
      }
    }

    marketplaceCache = skills
    marketplaceCacheTime = now
    log(`Fetched ${skills.length} marketplace skills from GitHub`)
    return skills
  } catch (e) {
    log(`Failed to fetch marketplace from GitHub: ${e.message}`)
    // Return cached data even if stale
    if (marketplaceCache) return marketplaceCache
    return []
  }
}

app.get('/api/marketplace/skills', async c => {
  const skills = await fetchMarketplaceSkillsFromGitHub()
  return c.json({ skills })
})

// === Agent Config (server-side persistence; the client mirrors these) ===

app.get('/api/agent/config', c => {
  return c.json(agentConfigStore)
})

app.put('/api/agent/config', async c => {
  const body = await c.req.json()
  agentConfigStore = { ...agentConfigStore, ...body }
  saveAgentConfig()
  return c.json({ success: true, config: agentConfigStore })
})

app.post('/api/chat/stream', async c => {
  const { message, sessionId, imageBase64, mimeType } = await c.req.json()
  if (!message) return c.json({ error: 'Message required' }, 400)

  const sid = sessionId || 'default'
  if (!sessions.has(sid)) {
    sessions.set(sid, {
      id: sid,
      title: 'New Session',
      workdir: HOME,
      messages: [],
      createdAt: Date.now(),
      lastMessage: '',
      engine: 'codex'
    })
    saveSession(sid)
  }
  const session = sessions.get(sid)

  session.messages.push({ role: 'user', content: message, timestamp: Date.now() })
  session.lastMessage = message
  session.updatedAt = Date.now()

  if (session.title === 'New Session' && session.messages.filter(m => m.role === 'user').length === 1) {
    session.title = message.substring(0, 50)
  }

  const engine = session.engine || 'codex'
  const last = session.messages.filter(m => m.role === 'user').pop()
  const cwd = session.workdir && fs.existsSync(session.workdir) ? session.workdir : HOME

  let tempImageFile = null
  if (imageBase64) {
    try {
      const buffer = Buffer.from(imageBase64, 'base64')
      const ext = (mimeType && mimeType.split('/')[1]) || 'png'
      tempImageFile = path.join(os.tmpdir(), `codex-image-${crypto.randomUUID()}.${ext}`)
      fs.writeFileSync(tempImageFile, buffer)
      log(`Saved temporary image for stream: ${tempImageFile}`)
    } catch (e) {
      log(`Failed to save temporary image for stream: ${e.message}`)
    }
  }

  const cleanup = () => {
    if (tempImageFile && fs.existsSync(tempImageFile)) {
      try {
        fs.unlinkSync(tempImageFile)
        log(`Cleaned up temporary image: ${tempImageFile}`)
      } catch (_) {}
    }
  }

  let model = null
  if (session.engine === agentConfigStore.engine) {
    model = agentConfigStore.model
  } else {
    model = session.engine === 'antigravity' ? 'gemini-2.5-flash' : 'codex-ollama'
  }

  return streamSSE(c, async (stream) => {
    let fullResponse = ''
    let resolved = false

    let codex;
    if (engine === 'antigravity') {
      const args = []
      if (model) {
        args.push('--model', model)
      }
      if (agentConfigStore && agentConfigStore.approvalMode === 'AUTO_APPROVE') {
        args.push('--dangerously-skip-permissions')
      }
      args.push('--print', last.content)
      codex = spawn('agy', args, {
        cwd,
        env: { ...process.env, HOME: '/root' },
        stdio: ['ignore', 'pipe', 'pipe']
      })
    } else {
      const args = []
      if (model) {
        args.push('--model', model)
      }
      if (agentConfigStore && agentConfigStore.approvalMode === 'AUTO_APPROVE') {
        args.push('--dangerously-bypass-approvals-and-sandbox')
        args.push('exec', '--skip-git-repo-check')
      } else {
        args.push('--sandbox', 'danger-full-access', '--ask-for-approval', 'untrusted')
        args.push('exec', '--skip-git-repo-check')
      }
      if (tempImageFile) {
        args.push('-i', tempImageFile)
      }
      args.push('--', last.content)

      codex = spawn('codex-ollama', args, {
        cwd,
        env: { ...process.env, HOME: '/root' },
        stdio: ['ignore', 'pipe', 'pipe']
      })
    }

    stream.onAbort(() => {
      if (!resolved) {
        resolved = true
        log('Stream aborted by client, killing Codex process')
        if (codex) {
          try {
            codex.kill()
          } catch (e) {
            log(`Failed to kill Codex process: ${e.message}`)
          }
        }
        cleanup()

        if (fullResponse.trim()) {
          session.messages.push({ role: 'assistant', content: fullResponse.trim(), timestamp: Date.now() })
          session.updatedAt = Date.now()
          saveSession(sid)
        }
      }
    })

    let timeoutTimer = null

    const resetTimeout = () => {
      if (timeoutTimer) clearTimeout(timeoutTimer)
      timeoutTimer = setTimeout(() => {
        if (!resolved) {
          resolved = true
          log('Codex stream request timed out due to inactivity')
          codex.kill()
          cleanup()
          stream.writeSSE({
            data: JSON.stringify({ error: 'Request timed out' }),
            event: 'error'
          }).catch(() => {})
        }
      }, 180000) // 180 seconds inactivity timeout
    }

    resetTimeout()

    const keepAliveInterval = setInterval(() => {
      stream.writeSSE({
        data: '',
        event: 'keep-alive'
      }).catch(() => {})
    }, 10000) // 10 seconds keep-alive comment

    const sendChunk = async (text) => {
      fullResponse += text
      await stream.writeSSE({
        data: JSON.stringify({ chunk: text }),
        event: 'chunk'
      })
    }

    let buffer = ''
    const flushBuffer = async () => {
      if (buffer) {
        await sendChunk(buffer)
        buffer = ''
      }
    }

    const flushInterval = setInterval(() => { flushBuffer().catch(() => {}) }, 100)

    let codexMarkerFound = true
    let tempBuffer = ''

    codex.stdout.on('data', (d) => {
      resetTimeout()
      const text = d.toString()
      log(`Codex stdout chunk: ${text.substring(0, 100).replace(/\n/g, '\\n')}`)
      if (!codexMarkerFound) {
        tempBuffer += text
        const lower = tempBuffer.toLowerCase()
        let index = lower.indexOf('codex')
        
        if (index !== -1) {
          codexMarkerFound = true
          index += 5
          while (index < tempBuffer.length && (tempBuffer[index] === ' ' || tempBuffer[index] === '\n' || tempBuffer[index] === '\r' || tempBuffer[index] === '\t')) {
            index++
          }
          const remaining = tempBuffer.substring(index)
          const tokensIndex = remaining.toLowerCase().indexOf('tokens used')
          const textToSend = tokensIndex !== -1 ? remaining.substring(0, tokensIndex) : remaining
          if (textToSend) {
            buffer += textToSend
            flushBuffer().catch(() => {})
          }
        }
      } else {
        const tokensIndex = engine === 'antigravity' ? -1 : text.toLowerCase().indexOf('tokens used')
        const textToSend = tokensIndex !== -1 ? text.substring(0, tokensIndex) : text
        if (textToSend) {
          buffer += textToSend
          if (buffer.includes('\n')) {
            flushBuffer().catch(() => {})
          }
        }
        if (tokensIndex !== -1) {
          codex.kill()
        }
      }
    })

    codex.stderr.on('data', (d) => {
      resetTimeout()
      log(`Codex stderr: ${d.toString().trim()}`)
    })

    codex.on('close', async (code) => {
      if (timeoutTimer) clearTimeout(timeoutTimer)
      clearInterval(keepAliveInterval)
      log(`Codex process closed with code ${code}`)
      clearInterval(flushInterval)
      await flushBuffer()
      cleanup()

      if (!resolved) {
        resolved = true
        if (code !== 0 && !fullResponse.trim()) {
          await stream.writeSSE({
            data: JSON.stringify({ error: `Codex exited with code ${code}` }),
            event: 'error'
          })
        } else {
          session.messages.push({ role: 'assistant', content: fullResponse.trim() || '(no output)', timestamp: Date.now() })
          session.updatedAt = Date.now()
          saveSession(sid)

          await stream.writeSSE({
            data: JSON.stringify({ done: true, sessionId: sid }),
            event: 'done'
          })
        }
      }
    })

    codex.on('error', async (err) => {
      if (timeoutTimer) clearTimeout(timeoutTimer)
      clearInterval(keepAliveInterval)
      clearInterval(flushInterval)
      cleanup()
      if (!resolved) {
        resolved = true
        await stream.writeSSE({
          data: JSON.stringify({ error: err.message }),
          event: 'error'
        })
      }
    })



    await new Promise((resolve) => {
      const check = setInterval(() => {
        if (resolved) { clearInterval(check); resolve() }
      }, 200)
    })
  })
})

// === Start ===

function checkAlreadyRunning(port) {
  return new Promise((resolve) => {
    const req = http.get(`http://127.0.0.1:${port}/api/health`, { timeout: 1000 }, (res) => {
      let data = ''
      res.on('data', chunk => data += chunk)
      res.on('end', () => {
        try {
          const parsed = JSON.parse(data)
          resolve(parsed.status === 'ok')
        } catch (_) {
          resolve(false)
        }
      })
    })
    req.on('error', () => resolve(false))
    req.on('timeout', () => { req.destroy(); resolve(false) })
  })
}

log('Starting Codex Server...')
log('Node version: ' + process.version)
log('Server dir: ' + SERVER_DIR)

if (await checkAlreadyRunning(PORT)) {
  log(`Codex Server is already running on port ${PORT}. Exiting gracefully.`)
  process.exit(0)
}

loadSessions()
initDefault()
log('Loaded ' + sessions.size + ' session(s)')

serve({ fetch: app.fetch, port: PORT, hostname: '0.0.0.0' }, info => {
  log('Codex Server running on http://0.0.0.0:' + info.port)
})
