#!/usr/bin/env node
// history 消息字段名侦察（fork 锚点 entryId 的来源）
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
    const hist = await request("chat.history", { sessionKey: "agent:main:main", limit: 4 });
    const msgs = hist.payload.messages || [];
    console.log("history 消息字段 keys（逐条）:");
    for (const m of msgs.slice(0, 4)) console.log(" ", Object.keys(m).join(","));
    const br = await request("sessions.branches.list", { sessionKey: "agent:main:main" });
    const active = (br.payload.branches||[]).find(b=>b.active);
    console.log("active branch leafEntryId:", active?.leafEntryId);
    process.exit(0);
  }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); }
});
