param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Username = "admin",
    [string]$Password = "123456",
    [string]$AdaptationDocx = "",
    [string]$ScriptDocx = ""
)

$ErrorActionPreference = "Stop"

$env:all_proxy = ""
$env:ALL_PROXY = ""
$env:http_proxy = ""
$env:HTTP_PROXY = ""
$env:https_proxy = ""
$env:HTTPS_PROXY = ""
$env:NO_PROXY = "localhost,127.0.0.1"

function New-DocxFixture {
    param(
        [string]$Path,
        [string]$Text
    )

    $temp = Join-Path ([System.IO.Path]::GetTempPath()) ("story-docx-" + [guid]::NewGuid())
    $wordDir = Join-Path $temp "word"
    $relsDir = Join-Path $temp "_rels"
    New-Item -ItemType Directory -Path $wordDir, $relsDir | Out-Null

    @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>
'@ | ForEach-Object { [System.IO.File]::WriteAllText((Join-Path $temp "[Content_Types].xml"), $_, [System.Text.Encoding]::UTF8) }

    @'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>
'@ | ForEach-Object { [System.IO.File]::WriteAllText((Join-Path $relsDir ".rels"), $_, [System.Text.Encoding]::UTF8) }

    $body = New-Object System.Text.StringBuilder
    foreach ($line in ($Text -split "\r?\n")) {
        $escaped = [System.Security.SecurityElement]::Escape($line)
        [void]$body.Append("<w:p><w:r><w:t xml:space=""preserve"">$escaped</w:t></w:r></w:p>")
    }

    $document = @"
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:body>
    $body
    <w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
  </w:body>
</w:document>
"@
    [System.IO.File]::WriteAllText((Join-Path $wordDir "document.xml"), $document, [System.Text.Encoding]::UTF8)

    if (Test-Path $Path) {
        Remove-Item $Path -Force
    }
    Compress-Archive -Path (Join-Path $temp "*") -DestinationPath $Path -Force
    Remove-Item $temp -Recurse -Force
}

function Assert-Equal {
    param(
        [string]$Name,
        [object]$Actual,
        [object]$Expected
    )

    if ($Actual -ne $Expected) {
        throw "$Name expected '$Expected' but got '$Actual'"
    }
    Write-Host "PASS $Name = $Actual"
}

function Prepare-UploadFile {
    param(
        [string]$SourcePath,
        [string]$TargetName
    )

    $targetPath = Join-Path $fixturesDir $TargetName
    Copy-Item -LiteralPath $SourcePath -Destination $targetPath -Force
    Set-ItemProperty -LiteralPath $targetPath -Name IsReadOnly -Value $false
    return Get-Item -LiteralPath $targetPath
}

$BaseUrl = $BaseUrl.TrimEnd("/")
$fixturesDir = Join-Path (Get-Location) "target/story-v1-fixtures"
$samplesDir = Join-Path (Get-Location) "src/test/resources/story-samples"
$adaptationSample = Join-Path $samplesDir "adaptation-equivalent.txt"
$scriptSample = Join-Path $samplesDir "short-drama-equivalent.txt"
New-Item -ItemType Directory -Path $fixturesDir -Force | Out-Null

$adaptationPath = if ($AdaptationDocx) { $AdaptationDocx } else { Join-Path $fixturesDir "改编-上岸 (1).docx" }
$scriptPath = if ($ScriptDocx) { $ScriptDocx } else { Join-Path $fixturesDir "剧本 (1).docx" }

if (-not $AdaptationDocx) {
    if (-not (Test-Path $adaptationSample)) {
        throw "Adaptation sample text not found: $adaptationSample"
    }
    New-DocxFixture -Path $adaptationPath -Text (Get-Content -LiteralPath $adaptationSample -Raw)
}

if (-not $ScriptDocx) {
    if (-not (Test-Path $scriptSample)) {
        throw "Script sample text not found: $scriptSample"
    }
    New-DocxFixture -Path $scriptPath -Text (Get-Content -LiteralPath $scriptSample -Raw)
}

$adaptationMode = if ($AdaptationDocx) { "external-file" } else { "generated-fixture" }
$scriptMode = if ($ScriptDocx) { "external-file" } else { "generated-fixture" }
Write-Host "INFO adaptation.docx = $adaptationPath ($adaptationMode)"
Write-Host "INFO script.docx = $scriptPath ($scriptMode)"
if (-not (Test-Path $adaptationPath)) {
    throw "Adaptation DOCX not found: $adaptationPath"
}
if (-not (Test-Path $scriptPath)) {
    throw "Script DOCX not found: $scriptPath"
}

$adaptationUpload = if ($AdaptationDocx) { Prepare-UploadFile -SourcePath $adaptationPath -TargetName "real-adaptation.docx" } else { Get-Item -LiteralPath $adaptationPath }
$scriptUpload = if ($ScriptDocx) { Prepare-UploadFile -SourcePath $scriptPath -TargetName "real-script.docx" } else { Get-Item -LiteralPath $scriptPath }

$login = Invoke-RestMethod -Uri "$BaseUrl/api/v1/auth/login" -Method Post -ContentType "application/json" -Body (@{
    username = $Username
    password = $Password
} | ConvertTo-Json)
$headers = @{ Authorization = "Bearer $($login.token)" }

$adaptation = Invoke-RestMethod -Uri "$BaseUrl/api/v1/story/import/file/preview" -Method Post -Headers $headers -Form @{
    title = if ($AdaptationDocx) { "改编-上岸真实样例" } else { "改编-上岸等价样例" }
    file = $adaptationUpload
}
$script = Invoke-RestMethod -Uri "$BaseUrl/api/v1/story/import/file/preview" -Method Post -Headers $headers -Form @{
    title = if ($ScriptDocx) { "剧本真实样例" } else { "剧本短剧分场稿等价样例" }
    file = $scriptUpload
}

if ($AdaptationDocx) {
    if ($adaptation.detectedType -ne "short_story" -and $adaptation.detectedType -ne "long_novel") {
        throw "adaptation.detectedType expected source novel but got '$($adaptation.detectedType)'"
    }
    Write-Host "PASS adaptation.detectedType = $($adaptation.detectedType)"

    $project = Invoke-RestMethod -Uri "$BaseUrl/api/v1/story/import/file" -Method Post -Headers $headers -Form @{
        title = "改编-上岸真实样例"
        file = $adaptationUpload
    }
    $convert = Invoke-RestMethod -Uri "$BaseUrl/api/v1/story/script/convert" -Method Post -Headers $headers -ContentType "application/json" -Body (@{
        projectId = $project.id
        targetEpisodes = 1
        useFallback = $true
    } | ConvertTo-Json)
    Assert-Equal -Name "adaptation.convert.status" -Actual $convert.status -Expected "completed"
    $draft = Invoke-RestMethod -Uri "$BaseUrl/api/v1/story/script/drafts/$($convert.draftId)" -Method Get -Headers $headers
    if (-not $draft.adaptationPlan -or -not $draft.adaptationPlan.storyCore) {
        throw "adaptation.plan expected generated storyCore but got empty adaptationPlan"
    }
    Write-Host "PASS adaptation.plan.generated = true"
} else {
    Assert-Equal -Name "adaptation.detectedType" -Actual $adaptation.detectedType -Expected "adaptation"
}

Assert-Equal -Name "script.detectedType" -Actual $script.detectedType -Expected "short_drama"

Write-Host "PASS story V1 DOCX acceptance"



