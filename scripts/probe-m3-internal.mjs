// M3.4 探针：__openclaw 内嵌字段结构（fork 锚点）+ sessions.abort 参数形态
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
    const hist = await request("chat.history", { sessionKey: "agent:main:main", limit: 2 });
    for (const m of (hist.payload.messages||[]).slice(0,2)) {
      console.log("role:", m.role);
      console.log("  __openclaw:", JSON.stringify(m.__openclaw).slice(0, 400));
    }
    // abort 参数形态：对空闲会话试发（期望 INVALID_REQUEST 报参数名，或 NO_ACTIVE_RUN 语义错）
    const a = await request("sessions.abort", {});
    console.log("abort({}) →", a.ok ? JSON.stringify(a.payload).slice(0,200) : `ERR ${a.error?.code}: ${a.error?.message?.slice(0,200)}`);
    process.exit(0);
  }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); }
});
