$OutputEncoding = New-Object System.Text.UTF8Encoding $false
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false
Get-CimInstance Win32_Process -Filter "Name LIKE 'java%'" | ForEach-Object {
  $cmd = ''
  if ($_.CommandLine) { $cmd = ($_.CommandLine -replace '\|','/').Replace("`r"," ").Replace("`n"," ") }
  Write-Output ($_.ProcessId.ToString() + '|' + $_.Name + '|' + $_.WorkingSetSize + '|' + $cmd)
}
