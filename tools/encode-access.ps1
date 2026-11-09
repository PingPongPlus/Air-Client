param(
    [Parameter(Mandatory = $true, ValueFromRemainingArguments = $true)]
    [string[]] $Names
)

$key = [Text.Encoding]::UTF8.GetBytes('q7AirClient!2026')
$text = ($Names | ForEach-Object { $_.Trim() } | Where-Object { $_ }) -join ','
$data = [Text.Encoding]::UTF8.GetBytes($text)
$encoded = New-Object byte[] $data.Length
for ($i = 0; $i -lt $data.Length; $i++) {
    $encoded[$i] = $data[$i] -bxor $key[$i % $key.Length]
}

$json = @{
    v = 1
    d = [Convert]::ToBase64String($encoded)
} | ConvertTo-Json -Compress

$target = Join-Path $PSScriptRoot '..\.github\access.dat'
Set-Content -LiteralPath $target -Value $json -NoNewline -Encoding UTF8
Write-Output $json
