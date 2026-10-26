package dev.sharpxia.agentreins.openclaw

/**
 * M2 单测 fixture：真实网关录制的 agent 事件帧序列与 chat.history 快照
 * （scripts/make-fixture.mjs 生成；源录制不入库）。本回合（main 会话 exec 工具）
 * 文本全保留，另一会话（coding subagent 广播）只保留帧结构、文本占位，thinking 留 3 帧
 * 代表快照重放。字段 shape 未经手改——解析器测试以此为协议事实。
 */
internal object FixtureData {
    val agentEventFramesJson: String = """{
 "fixture": {
  "capturedAt": "2026-10-25T14:16:01.958Z",
  "gatewayVersion": "2026.9.9",
  "myRunId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
  "mySessionKey": "agent:main:main",
  "frames": [
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "thinking",
     "data": {
      "text": "<redacted-for-fixture>",
      "delta": "500ms 进入多选；移动>10px"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 7666,
     "ts": 1792937611487,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "run_status",
     "data": {
      "phase": "preparing_workspace"
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 1,
     "ts": 1792937612748,
     "isHeartbeat": false
    },
    "seq": 21,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "run_status",
     "data": {
      "phase": "preparing_context"
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 2,
     "ts": 1792937612750,
     "isHeartbeat": false
    },
    "seq": 23,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "sessionKey": "agent:main:main",
     "sessionId": "ccdebd20-db5f-443b-ba51-d906e78a9c5a",
     "agentId": "main",
     "stream": "lifecycle",
     "data": {
      "phase": "start",
      "startedAt": 1792937613448
     },
     "seq": 3,
     "ts": 1792937613448,
     "isHeartbeat": false
    },
    "seq": 33,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "run_status",
     "data": {
      "phase": "starting_model"
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 4,
     "ts": 1792937613459,
     "isHeartbeat": false
    },
    "seq": 35,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "sessionKey": "agent:main:main",
     "sessionId": "ccdebd20-db5f-443b-ba51-d906e78a9c5a",
     "agentId": "main",
     "stream": "lifecycle",
     "data": {
      "phase": "model",
      "provider": "bigmodel",
      "model": "glm-5.3-flash"
     },
     "seq": 5,
     "ts": 1792937617920,
     "isHeartbeat": false
    },
    "seq": 91,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "sessionKey": "agent:main:main",
     "stream": "usage",
     "data": {
      "outputTokens": 81
     },
     "agentId": "main",
     "seq": 6,
     "ts": 1792937619112,
     "isHeartbeat": false
    },
    "seq": 110,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "item",
     "data": {
      "itemId": "tool:call_b1dcf84bff954ff187c79484",
      "toolCallId": "call_b1dcf84bff954ff187c79484",
      "name": "exec",
      "kind": "tool",
      "phase": "start",
      "title": "Exec AgentReins M2 fixture 回显测试",
      "status": "running",
      "meta": "AgentReins M2 fixture 回显测试",
      "commandBearing": true,
      "startedAt": 1792937619197
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 7,
     "ts": 1792937619198,
     "isHeartbeat": false
    },
    "seq": 112,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "item",
     "data": {
      "itemId": "tool:call_b1dcf84bff954ff187c79484",
      "toolCallId": "call_b1dcf84bff954ff187c79484",
      "name": "exec",
      "kind": "tool",
      "phase": "update",
      "title": "Exec AgentReins M2 fixture 回显测试",
      "status": "running",
      "meta": "AgentReins M2 fixture 回显测试",
      "commandBearing": true
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 10,
     "ts": 1792937619660,
     "isHeartbeat": false
    },
    "seq": 119,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "command_output",
     "data": {
      "itemId": "command:call_b1dcf84bff954ff187c79484",
      "phase": "delta",
      "title": "command AgentReins M2 fixture 回显测试",
      "toolCallId": "call_b1dcf84bff954ff187c79484",
      "name": "exec",
      "output": "agentreins-m2-fixture-ok",
      "status": "running"
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 11,
     "ts": 1792937619660,
     "isHeartbeat": false
    },
    "seq": 120,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "item",
     "data": {
      "itemId": "tool:call_b1dcf84bff954ff187c79484",
      "toolCallId": "call_b1dcf84bff954ff187c79484",
      "name": "exec",
      "kind": "tool",
      "phase": "end",
      "title": "Exec AgentReins M2 fixture 回显测试",
      "status": "completed",
      "meta": "AgentReins M2 fixture 回显测试",
      "commandBearing": true,
      "startedAt": 1792937619197,
      "endedAt": 1792937619682
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 13,
     "ts": 1792937619682,
     "isHeartbeat": false
    },
    "seq": 121,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "command_output",
     "data": {
      "itemId": "command:call_b1dcf84bff954ff187c79484",
      "phase": "end",
      "title": "command AgentReins M2 fixture 回显测试",
      "toolCallId": "call_b1dcf84bff954ff187c79484",
      "name": "exec",
      "output": "agentreins-m2-fixture-ok",
      "status": "completed",
      "exitCode": 0,
      "durationMs": 451,
      "cwd": "/Users/you/.openclaw/workspace"
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 14,
     "ts": 1792937619682,
     "isHeartbeat": false
    },
    "seq": 122,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "sessionKey": "agent:main:main",
     "sessionId": "ccdebd20-db5f-443b-ba51-d906e78a9c5a",
     "agentId": "main",
     "stream": "lifecycle",
     "data": {
      "phase": "model",
      "provider": "bigmodel",
      "model": "glm-5.3-flash"
     },
     "seq": 15,
     "ts": 1792937621330,
     "isHeartbeat": false
    },
    "seq": 145,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "sessionKey": "agent:main:main",
     "stream": "usage",
     "data": {
      "outputTokens": 123
     },
     "agentId": "main",
     "seq": 16,
     "ts": 1792937622082,
     "isHeartbeat": false
    },
    "seq": 155,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "stream": "assistant",
     "data": {
      "text": "`agentreins-m2-fixture-ok`",
      "delta": "`agentreins-m2-fixture-ok`",
      "itemId": "1c1a6712-170d-420d-8dbe-258ca94ba4e1:2"
     },
     "sessionKey": "agent:main:main",
     "agentId": "main",
     "seq": 17,
     "ts": 1792937622084,
     "isHeartbeat": false
    },
    "seq": 156,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "sessionKey": "agent:main:main",
     "sessionId": "ccdebd20-db5f-443b-ba51-d906e78a9c5a",
     "agentId": "main",
     "stream": "lifecycle",
     "data": {
      "phase": "finishing",
      "stopReason": "stop",
      "aborted": false,
      "livenessState": "working",
      "replayInvalid": true,
      "endedAt": 1792937622118
     },
     "seq": 18,
     "ts": 1792937622118,
     "isHeartbeat": false
    },
    "seq": 159,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "agentId": "main",
     "sessionId": "ccdebd20-db5f-443b-ba51-d906e78a9c5a",
     "sessionKey": "agent:main:main",
     "lane": "main",
     "stream": "lifecycle",
     "data": {
      "phase": "model",
      "provider": null,
      "model": null
     },
     "seq": 19,
     "ts": 1792937622501,
     "isHeartbeat": false
    },
    "seq": 165,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
     "sessionKey": "agent:main:main",
     "stream": "lifecycle",
     "data": {
      "stopReason": "stop",
      "aborted": false,
      "livenessState": "working",
      "replayInvalid": true,
      "phase": "end",
      "endedAt": 1792937622502,
      "startedAt": 1792937613448,
      "terminalReply": {
       "disposition": "visible",
       "text": "`agentreins-m2-fixture-ok`"
      },
      "terminalReceipt": {
       "runId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
       "sessionId": "ccdebd20-db5f-443b-ba51-d906e78a9c5a",
       "turnId": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f",
       "requested": {
        "provider": "bigmodel",
        "model": "glm-5.3-flash"
       },
       "effective": {
        "provider": "bigmodel",
        "model": "glm-5.3-flash",
        "responseModel": "glm-5.3-flash"
       },
       "successfulToolNames": [
        "exec"
       ],
       "rerouted": false,
       "terminalDisposition": "visible"
      },
      "executionSettled": true
     },
     "sessionId": "ccdebd20-db5f-443b-ba51-d906e78a9c5a",
     "agentId": "main",
     "seq": 20,
     "ts": 1792937622502,
     "isHeartbeat": false
    },
    "seq": 166,
    "recipientProfileId": "gateway-owner"
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "thinking",
     "data": {
      "text": "<redacted-for-fixture>",
      "delta": " search wiring —"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 9342,
     "ts": 1792937646543,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "kind": "preamble",
      "title": "<redacted-for-fixture>",
      "phase": "update",
      "progressText": "<redacted-for-fixture>",
      "itemId": "commentary-0-29ee1094b594412fa68996b4"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10041,
     "ts": 1792937705716,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "stream": "usage",
     "data": {
      "outputTokens": 15743
     },
     "agentId": "coding",
     "seq": 10042,
     "ts": 1792937705776,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "kind": "preamble",
      "title": "<redacted-for-fixture>",
      "phase": "end",
      "progressText": "<redacted-for-fixture>",
      "itemId": "commentary-0-29ee1094b594412fa68996b4"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10043,
     "ts": 1792937705777,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "itemId": "tool:call_9c5112abc30c4e079a4efaf3",
      "toolCallId": "call_9c5112abc30c4e079a4efaf3",
      "name": "exec",
      "kind": "tool",
      "phase": "start",
      "title": "<redacted-for-fixture>",
      "status": "running",
      "meta": "<redacted-for-fixture>",
      "commandBearing": true,
      "startedAt": 1792937707804
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10044,
     "ts": 1792937707804,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "itemId": "tool:call_9c5112abc30c4e079a4efaf3",
      "toolCallId": "call_9c5112abc30c4e079a4efaf3",
      "name": "exec",
      "kind": "tool",
      "phase": "end",
      "title": "<redacted-for-fixture>",
      "status": "completed",
      "meta": "<redacted-for-fixture>",
      "commandBearing": true,
      "startedAt": 1792937707804,
      "endedAt": 1792937707960
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10047,
     "ts": 1792937707960,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "command_output",
     "data": {
      "itemId": "command:call_9c5112abc30c4e079a4efaf3",
      "phase": "end",
      "title": "<redacted-for-fixture>",
      "toolCallId": "call_9c5112abc30c4e079a4efaf3",
      "name": "exec",
      "output": "<redacted-for-fixture>",
      "status": "completed",
      "exitCode": 0,
      "durationMs": 98
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10048,
     "ts": 1792937707961,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "sessionId": "98a90cab-f561-4c4e-875d-0ccd020274f0",
     "agentId": "coding",
     "stream": "lifecycle",
     "data": {
      "phase": "model",
      "provider": "bigmodel",
      "model": "glm-5.3-flash"
     },
     "seq": 10049,
     "ts": 1792937710688,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "kind": "preamble",
      "title": "<redacted-for-fixture>",
      "phase": "update",
      "progressText": "<redacted-for-fixture>",
      "itemId": "commentary-0-80285d9514ad4d12903af1d1"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10508,
     "ts": 1792937741101,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "stream": "usage",
     "data": {
      "outputTokens": 17551
     },
     "agentId": "coding",
     "seq": 10509,
     "ts": 1792937741112,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "kind": "preamble",
      "title": "<redacted-for-fixture>",
      "phase": "end",
      "progressText": "<redacted-for-fixture>",
      "itemId": "commentary-0-80285d9514ad4d12903af1d1"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10510,
     "ts": 1792937741113,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "itemId": "tool:call_f7a71f8d15a142419c8a3a80",
      "toolCallId": "call_f7a71f8d15a142419c8a3a80",
      "name": "exec",
      "kind": "tool",
      "phase": "start",
      "title": "<redacted-for-fixture>",
      "status": "running",
      "meta": "<redacted-for-fixture>",
      "commandBearing": true,
      "startedAt": 1792937741140
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10511,
     "ts": 1792937741140,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "itemId": "tool:call_f7a71f8d15a142419c8a3a80",
      "toolCallId": "call_f7a71f8d15a142419c8a3a80",
      "name": "exec",
      "kind": "tool",
      "phase": "end",
      "title": "<redacted-for-fixture>",
      "status": "completed",
      "meta": "<redacted-for-fixture>",
      "commandBearing": true,
      "startedAt": 1792937741140,
      "endedAt": 1792937742642
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10514,
     "ts": 1792937742642,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "command_output",
     "data": {
      "itemId": "command:call_f7a71f8d15a142419c8a3a80",
      "phase": "end",
      "title": "<redacted-for-fixture>",
      "toolCallId": "call_f7a71f8d15a142419c8a3a80",
      "name": "exec",
      "output": "<redacted-for-fixture>",
      "status": "completed",
      "exitCode": 0,
      "durationMs": 1437
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10515,
     "ts": 1792937742643,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "sessionId": "98a90cab-f561-4c4e-875d-0ccd020274f0",
     "agentId": "coding",
     "stream": "lifecycle",
     "data": {
      "phase": "model",
      "provider": "bigmodel",
      "model": "glm-5.3-flash"
     },
     "seq": 10516,
     "ts": 1792937745475,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "kind": "preamble",
      "title": "<redacted-for-fixture>",
      "phase": "update",
      "progressText": "<redacted-for-fixture>",
      "itemId": "commentary-0-ad34ec0c065b437894fec572"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10792,
     "ts": 1792937753751,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "stream": "usage",
     "data": {
      "outputTokens": 18069
     },
     "agentId": "coding",
     "seq": 10793,
     "ts": 1792937753756,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "kind": "preamble",
      "title": "<redacted-for-fixture>",
      "phase": "end",
      "progressText": "<redacted-for-fixture>",
      "itemId": "commentary-0-ad34ec0c065b437894fec572"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10794,
     "ts": 1792937753757,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "itemId": "tool:call_e93440e4ff8d428e87d741ff",
      "toolCallId": "call_e93440e4ff8d428e87d741ff",
      "name": "exec",
      "kind": "tool",
      "phase": "start",
      "title": "<redacted-for-fixture>",
      "status": "running",
      "meta": "<redacted-for-fixture>",
      "commandBearing": true,
      "startedAt": 1792937753785
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10795,
     "ts": 1792937753786,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "item",
     "data": {
      "itemId": "tool:call_e93440e4ff8d428e87d741ff",
      "toolCallId": "call_e93440e4ff8d428e87d741ff",
      "name": "exec",
      "kind": "tool",
      "phase": "end",
      "title": "<redacted-for-fixture>",
      "status": "completed",
      "meta": "<redacted-for-fixture>",
      "commandBearing": true,
      "startedAt": 1792937753785,
      "endedAt": 1792937753913
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10798,
     "ts": 1792937753913,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "command_output",
     "data": {
      "itemId": "command:call_e93440e4ff8d428e87d741ff",
      "phase": "end",
      "title": "<redacted-for-fixture>",
      "toolCallId": "call_e93440e4ff8d428e87d741ff",
      "name": "exec",
      "output": "<redacted-for-fixture>",
      "status": "completed",
      "exitCode": 0,
      "durationMs": 72
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10799,
     "ts": 1792937753913,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "sessionId": "98a90cab-f561-4c4e-875d-0ccd020274f0",
     "agentId": "coding",
     "stream": "lifecycle",
     "data": {
      "phase": "model",
      "provider": "bigmodel",
      "model": "glm-5.3-flash"
     },
     "seq": 10800,
     "ts": 1792937758789,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   },
   {
    "type": "event",
    "event": "agent",
    "payload": {
     "runId": "3a53d235-95e1-4673-be07-c2f613899c91",
     "stream": "thinking",
     "data": {
      "text": "<redacted-for-fixture>",
      "delta": "限制).\n\nLet me clean up and"
     },
     "sessionKey": "agent:coding:subagent:d6c97209-6daf-43c5-b6ea-9b5d8cf33b26",
     "agentId": "coding",
     "seq": 10981,
     "ts": 1792937761883,
     "spawnedBy": "agent:it:telegram:it:direct:5716197848"
    }
   }
  ],
  "history": {
   "messages": [
    {
     "role": "user",
     "content": "hello from AgentReins M1 smoke (自动冒烟，请回复任意一句话)",
     "timestamp": 1792917584427,
     "idempotencyKey": "19d2f2d5-a55f-4adb-a672-9f6ddfba8bff:user",
     "id": "8f81f973-3732-4e82-8864-7c1b23b20381"
    },
    {
     "role": "assistant",
     "content": [
      {
       "type": "text",
       "text": "冒烟测试收到 ✅ 链路双向通畅，我在线正常待命。"
      }
     ],
     "timestamp": 1792917586263,
     "id": "7494e109-a0b8-4685-b129-c4cbacdac432"
    },
    {
     "role": "user",
     "content": "在吗？",
     "timestamp": 1792924473996,
     "idempotencyKey": "a35ed560-9d5e-47a3-bf15-2ff0fa920e60:user",
     "id": "be2dc450-0cad-4fa0-9ff1-89f58e4cecfe"
    },
    {
     "role": "assistant",
     "content": [
      {
       "type": "text",
       "text": "在的，x总 ✅ 随时待命。有什么吩咐？"
      }
     ],
     "timestamp": 1792924475197,
     "id": "b055d562-d382-4cbd-bfcf-bb37ed6fe5af"
    },
    {
     "role": "user",
     "content": "hello from AgentReins M1 smoke (自动冒烟，请回复任意一句话)",
     "timestamp": 1792930254049,
     "idempotencyKey": "65142e80-c70a-4a0f-86fb-b16364b1733b:user",
     "id": "d291d619-9b24-4016-b8cb-e27e8a77ac4d"
    },
    {
     "role": "assistant",
     "content": [
      {
       "type": "text",
       "text": "冒烟测试收到 ✅ 一切正常，在线待命。"
      }
     ],
     "timestamp": 1792930256461,
     "id": "0517149e-70e1-4db1-9ca7-c8211aa86068"
    },
    {
     "role": "user",
     "content": "hello from AgentReins M1 smoke (自动冒烟，请回复任意一句话)",
     "timestamp": 1792931315397,
     "idempotencyKey": "03cadae3-0e78-40e3-9d2c-8bacf7d8243e:user",
     "id": "de200aa8-9f5a-40e6-9c11-21528262acec"
    },
    {
     "role": "assistant",
     "content": [
      {
       "type": "text",
       "text": "冒烟测试收到 ✅ 链路正常，在线待命。"
      }
     ],
     "timestamp": 1792931319354,
     "id": "e9383412-72da-4739-985b-77d82b35b645"
    },
    {
     "role": "user",
     "content": "AgentReins M2 fixture 录制：请用 exec 工具执行 `echo agentreins-m2-fixture-ok`，把输出原样告诉我，然后结束。",
     "timestamp": 1792937611516,
     "idempotencyKey": "ff2c290c-2ad4-4bb0-8344-478cfbe9ad4f:user",
     "id": "7ba2587b-03e1-4459-b364-14803dff3e5b"
    },
    {
     "role": "assistant",
     "content": [
      {
       "type": "toolCall",
       "id": "call_b1dcf84bff954ff187c79484",
       "name": "exec",
       "arguments": {
        "command": "echo agentreins-m2-fixture-ok",
        "title": "AgentReins M2 fixture 回显测试"
       }
      }
     ],
     "timestamp": 1792937613460,
     "id": "428ee2ea-1ce0-46a2-9646-939dce690acc"
    },
    {
     "role": "toolResult",
     "content": [
      {
       "type": "text",
       "text": "agentreins-m2-fixture-ok"
      }
     ],
     "timestamp": 1792937619683,
     "toolCallId": "call_b1dcf84bff954ff187c79484",
     "toolName": "exec",
     "isError": false,
     "id": "e8ca38d8-a3c1-45c2-859f-bf4627b8e369"
    },
    {
     "role": "assistant",
     "content": [
      {
       "type": "text",
       "text": "`agentreins-m2-fixture-ok`"
      }
     ],
     "timestamp": 1792937619704,
     "id": "beb13ccd-4862-4ab2-aeeb-8805fc60e253"
    }
   ],
   "totalMessages": 2401
  }
 }
}"""
}
