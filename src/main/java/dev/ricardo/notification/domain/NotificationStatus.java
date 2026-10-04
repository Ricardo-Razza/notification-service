package dev.ricardo.notification.domain;

public enum NotificationStatus {
    SCHEDULED,   // guardada para enviar depois (V4)
    PENDING,     // criada, ainda não enfileirada
    QUEUED,      // na fila (V3)
    PROCESSING,  // sendo enviada
    SENT,        // provedor aceitou
    FAILED,      // esgotou as tentativas
    CANCELED     // cancelada antes do envio
}
