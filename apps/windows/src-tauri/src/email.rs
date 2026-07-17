// SMTP email sender (lettre + native-tls / SChannel on Windows).
//
// The frontend composes the HTML (it has the rows, i18n and formatting); this
// module only handles delivery using the user's stored SMTP credentials.

use lettre::message::{Mailbox, MultiPart};
use lettre::transport::smtp::authentication::Credentials;
use lettre::{Message, SmtpTransport, Transport};

use crate::db::EmailConfig;

/// Send an HTML email (with a plain-text fallback) using the stored SMTP config.
pub fn send(cfg: &EmailConfig, subject: &str, html: &str, text: &str) -> Result<(), String> {
    let host = cfg
        .smtp_host
        .as_deref()
        .filter(|s| !s.is_empty())
        .ok_or("Falta el servidor SMTP")?;
    let port = cfg
        .smtp_port
        .filter(|p| *p > 0)
        .ok_or("Falta el puerto SMTP")? as u16;
    let username = cfg
        .username
        .as_deref()
        .filter(|s| !s.is_empty())
        .ok_or("Falta el usuario SMTP")?;
    let password = cfg
        .password
        .as_deref()
        .filter(|s| !s.is_empty())
        .ok_or("Falta la contraseña SMTP")?;
    let from = cfg
        .from_email
        .as_deref()
        .filter(|s| !s.is_empty())
        .unwrap_or(username);
    let to = cfg
        .to_email
        .as_deref()
        .filter(|s| !s.is_empty())
        .ok_or("Falta el email destino")?;

    let from_mb: Mailbox = from
        .parse()
        .map_err(|e| format!("Remitente inválido: {e}"))?;

    // Allow several recipients, comma- or semicolon-separated.
    let mut builder = Message::builder().from(from_mb).subject(subject);
    let mut any = false;
    for addr in to.split([',', ';']) {
        let a = addr.trim();
        if a.is_empty() {
            continue;
        }
        let mb: Mailbox = a
            .parse()
            .map_err(|e| format!("Destino inválido '{a}': {e}"))?;
        builder = builder.to(mb);
        any = true;
    }
    if !any {
        return Err("Falta el email destino".into());
    }

    let email = builder
        .multipart(MultiPart::alternative_plain_html(
            text.to_string(),
            html.to_string(),
        ))
        .map_err(|e| format!("Construcción del mensaje: {e}"))?;

    let creds = Credentials::new(username.to_string(), password.to_string());

    // Port 465 = implicit TLS; everything else (587/25) = STARTTLS.
    let builder = if port == 465 {
        SmtpTransport::relay(host).map_err(|e| format!("SMTP relay: {e}"))?
    } else {
        SmtpTransport::starttls_relay(host).map_err(|e| format!("SMTP starttls: {e}"))?
    };
    let mailer = builder.port(port).credentials(creds).build();

    mailer
        .send(&email)
        .map_err(|e| format!("Envío SMTP falló: {e}"))?;
    Ok(())
}
