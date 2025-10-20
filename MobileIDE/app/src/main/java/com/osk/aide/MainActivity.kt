// app/src/main/java/com/osk/aide/MainActivity.kt
package com.osk.aide

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        IdeUi(
          onSavePat = { user, pat -> writeScriptsAndRun("setup", user, pat) },
          onCreateRepo = { repo -> writeScriptsAndRun("create", repo) },
          onRun = { repo -> writeScriptsAndRun("run", repo) },
          onOpenLogs = { openUrl(readLogsUrl()) }
        )
      }
    }
  }

  private fun writeScriptsAndRun(action: String, arg1: String, arg2: String = "") {
    // 1) Ensure Termux home scripts exist
    val home = "/data/data/com.termux/files/home"
    val dir = "$home/.aide"
    val scripts = mapOf(
      "$dir/setup.sh" to setupSh(),
      "$dir/create_repo.sh" to createRepoSh(),
      "$dir/run.sh" to runSh()
    )
    try {
      // Write scripts via app-side file I/O (Termux home is app-private to Termux;
      // for MVP we ask user to run Termux once so home is initialized).
      scripts.forEach { (path, content) ->
        Runtime.getRuntime().exec(arrayOf("sh","-c","mkdir -p $dir && printf '%s' \"$content\" > $path && chmod +x $path"))
      }
    } catch (_: Exception) {
      // If direct write fails due to sandboxing, fallback: open Termux and let first run create scripts.
    }

    // 2) Compose command per action
    val cmd = when (action) {
      "setup" -> "~/.aide/setup.sh $arg1 $arg2"
      "create" -> "~/.aide/create_repo.sh $arg1"
      "run" -> "~/.aide/run.sh $arg1"
      else -> ""
    }
    if (cmd.isNotBlank()) runTermuxCommand(cmd)
  }

  private fun runTermuxCommand(cmd: String) {
    val intent = Intent("com.termux.RUN_COMMAND")
      .putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash")
      .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", cmd))
      .putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
      .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
    try { startActivity(intent) } catch (_: Exception) {
      startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("termux://")))
    }
  }

  private fun openUrl(url: String) {
    if (url.isNotBlank()) startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
  }

  private fun readLogsUrl(): String {
    return try {
      val path = "/data/data/com.termux/files/home/.aide/latest_run_url.txt"
      val p = Runtime.getRuntime().exec(arrayOf("sh","-c","cat $path"))
      p.inputStream.bufferedReader().readText().trim()
    } catch (_: Exception) { "" }
  }

  // --- Script contents ---

  private fun setupSh() = """
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
mkdir -p "$HOME/.aide"
CONFIG="$HOME/.aide/config.json"
USER="${'$'}{1:-}"; PAT="${'$'}{2:-}"
if [ -z "${'$'}USER" ] || [ -z "${'$'}PAT" ]; then echo "Usage: setup.sh <user> <pat>" >&2; exit 1; fi
jq -n --arg user "${'$'}USER" --arg pat "${'$'}PAT" '{user:$user, pat:$pat}' > "${'$'}CONFIG"
chmod 600 "${'$'}CONFIG"
echo "Saved PAT for ${'$'}USER"
""".trimIndent()

  private fun createRepoSh() = """
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
API="https://api.github.com"
CONFIG="$HOME/.aide/config.json"
REPO="${'$'}{1:-}"
BRANCH="main"
if [ -z "${'$'}REPO" ]; then echo "Usage: create_repo.sh <repo-name>" >&2; exit 1; fi
USER=$(jq -r '.user' "${'$'}CONFIG") || { echo "Run setup first" >&2; exit 1; }
TOKEN=$(jq -r '.pat' "${'$'}CONFIG")
echo "[*] Creating public repo ${'$'}USER/${'$'}REPO..."
curl -s -X POST -H "Authorization: token ${'$'}TOKEN" -H "Accept: application/vnd.github+json" \
  -d "{\"name\":\"${'$'}REPO\",\"private\":false,\"auto_init\":false}" \
  "${'$'}API/user/repos" > /dev/null

upload() { local path="${'$'}1"; local msg="${'$'}2"; local content="${'$'}3"; local b64; b64=$(echo -n "${'$'}content" | base64 -w 0);
  curl -s -X PUT -H "Authorization: token ${'$'}TOKEN" -H "Accept: application/vnd.github+json" \
    -d "{\"message\":\"${'$'}msg\",\"content\":\"${'$'}b64\",\"branch\":\"${'$'}BRANCH\"}" \
    "${'$'}API/repos/${'$'}USER/${'$'}REPO/contents/${'$'}path" > /dev/null; echo "  [+] ${'$'}path"; }

BOOTSTRAP='name: Bootstrap
on: { push: { branches: [ main ] }, workflow_dispatch: {} }
jobs:
  init:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Seed project
        run: |
          mkdir -p app/src/main/java/com/example/hello app/src/main/res/values
          cat > settings.gradle.kts <<EOT
          rootProject.name = "HelloAndroid"
          include(":app")
          EOT
          cat > build.gradle.kts <<EOT
          plugins { id("com.android.application") version "8.7.0" apply false; kotlin("android") version "2.0.0" apply false }
          EOT
          cat > app/build.gradle.kts <<EOT
          plugins { id("com.android.application"); kotlin("android") }
          android {
            namespace = "com.example.hello"
            compileSdk = 34
            defaultConfig { applicationId = "com.example.hello"; minSdk = 24; targetSdk = 34; versionCode = 1; versionName = "1.0" }
            buildFeatures { compose = true }
            composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
          }
          dependencies {
            implementation(platform("androidx.compose:compose-bom:2024.09.02"))
            implementation("androidx.activity:activity-compose:1.9.2")
            implementation("androidx.compose.material3:material3:1.3.0")
            implementation("androidx.compose.ui:ui")
            implementation("androidx.compose.ui:ui-tooling-preview")
            implementation("androidx.core:core-ktx:1.13.1")
          }
          EOT
          cat > app/src/main/AndroidManifest.xml <<EOT
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application android:label="HelloAndroid">
              <activity android:name=".MainActivity">
                <intent-filter>
                  <action android:name="android.intent.action.MAIN" />
                  <category android:name="android.intent.category.LAUNCHER" />
                </intent-filter>
              </activity>
            </application>
          </manifest>
          EOT
          cat > app/src/main/java/com/example/hello/MainActivity.kt <<EOT
          package com.example.hello
          import android.os.Bundle
          import androidx.activity.ComponentActivity
          import androidx.activity.compose.setContent
          import androidx.compose.material3.*
          import androidx.compose.runtime.*
          class MainActivity : ComponentActivity() {
            override fun onCreate(savedInstanceState: Bundle?) {
              super.onCreate(savedInstanceState)
              setContent {
                MaterialTheme {
                  var text by remember { mutableStateOf("Hello, Android!") }
                  Button(onClick = { text = "Built on GitHub Actions!" }) { Text(text) }
                }
              }
            }
          }
          EOT
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - name: Commit bootstrap
        run: |
          if [ -n "$(git status --porcelain)" ]; then
            git config user.name "github-actions[bot]"
            git config user.email "github-actions[bot]@users.noreply.github.com"
            git add -A && git commit -m "Bootstrap" && git push
          fi'
BUILD='name: Build Android
on: { workflow_dispatch: {}, push: { branches: [ main ] } }
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: 17 }
      - uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${'${'} runner.os }-gradle-${'${'} hashFiles('**/*.gradle*','**/gradle-wrapper.properties') }
          restore-keys: ${'${'} runner.os }-gradle-
      - name: Build
        run: ./gradlew :app:assembleDebug
      - name: Upload APK
        uses: actions/upload-artifact@v4
        with: { name: app-debug, path: app/build/outputs/apk/debug/*.apk }'

upload ".github/workflows/bootstrap.yml" "Add bootstrap" "${'$'}BOOTSTRAP"
upload ".github/workflows/build.yml" "Add build" "${'$'}BUILD"
upload "settings.gradle.kts" "Seed settings" $'rootProject.name="HelloAndroid"\ninclude(":app")'

echo "[*] Dispatching bootstrap..."
curl -s -X POST -H "Authorization: token ${'$'}TOKEN" -H "Accept: application/vnd.github+json" \
  -d "{\"ref\":\"${'$'}BRANCH\"}" \
  "${'$'}API/repos/${'$'}USER/${'$'}REPO/actions/workflows/bootstrap.yml/dispatches" > /dev/null

echo "https://github.com/${'$'}USER/${'$'}REPO" > "$HOME/.aide/latest_repo_url.txt"
echo "https://github.com/${'$'}USER/${'$'}REPO/actions" > "$HOME/.aide/latest_actions_url.txt"
echo "[✓] Repo ready: https://github.com/${'$'}USER/${'$'}REPO"
""".trimIndent()

  private fun runSh() = """
#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
API="https://api.github.com"
CONFIG="$HOME/.aide/config.json"
REPO="${'$'}{1:-}"
BRANCH="main"
if [ -z "${'$'}REPO" ]; then echo "Usage: run.sh <repo-name>" >&2; exit 1; fi
USER=$(jq -r '.user' "${'$'}CONFIG"); TOKEN=$(jq -r '.pat' "${'$'}CONFIG")

echo "[*] Dispatching build workflow..."
curl -s -X POST -H "Authorization: token ${'$'}TOKEN" -H "Accept: application/vnd.github+json" \
  -d "{\"ref\":\"${'$'}BRANCH\"}" \
  "${'$'}API/repos/${'$'}USER/${'$'}REPO/actions/workflows/build.yml/dispatches" > /dev/null

sleep 2
RUNS=$(curl -s -H "Authorization: token ${'$'}TOKEN" -H "Accept: application/vnd.github+json" \
  "${'$'}API/repos/${'$'}USER/${'$'}REPO/actions/workflows/build.yml/runs?per_page=1")
URL=$(echo "${'$'}RUNS" | jq -r '.workflow_runs[0].html_url')
echo "${'$'}URL" > "$HOME/.aide/latest_run_url.txt"
echo "[*] Logs: ${'$'}URL"

for i in $(seq 1 40); do
  RUNS=$(curl -s -H "Authorization: token ${'$'}TOKEN" -H "Accept: application/vnd.github+json" \
    "${'$'}API/repos/${'$'}USER/${'$'}REPO/actions/workflows/build.yml/runs?per_page=1")
  STATUS=$(echo "${'$'}RUNS" | jq -r '.workflow_runs[0].status')
  CONCLUSION=$(echo "${'$'}RUNS" | jq -r '.workflow_runs[0].conclusion')
  echo "  status=${'$'}STATUS conclusion=${'$'}{CONCLUSION:-none}"
  [ "${'$'}STATUS" = "completed" ] && break
  sleep 5
done

ARTS=$(curl -s -H "Authorization: token ${'$'}TOKEN" -H "Accept: application/vnd.github+json" \
  "${'$'}API/repos/${'$'}USER/${'$'}REPO/actions/artifacts")
ID=$(echo "${'$'}ARTS" | jq -r '.artifacts[0].id')
NAME=$(echo "${'$'}ARTS" | jq -r '.artifacts[0].name')
if [ "${'$'}ID" != "null" ]; then
  OUT="$HOME/.aide/${'$'}REPO_artifact.zip"
  echo "[*] Downloading ${'$'}NAME (${ '$'}ID )..."
  curl -L -H "Authorization: token ${'$'}TOKEN" -H "Accept: application/vnd.github+json" \
    "${'$'}API/repos/${'$'}USER/${'$'}REPO/actions/artifacts/${'$'}ID/zip" -o "${'$'}OUT"
  echo "[✓] Saved ${'$'}OUT"
else
  echo "[!] No artifact found."
fi
""".trimIndent()
}

@Composable
fun IdeUi(
  onSavePat: (String, String) -> Unit,
  onCreateRepo: (String) -> Unit,
  onRun: (String) -> Unit,
  onOpenLogs: () -> Unit
) {
  var user by remember { mutableStateOf("") }
  var pat by remember { mutableStateOf("") }
  var repo by remember { mutableStateOf("") }

  Scaffold(
    topBar = { TopAppBar(title = { Text("AIDE Plus") }) },
    floatingActionButton = {
      ExtendedFloatingActionButton(text = { Text("Run") }, onClick = { if (repo.isNotEmpty()) onRun(repo) })
    }
  ) { pad ->
    Column(Modifier.padding(pad).padding(12.dp)) {
      OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text("GitHub Username") }, modifier = Modifier.fillMaxWidth())
      Spacer(Modifier.height(8.dp))
      OutlinedTextField(value = pat, onValueChange = { pat = it }, label = { Text("PAT (workflow + contents read/write)") }, modifier = Modifier.fillMaxWidth())
      Spacer(Modifier.height(8.dp))
      Button(onClick = { onSavePat(user, pat) }) { Text("Save PAT in Termux") }

      Spacer(Modifier.height(12.dp))
      OutlinedTextField(value = repo, onValueChange = { repo = it }, label = { Text("Repo name (public)") }, modifier = Modifier.fillMaxWidth())
      Spacer(Modifier.height(8.dp))
      Button(onClick = { if (repo.isNotEmpty()) onCreateRepo(repo) }) { Text("Create repo + bootstrap") }

      Spacer(Modifier.height(12.dp))
      TextButton(onClick = onOpenLogs) { Text("Open latest build logs") }
    }
  }
}
