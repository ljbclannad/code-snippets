#!/usr/bin/env python3
"""提交录制结果，保存独立报告；只访问显式指定的评测服务。"""
import argparse
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen


def request(base, path, payload=None):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode()
    req = Request(base.rstrip('/') + '/api/evaluation' + path, data=data,
                  headers={'Content-Type': 'application/json'})
    with urlopen(req, timeout=60) as response:
        body = response.read().decode('utf-8')
        return json.loads(body) if 'application/json' in response.headers.get('Content-Type', '') else body


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8104')
    parser.add_argument('--dataset', required=True, type=Path)
    parser.add_argument('--run', required=True, type=Path)
    parser.add_argument('--output-dir', type=Path, default=Path('target/evaluation-reports'))
    parser.add_argument('--baseline', help='用于对比的既有运行 ID')
    args = parser.parse_args()
    dataset = json.loads(args.dataset.read_text(encoding='utf-8'))
    run = json.loads(args.run.read_text(encoding='utf-8'))
    if (run.get('datasetId'), run.get('datasetVersion')) != (dataset['id'], dataset['version']):
        raise ValueError('运行请求引用的样本集与 --dataset 不一致')
    try:
        request(args.base_url, '/datasets', dataset)
    except HTTPError as error:
        if error.code != 409:
            raise
        existing = request(args.base_url, '/datasets/' + quote(dataset['id'], safe='')
                           + '/' + quote(dataset['version'], safe=''))
        if with_optional_defaults(existing) != with_optional_defaults(dataset):
            raise ValueError('服务器中同版本样本内容不同，请创建新版本') from error
    report = request(args.base_url, '/runs', run)
    output = args.output_dir / report['id']
    output.mkdir(parents=True, exist_ok=False)
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (output / 'report.md').write_text(request(args.base_url, '/runs/' + report['id'] + '/report'), encoding='utf-8')
    comparison = None
    if args.baseline:
        comparison = request(args.base_url, '/comparisons?' + urlencode({'baseline': args.baseline, 'candidate': report['id']}))
        (output / 'comparison.json').write_text(json.dumps(comparison, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'runId': report['id'], 'summary': report['summary'], 'outputDirectory': str(output.resolve()),
                      'comparison': comparison}, ensure_ascii=False, indent=2))
    summary = report['summary']
    return 0 if summary['failed'] == 0 and summary['errors'] == 0 and (comparison is None or comparison['gatePassed']) else 2


def with_optional_defaults(dataset):
    """只规范化协议的可选字段，保留业务 input 内 null 与缺失的区别。"""
    normalized = json.loads(json.dumps(dataset))
    normalized.setdefault('description', None)
    for sample in normalized.get('cases', []):
        for rule in sample.get('rules', []):
            rule.setdefault('expected', None)
            rule.setdefault('referencePath', None)
    return normalized


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (HTTPError, OSError, ValueError, KeyError) as error:
        # 不将完整远端错误响应（可能含运行输入）写入控制台。
        raise SystemExit('评测提交失败：' + str(error)) from error
