"""采集命令不应因输入文件错误而执行真实业务请求。"""
import json
from pathlib import Path
import tempfile
import io
import unittest
from unittest.mock import patch
import collect_chat_evaluation as collector


class CollectChatTest(unittest.TestCase):
    def test_orchestrates_calls_and_exports_complete_or_missing_capture_reports(self):
        for complete in (True, False):
            with self.subTest(complete=complete), tempfile.TemporaryDirectory() as directory:
                dataset = Path(directory) / 'dataset.json'
                experiment = Path(directory) / 'experiment.json'
                dataset.write_text(json.dumps({'id': 'sample', 'version': 'v1', 'cases': [
                    {'id': 'case1', 'input': {'message': '问题'}}]}))
                experiment.write_text(json.dumps({'source': 'RECORDED_REAL'}))
                batch = {'id': 'batch1', 'expectedObservations': 1, 'receivedObservations': int(complete),
                         'status': 'READY' if complete else 'COLLECTING'}
                report = {'id': 'run1', 'summary': {'failed': 0, 'errors': int(not complete)}}
                routes = []

                def server(base, path, payload=None):
                    routes.append(path)
                    if path == '/datasets':
                        return {}
                    if path.endswith('/finalize') or path == '/batches/batch1/report':
                        return report
                    if path == '/runs/run1/report':
                        return '# 合成验证报告'
                    return batch

                args = ['collector', '--dataset', str(dataset), '--experiment', str(experiment),
                        '--chat-url', 'http://test-chat', '--output-dir', directory, '--capture-timeout', '0.001']
                with patch('sys.argv', args), patch.object(collector, 'request', side_effect=server), \
                        patch.object(collector, 'invoke_chat', return_value=200) as chat, patch('sys.stdout', new_callable=io.StringIO):
                    self.assertEqual(0 if complete else 2, collector.main())
                    payload = chat.call_args.args[1]
                    self.assertEqual({'batchId': 'batch1', 'caseId': 'case1', 'trial': 1}, payload['evaluation'])
                    self.assertEqual('问题', payload['message'])
                self.assertEqual(complete, '/batches/batch1/finalize' in routes)
                output = Path(directory) / 'batch-batch1'
                self.assertTrue((output / 'report.md').exists())
                self.assertEqual(report, json.loads((output / 'report.json').read_text()))

    def test_rejects_unsupported_context_instead_of_silently_discarding_it(self):
        with self.assertRaises(ValueError):
            collector.validate_dataset({'cases': [{'input': {'message': '问题', 'history': ['历史']}}]})

    def test_call_budget_is_checked_before_any_network_or_business_request(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / 'dataset.json'
            experiment = Path(directory) / 'experiment.json'
            dataset.write_text(json.dumps({'cases': [{'input': {'message': '问题'}}]}))
            experiment.write_text(json.dumps({'source': 'RECORDED_REAL'}))
            with patch('sys.argv', ['collector', '--dataset', str(dataset), '--experiment', str(experiment),
                                   '--chat-url', 'http://127.0.0.1:8100', '--max-calls', '0']), \
                    patch.object(collector, 'request') as network, patch.object(collector, 'invoke_chat') as chat:
                with self.assertRaises(ValueError):
                    collector.main()
                network.assert_not_called()
                chat.assert_not_called()


if __name__ == '__main__':
    unittest.main()
