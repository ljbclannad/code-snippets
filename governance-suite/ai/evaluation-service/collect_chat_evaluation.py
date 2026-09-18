#!/usr/bin/env python3
"""通过 ai-chat 执行固定测试问题，等待异步采集并导出评测报告。"""
import argparse
import json
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen
from run_evaluation import request, with_optional_defaults


def validate_dataset(dataset):
    cases = dataset.get('cases', [])
    if not cases:
        raise ValueError('样本集不能为空')
    for sample in cases:
        value = sample.get('input', {})
        if set(value) != {'message'} or not isinstance(value['message'], str) or not value['message'].strip():
            raise ValueError('首版自动采集仅支持 input 为非空 {"message":"..."} 的独立单轮样本')


def invoke_chat(url, payload, timeout):
    req = Request(url.rstrip('/') + '/api/chat', data=json.dumps(payload, ensure_ascii=False).encode(),
                  headers={'Content-Type': 'application/json'})
    try:
        with urlopen(req, timeout=timeout) as response:
            response.read()
            return response.status
    except HTTPError as error:
        # 服务错误也应被采集；不把原始响应、患者文本输出到控制台。
        return error.code
    except (URLError, TimeoutError):
        return 'TRANSPORT_ERROR'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--base-url', default='http://127.0.0.1:8104', help='评测服务地址')
    parser.add_argument('--chat-url', required=True, help='启用采集的独立测试 ai-chat 地址')
    parser.add_argument('--dataset', type=Path, required=True)
    parser.add_argument('--experiment', type=Path, required=True, help='实验版本 JSON，source 必须为 RECORDED_REAL')
    parser.add_argument('--repetitions', type=int, default=1, choices=range(1, 11))
    parser.add_argument('--max-calls', type=int, default=20)
    parser.add_argument('--chat-timeout', type=float, default=120)
    parser.add_argument('--capture-timeout', type=float, default=60)
    parser.add_argument('--group-id', default='evaluation-test')
    parser.add_argument('--output-dir', type=Path, default=Path('target/evaluation-reports'))
    args = parser.parse_args()
    dataset = json.loads(args.dataset.read_text(encoding='utf-8'))
    experiment = json.loads(args.experiment.read_text(encoding='utf-8'))
    validate_dataset(dataset)
    if len(dataset['cases']) * args.repetitions > args.max_calls or args.max_calls < 1:
        raise ValueError('预计调用次数超过 --max-calls；未发起业务请求')
    if not 0 < args.chat_timeout <= 300 or not 0 < args.capture_timeout <= 300:
        raise ValueError('超时必须大于 0 且不超过 300 秒')
    if experiment.get('source') != 'RECORDED_REAL':
        raise ValueError('采集实验必须声明 RECORDED_REAL 来源')
    try:
        request(args.base_url, '/datasets', dataset)
    except HTTPError as error:
        if error.code != 409:
            raise
        existing = request(args.base_url, '/datasets/' + quote(dataset['id'], safe='') + '/' + quote(dataset['version'], safe=''))
        if with_optional_defaults(existing) != with_optional_defaults(dataset):
            raise ValueError('样本版本已存在且内容不同') from error
    batch = request(args.base_url, '/batches', {'datasetId': dataset['id'], 'datasetVersion': dataset['version'],
                                              'repetitions': args.repetitions, 'experiment': experiment})
    output = args.output_dir / ('batch-' + batch['id'])
    output.mkdir(parents=True, exist_ok=False)
    # 在业务调用前保留批次标识；脚本中断后可查询或导出已有观测。
    (output / 'batch.json').write_text(json.dumps(batch, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'batchId': batch['id'], 'expectedObservations': batch['expectedObservations']}, ensure_ascii=False), flush=True)
    invocations = []
    for sample in dataset['cases']:
        for trial in range(1, args.repetitions + 1):
            identity = batch['id'] + '-' + sample['id'] + '-' + str(trial)
            status = invoke_chat(args.chat_url, {'message': sample['input']['message'], 'groupId': args.group_id,
                                'requestId': 'eval-' + identity, 'conversationId': 'eval-' + identity,
                                'evaluation': {'batchId': batch['id'], 'caseId': sample['id'], 'trial': trial}}, args.chat_timeout)
            invocations.append({'caseId': sample['id'], 'trial': trial, 'httpStatus': status})
            (output / 'invocations.json').write_text(json.dumps(invocations, ensure_ascii=False, indent=2), encoding='utf-8')
    deadline = time.monotonic() + args.capture_timeout
    while True:
        batch = request(args.base_url, '/batches/' + batch['id'])
        if batch['status'] in ('READY', 'FINALIZED') or time.monotonic() >= deadline:
            break
        time.sleep(min(1, max(0, deadline - time.monotonic())))
    complete = batch['receivedObservations'] == batch['expectedObservations']
    report = request(args.base_url, '/batches/' + batch['id'] + ('/finalize' if complete else '/report'), {} if complete else None)
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    if complete:
        markdown = request(args.base_url, '/runs/' + report['id'] + '/report')
    else:
        markdown = '# 采集未完成\n\n批次：' + batch['id'] + '\n\n已收到 ' + str(batch['receivedObservations']) \
                   + '/' + str(batch['expectedObservations']) + ' 条观测。缺失观测已在 report.json 中记为 ERROR，未创建正式运行。\n'
    (output / 'report.md').write_text(markdown, encoding='utf-8')
    print(json.dumps({'batchId': batch['id'], 'finalized': complete, 'summary': report['summary'],
                      'outputDirectory': str(output.resolve())}, ensure_ascii=False, indent=2))
    return 0 if complete and report['summary']['failed'] == 0 and report['summary']['errors'] == 0 else 2


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (HTTPError, OSError, ValueError, KeyError) as error:
        raise SystemExit('采集评测失败：' + str(error)) from error
