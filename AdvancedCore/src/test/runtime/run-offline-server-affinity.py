"""Manual isolated Java 8/Spigot acceptance against the exact consumer artifact."""
import hashlib, json, pathlib, shutil, socket, subprocess, sys, time, uuid, zipfile
import yaml
root = pathlib.Path(sys.argv[1]).resolve()
nonce = uuid.uuid4().hex[:10]
fixture = root / 'runtime' / ('spigot-offline-affinity-' + nonce)
fixture.mkdir()
plugins = fixture / 'plugins'
plugins.mkdir()
vp = plugins / 'VotingPlugin'
vp.mkdir()
artifact = root / 'work/VotingPlugin-1.8/VotingPlugin/target/VotingPlugin.jar'
shutil.copy2(artifact, plugins / 'VotingPlugin.jar')
source = pathlib.Path(__file__).with_name('OfflineServerAffinityAcceptance.java')
classes = fixture / 'helper-classes'
classes.mkdir()
subprocess.run([str(root / 'tools/jdk8u504-b01/bin/javac'), '-source', '8', '-target', '8', '-cp',
                str(artifact) + ':' + str(root / 'runtime/spigot-build/spigot-1.8.8.jar'),
                '-d', str(classes), str(source)], check=True)
(classes / 'plugin.yml').write_text('name: OfflineServerAffinityAcceptance\nversion: 1\nmain: OfflineServerAffinityAcceptance\ndepend: [VotingPlugin]\ncommands:\n  affinityacceptance: {}\n')
with zipfile.ZipFile(plugins / 'AffinityAcceptance.jar', 'w') as jar:
    for file in classes.rglob('*'):
        if file.is_file(): jar.write(file, str(file.relative_to(classes)))
with zipfile.ZipFile(artifact) as jar: config = yaml.safe_load(jar.read('Config.yml').decode())
config.update({'DataStorage': 'SQLITE', 'OnlineMode': False, 'AutoCreateVoteSites': False})
(vp / 'Config.yml').write_text(yaml.safe_dump(config, sort_keys=False))
(fixture / 'eula.txt').write_text('eula=true\n')
(fixture / 'server.properties').write_text('server-ip=127.0.0.1\nserver-port=47237\nonline-mode=false\nlevel-type=FLAT\nallow-nether=false\nspawn-monsters=false\nview-distance=2\n')
with socket.socket() as probe: probe.bind(('127.0.0.1', 47237))
evidence = root / 'evidence'
log = evidence / ('offline-affinity-spigot-' + nonce + '.log')
client_log = evidence / ('offline-affinity-client-' + nonce + '.log')
result = {'fixture': str(fixture), 'artifact_sha256': hashlib.sha256(artifact.read_bytes()).hexdigest(),
          'outcome': 'FAIL', 'checks': {},
          'limitations': 'Live Bukkit Server requirement, checked SQLite queued recovery and explicit force. Not process-crash, MySQL or timed reward acceptance.'}
server = client = None
streams = []
def wait(test, label, seconds=35):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if 'AFFINITY ACCEPTANCE FAILED' in log.read_text(errors='replace'): raise AssertionError('Fixture failed: ' + label)
        if test(): return
        if server.poll() is not None: raise AssertionError('Server exited: ' + label)
        time.sleep(.2)
    raise AssertionError('Timed out: ' + label)
def command(text):
    server.stdin.write(text + '\n')
    server.stdin.flush()
try:
    stream = log.open('w'); streams.append(stream)
    server = subprocess.Popen([str(root / 'tools/jdk8u504-b01/bin/java'), '-Djava.io.tmpdir=' + str(root / 'runtime/tmp'),
                               '-Xms256M', '-Xmx512M', '-jar', str(root / 'runtime/spigot-build/spigot-1.8.8.jar'), 'nogui'],
                              cwd=fixture, stdin=subprocess.PIPE, stdout=stream, stderr=subprocess.STDOUT, text=True)
    wait(lambda: 'Done (' in log.read_text(), 'startup')
    stream = client_log.open('w'); streams.append(stream)
    client = subprocess.Popen(['node', str(root / 'tools/minecraft-client/overflow.cjs')],
                              cwd=root / 'tools/minecraft-client', stdout=stream, stderr=subprocess.STDOUT)
    wait(lambda: 'LOGIN' in client_log.read_text(), 'client login', 20)
    command('affinityacceptance OverflowOne')
    wait(lambda: 'affinity-acceptance-complete' in log.read_text(), 'queued reward recovery', 30)
    for marker in ['affinity-wrong-backend-retains-same-occurrence-without-effects',
                   'affinity-matching-backend-delivers-and-removes-once',
                   'affinity-explicit-force-preserves-override-and-durable-removal']:
        assert marker in log.read_text()
        result['checks'][marker] = 'PASS'
    result['outcome'] = 'PASS'
finally:
    if client and client.poll() is None: client.terminate(); client.wait(timeout=10)
    if server and server.poll() is None: command('stop'); assert server.wait(timeout=30) == 0
    for stream in streams: stream.close()
    if result['outcome'] == 'PASS':
        text = log.read_text()
        assert all(marker not in text for marker in ['UnsupportedClassVersionError', 'NoSuchMethodError',
                                                    'NoClassDefFoundError', 'Error occurred while disabling',
                                                    'AFFINITY ACCEPTANCE FAILED'])
        assert 'Disabling VotingPlugin' in text
        result['checks']['java8-linkage-and-clean-shutdown'] = 'PASS'
    (evidence / ('offline-affinity-runtime-' + nonce + '.json')).write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result), flush=True)
