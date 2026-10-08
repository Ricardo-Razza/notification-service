# Operação

## Notificação falhou
Localize o ID no histórico `GET /api/v1/notifications` usando a chave da aplicação. Consulte
`GET /api/v1/notifications/{id}`: `lastError` e `attemptsHistory` mostram motivo e horários.
Pesquise notificationId/requestId nos logs. Corrija template, variáveis ou SMTP e use
`POST /api/v1/notifications/{id}/retry` somente para FAILED.
Endereço inválido, mensagem malformada e SMTP 5xx são permanentes; timeout, conexão e SMTP 4xx
são transitórios. A classificação percorre causas Jakarta Mail e MailSendException.failedMessages.
Motivos são sanitizados para não armazenar conteúdo ou credenciais.

## DLQ
Abra management por túnel SSH (deploy.md). Confira notification.dlq e identifique os IDs.
JSON inválido deve ser corrigido ou removido após análise. Reprocesse FAILED pela API:
publicar a mesma mensagem sem mudar estado apenas a descarta. Depois de registrar os IDs e reprocessar,
remova os avisos correspondentes. Purge apaga todos os avisos: só faça após confirmar a análise.

## RabbitMQ/MySQL indisponível
Confira health e `docker compose --env-file .env.prod -f docker-compose.prod.yml ps`.
Não apague volumes. MySQL fora do ar impede gravações; RabbitMQ fora do ar pode causar 503 após persistir.
Reutilize a Idempotency-Key para consultar o pedido. Após recuperação, acompanhe o reconciliador:
PENDING (>2min), QUEUED (>10min), PROCESSING (>5min). PROCESSING gera FAILURE por Worker timeout.
Após 5 recuperações a notificação fica FAILED. Investigue antes de retry manual.
A entrega é pelo menos uma vez: SMTP pode aceitar e o worker cair antes de gravar SENT, causando novo envio.
Mensagens duplicadas em QUEUED têm claim atômico; SMTP/MySQL não compartilham transação distribuída.

## Simular recuperação local
No banco de desenvolvimento, desligue o listener, crie uma notificação de teste e altere status para
PENDING, QUEUED ou PROCESSING e updated_at para `UTC_TIMESTAMP(6) - INTERVAL 20 MINUTE`.
Aguarde o job (60s), confira recovery_count e tentativas. PROCESSING deve acrescentar Worker timeout;
os outros apenas republicam. Religue o listener e confira SENT no Mailpit.

## Rotacionar chave
Com X-Admin-Token, chame `POST /api/v1/admin/applications/{id}/rotate-key`. Guarde apiKey imediatamente
no gerenciador de segredos e atualize o cliente. A anterior funciona por 24h (API_KEY_GRACE_HOURS).
Para encerrá-la antes, chame `POST /api/v1/admin/applications/{id}/revoke-previous-key`.
Rotacionar novamente substitui a anterior pela atual; a mais antiga deixa de funcionar.

## Restaurar backup
Execute scripts/restore-test.sh e confira checksum/contagens. Em manutenção, pare a aplicação e faça
backup adicional. Preserve o volume original. Descompacte e importe o dump pelo cliente mysql com
credenciais no ambiente. Religue, confira Flyway/health/histórico e execute smoke-test.sh.
Backups contêm dados pessoais e hashes: acesso restrito e retenção definida.

## Criar seu próprio administrador

No computador que executa o projeto, abra PowerShell na pasta notification-service e execute:

```powershell
.\scripts\create-admin.ps1
```

O script solicita o administrador atual, seu e-mail e sua nova senha, com confirmação.
Senhas são digitadas com entrada oculta, sem argumentos de linha de comando nem armazenamento em arquivo.
Ele autentica, renova o CSRF e chama POST /api/admin/users com role=ADMIN; ao final encerra sua própria sessão.
A senha nova deve ter ao menos 12 caracteres e no máximo 72 bytes UTF-8.
Depois da criação, saia do painel e entre com a conta pessoal. E-mails duplicados são rejeitados.
Para verificar apenas conexão/login sem criar usuário, existe a opção -CheckConnection.

## Prévia por IP na rede local

A stack de testes pode publicar o painel na interface LAN mantendo os demais serviços nos bindings originais.
No PowerShell, configure o IP atual da máquina e execute:

```powershell
$env:LAN_BIND_IP='192.168.2.100'
docker compose -f docker-compose.smoke.yml -f docker-compose.lan.yml up -d --wait
```

Abra http://192.168.2.100:14201 na mesma rede. Atualize o IP se o DHCP mudá-lo.
O backend usa Mailpit e as credenciais fictícias do compose smoke; esta configuração é uma prévia local.
O proxy mantém API e painel na mesma origem, inclusive cookies e CSRF.

## Enviado no painel, mas sem entrega no destinatário

Na stack smoke, o servidor SMTP padrão é Mailpit: ele aceita e captura a mensagem, mas não a
encaminha para caixas externas. SENT significa aceitação pelo SMTP configurado. Confira as
mensagens em http://127.0.0.1:18025 na máquina que executa o projeto.

Para enviar e-mails reais mantendo o painel na LAN, copie .env.smtp.example para .env.smtp e
preencha host, porta, usuário, senha/chave SMTP e remetente autorizado pelo provedor.
O override abaixo exige autenticação com STARTTLS (normalmente porta 587). Não use a porta 465
com esta configuração. As variáveis antigas MAIL_HOST/MAIL_PORT não configuram a aplicação atual.

```powershell
$env:LAN_BIND_IP='192.168.2.100'
docker compose --env-file .env --env-file .env.smtp -f docker-compose.smoke.yml -f docker-compose.lan.yml -f docker-compose.smtp.yml up -d --wait
```

SMTP real só é ativado quando esse override é incluído; os testes automatizados continuam usando
Mailpit. Depois de configurado, crie um novo envio para um destinatário controlado: registros já
marcados SENT no Mailpit não são enviados novamente automaticamente. Consulte spam/quarentena e
logs do provedor caso o SMTP real aceite a mensagem sem entrega visível.

## Mudar TTL
Siga “Configuração e atualização das filas” no README. PRECONDITION_FAILED indica argumentos diferentes.
Esvazie e recrie somente retries afetados em manutenção; nunca apague volumes ou a fila principal.
