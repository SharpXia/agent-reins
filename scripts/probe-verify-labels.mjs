#!/usr/bin/env node
// 验证修复后的 label 方案：app 新格式（新会话 MM-dd HH:mm:ss）连建两单 → 应都 ok；
// 同 label 重放第二单 → 应 INVALID label already in use（证明同秒兜底分支必要）。
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
const labelOf = (ts) => { // 与 AppModel.newSessionLabel 同构（本地时区 MM-dd HH:mm:ss）
  const d = new Date(ts + new Date().getTimezoneOffset() * 60000 * -1);
  const p = (n) => String(n).padStart(2, "0");
  return `新会话 ${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
};
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
    const ts1 = Date.now();
    const r1 = await request("sessions.create", { key: `agent:main:ar-${ts1.toString(36)}`, label: labelOf(ts1) });
    console.log("[单1 新格式]", labelOf(ts1), "→", r1.ok ? "ok" : JSON.stringify(r.error ?? r1.error));
    const ts2 = Date.now();
    const r2 = await request("sessions.create", { key: `agent:main:ar-${ts2.toString(36)}`, label: labelOf(ts2) });
    console.log("[单2 新格式]", labelOf(ts2), "→", r2.ok ? "ok" : JSON.stringify(r2.error));
    const r3 = await request("sessions.create", { key: `agent:main:ar-${(ts2 + 1).toString(36)}`, label: labelOf(ts2) });
    console.log("[单3 同 label 重放]", labelOf(ts2), "→", r3.ok ? "ok(意外)" : JSON.stringify(r3.error));
    process.exit(0);
});
