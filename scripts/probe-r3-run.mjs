#!/usr/bin/env node
// R3 目验：新建 scratch 会话 → 等 lifecycle 就绪 → 发一条会触发流式回复的消息
import { randomUUID, sign as edSign, createPrivateKey, createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
const URL_ = process.env.OPENCLAW_GATE_URL || "ws://127.0.0.1:18789";
const TOKEN = process.env.OPENCLAW_API_KEY;
const CLIENT = { id: "openclaw-macos", version: "0.1.0", platform: "macos", deviceFamily: "Mac", mode: "ui" };
const SCOPES = ["operator.read", "operator.write"];
const b64u = (b) => Buffer.from(b).toString("base64url");
const keyFile = join(dirname(fileURLToPath(import.meta.url)), "..", ".device-identity.json");
const { privPem, pubB64u } = JSON.parse(readFileSync(keyFile, "utf8"));
const deviceId = createHash("sha256").update(Buffer.from(pubB64u, "base64url")).digest("hex");
const ws = new WebSocket(URL_);
let seq = 1; const pending = new Map();
const request = (m, p) => { const id = `${seq++}:${randomUUID()}`; ws.send(JSON.stringify({ type: "req", id, method: m, params: p })); return new Promise((r) => pending.set(id, r)); };
setTimeout(() => { console.log("WATCHDOG"); process.exit(2); }, 40000);
ws.addEventListener("message", async (ev) => {
  let f; try { f = JSON.parse(ev.data); } catch { return; }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); return; }
  if (f.type !== "event" || f.event !== "connect.challenge") return;
  const params = { minProtocol: 4, maxProtocol: 4, client: { ...CLIENT, instanceId: randomUUID() }, caps: [], auth: { token: TOKEN }, role: "operator", scopes: SCOPES };
  const pl = ["v3", deviceId, CLIENT.id, CLIENT.mode, "operator", SCOPES.join(","), String(f.payload.ts), TOKEN, f.payload.nonce, CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
  params.device = { id: deviceId, publicKey: pubB64u, signature: b64u(edSign(null, Buffer.from(pl, "utf8"), createPrivateKey(privPem))), signedAt: f.payload.ts, nonce: f.payload.nonce };
  const hello = await request("connect", params);
  if (!hello.ok) { console.log("REJECTED"); process.exit(1); }
  const key = `agent:main:ar-r3probe-${Date.now().toString(36)}`;
  const cr = await request("sessions.create", { key, label: `新会话 r3probe ${new Date().toLocaleTimeString()}` });
  console.log("create:", JSON.stringify(cr.payload ? { ok: true, key } : cr.error).slice(0, 150));
  await new Promise((r) => setTimeout(r, 1500));
  const res = await request("sessions.send", { sessionKey: key, message: "请用三五句话介绍一下 Compose Multiplatform 的 iOS 支持现状，简单即可。" });
  console.log("send:", JSON.stringify(res.payload ? { ok: true } : res.error).slice(0, 150));
  process.exit(res.payload ? 0 : 1);
});
