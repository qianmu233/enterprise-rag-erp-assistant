import asyncio
from contextlib import asynccontextmanager
from dataclasses import replace
import json
import logging

import httpx
import pytest

from app import create_app
from config import Settings, UPSTREAM_URL
from fake_model import USAGE, chunk


@pytest.fixture
def anyio_backend():
    return "asyncio"


def payload(**changes):
    return {"model": "gateway-chat", "messages": [{"role": "user", "content": "private prompt"}], **changes}


def completion(**changes):
    return {"id": "local", "object": "chat.completion", "created": 1, "model": "local-fake",
            "choices": [{"index": 0, "message": {"role": "assistant", "content": "local answer"}, "finish_reason": "stop"}],
            "usage": USAGE.copy(), **changes}


class BytesStream(httpx.AsyncByteStream):
    def __init__(self, parts):
        self.parts, self.closed = parts, False

    async def __aiter__(self):
        for delay, data in self.parts:
            await asyncio.sleep(delay)
            yield data

    async def aclose(self):
        self.closed = True


@asynccontextmanager
async def harness(handler, settings=None):
    async with httpx.AsyncClient(transport=httpx.MockTransport(handler), trust_env=False) as upstream:
        app = create_app(settings or Settings(), client=upstream)
        async with app.router.lifespan_context(app):
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://gateway",
                    headers={"Authorization": "Bearer local-gateway-test-key"}) as client:
                yield client


@pytest.mark.anyio
@pytest.mark.parametrize("alias", ["gateway-chat", "local-fake"])
async def test_sync_alias_messages_tools_parameters_usage_and_request_id(alias):
    seen = []
    def handler(request):
        seen.append(request)
        return httpx.Response(200, json=completion())
    async with harness(handler) as client:
        request = payload(model=alias, temperature=0.2, max_tokens=32,
                tools=[{"type": "function", "function": {"name": "local_probe", "parameters": {"type": "object"}}}],
                tool_choice="auto")
        response = await client.post("/v1/chat/completions", json=request, headers={"X-Request-ID": "safe-id-01"})
        assert response.status_code == 200
        assert response.json()["usage"] == USAGE
        assert response.headers["x-request-id"] == "safe-id-01"
    forwarded = json.loads(seen[0].content)
    assert forwarded == {**request, "model": "local-fake"}
    assert str(seen[0].url) == UPSTREAM_URL
    assert "authorization" not in seen[0].headers
    assert seen[0].headers["x-gateway-hop"] == "model-gateway"
    assert seen[0].headers["x-request-id"] == "safe-id-01"
    assert seen[0].extensions["timeout"] == {"connect": 1, "read": 1, "write": 1, "pool": 1}


@pytest.mark.anyio
async def test_sse_usage_passthrough_and_crlf_split():
    final = chunk({}, "stop")
    body = (chunk({"role": "assistant", "content": "你好"}) + final + "data: " + json.dumps({
        "id": "local", "object": "chat.completion.chunk", "created": 1, "model": "local-fake",
        "choices": [], "usage": USAGE}) + "\n\ndata: [DONE]\n\n").replace("\n", "\r\n").encode()
    stream = BytesStream([(0, body[:37]), (0, body[37:])])
    async with harness(lambda request: httpx.Response(200, headers={"Content-Type": "text/event-stream"}, stream=stream)) as client:
        response = await client.post("/v1/chat/completions", json=payload(stream=True))
        assert response.status_code == 200
        assert "你好" in response.text
        assert response.text.endswith("data: [DONE]\n\n")
        assert '"total_tokens":10' in response.text
    assert stream.closed


@pytest.mark.anyio
async def test_tool_call_and_tool_result_are_not_executed_or_changed():
    call = {"id": "call-1", "type": "function", "function": {"name": "local_probe", "arguments": '{"value":"secret-tool-arg"}'}}
    messages = [{"role": "user", "content": "q"}, {"role": "assistant", "content": None, "tool_calls": [call]},
                {"role": "tool", "tool_call_id": "call-1", "content": "42"}]
    seen = []
    def handler(request):
        seen.append(json.loads(request.content))
        return httpx.Response(200, json=completion())
    async with harness(handler) as client:
        response = await client.post("/v1/chat/completions", json=payload(messages=messages))
        assert response.status_code == 200
    assert seen[0]["messages"] == messages


@pytest.mark.anyio
@pytest.mark.parametrize("status,category", [(400, "REQUEST_INVALID"), (401, "AUTH_FAILED"), (429, "RATE_LIMITED"),
        (500, "UPSTREAM_ERROR"), (502, "UPSTREAM_UNAVAILABLE"), (503, "UPSTREAM_UNAVAILABLE"), (504, "UPSTREAM_TIMEOUT")])
async def test_http_errors_are_classified_sanitized_and_not_retried(status, category):
    seen = []
    def handler(request):
        seen.append(request)
        return httpx.Response(status, json={"error": {"message": "secret upstream prompt and authorization"}})
    async with harness(handler) as client:
        response = await client.post("/v1/chat/completions", json=payload())
        assert response.status_code == status
        assert response.json()["error"]["code"] == category
        assert response.json()["request_id"] == response.headers["x-request-id"]
        assert "secret" not in response.text
    assert len(seen) == 1


@pytest.mark.anyio
@pytest.mark.parametrize("failure,category,status", [(httpx.ConnectTimeout, "UPSTREAM_TIMEOUT", 504),
        (httpx.ReadTimeout, "UPSTREAM_TIMEOUT", 504), (httpx.WriteTimeout, "UPSTREAM_TIMEOUT", 504),
        (httpx.PoolTimeout, "UPSTREAM_TIMEOUT", 504), (httpx.ConnectError, "UPSTREAM_UNAVAILABLE", 503)])
async def test_network_failures(failure, category, status):
    calls = []
    def handler(request):
        calls.append(request)
        raise failure("private diagnostic", request=request)
    async with harness(handler) as client:
        response = await client.post("/v1/chat/completions", json=payload())
        assert response.status_code == status
        assert response.json()["error"]["code"] == category
        assert "private" not in response.text
    assert len(calls) == 1


@pytest.mark.anyio
async def test_total_deadline_includes_body_read_and_closes_response():
    stream = BytesStream([(0.2, json.dumps(completion()).encode())])
    async with harness(lambda r: httpx.Response(200, stream=stream), replace(Settings(), deadline=0.03)) as client:
        response = await client.post("/v1/chat/completions", json=payload())
        assert response.status_code == 504
        assert response.json()["error"]["code"] == "UPSTREAM_TIMEOUT"
    assert stream.closed


@pytest.mark.anyio
@pytest.mark.parametrize("data", [payload(model="unknown"), payload(model=""), payload(messages=[]),
        payload(messages=[{"role": "admin", "content": "secret"}]), payload(messages=[{"role": "user", "content": 42}]),
        payload(upstream_url="http://evil.invalid"), payload(messages=[{"role": "tool", "content": "secret"}]),
        payload(messages=[{"role": "assistant", "tool_calls": [{"id": "x", "type": "function", "function": {"name": "x", "arguments": "not-json"}}]}]),
        payload(messages=[{"role": "tool", "tool_call_id": "unknown", "content": "secret"}]),
        payload(tools=[], tool_choice="required"), payload(tool_choice={"url": "http://evil.invalid"})])
async def test_invalid_request_never_reaches_upstream(data):
    def handler(_):
        pytest.fail("Invalid request reached upstream")
    async with harness(handler) as client:
        response = await client.post("/v1/chat/completions", json=data)
        assert response.status_code == 400
        assert response.json()["error"]["code"] == "REQUEST_INVALID"
        assert "secret" not in response.text


@pytest.mark.anyio
async def test_invalid_json_and_large_body():
    def handler(_):
        pytest.fail("Invalid body reached upstream")
    async with harness(handler, replace(Settings(), max_request_bytes=128)) as client:
        invalid = await client.post("/v1/chat/completions", content="{broken")
        large = await client.post("/v1/chat/completions", content=b"x" * 129)
        assert invalid.status_code == 400
        assert large.status_code == 413
        assert large.json()["error"]["code"] == "REQUEST_INVALID"


@pytest.mark.anyio
async def test_auth_and_request_id_sanitization():
    async with harness(lambda r: httpx.Response(200, json=completion())) as client:
        invalid = await client.post("/v1/chat/completions", json=payload(), headers={"Authorization": "Bearer wrong"})
        assert invalid.status_code == 401
        assert invalid.json()["error"]["code"] == "AUTH_FAILED"
        valid = await client.post("/v1/chat/completions", json=payload(), headers={"X-Request-ID": "unsafe id"})
        assert len(valid.headers["x-request-id"]) == 32


@pytest.mark.anyio
@pytest.mark.parametrize("body", ["{broken", "[]", json.dumps(completion(choices=[])), json.dumps(completion(usage={"total_tokens": 4}))])
async def test_invalid_response(body):
    async with harness(lambda r: httpx.Response(200, content=body)) as client:
        response = await client.post("/v1/chat/completions", json=payload())
        assert response.status_code == 502
        assert response.json()["error"]["code"] == "RESPONSE_INVALID"


@pytest.mark.anyio
@pytest.mark.parametrize("body", [chunk({"content": "partial"}), chunk({"content": "partial"}) + chunk({}, "stop"),
        "data: [DONE]\n\n", 'data: {"unterminated":'])
async def test_early_eof_is_an_error_frame_never_done(body):
    stream = BytesStream([(0, body.encode())])
    async with harness(lambda r: httpx.Response(200, headers={"Content-Type": "text/event-stream"}, stream=stream)) as client:
        response = await client.post("/v1/chat/completions", json=payload(stream=True))
        assert response.status_code == 200  # Headers were already sent.
        assert "UPSTREAM_EOF" in response.text
        assert "data: [DONE]" not in response.text
        error = json.loads(response.text.strip().split("data: ")[-1])
        assert error["request_id"] == response.headers["x-request-id"]
    assert stream.closed


@pytest.mark.anyio
@pytest.mark.parametrize("body,category", [("data: {broken\n\n", "RESPONSE_INVALID"),
        ('data: {"error":{"message":"private upstream detail"}}\n\n', "UPSTREAM_ERROR")])
async def test_invalid_or_error_sse_is_sanitized(body, category):
    async with harness(lambda r: httpx.Response(200, headers={"Content-Type": "text/event-stream"}, content=body)) as client:
        response = await client.post("/v1/chat/completions", json=payload(stream=True))
        assert category in response.text
        assert "private" not in response.text
        assert "[DONE]" not in response.text


@pytest.mark.anyio
async def test_stream_deadline_and_usage_absence():
    stream = BytesStream([(0, chunk({"content": "partial"}).encode()), (0.2, chunk({}, "stop").encode())])
    async with harness(lambda r: httpx.Response(200, headers={"Content-Type": "text/event-stream"}, stream=stream),
            replace(Settings(), deadline=0.03)) as client:
        response = await client.post("/v1/chat/completions", json=payload(stream=True))
        assert "UPSTREAM_TIMEOUT" in response.text
        assert "[DONE]" not in response.text
        assert '"usage"' not in response.text
    assert stream.closed
    body = completion()
    del body["usage"]
    async with harness(lambda r: httpx.Response(200, json=body)) as client:
        assert "usage" not in (await client.post("/v1/chat/completions", json=payload())).json()


@pytest.mark.anyio
async def test_logs_are_allowlisted_and_never_echo_sensitive_data(caplog):
    body = completion(usage={**USAGE, "private_extra": "secret metadata"})
    caplog.set_level(logging.INFO, logger="gateway.audit")
    async with harness(lambda r: httpx.Response(200, json=body)) as client:
        await client.post("/v1/chat/completions", json=payload(messages=[{"role": "user", "content": "secret prompt"}]))
        await client.post("/v1/chat/completions", json=payload(model="secret unknown alias"))
    records = [json.loads(r.message) for r in caplog.records if r.name == "gateway.audit"]
    assert len(records) == 2
    assert set(records[0]) <= {"request_id", "model", "stream", "status", "latency_ms", "upstream_status", "error_category", "usage"}
    assert set(records[0]["usage"]) == set(USAGE)
    assert "secret" not in json.dumps(records)
    assert "Bearer" not in json.dumps(records)


@pytest.mark.anyio
async def test_disconnect_closes_upstream_response():
    stream = BytesStream([(0, chunk({"content": "partial"}).encode()), (10, b"never")])
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r: httpx.Response(200,
            headers={"Content-Type": "text/event-stream"}, stream=stream))) as upstream:
        app = create_app(client=upstream)
        sent_body = False
        disconnect = asyncio.Event()
        async def receive():
            nonlocal sent_body
            if not sent_body:
                sent_body = True
                return {"type": "http.request", "body": json.dumps(payload(stream=True)).encode(), "more_body": False}
            await disconnect.wait()
            return {"type": "http.disconnect"}
        async def send(message):
            if message["type"] == "http.response.body" and message.get("body"):
                disconnect.set()
        scope = {"type": "http", "asgi": {"version": "3.0", "spec_version": "2.0"}, "method": "POST",
                 "path": "/v1/chat/completions", "raw_path": b"/v1/chat/completions", "query_string": b"",
                 "headers": [(b"authorization", b"Bearer local-gateway-test-key")], "scheme": "http",
                 "server": ("127.0.0.1", 8001), "client": ("127.0.0.1", 12345), "root_path": ""}
        async with app.router.lifespan_context(app):
            await asyncio.wait_for(app(scope, receive, send), 2)
    assert stream.closed


def test_upstream_allowlist_and_timeout_settings():
    with pytest.raises(ValueError):
        Settings(upstream_url="https://api.example.invalid/v1/chat/completions")
    with pytest.raises(ValueError):
        Settings(deadline=0)


@pytest.mark.anyio
async def test_sync_tool_call_response_is_preserved():
    call = {"id": "call-1", "type": "function", "function": {"name": "local_probe", "arguments": '{"value":"unchanged"}'}}
    body = completion(choices=[{"index": 0, "message": {"role": "assistant", "content": None, "tool_calls": [call]},
                               "finish_reason": "tool_calls"}])
    async with harness(lambda r: httpx.Response(200, json=body)) as client:
        response = await client.post("/v1/chat/completions", json=payload())
        assert response.status_code == 200
        assert response.json() == body


@pytest.mark.anyio
async def test_sse_fragmented_tool_calls_and_sdk_stream_options():
    first = {"role": "assistant", "tool_calls": [{"index": 0, "id": "call-1", "type": "function",
             "function": {"name": "local_probe", "arguments": '{"value":'}}]}
    second = {"tool_calls": [{"index": 0, "function": {"arguments": '"unchanged"}'}}]}
    body = chunk(first) + chunk(second) + chunk({}, "tool_calls") + "data: [DONE]\n\n"
    async with harness(lambda r: httpx.Response(200, headers={"Content-Type": "text/event-stream"}, content=body)) as client:
        response = await client.post("/v1/chat/completions", json=payload(stream=True,
                stream_options={"include_usage": True, "include_obfuscation": False}))
        assert response.status_code == 200
        frames = [json.loads(value[6:]) for value in response.text.strip().split("\n\n")[:-1]]
        assert frames[0]["choices"][0]["delta"] == first
        assert frames[1]["choices"][0]["delta"] == second
        assert response.text.endswith("data: [DONE]\n\n")
        assert '"usage"' not in response.text


@pytest.mark.anyio
async def test_read_timeout_after_stream_headers_is_an_error_frame_and_closes():
    class TimeoutStream(BytesStream):
        async def __aiter__(self):
            yield chunk({"content": "partial"}).encode()
            raise httpx.ReadTimeout("private timeout")
    stream = TimeoutStream([])
    async with harness(lambda r: httpx.Response(200, headers={"Content-Type": "text/event-stream"}, stream=stream)) as client:
        response = await client.post("/v1/chat/completions", json=payload(stream=True))
        assert response.status_code == 200
        assert "partial" in response.text
        assert "UPSTREAM_TIMEOUT" in response.text
        assert "private" not in response.text
        assert "[DONE]" not in response.text
    assert stream.closed


@pytest.mark.anyio
async def test_response_and_frame_size_limits_close_connections():
    stream = BytesStream([(0, b"x" * 129)])
    async with harness(lambda r: httpx.Response(200, stream=stream), replace(Settings(), max_response_bytes=128)) as client:
        response = await client.post("/v1/chat/completions", json=payload())
        assert response.status_code == 502
        assert response.json()["error"]["code"] == "RESPONSE_INVALID"
    assert stream.closed
    async with harness(lambda r: httpx.Response(200, headers={"Content-Type": "text/event-stream"}, content="data: " + "x" * 129),
            replace(Settings(), max_frame_bytes=128)) as client:
        response = await client.post("/v1/chat/completions", json=payload(stream=True))
        assert "RESPONSE_INVALID" in response.text
        assert "[DONE]" not in response.text


@pytest.mark.anyio
@pytest.mark.parametrize("source", ["request", "response", "sse"])
async def test_deep_json_has_a_structured_sanitized_error(source):
    deep = "[" * 10000 + "0" + "]" * 10000
    seen = []
    def handler(request):
        seen.append(request)
        if source == "sse":
            return httpx.Response(200, headers={"Content-Type": "text/event-stream"}, content="data: " + deep + "\n\n")
        return httpx.Response(200, content=deep)
    async with harness(handler) as client:
        if source == "request":
            response = await client.post("/v1/chat/completions", content=deep)
            assert response.status_code == 400
            assert response.json()["error"]["code"] == "REQUEST_INVALID"
            assert seen == []
        else:
            response = await client.post("/v1/chat/completions", json=payload(stream=source == "sse"))
            assert response.status_code == (200 if source == "sse" else 502)
            assert "RESPONSE_INVALID" in response.text
            assert "[DONE]" not in response.text
        assert "RecursionError" not in response.text
        assert deep not in response.text
