#!/usr/bin/env node
// OpenClaw 网关协议探针（M1 spike 工具，实测于 OpenClaw 2026.9.9 / protocol 4）
// 用法见 README 或 --help；凭据一律走环境变量，绝不接受命令行传 token。
//
//   set -a; source .env; set +a
//   node scripts/protocol-probe.mjs                 # 握手 + sessions.list + status
//   node scripts/protocol-probe.mjs pair            # 生成设备身份并首次配对（写 keychain-dev.json）
//   node scripts/protocol-probe.mjs send --key agent:main:main --text "hi"
//   node scripts/protocol-probe.mjs history --key agent:main:main
//   node scripts/protocol-probe.mjs probe --url wss://gw.example.com:443   # 地址可用 --url 覆盖
//
// 鉴权路径：纯 token 的第三方连接 scope 会被清空（详见 docs/protocol-notes.md §3.4），
// pair 一次之后所有命令自动带设备签名，拥有 operator.read/write。
import { randomUUID, generateKeyPairSync, sign as edSign, createPrivateKey, createHash } from "node:crypto";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

// 命令行参数先于环境变量解析（--url 非机密；token 仍只认 OPENCLAW_API_KEY 环境变量）
const cliArgs = process.argv.slice(2);
const arg = (name) => { const i = cliArgs.indexOf(`--${name}`); return i > 0 ? cliArgs[i + 1] : undefined; };

const URL_ = arg("url") || process.env.OPENCLAW_GATEWAY_URL || "ws://127.0.0.1:18789";
const TOKEN = process.env.OPENCLAW_API_KEY;
if (!TOKEN) { console.error("missing OPENCLAW_API_KEY"); process.exit(1); }

const cmd = cliArgs[0] || "probe";

const CLIENT = {
  id: process.env.OC_CLIENT_ID || "openclaw-ios",
  version: "0.1.0",
  platform: process.env.OC_PLATFORM || "macos",
  deviceFamily: process.env.OC_DEVICE_FAMILY || "Mac",
  mode: "ui",
};
const ROLE = "operator";
const SCOPES = ["operator.read", "operator.write"];
const b64u = (b) => Buffer.from(b).toString("base64url");

// ---- 设备身份（pair 时生成；deviceId 必须由公钥派生）----
const keyFile = join(dirname(fileURLToPath(import.meta.url)), "..", ".device-identity.json");
function loadOrCreateIdentity() {
  if (existsSync(keyFile)) {
    const { privPem, pubB64u } = JSON.parse(readFileSync(keyFile, "utf8"));
    return { privPem, pubB64u, deviceId: deriveId(pubB64u) };
  }
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const privPem = privateKey.export({ type: "pkcs8", format: "pem" });
  const pubB64u = publicKey.export({ format: "jwk" }).x; // raw 32B base64url
  writeFileSync(keyFile, JSON.stringify({ privPem, pubB64u }, null, 2));
  return { privPem, pubB64u, deviceId: deriveId(pubB64u) };
}
const deriveId = (pubB64u) => createHash("sha256").update(Buffer.from(pubB64u, "base64url")).digest("hex");

const identity = cmd === "pair" || existsSync(keyFile) ? loadOrCreateIdentity() : null;

// ---- WS 会话 ----
const ws = new WebSocket(URL_);
let seq = 1;
const pending = new Map();
const request = (method, params) => {
  const id = `${seq++}:${randomUUID()}`;
  ws.send(JSON.stringify({ type: "req", id, method, params }));
  return new Promise((resolve) => pending.set(id, resolve));
};

ws.addEventListener("open", () => console.log(`## WS OPEN ${URL_}`));
ws.addEventListener("close", (e) => { console.log("## CLOSED", e.code, e.reason || ""); process.exit(e.code === 1000 ? 0 : 1); });
ws.addEventListener("error", (e) => console.log("## ERROR", e.message || e));

ws.addEventListener("message", async (ev) => {
  const f = JSON.parse(ev.data);
  if (f.type === "event" && f.event === "connect.challenge") {
    const params = {
      minProtocol: 4, maxProtocol: 4,
      client: { ...CLIENT, instanceId: randomUUID() },
      caps: [], auth: { token: TOKEN }, role: ROLE, scopes: SCOPES,
    };
    if (identity) {
      const payload = ["v3", identity.deviceId, CLIENT.id, CLIENT.mode, ROLE, SCOPES.join(","),
        String(f.payload.ts), TOKEN, f.payload.nonce,
        CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
      params.device = {
        id: identity.deviceId, publicKey: identity.pubB64u,
        signature: b64u(edSign(null, Buffer.from(payload, "utf8"), createPrivateKey(identity.privPem))),
        signedAt: f.payload.ts, nonce: f.payload.nonce,
      };
    }
    const hello = await request("connect", params);
    if (!hello.ok) { console.log("## connect REJECTED:", JSON.stringify(hello.error)); process.exit(1); }
    console.log("## hello-ok auth =", JSON.stringify(hello.payload.auth));
    await dispatch();
    return;
  }
  if (f.type === "event" && cmd === "probe") return; // probe 模式不跟进事件
  if (f.type === "event" && f.event === "agent" && ["item", "message", "lifecycle", "usage"].includes(f.payload?.stream)) {
    console.log(`  [agent:${f.payload.stream}]`, JSON.stringify(f.payload.data).slice(0, 200));
    return;
  }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); }
});

async function dispatch() {
  switch (cmd) {
    case "pair": {
      console.log("## paired. deviceId =", identity.deviceId);
      console.log("## 检查配对表: openclaw devices list");
      break;
    }
    case "probe": {
      const list = await request("sessions.list", { sortBy: "activity" });
      console.log("## sessions.list:", list.ok ? `count=${list.payload.count} total=${list.payload.totalCount}` : JSON.stringify(list.error));
      const st = await request("status", {});
      console.log("## status:", st.ok ? `runtime=${st.payload.runtimeVersion} heartbeat=${st.payload.heartbeat?.defaultAgentId}` : JSON.stringify(st.error));
      break;
    }
    case "send": {
      const key = arg("key"), text = arg("text");
      if (!key || !text) { console.error("need --key --text"); process.exit(1); }
      const r = await request("sessions.send", { key, message: text, timeoutMs: 60000 });
      console.log("## send:", JSON.stringify(r.payload ?? r.error));
      console.log("## （两阶段受理，回复经 agent 事件流推送，观察窗口 60s）");
      await new Promise((r2) => setTimeout(r2, 60000));
      break;
    }
    case "history": {
      const key = arg("key");
      const r = await request("chat.history", { sessionKey: key, limit: 8 });
      if (!r.ok) { console.log("## FAIL:", JSON.stringify(r.error)); break; }
      console.log(`## total=${r.payload.totalMessages}`);
      for (const m of r.payload.messages || []) {
        const t = typeof m.content === "string" ? m.content
          : (m.content || []).map((c) => c.text ?? `[${c.type}]`).join(" ");
        console.log(`  [${m.role}] ${t.replace(/\n/g, "⏎").slice(0, 140)}`);
      }
      break;
    }
    default:
      console.error(`unknown command: ${cmd}（probe | pair | send | history）`);
  }
  setTimeout(() => process.exit(0), cmd === "history" || cmd === "probe" || cmd === "pair" ? 500 : 1000);
}
