"""Local FastAPI model gateway. Run uvicorn app:app --host 127.0.0.1 --port 8001."""
import asyncio
from contextlib import asynccontextmanager
import hmac
import json
import logging
import re
import time
from uuid import uuid4

import anyio
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse, StreamingResponse
import httpx
from pydantic import ValidationError
from starlette.exceptions import HTTPException

from config import ALIASES, Settings
from contracts import CompletionRequest, load_json
from upstream import GatewayError, before_deadline, bounded_body, check_status, classify, relay_sse, validate_completion

audit = logging.getLogger("gateway.audit")
audit.setLevel(logging.INFO)
if not audit.handlers:
    audit.addHandler(logging.StreamHandler())
ID = re.compile(r"^[A-Za-z0-9_.-]{1,64}$")


def log_request(stats, start):
    # Explicit allowlist: never log request/exception bodies, headers or extra usage fields.
    record = {key: stats.get(key) for key in ("request_id", "model", "stream", "status", "upstream_status", "error_category")}
    record["latency_ms"] = round((time.monotonic() - start) * 1000, 2)
    if stats.get("usage") is not None:
        record["usage"] = {key: stats["usage"][key] for key in ("prompt_tokens", "completion_tokens", "total_tokens")}
    audit.info(json.dumps(record, separators=(",", ":")))


class ManagedStream(StreamingResponse):
    def __init__(self, upstream, settings, deadline, stats, start):
        self.upstream, self.stats, self.start = upstream, stats, start
        super().__init__(relay_sse(upstream, settings, deadline, stats), media_type="text/event-stream",
                         headers={"X-Request-ID": stats["request_id"], "Cache-Control": "no-cache"})

    async def __call__(self, scope, receive, send):
        try:
            await super().__call__(scope, receive, send)
        except asyncio.CancelledError:
            self.stats["status"] = 499
            raise
        finally:
            # Starlette may cancel the response task on disconnect. Cleanup must survive that cancellation.
            with anyio.CancelScope(shield=True):
                await self.upstream.aclose()
                await self.body_iterator.aclose()
            if not self.stats.get("stream_terminated"):
                self.stats["status"] = 499
            log_request(self.stats, self.start)


def create_app(settings=None, client=None):
    settings = settings or Settings()

    @asynccontextmanager
    async def lifespan(application):
        application.state.client = client or httpx.AsyncClient(timeout=settings.timeout(),
                transport=httpx.AsyncHTTPTransport(retries=0), trust_env=False, follow_redirects=False,
                limits=httpx.Limits(max_connections=20, max_keepalive_connections=10))
        try:
            yield
        finally:
            if client is None:
                await application.state.client.aclose()

    application = FastAPI(lifespan=lifespan, docs_url=None, redoc_url=None, openapi_url=None)

    @application.exception_handler(HTTPException)
    async def http_error(request, error):
        request_id = uuid4().hex
        return JSONResponse(GatewayError(error.status_code, "REQUEST_INVALID").payload(request_id),
                            status_code=error.status_code, headers={"X-Request-ID": request_id})

    @application.get("/health")
    async def health():
        return {"status": "ok"}

    @application.post("/v1/chat/completions")
    async def completion(request: Request):
        start = time.monotonic()
        deadline = start + settings.deadline
        candidate = request.headers.get("x-request-id", "")
        request_id = candidate if ID.fullmatch(candidate) else uuid4().hex
        stats = {"request_id": request_id, "model": None, "stream": None, "status": 200,
                 "upstream_status": None, "error_category": None}
        response = None
        handed_off = False
        try:
            if not hmac.compare_digest(request.headers.get("authorization", "").encode(), ("Bearer " + settings.api_key).encode()):
                raise GatewayError(401, "AUTH_FAILED")
            data = bytearray()
            chunks = request.stream().__aiter__()
            while True:
                try:
                    chunk = await before_deadline(anext(chunks), deadline)
                except StopAsyncIteration:
                    break
                data.extend(chunk)
                if len(data) > settings.max_request_bytes:
                    raise GatewayError(413, "REQUEST_INVALID")
            try:
                payload = CompletionRequest.model_validate(load_json(data))
            except (ValidationError, ValueError, TypeError):
                raise GatewayError(400, "REQUEST_INVALID") from None
            if payload.model not in ALIASES:
                raise GatewayError(400, "REQUEST_INVALID")
            stats.update(model=payload.model, stream=payload.stream)
            forwarded = payload.model_dump(exclude_unset=True, mode="json")
            forwarded["model"] = ALIASES[payload.model]
            outgoing = application.state.client.build_request("POST", settings.upstream_url, json=forwarded,
                    timeout=settings.timeout(), headers={"X-Request-ID": request_id, "X-Gateway-Hop": "model-gateway"})
            response = await before_deadline(application.state.client.send(outgoing, stream=True), deadline)
            stats["upstream_status"] = response.status_code
            check_status(response.status_code)
            if payload.stream:
                if response.headers.get("content-type", "").split(";")[0] != "text/event-stream":
                    raise GatewayError(502, "RESPONSE_INVALID")
                result = ManagedStream(response, settings, deadline, stats, start)
                handed_off = True
                return result
            raw = await bounded_body(response, settings.max_response_bytes, deadline)
            try:
                body = validate_completion(load_json(raw))
            except (ValidationError, ValueError, TypeError, AttributeError):
                raise GatewayError(502, "RESPONSE_INVALID") from None
            stats["usage"] = body.get("usage")
            return JSONResponse(body, headers={"X-Request-ID": request_id})
        except (GatewayError, httpx.HTTPError, TimeoutError) as error:
            failure = classify(error)
            stats.update(status=failure.status, error_category=failure.category)
            return JSONResponse(failure.payload(request_id), status_code=failure.status,
                                headers={"X-Request-ID": request_id})
        finally:
            if not handed_off:
                with anyio.CancelScope(shield=True):
                    if response is not None:
                        await response.aclose()
                log_request(stats, start)

    return application


app = create_app()
