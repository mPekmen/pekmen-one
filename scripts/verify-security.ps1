param(
    [switch]$SkipGitleaks
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
Push-Location $RepoRoot

try {
    Write-Host "==> [1/4] Checking forbidden tracked or unignored workspace files..." -ForegroundColor Cyan
    $tracked = @(git ls-files)
    $untracked = @(git ls-files --others --exclude-standard)
    $allFiles = @($tracked + $untracked | Select-Object -Unique)
    $forbiddenPattern = '(^|/)(local\.properties|\.env(\..*)?|supabase\.properties|secrets\.properties|google-services\.json)$|\.(jks|keystore|pepk|pem|key|p12|apk|aab)$'
    $forbidden = $allFiles | Where-Object { $_ -match $forbiddenPattern }
    if ($forbidden) {
        Write-Error ("Forbidden sensitive/binary files present in repository:`n" + ($forbidden -join "`n"))
    }
    Write-Host "OK: No forbidden files tracked or staged/unignored ($($allFiles.Count) files checked)." -ForegroundColor Green

    Write-Host "==> [2/4] Scanning repository files for common secret patterns..." -ForegroundColor Cyan
    $secretRegex = '(-----BEGIN (RSA|EC|OPENSSH|DSA|PGP) PRIVATE KEY-----|SUPABASE_SERVICE_ROLE_KEY\s*=\s*["'']?[A-Za-z0-9\._-]{20,}|eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,})'
    $allowRegex = '^(SECURITY\.md|docs/|scripts/verify-security\.ps1|\.gitleaks\.toml)'
    $secretHits = @()
    foreach ($file in $allFiles) {
        if ($file -match $allowRegex) { continue }
        if (-not (Test-Path $file -PathType Leaf)) { continue }
        $matches = Select-String -Path $file -Pattern $secretRegex -AllMatches -ErrorAction SilentlyContinue
        if ($matches) { $secretHits += $matches }
    }
    if ($secretHits.Count -gt 0) {
        $details = $secretHits | ForEach-Object { "$($_.Path):$($_.LineNumber): $($_.Line.Trim())" }
        Write-Error ("Potential secret matches detected:`n" + ($details -join "`n"))
    }
    Write-Host "OK: Zero inline secret patterns found." -ForegroundColor Green

    Write-Host "==> [3/4] Running Gitleaks (history + working tree)..." -ForegroundColor Cyan
    if (-not $SkipGitleaks) {
        $gitleaksExe = $null
        if (Get-Command gitleaks -ErrorAction SilentlyContinue) {
            $gitleaksExe = "gitleaks"
        } elseif (Test-Path "C:\Tools\Gitleaks\gitleaks.exe") {
            $gitleaksExe = "C:\Tools\Gitleaks\gitleaks.exe"
        } elseif (Test-Path "C:\Users\Pekmen\AppData\Local\Programs\Gitleaks\gitleaks.exe") {
            $gitleaksExe = "C:\Users\Pekmen\AppData\Local\Programs\Gitleaks\gitleaks.exe"
        }

        if ($null -eq $gitleaksExe) {
            Write-Warning "Gitleaks binary not found on PATH; skipping local gitleaks execution (CI runs gitleaks-action)."
        } else {
            & $gitleaksExe git . --config .gitleaks.toml
            if ($LASTEXITCODE -ne 0) {
                Write-Error "Gitleaks git history scan detected a violation!"
            }
            & $gitleaksExe dir . --config .gitleaks.toml
            if ($LASTEXITCODE -ne 0) {
                Write-Error "Gitleaks working-tree scan detected a violation!"
            }
            Write-Host "OK: Gitleaks passed (git + working directory) with 0 findings." -ForegroundColor Green
        }
    } else {
        Write-Host "Skipped Gitleaks (--SkipGitleaks)." -ForegroundColor Yellow
    }

    Write-Host "==> [4/4] Auditing merged debug AndroidManifest.xml (if built)..." -ForegroundColor Cyan
    $mergedManifest = "app\build\intermediates\merged_manifest\debug\processDebugMainManifest\AndroidManifest.xml"
    if (-not (Test-Path $mergedManifest)) {
        $mergedManifest = "app\build\intermediates\merged_manifests\debug\processDebugManifest\AndroidManifest.xml"
    }
    if (Test-Path $mergedManifest) {
        [xml]$xml = Get-Content $mergedManifest
        $perms = $xml.SelectNodes("/manifest/uses-permission") | ForEach-Object {
            $_.GetAttribute("name", "http://schemas.android.com/apk/res/android")
        }
        if ($perms -contains "android.permission.INTERNET") {
            Write-Error "SECURITY VIOLATION: android.permission.INTERNET is present in M0/M1 merged manifest!"
        }

        $appNode = $xml.SelectSingleNode("/manifest/application")
        $allowBackup = $appNode.GetAttribute("allowBackup", "http://schemas.android.com/apk/res/android")
        if ($allowBackup -ne "false") {
            Write-Error "SECURITY VIOLATION: android:allowBackup must be 'false', found '$allowBackup'."
        }
        $isDebuggable = $appNode.GetAttribute("debuggable", "http://schemas.android.com/apk/res/android")

        $exportedComponents = @()
        foreach ($tag in @("activity", "service", "receiver", "provider")) {
            $nodes = $xml.SelectNodes("/manifest/application/$tag")
            foreach ($node in $nodes) {
                $exp = $node.GetAttribute("exported", "http://schemas.android.com/apk/res/android")
                $name = $node.GetAttribute("name", "http://schemas.android.com/apk/res/android")
                if ($exp -eq "true") {
                    $exportedComponents += "${tag}:${name}"
                }
            }
        }
        $allowedExported = @(
            "activity:net.pekmen.one.MainActivity",
            "receiver:net.pekmen.one.core.platform.BootCompletedReceiver",
            "receiver:androidx.profileinstaller.ProfileInstallReceiver"
        )
        if ($isDebuggable -eq "true") {
            # Added by debugImplementation androidx.compose.ui:ui-tooling and ui-test-manifest in debug builds only
            $allowedExported += "activity:androidx.compose.ui.tooling.PreviewActivity"
            $allowedExported += "activity:androidx.activity.ComponentActivity"
        }
        $unexpected = $exportedComponents | Where-Object { $allowedExported -notcontains $_ }
        if ($unexpected) {
            Write-Error ("SECURITY VIOLATION: Unexpected exported component(s): " + ($unexpected -join ", "))
        }
        Write-Host "OK: Merged manifest audited (no INTERNET, allowBackup=false, minimal exported components)." -ForegroundColor Green
    } else {
        Write-Host "Merged manifest not yet present at $mergedManifest (run assembleDebug first)." -ForegroundColor Yellow
    }
} finally {
    Pop-Location
}
