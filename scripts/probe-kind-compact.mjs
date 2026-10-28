#!/usr/bin/env node
// M4 P0 探针：①sessions.list 的 kind/surface 分布（Classifier 判据校准）
// ②sessions.compact payload/返回实测（scratch 会话上做，不碰主会话）
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
setTimeout(() => { console.log("WATCHDOG exit"); process.exit(2); }, 60000);
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

  // ① kind/surface 分布（翻 3 页，够覆盖形态）
  const surfaces = new Map(); const kinds = new Map(); const channels = new Map();
  for (let offset = 0; offset < 3; offset += 1) {
    const res = await request("sessions.list", { sortBy: "activity", limit: 100, offset });
    const rows = (res.payload ?? res.result ?? {}).sessions ?? [];
    for (const r of rows) {
      const key = String(r.key || "");
      const surface = key.startsWith("agent:") ? key.split(":").slice(2).join(":") : key;
      const bucket = surface.includes(":") ? surface.split(":")[0] : surface;
      const sb = surfaces.get(bucket) ?? { count: 0, sample: [] };
      sb.count += 1; if (sb.sample.length < 2) sb.sample.push(surface.slice(0, 60));
      surfaces.set(bucket, sb);
      const k = String(r.kind ?? "<null>");
      kinds.set(k, (kinds.get(k) ?? 0) + 1);
      const ch = String(r.channel ?? "<null>");
      channels.set(ch, (channels.get(ch) ?? 0) + 1);
    }
    if (!((res.payload ?? res.result ?? {}).hasMore)) break;
  }
  console.log("== surface buckets ==");
  for (const [k, v] of [...surfaces.entries()].sort((a, b) => b[1].count - a[1].count)) console.log(k, v.count, JSON.stringify(v.sample));
  console.log("== kind values ==", JSON.stringify([...kinds.entries()]));
  console.log("== channel values ==", JSON.stringify([...channels.entries()]));

  // ② compact 实测：scratch 会话（名含 cprobe，方便事后清理）
  const ts = Date.now();
  const scratch = `agent:main:ar-cprobe-${ts.toString(36)}`;
  const created = await request("sessions.create", { key: scratch, label: `新会话 cprobe ${ts}` });
  console.log("create:", JSON.stringify(created.payload ?? created.error ?? created).slice(0, 300));
  const sent = await request("sessions.send", { sessionKey: scratch, message: "探针消息：请只回复 OK" });
  console.log("send:", JSON.stringify(sent.payload ? { ok: true } : sent.error ?? sent).slice(0, 300));
  await new Promise((r) => setTimeout(r, 3000)); // 等 run 结束
  const compact = await request("sessions.compact", { sessionKey: scratch });
  console.log("compact:", JSON.stringify(compact.payload ?? compact.error ?? compact).slice(0, 600));
  const hist = await request("chat.history", { sessionKey: scratch, limit: 20 });
  const entries = (hist.payload ?? hist.result ?? {}).entries ?? (hist.payload ?? hist.result ?? {}).messages ?? [];
  console.log("history after compact:", JSON.stringify(entries.map((e) => ({ role: e.role ?? e.message?.role, type: e.type, preview: JSON.stringify(e.message?.content ?? e.content ?? "").slice(0, 80) })))).slice?.(0, 1200) ?? console.log("history after compact:", JSON.stringify(entries).slice(0, 1200));
  process.exit(0);
});
