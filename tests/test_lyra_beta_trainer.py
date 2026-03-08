import argparse
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts.ops import lyra_beta_trainer as lyra


class IntegrationMeshRetailReadinessTests(unittest.TestCase):
    def _runner_args(self) -> argparse.Namespace:
        return argparse.Namespace(
            package=lyra.DEFAULT_PACKAGE,
            serial=None,
            monkey_events=lyra.DEFAULT_MONKEY_EVENTS,
            monkey_seed=lyra.DEFAULT_MONKEY_SEED,
            skip_monkey_events=True,
            skip_python_bootstrap=True,
        )

    def _write_file(self, root: Path, relative_path: str, content: str) -> Path:
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def _build_fixture(self, root: Path) -> dict[str, object]:
        config_payload = (
            "{"
            '"smart_home_connectors":["smartthings"],'
            f'"wallet_setup_uri":"{lyra.EXPECTED_WALLET_SETUP_URI}"'
            "}"
        )
        python_payload = (
            "DEFAULT_SMART_HOME_CONNECTORS = ['smartthings']\n"
            f'DIGITAL_KEY_SETUP_URI = "{lyra.EXPECTED_WALLET_SETUP_URI}"\n'
        )
        smartthings_payload = "\n".join(lyra.SMARTTHINGS_SIMULATION_MARKERS)
        wording_files = {
            "strings.xml": "\n".join(lyra.INTEGRATION_MESH_WORDING_RULES[lyra.STRINGS_PATH]),
            "MainActivity.kt": "\n".join(lyra.INTEGRATION_MESH_WORDING_RULES[lyra.MAIN_ACTIVITY_PATH]),
            "tutorial.md": "\n".join(lyra.INTEGRATION_MESH_WORDING_RULES[lyra.TUTORIAL_DOC_PATH]),
            "policy.md": "\n".join(lyra.INTEGRATION_MESH_WORDING_RULES[lyra.POLICY_DISCLOSURE_DOC_PATH]),
            "pricing.md": "\n".join(lyra.INTEGRATION_MESH_WORDING_RULES[lyra.PRICING_PACKAGING_DOC_PATH]),
        }

        return {
            "SMART_HOME_CONFIG_PATHS": (
                self._write_file(root, "config/workspace_settings.json", config_payload),
                self._write_file(root, "android/assets/workspace_settings.json", config_payload),
            ),
            "INTEGRATION_MESH_CONFIG_PATH": self._write_file(
                root,
                "android/IntegrationMeshConfig.kt",
                lyra.EXPECTED_WALLET_SETUP_URI,
            ),
            "PYTHON_DEFAULT_CONFIG_PATH": self._write_file(
                root,
                "src/config.py",
                python_payload,
            ),
            "SMARTTHINGS_CONNECTOR_PATH": self._write_file(
                root,
                "android/SmartThingsConnector.kt",
                smartthings_payload,
            ),
            "STRINGS_PATH": self._write_file(root, "android/strings.xml", wording_files["strings.xml"]),
            "MAIN_ACTIVITY_PATH": self._write_file(root, "android/MainActivity.kt", wording_files["MainActivity.kt"]),
            "TUTORIAL_DOC_PATH": self._write_file(root, "docs/tutorial.md", wording_files["tutorial.md"]),
            "POLICY_DISCLOSURE_DOC_PATH": self._write_file(root, "docs/policy.md", wording_files["policy.md"]),
            "PRICING_PACKAGING_DOC_PATH": self._write_file(root, "docs/pricing.md", wording_files["pricing.md"]),
        }

    def test_simulation_backed_connector_passes_when_scope_is_honest(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture = self._build_fixture(Path(temp_dir))
            with patch.multiple(lyra, **fixture):
                runner = lyra.QaRunner(self._runner_args())
                runner._run_integration_mesh_retail_readiness()

        result = runner.results[-1]
        self.assertEqual("PASS", result.status)
        self.assertIn("simulation-backed", result.output_excerpt)
        self.assertIn("Validated wording alignment across 5 file(s).", result.output_excerpt)

    def test_partial_simulation_marker_drift_fails_readiness_gate(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            fixture = self._build_fixture(Path(temp_dir))
            smartthings_path = fixture["SMARTTHINGS_CONNECTOR_PATH"]
            assert isinstance(smartthings_path, Path)
            smartthings_path.write_text(lyra.SMARTTHINGS_SIMULATION_MARKERS[0], encoding="utf-8")

            with patch.multiple(lyra, **fixture):
                runner = lyra.QaRunner(self._runner_args())
                runner._run_integration_mesh_retail_readiness()

        result = runner.results[-1]
        self.assertEqual("FAIL", result.status)
        self.assertIn("mixed transition state", result.output_excerpt)


if __name__ == "__main__":
    unittest.main()
