"""确认指定 Nacos 2.x 实例上存在本地 AI 服务的命名空间。"""

import argparse
import json
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import ProxyHandler, build_opener


def ensure_namespace(base_url):
    url = base_url.rstrip("/") + "/v1/console/namespaces"
    client = build_opener(ProxyHandler({}))

    def exists():
        with client.open(url, timeout=10) as response:
            result = json.load(response)
        if result.get("code") != 200 or not isinstance(result.get("data"), list):
            raise ValueError("Nacos 返回了无效的命名空间列表")
        return any(item["namespace"] == "ai-local" for item in result["data"])

    if exists():
        return "ai-local 已存在。"

    form = urlencode({
        "customNamespaceId": "ai-local",
        "namespaceName": "ai-local",
        "namespaceDesc": "本地开发启动的 AI 服务",
    }).encode()
    try:
        with client.open(url, data=form, timeout=10) as response:
            response.read()
    except (URLError, TimeoutError):
        # 另一进程可能已创建空间，或创建已成功但响应丢失。
        if exists():
            return "已确认 ai-local 存在。"
        raise
    if not exists():
        raise RuntimeError("创建请求后仍未查到 ai-local")
    return "已恢复 ai-local。"


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base_url", help="Nacos 服务地址，包含 /nacos")
    try:
        print(ensure_namespace(parser.parse_args().base_url))
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        parser.exit(1, f"恢复 ai-local 失败：{error}\n")
