param(
    [string]$ApiId = '4r80bd3omi',
    [string]$Region = 'us-east-1',
    [string[]]$AllowedOrigins = @('https://carlitos-a.github.io', 'http://localhost:4200')
)

$ErrorActionPreference = 'Stop'
if (-not (Get-Command aws -ErrorAction SilentlyContinue)) {
    throw 'Instala AWS CLI v2 y configura un perfil con permiso apigateway:GET/PATCH para esta HTTP API.'
}

$apiJson = & aws apigatewayv2 get-api --api-id $ApiId --region $Region --output json
if ($LASTEXITCODE -ne 0) { throw 'No se pudo leer la API Gateway.' }
$api = $apiJson | ConvertFrom-Json
if ($api.ProtocolType -ne 'HTTP') { throw 'Esta herramienta requiere una HTTP API de API Gateway.' }

$existing = $api.CorsConfiguration
$cors = @{
    AllowOrigins = @(@($existing.AllowOrigins) + $AllowedOrigins | Where-Object { $_ } | Select-Object -Unique)
    AllowMethods = @(@($existing.AllowMethods) + @('GET', 'OPTIONS', 'POST', 'PUT', 'PATCH') | Where-Object { $_ } | Select-Object -Unique)
    AllowHeaders = @(@($existing.AllowHeaders) + @('authorization', 'content-type') | Where-Object { $_ } | Select-Object -Unique)
}
if ($null -ne $existing.AllowCredentials) { $cors.AllowCredentials = [bool]$existing.AllowCredentials }
if ($null -ne $existing.MaxAge) { $cors.MaxAge = [int]$existing.MaxAge }
if ($existing.ExposeHeaders) { $cors.ExposeHeaders = @($existing.ExposeHeaders) }

$corsFile = Join-Path ([System.IO.Path]::GetTempPath()) (([System.IO.Path]::GetRandomFileName()) + '.json')
try {
    [System.IO.File]::WriteAllText($corsFile, ($cors | ConvertTo-Json -Compress -Depth 5),
        [System.Text.UTF8Encoding]::new($false))
    & aws apigatewayv2 update-api --api-id $ApiId --region $Region `
        --cors-configuration "file://$corsFile" --output json
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo actualizar CORS.' }
} finally {
    Remove-Item -LiteralPath $corsFile -ErrorAction SilentlyContinue
}
