#!/usr/bin/env node
// 一次性取证（SW 真机反馈二轮①②）：mini-x 的 sessions 行（网关侧 unread）+
// 各主线会话 history 条目顺序（role/type/ts），验证 turn 折叠的输入形态。
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
setTimeout(() => { console.log("WATCHDOG"); process.exit(2); }, 25000);
ws.addEventListener("message", async (ev) => {
  let f; try { f = JSON.parse(ev.data); } catch { return; }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); return; }
  if (f.type !== "event" || f.event !== "connect.challenge") return;
  const params = { minProtocol: 4, maxProtocol: 4, client: { ...CLIENT, instanceId: randomUUID() }, caps: [], auth: { token: TOKEN }, role: "operator", scopes: SCOPES };
  const pl = ["v3", deviceId, CLIENT.id, CLIENT.mode, "operator", SCOPES.join(","), String(f.payload.ts), TOKEN, f.payload.nonce, CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
  params.device = { id: deviceId, publicKey: pubB64u, signature: b64u(edSign(null, Buffer.from(pl, "utf8"), createPrivateKey(privPem))), signedAt: f.payload.ts, nonce: f.payload.nonce };
  const hello = await request("connect", params);
  if (!hello.ok) { console.log("REJECTED", JSON.stringify(hello.error ?? {}).slice(0, 200)); process.exit(1); }
  const list = await request("sessions.list", { sortBy: "activity", limit: 100 });
  const body = list.payload ?? list.result ?? {};
  const rows = (body.sessions ?? body.rows ?? []).filter((r) => (r.key ?? "").startsWith("agent:mini-x"));
  for (const r of rows) {
    console.log("ROW", JSON.stringify({ key: r.key, label: r.label, unread: r.unread, lastActivityAt: r.lastActivityAt ?? r.updatedAt }));
  }
  for (const r of rows.slice(0, 3)) {
    const h = await request("chat.history", { sessionKey: r.key, limit: 24 });
    const hb = h.payload ?? h.result ?? {};
    const entries = hb.entries ?? hb.messages ?? [];
    console.log("HIST", r.key, "entries:", entries.length);
    for (const e of entries.slice(-24)) {
      const m = e.message ?? e;
      const text = JSON.stringify(m.content ?? "").slice(0, 40);
      console.log("  ", JSON.stringify({ type: e.type, role: m.role, ts: m.timestamp ?? e.timestamp, preview: text }));
    }
  }
  process.exit(0);
});
