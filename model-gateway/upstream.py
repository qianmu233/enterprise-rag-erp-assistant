"""HTTP and SSE protocol handling; no RAG, tools execution, retries or usage estimates."""
import asyncio
import json
import time

import httpx
from pydantic import ValidationError

from contracts import Message, ToolCall, Usage, load_json

MESSAGES = {
    "REQUEST_INVALID": "Invalid chat completion request.",
    "AUTH_FAILED": "Model service authentication failed.",
    "RATE_LIMITED": "Model service rate limit reached.",
    "UPSTREAM_TIMEOUT": "Model service timed out.",
    "UPSTREAM_UNAVAILABLE": "Model service is unavailable.",
    "UPSTREAM_ERROR": "Model service returned an error.",
    "RESPONSE_INVALID": "Model service returned an invalid response.",
    "UPSTREAM_EOF": "Model stream ended before normal completion.",
}


class GatewayError(Exception):
    def __init__(self, status, category):
        self.status, self.category = status, category
        super().__init__(category)

    def payload(self, request_id):
        return {"error": {"message": MESSAGES[self.category], "type": self.category,
                          "code": self.category, "param": None}, "request_id": request_id}


def classify(error):
    if isinstance(error, GatewayError):
        return error
    if isinstance(error, (TimeoutError, httpx.TimeoutException)):
        return GatewayError(504, "UPSTREAM_TIMEOUT")
    if isinstance(error, httpx.RemoteProtocolError):
        return GatewayError(502, "UPSTREAM_EOF")
    if isinstance(error, httpx.RequestError):
        return GatewayError(503, "UPSTREAM_UNAVAILABLE")
    return GatewayError(502, "RESPONSE_INVALID")


def check_status(status):
    if 200 <= status < 300:
        return
    category = {400: "REQUEST_INVALID", 401: "AUTH_FAILED", 403: "AUTH_FAILED", 429: "RATE_LIMITED",
                502: "UPSTREAM_UNAVAILABLE", 503: "UPSTREAM_UNAVAILABLE", 504: "UPSTREAM_TIMEOUT"}.get(status, "UPSTREAM_ERROR")
    raise GatewayError(status if status in (400, 401, 429, 500, 502, 503, 504) else 502, category)


async def before_deadline(awaitable, deadline):
    return await asyncio.wait_for(awaitable, timeout=max(0, deadline - time.monotonic()))


async def bounded_body(response, limit, deadline):
    data = bytearray()
    chunks = response.aiter_bytes().__aiter__()
    while True:
        try:
            chunk = await before_deadline(anext(chunks), deadline)
        except StopAsyncIteration:
            return bytes(data)
        data.extend(chunk)
        if len(data) > limit:
            raise GatewayError(502, "RESPONSE_INVALID")


def usage(data):
    value = data.get("usage")
    if value is not None:
        Usage.model_validate(value)
    return value


def validate_completion(data):
    if (not isinstance(data, dict) or data.get("object") != "chat.completion"
            or not isinstance(data.get("id"), str) or not isinstance(data.get("model"), str)
            or type(data.get("created")) is not int or not isinstance(data.get("choices"), list)
            or len(data["choices"]) != 1):
        raise GatewayError(502, "RESPONSE_INVALID")
    choice = data["choices"][0]
    if not isinstance(choice, dict) or choice.get("index") != 0 or choice.get("finish_reason") not in ("stop", "length", "tool_calls", "content_filter"):
        raise GatewayError(502, "RESPONSE_INVALID")
    message = Message.model_validate(choice.get("message"))
    if message.role != "assistant" or (choice["finish_reason"] == "tool_calls" and not message.tool_calls):
        raise GatewayError(502, "RESPONSE_INVALID")
    usage(data)
    return data


async def sse_frames(response, settings, deadline):
    buffer = b""
    total = 0
    chunks = response.aiter_bytes().__aiter__()
    while True:
        try:
            chunk = await before_deadline(anext(chunks), deadline)
        except StopAsyncIteration:
            if buffer.strip():
                raise GatewayError(502, "UPSTREAM_EOF")
            return
        total += len(chunk)
        if total > settings.max_response_bytes:
            raise GatewayError(502, "RESPONSE_INVALID")
        buffer = (buffer + chunk).replace(b"\r\n", b"\n")
        while b"\n\n" in buffer:
            frame, buffer = buffer.split(b"\n\n", 1)
            if len(frame) > settings.max_frame_bytes:
                raise GatewayError(502, "RESPONSE_INVALID")
            lines = frame.decode("utf-8", errors="strict").split("\n")
            values = [line[5:].removeprefix(" ") for line in lines if line.startswith("data:")]
            if values:
                yield "\n".join(values)
        if len(buffer) > settings.max_frame_bytes:
            raise GatewayError(502, "RESPONSE_INVALID")


async def relay_sse(response, settings, deadline, stats):
    finished = False
    calls = {}
    try:
        async for value in sse_frames(response, settings, deadline):
            if value == "[DONE]":
                if not finished:
                    raise GatewayError(502, "UPSTREAM_EOF")
                stats["stream_terminated"] = True
                yield b"data: [DONE]\n\n"
                return
            data = load_json(value)
            if not isinstance(data, dict):
                raise GatewayError(502, "RESPONSE_INVALID")
            if "error" in data:
                raise GatewayError(502, "UPSTREAM_ERROR")
            if (data.get("object") != "chat.completion.chunk" or not isinstance(data.get("id"), str)
                    or not isinstance(data.get("model"), str) or type(data.get("created")) is not int
                    or not isinstance(data.get("choices"), list) or len(data["choices"]) > 1):
                raise GatewayError(502, "RESPONSE_INVALID")
            observed_usage = usage(data)
            if observed_usage is not None:
                stats["usage"] = observed_usage
            if not data["choices"] and observed_usage is None:
                raise GatewayError(502, "RESPONSE_INVALID")
            for choice in data["choices"]:
                if (not isinstance(choice, dict) or choice.get("index") != 0 or finished
                        or not isinstance(choice.get("delta"), dict)):
                    raise GatewayError(502, "RESPONSE_INVALID")
                delta = choice["delta"]
                if delta.get("role", "assistant") != "assistant" or (delta.get("content") is not None and not isinstance(delta["content"], str)):
                    raise GatewayError(502, "RESPONSE_INVALID")
                for call in delta.get("tool_calls", []):
                    index = call.get("index")
                    if type(index) is not int or index < 0 or index > 63:
                        raise GatewayError(502, "RESPONSE_INVALID")
                    current = calls.setdefault(index, {"id": "", "type": "function", "function": {"name": "", "arguments": ""}})
                    if "id" in call:
                        current["id"] += call["id"]
                    if call.get("type", "function") != "function":
                        raise GatewayError(502, "RESPONSE_INVALID")
                    for key in ("name", "arguments"):
                        current["function"][key] += call.get("function", {}).get(key, "")
                reason = choice.get("finish_reason")
                if reason is not None:
                    if reason not in ("stop", "length", "tool_calls", "content_filter"):
                        raise GatewayError(502, "RESPONSE_INVALID")
                    if reason == "tool_calls":
                        if not calls:
                            raise GatewayError(502, "RESPONSE_INVALID")
                        for call in calls.values():
                            ToolCall.model_validate(call)
                    finished = True
            yield ("data: " + json.dumps(data, ensure_ascii=False, separators=(",", ":")) + "\n\n").encode()
        raise GatewayError(502, "UPSTREAM_EOF")
    except (GatewayError, httpx.HTTPError, TimeoutError, ValueError, TypeError, KeyError, AttributeError, ValidationError) as error:
        failure = classify(error)
        stats["error_category"] = failure.category
        stats["stream_terminated"] = True
        yield ("data: " + json.dumps(failure.payload(stats["request_id"])) + "\n\n").encode()
