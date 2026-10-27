#!/usr/bin/env node
// M3.4 fork 实测：chat.history 取 __openclaw.id → sessions.fork {sessionKey, entryId} → 新会话 key
import { randomUUID, createHash, sign as edSign, createPrivateKey } from "node:crypto";
import { readFileSync } from "node:fs";
const TOKEN = process.env.OPENCLAW_API_KEY;
const CLIENT = { id: "openclaw-macos", version: "0.1.0", platform: "macos", deviceFamily: "Mac", mode: "ui" };
const { privPem, pubB64u } = JSON.parse(readFileSync(".device-identity.json", "utf8"));
const deviceId = createHash("sha256").update(Buffer.from(pubB64u, "base64url")).digest("hex");
const b64u = (b) => Buffer.from(b).toString("base64url");
const ws = new WebSocket(process.env.OPENCLAW_GATEWAY_URL);
let seq = 1; const pending = new Map();
const request = (method, params) => { const id = `${seq++}:${randomUUID()}`; ws.send(JSON.stringify({type:"req",id,method,params})); return new Promise((r)=>pending.set(id,r)); };
ws.addEventListener("message", async (ev) => {
  const f = JSON.parse(ev.data);
  if (f.type === "event" && f.event === "connect.challenge") {
    const payload = ["v3", deviceId, CLIENT.id, CLIENT.mode, "operator", "operator.read,operator.write", String(f.payload.ts), TOKEN, f.payload.nonce, "macos", "mac"].join("|");
    const params = { minProtocol:4, maxProtocol:4, client:{...CLIENT, instanceId:randomUUID()}, caps:[], auth:{token:TOKEN}, role:"operator", scopes:["operator.read","operator.write"],
      device:{ id:deviceId, publicKey:pubB64u, signature:b64u(edSign(null, Buffer.from(payload,"utf8"), createPrivateKey(privPem))), signedAt:f.payload.ts, nonce:f.payload.nonce } };
    const hello = await request("connect", params);
    if (!hello.ok) { console.log("REJECTED", JSON.stringify(hello.error)); process.exit(1); }
    const srcKey = "agent:main:probe-m3-src";
    const hist = await request("chat.history", { sessionKey: srcKey, limit: 10 });
    const msgs = (hist.payload.messages || []).filter((m) => (m.__openclaw || {}).id);
    const anchor = msgs[msgs.length - 1];
    console.log("锚点 entryId:", anchor?.__openclaw?.id, "role:", anchor?.role);
    const fork = await request("sessions.fork", { sessionKey: srcKey, entryId: anchor.__openclaw.id });
    if (fork.ok) {
      const p = fork.payload;
      console.log("fork OK:", JSON.stringify(p).slice(0, 300));
      const newKey = p.key || p.sessionKey || (p.entry || {}).key;
      console.log("新会话 key:", newKey);
      if (newKey) {
        const h2 = await request("chat.history", { sessionKey: newKey, limit: 5 });
        console.log("新会话消息数:", h2.ok ? h2.payload.totalMessages : "ERR", "（应 ≤ 源会话，锚点截断）");
      }
    } else {
      console.log("fork ERR:", fork.error?.code, fork.error?.message?.slice(0, 200));
    }
    process.exit(0);
  }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); }
});
