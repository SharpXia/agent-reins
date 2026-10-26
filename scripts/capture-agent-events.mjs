#!/usr/bin/env node
// M2 fixture 录制：真实网关上跑一个会触发工具调用的回合，录制完整 agent 事件帧 +
// 前后 chat.history 快照，写入 scripts/fixtures/agent-events-capture.json 供解析器单测。
//
//   set -a; source .env; set +a
//   node scripts/capture-agent-events.mjs            # 默认 agent:main:main
//   node scripts/capture-agent-events.mjs --key agent:main:main --out 路径
//
// 只录制 connect 之后的 event 帧与 history res 帧；hello-ok（含 deviceToken）不落盘。
import { randomUUID, generateKeyPairSync, sign as edSign, createPrivateKey, createHash } from "node:crypto";
import { readFileSync, writeFileSync, existsSync, mkdirSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const URL_ = process.env.OPENCLAW_GATEWAY_URL || "ws://127.0.0.1:18789";
const TOKEN = process.env.OPENCLAW_API_KEY;
if (!TOKEN) { console.error("missing OPENCLAW_API_KEY"); process.exit(1); }

const arg = (name, dflt) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : dflt; };
const SESSION = arg("key", "agent:main:main");
const OUT = arg("out", join(dirname(fileURLToPath(import.meta.url)), "fixtures", "agent-events-capture.json"));
const TEXT = arg("text", "AgentReins M2 fixture 录制：请用 exec 工具执行 `echo agentreins-m2-fixture-ok`，把输出原样告诉我，然后结束。");

const CLIENT = {
  id: process.env.OC_CLIENT_ID || "openclaw-ios",
  version: "0.1.0",
  platform: process.env.OC_PLATFORM || "macos",
  deviceFamily: process.env.OC_DEVICE_FAMILY || "Mac",
  mode: "ui",
};
const ROLE = "operator";
const SCOPES = ["operator.read", "operator.write"];
const b64u = (b) => Buffer.from(b).toString("base64url");

const keyFile = join(dirname(fileURLToPath(import.meta.url)), "..", ".device-identity.json");
if (!existsSync(keyFile)) { console.error("no .device-identity.json — 先跑 protocol-probe.mjs pair"); process.exit(1); }
const { privPem, pubB64u } = JSON.parse(readFileSync(keyFile, "utf8"));
const deviceId = createHash("sha256").update(Buffer.from(pubB64u, "base64url")).digest("hex");

const ws = new WebSocket(URL_);
let seq = 1;
const pending = new Map();
const request = (method, params) => {
  const id = `${seq++}:${randomUUID()}`;
  ws.send(JSON.stringify({ type: "req", id, method, params }));
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    setTimeout(() => { if (pending.has(id)) { pending.get(id).reject(new Error(`${method} timeout`)); pending.delete(id); } }, 30000);
  });
};

const frames = []; // connect 之后的全部 event 帧（含 seq），res 只留 history 快照
let capturedRunIds = new Set();

ws.addEventListener("close", (e) => { console.log("## CLOSED", e.code, e.reason || ""); });
ws.addEventListener("error", (e) => console.log("## ERROR", e.message || e));

ws.addEventListener("message", async (ev) => {
  const f = JSON.parse(ev.data);
  if (f.type === "event" && f.event === "connect.challenge") {
    const payload = ["v3", deviceId, CLIENT.id, CLIENT.mode, ROLE, SCOPES.join(","),
      String(f.payload.ts), TOKEN, f.payload.nonce,
      CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
    const params = {
      minProtocol: 4, maxProtocol: 4,
      client: { ...CLIENT, instanceId: randomUUID() },
      caps: [], auth: { token: TOKEN }, role: ROLE, scopes: SCOPES,
      device: {
        id: deviceId, publicKey: pubB64u,
        signature: b64u(edSign(null, Buffer.from(payload, "utf8"), createPrivateKey(privPem))),
        signedAt: f.payload.ts, nonce: f.payload.nonce,
      },
    };
    const hello = await request("connect", params);
    if (!hello.ok) { console.log("## connect REJECTED:", JSON.stringify(hello.error)); process.exit(1); }
    console.log("## hello-ok scopes =", JSON.stringify(hello.payload.auth.scopes));
    await dispatch();
    return;
  }
  if (f.type === "event") {
    if (f.event === "agent" && f.payload?.runId) capturedRunIds.add(f.payload.runId);
    frames.push(f);
    return;
  }
  if (f.type === "res" && pending.has(f.id)) {
    const p = pending.get(f.id); pending.delete(f.id);
    p.resolve(f);
  }
});

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function history(limit) {
  const r = await request("chat.history", { sessionKey: SESSION, limit });
  if (!r.ok) throw new Error(`chat.history: ${JSON.stringify(r.error)}`);
  return r.payload;
}

async function dispatch() {
  console.log(`## history before (total=${(await history(4)).totalMessages})`);
  const historyBefore = await history(4);

  const send = await request("sessions.send", { key: SESSION, message: TEXT, timeoutMs: 120000 });
  if (!send.ok) { console.log("## send FAIL:", JSON.stringify(send.error)); process.exit(1); }
  const runId = send.payload?.runId;
  console.log("## send started, runId =", runId);

  // 等回合结束：出现过 assistant message 流且 10s 无新 agent 事件，或 150s 兜底
  const t0 = Date.now();
  let lastAgentAt = Date.now();
  let sawMessage = false;
  while (Date.now() - t0 < 150_000) {
    await sleep(500);
    const n = frames.filter((f) => f.type === "event").length;
    if (n > 0) lastAgentAt = Math.max(lastAgentAt, ...frames.filter((f) => f.type === "event").map((f) => f.payload?.ts ?? Date.now()));
    if (frames.some((f) => f.event === "agent" && f.payload?.stream === "message")) sawMessage = true;
    if (sawMessage && Date.now() - lastAgentAt > 10_000) break;
  }
  console.log(`## captured ${frames.length} frames in ${Math.round((Date.now() - t0) / 1000)}s`);

  const historyAfter = await history(12);
  console.log(`## history after (total=${historyAfter.totalMessages})`);

  const fixture = {
    capturedAt: new Date().toISOString(),
    gatewayVersion: "2026.9.9",
    sessionKey: SESSION,
    testMessage: TEXT,
    sendResponse: send.payload,
    frames,
    historyBefore,
    historyAfter,
  };
  mkdirSync(dirname(OUT), { recursive: true });
  writeFileSync(OUT, JSON.stringify(fixture, null, 2));
  const streams = {};
  for (const f of frames) if (f.event === "agent") streams[f.payload?.stream] = (streams[f.payload?.stream] ?? 0) + 1;
  console.log("## streams:", JSON.stringify(streams));
  console.log("## runIds:", [...capturedRunIds].length ? [...capturedRunIds].join(",") : "(none — check broadcast)");
  console.log("## written:", OUT);
  setTimeout(() => process.exit(0), 300);
}
