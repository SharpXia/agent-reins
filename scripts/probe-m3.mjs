#!/usr/bin/env node
// M3 Grok UX 复刻开工探针（四项，2026-10-27）。结论手记进 docs/protocol-notes.md §7.3。
// 用法：set -a; source .env; set +a; node scripts/probe-m3.mjs [--skip-send]
// ① 协议能否新建/重置会话键（决定「从头开始」与消息分支近似是否成立）
// ② sessions.send 是否支持附件参数（决定 DraftAttachments 是否做）
// ③ exec.approval.* 方法族与 session.approval 事件实测（决定审批卡完成度）
// ④ 同一 idempotencyKey 重发，网关是否去重（决定 outbox 重发防重的客户端兜底强度）
// 凭据只走环境变量；探针对测试会话操作，不触碰真实会话历史。
import { randomUUID, generateKeyPairSync, sign as edSign, createPrivateKey, createHash } from "node:crypto";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const cliArgs = process.argv.slice(2);
const arg = (name) => { const i = cliArgs.indexOf(`--${name}`); return i > 0 ? cliArgs[i + 1] : undefined; };
const URL_ = arg("url") || process.env.OPENCLAW_GATEWAY_URL || "ws://127.0.0.1:18789";
const TOKEN = process.env.OPENCLAW_API_KEY;
if (!TOKEN) { console.error("missing OPENCLAW_API_KEY"); process.exit(1); }

const CLIENT = {
  id: "openclaw-macos", version: "0.1.0", platform: "macos",
  deviceFamily: "Mac", mode: "ui",
};
const ROLE = "operator";
const SCOPES = ["operator.read", "operator.write"];
const b64u = (b) => Buffer.from(b).toString("base64url");

const keyFile = join(dirname(fileURLToPath(import.meta.url)), "..", ".device-identity.json");
function loadOrCreateIdentity() {
  if (existsSync(keyFile)) {
    const { privPem, pubB64u } = JSON.parse(readFileSync(keyFile, "utf8"));
    return { privPem, pubB64u, deviceId: deriveId(pubB64u) };
  }
  const { publicKey, privateKey } = generateKeyPairSync("ed25519");
  const privPem = privateKey.export({ type: "pkcs8", format: "pem" });
  const pubB64u = publicKey.export({ format: "jwk" }).x;
  writeFileSync(keyFile, JSON.stringify({ privPem, pubB64u }, null, 2));
  return { privPem, pubB64u, deviceId: deriveId(pubB64u) };
}
const deriveId = (pubB64u) => createHash("sha256").update(Buffer.from(pubB64u, "base64url")).digest("hex");

const identity = loadOrCreateIdentity();

const ws = new WebSocket(URL_);
let seq = 1;
const pending = new Map();
const request = (method, params) => {
  const id = `${seq++}:${randomUUID()}`;
  ws.send(JSON.stringify({ type: "req", id, method, params }));
  return new Promise((resolve) => pending.set(id, resolve));
};
const events = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

ws.addEventListener("open", () => console.log(`## WS OPEN ${URL_}`));
ws.addEventListener("close", (e) => console.log("## CLOSED", e.code, e.reason || ""));
ws.addEventListener("error", (e) => console.log("## ERROR", e.message || e));

ws.addEventListener("message", async (ev) => {
  const f = JSON.parse(ev.data);
  if (f.type === "event") {
    if (f.event === "connect.challenge") {
      const params = {
        minProtocol: 4, maxProtocol: 4,
        client: { ...CLIENT, instanceId: randomUUID() },
        caps: [], auth: { token: TOKEN }, role: ROLE, scopes: SCOPES,
      };
      const payload = ["v3", identity.deviceId, CLIENT.id, CLIENT.mode, ROLE, SCOPES.join(","),
        String(f.payload.ts), TOKEN, f.payload.nonce,
        CLIENT.platform.toLowerCase(), CLIENT.deviceFamily.toLowerCase()].join("|");
      params.device = {
        id: identity.deviceId, publicKey: identity.pubB64u,
        signature: b64u(edSign(null, Buffer.from(payload, "utf8"), createPrivateKey(identity.privPem))),
        signedAt: f.payload.ts, nonce: f.payload.nonce,
      };
      const hello = await request("connect", params);
      if (!hello.ok) { console.log("## connect REJECTED:", JSON.stringify(hello.error)); process.exit(1); }
      await main(hello.payload);
      return;
    }
    events.push(f);
    return;
  }
  if (f.type === "res" && pending.has(f.id)) { pending.get(f.id)(f); pending.delete(f.id); }
});

const outline = (r) => r.ok
  ? JSON.stringify(r.payload).slice(0, 300)
  : `ERR ${r.error?.code}: ${r.error?.message?.slice(0, 160)}`;

async function main(hello) {
  const granted = hello.auth?.scopes?.join(",") || "";
  console.log(`## hello-ok scopes=[${granted}] server=${hello.server?.version}`);
  const methods = hello.features?.methods || [];
  console.log(`## features.methods count=${methods.length}`);

  // ---- 方法表切片：探针①③的候选名直接从保守清单里找 ----
  const sessionsM = methods.filter((m) => m.startsWith("sessions."));
  const approvalM = methods.filter((m) => /approval|approve/i.test(m));
  const chatM = methods.filter((m) => m.startsWith("chat."));
  console.log("## sessions.* =", sessionsM.join(", "));
  console.log("## approval-ish =", approvalM.join(", "));
  console.log("## chat.* =", chatM.join(", "));

  // ============ 探针① 新建/重置会话键 ============
  console.log("\n== PROBE 1: 新建/重置会话键 ==");
  const probeKey = "agent:main:probe-m3";
  // 1a. 疑似候选方法逐个探（错误形态区分 UNKNOWN_METHOD / INVALID_REQUEST / FORBIDDEN / 其它语义错误）
  for (const m of ["sessions.reset", "sessions.create", "sessions.new", "sessions.clear", "sessions.spawn"]) {
    if (methods.includes(m)) {
      const r = await request(m, { key: probeKey });
      console.log(`  ${m} → ${outline(r)}`);
    } else {
      const r = await request(m, { key: probeKey });
      console.log(`  ${m} (不在保守清单) → ${outline(r)}`);
    }
  }
  // 1b. 直接向不存在的键 send：网关是否自动建会话
  const sendNew = await request("sessions.send", {
    key: probeKey, message: "agentreins-m3 探针：请只回复两个字：收到", timeoutMs: 45000,
  });
  console.log(`  sessions.send(新键) → ${outline(sendNew)}`);
  await sleep(8000);
  const list1 = await request("sessions.list", { sortBy: "activity", limit: 20 });
  const found = list1.ok
    ? (list1.payload.sessions || list1.payload.items || []).find((s) => s.key === probeKey)
    : null;
  console.log(`  sessions.list 含新键: ${found ? `YES kind=${found.kind} label=${found.label}` : "NO"}`);

  // ============ 探针② sessions.send 附件参数 ============
  console.log("\n== PROBE 2: sessions.send 附件参数 ==");
  const attachCandidates = [
    ["attachments", [{ path: "/tmp/probe.txt", name: "probe.txt" }]],
    ["media", ["/tmp/probe.txt"]],
    ["files", ["/tmp/probe.txt"]],
    ["content", [{ type: "text", text: "agentreins-m3 附件探针（content 数组形态）" }]],
  ];
  for (const [field, value] of attachCandidates) {
    const params = { key: probeKey, message: "agentreins-m3 附件参数形态探针", timeoutMs: 30000 };
    params[field] = value;
    const r = await request("sessions.send", params);
    console.log(`  send+${field} → ${outline(r)}`);
    await sleep(500);
  }

  // ============ 探针③ exec.approval 方法族与审批事件 ============
  console.log("\n== PROBE 3: exec.approval / session.approval ==");
  for (const m of approvalM) {
    // 只做形状探查：空参数试探错误语义（UNKNOWN / INVALID / FORBIDDEN），不真批任何请求
    const r = await request(m, {});
    console.log(`  ${m}({}) → ${outline(r)}`);
  }
  console.log("  -- 触发一次真实 exec，观察 session.approval 事件（30s 窗口）--");
  events.length = 0;
  const evSend = await request("sessions.send", {
    key: probeKey,
    message: "agentreins-m3 审批探针：请用 exec 工具执行 `echo approval-probe` 然后结束",
    timeoutMs: 45000,
  });
  console.log(`  send(exec 请求) → ${outline(evSend)}`);
  for (let i = 0; i < 30; i++) {
    await sleep(1000);
    const ap = events.filter((e) => /approval/i.test(e.event || ""));
    if (ap.length > 0) {
      for (const e of ap) console.log(`  [event:${e.event}]`, JSON.stringify(e.payload).slice(0, 400));
      break;
    }
  }
  const agentEvents = events.filter((e) => e.event === "agent").map((e) => e.payload?.stream);
  console.log(`  30s 内 agent 事件 streams: ${[...new Set(agentEvents)].join(",") || "(无)"}`);

  // ============ 探针④ idempotencyKey 重发去重 ============
  console.log("\n== PROBE 4: idempotencyKey 重发去重 ==");
  const idem = `probe-m3-${Date.now()}`;
  const r1 = await request("sessions.send", {
    key: probeKey, message: `agentreins-m3 幂等探针 A（${idem}）`, timeoutMs: 45000, idempotencyKey: idem,
  });
  console.log(`  send#1 → ${outline(r1)}`);
  await sleep(3000);
  const r2 = await request("sessions.send", {
    key: probeKey, message: `agentreins-m3 幂等探针 B 同键不同文（${idem}）`, timeoutMs: 45000, idempotencyKey: idem,
  });
  console.log(`  send#2(同key) → ${outline(r2)}`);
  await sleep(12000);
  const hist = await request("chat.history", { sessionKey: probeKey, limit: 50 });
  if (hist.ok) {
    const users = (hist.payload.messages || []).filter((m) => m.role === "user");
    const hits = users.filter((m) => (m.idempotencyKey || "").includes(idem.replace("probe-m3-", "")) || m.idempotencyKey === idem || m.idempotencyKey === `${idem}:user`);
    console.log(`  history user 条数=${users.length}，含该幂等键条数=${hits.length}`);
    console.log(`  各条 idempotencyKey: ${users.map((m) => m.idempotencyKey || "-").join(" | ")}`);
    console.log(`  → ${hits.length <= 1 ? "网关去重成立（1 条）" : "网关不去重（多条，客户端需防重）"}`);
  } else {
    console.log(`  chat.history → ${outline(hist)}`);
  }

  console.log("\n== probe done ==");
  setTimeout(() => process.exit(0), 800);
}
