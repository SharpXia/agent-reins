#!/usr/bin/env node
// 二分定位「创建新会话 INVALID_REQUEST」：app 实发参数逐项拆（label 中文/ASCII/无、key 形态）
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
const request = (method, params) => { const id = `${seq++}:${randomUUID()}`; ws.send(JSON.stringify({ type: "req", id, method, params })); return new Promise((r) => pending.set(id, r)); };
setTimeout(() => { console.log("WATCHDOG exit"); process.exit(2); }, 30000);
ws.addEventListener("message", async (ev) => {
  let f;
  try { f = JSON.parse(ev.data); } catch { return; }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); return; }
  if (f.type !== "event" || f.event !== "connect.challenge") return;
    const params = { minProtocol: 4, maxProtocol: 4, client: { ...CLIENT, instanceId: randomUUID() }, caps: [], auth: { token: TOKEN }, role: "operator", scopes: SCOPES };
    const pl = ["v3", deviceId, CLIENT.id, CLIENT.mode, "operator", SCOPES.join(","), String(f.payload.ts), TOKEN, f.payload.nonce, CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
    params.device = { id: deviceId, publicKey: pubB64u, signature: b64u(edSign(null, Buffer.from(pl, "utf8"), createPrivateKey(privPem))), signedAt: f.payload.ts, nonce: f.payload.nonce };
    const hello = await request("connect", params);
    if (!hello.ok) { console.log("REJECTED", JSON.stringify(hello.error)); process.exit(1); }
    console.log("hello-ok");
    const cases = [
      ["重复中文 label（app 撞车形态）", { key: "agent:main:ar-m3bisect1", label: "新会话" }],
      ["无 label", { key: "agent:main:ar-m3bisect2" }],
      ["ASCII label", { key: "agent:main:ar-m3bisect3", label: "probe" }],
      ["无 label 再来一发（连续创建主路径）", { key: "agent:main:ar-m3bisect4" }],
    ];
    for (const [name, p] of cases) {
      const r = await request("sessions.create", p);
      console.log(`[${name}] ${JSON.stringify(p)} →`, r.ok ? `ok key=${r.result?.key ?? "?"}` : JSON.stringify(r.error).slice(0, 300));
    }
    process.exit(0);
});
