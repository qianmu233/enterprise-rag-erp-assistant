"""Test-only, loopback model. No SDK, credentials, database or external network calls."""
import asyncio
import hashlib
import json

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, Response, StreamingResponse

app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)
active_streams = 0
USAGE = {"prompt_tokens": 7, "completion_tokens": 3, "total_tokens": 10}  # Declared fixture, not measured usage.


@app.get("/health")
async def health():
    return {"status": "ok", "active_streams": active_streams}


def chunk(delta, finish=None, request_id="fixture"):
    return "data: " + json.dumps({"id": "chatcmpl-local", "object": "chat.completion.chunk", "created": 1,
            "model": "local-fake", "choices": [{"index": 0, "delta": delta, "finish_reason": finish}],
            "gateway_request_id": request_id}) + "\n\n"


@app.post("/v1/chat/completions")
async def completion(request: Request):
    global active_streams
    body = await request.json()
    rid = request.headers.get("x-request-id", "")
    if request.headers.get("x-gateway-hop") != "model-gateway" or not rid or body.get("model") != "local-fake":
        return JSONResponse({"error": {"message": "Missing local gateway proof", "type": "invalid_request_error"}}, status_code=400)
    def text(content):
        return content if isinstance(content, str) else "".join(part.get("text", "") for part in content or [])
    question = next((text(m.get("content")) for m in reversed(body["messages"]) if m["role"] == "user"), "")
    scenario = question.removeprefix("probe:").split(" ")[0]
    if scenario in ("400", "401", "429", "500", "502", "503", "504"):
        return JSONResponse({"error": {"message": "sensitive upstream details must never escape", "type": "fixture_error"}}, status_code=int(scenario))
    if scenario == "slow":
        await asyncio.sleep(5)
    if scenario == "invalid-json":
        return Response("{broken", media_type="application/json")
    if scenario == "invalid-response":
        return JSONResponse({"choices": []})
    tool_results = [m for m in body["messages"] if m["role"] == "tool"]
    needs_tool = scenario == "tool" and not tool_results
    digest = hashlib.sha256(json.dumps(body["messages"], ensure_ascii=False, sort_keys=True,
                                       separators=(",", ":")).encode()).hexdigest()
    # No prompt echo: expose a fingerprint and routing evidence only.
    proof = (f"via-python-gateway|model=local-fake|messages={len(body['messages'])}|request_id={rid}|sha256={digest}"
             + "|question_sha256=" + hashlib.sha256(question.encode()).hexdigest())
    if tool_results:
        proof += "|tool_result=" + text(tool_results[-1]["content"])
    name = body.get("tools", [{}])[0].get("function", {}).get("name", "local_probe")
    call = {"id": "call_local", "type": "function", "function": {"name": name, "arguments": '{"value":"probe"}'}}
    if not body.get("stream"):
        message = {"role": "assistant", "content": None, "tool_calls": [call]} if needs_tool else {"role": "assistant", "content": proof}
        result = {"id": "chatcmpl-local", "object": "chat.completion", "created": 1, "model": "local-fake",
                  "choices": [{"index": 0, "message": message, "finish_reason": "tool_calls" if needs_tool else "stop"}],
                  "gateway_request_id": rid}
        if scenario != "no-usage":
            result["usage"] = USAGE.copy()
        return JSONResponse(result)

    async def stream():
        global active_streams
        active_streams += 1
        try:
            if needs_tool:
                yield chunk({"role": "assistant", "tool_calls": [{"index": 0, "id": "call_local", "type": "function",
                        "function": {"name": name, "arguments": '{"value":'}}]}, request_id=rid)
                yield chunk({"tool_calls": [{"index": 0, "function": {"arguments": '"probe"}'}}]}, request_id=rid)
            else:
                yield chunk({"role": "assistant", "content": proof}, request_id=rid)
            if scenario == "early-eof":
                return
            if scenario in ("stall", "cancel"):
                await asyncio.sleep(10)
            if scenario == "trickle":
                for _ in range(12):
                    await asyncio.sleep(0.4)
                    yield ": heartbeat\n\n"
            if scenario == "invalid-sse":
                yield "data: {broken\n\n"
                return
            yield chunk({}, "tool_calls" if needs_tool else "stop", rid)
            if scenario == "missing-done":
                return
            if scenario != "no-usage":
                yield "data: " + json.dumps({"id": "chatcmpl-local", "object": "chat.completion.chunk", "created": 1,
                        "model": "local-fake", "choices": [], "usage": USAGE}) + "\n\n"
            yield "data: [DONE]\n\n"
        finally:
            active_streams -= 1

    return StreamingResponse(stream(), media_type="text/event-stream")
