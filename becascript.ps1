param(
    [ValidateSet('build', 'run', 'verify', 'example')]
    [Alias('Action')]
    [string]$Accion = 'run'
)

$ErrorActionPreference = 'Stop'
$raiz = $PSScriptRoot
$bibliotecas = Join-Path $raiz 'lib'
$cup = Join-Path $bibliotecas 'java-cup-11a.jar'
$jflex = Join-Path $bibliotecas 'JFlex.jar'
$generados = Join-Path $raiz 'build/becascript/generated'
$clases = Join-Path $raiz 'build/becascript/classes'

function Invocar-Jdk {
    param([string]$Ejecutable, [string[]]$Argumentos)
    & $Ejecutable @Argumentos
    if ($LASTEXITCODE -ne 0) { throw "$Ejecutable fallo con codigo $LASTEXITCODE" }
}

foreach ($dependencia in @($cup, $jflex)) {
    if (!(Test-Path -LiteralPath $dependencia)) { throw "Falta $dependencia" }
}
if (!(Test-Path -LiteralPath (Join-Path $generados 'Parser.java'))) {
    New-Item -ItemType Directory -Force $generados | Out-Null
}
New-Item -ItemType Directory -Force $clases | Out-Null
# Las especificaciones generan las clases que se compilan con el codigo de BecaScript.
Invocar-Jdk java @('-cp', $cup, 'java_cup.Main', '-destdir', $generados, '-parser', 'Parser', (Join-Path $raiz 'grammar/Parser.cup'))
Invocar-Jdk java @('-jar', $jflex, '--nobak', '-d', $generados, (Join-Path $raiz 'grammar/Lexer.flex'))
$fuentes = Get-ChildItem -LiteralPath $generados -Filter '*.java' |
    Select-Object -ExpandProperty FullName
Invocar-Jdk javac (@('-encoding', 'UTF-8', '-cp', $cup, '-d', $clases) + @($fuentes) +
    (Join-Path $raiz 'src/becascript/Main.java') + (Join-Path $raiz 'src/becascript/Resaltado.java'))
$rutaClases = "$clases;$cup"
switch ($Accion) {
    'run' { Invocar-Jdk java @("-Dbecascript.root=$raiz", '-cp', $rutaClases, 'becascript.Main') }
    'verify' { Invocar-Jdk java @('-cp', $rutaClases, 'becascript.Main', '--verificar') }
    'example' { Invocar-Jdk java @('-cp', $rutaClases, 'becascript.Main', (Join-Path $raiz 'ejemplos/beca-academica.beca'), 'promedio=85', 'asistencia=92') }
}
