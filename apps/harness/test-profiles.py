#!/usr/bin/env python3
"""Offline contract checks through the image's real start-harness entry point."""
import json
import subprocess
import sys
import time
import uuid
from pathlib import Path

subprocess.run(["node", "--test", str(Path(__file__).with_name("test-chatgpt-models.mjs"))], check=True)

IMAGE = sys.argv[1] if len(sys.argv) == 2 else "workspaced-harness:provider-profiles"
PREFIX = "harness-profiles-" + uuid.uuid4().hex[:12]
VOLUME = PREFIX + "-home"
LOCATION = "?location%5Bdirectory%5D=%2Fhome%2Fworkspace%2Fproject"
MODELS = {"openrouter": "deepseek/deepseek-v4.1-flash", "openai": "gpt-6.1-sol", "openai-chatgpt": "workspaced-chatgpt"}
TITLES = {"openrouter": "anthropic/claude-haiku-5.5", "openai": "gpt-6.1-sol", "openai-chatgpt": "workspaced-chatgpt"}
containers = []


def docker(*args, check=True, input=None):
    return subprocess.run(["docker", *args], input=input, text=True, capture_output=True,
                          check=check, timeout=90)


def start(profile=None, persisted=False, credentials=True):
    name = PREFIX + "-" + str(len(containers))
    containers.append(name)
    home = ["--mount", f"type=volume,source={VOLUME},target=/home/workspace"] if persisted else [
        "--tmpfs", "/home/workspace:uid=1000,gid=1000"]
    args = ["run", "-d", "--name", name, "--platform", "linux/amd64", "--network", "none",
            "--read-only", "--tmpfs", "/tmp:exec", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", *home,
            "-e", "OPENCODE_SERVER_PASSWORD=profile-test",
            # Inert placeholders expose the catalog; no request can leave --network none.
            "-e", "OPENROUTER_BASE_URL=https://router.example.invalid/v1"]
    if credentials:
        args += ["-e", "OPENAI_API_KEY=not-a-real-key", "-e", "OPENROUTER_API_KEY=not-a-real-key"]
    if profile is not None:
        args += ["-e", "WORKSPACED_AI_PROVIDER=" + profile]
    docker(*args, IMAGE)
    return name


def api(name, path):
    return json.loads(docker("exec", name, "curl", "-fsS", "--max-time", "5",
                             "-u", "opencode:profile-test", "localhost:4096/api/" + path).stdout)


def ready(name):
    for _ in range(45):
        try:
            assert api(name, "info")["version"] == "2.2.5"
            return
        except (subprocess.CalledProcessError, json.JSONDecodeError):
            time.sleep(1)
    raise AssertionError("Server did not start: " + docker("logs", name).stdout)


def verify(name, profile):
    selected = profile
    profile = "openai" if selected == "openai-chatgpt" else selected
    ready(name)
    docs = [entry for entry in api(name, "config" + LOCATION) if entry["type"] == "document"]
    managed = [entry["info"] for entry in docs if entry.get("path") == "/etc/opencode/opencode.json"]
    # V2 emits separate policy and value documents for ONE managed source.
    assert len(managed) == 2, managed
    policies = next(doc["policies"] for doc in managed if "policies" in doc)
    assert policies == [{"action": "provider.use", "resource": "*", "effect": "deny"},
                        {"action": "provider.use", "resource": profile, "effect": "allow"}]
    values = next(doc for doc in managed if "model" in doc)
    assert values["model"] == {"providerID": profile, "model": MODELS[selected]}, values
    assert values["agents"]["title"]["model"] == {"providerID": profile, "model": TITLES[selected]}
    # Common defaults must occur only once, not again in a writable global config.
    instructions = [item for doc in docs for item in doc["info"].get("instructions", [])]
    assert instructions == ["/usr/share/harness/instructions/system.md",
                            "/home/workspace/.config/workspaced/instructions/*.md"], instructions
    if profile == "openrouter":
        provider = values["providers"]["openrouter"]
        assert provider["settings"]["baseURL"] == "https://router.example.invalid/v1"
        assert provider["headers"] == {"HTTP-Referer": "https://workspaced.online", "X-Title": "workspaced"}
    else:
        assert "openrouter" not in values.get("providers", {})
    models = api(name, "model" + LOCATION)["data"]
    assert {model["providerID"] for model in models} == {profile}
    assert any(model["id"] == MODELS[selected] for model in models), MODELS[selected]
    assert any(model["id"] == TITLES[selected] for model in models), TITLES[selected]
    default = api(name, "model/default" + LOCATION)["data"]
    assert (default["providerID"], default["id"]) == (profile, MODELS[selected]), default
    title = next(agent for agent in api(name, "agent" + LOCATION)["data"] if agent["id"] == "title")
    assert title["model"] == {"providerID": profile, "id": TITLES[selected]}, title["model"]
    print(f"PASS: {profile} managed policies, effective default/title models, provider catalog, single defaults source", flush=True)


def shell(name, script):
    return docker("exec", "-i", name, "sh", "-eu", input=script).stdout


def edit_home(name):
    # Lower-priority config tries to switch provider and both model choices. It must
    # remain on disk but cannot replace the active managed values or allowlist.
    override = json.dumps({"model": "anthropic/claude-sonnet-5-5",
                           "agents": {"title": {"model": "anthropic/claude-sonnet-5-5"}},
                           "policies": [{"action": "provider.use", "resource": "*", "effect": "allow"}]})
    return shell(name, '''mkdir -p "$HOME/.config/workspaced/instructions"
printf '%s\\n' keep > "$HOME/project/keep.txt"
printf '%s\\n' 'User instructions' > "$HOME/.config/workspaced/instructions/keep.md"
printf '%s\\n' '{"username":"keep-user-setting"}' > "$HOME/.config/opencode/opencode.jsonc"
cat > "$HOME/project/opencode.json" <<'JSON'
''' + override + '''
JSON
cp "$HOME/project/opencode.json" "$HOME/.config/opencode/opencode.json"
printf '%s\\n' '{"model":"anthropic/claude-sonnet-5-5"}' > /tmp/workspaced-opencode/opencode.json
printf '%s\\n' '{"model":"anthropic/claude-sonnet-5-5"}' > /tmp/workspaced-opencode/opencode.jsonc
cat > "$HOME/.config/workspaced/.env" <<'ENV'
WORKSPACED_AI_PROVIDER=invalid-from-user-env
OPENCODE_CONFIG_DIR=/home/workspace/project
OPENCODE_CONFIG=/home/workspace/project/opencode.json
OPENCODE_CONFIG_CONTENT={"model":"anthropic/claude-sonnet-5-5"}
PROFILE_TEST_VALUE=preserved
ENV
''')


def preserved(name):
    shell(name, '''test "$(cat "$HOME/project/keep.txt")" = keep
test "$(cat "$HOME/.config/workspaced/instructions/keep.md")" = 'User instructions'
test "$(cat "$HOME/.config/opencode/opencode.jsonc")" = '{"username":"keep-user-setting"}'
test -f "$HOME/project/opencode.json"
test ! -e "$HOME/.config/opencode/opencode.json"
test ! -e /tmp/workspaced-opencode/opencode.jsonc
''')
    # /proc/1 is tini; find the actual server process to observe its loaded environment.
    env = shell(name, '''for file in /proc/[0-9]*/environ; do
  [ -r "$file" ] || continue
  tr '\\000' '\\n' < "$file" | grep -E '^(PROFILE_TEST_VALUE|WORKSPACED_AI_PROVIDER|OPENCODE_CONFIG_DIR|OPENCODE_CONFIG|OPENCODE_CONFIG_CONTENT)=' || true
done
''')
    assert "PROFILE_TEST_VALUE=preserved" in env
    assert "invalid-from-user-env" not in env
    assert "OPENCODE_CONFIG_DIR=" not in env
    assert "OPENCODE_CONFIG=" not in env
    assert "OPENCODE_CONFIG_CONTENT=" not in env


try:
    name = start("openai-chatgpt", credentials=False)
    ready(name)
    for attempt in range(20):
        try:
            api(name, "integration/openai" + LOCATION)
            break
        except subprocess.CalledProcessError:
            time.sleep(1)
    else:
        raise AssertionError("ChatGPT setup endpoint never became available")
    docker("rm", "-f", name)
    print("PASS: credential-free ChatGPT profile starts and exposes connection setup", flush=True)
    for profile in [None, "openrouter", "openai", "openai-chatgpt"]:
        name = start(profile)
        verify(name, profile or "openrouter")
        docker("rm", "-f", name)
    for profile in ["invalid", ""]:
        name = start(profile)
        result = docker("wait", name)
        assert result.stdout.strip() == "2", result.stdout
        logs = docker("logs", name)
        assert "Unsupported WORKSPACED_AI_PROVIDER" in logs.stderr + logs.stdout
        print(f"PASS: invalid selector {profile!r} fails before server startup", flush=True)
        docker("rm", "-f", name)
    docker("volume", "create", VOLUME)
    docker("run", "--rm", "--platform", "linux/amd64", "--network", "none", "--user", "0",
           "--mount", f"type=volume,source={VOLUME},target=/home/workspace",
           "--entrypoint", "sh", IMAGE, "-c", "chown -R 1000:1000 /home/workspace")
    name = start("openrouter", persisted=True)
    verify(name, "openrouter")
    edit_home(name)
    docker("rm", "-f", name)
    # Successive starts share the edited home and switch both directions. A real
    # docker restart also has to reconstruct the managed file after edits.
    for profile in [None, "openai-chatgpt", "openai", "openrouter"]:
        name = start(profile, persisted=True)
        verify(name, profile or "openrouter")
        preserved(name)
        edit_home(name)
        docker("restart", "--time", "2", name)
        verify(name, profile or "openrouter")
        preserved(name)
        docker("rm", "-f", name)
    print("PASS: persisted-home profile switches, edited-config restarts, .env filtering, and user file preservation", flush=True)
except Exception:
    for name in containers:
        logs = docker("logs", name, check=False)
        if logs.returncode == 0:
            print(logs.stdout + logs.stderr, file=sys.stderr)
    raise
finally:
    for name in containers:
        docker("rm", "-f", name, check=False)
    docker("volume", "rm", VOLUME, check=False)
