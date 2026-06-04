param(
    [string[]]$Codes = @("es", "vi", "tl", "pt", "hi", "ar")
)

$ErrorActionPreference = "Stop"

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
$root = Split-Path -Parent $PSScriptRoot
$captureRoot = Join-Path $root "chrome-language-captures-real"
$drawableRoot = Join-Path $root "app\src\main\res\drawable-nodpi"
New-Item -ItemType Directory -Force -Path $captureRoot | Out-Null

$locales = @{
    "en" = "en-US"
    "es" = "es-ES"
    "zh" = "zh-CN"
    "zh_tw" = "zh-TW"
    "ko" = "ko-KR"
    "vi" = "vi-VN"
    "tl" = "fil-PH"
    "fr" = "fr-FR"
    "pt" = "pt-BR"
    "hi" = "hi-IN"
    "ar" = "ar-SA"
}

Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Web

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Args)
    & $adb @Args
}

function Wait-DeviceReady {
    Invoke-Adb wait-for-device | Out-Null
    for ($i = 0; $i -lt 90; $i++) {
        $booted = (Invoke-Adb shell getprop sys.boot_completed).Trim()
        if ($booted -eq "1") { return }
        Start-Sleep -Seconds 1
    }
    throw "Device did not finish booting."
}

function Dump-Ui {
    param([string]$Path)
    Invoke-Adb shell uiautomator dump /sdcard/window.xml | Out-Null
    Invoke-Adb pull /sdcard/window.xml $Path | Out-Null
    return Get-Content -Raw -Encoding UTF8 $Path
}

function Get-Bounds {
    param([string]$Node)
    if ($Node -notmatch 'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        return $null
    }
    return [pscustomobject]@{
        Left = [int]$Matches[1]
        Top = [int]$Matches[2]
        Right = [int]$Matches[3]
        Bottom = [int]$Matches[4]
    }
}

function Get-NodeText {
    param([string]$Node)
    if ($Node -notmatch 'text="([^"]*)"') {
        return ""
    }
    return [System.Web.HttpUtility]::HtmlDecode($Matches[1])
}

function Tap-Bounds {
    param($Bounds)
    $x = [int](($Bounds.Left + $Bounds.Right) / 2)
    $y = [int](($Bounds.Top + $Bounds.Bottom) / 2)
    Invoke-Adb shell input tap $x $y | Out-Null
}

function Find-NodeByResourceId {
    param([string]$Xml, [string]$ResourceId)
    $escaped = [regex]::Escape($ResourceId)
    $matches = [regex]::Matches($Xml, "<node\b(?=[^>]*resource-id=`"$escaped`")[^>]*/?>")
    if ($matches.Count -eq 0) { return $null }
    return $matches[0].Value
}

function Dismiss-ChromeFirstRun {
    param([string]$WorkDir)
    for ($i = 0; $i -lt 4; $i++) {
        $xml = Dump-Ui (Join-Path $WorkDir "first_run_$i.xml")
        $dismiss = Find-NodeByResourceId $xml "com.android.chrome:id/signin_fre_dismiss_button"
        if ($dismiss -ne $null) {
            Tap-Bounds (Get-Bounds $dismiss)
            Start-Sleep -Seconds 2
            continue
        }
        $accept = Find-NodeByResourceId $xml "com.android.chrome:id/terms_accept"
        if ($accept -ne $null) {
            Tap-Bounds (Get-Bounds $accept)
            Start-Sleep -Seconds 2
            continue
        }
        return
    }
}

function Open-ChromeSettings {
    param([string]$WorkDir)
    Invoke-Adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main -d about:blank | Out-Null
    Start-Sleep -Seconds 3
    Dismiss-ChromeFirstRun $WorkDir

    $xml = Dump-Ui (Join-Path $WorkDir "browser.xml")
    $menu = Find-NodeByResourceId $xml "com.android.chrome:id/menu_button"
    if ($menu -eq $null) { throw "Chrome menu button was not found." }
    $menuBounds = Get-Bounds $menu
    Tap-Bounds $menuBounds
    Start-Sleep -Seconds 1

    $menuX = 810
    if ($menuBounds.Left -lt 200) {
        $menuX = 280
    }
    Invoke-Adb shell input swipe $menuX 1740 $menuX 760 400 | Out-Null
    Start-Sleep -Seconds 1
    $xml = Dump-Ui (Join-Path $WorkDir "menu.xml")
    $items = [regex]::Matches($xml, '<node\b(?=[^>]*resource-id="com.android.chrome:id/menu_item_text")[^>]*/?>')
    if ($items.Count -lt 2) { throw "Chrome menu items were not found." }
    $settingsNode = $items[$items.Count - 2].Value
    Tap-Bounds (Get-Bounds $settingsNode)
    Start-Sleep -Seconds 2
}

function Get-VisibleSettingTarget {
    param([string]$Xml)
    $nodes = @()
    foreach ($match in [regex]::Matches($Xml, '<node\b(?=[^>]*resource-id="android:id/title")[^>]*/?>')) {
        $node = $match.Value
        $bounds = Get-Bounds $node
        $text = Get-NodeText $node
        if ($bounds -ne $null -and $text.Length -gt 0 -and $bounds.Top -gt 180) {
            $nodes += [pscustomobject]@{ Text = $text; Bounds = $bounds }
        }
    }
    $nodes = $nodes | Sort-Object { $_.Bounds.Top }
    if ($nodes.Count -lt 4) {
        return $null
    }
    return $nodes[3]
}

function Capture-Screen {
    param([string]$Path)
    $remote = "/sdcard/chrome_capture.png"
    & $adb shell screencap -p $remote | Out-Null
    Start-Sleep -Milliseconds 250
    & $adb pull $remote $Path | Out-Null
}

function Draw-Highlight {
    param([string]$InputPath, [string]$OutputPath, $Bounds, [switch]$ExpandSettingRow)

    $bitmap = [System.Drawing.Bitmap]::new($InputPath)
    $graphics = [System.Drawing.Graphics]::FromImage($bitmap)
    $fill = [System.Drawing.SolidBrush]::new([System.Drawing.Color]::FromArgb(68, 255, 193, 7))
    $pen = [System.Drawing.Pen]::new([System.Drawing.Color]::FromArgb(255, 245, 158, 11), 5)

    if ($ExpandSettingRow) {
        $left = 18
        $right = $bitmap.Width - 18
        $top = [Math]::Max(0, $Bounds.Top - 48)
        $bottom = [Math]::Min($bitmap.Height - 1, $Bounds.Bottom + 54)
    } else {
        $left = [Math]::Max(0, $Bounds.Left)
        $right = [Math]::Min($bitmap.Width - 1, $Bounds.Right)
        $top = [Math]::Max(0, $Bounds.Top)
        $bottom = [Math]::Min($bitmap.Height - 1, $Bounds.Bottom)
    }

    $rect = [System.Drawing.Rectangle]::FromLTRB($left, $top, $right, $bottom)
    $graphics.FillRectangle($fill, $rect)
    $graphics.DrawRectangle($pen, $rect)
    $graphics.Dispose()
    $fill.Dispose()
    $pen.Dispose()
    $bitmap.Save($OutputPath, [System.Drawing.Imaging.ImageFormat]::Png)
    $bitmap.Dispose()
}

$results = @()

foreach ($code in $Codes) {
    $key = $code.Replace("-", "_")
    if (-not $locales.ContainsKey($key)) {
        throw "No locale configured for $code"
    }
    $locale = $locales[$key]
    $workDir = Join-Path $captureRoot $key
    New-Item -ItemType Directory -Force -Path $workDir | Out-Null

    Write-Host "Capturing Chrome for $key ($locale)"
    Invoke-Adb shell cmd locale set-app-locales com.android.chrome --locales $locale | Out-Null
    Invoke-Adb shell settings put system system_locales "$locale,en-US" | Out-Null
    Wait-DeviceReady
    Invoke-Adb shell am force-stop com.android.chrome | Out-Null
    Start-Sleep -Seconds 2

    Open-ChromeSettings $workDir

    $target = $null
    Invoke-Adb shell input swipe 540 1700 540 520 500 | Out-Null
    Start-Sleep -Seconds 1
    for ($i = 0; $i -lt 4; $i++) {
        $xml = Dump-Ui (Join-Path $workDir "settings_$i.xml")
        $target = Get-VisibleSettingTarget $xml
        if ($target -ne $null) { break }
        Invoke-Adb shell input swipe 540 1700 540 520 500 | Out-Null
        Start-Sleep -Seconds 1
    }
    if ($target -eq $null) { throw "Could not find Chrome Autofill services row for $key." }

    $rawSettings = Join-Path $workDir "settings.png"
    Capture-Screen $rawSettings
    $settingsDrawable = Join-Path $drawableRoot "chrome_settings_autofill_options_$key.png"
    Draw-Highlight $rawSettings $settingsDrawable $target.Bounds -ExpandSettingRow
    if ($key -eq "en") {
        Copy-Item $settingsDrawable (Join-Path $drawableRoot "chrome_settings_autofill_options.png") -Force
    }

    Tap-Bounds $target.Bounds
    Start-Sleep -Seconds 2
    $optionsXml = Dump-Ui (Join-Path $workDir "autofill_services.xml")
    $optionNode = Find-NodeByResourceId $optionsXml "com.android.chrome:id/autofill_third_party_filling_opt_in"
    if ($optionNode -eq $null) { throw "Could not find Chrome other-provider option for $key." }
    $optionBounds = Get-Bounds $optionNode

    $optionTitleNode = Find-NodeByResourceId $optionsXml "com.android.chrome:id/primary"
    $optionTitle = ""
    $primaryNodes = [regex]::Matches($optionsXml, '<node\b(?=[^>]*resource-id="com.android.chrome:id/primary")[^>]*/?>')
    if ($primaryNodes.Count -ge 2) {
        $optionTitle = Get-NodeText $primaryNodes[1].Value
    } elseif ($optionTitleNode -ne $null) {
        $optionTitle = Get-NodeText $optionTitleNode
    }

    $rawOptions = Join-Path $workDir "autofill_services.png"
    Capture-Screen $rawOptions
    $optionsDrawable = Join-Path $drawableRoot "chrome_autofill_options_actual_$key.png"
    Draw-Highlight $rawOptions $optionsDrawable $optionBounds
    if ($key -eq "en") {
        Copy-Item $optionsDrawable (Join-Path $drawableRoot "chrome_autofill_options_actual.png") -Force
    }

    $results += [pscustomobject]@{
        Code = $key
        Locale = $locale
        SettingsLabel = $target.Text
        OtherServiceLabel = $optionTitle
        SettingsResource = Split-Path -Leaf $settingsDrawable
        OptionsResource = Split-Path -Leaf $optionsDrawable
    }
}

$results | ConvertTo-Json -Depth 4 | Set-Content -Encoding UTF8 (Join-Path $captureRoot "localized-labels.json")
$results | Format-Table -AutoSize
