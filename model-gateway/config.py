"""Operator configuration; request bodies never select a URL or credentials."""
from dataclasses import dataclass
import math

import httpx

UPSTREAM_URL = "http://127.0.0.1:8002/v1/chat/completions"
ALIASES = {"gateway-chat": "local-fake", "local-fake": "local-fake"}


@dataclass(frozen=True)
class Settings:
    upstream_url: str = UPSTREAM_URL
    api_key: str = "local-gateway-test-key"  # Local demo token, never a provider key.
    connect_timeout: float = 1.0
    read_timeout: float = 1.0
    write_timeout: float = 1.0
    pool_timeout: float = 1.0
    deadline: float = 3.0
    max_request_bytes: int = 1024 * 1024
    max_response_bytes: int = 8 * 1024 * 1024
    max_frame_bytes: int = 64 * 1024

    def __post_init__(self):
        if self.upstream_url != UPSTREAM_URL:
            raise ValueError("Upstream URL is outside the local allowlist")
        times = (self.connect_timeout, self.read_timeout, self.write_timeout, self.pool_timeout, self.deadline)
        if any(not math.isfinite(t) or t <= 0 for t in times):
            raise ValueError("Timeouts must be finite and positive")
        if not self.api_key or min(self.max_request_bytes, self.max_response_bytes, self.max_frame_bytes) <= 0:
            raise ValueError("Invalid gateway limits or local token")

    def timeout(self):
        return httpx.Timeout(connect=self.connect_timeout, read=self.read_timeout,
                             write=self.write_timeout, pool=self.pool_timeout)
