param([string]$Serial='emulator-5554')
$ErrorActionPreference='Stop'
if (-not $env:ANDROID_HOME) { throw 'Set ANDROID_HOME to the Android SDK directory.' }
$adb=Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'
$project=Split-Path $PSScriptRoot
& (Join-Path $project 'gradlew.bat') -p $project assembleDebug assembleDebugAndroidTest testDebugUnitTest
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
& $adb -s $Serial install --no-streaming -r (Join-Path $project 'app\build\outputs\apk\debug\app-x86_64-debug.apk')
if ($LASTEXITCODE -ne 0) { throw 'App installation failed.' }
& $adb -s $Serial install -r (Join-Path $project 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk')
if ($LASTEXITCODE -ne 0) { throw 'Test installation failed.' }
& $adb -s $Serial shell input keyevent KEYCODE_WAKEUP
& $adb -s $Serial shell wm dismiss-keyguard
$result=& $adb -s $Serial shell am instrument -w kr.personal.tabletplayer.test/kr.personal.tabletplayer.SmokeRunner
$result
if (($result -join "`n") -notmatch 'PASS:') { throw 'Device smoke test did not pass; check system dialogs and output above.' }
