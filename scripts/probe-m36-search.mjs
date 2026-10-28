#!/usr/bin/env node
// M3.6 探针：sessions.search 参数/返回形态（服务端列表搜索增强的可行性确认）
// 用法：set -a; source .env; set +a; node scripts/probe-m36-search.mjs
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
const outline = (r) => r.ok ? JSON.stringify(r.payload).slice(0, 900) : `ERR ${r.error?.code}: ${r.error?.message?.slice(0, 220)}${r.error?.details ? " details=" + JSON.stringify(r.error.details).slice(0, 300) : ""}`;

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
  const q = "main";
  console.log("== sessions.search 形态探测 ==");
  console.log(`A {query} → ${outline(await request("sessions.search", { query: q }))}`);
  console.log(`B {query,limit} → ${outline(await request("sessions.search", { query: q, limit: 5 }))}`);
  console.log(`C {q} → ${outline(await request("sessions.search", { q }))}`);
  console.log(`D {term} → ${outline(await request("sessions.search", { term: q }))}`);
  console.log(`E {search} → ${outline(await request("sessions.search", { search: q }))}`);
  console.log(`F 无参 → ${outline(await request("sessions.search", {}))}`);
  // 连消息正文匹配试探（若 A 命中会话级，试 body 全文）
  console.log(`G {query 消息词} → ${outline(await request("sessions.search", { query: "hello" }))}`);
  process.exit(0);
}
