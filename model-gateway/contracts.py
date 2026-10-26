"""Bounded text/tool Chat Completions subset. Validation errors never echo inputs."""
import json
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, StringConstraints, model_validator

Name = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9_-]{1,64}$")]
NonEmpty = Annotated[str, StringConstraints(min_length=1)]


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class TextPart(Contract):
    type: Literal["text"]
    text: str


class FunctionCall(Contract):
    name: Name
    arguments: str

    @model_validator(mode="after")
    def valid_arguments(self):
        if not isinstance(load_json(self.arguments), dict):
            raise ValueError("Tool arguments must be a JSON object")
        return self


class ToolCall(Contract):
    id: NonEmpty
    type: Literal["function"]
    function: FunctionCall


class Message(Contract):
    role: Literal["system", "developer", "user", "assistant", "tool"]
    content: str | list[TextPart] | None = None
    tool_calls: list[ToolCall] | None = None
    tool_call_id: NonEmpty | None = None
    name: Name | None = None

    @model_validator(mode="after")
    def valid_role_fields(self):
        if self.role == "tool":
            if not self.tool_call_id or self.content is None or self.tool_calls is not None:
                raise ValueError("Invalid tool message")
        elif self.tool_call_id is not None:
            raise ValueError("Unexpected tool_call_id")
        if self.tool_calls is not None and (self.role != "assistant" or not self.tool_calls):
            raise ValueError("Invalid assistant tool_calls")
        if self.content is None and not self.tool_calls:
            raise ValueError("Missing message content")
        return self


class FunctionDefinition(Contract):
    name: Name
    description: str | None = None
    parameters: dict
    strict: bool | None = None


class ToolDefinition(Contract):
    type: Literal["function"]
    function: FunctionDefinition


class StreamOptions(Contract):
    include_usage: bool = False
    include_obfuscation: bool | None = None


class CompletionRequest(Contract):
    model: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    messages: Annotated[list[Message], Field(min_length=1, max_length=256)]
    stream: bool = False
    temperature: Annotated[float, Field(ge=0, le=2, allow_inf_nan=False)] | None = None
    max_tokens: Annotated[int, Field(gt=0)] | None = None
    max_completion_tokens: Annotated[int, Field(gt=0)] | None = None
    tools: Annotated[list[ToolDefinition], Field(max_length=64)] | None = None
    tool_choice: Literal["auto", "none", "required"] | dict | None = None
    parallel_tool_calls: bool | None = None
    stream_options: StreamOptions | None = None
    n: Literal[1] = 1
    top_p: Annotated[float, Field(ge=0, le=1, allow_inf_nan=False)] | None = None
    frequency_penalty: Annotated[float, Field(ge=-2, le=2, allow_inf_nan=False)] | None = None
    presence_penalty: Annotated[float, Field(ge=-2, le=2, allow_inf_nan=False)] | None = None
    stop: str | list[str] | None = None
    seed: int | None = None
    store: bool | None = None
    user: str | None = None

    @model_validator(mode="after")
    def valid_tool_dialogue(self):
        names = [tool.function.name for tool in self.tools or []]
        if len(names) != len(set(names)):
            raise ValueError("Duplicate tool names")
        if isinstance(self.tool_choice, dict):
            if (set(self.tool_choice) != {"type", "function"} or self.tool_choice.get("type") != "function"
                    or not isinstance(self.tool_choice.get("function"), dict)
                    or set(self.tool_choice["function"]) != {"name"}
                    or self.tool_choice["function"]["name"] not in names):
                raise ValueError("Invalid tool_choice")
        elif self.tool_choice in ("auto", "required") and not names:
            raise ValueError("tool_choice requires tools")
        pending = set()
        for message in self.messages:
            if message.role == "tool":
                if message.tool_call_id not in pending:
                    raise ValueError("Unmatched tool result")
                pending.remove(message.tool_call_id)
            else:
                if pending:
                    raise ValueError("Unresolved tool calls")
                if message.tool_calls:
                    ids = [call.id for call in message.tool_calls]
                    if len(set(ids)) != len(ids):
                        raise ValueError("Duplicate tool call IDs")
                    pending.update(ids)
        if pending:
            raise ValueError("Missing tool results")
        return self


class Usage(Contract):
    model_config = ConfigDict(extra="allow", strict=True)
    prompt_tokens: Annotated[int, Field(ge=0)]
    completion_tokens: Annotated[int, Field(ge=0)]
    total_tokens: Annotated[int, Field(ge=0)]


def load_json(raw):
    def invalid_constant(_):
        raise ValueError("Non-finite JSON constant")
    try:
        return json.loads(raw, parse_constant=invalid_constant)
    except RecursionError:
        # A small but deeply nested document must follow the same sanitized error path.
        raise ValueError("JSON nesting limit exceeded") from None
