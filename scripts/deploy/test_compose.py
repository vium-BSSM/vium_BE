from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
REQUIRED_KEYS = (
    "AUTH_SESSION_CLEANUP_ENABLED",
    "DISPOSAL_AUTO_DISPOSE_ENABLED",
    "OTLP_METRICS_ENABLED",
    "OTLP_METRICS_URL",
    "OTLP_METRICS_AUTHORIZATION",
)


class ComposeTests(unittest.TestCase):
    def test_production_app_passes_required_environment_variables(self):
        compose = (ROOT / "compose.prod.yml").read_text(encoding="utf-8")
        path = []
        keys = set()
        for line in compose.splitlines():
            stripped = line.lstrip()
            if not stripped or stripped.startswith("#"):
                continue
            indent = len(line) - len(stripped)
            while path and path[-1][0] >= indent:
                path.pop()
            key, separator, value = stripped.partition(":")
            if not separator:
                continue
            if [entry[1] for entry in path] == ["services", "app", "environment"]:
                keys.add(key)
            if not value.strip():
                path.append((indent, key))
        self.assertFalse(set(REQUIRED_KEYS) - keys,
                         f"app.environment is missing {sorted(set(REQUIRED_KEYS) - keys)}")

    def test_production_env_example_documents_required_variables(self):
        example = (ROOT / ".env.prod.example").read_text(encoding="utf-8")
        for key in REQUIRED_KEYS:
            with self.subTest(key=key):
                self.assertRegex(example, rf"(?m)^{key}=", f".env.prod.example is missing {key}")


if __name__ == "__main__":
    unittest.main()
