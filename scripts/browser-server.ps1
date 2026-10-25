$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
& .\mvnw.cmd -B -q test-compile dependency:build-classpath '-DincludeScope=test' '-Dmdep.outputFile=target\test-classpath.txt'
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$dependencies = (Get-Content -Raw 'target\test-classpath.txt').Trim()
$classpath = "target\test-classes;target\classes;$dependencies"
& java -Xms32m -Xmx160m -cp $classpath io.futurecode.FixtureApplication
exit $LASTEXITCODE
