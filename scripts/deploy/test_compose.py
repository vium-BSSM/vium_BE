from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[2]
REQUIRED_KEYS = (
    "DISPOSAL_AUTO_DISPOSE_ENABLED",
    "OTLP_METRICS_ENABLED",
    "OTLP_METRICS_URL",
    "OTLP_METRICS_AUTHORIZATION",
)


class ComposeTests(unittest.TestCase):
    def test_production_app_passes_required_environment_variables(self):
        compose = (ROOT / "compose.prod.yml").read_text(encoding="utf-8")
        app = re.search(r"^  app:\n(?:(?:^ {4,}.*|^\s*)\n)*", compose, re.MULTILINE)
        self.assertIsNotNone(app, "compose.prod.yml is missing app")
        environment = re.search(
            r"^    environment:\n((?:^ {6,}.*\n)*)", app.group(), re.MULTILINE
        )
        self.assertIsNotNone(environment, "app is missing environment")
        for key in REQUIRED_KEYS:
            with self.subTest(key=key):
                self.assertRegex(
                    environment.group(1), rf"(?m)^\s{{6}}{key}:",
                    f"app.environment is missing {key}",
                )

    def test_production_env_example_documents_required_variables(self):
        example = (ROOT / ".env.prod.example").read_text(encoding="utf-8")
        for key in REQUIRED_KEYS:
            with self.subTest(key=key):
                self.assertRegex(example, rf"(?m)^{key}=", f".env.prod.example is missing {key}")


if __name__ == "__main__":
    unittest.main()
