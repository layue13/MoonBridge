#!/usr/bin/env python3
"""Run a StrataProxy performance profile and write an evidence JSON file.

This runner intentionally uses only the Python standard library so it can run on
bare Linux acceptance hosts after unpacking a StrataProxy release bundle.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import platform
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_RESULTS_DIR = Path(__file__).resolve().parent / "results"


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a StrataProxy performance profile.")
    parser.add_argument("profile", help="Profile id or JSON file path.")
    parser.add_argument("--profiles-dir", default=str(Path(__file__).resolve().parent / "profiles"))
    parser.add_argument("--output", default="", help="Result JSON path. Defaults to deployment/performance/results/<profile>-<timestamp>.json.")
    parser.add_argument("--query-bin", default="", help="Path to strataproxy-query executable.")
    parser.add_argument("--admin-bin", default="", help="Path to strataproxy-admin executable.")
    parser.add_argument("--admin-url", default="http://127.0.0.1:8080", help="Admin API base URL for observations.")
    parser.add_argument("--config", default="", help="Proxy config path to hash into the result.")
    parser.add_argument("--virtual-host", default="", help="Override virtual host arguments in profile commands.")
    parser.add_argument("--host", default="", help="Override strataproxy-query --host arguments.")
    parser.add_argument("--port", default="", help="Override strataproxy-query --port arguments.")
    parser.add_argument("--timeout-seconds", type=int, default=0, help="Per-command timeout. 0 disables timeout.")
    parser.add_argument("--dry-run", action="store_true", help="Write the planned result skeleton without executing commands.")
    args = parser.parse_args()

    profile_path = resolve_profile(args.profile, Path(args.profiles_dir))
    profile = read_json(profile_path)
    tool_paths = {
        "strataproxy-query": resolve_tool(args.query_bin, "strataproxy-query"),
        "strataproxy-admin": resolve_tool(args.admin_bin, "strataproxy-admin"),
    }
    commands = []
    started = time.monotonic()
    for command in profile.get("commands", []):
        commands.append(run_profile_command(command, tool_paths, args))

    observations = collect_observations(args.admin_url)
    gate_results = [] if args.dry_run else evaluate_gates(profile.get("gates", {}), commands, observations)
    passed = True if args.dry_run else all(item["passed"] for item in gate_results) and all(command["exitCode"] == 0 for command in commands)
    result = {
        "profileId": profile.get("id", ""),
        "strataproxyVersion": strataproxy_version(tool_paths["strataproxy-query"]),
        "gitRevision": git_revision(),
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
        "host": host_info(),
        "runtime": {
            "javaVersion": java_version(),
            "javaVendor": java_vendor(),
            "jvmOptions": profile.get("jvmOptions", []),
            "nativeTransport": native_transport(observations.get("overviewJson", {})),
            "configSha256": sha256_file(Path(args.config)) if args.config else "",
        },
        "profile": profile,
        "commands": commands,
        "observations": observations,
        "gateResults": gate_results,
        "elapsedMillis": int((time.monotonic() - started) * 1000),
        "passed": passed,
        "notes": "dry-run" if args.dry_run else "",
    }
    output_path = Path(args.output) if args.output else default_output_path(profile.get("id", profile_path.stem))
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(f"wrote {output_path}")
    return 0 if passed else 1


def resolve_profile(profile: str, profiles_dir: Path) -> Path:
    path = Path(profile)
    if path.exists():
        return path
    candidate = profiles_dir / f"{profile}.json"
    if candidate.exists():
        return candidate
    raise SystemExit(f"profile not found: {profile}")


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def resolve_tool(explicit: str, name: str) -> str:
    if explicit:
        return explicit
    found = shutil.which(name)
    if found:
        return found
    windows = os.name == "nt"
    installed = ROOT / ("proxy-query" if name.endswith("query") else "proxy-admin-cli") / "build" / "install"
    if name.endswith("query"):
        candidate = installed / "strataproxy-query" / "bin" / ("strataproxy-query.bat" if windows else "strataproxy-query")
    else:
        candidate = installed / "strataproxy-admin" / "bin" / ("strataproxy-admin.bat" if windows else "strataproxy-admin")
    return str(candidate)


def run_profile_command(command: dict[str, Any], tool_paths: dict[str, str], args: argparse.Namespace) -> dict[str, Any]:
    name = str(command.get("name", "unnamed"))
    tool = str(command.get("tool", ""))
    executable = tool_paths.get(tool, tool)
    argv = [executable] + rewrite_args([str(item) for item in command.get("args", [])], args)
    if args.dry_run:
        return {
            "name": name,
            "tool": tool,
            "argv": argv,
            "exitCode": 0,
            "stdout": "",
            "stdoutJson": {},
            "stderr": "",
            "elapsedMillis": 0,
        }
    started = time.monotonic()
    completed = subprocess.run(
        argv,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=args.timeout_seconds or None,
        check=False,
    )
    stdout = completed.stdout.strip()
    return {
        "name": name,
        "tool": tool,
        "argv": argv,
        "exitCode": completed.returncode,
        "stdout": stdout,
        "stdoutJson": parse_json(stdout),
        "stderr": completed.stderr.strip(),
        "elapsedMillis": int((time.monotonic() - started) * 1000),
    }


def rewrite_args(values: list[str], args: argparse.Namespace) -> list[str]:
    result = list(values)
    replace_option(result, "--host", args.host)
    replace_option(result, "--port", args.port)
    replace_option(result, "--base-url", args.admin_url)
    replace_option(result, "--virtual-host", args.virtual_host)
    return result


def replace_option(values: list[str], option: str, replacement: str) -> None:
    if not replacement:
        return
    for index, value in enumerate(values[:-1]):
        if value == option:
            values[index + 1] = replacement


def collect_observations(admin_url: str) -> dict[str, Any]:
    metrics = http_get(f"{admin_url.rstrip('/')}/metrics")
    overview = http_json(f"{admin_url.rstrip('/')}/overview")
    native = http_json(f"{admin_url.rstrip('/')}/native-capabilities")
    return {
        "metricsSnapshot": metrics,
        "overviewJson": overview,
        "nativeRuntimeJson": native,
        "sloOutput": "",
    }


def http_get(url: str) -> str:
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            return response.read().decode("utf-8", errors="replace")
    except (urllib.error.URLError, TimeoutError):
        return ""


def http_json(url: str) -> dict[str, Any]:
    body = http_get(url)
    return parse_json(body)


def parse_json(text: str) -> dict[str, Any]:
    try:
        parsed = json.loads(text)
        return parsed if isinstance(parsed, dict) else {}
    except json.JSONDecodeError:
        return {}


def evaluate_gates(gates: dict[str, Any], commands: list[dict[str, Any]], observations: dict[str, Any]) -> list[dict[str, Any]]:
    values = flatten_command_values(commands)
    overview = observations.get("overviewJson", {})
    if isinstance(overview, dict):
        values.setdefault("eventLoopDelayMillis", float(overview.get("eventLoopDelaySeconds", 0.0)) * 1000.0)
        values.setdefault("rejectedConnections", overview.get("rejectedConnections"))
        values.setdefault("packetAnomalies", overview.get("packetAnomalies"))
        values.setdefault("nativeTransport", overview.get("nativeTransport"))
    results = []
    for name, expected in gates.items():
        observed, passed = evaluate_gate(name, expected, values)
        results.append({
            "name": name,
            "expected": expected,
            "observed": observed,
            "passed": passed,
        })
    return results


def flatten_command_values(commands: list[dict[str, Any]]) -> dict[str, Any]:
    values: dict[str, Any] = {}
    for command in commands:
        collect_values(values, command.get("stdoutJson", {}))
    return values


def collect_values(values: dict[str, Any], payload: dict[str, Any]) -> None:
    if not payload:
        return
    command = payload.get("command", "")
    if command == "load-suite":
        values["idleConnections"] = max_number(values.get("idleConnections"), payload.get("idleConnections"))
        values["activeConnections"] = max_number(values.get("activeConnections"), payload.get("activeConnections"))
        for step in payload.get("steps", []):
            if isinstance(step, dict):
                collect_values(values, parse_json(str(step.get("output", ""))))
        return
    if "failed" in payload:
        values["failed"] = max_number(values.get("failed"), payload.get("failed"))
    if "packetsSent" in payload:
        values["trafficPackets"] = max_number(values.get("trafficPackets"), payload.get("packetsSent"))
    if "p99LatencyMillis" in payload and payload.get("latencySamples", 0):
        values["p99ProxyForwardingLatencyMillis"] = max_number(values.get("p99ProxyForwardingLatencyMillis"), payload.get("p99LatencyMillis"))


def max_number(current: Any, candidate: Any) -> Any:
    if candidate is None:
        return current
    if current is None:
        return candidate
    try:
        return max(float(current), float(candidate))
    except (TypeError, ValueError):
        return current


def evaluate_gate(name: str, expected: Any, values: dict[str, Any]) -> tuple[Any, bool]:
    mapping = {
        "minIdleConnections": ("idleConnections", ">="),
        "minActiveConnections": ("activeConnections", ">="),
        "minTrafficPackets": ("trafficPackets", ">="),
        "maxFailed": ("failed", "<="),
        "maxP99ProxyForwardingLatencyMillis": ("p99ProxyForwardingLatencyMillis", "<="),
        "maxEventLoopDelayMillis": ("eventLoopDelayMillis", "<="),
        "maxRejectedConnections": ("rejectedConnections", "<="),
        "maxPacketAnomalies": ("packetAnomalies", "<="),
        "requireNativeTransport": ("nativeTransport", "=="),
    }
    if name not in mapping:
        return "", False
    key, operator = mapping[name]
    observed = values.get(key)
    if observed is None:
        return "", False
    if operator == "==":
        return observed, bool(observed) == bool(expected)
    try:
        observed_float = float(observed)
        expected_float = float(expected)
    except (TypeError, ValueError):
        return observed, False
    if operator == ">=":
        return observed, observed_float >= expected_float
    return observed, observed_float <= expected_float


def host_info() -> dict[str, Any]:
    return {
        "os": platform.platform(),
        "kernel": platform.release(),
        "cpuModel": cpu_model(),
        "logicalCores": os.cpu_count() or 0,
        "memoryGiB": memory_gib(),
        "ulimitNofile": ulimit_nofile(),
    }


def cpu_model() -> str:
    if Path("/proc/cpuinfo").exists():
        for line in Path("/proc/cpuinfo").read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.lower().startswith("model name"):
                return line.split(":", 1)[1].strip()
    return platform.processor()


def memory_gib() -> int:
    if Path("/proc/meminfo").exists():
        for line in Path("/proc/meminfo").read_text(encoding="utf-8", errors="ignore").splitlines():
            if line.startswith("MemTotal:"):
                return round(int(line.split()[1]) / 1024 / 1024)
    return 0


def ulimit_nofile() -> int:
    if os.name == "nt":
        return 0
    try:
        import resource

        return int(resource.getrlimit(resource.RLIMIT_NOFILE)[0])
    except Exception:
        return 0


def java_version() -> str:
    completed = subprocess.run(["java", "-version"], text=True, stderr=subprocess.PIPE, stdout=subprocess.PIPE, check=False)
    return (completed.stderr or completed.stdout).splitlines()[0] if (completed.stderr or completed.stdout) else ""


def java_vendor() -> str:
    return os.environ.get("JAVA_HOME", "")


def strataproxy_version(query_bin: str) -> str:
    try:
        completed = subprocess.run([query_bin, "--version"], text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=5, check=False)
        return completed.stdout.strip() or completed.stderr.strip()
    except Exception:
        return ""


def git_revision() -> str:
    try:
        completed = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=5, check=False)
        return completed.stdout.strip()
    except Exception:
        return ""


def native_transport(overview: dict[str, Any]) -> bool:
    value = overview.get("nativeTransport")
    return bool(value) if value is not None else False


def sha256_file(path: Path) -> str:
    if not path.exists():
        return ""
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def default_output_path(profile_id: str) -> Path:
    timestamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "-" for ch in profile_id)
    return DEFAULT_RESULTS_DIR / f"{safe}-{timestamp}.json"


if __name__ == "__main__":
    sys.exit(main())
