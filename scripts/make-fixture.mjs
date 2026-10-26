#!/usr/bin/env node
// 从本地原始录制（scripts/fixtures/agent-events-capture.json，不入库）生成
// shared/src/commonTest/kotlin/dev/sharpxia/agentreins/openclaw/FixtureData.kt。
// 脱敏规则：本测试回合（AgentReins 自发消息）文本全保留；另一会话广播帧只保留结构，
// 文本字段替换为占位符（含 thinking 快照重放的 3 帧代表样本）。
// 用法：node scripts/make-fixture.mjs
import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const raw = JSON.parse(readFileSync(join(root, "scripts/fixtures/agent-events-capture.json"), "utf8"));
const myRun = raw.sendResponse.runId;
// mySessionKey 必须取自本回合帧（捕获开始时其它会话可能已在广播，首帧不可靠）
const myKey = raw.frames.find((f) => f.event === "agent" && f.payload?.runId === myRun)?.payload?.sessionKey;

const REDACT = "<redacted-for-fixture>";
let thinkingKept = 0;

function sanitizeData(data) {
  const out = { ...data };
  for (const k of ["text", "progressText", "title", "meta", "output"]) {
    if (typeof out[k] === "string") out[k] = REDACT;
  }
  // lifecycle end 帧的 terminalReply / terminalReceipt 携带他回合终局文本，一并占位
  if (out.terminalReply && typeof out.terminalReply === "object") {
    out.terminalReply = { ...out.terminalReply, text: REDACT };
  }
  return out;
}

const frames = raw.frames.filter((f) => f.type === "event" && f.event === "agent").map((f) => {
  const p = f.payload ?? {};
  if (p.runId === myRun) return f; // 本测试回合：全量保留
  // 其它会话广播：thinking 只留首/中/末 3 帧代表重放语义，其余全丢；文本一律占位
  if (p.stream === "thinking") {
    thinkingKept += 1;
    if (![1, Math.ceil(878 / 2), 878].includes(thinkingKept)) return null;
  }
  return { type: "event", event: f.event, payload: { ...p, data: sanitizeData(p.data ?? {}) } };
}).filter(Boolean);

// chat.history：保留映射器所需字段，剥掉大对象 __openclaw（只留 id）并脱敏发送者
const history = {
  messages: raw.historyAfter.messages.map((m) => {
    const clean = {};
    for (const k of ["role", "content", "timestamp", "toolCallId", "toolName", "isError", "idempotencyKey"]) {
      if (m[k] !== undefined) clean[k] = m[k];
    }
    if (m.__openclaw?.id) clean.id = m.__openclaw.id;
    return clean;
  }),
  totalMessages: raw.historyAfter.totalMessages,
};

const body = {
  fixture: {
    capturedAt: raw.capturedAt, gatewayVersion: raw.gatewayVersion, myRunId: myRun,
    mySessionKey: myKey, frames, history,
  },
};

const json = JSON.stringify(body, null, 1)
  // 本回合帧全量保留，但 home 路径属个人环境信息，一律改写为占位用户
  .replaceAll(/\/Users\/[^/"]+/g, "/Users/you");
if (json.includes('"""') || json.includes("${")) {
  console.error("fixture JSON contains raw-string-unsafe sequence");
  process.exit(1);
}

const kt = `package dev.sharpxia.agentreins.openclaw

/**
 * M2 单测 fixture：真实网关录制的 agent 事件帧序列与 chat.history 快照
 * （scripts/make-fixture.mjs 生成；源录制不入库）。本回合（main 会话 exec 工具）
 * 文本全保留，另一会话（coding subagent 广播）只保留帧结构、文本占位，thinking 留 3 帧
 * 代表快照重放。字段 shape 未经手改——解析器测试以此为协议事实。
 */
internal object FixtureData {
    val agentEventFramesJson: String = """${json.replace('"""', "")}"""
}
`;
const out = join(root, "shared/src/commonTest/kotlin/dev/sharpxia/agentreins/openclaw/FixtureData.kt");
writeFileSync(out, kt);
console.log(`frames=${frames.length} historyMessages=${history.messages.length} -> ${out}`);
