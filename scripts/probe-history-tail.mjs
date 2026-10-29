#!/usr/bin/env node
// 检查 cinema:main 的 /status 是否被网关受理并回复
import { randomUUID, sign as edSign, createPrivateKey, createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
const URL_ = process.env.OPENCLAW_GATE_URL || "ws://127.0.0.1:18789";
const TOKEN = process.env.OPENCLAW_API_KEY;
const CLIENT = { id: "openclaw-macos", version: "0.1.0", platform: "macos", deviceFamily: "Mac", mode: "ui" };
const SCOPES = ["operator.read"];
const b64u = (b) => Buffer.from(b).toString("base64url");
const keyFile = join(dirname(fileURLToPath(import.meta.url)), "..", ".device-identity.json");
const { privPem, pubB64u } = JSON.parse(readFileSync(keyFile, "utf8"));
const deviceId = createHash("sha256").update(Buffer.from(pubB64u, "base64url")).digest("hex");
const ws = new WebSocket(URL_);
let seq = 1; const pending = new Map();
const request = (m, p) => { const id = `${seq++}:${randomUUID()}`; ws.send(JSON.stringify({ type: "req", id, method: m, params: p })); return new Promise((r) => pending.set(id, r)); };
setTimeout(() => { console.log("WATCHDOG"); process.exit(2); }, 20000);
ws.addEventListener("message", async (ev) => {
  let f; try { f = JSON.parse(ev.data); } catch { return; }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); return; }
  if (f.type !== "event" || f.event !== "connect.challenge") return;
  const params = { minProtocol: 4, maxProtocol: 4, client: { ...CLIENT, instanceId: randomUUID() }, caps: [], auth: { token: TOKEN }, role: "operator", scopes: SCOPES };
  const pl = ["v3", deviceId, CLIENT.id, CLIENT.mode, "operator", SCOPES.join(","), String(f.payload.ts), TOKEN, f.payload.nonce, CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
  params.device = { id: deviceId, publicKey: pubB64u, signature: b64u(edSign(null, Buffer.from(pl, "utf8"), createPrivateKey(privPem))), signedAt: f.payload.ts, nonce: f.payload.nonce };
  const hello = await request("connect", params);
  if (!hello.ok) { console.log("REJECTED", JSON.stringify(hello.error ?? {}).slice(0, 200)); process.exit(1); }
  const res = await request("chat.history", { sessionKey: process.argv[2] ?? "agent:cinema:main", limit: 10 });
  const body = res.payload ?? res.result ?? {};
  const entries = body.entries ?? body.messages ?? [];
  for (const e of entries.slice(-6)) {
    const m = e.message ?? e;
    console.log(JSON.stringify({ role: m.role, type: e.type, ts: m.timestamp ?? e.timestamp, preview: JSON.stringify(m.content ?? "").slice(0, 90) }));
  }
  if (!entries.length) console.log("(empty)");
  process.exit(0);
});
