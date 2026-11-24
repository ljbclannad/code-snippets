"""校验从启动类和组合配置进入时使用同一个 IDEA 配置。"""

import unittest
from pathlib import Path
from xml.etree import ElementTree


PROJECT = Path(__file__).resolve().parents[2]
SPRING_BOOT = "SpringBootApplicationConfigurationType"
SERVICES = {
    "ai-chat-service": "AiChatServiceApplication",
    "user-profile-service": "UserProfileServiceApplication",
    "vector-memory-service": "VectorMemoryServiceApplication",
    "knowledge-base-service": "KnowledgeBaseServiceApplication",
    "doctor-assistant-agent": "DoctorAssistantAgentApplication",
    "evaluation-service": "EvaluationApplication",
}


class SingleServiceRunTest(unittest.TestCase):
    def test_single_and_compound_entries(self):
        configs = [ElementTree.parse(path).getroot().find("configuration")
                   for path in (PROJECT / ".run").glob("*.run.xml")]
        for module, name in SERVICES.items():
            with self.subTest(module=module):
                matches = [c for c in configs if c.get("type") == SPRING_BOOT
                           and c.find(f"module[@name='{module}']") is not None]
                self.assertEqual([c.get("name") for c in matches], [name])
                config = matches[0]
                main = config.find("option[@name='SPRING_BOOT_MAIN_CLASS']").get("value")
                self.assertEqual(main.split(".")[-1], name)
                self.assertTrue((PROJECT / "ai" / module / "src/main/java"
                                 / (main.replace(".", "/") + ".java")).is_file())
                loaders = config.findall("option[@name='envFilePaths']/option")
                expected = [] if module == "evaluation-service" else [
                    "$PROJECT_DIR$/sh/run/ai-middleware-env.sh"]
                self.assertEqual([entry.get("value") for entry in loaders], expected)

        compound = next(c for c in configs if c.get("name") == "AI 服务（全部调试）")
        self.assertCountEqual([(c.get("type"), c.get("name")) for c in compound.findall("toRun")],
                              [(SPRING_BOOT, name) for name in SERVICES.values()])


if __name__ == "__main__":
    unittest.main()
