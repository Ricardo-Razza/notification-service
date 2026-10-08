[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://127.0.0.1:14201',
    [System.Management.Automation.PSCredential]$CurrentCredential,
    [System.Management.Automation.PSCredential]$NewCredential,
    [switch]$CheckConnection
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Net.Http
$serviceUri = [Uri]($BaseUrl.TrimEnd('/') + '/')
if ($serviceUri.Scheme -notin @('http', 'https')) { throw 'Use uma URL HTTP ou HTTPS.' }
$handler = [System.Net.Http.HttpClientHandler]::new()
$handler.CookieContainer = [System.Net.CookieContainer]::new()
$client = [System.Net.Http.HttpClient]::new($handler)
$client.Timeout = [TimeSpan]::FromSeconds(15)
$authenticated = $false

function Invoke-ConsoleApi {
    param([string]$Method, [string]$Path, $Body, [switch]$AllowUnauthorized)
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($Method), [Uri]::new($serviceUri, $Path))
    $response = $null
    try {
        if ($Method -ne 'GET') {
            $csrf = $handler.CookieContainer.GetCookies($serviceUri)['XSRF-TOKEN']
            if ($null -eq $csrf) { throw 'O servidor não forneceu o cookie CSRF.' }
            [void]$request.Headers.TryAddWithoutValidation('X-XSRF-TOKEN', [Uri]::UnescapeDataString($csrf.Value))
        }
        if ($null -ne $Body) {
            $json = $Body | ConvertTo-Json -Compress
            $request.Content = [System.Net.Http.StringContent]::new($json, [System.Text.Encoding]::UTF8, 'application/json')
        }
        $response = $client.SendAsync($request).GetAwaiter().GetResult()
        $status = [int]$response.StatusCode
        if ($AllowUnauthorized -and $status -eq 401) { return $null }
        if (-not $response.IsSuccessStatusCode) {
            $message = switch ($status) {
                400 { 'Verifique o e-mail e os requisitos da senha.' }
                401 { 'Credenciais do administrador atual inválidas.' }
                403 { 'É necessário um administrador ativo e um cookie CSRF válido.' }
                409 { 'Já existe um usuário com esse e-mail.' }
                423 { 'Conta temporariamente bloqueada por tentativas de login.' }
                default { 'Não foi possível concluir a operação.' }
            }
            throw ($message + ' HTTP ' + $status)
        }
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not [string]::IsNullOrWhiteSpace($content)) { return ($content | ConvertFrom-Json) }
    } finally {
        if ($null -ne $response) { $response.Dispose() }
        $request.Dispose()
        $json = $null
    }
}

try {
    if ($null -eq $CurrentCredential) {
        $currentEmail = Read-Host 'E-mail do administrador atual (Enter: admin@example.test)'
        if ([string]::IsNullOrWhiteSpace($currentEmail)) { $currentEmail = 'admin@example.test' }
        $currentPassword = Read-Host 'Senha do administrador atual' -AsSecureString
        $CurrentCredential = [System.Management.Automation.PSCredential]::new($currentEmail, $currentPassword)
    }
    [void](Invoke-ConsoleApi -Method GET -Path 'api/admin/auth/me' -AllowUnauthorized)
    [void](Invoke-ConsoleApi -Method POST -Path 'api/admin/auth/login' -Body @{
        email = $CurrentCredential.UserName
        password = $CurrentCredential.GetNetworkCredential().Password
    })
    $authenticated = $true
    $actor = Invoke-ConsoleApi -Method GET -Path 'api/admin/auth/me'
    if ($actor.role -ne 'ADMIN') { throw 'O usuário atual precisa ter o papel ADMIN.' }
    if ($CheckConnection) {
        Write-Host 'Conexão, autenticação ADMIN e sessão CSRF verificadas.'
        return
    }

    if ($null -eq $NewCredential) {
        $newEmail = (Read-Host 'Seu e-mail para o novo administrador').Trim()
        $newPassword = Read-Host 'Sua nova senha (mínimo de 12 caracteres)' -AsSecureString
        $confirmation = Read-Host 'Confirme sua nova senha' -AsSecureString
        $NewCredential = [System.Management.Automation.PSCredential]::new($newEmail, $newPassword)
        $confirmCredential = [System.Management.Automation.PSCredential]::new($newEmail, $confirmation)
        if ($NewCredential.GetNetworkCredential().Password -cne $confirmCredential.GetNetworkCredential().Password) {
            throw 'A confirmação da senha não confere.'
        }
    }
    $email = $NewCredential.UserName.Trim()
    if ($email.Length -gt 320 -or $email -notmatch '^[^\s@]+@[^\s@]+\.[^\s@]+$') { throw 'Informe um e-mail válido.' }
    $password = $NewCredential.GetNetworkCredential().Password
    if ($password.Length -lt 12 -or [System.Text.Encoding]::UTF8.GetByteCount($password) -gt 72) {
        throw 'A senha precisa ter ao menos 12 caracteres e no máximo 72 bytes UTF-8.'
    }
    $created = Invoke-ConsoleApi -Method POST -Path 'api/admin/users' -Body @{ email = $email; password = $password; role = 'ADMIN' }
    Write-Host ('Administrador criado: ' + $created.email)
    Write-Host ('Papel: ' + $created.role)
    Write-Host 'Agora entre no painel com seu e-mail e a nova senha.'
} finally {
    if ($authenticated) {
        try { [void](Invoke-ConsoleApi -Method POST -Path 'api/admin/auth/logout' -Body @{}) } catch { }
    }
    $password = $null
    $CurrentCredential = $null
    $NewCredential = $null
    $client.Dispose()
    $handler.Dispose()
}
