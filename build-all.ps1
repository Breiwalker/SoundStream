$ErrorActionPreference = "Stop"

$versionLine = Get-Content -LiteralPath "gradle.properties" | Where-Object { $_ -match '^mod_version=' } | Select-Object -First 1
if (-not $versionLine) {
    throw "mod_version is missing from gradle.properties"
}
$version = $versionLine.Substring("mod_version=".Length).Trim()

New-Item -ItemType Directory -Path "dist" -Force | Out-Null

Write-Output "Building SoundStream $version (one jar for Minecraft 26.1.2 - 26.3)"
.\gradlew.bat clean build --console=plain

$jar = "build\libs\SoundStream-$version.jar"
if (-not (Test-Path -LiteralPath $jar)) {
    throw "Expected jar was not produced: $jar"
}
Copy-Item -LiteralPath $jar -Destination "dist\" -Force

$paperJar = "paper\build\libs\SoundStream-Paper-$version.jar"
if (-not (Test-Path -LiteralPath $paperJar)) {
    throw "Expected Paper jar was not produced: $paperJar"
}
Copy-Item -LiteralPath $paperJar -Destination "dist\" -Force

$paperJavadocJar = "paper\build\libs\SoundStream-Paper-$version-javadoc.jar"
if (Test-Path -LiteralPath $paperJavadocJar) {
    Copy-Item -LiteralPath $paperJavadocJar -Destination "dist\" -Force
}

Write-Output "All release jars are in dist\"
