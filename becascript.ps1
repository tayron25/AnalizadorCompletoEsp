param(
    [ValidateSet('build', 'run', 'verify', 'example')]
    [string]$Action = 'run'
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$lib = Join-Path $root 'lib'
$cup = Join-Path $lib 'java-cup-11a.jar'
$jflex = Join-Path $lib 'JFlex.jar'
$generated = Join-Path $root 'build/becascript/generated'
$classes = Join-Path $root 'build/becascript/classes'

function Invoke-Jdk {
    param([string]$Executable, [string[]]$Arguments)
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable terminó con código $LASTEXITCODE" }
}

foreach ($dependency in @($cup, $jflex)) {
    if (!(Test-Path -LiteralPath $dependency)) { throw "Falta $dependency" }
}
if (!(Test-Path -LiteralPath (Join-Path $generated 'Parser.java'))) {
    New-Item -ItemType Directory -Force $generated | Out-Null
}
New-Item -ItemType Directory -Force $classes | Out-Null
Invoke-Jdk java @('-cp', $cup, 'java_cup.Main', '-destdir', $generated, '-parser', 'Parser', (Join-Path $root 'grammar/Parser.cup'))
Invoke-Jdk java @('-jar', $jflex, '--nobak', '-d', $generated, (Join-Path $root 'grammar/Lexer.flex'))
$sources = Get-ChildItem -LiteralPath $generated -Filter '*.java' |
    Select-Object -ExpandProperty FullName
Invoke-Jdk javac (@('-encoding', 'UTF-8', '-cp', $cup, '-d', $classes) + @($sources) + (Join-Path $root 'src/becascript/Main.java'))
$classpath = "$classes;$cup"
switch ($Action) {
    'run' { Invoke-Jdk java @('-cp', $classpath, 'becascript.Main') }
    'verify' { Invoke-Jdk java @('-cp', $classpath, 'becascript.Main', '--verificar') }
    'example' { Invoke-Jdk java @('-cp', $classpath, 'becascript.Main', (Join-Path $root 'ejemplos/beca-academica.beca'), 'promedio=85', 'asistencia=92') }
}
