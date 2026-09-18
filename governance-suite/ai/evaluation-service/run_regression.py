#!/usr/bin/env python3
"""运行固定模块级回归集，保存本次测试证据及尚未覆盖的业务缺口。"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import uuid
import xml.etree.ElementTree as ET

AI_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_SUITE = Path(__file__).resolve().parent / 'regression/suite-v1.json'
DEFAULT_OUTPUT = Path(__file__).resolve().parent / 'target/regression-reports'
JAVA_CLASS = re.compile(r'[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*', re.ASCII)


def load_suite(path, ai_root):
    suite = json.loads(path.read_text(encoding='utf-8'))
    if not isinstance(suite, dict):
        raise ValueError('回归集必须是 JSON 对象')
    for key in ('id', 'version'):
        if not isinstance(suite.get(key), str) or not suite[key].strip():
            raise ValueError(f'{key} 必须为非空字符串')
    groups = suite.get('groups')
    gaps = suite.get('knownGaps', [])
    if not isinstance(groups, list) or not groups or not isinstance(gaps, list):
        raise ValueError('groups 必须为非空列表，knownGaps 必须为列表')
    ids = set()
    for item in groups + gaps:
        if not isinstance(item, dict) or not isinstance(item.get('id'), str) or not item['id'].strip():
            raise ValueError('分组和缺口 id 必须为非空字符串')
        if item['id'] in ids:
            raise ValueError('分组和缺口 id 不得重复')
        ids.add(item['id'])
        if not isinstance(item.get('description'), str) or not item['description'].strip():
            raise ValueError('分组和缺口必须有 description')
    for group in groups:
        module = group.get('module')
        if not isinstance(module, str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]*', module):
            raise ValueError('module 必须是 ai 下的模块名')
        directory = (ai_root / module).resolve()
        if directory.parent != ai_root.resolve() or not (directory / 'pom.xml').is_file():
            raise ValueError(f'模块不在 ai 内或缺少 pom.xml：{module}')
        tests = group.get('tests')
        if not isinstance(tests, list) or not tests or any(
                not isinstance(test, str) or not JAVA_CLASS.fullmatch(test) for test in tests):
            raise ValueError('tests 必须为 Java 测试类名列表，不接受方法选择或通配符')
        if len(set(tests)) != len(tests):
            raise ValueError('同一分组 tests 不得重复')
    datasets = suite.get('datasets', [])
    if not isinstance(datasets, list):
        raise ValueError('datasets 必须为列表')
    for dataset in datasets:
        if not isinstance(dataset, dict) or not isinstance(dataset.get('path'), str):
            raise ValueError('dataset 必须有 path')
        path = (ai_root / dataset['path']).resolve()
        if Path(dataset['path']).is_absolute() or not path.is_relative_to(ai_root.resolve()) or not path.is_file():
            raise ValueError('dataset 路径必须指向 ai 内存在的文件')
    suite.setdefault('knownGaps', [])
    return suite


def snapshot(directory):
    return {path.name: (path.stat().st_mtime_ns, hashlib.sha256(path.read_bytes()).hexdigest())
            for path in directory.glob('TEST-*.xml')}


def inspect_reports(directory, before, selected):
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    matched = set()
    issues = []
    fresh = snapshot(directory)
    for name, signature in fresh.items():
        if before.get(name) == signature:
            continue
        try:
            root = ET.parse(directory / name).getroot()
            class_name = root.get('name', '')
            matches = [test for test in selected if test == class_name or (
                '.' not in test and test == class_name.rsplit('.', 1)[-1])]
            if not matches:
                continue
            matched.update(matches)
            values = {key: int(root.get(key, '0')) for key in counts}
            if any(value < 0 for value in values.values()):
                raise ValueError('测试统计不能为负数')
            for key, value in values.items():
                counts[key] += value
            if values['tests'] == 0:
                issues.append(f'{class_name} 未执行测试')
        except (ET.ParseError, ValueError) as error:
            issues.append(f'报告无效 {name}：{error}')
    missing = sorted(set(selected) - matched)
    if missing:
        issues.append('缺少本次测试报告：' + ', '.join(missing))
    if counts['skipped']:
        issues.append('存在跳过测试，不能判定通过')
    return counts, issues


def run_group(group, index, args, output, ai_root):
    module = ai_root / group['module']
    report_dir = module / 'target/surefire-reports'
    log_path = output / f'group-{index:02d}.log'
    command = [args.maven]
    if not args.online:
        command.append('-o')
    command += ['-f', str(module / 'pom.xml'), '-Dtest=' + ','.join(group['tests'])]
    if args.arg_line:
        command.append('-DargLine=' + args.arg_line)
    command.append('test')
    result = {**group, 'command': command, 'log': log_path.name,
              'status': 'ERROR', 'returnCode': None, 'issues': [],
              'counts': dict(tests=0, failures=0, errors=0, skipped=0)}
    try:
        before = snapshot(report_dir)
        with log_path.open('w', encoding='utf-8') as log:
            process = subprocess.run(command, cwd=ai_root, stdout=log, stderr=subprocess.STDOUT,
                                     timeout=args.timeout, check=False)
        result['returnCode'] = process.returncode
        counts, issues = inspect_reports(report_dir, before, group['tests'])
        result.update(counts=counts, issues=issues)
        if counts['failures'] or counts['errors'] or counts['skipped']:
            result['status'] = 'FAIL'
        elif process.returncode == 0 and not issues and counts['tests'] > 0:
            result['status'] = 'PASS'
        elif process.returncode:
            result['issues'].append(f'Maven 返回非零状态：{process.returncode}')
    except (OSError, subprocess.TimeoutExpired) as error:
        result['issues'].append('执行失败：' + str(error))
        with log_path.open('a', encoding='utf-8') as log:
            log.write('\n执行失败：' + str(error) + '\n')
    return result


def markdown(report):
    lines = ['# 固定回归报告', '', f"回归集：{report['suiteId']} / {report['suiteVersion']}",
             f"运行时间：{report['startedAt']}", '',
             f"执行组通过：{report['executedGatePassed']}",
             f"约定范围完整：{report['scopeComplete']}",
             f"整体门禁通过：{report['gatePassed']}", '',
             '本报告验证固定离线测试；不证明真实模型回答质量或线上端到端链路通过。', '',
             '| 分组 | 状态 | 测试 | 失败 | 错误 | 跳过 | 日志 |',
             '| --- | --- | ---: | ---: | ---: | ---: | --- |']
    for group in report['groups']:
        count = group['counts']
        lines.append(f"| {group['id']} | {group['status']} | {count['tests']} | {count['failures']} | "
                     f"{count['errors']} | {count['skipped']} | [查看]({group['log']}) |")
    for group in report['groups']:
        for issue in group['issues']:
            lines += ['', f"- {group['id']}：{issue}"]
    lines += ['', '## 已知覆盖缺口', '']
    lines += [f"- {gap['id']}：{gap['description']}" for gap in report['knownGaps']] or ['无已登记缺口。']
    if report['allowKnownGaps']:
        lines += ['', '已启用 --allow-known-gaps：退出码仅检查执行组；已知缺口仍使整体门禁不通过。']
    return '\n'.join(lines) + '\n'


def main(argv=None, ai_root=AI_ROOT):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--suite', type=Path, default=DEFAULT_SUITE)
    parser.add_argument('--output-dir', type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument('--maven', default='mvn')
    parser.add_argument('--online', action='store_true', help='允许 Maven 在线解析依赖')
    parser.add_argument('--arg-line', help='测试 JVM 参数，例如 -javaagent 路径')
    parser.add_argument('--timeout', type=float, default=300, help='单组超时秒数')
    parser.add_argument('--list', action='store_true', help='仅显示回归集，不执行命令')
    parser.add_argument('--allow-known-gaps', action='store_true', help='退出码忽略已登记缺口，报告仍保留未通过门禁')
    args = parser.parse_args(argv)
    ai_root = ai_root.resolve()
    if not math.isfinite(args.timeout) or args.timeout <= 0:
        raise ValueError('--timeout 必须是大于 0 的有限数值')
    suite = load_suite(args.suite, ai_root)
    if args.list:
        print(json.dumps(suite, ensure_ascii=False, indent=2))
        return 0
    output = args.output_dir.resolve() / str(uuid.uuid4())
    output.mkdir(parents=True, exist_ok=False)
    manifest_bytes = args.suite.read_bytes()
    (output / 'suite.json').write_bytes(manifest_bytes)
    datasets = [{**dataset, 'sha256': hashlib.sha256((ai_root / dataset['path']).read_bytes()).hexdigest()}
                for dataset in suite.get('datasets', [])]
    report = {'manifestSha256': hashlib.sha256(manifest_bytes).hexdigest(), 'datasets': datasets,
              'runId': output.name, 'suiteId': suite['id'], 'suiteVersion': suite['version'],
              'startedAt': datetime.now(timezone.utc).isoformat(), 'groups': [],
              'knownGaps': suite['knownGaps'], 'allowKnownGaps': args.allow_known_gaps}
    for index, group in enumerate(suite['groups'], 1):
        result = run_group(group, index, args, output, ai_root)
        report['groups'].append(result)
        print(f"{group['id']}：{result['status']}", flush=True)
    report['executedGatePassed'] = all(group['status'] == 'PASS' for group in report['groups'])
    report['scopeComplete'] = not suite['knownGaps']
    report['gatePassed'] = report['executedGatePassed'] and report['scopeComplete']
    report['finishedAt'] = datetime.now(timezone.utc).isoformat()
    (output / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    (output / 'report.md').write_text(markdown(report), encoding='utf-8')
    print(f"报告：{output}\n整体门禁通过：{report['gatePassed']}")
    if args.allow_known_gaps and suite['knownGaps']:
        print('已忽略缺口对退出码的影响；整体门禁仍未通过，不代表完整业务链路通过。')
    if any(group['status'] == 'ERROR' for group in report['groups']):
        return 1
    return 0 if report['executedGatePassed'] and (report['scopeComplete'] or args.allow_known_gaps) else 2


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except (OSError, ValueError) as error:
        print('回归执行失败：' + str(error))
        raise SystemExit(1) from error
