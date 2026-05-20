# rename-project.ps1
# Holle TechNolle
#
# Renames an Android Studio project — all folder names, file names and file contents.
# Place in the project root folder. Run from PowerShell:
#   .\rename-project.ps1 -OldName "transpeedo" -NewName "speedover"
#
# The script is case-sensitive by default. It replaces all occurrences of OldName
# regardless of whether they appear in folder names, file names or file contents.
# Both the exact case supplied and common capitalisation variants are replaced.
#
# IMPORTANT: Close Android Studio before running. Make a backup first.

param(
    [Parameter(Mandatory)][string]$OldName,
    [Parameter(Mandatory)][string]$NewName
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

Write-Host ""
Write-Host "SpeedOver project rename" -ForegroundColor Cyan
Write-Host "Old name : $OldName"
Write-Host "New name : $NewName"
Write-Host "Root     : $root"
Write-Host ""

# Build capitalisation variants so e.g. Transpeedo → Speedover and
# TRANSPEEDO → SPEEDOVER are also handled.
$variants = @(
    @{ Old = $OldName.ToLower();                          New = $NewName.ToLower() },
    @{ Old = $OldName.ToUpper();                          New = $NewName.ToUpper() },
    @{ Old = (Get-Culture).TextInfo.ToTitleCase($OldName.ToLower()); New = (Get-Culture).TextInfo.ToTitleCase($NewName.ToLower()) },
    @{ Old = $OldName;                                    New = $NewName }
) | Select-Object -Unique

# File extensions whose contents we rewrite
$textExtensions = @(".kt", ".xml", ".gradle", ".kts", ".toml", ".properties", ".pro", ".md", ".json", ".yaml", ".yml")

# -----------------------------------------------------------------------
# 1. Rewrite file contents
# -----------------------------------------------------------------------
Write-Host "Step 1: Rewriting file contents..." -ForegroundColor Yellow

Get-ChildItem -Path $root -Recurse -File | Where-Object {
    $textExtensions -contains $_.Extension
} | ForEach-Object {
    $file    = $_.FullName
    $content = Get-Content $file -Raw -Encoding UTF8

    $changed = $false
    foreach ($v in $variants) {
        if ($content -clike "*$($v.Old)*") {
            $content = $content -creplace [regex]::Escape($v.Old), $v.New
            $changed = $true
        }
    }

    if ($changed) {
        Set-Content $file -Value $content -Encoding UTF8 -NoNewline
        Write-Host "  Updated: $($_.Name)"
    }
}

# -----------------------------------------------------------------------
# 2. Rename files
# -----------------------------------------------------------------------
Write-Host ""
Write-Host "Step 2: Renaming files..." -ForegroundColor Yellow

Get-ChildItem -Path $root -Recurse -File | Where-Object {
    $name = $_.Name
    $variants | Where-Object { $name -clike "*$($_.Old)*" }
} | Sort-Object FullName -Descending | ForEach-Object {
    $newName = $_.Name
    foreach ($v in $variants) {
        $newName = $newName -creplace [regex]::Escape($v.Old), $v.New
    }
    if ($newName -ne $_.Name) {
        Rename-Item $_.FullName -NewName $newName
        Write-Host "  $($_.Name) → $newName"
    }
}

# -----------------------------------------------------------------------
# 3. Rename folders (deepest first to avoid path conflicts)
# -----------------------------------------------------------------------
Write-Host ""
Write-Host "Step 3: Renaming folders..." -ForegroundColor Yellow

Get-ChildItem -Path $root -Recurse -Directory | Where-Object {
    $name = $_.Name
    $variants | Where-Object { $name -clike "*$($_.Old)*" }
} | Sort-Object FullName -Descending | ForEach-Object {
    $newName = $_.Name
    foreach ($v in $variants) {
        $newName = $newName -creplace [regex]::Escape($v.Old), $v.New
    }
    if ($newName -ne $_.Name) {
        Rename-Item $_.FullName -NewName $newName
        Write-Host "  $($_.Name) → $newName"
    }
}

# -----------------------------------------------------------------------
# Done
# -----------------------------------------------------------------------
Write-Host ""
Write-Host "Done. Remember to:" -ForegroundColor Green
Write-Host "  1. Rename the root project folder manually"
Write-Host "  2. Open the project in Android Studio and do File → Sync Project with Gradle Files"
Write-Host "  3. Check that the package name in AndroidManifest.xml looks correct"
Write-Host ""
