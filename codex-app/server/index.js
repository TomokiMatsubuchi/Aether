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
const HOME = '/root/workspace'

if (!fs.existsSync(SESSIONS_DIR)) {
  fs.mkdirSync(SESSIONS_DIR, { recursive: true })
}

const sessions = new Map()

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



function callCodex(messages, workdir, engine = 'codex', tempImageFile = null) {
  return new Promise((resolve, reject) => {
    const last = messages.filter(m => m.role === 'user').pop()
    if (!last) return reject(new Error('No user message'))

    const cwd = workdir && fs.existsSync(workdir) ? workdir : HOME

    let codex;
    if (engine === 'antigravity') {
      codex = spawn('agy', [
        '--print',
        last.content
      ], {
        cwd,
        env: { ...process.env, HOME: '/root' },
        stdio: ['ignore', 'pipe', 'pipe'],
        timeout: 120000
      })
    } else {
      const args = [
        'exec',
        '--skip-git-repo-check',
        '--dangerously-bypass-approvals-and-sandbox'
      ]
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
    const response = await callCodex(session.messages, session.workdir, session.engine, tempImageFile)
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

  return streamSSE(c, async (stream) => {
    let fullResponse = ''
    let resolved = false

    let codex;
    if (engine === 'antigravity') {
      codex = spawn('agy', [
        '--print',
        last.content
      ], {
        cwd,
        env: { ...process.env, HOME: '/root' },
        stdio: ['ignore', 'pipe', 'pipe']
      })
    } else {
      const args = [
        'exec',
        '--skip-git-repo-check',
        '--dangerously-bypass-approvals-and-sandbox'
      ]
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
