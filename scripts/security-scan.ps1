# Requires PowerShell 7, Git and JDK 21 on Windows.
[CmdletBinding()]
param(
    [ValidateSet('All', 'Dependencies', 'Secrets')][string]$Scope = 'All',
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false
$repoRoot = Split-Path $PSScriptRoot -Parent
$reportDir = Join-Path $repoRoot 'artifacts/security'
$toolDir = Join-Path $repoRoot '.cache/security-tools'
$failures = [System.Collections.Generic.List[string]]::new()
New-Item -ItemType Directory -Force $reportDir, $toolDir | Out-Null

function Invoke-Check([string]$Name, [scriptblock]$Check) {
    Write-Host "Checking $Name"
    try { & $Check } catch {
        $failures.Add($Name)
        Write-Warning "$Name failed: $_"
    }
}

function Get-VerifiedTool([string]$Url, [string]$Path, [string]$Sha256) {
    if (-not (Test-Path -LiteralPath $Path)) {
        if ($Offline) { throw "Offline scan requires cached tool: $Path" }
        Invoke-WebRequest -Uri $Url -OutFile $Path
    }
    if ((Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash -ne $Sha256) {
        throw "SHA-256 mismatch for $Path; refusing to execute this download."
    }
}

Push-Location $repoRoot
try {
    if ($Scope -in @('All', 'Dependencies')) {
        $sboms = [System.Collections.Generic.List[string]]::new()
        foreach ($project in @('server', 'admin-console')) {
            Invoke-Check "Maven dependency inventory: $project" {
                $outputName = "$project-final.cdx"
                $path = Join-Path $reportDir "$outputName.json"
                $mavenArgs = @('-B', '-ntp', '-f', "$project/pom.xml",
                    'org.cyclonedx:cyclonedx-maven-plugin:2.9.2:makeAggregateBom',
                    '-DincludeTestScope=true', '-DoutputFormat=json',
                    "-DoutputDirectory=$reportDir", "-DoutputName=$outputName")
                if ($Offline) {
                    # CycloneDX requires Maven online mode, even with a populated cache.
                    # Offline validation reuses the previously resolved inventories.
                    if (-not (Test-Path -LiteralPath $path)) { throw "Missing previously generated SBOM: $path" }
                    if ((Get-Item "$project/pom.xml").LastWriteTimeUtc -gt (Get-Item $path).LastWriteTimeUtc) {
                        throw "POM changed since the cached SBOM was generated: $project"
                    }
                } else {
                    & "$repoRoot/server/mvnw.cmd" @mavenArgs
                    if ($LASTEXITCODE -ne 0) { throw "Maven SBOM generation failed for $project." }
                }
                $bom = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
                if (@($bom.components).Count -eq 0) { throw "Empty SBOM for $project." }
                foreach ($component in $bom.components) {
                    if ($component.purl -notlike 'pkg:maven/*' -or -not $component.version) {
                        throw "Unresolved Maven component in $project."
                    }
                }
                $sboms.Add($path)
            }
        }

        Invoke-Check 'OSV npm and resolved Maven dependencies (offline matching)' {
            if ($sboms.Count -ne 2) { throw 'Both successfully resolved Maven SBOMs are required.' }
            $scanner = Join-Path $toolDir 'osv-scanner-2.5.1.exe'
            Get-VerifiedTool 'https://github.com/google/osv-scanner/releases/download/v2.5.1/osv-scanner_windows_amd64.exe' $scanner '25e42f5ef6711fd8c0fb45390972205891dd44c6bd02ac93f0f63e8e98d9bfb6'
            $dbRoot = Join-Path $toolDir 'osv-db'
            # OSV-Scanner 2.5.1 uses the OSV-Scalibr cache layout.
            foreach ($ecosystem in @('Maven', 'npm')) {
                $dbDir = Join-Path $dbRoot "osv-scalibr/$ecosystem"
                New-Item -ItemType Directory -Force $dbDir | Out-Null
                $dbFile = Join-Path $dbDir 'all.zip'
                $dbUrl = "https://osv-vulnerabilities.storage.googleapis.com/$ecosystem/all.zip"
                # Download whole public databases; never send dependency inventories to OSV.
                if (-not $Offline) {
                    $download = Invoke-WebRequest -Uri $dbUrl -OutFile $dbFile -PassThru
                    @{
                        downloadedAtUtc = [DateTime]::UtcNow.ToString('o')
                        source = $dbUrl
                        lastModified = [string]$download.Headers['Last-Modified']
                        etag = [string]$download.Headers['ETag']
                        sha256 = (Get-FileHash -LiteralPath $dbFile -Algorithm SHA256).Hash.ToLowerInvariant()
                        scannerVersion = '2.5.1'
                        mode = 'offline'
                    } | ConvertTo-Json | Set-Content (Join-Path $reportDir "osv-$ecosystem-database-receipt.json") -Encoding utf8
                }
                if (-not (Test-Path -LiteralPath $dbFile)) { throw "Missing offline database: $dbFile" }
            }
            $env:OSV_SCANNER_LOCAL_DB_CACHE_DIRECTORY = $dbRoot
            $report = Join-Path $reportDir 'osv-dependencies-final.json'
            $scanArgs = @('scan', 'source', '--offline', '--all-packages', '--all-vulns', '--format=json', "--output-file=$report")
            foreach ($path in $sboms) { $scanArgs += "--lockfile=$path" }
            foreach ($relative in @('package-lock.json', 'client/package-lock.json')) {
                $scanArgs += "--lockfile=$(Join-Path $repoRoot $relative)"
            }
            & $scanner @scanArgs
            if ($LASTEXITCODE -ne 0) { throw "OSV found vulnerabilities or could not complete. See $report" }
            $scan = Get-Content -LiteralPath $report -Raw | ConvertFrom-Json
            if (@($scan.results).Count -ne 4) { throw 'Expected results for two npm lockfiles and two Maven SBOMs.' }
            foreach ($result in $scan.results) {
                if (@($result.packages).Count -eq 0) { throw "Empty scan result: $($result.source.path)" }
            }
            foreach ($path in $sboms) {
                $expected = @((Get-Content -LiteralPath $path -Raw | ConvertFrom-Json).components).Count
                $result = @($scan.results | Where-Object { $_.source.path.Replace('\', '/') -eq $path.Replace('\', '/') })
                if ($result.Count -ne 1 -or @($result[0].packages).Count -ne $expected) {
                    throw "Incomplete OSV coverage for $path (expected $expected packages)."
                }
            }
        }
    }

    if ($Scope -in @('All', 'Secrets')) {
        Invoke-Check 'Gitleaks history and working files' {
            $archive = Join-Path $toolDir 'gitleaks-8.30.1.zip'
            Get-VerifiedTool 'https://github.com/gitleaks/gitleaks/releases/download/v8.30.1/gitleaks_8.30.1_windows_x64.zip' $archive 'd29144deff3a68aa93ced33dddf84b7fdc26070add4aa0f4513094c8332afc4e'
            $extractDir = Join-Path $toolDir 'gitleaks-8.30.1'
            Expand-Archive -LiteralPath $archive -DestinationPath $extractDir -Force
            $gitleaks = Join-Path $extractDir 'gitleaks.exe'
            $historyReport = Join-Path $reportDir 'gitleaks-history-final.json'
            & $gitleaks git $repoRoot --log-opts=--all --redact --no-banner --report-format=json "--report-path=$historyReport"
            $historyExit = $LASTEXITCODE
            # Scan the current contents of tracked and nonignored untracked files, including local edits.
            $snapshot = Join-Path $toolDir ("secret-snapshot-" + [Guid]::NewGuid().ToString('N'))
            New-Item -ItemType Directory -Path $snapshot | Out-Null
            $fileList = (& git -c core.quotepath=false ls-files -z --cached --others --exclude-standard) -join "`n"
            if ($LASTEXITCODE -ne 0) { throw 'Could not enumerate working files.' }
            foreach ($relative in $fileList.Split([char]0, [StringSplitOptions]::RemoveEmptyEntries)) {
                $source = [IO.Path]::GetFullPath((Join-Path $repoRoot $relative))
                if (-not $source.StartsWith($repoRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                    throw 'Working file path escaped repository.'
                }
                if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { continue }
                if ((Get-Item -LiteralPath $source).Attributes -band [IO.FileAttributes]::ReparsePoint) {
                    throw "Review symlink before scanning: $relative"
                }
                $destination = Join-Path $snapshot $relative
                New-Item -ItemType Directory -Force (Split-Path $destination -Parent) | Out-Null
                Copy-Item -LiteralPath $source -Destination $destination
            }
            $workingReport = Join-Path $reportDir 'gitleaks-working-final.json'
            & $gitleaks dir $snapshot --redact --no-banner --report-format=json "--report-path=$workingReport"
            $workingExit = $LASTEXITCODE
            # The uniquely named snapshot is beneath the ignored tool cache; validate before removing it.
            $resolvedSnapshot = (Resolve-Path -LiteralPath $snapshot).Path
            $resolvedTools = (Resolve-Path -LiteralPath $toolDir).Path
            if (-not $resolvedSnapshot.StartsWith($resolvedTools + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
                throw 'Snapshot cleanup path escaped tool cache.'
            }
            Remove-Item -LiteralPath $resolvedSnapshot -Recurse -Force
            if ($historyExit -ne 0 -or $workingExit -ne 0) {
                throw "Gitleaks history exit $historyExit, working files exit $workingExit. Review redacted reports."
            }
        }
    }
} finally {
    Pop-Location
}

if ($failures.Count -gt 0) {
    Write-Error "Security checks failed: $($failures -join ', ')"
    exit 1
}
Write-Host 'All requested security checks completed with no findings.'
