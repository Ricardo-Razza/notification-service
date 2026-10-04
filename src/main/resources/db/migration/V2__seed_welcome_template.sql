-- Template de exemplo para testar o primeiro envio.
INSERT INTO notification_template (id, code, channel, subject, content, created_at, updated_at)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'WELCOME',
    'EMAIL',
    'Bem-vindo, {{name}}!',
    'Olá {{name}},\nseja bem-vindo ao sistema.',
    UTC_TIMESTAMP(6),
    UTC_TIMESTAMP(6)
);
