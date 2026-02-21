#!/usr/bin/env python3
"""Generate per-job summaries for Manus wide-research runtime outputs."""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from statistics import mean
from typing import Iterable, List, Optional

RUNTIME_ROOT = Path(__file__).resolve().parent.parent / "profiles" / "manus_wide_research" / "runtime"
JSON_BLOCK = re.compile(r"```json\s*(\{.*?\})\s*```", re.DOTALL)


@dataclass
class AgentReport:
    agent_id: str
    summary: str
    key_points: List[str]
    sources: List[dict]
    confidence: Optional[float]


def _load_agent_report(path: Path) -> Optional[AgentReport]:
    text = path.read_text(encoding="utf-8", errors="replace")

    match = JSON_BLOCK.search(text)
    if match:
        payload = match.group(1)
    else:
        payload = text

    try:
        data = json.loads(payload)
    except json.JSONDecodeError:
        return None

    agent_id = str(data.get("agent_id", path.stem))
    summary = str(data.get("summary", ""))
    key_points = [str(item) for item in data.get("key_points", [])]
    sources = [dict(item) for item in data.get("sources", []) if isinstance(item, dict)]
    confidence = data.get("confidence")
    if isinstance(confidence, (int, float)):
        confidence_value: Optional[float] = float(confidence)
    else:
        confidence_value = None

    return AgentReport(
        agent_id=agent_id,
        summary=summary.strip(),
        key_points=key_points,
        sources=sources,
        confidence=confidence_value,
    )


def _collect_job_reports(job_dir: Path) -> List[AgentReport]:
    reports: List[AgentReport] = []
    for path in sorted(job_dir.glob("agent_*.json")):
        report = _load_agent_report(path)
        if report is not None:
            reports.append(report)
    return reports


def _format_summary(job_dir: Path, reports: Iterable[AgentReport]) -> str:
    report_list = list(reports)
    if not report_list:
        return "# Manus Wide Research – {name}\n\n_No agent reports available._\n".format(name=job_dir.name)

    confidences = [r.confidence for r in report_list if r.confidence is not None]
    confidence_line = "Not available"
    if confidences:
        confidence_line = (
            f"min {min(confidences):.2f}, max {max(confidences):.2f}, "
            f"avg {mean(confidences):.2f}"
        )

    unique_points: List[str] = []
    seen_points = set()
    for report in report_list:
        for point in report.key_points:
            normalized = point.strip()
            if normalized and normalized not in seen_points:
                seen_points.add(normalized)
                unique_points.append(normalized)

    total_sources = sum(len(r.sources) for r in report_list)

    lines = [
        f"# Manus Wide Research – {job_dir.name}",
        "",
        f"- Generated: {datetime.now().isoformat()}",
        f"- Agent reports: {len(report_list)}",
        f"- Confidence stats: {confidence_line}",
        f"- Source references: {total_sources}",
    ]

    if unique_points:
        lines.append("- Aggregated key points:")
        lines.extend(f"  - {point}" for point in unique_points[:10])

    for report in report_list:
        lines.extend([
            "",
            f"## {report.agent_id}",
            "",
            report.summary or "(No summary provided)",
        ])

        if report.confidence is not None:
            lines.append(f"\n- Confidence: {report.confidence:.2f}")
        else:
            lines.append("\n- Confidence: N/A")

        if report.key_points:
            lines.append("- Key points:")
            lines.extend(f"  - {point}" for point in report.key_points)
        else:
            lines.append("- Key points: (not provided)")

        if report.sources:
            lines.append("- Sources:")
            for source in report.sources:
                title = source.get("title", "Untitled source")
                url = source.get("url")
                notes = source.get("notes", "")
                line = f"  - {title}"
                if url:
                    line += f" ({url})"
                if notes:
                    line += f" — {notes}"
                lines.append(line)
        else:
            lines.append("- Sources: (not provided)")

    lines.append("")
    return "\n".join(lines)


def generate_summaries(runtime_root: Path) -> List[Path]:
    created_files: List[Path] = []
    for job_dir in sorted(runtime_root.iterdir()):
        if not job_dir.is_dir():
            continue
        if not job_dir.name.startswith("wide_research_") and job_dir.name != "test_job":
            continue

        reports = _collect_job_reports(job_dir)
        summary = _format_summary(job_dir, reports)
        summary_path = job_dir / "summary.md"
        summary_path.write_text(summary, encoding="utf-8")
        created_files.append(summary_path)
    return created_files


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=RUNTIME_ROOT,
        help="Override runtime root directory",
    )
    args = parser.parse_args()

    if not args.root.exists():
        raise SystemExit(f"Runtime directory not found: {args.root}")

    created = generate_summaries(args.root)
    for path in created:
        print(f"Generated {path}")
    print(f"Processed {len(created)} job directories")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
