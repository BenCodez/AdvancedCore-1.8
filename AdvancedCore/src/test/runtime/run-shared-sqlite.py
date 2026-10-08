#!/usr/bin/env python3
"""Manual Java 8 / Spigot 1.8.8 acceptance; output stays in the isolated workspace."""
import hashlib
import json
from pathlib import Path
import shutil
import socket
import sqlite3
import subprocess
import sys
import time
import uuid
import zipfile
import yaml

workspace = Path(sys.argv[1]).resolve()
artifact = workspace / "work/VotingPlugin-1.8/VotingPlugin/target/VotingPlugin.jar"
java = workspace / "tools/jdk8u504-b01/bin/java"
server_jar = workspace / "runtime/spigot-build/spigot-1.8.8.jar"
identity = uuid.uuid4().hex[:10]
fixture = workspace / "runtime" / ("spigot-shared-sqlite-" + identity)
fixture.mkdir()
classes = fixture / "probe-classes"
classes.mkdir()
source = Path(__file__).with_name("SharedSqliteAcceptance.java")
subprocess.run([str(java.with_name("javac")), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                "-cp", str(artifact) + ":" + str(server_jar), "-d", str(classes), str(source)], check=True)
(classes / "plugin.yml").write_text("name: SharedSqliteAcceptance\nversion: 1\nmain: SharedSqliteAcceptance\ndepend: [VotingPlugin]\ncommands:\n  sharedsqliteacceptance: {}\n")
plugins = fixture / "plugins"
plugins.mkdir()
subprocess.run([str(java.with_name("jar")), "cf", str(plugins / "SharedSqliteAcceptance.jar"), "-C", str(classes), "."], check=True)
shutil.copy2(artifact, plugins / "VotingPlugin.jar")
vp = plugins / "VotingPlugin"
vp.mkdir()
with zipfile.ZipFile(artifact) as jar:
    config = yaml.safe_load(jar.read("Config.yml").decode())
config.update({"DataStorage": "SQLITE", "OnlineMode": False, "AutoCreateVoteSites": False})
(vp / "Config.yml").write_text(yaml.safe_dump(config, sort_keys=False))
(fixture / "eula.txt").write_text("eula=true\n")
(fixture / "server.properties").write_text("server-ip=127.0.0.1\nserver-port=47237\nonline-mode=false\nlevel-type=FLAT\nallow-nether=false\nspawn-monsters=false\nview-distance=2\n")
with socket.socket() as port:
    port.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    port.bind(("127.0.0.1", 47237))
log = workspace / "evidence" / ("shared-sqlite-spigot-" + identity + ".log")
report = workspace / "evidence" / ("shared-sqlite-acceptance-" + identity + ".json")
result = {"fixture": str(fixture), "artifact_sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
          "outcome": "FAIL", "checks": {}, "limitations": "Shared backend explicitly exercised by acceptance plugin on worker. Does not prove native cache/shared runtime integration or outer reward crash recovery."}
server = None
stream = log.open("w")

def wait_for(marker):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        text = log.read_text(errors="replace")
        assert "SHARED SQLITE PROBE FAILED" not in text, "shared SQLite probe failed"
        if marker in text:
            return
        assert server.poll() is None, "server exited before " + marker
        time.sleep(0.2)
    raise AssertionError("timed out before " + marker)

try:
    server = subprocess.Popen([str(java), "-Djava.io.tmpdir=" + str(workspace / "runtime/tmp"), "-Xms256M", "-Xmx512M",
                               "-jar", str(server_jar), "nogui"], cwd=fixture, stdin=subprocess.PIPE,
                              stdout=stream, stderr=subprocess.STDOUT, text=True)
    wait_for("Done (")
    wait_for("shared-sqlite-probe-ready")
    server.stdin.write("sharedsqliteacceptance\n")
    server.stdin.flush()
    wait_for("shared-sqlite-probe-complete")
    for line in log.read_text().splitlines():
        if "shared-sqlite-pass:" in line:
            result["checks"][line.split("shared-sqlite-pass:", 1)[1]] = "PASS"
    assert len(result["checks"]) == 8
    database = plugins / "SharedSqliteAcceptance/Probe.db"
    with sqlite3.connect("file:" + str(database) + "?mode=ro", uri=True) as connection:
        assert connection.execute("PRAGMA integrity_check").fetchone() == ("ok",)
        assert connection.execute("SELECT Points, OfflineRewards FROM Users").fetchall() == [(17, "RewardA;;RewardB")]
        assert connection.execute("SELECT id FROM Receipts").fetchall() == [("occurrence-a",)]
    result["checks"]["independent-sqlite-integrity-and-retained-values"] = "PASS"
    result["outcome"] = "PASS"
finally:
    clean = False
    try:
        if server is not None and server.poll() is None:
            server.stdin.write("stop\n")
            server.stdin.flush()
            clean = server.wait(timeout=30) == 0
    finally:
        if server is not None and server.poll() is None:
            server.kill()
            server.wait(timeout=10)
        stream.close()
        text = log.read_text(errors="replace")
        clean = clean and "Disabling VotingPlugin" in text and not any(error in text for error in (
            "UnsupportedClassVersionError", "NoSuchMethodError", "NoClassDefFoundError", "Error occurred while disabling", "SHARED SQLITE PROBE FAILED"))
        if result["outcome"] == "PASS" and clean:
            result["checks"]["java8-linkage-and-clean-shutdown"] = "PASS"
        else:
            result["outcome"] = "FAIL"
        report.write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result), flush=True)
assert result["outcome"] == "PASS"
