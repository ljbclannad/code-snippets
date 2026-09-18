"""固定回归运行器测试；使用临时报告和模拟 Maven，不调用外部服务。"""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import run_regression as runner


class RegressionRunnerTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = self.root / 'chat'
        self.module.mkdir()
        (self.module / 'pom.xml').write_text('<project/>')
        self.reports = self.module / 'target/surefire-reports'
        self.reports.mkdir(parents=True)
        self.suite = {'id': 'business', 'version': 'v1', 'groups': [
            {'id': 'route', 'description': '路由', 'module': 'chat', 'tests': ['RouteTest']}], 'knownGaps': []}
        self.manifest = self.root / 'suite.json'
        self.output = self.root / 'output'

    def write_report(self, tests=2, failures=0, errors=0, skipped=0):
        (self.reports / 'TEST-example.RouteTest.xml').write_text(
            f'<testsuite name="example.RouteTest" tests="{tests}" failures="{failures}" '
            f'errors="{errors}" skipped="{skipped}"/>')

    def execute(self, effect, *options):
        self.manifest.write_text(json.dumps(self.suite))
        with patch.object(runner.subprocess, 'run', side_effect=effect) as command, contextlib.redirect_stdout(io.StringIO()):
            code = runner.main(['--suite', str(self.manifest), '--output-dir', str(self.output), *options], self.root)
        reports = sorted(self.output.glob('*/report.json'), key=lambda p: p.stat().st_mtime_ns)
        return code, json.loads(reports[-1].read_text()) if reports else None, command

    def successful(self, *args, **kwargs):
        self.write_report()
        return subprocess.CompletedProcess(args[0], 0)

    def test_success_saves_fresh_report_manifest_and_offline_command(self):
        code, report, command = self.execute(self.successful)
        self.assertEqual(0, code)
        self.assertTrue(report['gatePassed'])
        self.assertEqual(2, report['groups'][0]['counts']['tests'])
        self.assertIn('-o', command.call_args.args[0])
        self.assertIn('-Dtest=RouteTest', command.call_args.args[0])
        self.assertEqual(64, len(report['manifestSha256']))
        self.assertEqual(self.manifest.read_bytes(), next(self.output.glob('*/suite.json')).read_bytes())

    def test_failed_tests_return_two(self):
        def fail(*args, **kwargs):
            self.write_report(failures=1)
            return subprocess.CompletedProcess(args[0], 1)
        code, report, _ = self.execute(fail)
        self.assertEqual(2, code)
        self.assertEqual('FAIL', report['groups'][0]['status'])

    def test_missing_and_stale_reports_are_errors(self):
        for stale in (False, True):
            with self.subTest(stale=stale):
                if stale:
                    self.write_report()
                code, report, _ = self.execute(lambda *a, **k: subprocess.CompletedProcess(a[0], 0))
                self.assertEqual(1, code)
                self.assertFalse(report['gatePassed'])
                self.assertIn('缺少本次', report['groups'][0]['issues'][0])

    def test_skipped_and_zero_tests_cannot_pass(self):
        for values in ({'skipped': 1}, {'tests': 0}):
            def skipped(*args, **kwargs):
                self.write_report(**values)
                return subprocess.CompletedProcess(args[0], 0)
            code, report, _ = self.execute(skipped)
            self.assertNotEqual(0, code)
            self.assertFalse(report['executedGatePassed'])

    def test_known_gap_remains_visible_even_when_exit_code_allowed(self):
        self.suite['knownGaps'] = [{'id': 'approval', 'description': '拒绝审批尚未覆盖'}]
        for options, expected in (([], 2), (['--allow-known-gaps'], 0)):
            code, report, _ = self.execute(self.successful, *options)
            self.assertEqual(expected, code)
            self.assertTrue(report['executedGatePassed'])
            self.assertFalse(report['scopeComplete'])
            self.assertFalse(report['gatePassed'])
            self.assertEqual(self.suite['knownGaps'], report['knownGaps'])

    def test_timeout_does_not_prevent_later_groups(self):
        self.suite['groups'].append({**self.suite['groups'][0], 'id': 'retry'})
        calls = 0
        def effect(*args, **kwargs):
            nonlocal calls
            calls += 1
            if calls == 1:
                raise subprocess.TimeoutExpired(args[0], 1)
            return self.successful(*args, **kwargs)
        code, report, _ = self.execute(effect, '--timeout', '1')
        self.assertEqual(1, code)
        self.assertEqual(['ERROR', 'PASS'], [g['status'] for g in report['groups']])

    def test_online_and_arg_line_are_arguments_without_shell(self):
        code, _, command = self.execute(self.successful, '--online', '--arg-line=-javaagent:/tmp/agent.jar')
        self.assertEqual(0, code)
        self.assertNotIn('-o', command.call_args.args[0])
        self.assertIn('-DargLine=-javaagent:/tmp/agent.jar', command.call_args.args[0])
        self.assertNotIn('shell', command.call_args.kwargs)

    def test_list_does_not_execute_or_create_output(self):
        code, report, command = self.execute(self.successful, '--list')
        self.assertEqual(0, code)
        self.assertIsNone(report)
        command.assert_not_called()
        self.assertFalse(self.output.exists())

    def test_invalid_manifest_rejected_before_execution(self):
        for field, value in [('module', '../chat'), ('module', '/tmp'), ('tests', ['Test#method']),
                             ('tests', ['*Test']), ('tests', []), ('tests', ['T', 'T']), ('id', '')]:
            with self.subTest(field=field, value=value):
                original = self.suite['groups'][0][field]
                self.suite['groups'][0][field] = value
                with self.assertRaises(ValueError):
                    self.execute(self.successful)
                self.suite['groups'][0][field] = original
        self.suite['groups'].append(self.suite['groups'][0].copy())
        with self.assertRaisesRegex(ValueError, '重复'):
            self.execute(self.successful)

    def test_non_finite_timeout_is_rejected(self):
        for value in ('nan', 'inf', '0', '-1'):
            with self.assertRaisesRegex(ValueError, 'timeout'):
                self.execute(self.successful, '--timeout', value)

    def test_datasets_are_hashed_and_restricted(self):
        (self.root / 'dataset.json').write_text('{}')
        self.suite['datasets'] = [{'path': 'dataset.json', 'description': '固定样本'}]
        code, report, _ = self.execute(self.successful)
        self.assertEqual(0, code)
        self.assertEqual(64, len(report['datasets'][0]['sha256']))
        for invalid in ('missing.json', '../outside.json', '/tmp'):
            self.suite['datasets'][0]['path'] = invalid
            with self.assertRaises(ValueError):
                self.execute(self.successful)


if __name__ == '__main__':
    unittest.main()
