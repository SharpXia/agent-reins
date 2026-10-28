#!/usr/bin/env node
// M3.6 UX 目验驱动：向 agent:main:main 发一条带思考+exec 的指令（复用 probe-m36-search.mjs 的鉴权骨架）
import { randomUUID, sign as edSign, createPrivateKey, createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
const URL_ = process.env.OPENCLAW_GATE_URL || process.env.OPENCLAW_GATEWAY_URL || "ws://127.0.0.1:18789";
const TOKEN = process.env.OPENCLAW_API_KEY;
const CLIENT = { id: "openclaw-macos", version: "0.1.0", platform: "macos", deviceFamily: "Mac", mode: "ui" };
const SCOPES = ["operator.read", "operator.write"];
const b64u = (b) => Buffer.from(b).toString("base64url");
const keyFile = join(dirname(fileURLToPath(import.meta.url)), "..", ".device-identity.json");
const { privPem, pubB64u } = JSON.parse(readFileSync(keyFile, "utf8"));
const deviceId = createHash("sha256").update(Buffer.from(pubB64u, "base64url")).digest("hex");
const ws = new WebSocket(URL_);
let seq = 1; const pending = new Map();
const request = (method, params) => { const id = `${seq++}:${randomUUID()}`; ws.send(JSON.stringify({ type: "req", id, method, params })); return new Promise((r) => pending.set(id, r)); };
setTimeout(() => { console.log("WATCHDOG exit"); process.exit(2); }, 40000);
ws.addEventListener("open", () => console.log("open"));
ws.addEventListener("error", (e) => console.log("ws-err", e.message || String(e)));
ws.addEventListener("message", async (ev) => {
  let f;
  try { f = JSON.parse(ev.data); } catch { console.log("non-json frame:", String(ev.data).slice(0, 80)); return; }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); return; }
  if (f.type !== "event" || f.event !== "connect.challenge") return;
    const params = { minProtocol: 4, maxProtocol: 4, client: { ...CLIENT, instanceId: randomUUID() }, caps: [], auth: { token: TOKEN }, role: "operator", scopes: SCOPES };
    const pl = ["v3", deviceId, CLIENT.id, CLIENT.mode, "operator", SCOPES.join(","), String(f.payload.ts), TOKEN, f.payload.nonce, CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
    params.device = { id: deviceId, publicKey: pubB64u, signature: b64u(edSign(null, Buffer.from(pl, "utf8"), createPrivateKey(privPem))), signedAt: f.payload.ts, nonce: f.payload.nonce };
    const hello = await request("connect", params);
    if (!hello.ok) { console.log("REJECTED", JSON.stringify(hello.error)); process.exit(1); }
    const res = await request("sessions.send", { key: process.env.OPENCLAW_PROBE_SESSION || "agent:main:main", message: "agentreins-m36-ux 目验：请先想一想再用 exec 执行 `echo m36-ok` 并简短确认，然后结束。", timeoutMs: 180000 });
    console.log("send:", res.ok ? "ok" : JSON.stringify(res.error));
    setTimeout(() => process.exit(0), 4000);
});
