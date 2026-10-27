#!/usr/bin/env node
// M3 探针补充：① sessions.fork/rewind/branches.* 的 scope 实测（分支是否可 A 档）
// ② 同 key 同文本重发的幂等行为（outbox 补发路径）
// ③ sessions.create 完整参数形态（label 等可选字段）
// ④ sessions.delete 实测（探针会话清理 + 「删除会话」能力确认）
// 用法：set -a; source .env; set +a; node scripts/probe-m3-extra.mjs
import { randomUUID, generateKeyPairSync, sign as edSign, createPrivateKey, createHash } from "node:crypto";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const URL_ = process.env.OPENCLAW_GATEWAY_URL || "ws://127.0.0.1:18789";
const TOKEN = process.env.OPENCLAW_API_KEY;
if (!TOKEN) { console.error("missing OPENCLAW_API_KEY"); process.exit(1); }

const CLIENT = { id: "openclaw-macos", version: "0.1.0", platform: "macos", deviceFamily: "Mac", mode: "ui" };
const ROLE = "operator";
const SCOPES = ["operator.read", "operator.write"];
const b64u = (b) => Buffer.from(b).toString("base64url");

const keyFile = join(dirname(fileURLToPath(import.meta.url)), "..", ".device-identity.json");
const { privPem, pubB64u } = JSON.parse(readFileSync(keyFile, "utf8"));
const deviceId = createHash("sha256").update(Buffer.from(pubB64u, "base64url")).digest("hex");

const ws = new WebSocket(URL_);
let seq = 1;
const pending = new Map();
const request = (method, params) => {
  const id = `${seq++}:${randomUUID()}`;
  ws.send(JSON.stringify({ type: "req", id, method, params }));
  return new Promise((resolve) => pending.set(id, resolve));
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const outline = (r) => r.ok
  ? JSON.stringify(r.payload).slice(0, 400)
  : `ERR ${r.error?.code}: ${r.error?.message?.slice(0, 200)}`;

ws.addEventListener("message", async (ev) => {
  const f = JSON.parse(ev.data);
  if (f.type === "event" && f.event === "connect.challenge") {
    const params = {
      minProtocol: 4, maxProtocol: 4,
      client: { ...CLIENT, instanceId: randomUUID() },
      caps: [], auth: { token: TOKEN }, role: ROLE, scopes: SCOPES,
    };
    const payload = ["v3", deviceId, CLIENT.id, CLIENT.mode, ROLE, SCOPES.join(","),
      String(f.payload.ts), TOKEN, f.payload.nonce,
      CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
    params.device = {
      id: deviceId, publicKey: pubB64u,
      signature: b64u(edSign(null, Buffer.from(payload, "utf8"), createPrivateKey(privPem))),
      signedAt: f.payload.ts, nonce: f.payload.nonce,
    };
    const hello = await request("connect", params);
    if (!hello.ok) { console.log("## connect REJECTED:", JSON.stringify(hello.error)); process.exit(1); }
    await main();
    return;
  }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); }
});
ws.addEventListener("open", () => console.log(`## WS OPEN ${URL_}`));

async function main() {
  console.log("== EXTRA: fork / rewind / branches scope ==");
  // 建一个源会话并产生两条消息，供 fork 依据
  const src = await request("sessions.create", { key: "agent:main:probe-m3-src" });
  console.log(`  create src → ${outline(src)}`);
  const srcKey = src.ok ? src.payload.key : "agent:main:probe-m3-src";
  await request("sessions.send", { key: srcKey, message: "agentreins-m3 fork 源消息 1：请只回复 ok1", timeoutMs: 45000 });
  await sleep(9000);

  // ① 分支原语 scope 实测
  const hist = await request("chat.history", { sessionKey: srcKey, limit: 5 });
  const firstUser = hist.ok ? (hist.payload.messages || []).find((m) => m.role === "user") : null;
  console.log(`  fork 锚点消息 id: ${firstUser?.id || "(无 id)"}`);
  for (const [m, params] of [
    ["sessions.fork", { key: srcKey }],
    ["sessions.rewind", { key: srcKey }],
    ["sessions.branches.list", { key: srcKey }],
  ]) {
    const r = await request(m, params);
    console.log(`  ${m} → ${outline(r)}`);
  }

  // ② 同 key 同文本重发幂等
  console.log("== EXTRA: 同 key 同文本重发 ==");
  const idem = `probe-m3b-${Date.now()}`;
  const text = `agentreins-m3 幂等同文探针（${idem}）`;
  const a = await request("sessions.send", { key: srcKey, message: text, timeoutMs: 45000, idempotencyKey: idem });
  console.log(`  send#1 → ${outline(a)}`);
  await sleep(3000);
  const b = await request("sessions.send", { key: srcKey, message: text, timeoutMs: 45000, idempotencyKey: idem });
  console.log(`  send#2 同key同文 → ${outline(b)}`);

  // ③ sessions.create 完整参数形态
  console.log("== EXTRA: sessions.create 可选参数 ==");
  const c = await request("sessions.create", { key: "agent:main:probe-m3-labeled", label: "probe 标签会话" });
  console.log(`  create(+label) → ${outline(c)}`);

  // ④ 清理探针会话
  console.log("== EXTRA: sessions.delete 清理 ==");
  for (const k of ["agent:main:probe-m3", "agent:main:probe-m3-labeled", "agent:main:probe-m3-src"]) {
    const r = await request("sessions.delete", { key: k });
    console.log(`  delete ${k} → ${outline(r).slice(0, 160)}`);
  }
  console.log("== extra done ==");
  setTimeout(() => process.exit(0), 800);
}
