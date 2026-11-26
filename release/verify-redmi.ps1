param(
    [string]$Serial = "",
    [string]$Apk = "",
    [string]$Server = "http://182.254.155.179:18080",
    [string]$Username = "",
    [string]$Password = "",
    [string]$DeviceId = ""
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    $candidates += "D:\dev\tools\android-sdk\platform-tools\adb.exe"
    $candidates += "adb.exe"
    $candidates += "adb"
    foreach ($c in $candidates) {
        try {
            $cmd = Get-Command $c -ErrorAction Stop
            return $cmd.Source
        } catch { }
    }
    throw "找不到 adb，请安装 Android platform-tools 或设置 ANDROID_HOME"
}

$adb = Find-Adb
if (-not $Apk) { $Apk = Join-Path $PSScriptRoot "where-are-u-1.0.1.apk" }

if (-not $Serial) {
    $devices = (& $adb devices) | Select-String '^\S+\s+device$' | ForEach-Object { ($_ -split '\s+')[0] }
    $Serial = $devices | Where-Object { $_ -ne "emulator-5554" } | Select-Object -First 1
    if (-not $Serial) { $Serial = $devices | Select-Object -First 1 }
}
if (-not $Serial) { throw "没有连接的 Android 设备，请先连接手机并开启 USB 调试" }

function A { $ErrorActionPreference = 'SilentlyContinue'; & $adb -s $Serial @args 2>$null }
function Get-AppPid { (A shell pidof com.whereareu.app) 2>$null }
function Get-UiXml {
    A shell uiautomator dump /sdcard/whereareu-verify.xml 2>$null | Out-Null
    A pull /sdcard/whereareu-verify.xml "$env:TEMP\whereareu-verify.xml" 2>$null | Out-Null
    return [System.IO.File]::ReadAllText("$env:TEMP\whereareu-verify.xml", [System.Text.Encoding]::UTF8)
}
function Tap-Tab([string]$text) {
    $xml = Get-UiXml
    $nodes = @(($xml -split '><') | Where-Object {
        $_ -match ('text="' + [regex]::Escape($text) + '"') -and
        $_ -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
    })
    if ($nodes.Count -eq 0) { return $false }
    $best = $null
    $bestY = -1
    foreach ($n in $nodes) {
        if ($n -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
            $y = [int]$matches[2]
            if ($y -gt $bestY) { $bestY = $y; $best = $n }
        }
    }
    if (-not $best) { return $false }
    if ($best -match 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        $x = [int](([int]$matches[1] + [int]$matches[3]) / 2)
        $y = [int](([int]$matches[2] + [int]$matches[4]) / 2)
        A shell input tap $x $y | Out-Null
        return $true
    }
    return $false
}

Write-Host "设备: $Serial"
if (-not (Test-Path $Apk)) { throw "APK 不存在: $Apk" }
Write-Host "安装: $Apk"
& $adb -s $Serial install -r $Apk
if ($LASTEXITCODE -ne 0) { throw "安装失败" }

$versionLine = (& $adb -s $Serial shell dumpsys package com.whereareu.app | Select-String 'versionName=' | Select-Object -First 1)
Write-Host "版本: $($versionLine.ToString().Trim())"
if ($versionLine -notmatch '1\.0\.1') { Write-Host "警告: 当前不是 1.0.1" -ForegroundColor Yellow }

foreach ($p in @(
    'android.permission.POST_NOTIFICATIONS',
    'android.permission.ACCESS_FINE_LOCATION',
    'android.permission.ACCESS_COARSE_LOCATION',
    'android.permission.ACCESS_BACKGROUND_LOCATION'
)) {
    & $adb -s $Serial shell pm grant com.whereareu.app $p 2>$null | Out-Null
}
& $adb -s $Serial shell dumpsys deviceidle whitelist +com.whereareu.app 2>$null | Out-Null
& $adb -s $Serial logcat -c -b crash 2>$null | Out-Null
& $adb -s $Serial shell am force-stop com.whereareu.app | Out-Null
& $adb -s $Serial shell am start -n com.whereareu.app/.MainActivity | Out-Null
Start-Sleep -Seconds 7

$pid0 = Get-AppPid
if (-not $pid0) { throw "App 未启动，请检查是否闪退（adb logcat -d -b crash）" }
Write-Host "启动 PID: $pid0"

$failed = $false
foreach ($tab in @('家庭', '设置', '地图')) {
    $before = Get-AppPid
    $found = Tap-Tab $tab
    Start-Sleep -Seconds 2
    $after = Get-AppPid
    $crashText = (& $adb -s $Serial logcat -d -b crash -t 100 2>$null) -join "`n"
    $crashed = $crashText -match 'com\.whereareu\.app'
    $restarted = ($before -ne $after)
    if (-not $found -or $crashed -or $restarted -or -not $after) {
        Write-Host "Tab ${tab}: found=$found pid=$before -> $after crash=$crashed restarted=$restarted" -ForegroundColor Red
        $failed = $true
    } else {
        Write-Host "Tab ${tab}: OK (pid=$after, 无崩溃)" -ForegroundColor Green
    }
    A shell screencap -p "/sdcard/whereareu-$tab.png" | Out-Null
    A pull "/sdcard/whereareu-$tab.png" "$env:TEMP\whereareu-$tab.png" 2>$null | Out-Null
}

if ($Username -and $Password) {
    Write-Host "立即定位: $Username"
    $loginBody = @{ username = $Username; password = $Password } | ConvertTo-Json
    $login = Invoke-RestMethod -Uri "$Server/api/auth/login" -Method Post -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($loginBody))
    $headers = @{ Authorization = "Bearer $($login.token)" }
    if (-not $DeviceId) {
        $DeviceId = (Invoke-RestMethod -Uri "$Server/api/devices" -Headers $headers).devices |
            Where-Object { $_.online } | Select-Object -First 1 -ExpandProperty id
    }
    if ($DeviceId) {
        $cmd = Invoke-RestMethod -Uri "$Server/api/devices/$DeviceId/locate" -Method Post -ContentType 'application/json' -Body '{}' -Headers $headers
        Write-Host "命令: $($cmd.commandId)"
        $status = 'pending'
        for ($i = 0; $i -lt 15; $i++) {
            Start-Sleep -Seconds 2
            $st = Invoke-RestMethod -Uri "$Server/api/commands/$($cmd.commandId)" -Headers $headers
            $status = $st.status
            if ($status -ne 'pending') { break }
        }
        if ($status -eq 'completed') {
            Write-Host "立即定位: completed" -ForegroundColor Green
        } else {
            Write-Host "立即定位: $status" -ForegroundColor Red
            $failed = $true
        }
    } else {
        Write-Host "没有在线设备，跳过立即定位" -ForegroundColor Yellow
    }
}

if ($failed) {
    Write-Host "验收失败：请把上面的输出和 $env:TEMP\whereareu-*.png 发给开发者。" -ForegroundColor Red
    exit 1
}
Write-Host "验收通过：家庭/设置/地图无崩溃。" -ForegroundColor Green
exit 0
