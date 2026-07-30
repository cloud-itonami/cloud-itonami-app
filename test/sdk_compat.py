import asyncio
import os

import anthropic
import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from openai import OpenAI


BASE_URL = os.environ.get("SDK_FIXTURE_URL", "http://127.0.0.1:18473")


def verify_openai() -> None:
    client = OpenAI(api_key="fixture", base_url=f"{BASE_URL}/v1")
    completion = client.chat.completions.create(
        model="fixture-model",
        messages=[{"role": "user", "content": "compatibility"}],
    )
    assert completion.choices[0].message.content == "cloud-itonami-sdk-ok"

    streamed = "".join(
        chunk.choices[0].delta.content or ""
        for chunk in client.chat.completions.create(
            model="fixture-model",
            messages=[{"role": "user", "content": "stream"}],
            stream=True,
        )
        if chunk.choices
    )
    assert streamed == "cloud-itonami-sdk-ok"

    response = client.responses.create(
        model="fixture-model", input="responses compatibility"
    )
    assert "cloud-itonami-sdk-ok" in response.output_text


def verify_anthropic() -> None:
    client = anthropic.Anthropic(
        api_key="fixture", base_url=BASE_URL
    )
    message = client.messages.create(
        model="fixture-model",
        max_tokens=32,
        messages=[{"role": "user", "content": "compatibility"}],
    )
    assert message.content[0].text == "cloud-itonami-sdk-ok"

    with client.messages.stream(
        model="fixture-model",
        max_tokens=32,
        messages=[{"role": "user", "content": "stream"}],
    ) as stream:
        assert stream.get_final_text() == "cloud-itonami-sdk-ok"


async def verify_mcp() -> None:
    headers = {"Authorization": "Bearer fixture-mcp-token"}
    async with httpx.AsyncClient(headers=headers) as http_client:
        async with streamable_http_client(
            f"{BASE_URL}/mcp", http_client=http_client
        ) as (read_stream, write_stream, _):
            async with ClientSession(read_stream, write_stream) as session:
                initialized = await session.initialize()
                assert initialized.serverInfo.name == "cloud-itonami"
                tools = await session.list_tools()
                assert any(
                    tool.name == "workspace_snapshot" for tool in tools.tools
                )


if __name__ == "__main__":
    verify_openai()
    verify_anthropic()
    asyncio.run(verify_mcp())
    print("upstream SDK compatibility: ok")
