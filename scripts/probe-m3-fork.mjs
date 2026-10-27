#!/usr/bin/env node
// M3 探针之三：sessions.fork / branches.* 正确参数形态（分支原语普通 scope 可用性的最终确认）
// 用法：set -a; source .env; set +a; node scripts/probe-m3-fork.mjs
import { randomUUID, generateKeyPairSync, sign as edSign, createPrivateKey, createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const URL_ = process.env.OPENCLAW_GATEWAY_URL || "ws://127.0.0.1:18789";
const TOKEN = process.env.OPENCLAW_API_KEY;
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
const outline = (r) => r.ok ? JSON.stringify(r.payload).slice(0, 500) : `ERR ${r.error?.code}: ${r.error?.message?.slice(0, 220)}${r.error?.details ? " details=" + JSON.stringify(r.error.details).slice(0, 200) : ""}`;

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

async function main() {
  // 用既有真实会话做只读探查（branches.list 不改状态）
  const target = "agent:main:main";
  console.log("== branches.list 参数形态 ==");
  console.log(`  {sessionKey} → ${outline(await request("sessions.branches.list", { sessionKey: target }))}`);

  console.log("== describe/preview（fork 目标锚点侦察） ==");
  const d = await request("sessions.describe", { key: target });
  console.log(`  describe → ${outline(d)}`);

  console.log("== fork 参数形态 ==");
  // fork 大概率需要锚定到某条消息/turn；先试 sessionKey 形态
  console.log(`  fork {sessionKey} → ${outline(await request("sessions.fork", { sessionKey: target }))}`);
  const hist = await request("chat.history", { sessionKey: target, limit: 3 });
  const lastUser = hist.ok ? [...(hist.payload.messages || [])].reverse().find((m) => m.role === "user") : null;
  console.log(`  锚点: id=${lastUser?.id ?? "(无)"} idempotencyKey=${lastUser?.idempotencyKey ?? "-"}`);
  if (lastUser?.id) {
    console.log(`  fork {sessionKey, messageId} → ${outline(await request("sessions.fork", { sessionKey: target, messageId: lastUser.id }))}`);
    console.log(`  fork {sessionKey, entryId} → ${outline(await request("sessions.fork", { sessionKey: target, entryId: lastUser.id }))}`);
  }
  // 清理：fork 若成功会产生新会话，列出最新 keys 以便识别
  const list = await request("sessions.list", { sortBy: "activity", limit: 10 });
  if (list.ok) {
    console.log("== 最新 10 会话 keys ==");
    for (const s of list.payload.sessions || []) console.log(`  ${s.key} label=${s.label ?? "-"} updated=${s.updatedAt}`);
  }
  console.log("== fork probe done ==");
  setTimeout(() => process.exit(0), 800);
}
ws.addEventListener("open", () => console.log(`## WS OPEN ${URL_}`));
