"""命令行输入绑定回归，不访问网络。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import run_evaluation


class RunEvaluationTest(unittest.TestCase):
    def test_mismatched_dataset_is_rejected_before_network_request(self):
        with tempfile.TemporaryDirectory() as directory:
            dataset = Path(directory) / 'dataset.json'
            run = Path(directory) / 'run.json'
            dataset.write_text(json.dumps({'id': 'a', 'version': 'v1'}))
            run.write_text(json.dumps({'datasetId': 'b', 'datasetVersion': 'v1'}))
            with patch('sys.argv', ['run_evaluation.py', '--dataset', str(dataset), '--run', str(run)]), \
                    patch.object(run_evaluation, 'request') as network:
                with self.assertRaisesRegex(ValueError, '不一致'):
                    run_evaluation.main()
                network.assert_not_called()

    def test_optional_rule_defaults_do_not_erase_business_input_null(self):
        dataset = {'id': 'a', 'version': 'v1', 'cases': [{'input': {'value': None}, 'rules': [{'id': 'r'}]}]}
        normalized = run_evaluation.with_optional_defaults(dataset)
        self.assertIsNone(normalized['cases'][0]['rules'][0]['referencePath'])
        self.assertEqual({'value': None}, normalized['cases'][0]['input'])
        changed = {'id': 'a', 'version': 'v1', 'cases': [{'input': {}, 'rules': [{'id': 'r'}]}]}
        self.assertNotEqual(normalized, run_evaluation.with_optional_defaults(changed))


if __name__ == '__main__':
    unittest.main()
